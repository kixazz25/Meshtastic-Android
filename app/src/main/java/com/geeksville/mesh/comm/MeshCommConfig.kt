package com.geeksville.mesh.comm

import com.grouptrack.comm.ApplyReport
import com.grouptrack.comm.ApplyRequest
import com.grouptrack.comm.CommConfig
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.ConfigChange
import com.grouptrack.comm.ConfigCheck
import com.grouptrack.comm.SavedConfig
import com.grouptrack.comm.SavedSetup   // SAVEDCFG-2026-10-12
import com.grouptrack.comm.ConfigDifference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.meshtastic.proto.DeviceProfile
import javax.inject.Inject

/**
 * COMMCONFIG-2026-10-12 (2.7b cycle 1 P4.2) -- the MESH implementation of CommConfig: GroupTrack says WHAT (the ride file and the
 * callsign); this decides HOW -- RadioConfigurator plans the managed values against the radio as it is, RadioConfigWriter
 * writes them in Nathan's groups with the restart waits (through MeshRadioOps), the result is verified and saved.
 * Exactly what RadioConfigScreen did before P4.2: the first save "as found", the writer, the save after titled with the ride
 * (" unverified" in the file name if a check failed). A Nucleus implementation answers the same calls its own way.
 */
@javax.inject.Singleton
class MeshCommConfig @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val radioOps: MeshRadioOps,
    private val radioConfigRepository: org.meshtastic.core.repository.RadioConfigRepository,
    private val exportProfileUseCase: org.meshtastic.core.domain.usecase.settings.ExportProfileUseCase,
    private val importProfileUseCase: org.meshtastic.core.domain.usecase.settings.ImportProfileUseCase,
    private val nodeRepository: org.meshtastic.core.repository.NodeRepository,
) : CommConfig {

    private val _report = MutableStateFlow<ApplyReport?>(null)
    override val applyReport: StateFlow<ApplyReport?> = _report.asStateFlow()

    // ------------------------------------------------------------------ helpers
    /** This radio's id for its saves ("!0ba3deda"). CODE RULE 1: null = no radio connected. */
    private fun nodeId(): String? = nodeRepository.myNodeInfo.value?.myNodeNum?.let { "!%08x".format(it) }
    private fun defaultDoc(): JSONObject = JSONObject(context.assets.open("grouptrack_default.json").bufferedReader().use { it.readText() })
    private suspend fun current(): DeviceProfile = radioConfigRepository.deviceProfileFlow.first()
    private fun load(f: java.io.File): DeviceProfile = f.inputStream().use { importProfileUseCase(it).getOrElse { e -> throw e } }
    private suspend fun saveTo(f: java.io.File) {
        val p = current()
        f.parentFile?.mkdirs()
        f.outputStream().use { exportProfileUseCase(it, p).getOrElse { e -> throw e } }
        android.util.Log.i("ConvoyProfile", "Exported profile to: ${f.absolutePath}")
    }
    /** "field: old -> new" (the configurator's change line) -> a ConfigChange. A line without an arrow ("channel key: changed")
     *  is kept whole in [ConfigChange.field] with empty from/to -- GroupTrack shows it as it is. */
    private fun change(line: String): ConfigChange {
        val i = line.indexOf(": "); val rest = if (i < 0) "" else line.substring(i + 2); val j = rest.indexOf(" -> ")
        return if (i < 0 || j < 0) ConfigChange(line, "", "") else ConfigChange(line.substring(0, i), rest.substring(0, j), rest.substring(j + 4))
    }

    // ------------------------------------------------------------------ preview
    override suspend fun previewConfig(configDoc: String, callsign: String): Pair<List<ConfigChange>, CommResult> = try {
        val v = RadioConfigurator.fromRideFile(JSONObject(configDoc), defaultDoc(), callsign)
        RadioConfigurator.build(current(), v).changes.map(::change) to CommResult.Ok
    } catch (e: Exception) {
        android.util.Log.e("RadioConfig", "APPLYTRACE: cannot use this configuration", e)
        emptyList<ConfigChange>() to CommResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    override suspend fun previewSaved(id: String): Pair<List<ConfigChange>, CommResult> = try {
        val v = RadioConfigurator.fromProfile(load(java.io.File(id)))
        RadioConfigurator.build(current(), v).changes.map(::change) to CommResult.Ok
    } catch (e: Exception) {
        android.util.Log.e("RadioConfig", "APPLYTRACE: cannot use save '$id'", e)
        emptyList<ConfigChange>() to CommResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    override suspend fun driftSinceLastSave(): List<ConfigChange> = try {
        val id = nodeId()
        val last = id?.let { RadioBackups.list(context, it).firstOrNull() }?.let(::load)
        if (last != null) ConfigCompare.differences(last, current()).map { ConfigChange(it.path, it.a, it.b) } else emptyList()
    } catch (e: Exception) { android.util.Log.w("RadioConfig", "drift: ${e.message}"); emptyList() }

    // ------------------------------------------------------------------ apply
    override suspend fun applyConfig(configDoc: String, callsign: String): CommResult {
        val r = JSONObject(configDoc).optJSONObject("ride")
        val title = r?.optString("name")?.takeIf { it.isNotBlank() && it != "null" } ?: "GroupTrack default"
        return applyRide(ApplyRequest(configDoc, callsign, title, title, r?.optString("rideId")?.takeIf { it.isNotBlank() && it != "null" }))
    }

    override suspend fun applyRide(request: ApplyRequest): CommResult {
        _report.value = null   // a new apply: the last one's report is not shown for it
        val v = try { RadioConfigurator.fromRideFile(JSONObject(request.configDoc), defaultDoc(), request.callsign) }
            catch (e: Exception) { android.util.Log.e("RadioConfig", "APPLYTRACE: cannot use this configuration", e)
                return CommResult.Failed(e.message ?: e.javaClass.simpleName) }
        return run(v, request.title, request.saveTitle, request.rideId)
    }

    override suspend fun applySaved(id: String, saveTitle: String): CommResult {
        _report.value = null   // a new apply: the last one's report is not shown for it
        val f = java.io.File(id)
        val v = try { RadioConfigurator.fromProfile(load(f)) }
            catch (e: Exception) { android.util.Log.e("RadioConfig", "APPLYTRACE: cannot use save '$id'", e)
                return CommResult.Failed(e.message ?: e.javaClass.simpleName) }
        return run(v, RadioBackups.title(f), saveTitle, RadioBackups.rideId(f))
    }

    private suspend fun run(v: ManagedValues, title: String, saveTitle: String, rideId: String?): CommResult {
        val ops = radioOps.ops() ?: return CommResult.Failed("NO_RADIO")
        val id = nodeId() ?: return CommResult.Failed("NO_RADIO")
        val log = mutableListOf<String>()
        fun publish(running: Boolean, checks: List<ConfigCheck> = emptyList(), verified: Boolean = false, stoppedAt: String? = null,
                    stopReason: String? = null, written: List<String> = emptyList(), savedAs: String? = null) {
            _report.value = ApplyReport(running, log.toList(), checks, verified, stoppedAt, stopReason, written, savedAs)
        }
        fun addLog(s: String) { android.util.Log.i("RadioConfig", s); log += s.removePrefix("RADIOWRITER: "); publish(true) }
        publish(true)
        return try {
            if (RadioBackups.list(context, id).isEmpty()) {
                val f = RadioBackups.newFile(context, id, "as-found")
                saveTo(f)
                RadioBackups.writeMeta(f, "As found", null, true)
                addLog("first backup of this radio saved: as found")
            }
            val plan = RadioConfigurator.build(current(), v)
            when (val r = RadioConfigWriter(ops, ::addLog).apply(plan, v)) {
                is WriteResult.Done -> {
                    val f = RadioBackups.newFile(context, id, title + if (r.verified) "" else " unverified")
                    saveTo(f)
                    RadioBackups.writeMeta(f, saveTitle, rideId, r.verified)   // SAVETITLE: the ride's own title, carried forward
                    val checks = r.checks.map { ConfigCheck(it.field, it.expected, it.actual) }
                    publish(false, checks, r.verified, written = r.groupsWritten, savedAs = RadioBackups.describe(f))
                    CommResult.Applied(r.verified, checks.filter { !it.ok }.map { it.field })
                }
                is WriteResult.Stopped -> {
                    publish(false, stoppedAt = r.group, stopReason = r.reason, written = r.groupsWritten)
                    CommResult.Failed("STOPPED")
                }
            }
        } catch (e: Exception) {
            addLog("ERROR: ${e.message}")
            publish(false)
            CommResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ saved setups (P4.3 completes these)
    override suspend fun savedConfigs(): List<SavedConfig> =
        nodeId()?.let { id -> RadioBackups.list(context, id).map { SavedConfig(it.absolutePath, RadioBackups.title(it), it.lastModified()) } } ?: emptyList()

    override suspend fun compareSaved(a: String, b: String): Pair<List<ConfigChange>, CommResult> = try {
        ConfigCompare.differences(load(java.io.File(a)), load(java.io.File(b)), includeManaged = true)
            .map { ConfigChange(it.path, it.a, it.b) } to CommResult.Ok
    } catch (e: Exception) { emptyList<ConfigChange>() to CommResult.Failed(e.message ?: e.javaClass.simpleName) }

    // RADIONET-2026-10-12: the radio's own answer -- the primary channel's name from the settings it reported. null if not read yet.
    override suspend fun currentNetwork(): String? = try {
        if (nodeId() == null) null else RadioConfigurator.primaryChannel(current()).name
    } catch (e: Exception) { android.util.Log.i("RadioConfig", "RADIONET-2026-10-12: network not known yet (${e.message})"); null }

    // SAVEDCFG-2026-10-12 (P4.3): the saved-configs screen's list and comparison.
    override suspend fun savedSetups(): List<SavedSetup> = nodeId()?.let { id ->
        RadioBackups.list(context, id).map { SavedSetup(it.absolutePath, RadioBackups.title(it), RadioBackups.detail(it), it.lastModified(), RadioBackups.rideId(it)) }
    } ?: emptyList()

    private suspend fun profileOf(id: String): DeviceProfile = if (id == CommConfig.RADIO_NOW) current() else load(java.io.File(id))

    override suspend fun compareConfigs(a: String, b: String): Pair<List<ConfigDifference>, CommResult> = try {
        ConfigCompare.differences(profileOf(a), profileOf(b), includeManaged = true)
            .map { ConfigDifference(it.path, it.a, it.b, it.path in ConfigCompare.MANAGED) } to CommResult.Ok
    } catch (e: Exception) {
        android.util.Log.w("RadioConfig", "SAVEDCFG-2026-10-12: cannot compare: ${e.message}")
        emptyList<ConfigDifference>() to CommResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    override suspend fun restoreSaved(id: String): CommResult = CommResult.NotSupported   // P4.3
    override suspend fun networkLink(): Pair<String?, CommResult> = null to CommResult.NotSupported   // Nucleus: not built
}
