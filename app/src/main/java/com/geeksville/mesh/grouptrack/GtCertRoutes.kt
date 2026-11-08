package com.geeksville.mesh.grouptrack

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.RouteExplorerCertOld as Old
import com.grouptrack.shared.route.GtRoute as New

/**
 * STEP13C-2026-11-07: the route builder certification -- 2.7e's RouteExplorer (the TEST-ONLY copy RouteExplorerCertOld) against the
 * shared GtRoute, on the FROZEN copy of Droid 1's databases (gtcert_frozen, read-only). STEP13C-B (Fred 19:23: public / private are
 * classified differently now, so one land setting leaves some recipe areas with no rides): every request runs TWICE -- land PUBLIC,
 * then land ALL (public + private) -- the rest of FILTER as it is on the Droid; both sides read the same shared TrailFilterState.
 * STEP13C-C: one trail network in memory at a time (19:25 OOM); report also to GroupTrack/gtcert_routes_<time>.txt; toRadians probe.
 * The land setting is put back exactly as it was when the run ends (it is saved to the filter file; restored in finally). Requests: every saved route's AI recipe (route_notes 'narrative' payload ->
 * recipe with milesHigh; duplicates once) + two made up -- must-visit points (explore with includePoints + assess Loop) and a
 * point-to-point assess. For each: explore's suggestions, each draft (org.json, createdAt/updatedAt removed), lastStats, assess,
 * selectionTable / selectionBreakdown / connectedMiles / nearestShownTrailMeters. Lines: ROUTES|<what>|<key>|SAME or DIFF (+ both).
 * Drafts never reach RouteDraftStore (tests never write the live drafts).
 * ⚠ TEST-ONLY (CODE RULE 3): remove with GtCertRun before 2.8 is released.
 */
object GtCertRoutes {
    private const val TAG = "CertRun"

    private class Rq(val key: String, val lat: Double, val lon: Double, val mLo: Double, val mHi: Double, val sLo: Double, val sHi: Double,
                     val pins: List<Pair<Double, Double>>, val end: Pair<Double, Double>?)

