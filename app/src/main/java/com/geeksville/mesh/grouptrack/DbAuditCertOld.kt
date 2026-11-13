package com.geeksville.mesh.grouptrack

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * STEP17G2F2-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * 2.7e's GtDbAudit.run, running as REAL ANDROID CODE against a REAL scratch SQLiteDatabase and REAL
 * .gpx files, both built from GtHkFixture. ⭐ This is what makes the Droid comparison worth running:
 * OLD is 2.7e itself, not a second transcription of 2.7e by the same hand that wrote the port.
 *
 * ══════════════════════════════════════════════════════════════════
 *  SUBSTITUTION MANIFEST -- piece 2 (run). Counted. Control flow untouched.
 * ══════════════════════════════════════════════════════════════════
 *   1x  object GtDbAudit                      ->  object DbAuditCertOld
 *   1x  SpatialDbManager.init(ctx)            ->  (removed: the harness opens the scratch databases)
 *   1x  SpatialDbManager.getSpatialDb()       ->  the `spIn` parameter  (⭐ the ?: run{...} arm is KEPT)
 *   1x  SpatialDbManager.getExtensionDb()     ->  the `ext` parameter
 *   1x  ConvoyTrackOps.tracksDir()            ->  the `tracksDir` parameter
 *   9x  Log.i(TAG, ...)  (via log())          ->  GtHkCertLog.i(TAG, ...)
 *   2x  android.database.sqlite.SQLiteDatabase ->  SQLiteDatabase  (imported; the `one`/`rows` signatures)
 *   6x  PROBE  (ADDED)                        ->  GtHkCertLog.probe(...) / .effect(...), plus the
 *                                                 local `sql` list PROBE 4 reads out. ⚠ Its two
 *                                                 sql.add(...) strings are character-for-character
 *                                                 the strings 2.7e already passes to one(ext, ...)
 *                                                 on the next line -- nothing new is constructed.
 *   0x  GtCertRun.active guard                ->  (absent: 2.8 TEST-ONLY, and it is on repairNow)
 * ⭐ VERIFIED MECHANICALLY, not by eye: reversing this manifest on the copy and diffing against
 * `git show 2.7e:...DbAudit.kt` leaves only the lines listed above. Re-run that check after any
 * edit to this file -- the harness prints the command.
 * Every other character between `fun run` and its closing brace is 2.7e.
 *
 * ⚠ THE PROBES ARE THE ONLY ADDITION AND THEY READ, NEVER WRITE. 2.7e keeps `missing` and
 * `files - withRow` as locals and logs only truncated samples of them; the probes read the full
 * collections out, sorted, so the harness compares the decision rather than the sample.
 */
object DbAuditCertOld {

    private const val TAG = "DbAudit"
    private fun log(s: String) = GtHkCertLog.i(TAG, "DBAUDIT $s")

