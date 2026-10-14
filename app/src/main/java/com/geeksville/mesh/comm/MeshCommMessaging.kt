package com.geeksville.mesh.comm

import com.grouptrack.comm.CommEvent
import com.grouptrack.comm.CommMessage
import com.grouptrack.comm.CommMessaging
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.Member
import com.grouptrack.comm.ReportingRequest
import com.grouptrack.comm.ReportingStep   // COMMREPORT-2026-10-12
import com.grouptrack.comm.Role
import org.meshtastic.core.data.manager.TakRoleStore
import org.meshtastic.core.data.manager.TakSeenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first   // COMMREPORT-2026-10-12
import kotlinx.coroutines.flow.map   // REPORTNOW-2026-10-13
import kotlinx.coroutines.flow.shareIn   // HEARDEVENTS-2026-10-14
import javax.inject.Inject

/**
 * COMMSEND-2026-10-08 (2.7b cycle 1 step 2a, design v16 §R6.5) -- the MESH implementation of CommMessaging.
 * Lives in the app module (the comm side); GroupTrack only sees the contract (com.grouptrack.comm).
 * Step 2a = SEND only: deliver() turns a CoT CommMessage into today's TAK V1 report (port 72) through the translator --
 * the bytes on the air are unchanged. Receive (events: roles, the hand-off heartbeat) and plain text come in step 2b;
 * until then they answer NotSupported / nothing (contract rule R3).
 */
