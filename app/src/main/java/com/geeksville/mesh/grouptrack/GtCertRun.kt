package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.ImportConverter
import com.geeksville.mesh.convoy.LandAreas
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.SpatialDbManager
import com.grouptrack.shared.cert.GtCert
import com.grouptrack.shared.db.gtOpenDatabase

/**
 * GTCERT-2026-11-06 (2.8, Fred 13:17): THE WHOLE-DATABASE CERTIFICATION RUN on a Droid -- OLD (2.7e's own code, a FROZEN copy below,
 * on the app's SQLiteDatabase handles) and NEW (the shared code, grouptrack-shared GtCert.runNew, on Android's own SQLite) over the
 * entire spatial database: every filter element on and off by itself, every trail through the builder, every track / waypoint / route /
 * land area, the GPX of every waypoint, route and trail. One line per check, same format both sides (see GtCert). Written to
 * Download/GroupTrack_CERT_<droid>_<time>.txt; compared with the Mac's run by cert_compare.
 *  - Runs ONLY when GroupTrack/cert_run.request exists (adb shell touch ...); the file is deleted first: one request = one run.
 *  - On its own thread; log tag CertRun (progress, total time). Reads only.
 * ⚠ TEST-ONLY (CODE RULE 3): remove with GtCert before 2.8 is released.
 */
object GtCertRun {
    private const val TAG = "CertRun"

    /** STEP14C-G-2026-11-08: true from the moment a request is taken until the app process ends. DbAudit's startup repair checks it
     *  and does not run: the cert run swaps SpatialDbManager's handles, and the repair reads them at two different moments. */
    @Volatile var active = false

