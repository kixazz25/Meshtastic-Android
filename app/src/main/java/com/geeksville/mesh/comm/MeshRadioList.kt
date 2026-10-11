package com.geeksville.mesh.comm

import com.grouptrack.comm.CommResult
import com.grouptrack.comm.RadioEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import org.meshtastic.core.ble.MeshtasticBleConstants
import org.meshtastic.core.data.manager.RadioNameStore
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * COMMRADIOS-2026-10-11 (2.7b cycle 1 P2b) -- THE RADIO LIST, on the comm side. GRP Awareness draws its rows from [radios].
 *   PAIRED first: Bluetooth radios Android has paired (Meshtastic's filter; BluetoothRepository, kept current by COMMBOND)
 *   and USB serial radios (paired = permission granted); then radios FOUND NEARBY that Android has not paired (PAIR).
 *   Names: the name table (the name a radio last used), else its Bluetooth / device name.
 * The scan runs only while GroupTrack asks (GRP Awareness open): Meshtastic's BleScanner, ONE long window (5 min),
 * restarted only if it ends while still asked -- Android allows an app 5 scan starts per 30 s.
 * The scanned radios are kept so that CommLink.connect can PAIR a new one.
 */
@javax.inject.Singleton
class MeshRadioList @Inject constructor(
    private val bluetoothRepository: org.meshtastic.core.ble.BluetoothRepository,
    private val bleScanner: org.meshtastic.core.ble.BleScanner,
    private val usbRepository: com.geeksville.mesh.repository.usb.UsbRepository,
    private val usbManager: dagger.Lazy<android.hardware.usb.UsbManager>,
    private val radioInterfaceService: org.meshtastic.core.repository.RadioInterfaceService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val namePattern = Regex(MeshtasticBleConstants.BLE_NAME_PATTERN)
    /** Radios found nearby in this scan session, by Bluetooth address. */
    private val found = MutableStateFlow<Map<String, Peripheral>>(emptyMap())
    @Volatile private var scanJob: Job? = null

    private fun usable(a: String?): String? = a?.takeIf { it.isNotBlank() && it != "n" }

    /** The radio this device uses. CODE RULE 1: null = none chosen. */
    val selectedRadioId: StateFlow<String?> = radioInterfaceService.currentDeviceAddressFlow
        .map { usable(it) }
        .stateIn(scope, SharingStarted.Eagerly, usable(radioInterfaceService.currentDeviceAddressFlow.value))

    private fun nameOf(id: String, deviceName: String?): String =
        RadioNameStore.nameForAddress(id)
            ?: deviceName?.let { RadioNameStore.nameForBluetoothName(it) }
            ?: deviceName ?: id.drop(1)

    val radios: StateFlow<List<RadioEntry>> =
        combine(bluetoothRepository.state, usbRepository.serialDevices, found) { bt, usb, nearby ->
            val paired = bt.bondedDevices.map { p -> RadioEntry("x" + p.address, nameOf("x" + p.address, p.name), true, null) }
            val pairedIds = paired.map { it.id }.toSet()
            val usbRadios = usb.map { (_, d) ->
                val e = com.geeksville.mesh.model.DeviceListEntry.Usb(radioInterfaceService, usbManager.get(), d)
                RadioEntry(e.fullAddress, e.name, e.bonded, null)
            }
            // COMMBTOFF-2026-10-11: nothing found nearby is listed while Bluetooth is off (Android's paired list then reads empty,
            // and a paired radio from the last scan showed as NEW / PAIR).
            val newOnes = (if (bt.enabled) nearby.values.toList() else emptyList()).filter { "x" + it.address !in pairedIds }
                .map { p -> RadioEntry("x" + p.address, nameOf("x" + p.address, p.name), false, null) }
            (paired + usbRadios + newOnes).sortedWith(compareBy({ !it.paired }, { it.name.lowercase() }))   // PAIRED FIRST (Fred 10-11)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // COMMBTOFF-2026-10-11: GroupTrack asked for the list (GRP Awareness open). The scan FOLLOWS BLUETOOTH: it starts when Bluetooth
    // comes on and stops -- dropping the radios found nearby -- when it goes off. scanProblem follows it live.
    @Volatile private var wanted = false

    private fun problemOf(st: org.meshtastic.core.ble.BluetoothState): String? = when {
        !st.hasPermissions -> "PERMISSION_MISSING"
        !st.enabled -> "BLUETOOTH_OFF"
        else -> null
    }

    /** Why radios nearby cannot be looked for, live. CODE RULE 1: null = nothing wrong. */
    val scanProblem: StateFlow<String?> = bluetoothRepository.state.map { problemOf(it) }
        .stateIn(scope, SharingStarted.Eagerly, problemOf(bluetoothRepository.state.value))

    init {
        scope.launch {
            bluetoothRepository.state.collect { st ->
                if (!wanted) return@collect
                val p = problemOf(st)
                if (p == null) beginScan()
                else if (scanJob?.isActive == true) {
                    android.util.Log.i("COMMRADIOS", "scan paused: $p")
                    scanJob?.cancel(); scanJob = null; found.value = emptyMap()
                }
            }
        }
    }

    /** Starts looking for radios nearby (GroupTrack: GRP Awareness opened). Answers at once; if Bluetooth is off the scan
     *  starts by itself when it comes on while the list is still wanted. */
    fun startScan(): CommResult {
        wanted = true
        bluetoothRepository.refreshState()
        val p = problemOf(bluetoothRepository.state.value)
        if (p != null) {
            android.util.Log.i("COMMRADIOS", "scan: $p")
            if (scanJob?.isActive != true) found.value = emptyMap()   // radios from an earlier scan are not shown
            return CommResult.Failed(p)
        }
        beginScan()
        return CommResult.Ok
    }

    @Synchronized private fun beginScan() {
        if (scanJob?.isActive == true) return
        found.value = emptyMap()
        scanJob = scope.launch {
            android.util.Log.i("COMMRADIOS", "scan started")
            while (isActive) {
                try {
                    bleScanner.scan(5.minutes).collect { p ->
                        if (p.name?.matches(namePattern) == true && p.address !in found.value) {
                            found.value = found.value + (p.address to p)
                            android.util.Log.i("COMMRADIOS", "found nearby: ${p.name} ${p.address}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("COMMRADIOS", "scan failed: ${e.message}")
                    delay(10_000L)
                }
            }
        }
    }

    /** Stops looking (GroupTrack: GRP Awareness closed). */
    fun stopScan() {
        wanted = false   // COMMBTOFF-2026-10-11
        if (scanJob?.isActive == true) android.util.Log.i("COMMRADIOS", "scan stopped")
        scanJob?.cancel(); scanJob = null
    }

    /** The scanned radio itself, to pair it. CODE RULE 1: null = not found in this scan session. */
    fun scanned(radioId: String): Peripheral? = found.value[radioId.removePrefix("x")]
}
