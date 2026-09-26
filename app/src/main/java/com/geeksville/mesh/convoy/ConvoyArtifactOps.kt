package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** RENAME: change display name. DB enforces rules. Caller refreshes its map. */
    suspend fun rename(context: Context, artifactType: String, artifactId: String, newName: String) {
        withContext(Dispatchers.IO) {
            SpatialDbManager.init(context)
            when (artifactType) {
                "Waypoints" -> SpatialDbManager.renameWaypoint(artifactId, newName)
                "Routes" -> SpatialDbManager.renameRoute(artifactId, newName)
                "Tracks" -> SpatialDbManager.renameTrackInDb(artifactId, newName)
            }
        }
        Log.d(TAG, "RENAME $artifactType $artifactId -> $newName")
    }

    /** DELETE: DB enforces guards. Caller refreshes its map. */
    suspend fun delete(context: Context, artifactType: String, artifactId: String) {
        withContext(Dispatchers.IO) {
            SpatialDbManager.init(context)
            when (artifactType) {
                "Waypoints" -> SpatialDbManager.deleteWaypoint(artifactId)
                "Routes" -> SpatialDbManager.deleteRoute(artifactId)
                "Tracks" -> SpatialDbManager.deleteTrackFromDb(artifactId)
                // RIDERTRAILDELETE-2026-09-08: ⛔ THIS BRANCH DID NOT EXIST, so a
                // delete on a trail fell through the when(), logged success and
                // did nothing. The planner has always passed onDelete; only the
                // panel's type check kept anyone from finding out.
                // ⚠ The panel decides WHICH trails offer the button -- rider-
                // created only. This function does not re-check, exactly as the
                // other three do not.
                "Trails" -> SpatialDbManager.deleteTrailFromDb(artifactId)
            }
        }
        Log.d(TAG, "DELETE $artifactType $artifactId")
    }

    /** TO ROUTE: flip track type to route, link source_track_id */
    fun toRoute(trackId: String) {
        Log.d(TAG, "TO ROUTE $trackId — Pass 1 stub")
    }

    /**
     * TRACKROUTE-2026-09-26 (Fred) -- PASS 1 (review): a TRACK's recorded line, simplified so every turn keeps its
     * shape (a point wherever the heading has turned > 15 degrees, plus the corner before it; otherwise one every 20 m;
     * GPS jitter under 3 m dropped), written as a Route+ DRAFT marked "method": "convertroute" -- NOT a route. Open it
     * from Route+'s in-progress list, over the track, to approve the method. Pass 2 (final) writes the route and fits it.
     * Refuses when Route+ already holds an unsaved route (single working route); leaves Route+ empty afterwards.
     * Returns the number of points written, or a negative code: -1 no geometry, -2 too few points, -3 Route+ busy,
     * -4 write failed.
     */
    suspend fun trackToConvertDraft(context: Context, trackId: String, name: String): Int = withContext(Dispatchers.IO) {
        SpatialDbManager.init(context)
        val wkt = SpatialDbManager.trackGeometry(trackId) ?: run { Log.w(TAG, "TRACKROUTE: no geometry for track $trackId"); return@withContext -1 }
        val pts = ConvoyRideStore.parseWktLine(wkt)   // lon/lat pairs
        if (pts.size < 2) { Log.w(TAG, "TRACKROUTE: track $trackId has ${pts.size} points"); return@withContext -2 }
        if (RouteManager.routeVertices().isNotEmpty()) { Log.w(TAG, "TRACKROUTE: Route+ holds an unsaved route -- refused"); return@withContext -3 }
        val kept = simplifyByHeading(pts)
        RouteManager.clearRoute()
        for (p in kept) RouteManager.addVertex(RouteManager.freeVertex(p.second, p.first))
        val ok = try { RouteDraftStore.writeDraft(name.trim(), "convertroute") } catch (e: Exception) { Log.e(TAG, "TRACKROUTE: writeDraft failed: ${e.message}"); false }
        RouteManager.clearRoute()
        if (!ok) return@withContext -4
        Log.i(TAG, "TRACKROUTE: convertroute draft '${name.trim()}' from track $trackId (${pts.size} points -> ${kept.size})")
        kept.size
    }

    /**
     * TRACKROUTE2-2026-09-26 (Fred) -- PASS 2 (final): the track's line, simplified as in trackToConvertDraft (method
     * approved on the review draft), SAVED as a route with the existing insertRoute. The track is never changed.
     * Returns the new route id, or null (logged).
     */
    suspend fun trackToRoute(context: Context, trackId: String, name: String, description: String): String? = withContext(Dispatchers.IO) {   // TRACKDESC-2026-09-26
        SpatialDbManager.init(context)
        val wkt = SpatialDbManager.trackGeometry(trackId) ?: run { Log.w(TAG, "TRACKROUTE: no geometry for track $trackId"); return@withContext null }
        val pts = ConvoyRideStore.parseWktLine(wkt)   // lon/lat pairs
        if (pts.size < 2) { Log.w(TAG, "TRACKROUTE: track $trackId has ${pts.size} points"); return@withContext null }
        val kept = simplifyByHeading(pts)
        val line = "LINESTRING(" + kept.joinToString(", ") { "${it.first} ${it.second}" } + ")"
        val id = try {
            SpatialDbManager.insertRoute(name.trim(), line, kept.minOf { it.second }, kept.maxOf { it.second }, kept.minOf { it.first }, kept.maxOf { it.first })
        } catch (e: Exception) { Log.e(TAG, "TRACKROUTE: insertRoute failed: ${e.message}"); "" }
        if (id.isBlank()) return@withContext null
        // TRACKDESC-2026-09-26 (Fred): the route is born WITH its narrative -- the route owns it. Headline = automatic
        // ("Created from track <track> -- <miles> miles", measured on the RECORDED points); description = the rider's words.
        val miles = kotlin.math.round(pts.zipWithNext().sumOf { (a, b) -> trDistM(a, b) } / 1609.344 * 10) / 10.0
        val trackName = SpatialDbManager.trackName(trackId) ?: name.trim()
        val notes = org.json.JSONObject()
            .put("source", "convertroute")
            .put("narrative", org.json.JSONObject()
                .put("headline", "Created from track $trackName \u2014 $miles miles")
                .put("description", description.trim()))
            .put("summary", org.json.JSONObject().put("total_miles", miles))
        val rows = SpatialDbManager.writeRouteNotes(id, notes)
        Log.i(TAG, "TRACKROUTE: track $trackId -> ROUTE $id '${name.trim()}' (${pts.size} points -> ${kept.size}; $miles mi; notes rows $rows)")
        id
    }

    private fun trDistM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val k = 111_320.0
        val dx = (b.first - a.first) * k * kotlin.math.cos(Math.toRadians((a.second + b.second) / 2))
        val dy = (b.second - a.second) * k
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun trBearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = (b.first - a.first) * kotlin.math.cos(Math.toRadians((a.second + b.second) / 2))
        val dy = b.second - a.second
        return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360
    }

    private fun trTurn(a: Double, b: Double): Double { val d = kotlin.math.abs(a - b) % 360; return if (d > 180) 360 - d else d }

    /** TRACKROUTE: heading-aware simplification (see trackToRoute). */
    internal fun simplifyByHeading(
        pts: List<Pair<Double, Double>>, turnDeg: Double = 15.0, maxGapM: Double = 20.0, minGapM: Double = 3.0,
    ): List<Pair<Double, Double>> {
        if (pts.size <= 2) return pts
        val out = mutableListOf(pts.first())
        var lastB: Double? = null
        for (i in 1 until pts.size - 1) {
            val p = pts[i]; val last = out.last(); val d = trDistM(last, p)
            if (d < minGapM) continue
            val b = trBearing(last, p)
            val turned = lastB != null && trTurn(b, lastB!!) > turnDeg
            if (turned) {
                val prev = pts[i - 1]   // keep the CORNER, not just the point after it
                if (prev != last && trDistM(last, prev) >= minGapM) out += prev
                out += p; lastB = trBearing(out[out.size - 2], p)
            } else if (d >= maxGapM) { out += p; lastB = b } else if (lastB == null && d >= minGapM * 3) { lastB = b }
        }
        out += pts.last()
        return out
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

    /** CHANGE TYPE: change waypoint type. Caller refreshes its map. */
    suspend fun changeType(context: Context, waypointId: String, newTypeId: String) {
        withContext(Dispatchers.IO) {
            SpatialDbManager.init(context)
            SpatialDbManager.changeWaypointType(waypointId, newTypeId)
        }
        Log.d(TAG, "CHANGE TYPE $waypointId -> $newTypeId")
    }

    /** Shared GPX builder for SHARE/EXPORT (type-dispatched). null = no GPX for type. */
    private suspend fun buildGpx(context: Context, artifactType: String, artifactId: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            SpatialDbManager.init(context)
            when (artifactType) {
                "Waypoints" -> SpatialDbManager.buildWaypointGpxById(artifactId)
                "Routes" -> SpatialDbManager.buildRouteGpxById(artifactId)
                "Trails" -> SpatialDbManager.buildTrailGpxById(artifactId)
                "Tracks" -> SpatialDbManager.buildTrackGpxById(artifactId)
                else -> null
            }
        }

    /** SHARE: build GPX + hand to share sheet. Toasts on failure (op owns UI feedback). */
    suspend fun share(context: Context, artifactType: String, artifactId: String) {
        val gpx = buildGpx(context, artifactType, artifactId)
        withContext(Dispatchers.Main) {
            if (gpx != null) {
                ConvoyTrackOps.shareGpx(context, gpx.first, gpx.second)
            } else {
                android.widget.Toast.makeText(context, "Share failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** EXPORT: build GPX + write to Downloads. Toasts result (op owns UI feedback). */
    suspend fun export(context: Context, artifactType: String, artifactId: String) {
        val gpx = buildGpx(context, artifactType, artifactId)
        val ok = if (gpx != null) withContext(Dispatchers.IO) {
            ConvoyTrackOps.exportGpxToDownloads(gpx.first, gpx.second)
        } else false
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context,
                if (ok == true) "Exported to Downloads" else "Export failed",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** DELETE ALIAS: remove one alias row. Caller refreshes its alias list. */
    suspend fun deleteAlias(context: Context, aliasId: String) {
        withContext(Dispatchers.IO) {
            SpatialDbManager.init(context)
            SpatialDbManager.deleteAlias(aliasId)
        }
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
