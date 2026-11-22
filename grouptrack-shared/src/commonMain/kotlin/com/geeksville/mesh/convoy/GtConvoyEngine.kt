package com.geeksville.mesh.convoy

import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtMath
import com.grouptrack.shared.text.GtMath.asin   // GTMATH-2026-11-10: Android's math on every platform
import com.grouptrack.shared.text.GtMath.cos
import com.grouptrack.shared.text.GtMath.sin
import kotlin.math.sqrt

/**
 * BOARDMATH-2026-11-22: the iPad's copy of ConvoyEngine (app/.../convoy/ConvoyEngine.kt), TRANSFORMED from
 * Android's file. Android's own ConvoyEngine is UNTOUCHED. Every function BODY is byte-identical
 * to Android's; only the imports and the two calls below could not survive the move.
 *
 * GTMATH-2026-11-10 (the platform rule): shared code never calls kotlin.math's sin/cos/asin --
 * they are MEMBER-IMPORTED from GtMath above, exactly as the two cert-green haversines do it
 * (GtTracks.kt:19-23, GtRead.kt:17-21), so the bare names in the bodies resolve to Android's
 * algorithms on both platforms. sqrt stays kotlin.math's: IEEE 754 requires it correctly
 * rounded everywhere, which is why GtMath deliberately does not carry it.
 *
 * THE TWO CALLS THAT CHANGED, and why each had to:
 *   Math.toRadians(x)  -> GtJava.toRadians(x)        no `Math` in commonMain; the same constant,
 *                                                    proven bit-exact on Droid 1 across 100,000
 *                                                    inputs (STEP7FIX-2026-11-08)
 *   x.pow(2)           -> GtMath.pow(x, 2.0)         an EXTENSION, so no import can redirect it.
 *                                                    Kotlin's Double.pow(Int) is Math.pow(x, 2.0),
 *                                                    which is what GtMath.pow certifies
 *                                                    (GTMATH2-2026-11-10, GtMathPowMacTest).
 *
 * R stays 3958.8 -- ConvoyEngine's own value. Four haversines exist and INVENTORY T4 keeps them
 * apart (GtTracks is R=3958.7613/Double, and its formula differs too: sin*sin, not pow, and
 * asin(min(1.0, sqrt(a))) clamped). Matching ANDROID'S ConvoyEngine is what makes the hashes
 * match; consolidating the four is real debt, and it is not this port's job.
 *
 * nowMs: taken as a parameter, as Android's is. The host passes GtClock.epochMs()
 * (GtImportEnv.kt:24, iOS at GtIosEnv.kt:94) where Android passes System.currentTimeMillis().
 */

object GtConvoyEngine {

    /** BOARDMATH-2026-11-22 seam: Android reads ConvoyConfig.LOST_MINUTES / SIGNAL_DROP_MINUTES -- MUTABLE
     *  vars (ConvoyConfig.kt:84-85) fed from the settings repo at ConvoyViewModel:558-559. These are
     *  the same two values in the same shape; the host sets them from GtRideSettings, whose keys and
     *  defaults are identical (signal_lost_minutes 5f / signal_drop_minutes 2f) and already cert'd.
     *  CODE RULE 1 n/a: not nullable -- Android's defaults, carried over. */
    var lostMinutes: Float = 5f
    var dropMinutes: Float = 2f

    data class ConvoyState(
        val nodes: List<GtConvoyNode> = emptyList(),
        val lead: GtConvoyNode? = null,
        val tail: GtConvoyNode? = null,
        val span_miles: Float = 0f,
        val convoyHeading: Float = 0f,
        val hasLost: Boolean = false,
        val activeCount: Int = 0,
        val lostCount: Int = 0
    ) {
        companion object {
            fun empty() = ConvoyState()
        }
    }

    data class LeadTrackSegment(
        val startLat: Double = 0.0,
        val startLon: Double = 0.0,
        val endLat: Double = 0.0,
        val endLon: Double = 0.0,
        val color: String = "#000000",
        val nodeId: String = ""
    )

    fun compute(
        nodes: List<GtConvoyNode>,
        myCartId: String = "",
        nowMs: Long,
        leadLocked: Boolean = false,
        lockedLeadNodeId: String? = null,
        tailNodeId: String? = null
    ): ConvoyState {
        if (nodes.isEmpty()) return ConvoyState.empty()
        val withStatus = nodes.map { it.copy(status = computeStatus(it, nowMs)) }
        val heading = computeHeading(withStatus)
        val sorted = computeSortPositions(withStatus, heading)
        val withRoles = assignLeadTail(sorted, lockedLeadNodeId, tailNodeId)
        val lead = withRoles.firstOrNull { it.isLead }
        val tail = withRoles.firstOrNull { it.isTail }
        val span = computeSpan(lead, tail)
        val withProximity = computeProximity(withRoles, myCartId)
        val activeCount = withProximity.count { it.status == GtConvoyStatus.ACTIVE }
        val lostCount = withProximity.count { it.status == GtConvoyStatus.LOST }
        return ConvoyState(
            nodes = withProximity,
            lead = withProximity.firstOrNull { it.isLead },
            tail = withProximity.firstOrNull { it.isTail },
            span_miles = span,
            convoyHeading = heading,
            hasLost = lostCount > 0,
            activeCount = activeCount,
            lostCount = lostCount
        )
    }

