package com.geeksville.mesh.convoy

package com.geeksville.mesh.convoy

import org.junit.Assert.*
import org.junit.Test

class ConvoyEngineTest {

    private val nowMs = 1_000_000L

    private fun node(
        id: String,
        lat: Double,
        lon: Double,
        speed: Float = 10f,
        heading: Float = 320f,
        battery: Int = 80,
        lastSeenMs: Long = nowMs,
        isMyCart: Boolean = false
    ) = ConvoyNode(
        nodeId = id,
        callsign = id,
        latitude = lat,
        longitude = lon,
        speed_mph = speed,
        heading_deg = heading,
        battery_pct = battery,
        lastSeenMs = lastSeenMs,
        isMyCart = isMyCart
    )

    // ── Status tests ──────────────────────────────────────────────────────

    @Test fun ut01_activeNodeIsActive() {
        val n = node("A", 37.5, -113.3, lastSeenMs = nowMs - 60_000L)
        val result = ConvoyEngine.computeStatus(n, nowMs)
        assertEquals(ConvoyStatus.ACTIVE, result)
    }

    @Test fun ut02_nodeAt120sIsSignalDrop() {
        val n = node("A", 37.5, -113.3, lastSeenMs = nowMs - 120_000L)
        val result = ConvoyEngine.computeStatus(n, nowMs)
        assertEquals(ConvoyStatus.SIGNAL_DROP, result)
    }

    @Test fun ut03_nodeAt300sIsLost() {
        val n = node("A", 37.5, -113.3, lastSeenMs = nowMs - 300_000L)
        val result = ConvoyEngine.computeStatus(n, nowMs)
        assertEquals(ConvoyStatus.LOST, result)
    }