    fun run(spIn: SQLiteDatabase?, ext: SQLiteDatabase?, tracksDir: File) {
        val sp = spIn ?: run { log("spatial database not open"); GtHkCertLog.probe("exit", "spatial database not open"); return }
        fun one(db: SQLiteDatabase, sql: String): String = try {
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) (c.getString(0) ?: "null") else "-" }
        } catch (e: Exception) { "ERR ${e.message?.take(80)}" }
        fun rows(db: SQLiteDatabase, sql: String, max: Int = 12): String = try {
            db.rawQuery(sql, null).use { c -> val l = ArrayList<String>()
                while (c.moveToNext() && l.size < max) l.add((0 until c.columnCount).joinToString("=") { c.getString(it) ?: "null" })
                l.joinToString("; ") }
        } catch (e: Exception) { "ERR ${e.message?.take(80)}" }
        val t0 = System.currentTimeMillis()

        // ---- tracks
        log("tracks: total=${one(sp, "SELECT COUNT(*) FROM tracks")}  by type: ${rows(sp, "SELECT IFNULL(type,'NULL'), COUNT(*) FROM tracks GROUP BY type")}")
        val dir = tracksDir
        val files = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".gpx") && !it.name.startsWith(".") }.map { it.name.removeSuffix(".gpx") }.toSet()
        val missing = ArrayList<String>(); val withRow = HashSet<String>()
        sp.rawQuery("SELECT IFNULL(geom_hash,''), IFNULL(name,''), IFNULL(type,'NULL') FROM tracks", null).use { c ->
            while (c.moveToNext()) {
                val h = c.getString(0); withRow.add(h)
                if ((c.getString(2) == "TRACK" || c.getString(2) == "NULL") && h !in files) missing.add(c.getString(1).ifBlank { "(no name)" } + " [" + h.take(10) + "]")
            }
        }
        GtHkCertLog.probe("missing", missing.sorted().joinToString(" | "))                    // PROBE 1
        GtHkCertLog.probe("noRow", (files - withRow).sorted().joinToString(" | "))            // PROBE 2
        log("tracks: TRACK rows with NO FILE=${missing.size}: ${missing.take(15).joinToString(", ")}")
        log("tracks: files in ${dir.name}=${files.size}; files with NO ROW=${(files - withRow).size}: ${(files - withRow).take(10).joinToString(", ") { it.take(10) }}")
        log("tracks: duplicate geom_hash=${one(sp, "SELECT COUNT(*) - COUNT(DISTINCT geom_hash) FROM tracks WHERE geom_hash IS NOT NULL")}  no geom_hash=${one(sp, "SELECT COUNT(*) FROM tracks WHERE geom_hash IS NULL")}")

        // ---- routes
        log("routes: total=${one(sp, "SELECT COUNT(*) FROM routes")}  duplicate geom_hash=${one(sp, "SELECT COUNT(*) - COUNT(DISTINCT geom_hash) FROM routes WHERE geom_hash IS NOT NULL")}")
        log("routes: ROUTE-type rows left in tracks=${one(sp, "SELECT COUNT(*) FROM tracks WHERE type='ROUTE'")}  of them also in routes (same geom_hash)=" +
            one(sp, "SELECT COUNT(*) FROM tracks t WHERE t.type='ROUTE' AND EXISTS (SELECT 1 FROM routes r WHERE r.geom_hash=t.geom_hash)"))
        log("routes: ROUTE-type rows in tracks (first): ${rows(sp, "SELECT IFNULL(name,''), substr(IFNULL(geom_hash,''),1,10), IFNULL(created_at,'') FROM tracks WHERE type='ROUTE' ORDER BY created_at DESC")}")

        // ---- waypoints, trails
        log("waypoints: total=${one(sp, "SELECT COUNT(*) FROM waypoints")}  by type: ${rows(sp, "SELECT IFNULL(type,'NULL'), COUNT(*) FROM waypoints GROUP BY type", 20)}")
        log("waypoints: exact duplicates (same name and spot)=${one(sp, "SELECT COUNT(*) - COUNT(DISTINCT IFNULL(name,'') || '|' || round(min_lat,5) || '|' || round(min_lon,5)) FROM waypoints")}")
        log("trails: total=${one(sp, "SELECT COUNT(*) FROM trails")}  by source: ${rows(sp, "SELECT IFNULL(carto_code_source,'NULL'), COUNT(*) FROM trails GROUP BY carto_code_source", 20)}")
        log("trails: duplicate geom_hash=${one(sp, "SELECT COUNT(*) - COUNT(DISTINCT geom_hash) FROM trails WHERE geom_hash IS NOT NULL")}  no geometry=${one(sp, "SELECT COUNT(*) FROM trails WHERE geometry IS NULL OR geometry=''")}")

        // ---- extension: what points at a row that is gone
        val path = sp.path
        if (ext == null || path.isNullOrEmpty()) { log("extension database not open -- orphans not checked"); GtHkCertLog.probe("ext", "orphans not checked") }
        else try {
            ext.execSQL("ATTACH DATABASE ? AS sp", arrayOf<Any>(path)); GtHkCertLog.effect("ATTACH DATABASE ? AS sp")   // PROBE 3 -- the statement, not the bound path
            try {
                val sql = ArrayList<String>()
                for ((type, table, id) in listOf(Triple("trail", "trails", "trail_id"), Triple("track", "tracks", "track_id"),
                        Triple("waypoint", "waypoints", "waypoint_id"), Triple("route", "routes", "route_id"))) {
                    sql.add("SELECT COUNT(*) FROM artifact_aliases a WHERE a.artifact_type='$type' AND NOT EXISTS (SELECT 1 FROM sp.$table x WHERE x.$id=a.artifact_id)")
                    log("aliases $type: total=${one(ext, "SELECT COUNT(*) FROM artifact_aliases WHERE artifact_type='$type'")}  pointing at nothing=" +
                        one(ext, "SELECT COUNT(*) FROM artifact_aliases a WHERE a.artifact_type='$type' AND NOT EXISTS (SELECT 1 FROM sp.$table x WHERE x.$id=a.artifact_id)"))
                }
                for ((props, table, id) in listOf(Triple("trail_properties", "trails", "trail_id"), Triple("track_properties", "tracks", "track_id"),
                        Triple("waypoint_properties", "waypoints", "waypoint_id"), Triple("route_properties", "routes", "route_id"),
                        Triple("route_notes", "routes", "route_id"), Triple("track_surveys", "tracks", "track_id"))) {
                    sql.add("SELECT COUNT(*) FROM $props p WHERE NOT EXISTS (SELECT 1 FROM sp.$table x WHERE x.$id=p.$id)")
                    log("$props: total=${one(ext, "SELECT COUNT(*) FROM $props")}  pointing at nothing=" +
                        one(ext, "SELECT COUNT(*) FROM $props p WHERE NOT EXISTS (SELECT 1 FROM sp.$table x WHERE x.$id=p.$id)"))
                }
                GtHkCertLog.probe("sql", sql.joinToString(" || "))                              // PROBE 4
            } finally { try { ext.execSQL("DETACH DATABASE sp"); GtHkCertLog.effect("DETACH DATABASE sp") } catch (e: Exception) { log("detach: ${e.message}") } }
            GtHkCertLog.probe("ext", "checked")
        } catch (e: Exception) { log("extension check failed: ${e.message}") }
        log("done in ${System.currentTimeMillis() - t0} ms -- read only, nothing changed")
    }

    // ══════════════════════════════════════════════════════════════════
    //  PIECE 3 -- repair + repairNow.  ⛔ run() above is CERTIFIED and untouched.
    //
    //  SUBSTITUTION MANIFEST -- piece 3. Counted. Control flow untouched.
    //    1x  SpatialDbManager.init(ctx)          ->  (removed: the harness opens the scratch databases)
    //    1x  SpatialDbManager.getSpatialDb()     ->  the `spIn` parameter  (⭐ the ?: run{...} arm is KEPT)
    //    1x  SpatialDbManager.getExtensionDb()   ->  the `ext` parameter   (⭐ its ?: run{...} arm is KEPT)
    //    1x  ConvoyTrackOps.tracksDir()          ->  the `tracksDir` parameter
    //    1x  SpatialDbManager.parseGpxTrackPoints->  GtHkCertStubs.parse      (other groups' maths)
    //    1x  SpatialDbManager.computeGeomHash    ->  GtHkCertStubs.hash       (other groups' maths)
    //    1x  TrailClassifier.useOf("rider")      ->  GtHkCertStubs.useOf      (other groups' classifier)
    //    1x  f.delete()                          ->  GtHkCertStubs.delete(f)  (⚠ really deletes unless
    //                                                 the fixture marks the file -- the false arm has no
    //                                                 honest way to happen on a writable scratch dir)
    //    2x  System.currentTimeMillis()          ->  GtHkCertClock.now()      (⛔ RULE 1)
    //   13x  Log.i(TAG, ...)  (via rlog())       ->  GtHkCertLog.i(TAG, ...)
    //    4x  a local `q` for a statement built inline, so the SAME string is executed and recorded
    //    5x  PROBE (ADDED)                       ->  GtHkCertLog.probe(...) / .effect(...)
    //    1x  PROBE (ADDED)                       ->  a local `whyBy` map written beside 2.7e's own
    //                                                 kept/deleted bookkeeping; ⛔ never read by
    //                                                 control flow, only read out at the end
    //    0x  GtCertRun.active guard              ->  (absent: 2.8 TEST-ONLY, not 2.7e)
    //    0x  Thread { sleep(10_000); ... }       ->  see [repair]: the spawn is the platform's
    // ══════════════════════════════════════════════════════════════════

    /** TRACKSFIX-2026-11-03: once per app start. ⚠ TEST-ONLY reset so the cert can exercise it twice. */
    @Volatile private var repaired = false
    fun resetForCert() { repaired = false }
    private fun rlog(s: String) = GtHkCertLog.i(TAG, "DBREPAIR $s")

    /**
     * 2.7e spawns Thread { Thread.sleep(10_000); repairNow(ctx) }. ⛔ A ten-second sleep inside a
     * cert harness is precisely the nondeterminism that cost us patch k on 11-13, and WHETHER it
     * spawns is the only decision this function makes -- the @Volatile latch means once per process
     * and never again. So the harness supplies the spawn and we certify the decision.
     */
    fun repair(spawn: (Long, () -> Unit) -> Unit): Boolean {
        if (repaired) return false
        repaired = true
        spawn(10_000L) { }
        return true
    }

    private fun count(db: SQLiteDatabase, sql: String): Int =
        try { db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } } catch (e: Exception) { rlog("count failed: ${e.message}"); 0 }

    fun repairNow(spIn: SQLiteDatabase?, ext: SQLiteDatabase?, tracksDir: File) {
        val sp = spIn ?: run { rlog("spatial database not open"); GtHkCertLog.probe("exit", "spatial database not open"); return }
        val t0 = GtHkCertClock.now()
        // 1. track files with no row
        val hashes = HashSet<String>()
        sp.rawQuery("SELECT geom_hash FROM tracks WHERE geom_hash IS NOT NULL", null).use { c -> while (c.moveToNext()) hashes.add(c.getString(0)) }
        val recent = GtHkCertClock.now() - 2 * 3_600_000L
        var deleted = 0; val kept = ArrayList<String>()
        val whyBy = LinkedHashMap<String, String>()                                   // PROBE (observation only)
        for (f in tracksDir.listFiles() ?: emptyArray()) {
            if (!f.isFile || !f.name.endsWith(".gpx") || f.name.startsWith(".")) continue
            if (f.name.removeSuffix(".gpx") in hashes) continue
            if (f.lastModified() > recent) { kept.add("${f.name} (changed in the last 2 hours)"); whyBy[f.name] = "recent"; continue }
            val pts = try { GtHkCertStubs.parse(f.readText()) } catch (e: Exception) { emptyList() }
            val why = when {
                pts.isEmpty() -> "no points"
                GtHkCertStubs.hash("LINESTRING(" + pts.joinToString(",") { "${it.first} ${it.second}" } + ")") in hashes -> "a copy of a track GroupTrack has"
                else -> ""
            }
            if (why.isEmpty()) { kept.add("${f.name} (${pts.size} points -- a real track GroupTrack does not have; Resync tracks brings it in)"); whyBy[f.name] = "keep"; continue }
            if (GtHkCertStubs.delete(f)) { deleted++; rlog("deleted track file ${f.name} (${f.length()} bytes): $why"); whyBy[f.name] = why }
            else { kept.add("${f.name} (delete failed)"); whyBy[f.name] = "delete failed" }
        }
        GtHkCertLog.probe("deleted", "$deleted")                                       // PROBE 1
        GtHkCertLog.probe("kept", kept.sorted().joinToString(" | "))                   // PROBE 2
        GtHkCertLog.probe("why", whyBy.toSortedMap().entries.joinToString(" | ") { "${it.key}=${it.value}" })   // PROBE 3

        val wImp = "carto_code_source='import' AND (COALESCE(carto_code,'')<>'OHV' OR COALESCE(use_type,'')<>'MOTORIZED' OR COALESCE(land_status,'')<>'PUBLIC' OR restricted_geom IS NOT NULL OR open_geom IS NOT NULL)"
        val wRid = "carto_code_source='rider' AND (COALESCE(carto_code,'')<>'rider' OR COALESCE(land_status,'')<>'PUBLIC' OR restricted_geom IS NOT NULL OR open_geom IS NOT NULL)"
        val nImp = count(sp, "SELECT COUNT(*) FROM trails WHERE " + wImp)
        val nRid = count(sp, "SELECT COUNT(*) FROM trails WHERE " + wRid)
        try {
            if (nImp > 0) { val q = "UPDATE trails SET carto_code='OHV', use_type='MOTORIZED', land_status='PUBLIC', restricted_geom=NULL, open_geom=NULL WHERE " + wImp; sp.execSQL(q); GtHkCertLog.effect(q) }
            if (nRid > 0) { val q = "UPDATE trails SET carto_code='rider', use_type='" + GtHkCertStubs.useOf("rider") + "', land_status='PUBLIC', restricted_geom=NULL, open_geom=NULL WHERE " + wRid; sp.execSQL(q); GtHkCertLog.effect(q) }
        } catch (e: Exception) { rlog("rider trails: put back failed: ${e.message}") }
        rlog("rider trails put back to PUBLIC / unsegmented: $nImp imported, $nRid made from tracks (changed by a land update)")
        rlog("track files with no row: deleted $deleted, kept ${kept.size}${if (kept.isEmpty()) "" else ": " + kept.take(15).joinToString("; ")}")

        // 2. extension rows that point at nothing
        val extDb = ext ?: run { rlog("extension database not open"); GtHkCertLog.probe("ext", "extension database not open"); return }
        val path = sp.path ?: run { rlog("no spatial path"); GtHkCertLog.probe("ext", "no spatial path"); return }
        val qA = "ATTACH DATABASE ? AS sp"; extDb.execSQL(qA, arrayOf<Any>(path)); GtHkCertLog.effect(qA)      // PROBE 4
        try {
            val dates = try { extDb.rawQuery("SELECT IFNULL(MIN(created_at),''), IFNULL(MAX(created_at),'') FROM route_notes p WHERE NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=p.route_id)", null)
                .use { c -> if (c.moveToFirst()) "${c.getString(0)} .. ${c.getString(1)}" else "" } } catch (e: Exception) { "?" }
            val orphanWhere = listOf(
                Triple("route_notes", "NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=route_notes.route_id)", "route notes (written $dates)"),
                Triple("artifact_aliases", "artifact_type='route' AND NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=artifact_aliases.artifact_id)", "route names"),
                Triple("artifact_aliases", "artifact_type='track' AND NOT EXISTS (SELECT 1 FROM sp.tracks x WHERE x.track_id=artifact_aliases.artifact_id)", "track names"),
                Triple("artifact_aliases", "artifact_type='trail' AND NOT EXISTS (SELECT 1 FROM sp.trails x WHERE x.trail_id=artifact_aliases.artifact_id)", "trail names"),
                Triple("artifact_aliases", "artifact_type='waypoint' AND NOT EXISTS (SELECT 1 FROM sp.waypoints x WHERE x.waypoint_id=artifact_aliases.artifact_id)", "waypoint names"),
                Triple("track_properties", "NOT EXISTS (SELECT 1 FROM sp.tracks x WHERE x.track_id=track_properties.track_id)", "track properties"),
                Triple("trail_properties", "NOT EXISTS (SELECT 1 FROM sp.trails x WHERE x.trail_id=trail_properties.trail_id)", "trail properties"),
                Triple("route_properties", "NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=route_properties.route_id)", "route properties"),
                Triple("waypoint_properties", "NOT EXISTS (SELECT 1 FROM sp.waypoints x WHERE x.waypoint_id=waypoint_properties.waypoint_id)", "waypoint properties"),
                Triple("track_surveys", "NOT EXISTS (SELECT 1 FROM sp.tracks x WHERE x.track_id=track_surveys.track_id)", "track surveys"))
            for ((table, where, what) in orphanWhere) {
                val n = count(extDb, "SELECT COUNT(*) FROM $table WHERE $where")
                if (n == 0) continue
                try { val q = "DELETE FROM $table WHERE $where"; extDb.execSQL(q); GtHkCertLog.effect(q); rlog("deleted $n $what pointing at nothing") }
                catch (e: Exception) { rlog("$what: delete failed: ${e.message}") }
            }
            GtHkCertLog.probe("ext", "checked")                                        // PROBE 5
        } finally { try { extDb.execSQL("DETACH DATABASE sp"); GtHkCertLog.effect("DETACH DATABASE sp") } catch (e: Exception) { rlog("detach: ${e.message}") } }
        rlog("done in ${GtHkCertClock.now() - t0} ms")
    }
}