@javax.inject.Singleton   // COMMREPORT-2026-10-12: one instance -- the reporting change runs here, not in a screen's scope
class MeshCommMessaging @Inject constructor(
    private val radioController: org.meshtastic.core.model.RadioController,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,   // COMMREPORT-2026-10-12
    private val radioOps: MeshRadioOps,   // COMMREPORT-2026-10-12: the radio adapter (keep-alive held while it is used)
    private val nodeRepository: org.meshtastic.core.repository.NodeRepository,   // HEARDSHADOW-2026-10-13: what the radio has heard
    private val radioConfigRepository: org.meshtastic.core.repository.RadioConfigRepository,   // REPORTNOW-2026-10-13: read only
) : CommMessaging {

    override suspend fun deliver(message: CommMessage): CommResult = try {
        radioController.sendMessage(MeshTakTranslator.toV1DataPacket(message))
        CommResult.Ok
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        CommResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    override suspend fun deliverText(text: String): CommResult = CommResult.NotSupported      // step 2b
    override suspend fun setReporting(request: ReportingRequest): CommResult = setReporting(request) {}

    /**
     * COMMREPORT-2026-10-12 (P4.4) -- GPSPANEL-2026-09-29 moved from ConvoyViewModel.applyLocationInterval, calls/values/waits unchanged:
     * the radio's FULL position section with the ride's standard values (GroupTrack default network.standalone, GPSRIDE) and ONLY
     * the moving interval from the request; begin -> write -> commit; 3 s -> disconnect -> 20 s (GPSRECON20) -> reconnect; then
     * GPSCYCLE2: wait for Connected (max 60 s) -> settleWaitSecs -> disconnect -> reconnectGapSecs -> reconnect.
     * Every managed field is set explicitly, so a zero read back is never written (09-29). Runs on the caller's scope.
     * Failed reasons: NO_RADIO, NO_DEFAULT (the default settings unreadable -- nothing written), else the error text.
     */
    override suspend fun setReporting(request: ReportingRequest, onStep: (ReportingStep) -> Unit): CommResult {
        val ops = radioOps.ops() ?: return CommResult.Failed("NO_RADIO")
        val secs = request.meshIntervalSecs
        val st = runCatching {
            org.json.JSONObject(context.assets.open("grouptrack_default.json").bufferedReader().use { it.readText() })
                .getJSONObject("network").getJSONObject("standalone")
        }.getOrNull() ?: return CommResult.Failed("NO_DEFAULT")
        var disconnected = false
        return try {
            // CODE RULE 1: the radio's position section may not be read yet (null) -- the managed fields are all set below.
            val pos = ops.retrieve().config?.position ?: org.meshtastic.proto.Config.PositionConfig()
            val newPos = pos.copy(
                position_broadcast_secs = request.stationaryIntervalSecs,
                position_broadcast_smart_enabled = st.getBoolean("smartPositionEnabled"),
                broadcast_smart_minimum_interval_secs = secs,
                broadcast_smart_minimum_distance = st.getInt("smartMinDistanceMeters"),
                gps_mode = org.meshtastic.proto.Config.PositionConfig.GpsMode.valueOf(st.getString("gpsMode")),
                gps_update_interval = st.getInt("gpsUpdateSecs"),
                fixed_position = st.getBoolean("fixedPosition"),
            )
            val was = pos.broadcast_smart_minimum_interval_secs
            if (newPos == pos) { onStep(ReportingStep.UNCHANGED); return CommResult.Ok }
            android.util.Log.i("GPSINT", "apply smart ${was}s -> ${secs}s, standing ${pos.position_broadcast_secs}s -> " +
                "${newPos.position_broadcast_secs}s at ${System.currentTimeMillis()}")
            onStep(ReportingStep.WRITING)
            ops.beginEdit()
            ops.writeConfig(org.meshtastic.proto.Config(position = newPos))
            ops.commitEdit()
            android.util.Log.i("GPSINT", "written at ${System.currentTimeMillis()}")
            onStep(ReportingStep.WRITTEN)
            kotlinx.coroutines.delay(3_000)
            ops.disconnect(); disconnected = true
            android.util.Log.i("GPSINT", "disconnect at ${System.currentTimeMillis()}")
            onStep(ReportingStep.RESTARTING)
            kotlinx.coroutines.delay(20_000)   // GPSRECON20-2026-09-29 (Fred): 10 s was too short for the radio to restart
            ops.reconnect(); disconnected = false
            android.util.Log.i("GPSINT", "reconnect at ${System.currentTimeMillis()}")
            // GPSCYCLE2-2026-09-30 (Fred): once the reconnect has CONNECTED, one more cycle -- wait -> disconnect -> gap -> reconnect.
            onStep(ReportingStep.RECONNECTING_AGAIN)
            val firstUp = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                ops.connection.first { it == org.meshtastic.core.model.ConnectionState.Connected }
            } != null
            android.util.Log.i("GPSINT", "cycle2 first reconnect connected=$firstUp wait=${request.settleWaitSecs}s " +
                "gap=${request.reconnectGapSecs}s at ${System.currentTimeMillis()}")   // GPSTIMING-2026-09-30
            kotlinx.coroutines.delay(request.settleWaitSecs * 1000L)
            ops.disconnect(); disconnected = true
            android.util.Log.i("GPSINT", "cycle2 disconnect at ${System.currentTimeMillis()}")
            kotlinx.coroutines.delay(request.reconnectGapSecs * 1000L)
            ops.reconnect(); disconnected = false
            android.util.Log.i("GPSINT", "cycle2 reconnect at ${System.currentTimeMillis()}")
            onStep(ReportingStep.DONE)
            CommResult.Ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("GPSINT", "failed: ${e.message}", e)
            if (disconnected) runCatching { ops.reconnect() }.onSuccess { android.util.Log.i("GPSINT", "reconnect after failure") }
            CommResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    // HEARDEVENTS-2026-10-14 (tick replacement T3): what the radio hears, as it is heard. A member's record changing in the node database
    // (any packet moves lastHeard; a position moves the fix) = one MemberHeard -- the same Member members() returns. Hot, no replay.
    private val eventScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    override val events: Flow<CommEvent> = kotlinx.coroutines.flow.flow {
        var last = emptyMap<Int, org.meshtastic.core.model.Node>()
        nodeRepository.nodeDBbyNum.collect { db ->
            for ((num, n) in db) {
                val p = last[num]
                if (p == null || p.lastHeard != n.lastHeard || p.position != n.position || p.user != n.user || p.deviceMetrics != n.deviceMetrics) {
                    emit(CommEvent.MemberHeard(memberOf(n)))
                }
            }
            last = db
        }
    }.shareIn(eventScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), 0)
    // REPORTNOW-2026-10-13 (P5.2): the value ChannelViewModel showed (position.broadcast_smart_minimum_interval_secs). Passive -- no radio call.
    // CODE RULE 1: the proto field is optional; 0 / missing = not read yet -> null.
    override val currentReportingSecs: Flow<Int?> = radioConfigRepository.localConfigFlow
        .map { cfg -> cfg.position?.broadcast_smart_minimum_interval_secs?.takeIf { it > 0 } }
    /**
     * HEARDSHADOW-2026-10-13 (P5.1a) -- what the radio has heard, RAW: one Member per node in Meshtastic's node database. No filtering:
     * the admission window, stale fixes, removed carts, the previous-tick hold and speed are GroupTrack's display rules (Fred
     * 10-13, D3). Seconds -> milliseconds here (the one place). memberId = node number as text (as reportedRole / memberName).
     */
    override fun members(): List<Member> {
        // CODE RULE 1: the node database may be unreadable (radio service not up yet) -- then nothing has been heard.
        val nodes = runCatching { nodeRepository.nodeDBbyNum.value }.getOrNull() ?: return emptyList()
        return nodes.values.map { memberOf(it) }
    }

    private fun memberOf(node: org.meshtastic.core.model.Node): Member {
        val id = node.num.toString()
        val pos = node.position
        val latI = pos.latitude_i ?: 0
        val lonI = pos.longitude_i ?: 0
        val hasPos = latI != 0 || lonI != 0
        val fixMs = (pos.time ?: 0).toLong() * 1000L
        return Member(
            id = id,
            // RADIONAMES-2026-10-03: the name last used (radio name table), else the node database's long / short name.
            callsign = memberName(id) ?: node.user.long_name.ifBlank { node.user.short_name }.ifBlank { "!${node.num}" },
            staleAtMs = null,                                   // CODE RULE 1: the mesh report carries no CoT stale yet (tick replacement)
            latitude = if (hasPos) latI * 1e-7 else null,       // CODE RULE 1 (lat, lon): null = no position from this radio (0/0)
            longitude = if (hasPos) lonI * 1e-7 else null,
            altitudeM = pos.altitude,                           // metres; null = not reported
            fixTimeMs = if (fixMs > 0L) fixMs else null,        // CODE RULE 1: null = no fix time reported
            lastHeardMs = node.lastHeard.toLong() * 1000L,
            reportedRole = reportedRole(id),
            channelUtilPct = node.deviceMetrics.channel_utilization,
            headingDeg = pos.ground_track?.let { ((it.toFloat() / 100000f) % 360f + 360f) % 360f },
            batteryPct = node.deviceMetrics.battery_level,
        )
    }

    // COMMRECV-2026-10-08 (step 2b): what Meshtastic's receive path recorded (MeshDataHandlerImpl -> TakRoleStore /
    // TakSeenStore, unchanged), read through the translator's role table. memberId = node number as text.
    override fun reportedRole(memberId: String): Role? =
        memberId.toIntOrNull()?.let { MeshTakTranslator.roleFromName(TakRoleStore.roleOf(it)) }
    override fun lastReportFromMs(memberId: String): Long? =
        memberId.toIntOrNull()?.let { TakSeenStore.lastFrom(it) }
    override fun memberName(memberId: String): String? =   // COMMNAMES-2026-10-11
        memberId.toIntOrNull()?.let { org.meshtastic.core.data.manager.RadioNameStore.nameOf(it) }
    override fun recordOwnRole(memberId: String, role: Role) {
        memberId.toIntOrNull()?.let { TakRoleStore.put(it, MeshTakTranslator.memberRole(role).name) }
    }
    override fun clearRoles() = TakRoleStore.clear()
}
