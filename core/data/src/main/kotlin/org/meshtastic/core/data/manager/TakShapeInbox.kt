package org.meshtastic.core.data.manager

/**
 * POLYLINE-2026-10-20 (Fred 10-20 14:27 -- keep GroupTrack's message out of the mesh layer): EVERY TAK V2 DRAWN SHAPE received
 * (a polyline, a polygon...), decoded to plain points. GENERIC -- nothing here knows GroupTrack; the comm layer (MeshCommMessaging)
 * decides what a shape is. Replaces POLYTEST's GtPolylineInbox. In memory only.
 */
object TakShapeInbox {
    /** from = the sending radio; cotType = the CoT type enum name (e.g. CotType_u_d_f); lats/lons = the vertices, anchor + deltas applied. */
    class Shape(val from: Int, val uid: String, val callsign: String, val remarks: String, val cotType: String,
                val lats: List<Double>, val lons: List<Double>, val rxMs: Long)
    val shapes = kotlinx.coroutines.flow.MutableSharedFlow<Shape>(extraBufferCapacity = 16)
}