    @Test fun ut04_lostNodeRemainsInList() {
        val nodes = listOf(
            node("A", 37.50, -113.30, lastSeenMs = nowMs),
            node("B", 37.49, -113.29, lastSeenMs = nowMs - 400_000L)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertEquals(2, state.nodes.size)
    }

    // ── Lead / Tail tests ─────────────────────────────────────────────────

    @Test fun ut05_leadIsFurthestAhead() {
        val nodes = listOf(
            node("LEAD", 37.50, -113.33),
            node("MID",  37.49, -113.32),
            node("TAIL", 37.48, -113.31)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertEquals("LEAD", state.lead?.nodeId)
    }

    @Test fun ut06_tailIsFurthestBehind() {
        val nodes = listOf(
            node("LEAD", 37.50, -113.33),
            node("MID",  37.49, -113.32),
            node("TAIL", 37.48, -113.31)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertEquals("TAIL", state.tail?.nodeId)
    }

    @Test fun ut07_lostNodeNotLead() {
        val nodes = listOf(
            node("A", 37.50, -113.33, lastSeenMs = nowMs - 400_000L),
            node("B", 37.49, -113.32)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertEquals("B", state.lead?.nodeId)
    }

    @Test fun ut08_lostNodeNotTail() {
        val nodes = listOf(
            node("A", 37.50, -113.33),
            node("B", 37.49, -113.32, lastSeenMs = nowMs - 400_000L)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertEquals("A", state.tail?.nodeId)
    }

    @Test fun ut09_singleNodeIsLeadAndTail() {
        val nodes = listOf(node("A", 37.50, -113.33))
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertEquals("A", state.lead?.nodeId)
        assertEquals("A", state.tail?.nodeId)
    }

    // ── Span tests ────────────────────────────────────────────────────────

    @Test fun ut10_spanZeroForSingleNode() {
        val nodes = listOf(node("A", 37.50, -113.33))
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertEquals(0f, state.span_miles, 0.001f)
    }

    @Test fun ut11_spanPositiveForTwoNodes() {
        val nodes = listOf(
            node("A", 37.50, -113.33),
            node("B", 37.48, -113.31)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertTrue(state.span_miles > 0f)
    }

    // ── Heading tests ─────────────────────────────────────────────────────

    @Test fun ut12_headingIsMedianOfMovingNodes() {
        val nodes = listOf(
            node("A", 37.50, -113.33, speed = 10f, heading = 310f),
            node("B", 37.49, -113.32, speed = 10f, heading = 320f),
            node("C", 37.48, -113.31, speed = 10f, heading = 330f)
        )
        val heading = ConvoyEngine.computeHeading(nodes.map {
            it.copy(status = ConvoyStatus.ACTIVE)
        })
        assertEquals(320f, heading, 0.001f)
    }

    @Test fun ut13_stoppedNodesExcludedFromHeading() {
        val nodes = listOf(
            node("A", 37.50, -113.33, speed = 0f, heading = 180f),
            node("B", 37.49, -113.32, speed = 10f, heading = 320f)
        )
        val heading = ConvoyEngine.computeHeading(nodes.map {
            it.copy(status = ConvoyStatus.ACTIVE)
        })
        assertEquals(320f, heading, 0.001f)
    }

    @Test fun ut14_headingZeroWhenNoMovingNodes() {
        val nodes = listOf(
            node("A", 37.50, -113.33, speed = 0f),
            node("B", 37.49, -113.32, speed = 1f)
        )
        val heading = ConvoyEngine.computeHeading(nodes.map {
            it.copy(status = ConvoyStatus.ACTIVE)
        })
        assertEquals(0f, heading, 0.001f)
    }

    // ── Sort tests ────────────────────────────────────────────────────────

    @Test fun ut15_sortUsesHeadingProjection() {
        val nodes = listOf(
            node("A", 37.50, -113.33),
            node("B", 37.48, -113.31)
        ).map { it.copy(status = ConvoyStatus.ACTIVE) }
        val sorted = ConvoyEngine.computeSortPositions(nodes, 320f)
        assertEquals("A", sorted.first().nodeId)
    }

    @Test fun ut16_lostNodesSortLast() {
        val nodes = listOf(
            node("LOST", 37.52, -113.35, lastSeenMs = nowMs - 400_000L),
            node("ACTIVE", 37.48, -113.31)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertEquals("LOST", state.nodes.last().nodeId)
    }

    // ── Proximity tests ───────────────────────────────────────────────────

    @Test fun ut17_myCartFeetAheadPositive() {
        val nodes = listOf(
            node("AHEAD", 37.50, -113.33),
            node("MINE",  37.49, -113.32, isMyCart = true),
            node("BEHIND",37.48, -113.31)
        )
        val state = ConvoyEngine.compute(nodes, myCartId = "MINE", nowMs = nowMs)
        val mine = state.nodes.first { it.nodeId == "MINE" }
        assertTrue(mine.feetToNodeAhead > 0f)
    }

    @Test fun ut18_myCartFeetBehindPositive() {
        val nodes = listOf(
            node("AHEAD", 37.50, -113.33),
            node("MINE",  37.49, -113.32, isMyCart = true),
            node("BEHIND",37.48, -113.31)
        )
        val state = ConvoyEngine.compute(nodes, myCartId = "MINE", nowMs = nowMs)
        val mine = state.nodes.first { it.nodeId == "MINE" }
        assertTrue(mine.feetToNodeBehind > 0f)
    }

    // ── Lead track color tests ────────────────────────────────────────────

    @Test fun ut19_trackAllBlackWhenNoNodes() {
        val segments = listOf(
            ConvoyEngine.LeadTrackSegment(37.49, -113.32, 37.50, -113.33)
        )
        val result = ConvoyEngine.computeLeadTrackColors(segments, emptyList(), null, null, 320f)
        assertEquals("#000000", result.first().color)
    }

    @Test fun ut20_trackSegmentInSpanGetsNodeColor() {
        val lead = node("LEAD", 37.50, -113.33).copy(isLead = true, status = ConvoyStatus.ACTIVE)
        val tail = node("TAIL", 37.48, -113.31).copy(isTail = true, status = ConvoyStatus.ACTIVE)
        val segments = listOf(
            ConvoyEngine.LeadTrackSegment(37.489, -113.319, 37.491, -113.321)
        )
        val result = ConvoyEngine.computeLeadTrackColors(
            segments, listOf(lead, tail), lead, tail, 320f)
        assertNotEquals("#000000", result.first().color)
    }

    @Test fun ut21_trackSegmentOutsideSpanIsBlack() {
        val lead = node("LEAD", 37.50, -113.33).copy(isLead = true, status = ConvoyStatus.ACTIVE)
        val tail = node("TAIL", 37.48, -113.31).copy(isTail = true, status = ConvoyStatus.ACTIVE)
        val segments = listOf(
            ConvoyEngine.LeadTrackSegment(37.44, -113.27, 37.45, -113.28)
        )
        val result = ConvoyEngine.computeLeadTrackColors(
            segments, listOf(lead, tail), lead, tail, 320f)
        assertEquals("#000000", result.first().color)
    }

    @Test fun ut22_emptyNodeListAllBlack() {
        val segments = listOf(
            ConvoyEngine.LeadTrackSegment(37.49, -113.32, 37.50, -113.33)
        )
        val result = ConvoyEngine.computeLeadTrackColors(segments, emptyList(), null, null, 320f)
        assertTrue(result.all { it.color == "#000000" })
    }

    // ── Marker color tests ────────────────────────────────────────────────

    @Test fun ut23_lostMarkerIsRed() {
        val n = node("A", 37.5, -113.3).copy(status = ConvoyStatus.LOST)
        assertEquals("#F44336", n.markerColor)
    }

    @Test fun ut24_slowNodeIsOrange() {
        val n = node("A", 37.5, -113.3, speed = 3f).copy(status = ConvoyStatus.ACTIVE)
        assertEquals("#FFAA00", n.markerColor)
    }

    @Test fun ut25_fastNodeIsGreen() {
        val n = node("A", 37.5, -113.3, speed = 10f).copy(status = ConvoyStatus.ACTIVE)
        assertEquals("#00AA00", n.markerColor)
    }

    @Test fun ut26_lowBatteryIsOrange() {
        val n = node("A", 37.5, -113.3, speed = 10f, battery = 15).copy(status = ConvoyStatus.ACTIVE)
        assertEquals("#FFAA00", n.markerColor)
    }

    // ── Edge case tests ───────────────────────────────────────────────────

    @Test fun ut27_emptyListReturnsEmptyState() {
        val state = ConvoyEngine.compute(emptyList(), nowMs = nowMs)
        assertNull(state.lead)
        assertNull(state.tail)
        assertEquals(0, state.activeCount)
    }

    @Test fun ut28_allLostNoLeadOrTail() {
        val nodes = listOf(
            node("A", 37.50, -113.33, lastSeenMs = nowMs - 400_000L),
            node("B", 37.49, -113.32, lastSeenMs = nowMs - 400_000L)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertNull(state.lead)
        assertNull(state.tail)
    }

    @Test fun ut29_hasLostTrueWhenAnyLost() {
        val nodes = listOf(
            node("A", 37.50, -113.33),
            node("B", 37.49, -113.32, lastSeenMs = nowMs - 400_000L)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertTrue(state.hasLost)
    }

    @Test fun ut30_hasLostFalseWhenAllActive() {
        val nodes = listOf(
            node("A", 37.50, -113.33),
            node("B", 37.49, -113.32)
        )
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertFalse(state.hasLost)
    }
}