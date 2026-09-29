package org.meshtastic.core.data.manager

/**
 * TAKROLE-2026-09-28 (GroupTrack, Fred): the latest TAK ROLE reported by each node (its TAKPacket group.role, by name:
 * "TeamMember", "TeamLead", ...), recorded as TAK position reports arrive (handleTakPosition). Read by GroupTrack's tick
 * to show each cart's ride role -- DISPLAY ONLY. Nothing here changes positions or any Meshtastic behaviour.
 */
object TakRoleStore {
    private val roles = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Records the role a node reported (the enum's name). */
    fun put(nodeNum: Int, role: String) { roles[nodeNum] = role }

    /** The last role a node reported, or null if it never sent one. */
    fun roleOf(nodeNum: Int): String? = roles[nodeNum]
    /** LEADCLEAN-2026-09-29 (Fred): END -- the ride's roles end with the ride. */
    fun clear() { roles.clear() }
}
