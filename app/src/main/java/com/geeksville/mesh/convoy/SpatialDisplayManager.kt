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

    /**
     * TRACE-2026-09-01. Set to a trail name (or part of one) to follow ONE
     * artifact through every stage between the query and the WebView. Empty
     * string logs the counts only.
     * ⚠ TEMPORARY DIAGNOSTIC -- remove with the TRACE block below once the
     * missing-trail cause is found.
     */
    private const val TRACE_NAME = "Spanish George"

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
            // TRAILS-GATE-ORDER-2026-07-31: minZoom 8 -> 11.
            //
            // ⭐ THIS IS WHAT MAKES THE CAP SAFE. Below z11 the viewport is a
            // whole state and every trail matches — the case that ANR'd at
            // 120,000 and churned membership at 500. Gating it away means the
            // cap should never bind in normal use.
            //
            // ⚠ COST: no trails below z11. Judging corridor coverage before a
            // download happens on an emptier map. Accepted — at that density
            // they were unreadable anyway, which is what started this.
            //
            // ⚠ If z11 still stalls, raise this to 12 BEFORE lowering the cap.
            // SVG DOM construction is the constraint, not the row count.
            // TRAILS-MINZOOM-10-2026-07-31: 11 -> 10 after device testing (Fred).
            // ⚠ At z10 the viewport roughly quadruples and the 20,000 cap DOES
            // bind in dense country — you see the top 20,000 by the ORDER BY
            // (named first, then largest extent), not everything. That is only
            // tolerable BECAUSE of the ORDER BY: without it, binding the cap
            // meant an arbitrary rowid subset that churned on every pan.
            // If z10 stalls, go back to 11 — do NOT lower the cap, or trails
            // start disappearing at z11 too, where they currently all fit.
            // TRAILS-Z11-10K-2026-07-31: back to 11 after z10 was tested and rejected.
            // TRAILS-Z10-RETRY-2026-08-03: 11 -> 10 again (Fred 08-03).
            // ⭐⭐ THE REJECTION ABOVE DOES NOT APPLY TO THE CURRENT CODE. Fred
            // 08-03: z10 was reverted BEFORE the display was capped at the first
            // 10,000 trails. So it was measured on an UNCAPPED z10 viewport -
            // a whole state, every trail matching, which is the 120,000-at-z8
            // case that ANR'd. It never tested a capped z10 at all.
            // ⭐ The marker name TRAILS-Z11-10K-2026-07-31 reads as though the cap
            // was already in place when it reverted. IT WAS NOT. Do not honour
            // that rejection as evidence about this configuration.
            // ⭐ Second change since: the same 07-31 commit switched both maps from
            // L.svg to L.canvas - one surface, no DOM element per feature - so the
            // DOM-construction cost the rejection rested on is gone too.
            // ⚠ Still a RETRY, not a proven win. Canvas fails differently -
            // frame time, not ANR. VERIFY: fit a large-extent trail at z10 and
            // watch for pan lag. If it stalls, put this back to 11 and do NOT
            // lower the row cap (see the z10 note above for why).
            // ⚠ The cap figure in the comment above says 20,000; the release board
            // says 10,000. One of the two is stale - check the limit block before
            // relying on either number.
            // TRAILS-Z10-REVERTED-ANR-2026-08-03: 10 -> 11. Fred saw ANR messages at z10 on device.
            // ⭐⭐ THIS CLOSES THE z10 QUESTION - it is not the same result as 07-31.
            // 07-31 was measured UNCAPPED on L.svg. This attempt was CAPPED at
            // 10,000 rows on L.canvas - both objections addressed - and it STILL
            // ANRs. So the constraint is neither DOM construction nor an uncapped
            // result set: it is the ROW COUNT ITSELF at a z10 viewport, where the
            // area is ~4x z11. Do not attempt z10 again without a fundamentally
            // different mechanism (vector tiles / MapLibre - that is 2.7).
            // ⚠ CORRECTION to the 08-03 comment above: it claimed canvas fails on
            // frame time rather than ANR. Device evidence says canvas ANRs too.
            // ⛔ DO NOT lower the row cap as an alternative. At z11 trails currently
            // all fit; lowering the cap makes them disappear at z11 as well.
            // ZOOM-Z9-2026-08-09: trails gate lowered to 9.
            //
            // *** CORRECTS THE CONCLUSION IN THE COMMENT BLOCK ABOVE. ***
            // That block ends by saying this level must not be attempted again
            // without vector tiles / MapLibre. Fred 08-09: that is NOT what the
            // device showed. The ANR was a ROW-COUNT event - it happened with a
            // twenty-thousand-row cap. The level itself ran fine. The later move
            // back up was a PRODUCT judgement about what testers needed, and a
            // survey since has reversed it: they want to orient at a wide view
            // and zoom in for detail.
            //
            // *** WHY THIS IS SAFE NOW, STRUCTURALLY. *** The trail set itself was
            // cut to 10,000 rows total. The cap below can therefore NEVER bind at
            // any zoom - the worst case at any viewport is the entire set, which
            // is half the row count that ANR'd. Going wider costs nothing once the
            // set is smaller than the cap.
            //
            // IF IT DOES STALL: drop the trail set to 7,500 (Fred's stated lever).
            // Do NOT raise this gate back up as the first move, and do NOT lower
            // the cap below - at the previous gate trails all fit, and a tighter
            // cap makes them vanish there too.
            "updateTrails", "showTrails", "hideTrails", 9
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

        // TRAILS-LIMIT-ALL-2026-07-31: trails get their own branch.
        //
        // ⭐ THE DEFECT THIS FIXES: queryTrailsByViewport has NO `ORDER BY`, so
        // `LIMIT 500` returned whichever 500 rows SQLite reached first — rowid
        // order, i.e. import insertion order, which is spatially meaningless.
        // At z8 roughly 89,536 Utah trails match the bbox and 500 drew. Move
        // the viewport slightly and a DIFFERENT arbitrary 500 came back.
        // Nothing was ever misplaced — the MEMBERSHIP CHURNED.
        //
        // ⭐ Which is why TRACKS looked fine throughout: a few hundred exist, so
        // their cap never binds. And why it appeared to "resolve at z12" —
        // that is simply where the viewport shrinks below 500 trails. There is
        // no z12 threshold in this code; the only 12 is the Tracks row limit.
        //
        // ⚠⚠ RISK: both maps render with L.svg, so this is a DOM element per
        // trail. If z8 stalls the WebView, the lever is the RENDERER
        // (L.canvas — one surface, handles six figures) NOT a lower cap.
        // Lowering the cap just restores the churn. Canvas changes hit-testing,
        // so tap handling needs re-checking if it comes to that.
        //
        // Waypoints and Routes keep the old behaviour deliberately.
        val limit = when (type) {
            // ZOOM-Z9-2026-08-09: tracks cap is now FLAT.
            // It previously halved above a mid-level threshold and returned a
            // fraction of the set below it - so zooming out to orient showed only
            // a quarter of a rider's tracks, which reads as broken. Tracks were
            // never gated by minZoom (it is 0); this cap was the actual limiter.
            // ~200 tracks in practice, so 500 draws everything with headroom.
            "Tracks" -> 500
            // TRAILS-GATE-ORDER-2026-07-31: 120,000 ANR'd the WebView at z8 — SVG builds
            // a DOM element per trail. This is now a SAFETY VALVE, not the
            // mechanism: the minZoom 11 gate below means the wide-viewport case
            // that matched 120,000 rows never happens. If it ever binds, the
            // ORDER BY in queryTrailsByViewport makes the result STABLE.
            // TRAILS-Z11-10K-2026-07-31: 20,000 -> 10,000 with the gate back at z11.
            // z10 was tested and rejected — the viewport quadruples and 20,000
            // SVG paths is more DOM than the WebView builds comfortably.
            // ⭐ A tighter cap is only safe BECAUSE of the ORDER BY: binding it
            // now returns the SAME named-and-largest 10,000 every time, where
            // before it returned an arbitrary rowid subset that churned on pan.
            // TRAILS-CAP-2026-08-09B: cap is now ZOOM-DEPENDENT.
            //
            // The gate came down to 9 the same day (see ZOOM-Z9-2026-08-09
            // above), which opened viewports several times wider than the
            // level this cap was sized for. On device the draw was too slow
            // there. Cutting the wide-view cap fixes the draw without
            // touching the close-in case, where far fewer rows match anyway
            // and the cap does not bind.
            //
            // *** DISPLAY CAP ONLY - THE TRAIL SET IS UNCHANGED AT 10,000. ***
            // Nothing leaves the database. The ORDER BY in
            // queryTrailsByViewport (named first, then largest extent) makes
            // the capped subset STABLE across pans and keeps the trails worth
            // seeing - which is what makes a tighter cap tolerable here where
            // it was not before, when binding it returned an arbitrary rowid
            // subset that churned on every pan.
            //
            // IF THE WIDE VIEW IS STILL SLOW: lower the low-zoom figure
            // first. Do not raise the gate back up - the wide view is what
            // testers asked for (orient wide, zoom for detail).
            "Trails" -> if (zoom < 10) 2_000 else 5_000
            else -> if (zoom < 14) 500 else 2000
        }

        val raw = b.query(south, west, north, east, limit)
        val items = if (state == DS_SELECTED && checkedIds != null)
            raw.filter { it[b.idField] in checkedIds } else raw

        val json = b.build(items)

        // ── TRACE-2026-09-01 ────────────────────────────────────────────
        // ⛔ WHY THIS EXISTS. Spanish George Road is in the trails table with
        // valid geometry, five miles long, the query returns it when run BY
        // HAND against the device's own database -- and it does not draw. Two
        // readable causes were found and fixed (name-first ordering, and the
        // DS_SELECTED filter) and it STILL does not draw. Fred: "I have no
        // confidence that any of my other impressions are valid until this is
        // solved."
        // ⭐ So stop reasoning and measure. Every stage between the query and
        // the WebView reports its count, and one named trail is followed
        // through each of them.
        // ⚠ TEMPORARY. Remove once the cause is found -- it logs on EVERY
        // viewport change, which is every pan and every zoom.
        try {
            val probe = TRACE_NAME
            if (probe.isNotEmpty()) {
                val inRaw = raw.count { r ->
                    val nm: String = r["name"] ?: ""
                    nm.contains(probe, ignoreCase = true)
                }
                val inItems = items.count { r ->
                    val nm: String = r["name"] ?: ""
                    nm.contains(probe, ignoreCase = true)
                }
                // ⚠ The JSON is the LAST thing before the WebView. If the row
                // is in `items` but its name is not in the JSON, the failure is
                // in b.build() -- the WKT-to-GeoJSON conversion -- and not in
                // any query or filter.
                val inJson = json.contains(probe, ignoreCase = true)
                android.util.Log.i("SpatialDisplay",
                    "TRACE $type: raw=${raw.size} items=${items.size} " +
                        "json=${json.length}b state=$state " +
                        "checked=${checkedIds?.size ?: -1} limit=$limit | " +
                        "'$probe' raw=$inRaw items=$inItems json=$inJson")
                // ⭐ When the trail IS in raw but NOT in the json, dump the row
                // so we can see what is different about it. Geometry length is
                // the first suspect: this one is a 5-mile line inside a bbox
                // 120 FEET tall, which is as close to degenerate as a real
                // trail gets.
                if (inRaw > 0 && !inJson) {
                    raw.filter { r ->
                        val nm: String = r["name"] ?: ""
                        nm.contains(probe, ignoreCase = true)
                    }.forEach { r ->
                        val dump = StringBuilder()
                        for ((k, v) in r) {
                            val sv: String = v ?: "null"
                            dump.append(k).append("=")
                                .append(if (sv.length > 90) sv.substring(0, 90) else sv)
                                .append("  ")
                        }
                        android.util.Log.w("SpatialDisplay", "TRACE DROPPED: $dump")
                    }
                }
            } else {
                android.util.Log.i("SpatialDisplay",
                    "TRACE $type: raw=${raw.size} items=${items.size} " +
                        "json=${json.length}b state=$state " +
                        "checked=${checkedIds?.size ?: -1} limit=$limit")
            }
        } catch (e: Exception) {
            android.util.Log.w("SpatialDisplay", "TRACE failed: ${e.message}")
        }

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
        // NOPAD-2026-08-04: 15% PAD REMOVED. Move the map to the saved frame EXACTLY.
        // Added 8a0e4e70b with FIT, on top of fitBounds() which was ALREADY
        // padding by 60px since V2.4 - the two compounded. Neither was
        // FIT-specific: this is the only Kotlin caller of fitBounds(), so map
        // re-entry paid for FIT's framing and zoomed out a little every time.
        val fS = bbox.south; val fN = bbox.north
        val fW = bbox.west;  val fE = bbox.east
        webView?.post {
            webView.evaluateJavascript("fitBounds([$fS,$fN],[$fW,$fE])", null)
        }
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
