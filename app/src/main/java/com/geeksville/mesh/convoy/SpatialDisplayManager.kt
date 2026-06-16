package com.geeksville.mesh.convoy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

//
// SpatialDisplayManager - the ONE standard process for displaying artifacts on a map.
// Both ConvoyScreen (convoy map) and ConvoyMapViewerScreen (planning map) call this
// instead of carrying their own inline query/filter/build/push logic.
//
// PHASE 1: JS function names are single-sourced here. JS is NOT injected yet; the HTML
// files keep their existing update, show, and hide functions. No zoom-gate: selection
// is protected by the SELECTED state itself, so viewport changes never alter the
// selected set - they only change which selected items are currently in view.
//
// STATE CHANGE FORCES A REQUERY: a display-state change is handled by the caller's
// onSetState writing ConvoyConfig then re-firing onViewportChanged, which re-runs this
// process. This function is never asked to flip an existing rendered list.
//
// This object holds NO state. The caller owns its WebView and its selection
// (ConvoyConfig checked-id sets). This function reads ConvoyConfig; it never writes it.
//
object SpatialDisplayManager {

    const val DS_OFF = 0
    const val DS_ON = 1
    const val DS_SELECTED = 2

    private data class TypeBinding(
        val idField: String,
        val query: (Double, Double, Double, Double, Int) -> List<Map<String, String?>>,
        val build: (List<Map<String, String?>>) -> String,
        val jsUpdate: String,
        val jsShow: String,
        val jsHide: String,
        val minZoom: Int
    )

    private fun bindingFor(type: String): TypeBinding? = when (type) {
        "Trails" -> TypeBinding(
            "trail_id",
            { s, w, n, e, lim -> SpatialDbManager.queryTrailsByViewport(s, w, n, e, lim) },
            { SpatialDbManager.buildTrailGeoJson(it) },
            "updateTrails", "showTrails", "hideTrails", 8
        )
        "Tracks" -> TypeBinding(
            "track_id",
            { s, w, n, e, lim -> SpatialDbManager.queryTracksByViewport(s, w, n, e, lim) },
            { SpatialDbManager.buildTrackGeoJson(it) },
            "updateTracks", "showTracks", "hideTracks", 0
        )
        "Waypoints" -> TypeBinding(
            "waypoint_id",
            { s, w, n, e, lim -> SpatialDbManager.queryWaypointsByViewport(s, w, n, e, lim) },
            { SpatialDbManager.buildWaypointGeoJson(it) },
            "updateWaypoints", "showWaypoints", "hideWaypoints", 0
        )
        "Routes" -> TypeBinding(
            "route_id",
            { s, w, n, e, lim -> SpatialDbManager.queryRoutesByViewport(s, w, n, e, lim) },
            { SpatialDbManager.buildRouteGeoJson(it) },
            "updateRoutes", "showRoutes", "hideRoutes", 0
        )
        else -> null
    }

    // Process ONE artifact type for the current viewport. Call once per type from the
    // caller's onViewportChanged, on a worker thread. update-then-show/hide is baked in;
    // that is the correctness fix the convoy paths were missing (they called update but
    // never show).
    fun processArtifact(
        type: String,
        south: Double, west: Double, north: Double, east: Double,
        zoom: Int,
        state: Int,
        checkedIds: Set<String>?,
        webView: WebView?,
        context: Context
    ) {
        val b = bindingFor(type) ?: return
        val wv = webView ?: return
        val main = Handler(Looper.getMainLooper())

        if (state == DS_OFF) {
            main.post { wv.evaluateJavascript(b.jsHide + "()", null) }
            return
        }
        if (zoom < b.minZoom) {
            main.post { wv.evaluateJavascript(b.jsHide + "()", null) }
            return
        }

        SpatialDbManager.init(context)

        val limit = if (type == "Tracks") { if (zoom >= 12) 200 else 50 }
                    else { if (zoom < 14) 500 else 2000 }

        val raw = b.query(south, west, north, east, limit)
        val items = if (state == DS_SELECTED && checkedIds != null)
            raw.filter { it[b.idField] in checkedIds } else raw

        val json = b.build(items)

        main.post {
            wv.evaluateJavascript(b.jsUpdate + "(" + json + ")", null)
            wv.evaluateJavascript(b.jsShow + "()", null)
        }
    }

    // RESTORE entry: draw the persisted state for a map WITHOUT depending on a
    // viewport event firing. Reads <mapKey>_panel.json (states + select-lists + bbox)
    // and runs the same processViewport draw, fed from the JSON frame. zoom is set
    // generously (bbox bounds the query; we don't want to gate the restore by zoom).
    fun drawPersistedState(mapKey: String, webView: WebView?, context: Context) {
        val rs = MapStateStore.readMap(mapKey)
        val bbox = rs.bbox ?: return  // no saved frame yet -> live viewport draw will populate
        val states = mapOf(
            "Trails" to (rs.types["Trails"]?.state ?: DS_OFF),
            "Tracks" to (rs.types["Tracks"]?.state ?: DS_OFF),
            "Waypoints" to (rs.types["Waypoints"]?.state ?: DS_OFF),
            "Routes" to (rs.types["Routes"]?.state ?: DS_OFF)
        )
        val selectLists = mapOf(
            "Trails" to MapStateStore.checkedIdsFor(rs, "Trails"),
            "Tracks" to MapStateStore.checkedIdsFor(rs, "Tracks"),
            "Waypoints" to MapStateStore.checkedIdsFor(rs, "Waypoints"),
            "Routes" to MapStateStore.checkedIdsFor(rs, "Routes")
        )
        val restoreZoom = 14  // generous limit + passes minZoom gates; bbox bounds the query
        Thread {
            processViewport(bbox.south, bbox.west, bbox.north, bbox.east, restoreZoom, states, selectLists, webView, context)
        }.start()
    }
    // Run all four types for one viewport change. Caller invokes on a worker thread.
    fun processViewport(
        south: Double, west: Double, north: Double, east: Double,
        zoom: Int,
        states: Map<String, Int>,
        selectLists: Map<String, Set<String>?>,
        webView: WebView?, context: Context
    ) {
        for (type in listOf("Trails", "Tracks", "Waypoints", "Routes")) {
            val st = states[type] ?: DS_OFF
            val ids = selectLists[type]
            processArtifact(type, south, west, north, east, zoom, st, ids, webView, context)
        }
    }
}
