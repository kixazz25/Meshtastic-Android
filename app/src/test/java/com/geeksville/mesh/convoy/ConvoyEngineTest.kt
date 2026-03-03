package com.geeksville.mesh.convoy

import org.junit.Assert.*
import org.junit.Test

class ConvoyEngineTest {

    private val nowMs = 1_000_000L

    private fun node(
        id: String, lat: Double, lon: Double,
        speed: Float = 10f, heading: Float = 320f,
        battery: Int = 80, lastSeenMs: Long = nowMs,
        isMyCart: Boolean = false
    ) = ConvoyNode(
        nodeId = id, callsign = id,
        latitude = lat, longitude = lon,
        speed_mph = speed, heading_deg = heading,
        battery_pct = battery, lastSeenMs = lastSeenMs,
        isMyCart = isMyCart
    )

    @Test fun ut01_activeNodeIsActive() {
        val n = node("A", 37.5, -113.3, lastSeenMs = nowMs - 60_000L)
        assertEquals(ConvoyStatus.ACTIVE, ConvoyEngine.computeStatus(n, nowMs))
    }
    @Test fun ut02_nodeAt120sIsSignalDrop() {
        val n = node("A", 37.5, -113.3, lastSeenMs = nowMs - 120_000L)
        assertEquals(ConvoyStatus.SIGNAL_DROP, ConvoyEngine.computeStatus(n, nowMs))
    }
    @Test fun ut03_nodeAt300sIsLost() {
        val n = node("A", 37.5, -113.3, lastSeenMs = nowMs - 300_000L)
        assertEquals(ConvoyStatus.LOST, ConvoyEngine.computeStatus(n, nowMs))
    }
    @Test fun ut04_lostNodeRemainsInList() {
        val nodes = listOf(node("A", 37.50, -113.30), node("B", 37.49, -113.29, lastSeenMs = nowMs - 400_000L))
        assertEquals(2, ConvoyEngine.compute(nodes, nowMs = nowMs).nodes.size)
    }
    @Test fun ut05_leadIsFurthestAhead() {
        val nodes = listOf(node("LEAD", 37.50, -113.33), node("MID", 37.49, -113.32), node("TAIL", 37.48, -113.31))
        assertEquals("LEAD", ConvoyEngine.compute(nodes, nowMs = nowMs).lead?.nodeId)
    }
    @Test fun ut06_tailIsFurthestBehind() {
        val nodes = listOf(node("LEAD", 37.50, -113.33), node("MID", 37.49, -113.32), node("TAIL", 37.48, -113.31))
        assertEquals("TAIL", ConvoyEngine.compute(nodes, nowMs = nowMs).tail?.nodeId)
    }
    @Test fun ut07_lostNodeNotLead() {
        val nodes = listOf(node("A", 37.50, -113.33, lastSeenMs = nowMs - 400_000L), node("B", 37.49, -113.32))
        assertEquals("B", ConvoyEngine.compute(nodes, nowMs = nowMs).lead?.nodeId)
    }
    @Test fun ut08_lostNodeNotTail() {
        val nodes = listOf(node("A", 37.50, -113.33), node("B", 37.49, -113.32, lastSeenMs = nowMs - 400_000L))
        assertEquals("A", ConvoyEngine.compute(nodes, nowMs = nowMs).tail?.nodeId)
    }
    @Test fun ut09_singleNodeIsLeadAndTail() {
        val state = ConvoyEngine.compute(listOf(node("A", 37.50, -113.33)), nowMs = nowMs)
        assertEquals("A", state.lead?.nodeId)
        assertEquals("A", state.tail?.nodeId)
    }
    @Test fun ut10_spanZeroForSingleNode() {
        assertEquals(0f, ConvoyEngine.compute(listOf(node("A", 37.50, -113.33)), nowMs = nowMs).span_miles, 0.001f)
    }
    @Test fun ut11_spanPositiveForTwoNodes() {
        val nodes = listOf(node("A", 37.50, -113.33), node("B", 37.48, -113.31))
        assertTrue(ConvoyEngine.compute(nodes, nowMs = nowMs).span_miles > 0f)
    }
    @Test fun ut12_headingIsMedianOfMovingNodes() {
        val nodes = listOf(
            node("A", 37.50, -113.33, speed = 10f, heading = 310f),
            node("B", 37.49, -113.32, speed = 10f, heading = 320f),
            node("C", 37.48, -113.31, speed = 10f, heading = 330f)
        ).map { it.copy(status = ConvoyStatus.ACTIVE) }
        assertEquals(320f, ConvoyEngine.computeHeading(nodes), 0.001f)
    }
    @Test fun ut13_stoppedNodesExcludedFromHeading() {
        val nodes = listOf(
            node("A", 37.50, -113.33, speed = 0f, heading = 180f),
            node("B", 37.49, -113.32, speed = 10f, heading = 320f)
        ).map { it.copy(status = ConvoyStatus.ACTIVE) }
        assertEquals(320f, ConvoyEngine.computeHeading(nodes), 0.001f)
    }
    @Test fun ut14_headingZeroWhenNoMovingNodes() {
        val nodes = listOf(node("A", 37.50, -113.33, speed = 0f), node("B", 37.49, -113.32, speed = 1f))
            .map { it.copy(status = ConvoyStatus.ACTIVE) }
        assertEquals(0f, ConvoyEngine.computeHeading(nodes), 0.001f)
    }
    @Test fun ut15_sortUsesHeadingProjection() {
        val nodes = listOf(node("A", 37.50, -113.33), node("B", 37.48, -113.31))
            .map { it.copy(status = ConvoyStatus.ACTIVE) }
        assertEquals("A", ConvoyEngine.computeSortPositions(nodes, 320f).first().nodeId)
    }
    @Test fun ut16_lostNodesSortLast() {
        val nodes = listOf(node("LOST", 37.52, -113.35, lastSeenMs = nowMs - 400_000L), node("ACTIVE", 37.48, -113.31))
        assertEquals("LOST", ConvoyEngine.compute(nodes, nowMs = nowMs).nodes.last().nodeId)
    }
    @Test fun ut17_myCartFeetAheadPositive() {
        val nodes = listOf(node("AHEAD", 37.50, -113.33), node("MINE", 37.49, -113.32), node("BEHIND", 37.48, -113.31))
        val mine = ConvoyEngine.compute(nodes, myCartId = "MINE", nowMs = nowMs).nodes.first { it.nodeId == "MINE" }
        assertTrue(mine.feetToNodeAhead > 0f)
    }
    @Test fun ut18_myCartFeetBehindPositive() {
        val nodes = listOf(node("AHEAD", 37.50, -113.33), node("MINE", 37.49, -113.32), node("BEHIND", 37.48, -113.31))
        val mine = ConvoyEngine.compute(nodes, myCartId = "MINE", nowMs = nowMs).nodes.first { it.nodeId == "MINE" }
        assertTrue(mine.feetToNodeBehind > 0f)
    }
    @Test fun ut19_trackAllBlackWhenNoNodes() {
        val segs = listOf(ConvoyEngine.LeadTrackSegment(37.49, -113.32, 37.50, -113.33))
        assertEquals("#000000", ConvoyEngine.computeLeadTrackColors(segs, emptyList(), null, null, 320f).first().color)
    }
    @Test fun ut20_trackSegmentInSpanGetsNodeColor() {
        val lead = node("LEAD", 37.50, -113.33).copy(isLead = true, status = ConvoyStatus.ACTIVE)
        val tail = node("TAIL", 37.48, -113.31).copy(isTail = true, status = ConvoyStatus.ACTIVE)
        val segs = listOf(ConvoyEngine.LeadTrackSegment(37.489, -113.319, 37.491, -113.321))
        val result = ConvoyEngine.computeLeadTrackColors(segs, listOf(lead, tail), lead, tail, 320f)
        assertNotEquals("#000000", result.first().color)
    }
    @Test fun ut21_trackSegmentOutsideSpanIsBlack() {
        val lead = node("LEAD", 37.50, -113.33).copy(isLead = true, status = ConvoyStatus.ACTIVE)
        val tail = node("TAIL", 37.48, -113.31).copy(isTail = true, status = ConvoyStatus.ACTIVE)
        val segs = listOf(ConvoyEngine.LeadTrackSegment(37.44, -113.27, 37.45, -113.28))
        assertEquals("#000000", ConvoyEngine.computeLeadTrackColors(segs, listOf(lead, tail), lead, tail, 320f).first().color)
    }
    @Test fun ut22_emptyNodeListAllBlack() {
        val segs = listOf(ConvoyEngine.LeadTrackSegment(37.49, -113.32, 37.50, -113.33))
        assertTrue(ConvoyEngine.computeLeadTrackColors(segs, emptyList(), null, null, 320f).all { it.color == "#000000" })
    }
    @Test fun ut23_lostMarkerIsRed() {
        assertEquals("#F44336", node("A", 37.5, -113.3).copy(status = ConvoyStatus.LOST).markerColor)
    }
    @Test fun ut24_slowNodeIsOrange() {
        assertEquals("#FFAA00", node("A", 37.5, -113.3, speed = 3f).copy(status = ConvoyStatus.ACTIVE).markerColor)
    }
    @Test fun ut25_fastNodeIsGreen() {
        assertEquals("#00AA00", node("A", 37.5, -113.3, speed = 10f).copy(status = ConvoyStatus.ACTIVE).markerColor)
    }
    @Test fun ut26_lowBatteryIsOrange() {
        assertEquals("#FFAA00", node("A", 37.5, -113.3, speed = 10f, battery = 15).copy(status = ConvoyStatus.ACTIVE).markerColor)
    }
    @Test fun ut27_emptyListReturnsEmptyState() {
        val state = ConvoyEngine.compute(emptyList(), nowMs = nowMs)
        assertNull(state.lead)
        assertNull(state.tail)
        assertEquals(0, state.activeCount)
    }
    @Test fun ut28_allLostNoLeadOrTail() {
        val nodes = listOf(node("A", 37.50, -113.33, lastSeenMs = nowMs - 400_000L), node("B", 37.49, -113.32, lastSeenMs = nowMs - 400_000L))
        val state = ConvoyEngine.compute(nodes, nowMs = nowMs)
        assertNull(state.lead)
        assertNull(state.tail)
    }
    @Test fun ut29_hasLostTrueWhenAnyLost() {
        val nodes = listOf(node("A", 37.50, -113.33), node("B", 37.49, -113.32, lastSeenMs = nowMs - 400_000L))
        assertTrue(ConvoyEngine.compute(nodes, nowMs = nowMs).hasLost)
    }
    @Test fun ut30_hasLostFalseWhenAllActive() {
        val nodes = listOf(node("A", 37.50, -113.33), node("B", 37.49, -113.32))
        assertFalse(ConvoyEngine.compute(nodes, nowMs = nowMs).hasLost)
    }
}