    fun computeStatus(node: GtConvoyNode, nowMs: Long): GtConvoyStatus {
        val ageMs = nowMs - node.lastSeenMs
        val lostMs = (lostMinutes * 60_000f).toLong()
        val dropMs = (dropMinutes * 60_000f).toLong()
        return when {
            ageMs >= lostMs -> GtConvoyStatus.LOST
            ageMs >= dropMs -> GtConvoyStatus.SIGNAL_DROP
            else -> GtConvoyStatus.ACTIVE
        }
    }

    fun computeHeading(nodes: List<GtConvoyNode>): Float {
        val moving = nodes
            .filter { it.status == GtConvoyStatus.ACTIVE && it.speed_mph > 3f }
            .map { it.heading_deg }
        if (moving.isEmpty()) return 0f
        val sorted = moving.sorted()
        return sorted[sorted.size / 2]
    }

    fun computeSortPositions(nodes: List<GtConvoyNode>, headingDeg: Float): List<GtConvoyNode> {
        val rad = GtJava.toRadians(headingDeg.toDouble())
        val dx = sin(rad)
        val dy = cos(rad)
        val active = nodes.filter { it.status != GtConvoyStatus.LOST }
            .sortedByDescending { it.latitude * dy + it.longitude * dx }
        val lost = nodes.filter { it.status == GtConvoyStatus.LOST }
        val all = active + lost
        return all.mapIndexed { i, node -> node.copy(convoyPosition = i + 1) }
    }

    /**
     * Assign lead and tail roles.
     * LEADCLEAN-2026-09-29 (Fred): the engine never picks a lead.
     * Lead: the lead field (lockedLeadNodeId) -- from a Leader report, or the rider's pick at REC. Null = no lead.
     * Tail: the Tail gunner (tailNodeId, from the roles); none -> the last cart by position (the fallback).
     */
    fun assignLeadTail(
        nodes: List<GtConvoyNode>,
        lockedLeadNodeId: String? = null,
        tailNodeId: String? = null
    ): List<GtConvoyNode> {
        val active = nodes.filter { it.status == GtConvoyStatus.ACTIVE }
        if (active.isEmpty()) return nodes
        // Lead — the lead field: from a Leader report or the rider's pick at REC. Null = no lead.
        val leadNode = if (lockedLeadNodeId != null)
            nodes.firstOrNull { it.nodeId == lockedLeadNodeId }
        else
            null
        // Tail — the Tail gunner (from the roles); none -> the last cart by position.
        val tailNode = if (tailNodeId != null)
            nodes.firstOrNull { it.nodeId == tailNodeId }
        else
            active.maxByOrNull { it.convoyPosition }
        return nodes.map { node ->
            node.copy(
                isLead = node.nodeId == leadNode?.nodeId,
                isTail = node.nodeId == tailNode?.nodeId,
                role = when {
                    node.nodeId == leadNode?.nodeId -> "Lead"
                    node.nodeId == tailNode?.nodeId -> "Tail"
                    node.isMyCart -> "My Cart"
                    else -> "Convoy"
                }
            )
        }
    }

    fun computeSpan(lead: GtConvoyNode?, tail: GtConvoyNode?): Float {
        if (lead == null || tail == null) return 0f
        return haversineMiles(lead.latitude, lead.longitude, tail.latitude, tail.longitude)
    }

    fun computeProximity(nodes: List<GtConvoyNode>, myCartId: String): List<GtConvoyNode> {
        return nodes.map { node ->
            val idx = nodes.indexOf(node)
            val ahead = if (idx > 0) nodes[idx - 1] else null
            val behind = if (idx < nodes.size - 1) nodes[idx + 1] else null
            val lead = nodes.firstOrNull { it.isLead }
            val tail = nodes.firstOrNull { it.isTail }
            node.copy(
                isMyCart = node.nodeId == myCartId,
                feetToNodeAhead = if (ahead != null)
                    haversineMiles(node.latitude, node.longitude, ahead.latitude, ahead.longitude) * 5280f else 0f,
                feetToNodeBehind = if (behind != null)
                    haversineMiles(node.latitude, node.longitude, behind.latitude, behind.longitude) * 5280f else 0f,
                milesToLead = if (lead != null)
                    haversineMiles(node.latitude, node.longitude, lead.latitude, lead.longitude) else 0f,
                milesToTail = if (tail != null)
                    haversineMiles(node.latitude, node.longitude, tail.latitude, tail.longitude) else 0f
            )
        }
    }

    /**
     * Color segments by nodeId — each node gets its own markerColor.
     * Used in MULTI TRACK mode. Segments must have nodeId set.
     */
    fun colorSegmentsByNode(
        segments: List<LeadTrackSegment>,
        nodes: List<GtConvoyNode>
    ): List<LeadTrackSegment> {
        val colorMap = nodes.associate { it.nodeId to it.markerColor }
        return segments.map { seg ->
            seg.copy(color = colorMap[seg.nodeId] ?: "#000000")
        }
    }

    fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 3958.8
        val dLat = GtJava.toRadians(lat2 - lat1)
        val dLon = GtJava.toRadians(lon2 - lon1)
        val a = GtMath.pow(sin(dLat / 2), 2.0) +
                cos(GtJava.toRadians(lat1)) * cos(GtJava.toRadians(lat2)) * GtMath.pow(sin(dLon / 2), 2.0)
        return (2 * R * asin(sqrt(a))).toFloat()
    }
}