    fun run(spatialPath: String, dataPath: String?, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0
        // STEP13C-C: every line also goes to GroupTrack/gtcert_routes_<time>.txt (adb-readable), flushed per request, so a run that
        // dies still leaves its report; each DIFF is also logged (CertRun) with the first 300 characters of both sides.
        val ext = com.geeksville.mesh.convoy.GroupTrackStorage.dir("gtcert_routes_" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt").bufferedWriter()
        fun put(s: String) { w.write(s); ext.write(s) }
        fun check(what: String, key: String, a: String, b: String) {
            if (a == b) { same++; put("ROUTES|$what|$key|SAME\n") }
            else { diff++; put("ROUTES|$what|$key|DIFF\n  OLD|$a\n  NEW|$b\n"); Log.w(TAG, "GTCERT DIFF $what $key\n OLD ${a.take(300)}\n NEW ${b.take(300)}") }
        }
        // STEP13C-C: which degrees-to-radians formula this Droid's Math.toRadians is (PORT_NOTES risk 1)
        run { var m1 = 0; var m2 = 0; var n = 0; val rr = java.util.Random(5)
            repeat(100000) { val x = (rr.nextDouble() - 0.5) * 360.0; n++
                if (Math.toRadians(x) == x / 180.0 * Math.PI) m1++; if (Math.toRadians(x) == x * 0.017453292519943295) m2++ }
            put("# toRadians probe: $n values -- equals x/180*PI: $m1, equals x*0.017453292519943295: $m2\n") }
        val fS = SQLiteDatabase.openDatabase(spatialPath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val conn = GtAppDb.wrap(fS)
            val reqs0 = requests(fS, dataPath)
            val tf = com.geeksville.mesh.convoy.TrailFilterState
            val landWas = tf.land
            try { for (land in listOf("PUBLIC", "ALL")) {
            tf.setLand(land)
            val reqs = reqs0.map { Rq(it.key + "[$land]", it.lat, it.lon, it.mLo, it.mHi, it.sLo, it.sHi, it.pins, it.end) }
            put("# routes: ${reqs.size} requests, land $land, filter '${tf.whereOrEmpty()}'\n")
            Log.i(TAG, "GTCERT routes: ${reqs.size} requests, land $land")
            for ((i, r) in reqs.withIndex()) {
                val name = "certroute_${i + 1}"
                val oReq = Old.Request(r.lat, r.lon, name, r.mLo, r.mHi, r.sLo, r.sHi, includePoints = r.pins)
                val nReq = New.Request(r.lat, r.lon, name, r.mLo, r.mHi, r.sLo, r.sHi, includePoints = r.pins)
                val oDrafts = LinkedHashMap<String, String>(); val nDrafts = LinkedHashMap<String, String>()
                Old.certDraftSink = { n, j -> oDrafts[n] = j; true }
                New.env = New.Env(nowIso = { "2026-11-07T00:00:00Z" }, writeDraft = { n, j -> nDrafts[n] = j; true })
                if (r.end == null || r.pins.isEmpty()) {
                    val ta = System.currentTimeMillis()
                    val o = try { Old.explore(fS, oReq).joinToString("\n") { s -> "${s.name}|${s.miles}|${s.hoursLow}|${s.hoursHigh}|${s.featureCount}|${s.featureMix}|${s.draftName}" } } catch (e: Exception) { "EXCEPTION $e" }
                    Old.certClearCache()   // STEP13C-C: one trail network in memory at a time
                    val tb = System.currentTimeMillis()
                    val n = try { New.explore(conn, nReq).joinToString("\n") { s -> "${s.name}|${s.miles}|${s.hoursLow}|${s.hoursHigh}|${s.featureCount}|${s.featureMix}|${s.draftName}" } } catch (e: Exception) { "EXCEPTION $e" }
                    New.clearCache()
                    val tc = System.currentTimeMillis()
                    put("# ${r.key}: old ${tb - ta} ms, new ${tc - tb} ms, ${oDrafts.size} / ${nDrafts.size} drafts\n")
                    check("EXPLORE", r.key, o, n)
                    val os = Old.lastStats; val ns = New.lastStats
                    check("STATS", r.key, "${os.trails}|${os.networks}|${os.junctions}|${os.features}|${os.areaMi}|${os.stop}|${os.omitted}",
                        "${ns.trails}|${ns.networks}|${ns.junctions}|${ns.features}|${ns.areaMi}|${ns.stop}|${ns.omitted}")
                    check("DRAFTNAMES", r.key, oDrafts.keys.joinToString(","), nDrafts.keys.joinToString(","))
                    for (k in oDrafts.keys) check("DRAFT", "${r.key}/$k", canon(oDrafts[k]), canon(nDrafts[k]))
                }
                if (r.pins.isNotEmpty()) {
                    val oShape: Old.RouteShape = if (r.end == null) Old.RouteShape.Loop else Old.RouteShape.PointToPoint(r.end.first, r.end.second)
                    val nShape: New.RouteShape = if (r.end == null) New.RouteShape.Loop else New.RouteShape.PointToPoint(r.end.first, r.end.second)
                    check("ASSESS", r.key, try { Old.assess(fS, oReq, r.pins, oShape).toString() } catch (e: Exception) { "EXCEPTION $e" },
                        try { New.assess(conn, nReq, r.pins, nShape).toString() } catch (e: Exception) { "EXCEPTION $e" })
                }
                check("SELTABLE", r.key, Old.selectionTable(fS, oReq).toString(), New.selectionTable(conn, nReq).toString())
                check("SELBREAKDOWN", r.key, Old.selectionBreakdown(fS, oReq), New.selectionBreakdown(conn, nReq))
                check("CONNECTED", r.key, Old.connectedMiles(fS, oReq).toString(), New.connectedMiles(conn, nReq).toString())
                check("NEARESTTRAIL", r.key, Old.nearestShownTrailMeters(fS, r.lat, r.lon).toString(), New.nearestShownTrailMeters(conn, r.lat, r.lon).toString())
                Old.certClearCache(); New.clearCache(); ext.flush()
                Log.i(TAG, "GTCERT routes ${i + 1}/${reqs.size} ${r.key}: same $same diff $diff")
            }
            } } finally { if (tf.land != landWas) tf.setLand(landWas); put("# land put back: ${tf.land}\n") }
        } finally { Old.certDraftSink = { _, _ -> true }; try { fS.close() } catch (_: Exception) {} }
        put("# routes done: same $same diff $diff in ${(System.currentTimeMillis() - t0) / 1000} s\n"); try { ext.close() } catch (_: Exception) {}
        Log.i(TAG, "GTCERT routes done: same $same diff $diff")
    }

    /** a draft as org.json reads it (so "2" and "2.0" are the same number), the two clock fields removed. */
    private fun canon(j: String?): String = if (j == null) "MISSING" else try {
        val o = org.json.JSONObject(j); o.remove("createdAt"); o.remove("updatedAt"); o.optJSONObject("notes")?.remove("generated"); o.toString()   // STEP13C-E: the third clock field
    } catch (e: Exception) { "BADJSON $e" }

    /** every saved route's AI recipe (once each), then the two made-up requests near the first recipe's start. */
    private fun requests(fS: SQLiteDatabase, dataPath: String?): List<Rq> {
        val out = ArrayList<Rq>(); val seen = HashSet<String>()
        val names = HashMap<String, String>()
        fS.rawQuery("SELECT route_id, name FROM routes", null).use { c -> while (c.moveToNext()) names[c.getString(0)] = c.getString(1) ?: "" }
        if (dataPath != null) SQLiteDatabase.openDatabase(dataPath, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            val rows = ArrayList<Pair<String, org.json.JSONObject>>()
            d.rawQuery("SELECT route_id, payload FROM route_notes WHERE kind='narrative'", null).use { c ->
                while (c.moveToNext()) {
                    val r = try { org.json.JSONObject(c.getString(1) ?: "").optJSONObject("recipe") } catch (_: Exception) { null } ?: continue
                    if (!r.has("milesHigh") || r.isNull("milesHigh")) continue   // converted or drawn routes carry no search recipe
                    rows.add((names[c.getString(0)] ?: c.getString(0)) to r)
                }
            }
            for ((nm, r) in rows.sortedBy { it.first }) {
                val pins = r.optJSONArray("pins")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optDouble("lat") to it.optDouble("lon") } } ?: emptyList()
                val k = listOf(r.optDouble("anchorLat"), r.optDouble("anchorLon"), r.optDouble("milesLow"), r.optDouble("milesHigh"),
                    r.optDouble("mphLow"), r.optDouble("mphHigh"), pins).joinToString("|")
                if (!seen.add(k)) continue
                out.add(Rq("recipe:" + nm.replace('|', '/'), r.optDouble("anchorLat"), r.optDouble("anchorLon"), r.optDouble("milesLow"),
                    r.optDouble("milesHigh"), r.optDouble("mphLow"), r.optDouble("mphHigh"), pins, null))
            }
        }
        val a = out.firstOrNull() ?: return out
        // two made-up requests: the two waypoints 5-15 miles from the first recipe's start, as must-visit points (loop), then as a
        // point-to-point (start -> first point, the second point on the way)
        val pts = ArrayList<Triple<Double, Double, Double>>()
        val dLat = 15.0 / 69.0; val dLon = 15.0 / (69.0 * Math.cos(Math.toRadians(a.lat)))
        fS.rawQuery("SELECT min_lat, min_lon FROM waypoints WHERE min_lat BETWEEN ? AND ? AND min_lon BETWEEN ? AND ? ORDER BY min_lat, min_lon",
            arrayOf((a.lat - dLat).toString(), (a.lat + dLat).toString(), (a.lon - dLon).toString(), (a.lon + dLon).toString())).use { c ->
            while (c.moveToNext()) {
                val la = c.getDouble(0); val lo = c.getDouble(1)
                val mi = Math.hypot((la - a.lat) * 69.0, (lo - a.lon) * 69.0 * Math.cos(Math.toRadians(a.lat)))
                if (mi in 5.0..15.0) pts.add(Triple(la, lo, mi))
            }
        }
        val two = pts.sortedBy { it.third }.take(2).map { it.first to it.second }
        if (two.size == 2) {
            out.add(Rq("madeup:pins-loop", a.lat, a.lon, a.mLo, a.mHi, a.sLo, a.sHi, two, null))
            out.add(Rq("madeup:point-to-point", a.lat, a.lon, a.mLo, a.mHi, a.sLo, a.sHi, listOf(two[1]), two[0]))
        }
        return out
    }
}
