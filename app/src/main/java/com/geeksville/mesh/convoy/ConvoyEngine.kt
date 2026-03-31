package com.geeksville.mesh.convoy

import kotlin.math.*

object ConvoyEngine {

    data class ConvoyState(
        val nodes: List<ConvoyNode> = emptyList(),
        val lead: ConvoyNode? = null,
        val tail: ConvoyNode? = null,
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
        val color: String = "#000000"
    )

    fun compute(
        nodes: List<ConvoyNode>,
        myCartId: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): ConvoyState {
        if (nodes.isEmpty()) return ConvoyState.empty()
        val withStatus = nodes.map { it.copy(status = computeStatus(it, nowMs)) }
        val heading = computeHeading(withStatus)
        val sorted = computeSortPositions(withStatus, heading)
        val withRoles = assignLeadTail(sorted)
        val lead = withRoles.firstOrNull { it.isLead }
        val tail = withRoles.firstOrNull { it.isTail }
        val span = computeSpan(lead, tail)
        val withProximity = computeProximity(withRoles, myCartId)
        val activeCount = withProximity.count { it.status == ConvoyStatus.ACTIVE }
        val lostCount = withProximity.count { it.status == ConvoyStatus.LOST }
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

    fun computeStatus(node: ConvoyNode, nowMs: Long): ConvoyStatus {
        val ageMs = nowMs - node.lastSeenMs
        val lostMs = (ConvoyConfig.LOST_MINUTES * 60_000f).toLong()
        val dropMs = (ConvoyConfig.SIGNAL_DROP_MINUTES * 60_000f).toLong()
        return when {
            ageMs >= lostMs -> ConvoyStatus.LOST
            ageMs >= dropMs -> ConvoyStatus.SIGNAL_DROP
            else -> ConvoyStatus.ACTIVE
        }
    }

    fun computeHeading(nodes: List<ConvoyNode>): Float {
        val moving = nodes
            .filter { it.status == ConvoyStatus.ACTIVE && it.speed_mph > 3f }
            .map { it.heading_deg }
        if (moving.isEmpty()) return 0f
        val sorted = moving.sorted()
        return sorted[sorted.size / 2]
    }

    fun computeSortPositions(nodes: List<ConvoyNode>, headingDeg: Float): List<ConvoyNode> {
        val rad = Math.toRadians(headingDeg.toDouble())
        val dx = sin(rad)
        val dy = cos(rad)
        val active = nodes.filter { it.status != ConvoyStatus.LOST }
            .sortedByDescending { it.latitude * dy + it.longitude * dx }
        val lost = nodes.filter { it.status == ConvoyStatus.LOST }
        val all = active + lost
        return all.mapIndexed { i, node -> node.copy(convoyPosition = i + 1) }
    }

    fun assignLeadTail(nodes: List<ConvoyNode>): List<ConvoyNode> {
        val active = nodes.filter { it.status == ConvoyStatus.ACTIVE }
        if (active.isEmpty()) return nodes
        // Lead = node with callsign "Lead" (case-insensitive) — set on radio hardware
        val leadNode = nodes.firstOrNull { it.callsign.equals("Lead", ignoreCase = true) }
        // Tail = rearmost active node, excluding lead
        val tailNode = active
            .filter { it.nodeId != leadNode?.nodeId }
            .maxByOrNull { it.convoyPosition }
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
        fun computeSpan(lead: ConvoyNode?, tail: ConvoyNode?): Float {
        if (lead == null || tail == null) return 0f
        return haversineMiles(lead.latitude, lead.longitude, tail.latitude, tail.longitude)
    }

    fun computeProximity(nodes: List<ConvoyNode>, myCartId: String): List<ConvoyNode> {
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

    fun computeLeadTrackColors(
        segments: List<LeadTrackSegment>,
        nodes: List<ConvoyNode>,
        lead: ConvoyNode?,
        tail: ConvoyNode?,
        headingDeg: Float
    ): List<LeadTrackSegment> {
        if (nodes.isEmpty() || lead == null || tail == null)
            return segments.map { it.copy(color = "#000000") }
        val rad = Math.toRadians(headingDeg.toDouble())
        val dx = sin(rad)
        val dy = cos(rad)
        val leadProj = lead.latitude * dy + lead.longitude * dx
        val tailProj = tail.latitude * dy + tail.longitude * dx
        val minProj = minOf(leadProj, tailProj)
        val maxProj = maxOf(leadProj, tailProj)
        return segments.map { seg ->
            val midLat = (seg.startLat + seg.endLat) / 2
            val midLon = (seg.startLon + seg.endLon) / 2
            val proj = midLat * dy + midLon * dx
            if (proj in minProj..maxProj) {
                val closest = nodes.minByOrNull { abs((it.latitude * dy + it.longitude * dx) - proj) }
                seg.copy(color = closest?.markerColor ?: "#000000")
            } else {
                seg.copy(color = "#000000")
            }
        }
    }

    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return (2 * R * asin(sqrt(a))).toFloat()
    }
}
