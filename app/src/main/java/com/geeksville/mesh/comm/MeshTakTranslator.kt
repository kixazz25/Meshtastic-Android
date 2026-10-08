package com.geeksville.mesh.comm

import com.grouptrack.comm.CommMessage
import com.grouptrack.comm.Role
import org.meshtastic.core.model.DataPacket
import org.meshtastic.proto.Contact
import org.meshtastic.proto.Group
import org.meshtastic.proto.MemberRole
import org.meshtastic.proto.PLI
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.TAKPacket
import org.meshtastic.proto.Team

/**
 * COMMSEND-2026-10-08 -- THE TRANSLATOR (design §R5.2 row 8, §R6.3): the one place that knows how a CoT message looks
 * on a Meshtastic radio. GroupTrack speaks CoT; this turns it into the wire form. Cycle 3 (tick removal) changes only
 * this component.
 * Step 2a: CoT -> TAK V1 position report (port 72), field for field what GroupTrack sent before (ROLEREPORT-2026-09-29):
 * uncompressed, contact = callsign (twice), group = role + team, PLI = lat/lon in 1e-7 degrees. CoT fields V1 cannot carry
 * (uid, type, how, time/start/stale, the GroupTrack detail) are not sent.
 * Role mapping (the 2.7a table): LEADER -> TeamLead · MIDDLE -> RTO · TAIL_GUNNER -> ForwardObserver · RIDER -> HQ.
 */
object MeshTakTranslator {

    fun memberRole(role: Role): MemberRole = when (role) {
        Role.LEADER -> MemberRole.TeamLead
        Role.MIDDLE -> MemberRole.RTO
        Role.TAIL_GUNNER -> MemberRole.ForwardObserver
        Role.RIDER -> MemberRole.HQ
    }

    /** CoT __group name -> TAK team; an unknown name is Cyan (the 2.7a team). */
    fun team(name: String): Team = Team.values().firstOrNull { it.name == name } ?: Team.Cyan

    fun toV1TakPacket(m: CommMessage): TAKPacket {
        val p = requireNotNull(m.point) { "TAK V1 report needs a position" }
        return TAKPacket(
            is_compressed = false,
            contact = Contact(callsign = m.callsign, device_callsign = m.callsign),
            group = Group(role = memberRole(m.role), team = team(m.team)),
            pli = PLI(latitude_i = (p.latitude * 1e7).toInt(), longitude_i = (p.longitude * 1e7).toInt()),
        )
    }

    fun toV1DataPacket(m: CommMessage): DataPacket = DataPacket(
        bytes = okio.ByteString.of(*TAKPacket.ADAPTER.encode(toV1TakPacket(m))),
        dataType = PortNum.ATAK_PLUGIN.value,
        wantAck = false,
    )
}
