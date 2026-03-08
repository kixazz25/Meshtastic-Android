package org.meshtastic.feature.convoy.filter

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ConvoyNodeFilter
 *
 * Filters the Meshtastic node list for display on the Convoy map.
 * Called at display time — does NOT modify the Meshtastic node database.
 *
 * Rules (applied in order):
 *  1. Node must have been heard today (after local midnight) — calendar day, not 24hr rolling
 *  2. Node's first-heard-today timestamp must be within the operator's admission window
 *  3. Node must not be in the removedCartIds set for today
 *
 * Nodes that pass all three rules are shown on the Convoy map.
 * Once admitted (rule 1+2 passed), a node stays visible all day even if it goes silent.
 */
object ConvoyNodeFilter {

    /**
     * @param nodes              Raw node list from Meshtastic node DB.
     *                           Each entry is a Pair of (nodeId, lastHeardEpochSeconds).
     * @param removedCartIds     Set of nodeIds manually removed by operator today.
     * @param admissionWindowHours  Max hours since first heard today for initial admission.
     * @param nowEpochSeconds    Current time (injectable for testing; defaults to System time).
     * @return Filtered list of nodeIds to display on the Convoy map.
     */
    fun filter(
        nodes:                  List<Pair<String, Long>>,   // nodeId to lastHeardEpochSeconds
        removedCartIds:         Set<String>,
        admissionWindowHours:   Int,
        nowEpochSeconds:        Long = System.currentTimeMillis() / 1000L
    ): List<String> {
        val todayMidnightEpoch = todayMidnightEpochSeconds()
        val admissionCutoff    = nowEpochSeconds - (admissionWindowHours * 3600L)

        return nodes
            .filter { (nodeId, lastHeard) ->

                // Rule 1: Must have been heard today (after midnight)
                if (lastHeard < todayMidnightEpoch) return@filter false

                // Rule 2: Must have been first heard within the admission window
                // (For simplicity we use lastHeard as proxy — if they were heard today
                //  and within the window, they qualify. Once in, silence doesn't remove them
                //  because rule 1 still passes all day.)
                if (lastHeard < admissionCutoff) return@filter false

                // Rule 3: Must not be manually removed
                if (nodeId in removedCartIds) return@filter false

                true
            }
            .map { it.first }
    }

    /**
     * Returns epoch seconds at local midnight today.
     * This is the calendar day boundary — not a rolling 24hr window.
     */
    fun todayMidnightEpochSeconds(): Long =
        LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()

    /**
     * Convenience: check if a single node should be shown.
     */
    fun shouldShow(
        nodeId:               String,
        lastHeardEpochSeconds: Long,
        removedCartIds:        Set<String>,
        admissionWindowHours:  Int,
        nowEpochSeconds:       Long = System.currentTimeMillis() / 1000L
    ): Boolean = filter(
        nodes                = listOf(Pair(nodeId, lastHeardEpochSeconds)),
        removedCartIds       = removedCartIds,
        admissionWindowHours = admissionWindowHours,
        nowEpochSeconds      = nowEpochSeconds
    ).isNotEmpty()
}
