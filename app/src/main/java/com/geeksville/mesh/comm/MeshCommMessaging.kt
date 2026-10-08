package com.geeksville.mesh.comm

import com.grouptrack.comm.CommEvent
import com.grouptrack.comm.CommMessage
import com.grouptrack.comm.CommMessaging
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.Member
import com.grouptrack.comm.ReportingRequest
import com.grouptrack.comm.Role
import org.meshtastic.core.data.manager.TakRoleStore
import org.meshtastic.core.data.manager.TakSeenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject

/**
 * COMMSEND-2026-10-08 (2.7b cycle 1 step 2a, design v16 §R6.5) -- the MESH implementation of CommMessaging.
 * Lives in the app module (the comm side); GroupTrack only sees the contract (com.grouptrack.comm).
 * Step 2a = SEND only: deliver() turns a CoT CommMessage into today's TAK V1 report (port 72) through the translator --
 * the bytes on the air are unchanged. Receive (events: roles, the hand-off heartbeat) and plain text come in step 2b;
 * until then they answer NotSupported / nothing (contract rule R3).
 */
class MeshCommMessaging @Inject constructor(
    private val radioController: org.meshtastic.core.model.RadioController,
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
    override suspend fun setReporting(request: ReportingRequest): CommResult = CommResult.NotSupported
    override val events: Flow<CommEvent> = emptyFlow()                                        // cycle 3 (tick removal)
    override fun members(): List<Member> = emptyList()                                       // cycle 3 (tick removal)

    // COMMRECV-2026-10-08 (step 2b): what Meshtastic's receive path recorded (MeshDataHandlerImpl -> TakRoleStore /
    // TakSeenStore, unchanged), read through the translator's role table. memberId = node number as text.
    override fun reportedRole(memberId: String): Role? =
        memberId.toIntOrNull()?.let { MeshTakTranslator.roleFromName(TakRoleStore.roleOf(it)) }
    override fun lastReportFromMs(memberId: String): Long? =
        memberId.toIntOrNull()?.let { TakSeenStore.lastFrom(it) }
    override fun recordOwnRole(memberId: String, role: Role) {
        memberId.toIntOrNull()?.let { TakRoleStore.put(it, MeshTakTranslator.memberRole(role).name) }
    }
    override fun clearRoles() = TakRoleStore.clear()
}
