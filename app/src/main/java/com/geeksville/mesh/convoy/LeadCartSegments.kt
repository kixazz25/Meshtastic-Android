package com.geeksville.mesh.convoy

import com.grouptrack.shared.track.LeadCartProcess

/** SEAMRULE-2026-11-19: ConvoyEngine.LeadTrackSegment is an ANDROID DISPLAY type, so it does not cross into shared code.
 *  LeadCartProcess.drawnLine() hands over the trimmed lead line (TRIM-2026-10-20) and this builds what Android draws.
 *  Was LeadCartProcess.segments(). BLUETRACKS-2026-10-20: bright blue, solid (was orange -- hard to see on red rock). */
fun LeadCartProcess.segments(): List<ConvoyEngine.LeadTrackSegment> {
    val l = drawnLine()
    val lead = leadId()
    val out = ArrayList<ConvoyEngine.LeadTrackSegment>()
    for (i in 1 until l.size) out.add(ConvoyEngine.LeadTrackSegment(
        startLat = l[i - 1].lat, startLon = l[i - 1].lon, endLat = l[i].lat, endLon = l[i].lon, color = "#1E88FF", nodeId = lead))
    return out
}
