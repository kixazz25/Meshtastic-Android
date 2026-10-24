package com.geeksville.mesh.convoy

import java.time.LocalDate
import java.time.ZoneId

/**
 * ConvoyNodeFilter
 *
 * Filters the Meshtastic node list for display on the Convoy map.
 * Called at display time — does NOT modify the Meshtastic node database.
 *
 * Rules:
 *  1. Node must have been heard today (after local midnight — calendar day not 24hr rolling)
 *  2. Node's lastHeard must be within the operator's admission window
 *  3. Node must not be in the removedCarts set for today
 *
 * Once admitted, a node stays visible all day even if it goes silent.
 */
object ConvoyNodeFilter {

    /**
     * @param nodes                Raw node list. Each entry: nodeId to lastHeardEpochSeconds.
     * @param removedCartIds       Set of nodeIds manually removed by operator today.
     * @param admissionWindowHours Max hours since lastHeard for initial admission.
     * @param nowEpochSeconds      Current time (injectable for testing).
     * @return Filtered list of nodeIds to display on the Convoy map.
     */
    fun filter(
        nodes:                List<Pair<String, Long>>,
        removedCartIds:       Set<String>,
        admissionWindowHours: Int,
        nowEpochSeconds:      Long = System.currentTimeMillis() / 1000L
    ): List<String> {
        val todayMidnight  = todayMidnightEpochSeconds()
        val admissionCutoff = nowEpochSeconds - (admissionWindowHours * 3600L)

        return nodes
            .filter { (nodeId, lastHeard) ->
                // Rule 1: Heard today (after midnight)
                if (lastHeard < todayMidnight) return@filter false
                // Rule 2: Within admission window
                if (lastHeard < admissionCutoff) return@filter false
                // Rule 3: Not manually removed
                if (nodeId in removedCartIds) return@filter false
                true
            }
            .map { it.first }
    }

    fun todayMidnightEpochSeconds(): Long =
        LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
}
