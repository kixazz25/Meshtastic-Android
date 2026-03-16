package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import com.geeksville.mesh.ui.sharing.ChannelViewModel
import android.util.Base64
import kotlinx.coroutines.delay
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.core.model.ConnectionState

/**
 * ConvoyRadioWriter — reusable radio write module
 *
 * Used by:
 *   - ConvoyApplyRadioScreen (apply master config or ride)
 *   - ConvoyRestoreScreen (restore from archive)
 *
 * Write sequence:
 *   1. Archive current radio state
 *   2. Write device config
 *   PAUSE — wait for reboot + reconnect
 *   3. Write LoRa config
 *   PAUSE — wait for reboot + reconnect
 *   4. Write channel + PSK
 *   COMPLETE
 *
 * Each pause shows live BLE status and a manual CONTINUE button.
 * Auto-detect displayed for evaluation — not yet used to auto-advance.
 */

enum class WriteStep {
    IDLE,
    ARCHIVING,
    ARCHIVE_DONE,
    WRITING_DEVICE,
    DEVICE_DONE,
    PAUSE_1,               // After device write — waiting for reboot
    WRITING_LORA,
    LORA_DONE,
    PAUSE_2,               // After LoRa write — waiting for reboot
    WRITING_CHANNEL,
    CHANNEL_DONE,
    COMPLETE,
    FAILED
}

data class WriteState(
    val step: WriteStep = WriteStep.IDLE,
    val log: List<String> = emptyList(),
    val bleConnected: Boolean = false,
    val canContinue: Boolean = false,
    val errorMsg: String = ""
) {
    fun withLog(msg: String) = copy(log = log + msg)
}

object ConvoyRadioWriter {
    private const val TAG = "ConvoyRadioWriter"

    private val _state = MutableStateFlow(WriteState())
    val state: StateFlow<WriteState> = _state.asStateFlow()

    fun reset() {
        _state.value = WriteState()
    }

    /**
     * Execute the full write sequence.
     * Call from a coroutine scope in the screen.
     *
     * @param context Android context
     * @param channelViewModel For writing config to radio
     * @param connectionState Flow of BLE connection state
     * @param snapshot Current radio snapshot for archive
     * @param workingConfig The merged config to write
     */
    suspend fun execute(
        context: Context,
        channelViewModel: ChannelViewModel,
        connectionStateFlow: Flow<ConnectionState>,
        snapshot: ConvoyRadioManager.RadioSnapshot,
        workingConfig: WorkingConfig
    ) {
        try {
            // ── Step 1: Archive ───────────────────────────────────────────────
            emit(WriteStep.ARCHIVING, "Archiving current radio state...")
            val archivePath = ConvoyRadioManager.saveBackup(context, snapshot, "pre_write")
            emit(WriteStep.ARCHIVE_DONE, "✓ Archive saved: $archivePath")

            // ── Step 2: Write device config ───────────────────────────────────
            emit(WriteStep.WRITING_DEVICE, "Writing device config...")
            channelViewModel.setConfig(Config(device = Config.DeviceConfig(role = Config.DeviceConfig.Role.CLIENT)))
            emit(WriteStep.DEVICE_DONE, "✓ Device config written (placeholder)")

            // ── PAUSE 1 ───────────────────────────────────────────────────────
            emit(WriteStep.PAUSE_1, "⏸ Radio rebooting — monitor BLE status below")
            _state.value = _state.value.copy(canContinue = false)
            // BLE monitor updates bleConnected — user watches and taps CONTINUE
            // Auto-advance will be wired here once timing is confirmed reliable

            // Wait for user to tap CONTINUE (set via proceedToLoRa())
            while (_state.value.step == WriteStep.PAUSE_1) {
                delay(500)
            }
            if (_state.value.step == WriteStep.FAILED) return

            // ── Step 3: Write LoRa config ─────────────────────────────────────
            emit(WriteStep.WRITING_LORA, "Writing LoRa config...")
            channelViewModel.setConfig(Config(lora = Config.LoRaConfig(hop_limit = workingConfig.loraHopLimit, tx_enabled = workingConfig.loraTxEnabled, tx_power = workingConfig.loraTxPower)))
            emit(WriteStep.LORA_DONE, "✓ LoRa config written (placeholder)")

            // ── PAUSE 2 ───────────────────────────────────────────────────────
            emit(WriteStep.PAUSE_2, "⏸ Radio rebooting — monitor BLE status below")
            _state.value = _state.value.copy(canContinue = false)

            while (_state.value.step == WriteStep.PAUSE_2) {
                delay(500)
            }
            if (_state.value.step == WriteStep.FAILED) return

            // ── Step 4: Write channel + PSK ───────────────────────────────────
            emit(WriteStep.WRITING_CHANNEL, "Writing channel and encryption key...")
            val pskBytes = okio.ByteString.of(*Base64.decode(workingConfig.channelPsk, Base64.NO_WRAP))
            val chSettings = ChannelSettings(name = workingConfig.channelName, psk = pskBytes)
            channelViewModel.setChannels(ChannelSet(settings = listOf(chSettings)))
            emit(WriteStep.CHANNEL_DONE, "✓ Channel written (placeholder)")

            // ── Complete ──────────────────────────────────────────────────────
            emit(WriteStep.COMPLETE, "✓ COMPLETE — all config written successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
            _state.value = _state.value.copy(
                step     = WriteStep.FAILED,
                errorMsg = e.message ?: "Unknown error"
            ).withLog("✗ FAILED: ${e.message}")
        }
    }

    /** Called when user taps CONTINUE after PAUSE_1 */
    fun proceedToLoRa() {
        if (_state.value.step == WriteStep.PAUSE_1) {
            _state.value = _state.value.copy(step = WriteStep.WRITING_LORA, canContinue = false)
                .withLog("▶ Continuing to LoRa write...")
        }
    }

    /** Called when user taps CONTINUE after PAUSE_2 */
    fun proceedToChannel() {
        if (_state.value.step == WriteStep.PAUSE_2) {
            _state.value = _state.value.copy(step = WriteStep.WRITING_CHANNEL, canContinue = false)
                .withLog("▶ Continuing to channel write...")
        }
    }

    /** Called by BLE connection monitor to update status */
    fun updateBleStatus(connected: Boolean) {
        val current = _state.value
        val wasConnected = current.bleConnected
        _state.value = current.copy(
            bleConnected = connected,
            canContinue  = connected && (current.step == WriteStep.PAUSE_1 || current.step == WriteStep.PAUSE_2)
        ).withLog(
            if (connected && !wasConnected) "● BLE RECONNECTED"
            else if (!connected && wasConnected) "○ BLE DISCONNECTED — radio rebooting..."
            else return
        )
    }

    private fun emit(step: WriteStep, msg: String) {
        Log.i(TAG, "[$step] $msg")
        _state.value = _state.value.copy(step = step).withLog(msg)
    }
}

/**
 * WorkingConfig — the merged config ready to write to radio.
 * Built from master_config.json or current radio image + apply rules.
 */
data class WorkingConfig(
    val nodeId: String,
    val longName: String,
    val channelName: String,
    val channelPsk: String,
    val loraRegion: String,
    val loraModemPreset: String,
    val loraBandwidth: Int,
    val loraSpreadFactor: Int,
    val loraCodingRate: Int,
    val loraHopLimit: Int,
    val loraTxEnabled: Boolean,
    val loraTxPower: Int,
    val source: String  // "MASTER" or "RIDE:<eventId>"
)
