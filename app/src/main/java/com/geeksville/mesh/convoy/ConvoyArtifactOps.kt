package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log

// ----------------------------------------------------------------
// ConvoyArtifactOps -- V2.5 Scaffold (Pass 1)
// All artifact actions: FIT, RENAME, DELETE, TO ROUTE, TO TRACK,
// UPLOAD, DOWNLOAD, CHANGE TYPE, EDIT POINTS.
// Reusable from both ArtifactsPanel and ArtifactDetailPanel.
// Source: ScreenReference v5 section 5, Master Build Phase 0
// ----------------------------------------------------------------

object ConvoyArtifactOps {

    private const val TAG = "ArtifactOps"

    /** FIT: write a convoy JSON (fitted type SELECTED w/ full bbox list, only the fitted
     *  artifact checked; other types OFF; bbox = artifact extent) then drawPersistedState.
     *  Rides the persistence machinery — no FIT-specific draw. */
    fun fit(context: Context, webView: android.webkit.WebView?, mapKey: String, artifactType: String, artifactId: String) {
        val bb = SpatialDbManager.bboxForArtifact(artifactType, artifactId) ?: run {
            Log.w(TAG, "FIT: no bbox for $artifactType $artifactId"); return
        }
        val south = bb[0]; val west = bb[1]; val north = bb[2]; val east = bb[3]
        // Query ALL of this type in the artifact bbox -> the toggle list (same query draw runs).
        val inBbox = when (artifactType) {
            "Trails" -> SpatialDbManager.queryTrailsByViewport(south, west, north, east)
            "Tracks" -> SpatialDbManager.queryTracksByViewport(south, west, north, east)
            "Waypoints" -> SpatialDbManager.queryWaypointsByViewport(south, west, north, east)
            "Routes" -> SpatialDbManager.queryRoutesByViewport(south, west, north, east)
            else -> emptyList()
        }
        val idField = when (artifactType) {
            "Trails" -> "trail_id"; "Tracks" -> "track_id"
            "Waypoints" -> "waypoint_id"; else -> "route_id"
        }
        // Rows = every in-bbox artifact; ONLY the fitted one checked=true (others toggleable).
        val fittedName = SpatialDbManager.getArtifactDetail(artifactType, artifactId)["name"] ?: ""
        val rows = inBbox.mapNotNull { m ->
            val id = m[idField] ?: return@mapNotNull null
            val nm = if (id == artifactId) fittedName else (m["name"] ?: "")
            MapStateStore.Row(id, nm, id == artifactId)
        }
        val finalRows = if (rows.any { it.id == artifactId }) rows
            else rows + MapStateStore.Row(artifactId, fittedName, true)
        val types = listOf("Trails", "Tracks", "Waypoints", "Routes").associateWith { t ->
            if (t == artifactType) MapStateStore.TypeState(2, finalRows)
            else MapStateStore.TypeState(0, emptyList())
        }
        val snap = MapStateStore.MapSnapshot(
            types,
            MapStateStore.PanelBoxes(),
            MapStateStore.BBox(south, west, north, east),
            MapStateStore.FitArtifact(artifactId, fittedName, artifactType)
        )
        MapStateStore.saveMap(mapKey, snap)
        Log.d(TAG, "FIT $artifactType $artifactId -> bbox=[$south,$west,$north,$east] rows=${finalRows.size}")
        SpatialDisplayManager.drawPersistedState(mapKey, webView, context)
    }

    /** RENAME: change display name, update aliases, journal */
    fun rename(artifactType: String, artifactId: String, newName: String) {
        Log.d(TAG, "RENAME $artifactType $artifactId -> $newName — Pass 1 stub")
    }

    /** DELETE: confirm + guard check + backup + journal + remove */
    fun delete(context: Context, artifactType: String, artifactId: String): Boolean {
        Log.d(TAG, "DELETE $artifactType $artifactId — Pass 1 stub")
        return false
    }

    /** TO ROUTE: flip track type to route, link source_track_id */
    fun toRoute(trackId: String) {
        Log.d(TAG, "TO ROUTE $trackId — Pass 1 stub")
    }

    /** TO TRACK: flip route type back to track */
    fun toTrack(routeId: String) {
        Log.d(TAG, "TO TRACK $routeId — Pass 1 stub")
    }

    /** UPLOAD: share prompt -> upload queue entry (V2.5 collect only) */
    fun upload(artifactType: String, artifactId: String) {
        Log.d(TAG, "UPLOAD $artifactType $artifactId — Pass 1 stub (V2.5 collect only)")
    }

    /** DOWNLOAD: create HIGH priority tile jobs for artifact geometry */
    fun download(artifactType: String, artifactId: String) {
        Log.d(TAG, "DOWNLOAD $artifactType $artifactId — Pass 1 stub")
    }

    /** CHANGE TYPE: change waypoint type */
    fun changeType(waypointId: String, newTypeId: String) {
        Log.d(TAG, "CHANGE TYPE $waypointId -> $newTypeId — Pass 1 stub")
    }

    /** EDIT POINTS: enter route edit mode with draggable handles */
    fun editPoints(routeId: String) {
        Log.d(TAG, "EDIT POINTS $routeId — Pass 1 stub")
    }

    /** + ALIAS: add alias to artifact */
    fun addAlias(artifactType: String, artifactId: String, alias: String) {
        Log.d(TAG, "ADD ALIAS $artifactType $artifactId '$alias' — Pass 1 stub")
    }

    /** SET TH: assign trailhead waypoint to route */
    fun setTrailhead(routeId: String, waypointId: String) {
        Log.d(TAG, "SET TH route=$routeId waypoint=$waypointId — Pass 1 stub")
    }
}