    fun runIfRequested(ctx: Context) {
        val request = GroupTrackStorage.dir("cert_run.request", ctx)
        if (!request.exists()) return
        // GTCERTW-2026-11-06: what the request file says -- "writes" = only the writes (step 10), "all" = reads then writes, empty = reads; "lookups" = only the app's lookups (STEP7)
        val mode = try { request.readText().trim().lowercase() } catch (_: Exception) { "" }
        val deleted = request.delete()
        Log.i(TAG, "GTCERT request found (deleted=$deleted, mode='$mode') -- whole-database run starting")
        active = true   // STEP14C-G-2026-11-08: DbAudit's repair stays off for this app run
        val app = ctx.applicationContext
        Thread {
            val t0 = System.currentTimeMillis()
            try {
                SpatialDbManager.init(app)
                val db = SpatialDbManager.getSpatialDb() ?: run { Log.e(TAG, "GTCERT no spatial database"); return@Thread }
                val edb = SpatialDbManager.getExtensionDb()
                // CODE RULE 1: the data db is optional exactly as in 2.7e (see GtSpatial)
                val conn = gtOpenDatabase(db.path, true)
                val dconn = edb?.let { gtOpenDatabase(it.path, true) }
                com.geeksville.mesh.convoy.TrailFilterState.load()
                val f = com.geeksville.mesh.convoy.TrailFilterState
                val saved = GtCert.Combo("saved", f.land, f.restricted, f.use, f.off.toSet())
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())
                val out = java.io.File(app.cacheDir, "GroupTrack_CERT_droid_$stamp.txt")
                out.bufferedWriter().use { w ->
                    w.write("# GTCERT droid ${android.os.Build.MODEL} $stamp spatial=${db.path}\n")
                    // STEP7-2026-11-07: "lookups" = ONLY the app's own lookup functions (SpatialDbManager, now on GtRead) through CertRead, written as
                    // OLD lines -- compared with the SAVED 2.7e lines of run 2026-11-06_1606 (cert_baseline_2026-11-07.py). No NEW side, no Mac.
                    // STEP8B-2026-11-07: "freeze" = copy the live databases into gtcert_frozen/ and run the lookups on that copy over EVERY
                    // trail page (the new baseline); "lookups" = the lookups on the frozen copy (every 4th trail page). Never the live files.
                    if (mode == "lookups" || mode == "freeze") { var o = 0
                        val fz = if (mode == "freeze") freeze(app, db, edb, stamp) else frozen(app)
                        if (fz == null) Log.e(TAG, "GTCERT failed: no frozen copy on this Droid -- run gt_cert with mode freeze first")
                        else {
                            w.write("# frozen=${fz.stamp}\n")
                            // the baseline covers every trail page so any later rotation finds its pages in it
                            val sample = if (mode == "freeze") com.grouptrack.shared.cert.CertRead.TrailSample.ALL
                                else com.grouptrack.shared.cert.CertRead.TrailSample(4, java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR) % 4)
                            Log.i(TAG, "GTCERT app: frozen copy of ${fz.stamp}" + (if (mode == "freeze") " (just made)" else "") + " -- trail pages every ${sample.every} from page ${sample.start}")
                            val fS = SQLiteDatabase.openDatabase(fz.spatial, null, SQLiteDatabase.OPEN_READONLY)
                            val fE = fz.data?.let { SQLiteDatabase.openDatabase(it, null, SQLiteDatabase.OPEN_READONLY) }
                            val prev = SpatialDbManager.certSwapDatabases(fS, fE)
                            val fconn = gtOpenDatabase(fz.spatial, true)
                            try {
                                com.grouptrack.shared.cert.CertRead.run(OldRead, fconn, sample, { l -> w.write("OLD|$l\n"); o++ }, { p -> Log.i(TAG, "GTCERT app: $p ($o lines)") })
                            } finally {
                                try { fconn.close() } catch (_: Exception) {}
                                SpatialDbManager.certSwapDatabases(prev.first, prev.second)
                                try { fS.close() } catch (_: Exception) {}; try { fE?.close() } catch (_: Exception) {}
                                Log.i(TAG, "GTCERT live handles restored")
                            }
                            Log.i(TAG, "GTCERT app lookups: $o lines in ${(System.currentTimeMillis() - t0) / 1000} s")
                        }
                    }
                    // STEP13C-2026-11-07: "routes" = the route builder, 2.7e RouteExplorer vs shared GtRoute, on the frozen copy (read-only)
                    if (mode == "routes") { val fz = frozen(app)
                        if (fz == null) Log.e(TAG, "GTCERT failed: no frozen copy on this Droid -- run gt_cert with mode freeze first")
                        else { w.write("# frozen=${fz.stamp}\n"); GtCertRoutes.run(fz.spatial, fz.data, w) } }
                    // STEP14C-2026-11-08: "rides" = the ride model, 2.7e ConvoyRideStore & co vs shared GtRides, on copies of the frozen copy
                    if (mode == "rides") { val fz = frozen(app)
                        if (fz == null) Log.e(TAG, "GTCERT failed: no frozen copy on this Droid -- run gt_cert with mode freeze first")
                        else { w.write("# frozen=${fz.stamp}\n"); GtCertRides.run(app, fz.spatial, fz.data, fz.stamp, w) } }
                    // STEP15B-2026-11-09: "parse15b" = the import parsers and converter, 2.7e (ImportParseCertOld / ImportConverterCertOld)
                    // vs shared GtGpxParse / GtImportConverter, on the files adb-pushed to GroupTrack/cert15b/. Reads no database.
                    if (mode == "parse15b") GtCert15b.run(app, w)
                    // GEOMONE-2026-11-09: "geom" = THE ONE geometry module (shared GtGeom) vs EVERY stored row of the frozen copy (CertGeom):
                    // rebuilt text == stored text, hash == stored hash, the end-ride lookup finds each track. Read-only. Mac side: GtCertGeomMacTest.
                    if (mode == "geom") { val fz = frozen(app)
                        if (fz == null) Log.e(TAG, "GTCERT failed: no frozen copy on this Droid -- run gt_cert with mode freeze first")
                        else { w.write("# frozen=${fz.stamp}\n"); val gc = gtOpenDatabase(fz.spatial, true)
                            try { val s = com.grouptrack.shared.cert.CertGeom.run(gc, { l -> w.write(l); w.write("\n") }, { p -> Log.i(TAG, "GTCERT geom: $p") })
                                Log.i(TAG, "GTCERT geom: ${s.rows} rows, ${s.diff} DIFF, ${s.noncanonical} NONCANONICAL") }
                            finally { try { gc.close() } catch (_: Exception) {} } } }
                    // STEP15C-2026-11-09 (b, Fred 11-09 Q4): "rider15c" = rider trails, 2.7e (RiderTrailWriterCertOld / RiderTrailScannerCertOld) vs
                    // shared GtRiderTrails / GtRiderScan, each side on its OWN fresh copy of GroupTrack/cert15c_input/ -- the Mac's live_2026-11-08,
                    // pushed by gt_cert_v19b, the same data the iPad test (GtCertRider15cMacTest) uses. Never the frozen copy; no input -> STOP.
                    if (mode == "rider15c") GtCert15c.run(app, w)
                    // STEP15D-2026-11-09: "track15d" = track add / sync / properties, the app's code (TrackSyncCertOld, a generated copy) vs shared
                    // GtTracks, each side on its OWN fresh copy of GroupTrack/cert15c_input/ (the databases) + cert15d_input/my_tracks/ -- the
                    // Mac's files, pushed by gt_cert_v20, the same input the iPad test (GtCertTrack15dMacTest) uses. No input -> STOP.
                    if (mode == "track15d") GtCert15d.run(app, w)
                    // STEP15E-2026-11-09: "import15e" = the GPX import (the screen's per-file loop + importGpxAllArtifacts), the app's code
                    // (GpxImportCertOld, a generated copy) vs shared GtGpxImport, each side on its OWN fresh copy of GroupTrack/cert15c_input/
                    // (the databases) + cert15d_input/my_tracks/, importing the 15b test set (GroupTrack/cert15b/) three times (NO with maps,
                    // YES, NO again) -- the Mac's files, pushed by gt_cert_v21, the same input the iPad test (GtCertImport15eMacTest) uses.
                    if (mode == "import15e") GtCert15e.run(app, w)
                    if (mode == "trail15f") GtCert15f.run(app, w)   // STEP15F-2026-11-10: the agency trail import, 2.7e vs shared (TEST-ONLY)
                    if (mode == "osm15g") GtCert15g.run(app, w)   // STEP15G-2026-11-10: the OSM extract + import, 2.7e vs shared (TEST-ONLY)
                    if (mode == "state17e1") GtCert17e1.run(app, w)
                    if (mode == "tile17g1a") GtCert17g1a.run(app, w)   // STEP17G1A-2026-11-13: the tile-set arithmetic, 2.7e vs shared -- PURE functions, no Context used (TEST-ONLY)
                    if (mode == "help17g2g") GtCert17g2g.run(app, w)   // STEP17G2G-2026-11-13: the HELP link table, 2.7e on real org.json vs shared (TEST-ONLY)
                    if (mode == "hk17g2f4") GtCert17g2f.run4(app, w)   // STEP17G2F4-2026-11-13: the startup container (G2f piece 4), 2.7e on real SQLite + real files vs shared (TEST-ONLY)
                    if (mode == "hk17g2f3") GtCert17g2f.run3(app, w)   // STEP17G2F3-2026-11-13: the database repair (G2f piece 3), 2.7e on real SQLite + real files vs shared (TEST-ONLY)
                    if (mode == "hk17g2f2") GtCert17g2f.run2(app, w)   // STEP17G2F2-2026-11-13: the database audit (G2f piece 2), 2.7e on real SQLite vs shared (TEST-ONLY)
                    if (mode == "hk17g2f1") GtCert17g2f.run(app, w)   // STEP17G2F1-2026-11-13: startup housekeeping everyLaunch (G2f piece 1), 2.7e vs shared (TEST-ONLY)
                    if (mode == "queue17e2") GtCert17e2.run(app, w)   // STEP17G2E2E-2026-11-12: the state data queue, 2.7e vs shared (TEST-ONLY)   // STEP17G2E1-2026-11-12: a state's kept data, 2.7e vs shared (TEST-ONLY)
                    if (mode == "config17d") GtCert17d.run(app, w)   // STEP17G2D-2026-11-12: the V2 config files, 2.7e vs shared (TEST-ONLY)
                    if (mode == "user17c2") GtCert17c2.run(app, w)   // STEP17G2C2-2026-11-12: the V2 local user, 2.7e vs shared (TEST-ONLY)
                    if (mode == "net17c") GtCert17c.run(app, w)   // STEP17G2C1-2026-11-12: the profile + networks + generator, 2.7e vs shared (TEST-ONLY)
                    if (mode == "ride17b2") GtCert17b2.run(app, w)   // STEP17G2B2-2026-11-12: the ride settings, DataStore vs GtRideSettings (TEST-ONLY)
                    if (mode == "session17b") GtCert17b.run(app, w)   // STEP17G2B1-2026-11-12: the session + settings socket + constants (TEST-ONLY)
                    if (mode == "storage17a") GtCert17a.run(app, w)   // STEP17G2A-2026-11-12: the storage socket, GroupTrackStorage vs GtStorage (TEST-ONLY)
                    if (mode == "queue16c") GtCert16c.run(app, w)   // STEP16C-2026-11-11: the save-by-area writes, 2.7e vs shared (TEST-ONLY)
                    if (mode == "mathtile") GtMathProbe.runTile(app, w)   // MATHTILE-2026-11-10: the save-by-area tile math (TEST-ONLY)
                    if (mode == "mathpow") GtMathProbe.runPow(app, w)   // MATHPOW-2026-11-10: Android's Math.pow(x, 2.0) (TEST-ONLY)
                    if (mode == "mathprobe") GtMathProbe.run(app, w)   // MATHPROBE-2026-11-10: what Android's Math returns (TEST-ONLY)
                    // STEP15A-2026-11-08: "golden15a" = the 15a foundations (Java number / date / org.json text) -- Android's answers and the
                    // shared code's, one line each (GtGolden15a). Reads no database. gt_cert_v12 copies the file to the Mac for GtFoundation15aMacTest.
                    if (mode == "golden15a") { val g = GtGolden15a.write(w); Log.i(TAG, "GTCERT golden15a: ${g.lines} lines, ${g.diffs} DIFF on the Droid") }
                    if (mode != "state17e1" && mode != "config17d" && mode != "user17c2" && mode != "net17c" && mode != "ride17b2" && mode != "session17b" && mode != "storage17a" && mode != "queue16c" && mode != "mathtile" && mode != "osm15g" && mode != "trail15f" && mode != "mathpow" && mode != "mathprobe" && mode != "import15e" && mode != "track15d" && mode != "writes" && mode != "lookups" && mode != "freeze" && mode != "routes" && mode != "rides" && mode != "golden15a" && mode != "parse15b" && mode != "geom" && mode != "rider15c" && mode != "queue17e2" && mode != "hk17g2f1" && mode != "hk17g2f2" && mode != "hk17g2f3" && mode != "hk17g2f4" && mode != "help17g2g" && mode != "tile17g1a") {   // STEP17G2E2J-2026-11-13: queue17e2 skips the whole-database pass, as every other 17-group mode does   // STEP8B-2026-11-07, STEP13C, STEP15A-2026-11-08
                        var n = 0
                        GtCert.runNew(conn, dconn, saved, { id -> notesFor(id) }, { l -> w.write("NEW|$l\n"); n++ }, { p -> Log.i(TAG, "GTCERT new: $p ($n lines)") })
                        com.grouptrack.shared.cert.CertRead.run(com.grouptrack.shared.cert.CertRead.Shared(com.grouptrack.shared.spatial.GtRead(conn, dconn)), conn, com.grouptrack.shared.cert.CertRead.TrailSample.ALL /* STEP8-2026-11-07 */,
                            { l -> w.write("NEW|$l\n"); n++ }, { p -> Log.i(TAG, "GTCERT new: $p ($n lines)") })   // GTCERT-2B-2026-11-06
                        Log.i(TAG, "GTCERT new side: $n lines in ${(System.currentTimeMillis() - t0) / 1000} s")
                        val t1 = System.currentTimeMillis(); var o = 0
                        runOld(conn, db, edb, saved, { l -> w.write("OLD|$l\n"); o++ }, { p -> Log.i(TAG, "GTCERT old: $p ($o lines)") })
                        com.grouptrack.shared.cert.CertRead.run(OldRead, conn, com.grouptrack.shared.cert.CertRead.TrailSample.ALL, { l -> w.write("OLD|$l\n"); o++ }, { p -> Log.i(TAG, "GTCERT old: $p ($o lines)") })   // GTCERT-2B-2026-11-06
                        Log.i(TAG, "GTCERT old side: $o lines in ${(System.currentTimeMillis() - t1) / 1000} s")
                    }
                    if (mode == "writes" || mode == "all") runWrites(app, db, edb, w)   // GTCERTW-2026-11-06
                }
                try { conn.close() } catch (_: Exception) {}; try { dconn?.close() } catch (_: Exception) {}
                val name = ImportConverter.saveToDownloads(app, out)
                Log.i(TAG, "GTCERT done in ${(System.currentTimeMillis() - t0) / 1000} s -> Download/$name")
            } catch (e: Throwable) {
                Log.e(TAG, "GTCERT failed after ${(System.currentTimeMillis() - t0) / 1000} s: $e", e)
            }
        }.start()
    }

    /** GTCERTW-2026-11-06: 2.7e's SpatialDbManager write functions as the OLD side of step 10 -- called while its handles point at a COPY. */
    private object OldWrite : com.grouptrack.shared.cert.CertWrite.Api {
        override fun insertWaypointWithId(id: String, name: String, lat: Double, lon: Double, type: String) = SpatialDbManager.insertWaypointWithId(id, name, lat, lon, type)
        override fun renameWaypoint(id: String, n: String) { SpatialDbManager.renameWaypoint(id, n) }
        override fun deleteWaypoint(id: String) { SpatialDbManager.deleteWaypoint(id) }
        override fun changeWaypointType(id: String, t: String) { SpatialDbManager.changeWaypointType(id, t) }
        override fun insertRoute(name: String, wkt: String, a: Double, b: Double, c: Double, d: Double) = SpatialDbManager.insertRoute(name, wkt, a, b, c, d)
        override fun renameRoute(id: String, n: String) { SpatialDbManager.renameRoute(id, n) }
        override fun deleteRoute(id: String) { SpatialDbManager.deleteRoute(id) }
        override fun writeRouteNotes(routeId: String, notesJson: String) = SpatialDbManager.writeRouteNotes(routeId, org.json.JSONObject(notesJson))
        override fun deleteRouteNotes(routeId: String) = SpatialDbManager.deleteRouteNotes(routeId)
        override fun setRouteAnchor(routeId: String, lat: Double, lon: Double, name: String) = SpatialDbManager.setRouteAnchor(routeId, lat, lon, name)
        override fun addAlias(type: String, id: String, name: String, hash: String) { SpatialDbManager.addAlias(type, id, name, hash, null) }
        override fun renameAlias(aliasId: String, t: String) { SpatialDbManager.renameAlias(aliasId, t) }
        override fun deleteAlias(aliasId: String) { SpatialDbManager.deleteAlias(aliasId) }
        override fun setPreferredAlias(type: String, id: String, aliasId: String) { SpatialDbManager.setPreferredAlias(type, id, aliasId) }
        override fun insertTrack(name: String, wkt: String, a: Double, b: Double, c: Double, d: Double) =
            SpatialDbManager.insertTrackToDb(name, wkt, a, b, c, d).let { "${it.wasNew},${it.trackId},${it.geomHash},${it.name}" }
        override fun renameTrack(id: String, n: String) { SpatialDbManager.renameTrackInDb(id, n) }
        override fun deleteTrack(id: String) { SpatialDbManager.deleteTrackFromDb(id) }
        override fun beginDedup() { SpatialDbManager.beginDedupSession() }
        override fun endDedup() { SpatialDbManager.endDedupSession() }
        override fun sourceUidSeen(s: String, u: String) = SpatialDbManager.sourceUidSeen(s, u)
        override fun markSourceUid(s: String, u: String) { SpatialDbManager.markSourceUid(s, u) }
        override fun insertTrail(id: String, name: String?, wkt: String, a: Double, b: Double, c: Double, d: Double, created: String) =
            SpatialDbManager.insertTrail(id, name, wkt, a, b, c, d, created).let { "${it.first},${it.second}" }
        override fun deleteTrail(id: String) { SpatialDbManager.deleteTrailFromDb(id) }
        override fun clearAllTrails() = SpatialDbManager.clearAllTrailData().let { "${it.trails},${it.properties},${it.aliases},${it.error}" }
    }

    /**
     * GTCERTW-2026-11-06: step 10 -- the WRITES, on two COPIES of the live databases in the app's cache (never the live files):
     * NEW = the shared GtWrite on copy "new"; OLD = 2.7e's SpatialDbManager with its two handles pointed at copy "old" for the run and
     * put back afterwards (finally). The copies are deleted at the end. GPX files are never touched (the script only deletes tracks it made).
     */
    private fun runWrites(app: Context, db: SQLiteDatabase, edb: SQLiteDatabase?, w: java.io.Writer) {
        val e = edb ?: run { Log.e(TAG, "GTCERTW no data database -- writes not run"); return }
        val dir = java.io.File(app.cacheDir, "gtcertw"); dir.deleteRecursively(); dir.mkdirs()
        val tracksDir = com.geeksville.mesh.convoy.ConvoyTrackOps.tracksDir()
        val exists: (String) -> Boolean = { h -> java.io.File(tracksDir, "$h.gpx").exists() }
        fun copy(tag: String): Pair<String, String> {
            val s = java.io.File(dir, "$tag-spatial.db"); val x = java.io.File(dir, "$tag-data.db")
            java.io.File(db.path).copyTo(s, true); java.io.File(e.path).copyTo(x, true); return s.path to x.path
        }
        val t0 = System.currentTimeMillis()
        val (ns, ne) = copy("new"); val (os, oe) = copy("old")
        Log.i(TAG, "GTCERTW copies made in ${(System.currentTimeMillis() - t0) / 1000} s")
        try {
            val c = gtOpenDatabase(ns, false); val d = gtOpenDatabase(ne, false)
            try {
                val gw = com.grouptrack.shared.spatial.GtWrite(c, d, { java.util.UUID.randomUUID().toString() }, { java.time.Instant.now().toString() })
                com.grouptrack.shared.cert.CertWrite.run(com.grouptrack.shared.cert.CertWrite.Shared(gw), c, d, exists, { l -> w.write("NEW|$l\n") }, { p -> Log.i(TAG, "GTCERT new: $p") })
            } finally { c.close(); d.close() }
            val oS = SQLiteDatabase.openDatabase(os, null, SQLiteDatabase.OPEN_READWRITE)
            val oE = SQLiteDatabase.openDatabase(oe, null, SQLiteDatabase.OPEN_READWRITE)
            val prev = SpatialDbManager.certSwapDatabases(oS, oE)
            try {
                val c2 = gtOpenDatabase(os, true); val d2 = gtOpenDatabase(oe, true)
                try { com.grouptrack.shared.cert.CertWrite.run(OldWrite, c2, d2, exists, { l -> w.write("OLD|$l\n") }, { p -> Log.i(TAG, "GTCERT old: $p") }) }
                finally { c2.close(); d2.close() }
            } finally {
                SpatialDbManager.certSwapDatabases(prev.first, prev.second)
                try { oS.close() } catch (_: Exception) {}; try { oE.close() } catch (_: Exception) {}
                Log.i(TAG, "GTCERTW live handles restored")
            }
        } finally { dir.deleteRecursively() }
        Log.i(TAG, "GTCERTW writes done in ${(System.currentTimeMillis() - t0) / 1000} s")
    }

    /**
     * STEP8B-2026-11-07: the FROZEN copy the lookups check reads -- made by mode "freeze" from the live databases, then never changed,
     * so riding and testing on the Droid cannot move the baseline. Kept in the app's own files, not the cache (the system may clear
     * the cache). [data] is null exactly when the live data database is absent, as in 2.7e (CODE RULE 1: see GtSpatial).
     */
    private class Frozen(val spatial: String, val data: String?, val stamp: String)
    private fun frozenDir(app: Context) = java.io.File(app.filesDir, "gtcert_frozen")
    private fun freeze(app: Context, db: SQLiteDatabase, edb: SQLiteDatabase?, stamp: String): Frozen {
        val t = System.currentTimeMillis()
        val dir = frozenDir(app); dir.deleteRecursively(); dir.mkdirs()
        fun cp(src: String, name: String): String {
            val d = java.io.File(dir, name); java.io.File(src).copyTo(d, true)
            val wal = java.io.File("$src-wal"); if (wal.exists() && wal.length() > 0) wal.copyTo(java.io.File(dir, "$name-wal"), true)
            return d.path
        }
        val s = cp(db.path, "spatial.db"); val d = edb?.let { cp(it.path, "data.db") }
        java.io.File(dir, "stamp.txt").writeText(stamp)
        Log.i(TAG, "GTCERT frozen copy made in ${(System.currentTimeMillis() - t) / 1000} s: $s" + (d?.let { " + $it" } ?: " (no data database)"))
        return Frozen(s, d, stamp)
    }
    /** The frozen copy, or null when this Droid has none yet (mode "freeze" makes it). */
    private fun frozen(app: Context): Frozen? {
        val dir = frozenDir(app); val st = java.io.File(dir, "stamp.txt"); val s = java.io.File(dir, "spatial.db")
        if (!st.exists() || !s.exists()) return null
        val d = java.io.File(dir, "data.db")
        return Frozen(s.path, if (d.exists()) d.path else null, st.readText().trim())
    }

    /** GTCERT-2B-2026-11-06: 2.7e's SpatialDbManager reading functions as the OLD side of the batch-2b cases. */
    private object OldRead : com.grouptrack.shared.cert.CertRead.Api {
        override fun search(type: String, term: String) = SpatialDbManager.searchByName(type, term)
        override fun detail(type: String, id: String): Map<String, String?> = SpatialDbManager.getArtifactDetail(type, id)
        override fun aliases(type: String, id: String) = SpatialDbManager.getAliasesFor(type, id)
        override fun list(table: String) = SpatialDbManager.queryArtifactList(table, -90.0, -180.0, 90.0, 180.0)
        override fun corridor() = SpatialDbManager.queryAllTracksForCorridor()
        override fun trailheads(lat: Double, lon: Double) = SpatialDbManager.trailheadsNear(lat, lon).map { listOf<Any>(it.waypointId, it.name, it.lat, it.lon, it.miles) }
        override fun routeUsing(waypointId: String) = SpatialDbManager.routeUsingTrailhead(waypointId)
        override fun routeName(id: String) = SpatialDbManager.routeNameById(id)
        override fun routeHash(id: String) = SpatialDbManager.routeGeomHash(id)
        override fun routeByHash(hash: String) = SpatialDbManager.findRouteIdByHash(hash)
        override fun nameExists(name: String) = SpatialDbManager.routeNameExists(name)
        override fun nextNumber(base: String) = SpatialDbManager.nextRouteNumber(base)
        override fun trackName(id: String) = SpatialDbManager.trackName(id)
        override fun trackGeom(id: String) = SpatialDbManager.trackGeometry(id)
        override fun waypointAt(lat: Double, lon: Double) = SpatialDbManager.waypointNameAt(lat, lon)
        override fun geomHash(wkt: String) = SpatialDbManager.computeGeomHash(wkt)
        override fun parseGpx(text: String) = SpatialDbManager.parseGpxTrackPoints(text)
        override fun trackIdForGpx(text: String) = SpatialDbManager.trackIdForGpxText(text)
    }

    /** (headline, notes JSON) exactly as 2.7e's buildRouteGpxById makes them. */
    private fun notesFor(routeId: String): Pair<String, String?> {
        val notes = SpatialDbManager.readRouteNotes(routeId)
        return (notes?.optJSONObject("narrative")?.optString("headline") ?: "") to notes?.toString()
    }

    fun countFeatures(json: String): Int {
        var n = 0; var i = json.indexOf("{\"type\":\"Feature\""); while (i >= 0) { n++; i = json.indexOf("{\"type\":\"Feature\"", i + 1) }; return n
    }

    /** The OLD side, same checks, same order, same lines as GtCert.runNew -- with 2.7e's code. The enumeration (combos, pages, ids)
     *  comes from GtCert; it only names what to compare. */
    private fun runOld(conn: androidx.sqlite.SQLiteConnection, db: SQLiteDatabase, edb: SQLiteDatabase?, saved: GtCert.Combo,
                       emit: (String) -> Unit, progress: (String) -> Unit) {
        val cats = GtCert.categories(conn)
        for (cb in GtCert.combos(cats, saved)) {
            val w = legacyWhere(cb.land, cb.restricted, cb.use, cb.off)
            val got = ArrayList<String>()
            db.rawQuery("SELECT trail_id FROM trails WHERE 1=1$w ORDER BY trail_id", null).use { c -> while (c.moveToNext()) c.getString(0)?.let { got.add(it) } }
            emit(GtCert.line("WHERE", cb.id, "-", got.size, GtCert.fp(got.joinToString("\n")) + "/" + GtCert.fp(w)))
        }
        progress("filters done")
        val tp = GtCert.pages(conn, "trails", "trail_id", true)
        for (r in listOf(true, false)) {
            val w = legacyWhere("ALL", r, "ALL", emptySet())
            for ((k, frag) in tp) {
                val rows = queryTrails(db, edb, -90.0, -180.0, 90.0, 180.0, 1_000_000, w + frag)
                emit(GtCert.line("TRAILS", k, "r$r", rows.size, GtCert.fp(trailGeoJson(rows, r))))
            }
            progress("trails r$r done")
        }
        SpatialDbManager.queryTracksByViewport(-90.0, -180.0, 90.0, 180.0, 1_000_000).let { emit(GtCert.line("TRACKS", "all", "-", it.size, GtCert.fp(SpatialDbManager.buildTrackGeoJson(it)))) }
        SpatialDbManager.queryWaypointsByViewport(-90.0, -180.0, 90.0, 180.0, 1_000_000).let { emit(GtCert.line("WAYPOINTS", "all", "-", it.size, GtCert.fp(SpatialDbManager.buildWaypointGeoJson(it)))) }
        SpatialDbManager.queryRoutesByViewport(-90.0, -180.0, 90.0, 180.0, 1_000_000).let { emit(GtCert.line("ROUTES", "all", "-", it.size, GtCert.fp(SpatialDbManager.buildRouteGeoJson(it)))) }
        val lp = GtCert.pages(conn, "land_areas", "area_id", false)
        for ((pub, rst) in listOf(true to true, true to false, false to true)) for ((k, frag) in lp) {
            val json = landGeoJson(db, -90.0, -180.0, 90.0, 180.0, pub, rst, 1_000_000, frag)
            emit(GtCert.line("LAND", k, "p${pub}r$rst", countFeatures(json), GtCert.fp(json)))
        }
        progress("land done")
        fun gpxPages(kind: String, table: String, col: String, one: (String) -> String?) {
            for ((k, frag) in GtCert.pages(conn, table, col, true)) {
                val sb = StringBuilder(); var n = 0
                for (id in GtCert.ids(conn, "SELECT $col FROM $table WHERE 1=1$frag ORDER BY $col")) { val g = one(id); if (g != null) { sb.append(g).append('\u0000'); n++ } else sb.append("NULL\u0000") }
                emit(GtCert.line(kind, k, "-", n, GtCert.fp(sb.toString())))
            }
        }
        gpxPages("GPXW", "waypoints", "waypoint_id") { waypointGpx(db, it)?.second }
        gpxPages("GPXR", "routes", "route_id") { routeGpx(db, it)?.second }
        gpxPages("GPXT", "trails", "trail_id") { trailGpx(db, it)?.second }
        progress("gpx done")
    }

    // ═════════ FROZEN 2.7e CODE (copied from SpatialDbManager / TrailFilterState / LandAreas at 1b32f3bbd) -- do not edit ═════════
    private const val GPX_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"GroupTrack\"\n" +
        "     xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
        "     xmlns:grouptrack=\"https://grouptrack.app/gpx/1\">\n"
    private const val GPX_FOOTER = "</gpx>"
    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** TrailFilterState.regenerate() (2.7e), as a function of the four settings. */
    private fun legacyWhere(land: String, restricted: Boolean, use: String, off: Set<String>): String {
        val sb = StringBuilder()
        sb.append(" AND (status IS NULL OR status <> 'CLOSED')")
        val lands = ArrayList<String>()
        if (land != "PRIVATE") lands.add("PUBLIC")
        if (land != "PUBLIC") lands.add("PRIVATE")
        if (restricted) lands.add("RESTRICTED")
        sb.append(" AND (land_status IS NULL OR land_status IN (" + lands.joinToString(",") { "'" + it + "'" } + "))")
        if (use == "MOTORIZED" || use == "NON-MOTORIZED") {
            sb.append(" AND (use_type IS NULL OR use_type='")
                .append(use).append("')")
        }
        if (off.contains("__unofficial")) {
            sb.append(" AND (status IS NULL OR status NOT IN ('UNOFFICIAL','UNCERTAIN'))")
        }
        val cats = off.filter { it != "__unofficial" }
        if (cats.isNotEmpty()) {
            sb.append(" AND COALESCE(carto_code,'shape only') NOT IN (")
            sb.append(cats.joinToString(",") { "'" + it.replace("'", "") + "'" })
            sb.append(")")
        }
        return sb.toString()
    }

    fun queryTrails(db: SQLiteDatabase, edb: SQLiteDatabase?, south: Double, west: Double, north: Double, east: Double, limit: Int, where: String): List<Map<String, String?>> {
        val results = mutableListOf<MutableMap<String, String?>>()
        val cursor = db.rawQuery(
            // TRAILS-GATE-ORDER-2026-07-31: ORDER BY added. LIMIT without it returned
            // whichever rows SQLite reached first — rowid, i.e. IMPORT ORDER.
            // That produced longitude-banded results, and made Arizona trails
            // invisible behind Utah's lower rowids until the viewport shrank.
            // Named first (OSM carries no carto_code), then largest extent —
            // squared bbox diagonal, no sqrt needed for ordering, and both
            // columns already exist and are indexed.
            // MAPFILTER-2026-09-01: ⭐ ONE PLACE, BOTH MAPS. Everything reaches
            // the map through SpatialDisplayManager.processViewport, which
            // calls this and only this -- so the rider's Map Keys selection is
            // applied here and cannot be applied inconsistently anywhere else.
            //
            // ⛔ ALWAYS APPLIED, NEVER CONDITIONAL (Fred, 09-01): "that filter
            // should always be there with current values." An empty selection
            // yields an empty clause and the query is exactly what it was.
            //
            // ⚠ whereOrEmpty(), not where(): this can run before the map
            // composable's load() on a cold path, and a viewport query is the
            // wrong place to throw. TrailFilterState.load() is called at the
            // FRONT of both map screens precisely so that does not happen.
            //
            // ⭐ status joins the SELECT so CLOSED draws red and PLANNED
            // ghosted -- trailStyleOf() in the assets already handles it and
            // has been waiting for the property to arrive.
            "SELECT trail_id, name, geometry, carto_code AS CartoCode, status AS Status, land_status AS Land, restricted_geom AS RstGeom, open_geom AS OpenGeom FROM trails " +   // RSTSEG-2026-11-02: + RstGeom   // LANDDISPLAY-2026-11-02: + Land
                "WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ? " +
                where +
                // ORDERBY-2026-09-01: ⛔ THE NAME CLAUSE IS GONE. It sorted
                // named trails first -- but "Not Named" IS A NAME (Fred,
                // 09-01), and ~69% of rows carry that literal string, so it
                // only ever sorted the truly NULL ones last. Tens of thousands
                // of placeholders competed as "named", and a 200-foot named
                // connector outranked a ten-mile track.
                // ⭐ Length alone. Fred: "real unnamed trails can be long. I
                // would rather load by trail length so meaningful artifacts are
                // loaded first."
                // ⚠ This is the BBOX DIAGONAL, not true length -- close for a
                // straight road, an understatement for a switchbacked trail.
                // Real length would need a column computed at extract; Fred
                // called that too much work for the gain, and this is still
                // strictly better than name-first.
                " ORDER BY " +
                "((max_lat-min_lat)*(max_lat-min_lat)+(max_lon-min_lon)*(max_lon-min_lon)) DESC " +
                "LIMIT ?",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
        )
        while (cursor.moveToNext()) {
            val wkt = cursor.getString(2)
            if (!wkt.isNullOrEmpty()) {
                results.add(mutableMapOf(
                    // MAPFILTER-2026-09-01: Status rides along so the WebView
                    // can colour closed and planned.
                    "Status" to (if (cursor.columnCount > 4) cursor.getString(4) else null),
                    "trail_id" to cursor.getString(0),
                    "name" to cursor.getString(1),
                    "geometry" to wkt,
                    "CartoCode" to cursor.getString(3),
                    "Land" to (if (cursor.columnCount > 5) cursor.getString(5) else null),   // LANDDISPLAY-2026-11-02: RESTRICTED draws red
                    "RstGeom" to (if (cursor.columnCount > 6) cursor.getString(6) else null),   // RSTSEG-2026-11-02: the red segments
                    "OpenGeom" to (if (cursor.columnCount > 7) cursor.getString(7) else null)   // RSTOPEN-2026-11-02: the trail without them
                ))
            }
        }
        cursor.close()
        // ── Enrich draw rows with trail_properties (data DB) so the map popup
        //    shows the same fields as the detail panel. ONE batched query for all
        //    visible trail_ids (hot draw path: avoid N per-trail SELECTs).
        //    snake_case columns -> PascalCase keys buildTrailGeoJson reads.
        if (edb != null && results.isNotEmpty()) {
            try {
                val ids = results.mapNotNull { it["trail_id"] }
                if (ids.isNotEmpty()) {
                    val placeholders = ids.joinToString(",") { "?" }
                    val pc = edb.rawQuery(
                        "SELECT trail_id, surface_type, designated_uses, motorized_allowed, " +
                        "horse_allowed, hike_difficulty, bike_difficulty, owner_steward, county " +
                        "FROM trail_properties WHERE trail_id IN (" + placeholders + ")",
                        ids.toTypedArray()
                    )
                    val byId = HashMap<String, Map<String, String?>>()
                    pc.use {
                        while (it.moveToNext()) {
                            val tid = it.getString(0) ?: continue
                            byId[tid] = mapOf(
                                "SurfaceType" to it.getString(1),
                                "DesignatedUses" to it.getString(2),
                                "MotorizedAllowed" to it.getString(3),
                                "HorseAllowed" to it.getString(4),
                                "HikeDifficulty" to it.getString(5),
                                "BikeDifficulty" to it.getString(6),
                                "OwnerSteward" to it.getString(7),
                                "County" to it.getString(8)
                            )
                        }
                    }
                    for (row in results) {
                        val props = byId[row["trail_id"]] ?: continue
                        for ((k, v) in props) {
                            // spatial CartoCode wins only if it already has a real value
                            val existing = row[k]
                            if (!existing.isNullOrBlank()) continue
                            if (v.isNullOrBlank()) continue
                            row[k] = v
                        }
                    }
                }
            } catch (e: Exception) {
                // trail_properties missing/empty -> leave spatial-only rows
            }
        }
        return results
    }

    fun trailGeoJson(trails: List<Map<String, String?>>, restricted: Boolean): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (trail in trails) {
            // RSTOPEN-2026-11-02: a trail with red parts is drawn from its OPEN parts -- nothing of it lies under the red
            val wkt = (if (!trail["RstGeom"].isNullOrEmpty()) (trail["OpenGeom"] ?: trail["geometry"]) else trail["geometry"]) ?: ""   // NULL open = not worked out yet -> whole line
            if (wkt.isEmpty()) {
                // wholly red: no open part -- its red parts are still sent below (with Restricted ticked)
                val rg0 = trail["RstGeom"] ?: ""
                if (rg0.isNotEmpty() && restricted) {
                    val rc0 = SpatialDbManager.wktToGeoJsonCoords(rg0)
                    if (rc0 != "[]") { if (!first) sb.append(","); first = false
                        sb.append("{\"type\":\"Feature\",\"properties\":{\"trail_id\":\"" + SpatialDbManager.jsonEsc(trail["trail_id"] ?: "") +
                            "\",\"PrimaryName\":\"" + SpatialDbManager.jsonEsc(trail["name"] ?: "") + "\",\"_rst\":1},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":" + rc0 + "}}") }
                }
                continue
            }
            val coords = SpatialDbManager.wktToGeoJsonCoords(wkt)
            if (coords == "[]") continue
            if (!first) sb.append(",")
            first = false
            val geoType = if (wkt.startsWith("MULTI")) "MultiLineString" else "LineString"
            fun s(k: String): String = SpatialDbManager.jsonEsc(trail[k] ?: "")   // RIDERESC-2026-10-09 (was: a quote became \\" -> broken batch)
            sb.append("{\"type\":\"Feature\",\"properties\":{")
            // TRAILTAP-2026-09-02: ⛔ THE ID WAS NEVER IN THE PROPERTIES. Every
            // other artifact hands its id to Kotlin on tap; a trail had nothing
            // to hand over, which is why trails kept a Leaflet popup while
            // tracks and routes got the detail panel.
            sb.append("\"trail_id\":\"" + s("trail_id") + "\"")
            sb.append(",\"PrimaryName\":\"" + s("name") + "\"")
            sb.append(",\"CartoCode\":\"" + s("CartoCode") + "\"")
            sb.append(",\"SurfaceType\":\"" + s("SurfaceType") + "\"")
            sb.append(",\"DesignatedUses\":\"" + s("DesignatedUses") + "\"")
            sb.append(",\"MotorizedAllowed\":\"" + s("MotorizedAllowed") + "\"")
            sb.append(",\"HorseAllowed\":\"" + s("HorseAllowed") + "\"")
            sb.append(",\"HikeDifficulty\":\"" + s("HikeDifficulty") + "\"")
            sb.append(",\"BikeDifficulty\":\"" + s("BikeDifficulty") + "\"")
            sb.append(",\"OwnerSteward\":\"" + s("OwnerSteward") + "\"")
            sb.append(",\"County\":\"" + s("County") + "\"")
            sb.append(",\"Land\":\"" + s("Land") + "\"")   // LANDDISPLAY-2026-11-02
            sb.append("},\"geometry\":{\"type\":\"" + geoType + "\",\"coordinates\":" + coords + "}}")
            // RSTSEG-2026-11-02: the trail's restricted segments, drawn red OVER it (after it) -- with Filter > LAND > Restricted ticked
            val rg = trail["RstGeom"] ?: ""
            if (rg.isNotEmpty() && restricted) {
                val rc = SpatialDbManager.wktToGeoJsonCoords(rg)
                if (rc != "[]") sb.append(",{\"type\":\"Feature\",\"properties\":{\"trail_id\":\"" + s("trail_id") +
                    "\",\"PrimaryName\":\"" + s("name") + "\",\"_rst\":1},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":" + rc + "}}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    fun waypointGpx(db: SQLiteDatabase, waypointId: String): Pair<String, String>? {
        try {
            val cursor = db.rawQuery(
                "SELECT name, type, geometry FROM waypoints WHERE waypoint_id=?",
                arrayOf(waypointId))
            cursor.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(0) ?: "Unnamed"
                val type = c.getString(1) ?: ""
                val geom = c.getString(2) ?: return null
                val desc = if (c.columnCount > 3) (c.getString(3) ?: "") else ""
                val coords = geom.removePrefix("POINT(").removeSuffix(")").trim().split(" ")
                if (coords.size < 2) return null
                val lon = coords[0]
                val lat = coords[1]
                val sb = StringBuilder()
                sb.append(GPX_HEADER)
                sb.append("  <wpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\">\n")
                sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
                if (type.isNotEmpty()) sb.append("    <type>").append(xmlEscape(type)).append("</type>\n")
                if (desc.isNotEmpty()) sb.append("    <desc>").append(xmlEscape(desc)).append("</desc>\n")
                sb.append("  </wpt>\n")
                sb.append(GPX_FOOTER)
                return Pair(name, sb.toString())
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "buildWaypointGpx error: " + e.message)
            return null
        }
    }

    fun routeGpx(db: SQLiteDatabase, routeId: String): Pair<String, String>? {
        try {
            val cursor = db.rawQuery(
                "SELECT name, geometry FROM routes WHERE route_id=?",
                arrayOf(routeId))
            cursor.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(0) ?: "Unnamed"
                val geom = c.getString(1) ?: return null
                /* CLOSEPLANNER-2026-08-29: THE NARRATIVE, AT LAST.
                 *
                 * ⛔ This read `columnCount > 2` against a two-column SELECT, so
                 * it has NEVER fired and no narrative has ever been shared. It
                 * was written for a description column that does not exist.
                 *
                 * ⭐ It lives in route_notes, and readRouteNotes() returns it.
                 *
                 * ⚠ <desc> GETS THE HEADLINE, NOT THE JSON. Gaia and onX put
                 * human prose here, and a rider opening this route there should
                 * see a sentence rather than a payload. The structured block
                 * goes in <extensions>, which other apps ignore.
                 */
                val notes = SpatialDbManager.readRouteNotes(routeId)
                val desc = notes?.optJSONObject("narrative")?.optString("headline") ?: ""
                val coordStr = geom.removePrefix("LINESTRING(").removeSuffix(")")
                val points = coordStr.split(",").mapNotNull { pt ->
                    val parts = pt.trim().split(" ")
                    if (parts.size >= 2) Pair(parts[1], parts[0]) else null
                }
                val sb = StringBuilder()
                sb.append(GPX_HEADER)
                sb.append("  <rte>\n")
                sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
                if (desc.isNotEmpty()) sb.append("    <desc>").append(xmlEscape(desc)).append("</desc>\n")
                /* ROUTEEXTENSIONS-2026-09-08: THE STRUCTURED BLOCK, AT LAST.
                 *
                 * \u26d4 parseGpxRoutes has looked for <grouptrack:notes> since
                 * 08-29 and prefers it over <desc> -- "the payload wins where
                 * present -- it is ours and complete." Nothing ever wrote it,
                 * so every route we exported carried one sentence and lost its
                 * narrative detail and its recipe.
                 *
                 * \u2b50 THE RECIPE RIDES INSIDE. RECIPEINDRAFT-2026-08-29 put it in
                 * the notes block so it would make the same journey with no new
                 * plumbing. This is the step that journey was missing.
                 *
                 * \u26a0 ESCAPED. The JSON is full of quotes and the recipe's
                 * trailFilter clause can carry < and >; unescaped, one filtered
                 * route produces a file nothing can parse.
                 */
                if (notes != null) {
                    sb.append("    <extensions>\n")
                    sb.append("      <grouptrack:notes>")
                    sb.append(xmlEscape(notes.toString()))
                    sb.append("</grouptrack:notes>\n")
                    sb.append("    </extensions>\n")
                }
                var idx = 1
                for ((lat, lon) in points) {
                    sb.append("    <rtept lat=\"").append(lat).append("\" lon=\"").append(lon).append("\">\n")
                    sb.append("      <name>Pt ").append(idx).append("</name>\n")
                    sb.append("    </rtept>\n")
                    idx++
                }
                /* THE WHOLE PAYLOAD, so the recipe travels with the route.
                 * A recipient presses one button and gets six rides from the
                 * sender's parameters -- the entire point of keeping a recipe.
                 * Other apps ignore extensions they do not recognise. */
                if (notes != null) {
                    sb.append("    <extensions>\n")
                    sb.append("      <grouptrack:notes>")
                    sb.append(xmlEscape(notes.toString()))
                    sb.append("</grouptrack:notes>\n")
                    sb.append("    </extensions>\n")
                }
                sb.append("  </rte>\n")
                sb.append(GPX_FOOTER)
                return Pair(name, sb.toString())
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "buildRouteGpx error: " + e.message)
            return null
        }
    }

    fun trailGpx(db: SQLiteDatabase, trailId: String): Pair<String, String>? {
        try {
            val cursor = db.rawQuery(
                "SELECT name, geometry FROM trails WHERE trail_id=?",
                arrayOf(trailId))
            cursor.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(0) ?: "Unnamed"
                val geom = c.getString(1) ?: return null
                val coordStr = geom.removePrefix("LINESTRING(").removeSuffix(")")
                val points = coordStr.split(",").mapNotNull { pt ->
                    val parts = pt.trim().split(" ")
                    if (parts.size >= 2) Pair(parts[1], parts[0]) else null
                }
                val sb = StringBuilder()
                sb.append(GPX_HEADER)
                sb.append("  <trk>\n")
                sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
                sb.append("    <trkseg>\n")
                for ((lat, lon) in points) {
                    sb.append("      <trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"/>\n")
                }
                sb.append("    </trkseg>\n")
                sb.append("  </trk>\n")
                sb.append(GPX_FOOTER)
                return Pair(name, sb.toString())
            }
        } catch (e: Exception) {
            android.util.Log.e("SpatialDb", "buildTrailGpx error: " + e.message)
            return null
        }
    }

    fun landGeoJson(db: SQLiteDatabase, s: Double, w: Double, n: Double, e: Double, pub: Boolean, rst: Boolean, limit: Int, extraWhere: String): String {
        LandAreas.createTables(db)
        val kinds = ArrayList<String>()
        if (pub) kinds.add("'PUBLIC'")
        if (rst) kinds.add("'RESTRICTED'")
        if (kinds.isEmpty()) return "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val sb = StringBuilder(64 * 1024)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        db.rawQuery("SELECT area_id, kind, unit_name, own_name, mang_name, des_name, pub_access, acres, rings FROM land_areas " +
            "WHERE kind IN (" + kinds.joinToString(",") + ") AND max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?" + extraWhere + " " +
            "ORDER BY acres DESC LIMIT ?", arrayOf(s.toString(), n.toString(), w.toString(), e.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) {
                fun q(i: Int) = SpatialDbManager.jsonEsc(c.getString(i) ?: "")
                if (!first) sb.append(',')
                first = false
                sb.append("{\"type\":\"Feature\",\"properties\":{\"area_id\":").append(c.getLong(0))
                    .append(",\"kind\":\"").append(q(1)).append("\",\"unit\":\"").append(q(2))
                    .append("\",\"owner\":\"").append(q(3)).append("\",\"manager\":\"").append(q(4))
                    .append("\",\"designation\":\"").append(q(5)).append("\",\"access\":\"").append(q(6))
                    .append("\",\"acres\":").append(Math.round(c.getDouble(7)))
                    .append("},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[")
                c.getString(8).split('|').forEachIndexed { pi, poly ->
                    if (pi > 0) sb.append(',')
                    sb.append('[')
                    poly.split(';').forEachIndexed { ri, ring ->
                        if (ri > 0) sb.append(',')
                        sb.append('[')
                        ring.split(',').forEachIndexed { k, pt ->
                            if (k > 0) sb.append(',')
                            val sp = pt.indexOf(' ')
                            sb.append('[').append(pt, 0, sp).append(',').append(pt, sp + 1, pt.length).append(']')
                        }
                        sb.append(']')
                    }
                    sb.append(']')
                }
                sb.append("]}}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }
}
