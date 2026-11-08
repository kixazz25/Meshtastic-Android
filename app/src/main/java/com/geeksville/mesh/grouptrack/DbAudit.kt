package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyTrackOps
import com.geeksville.mesh.convoy.SpatialDbManager

/**
 * DBAUDIT-2026-11-03 (Fred 11:52): READ-ONLY -- what the two databases hold and what points at something that is gone. Logged DBAUDIT;
 * nothing is changed or deleted (any clean-up is a separate, agreed step).
 */
object GtDbAudit {
    private const val TAG = "DbAudit"
    private fun log(s: String) = Log.i(TAG, "DBAUDIT $s")

    // ---- TRACKSFIX-2026-11-03 (Fred 12:07): THE REPAIR -- deletes what can never be used; logged DBREPAIR. Once per app start.
    @Volatile private var repaired = false
    private fun rlog(s: String) = Log.i(TAG, "DBREPAIR $s")

    fun repair(ctx: Context) {
        if (repaired) return
        repaired = true
        Thread {
            try { Thread.sleep(10_000); repairNow(ctx) } catch (e: Exception) { Log.e(TAG, "DBREPAIR failed", e) }
        }.start()
    }

    private fun count(db: android.database.sqlite.SQLiteDatabase, sql: String): Int =
        try { db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } } catch (e: Exception) { rlog("count failed: ${e.message}"); 0 }

    private fun repairNow(ctx: Context) {
        // STEP14C-G-2026-11-08 (TEST-ONLY, removed with GtCertRun): never during a certification run (swapped database handles)
        if (GtCertRun.active) { rlog("skipped: a certification run is using swapped database handles"); return }
        SpatialDbManager.init(ctx)
        val sp = SpatialDbManager.getSpatialDb() ?: run { rlog("spatial database not open"); return }
        val t0 = System.currentTimeMillis()
        // 1. track files with no row
        val hashes = HashSet<String>()
        sp.rawQuery("SELECT geom_hash FROM tracks WHERE geom_hash IS NOT NULL", null).use { c -> while (c.moveToNext()) hashes.add(c.getString(0)) }
        val recent = System.currentTimeMillis() - 2 * 3_600_000L
        var deleted = 0; val kept = ArrayList<String>()
        for (f in ConvoyTrackOps.tracksDir().listFiles() ?: emptyArray()) {
            if (!f.isFile || !f.name.endsWith(".gpx") || f.name.startsWith(".")) continue
            if (f.name.removeSuffix(".gpx") in hashes) continue
            if (f.lastModified() > recent) { kept.add("${f.name} (changed in the last 2 hours)"); continue }
            val pts = try { SpatialDbManager.parseGpxTrackPoints(f.readText()) } catch (e: Exception) { emptyList() }
            val why = when {
                pts.isEmpty() -> "no points"
                SpatialDbManager.computeGeomHash("LINESTRING(" + pts.joinToString(",") { "${it.first} ${it.second}" } + ")") in hashes -> "a copy of a track GroupTrack has"
                else -> ""
            }
            if (why.isEmpty()) { kept.add("${f.name} (${pts.size} points -- a real track GroupTrack does not have; Resync tracks brings it in)"); continue }
            if (f.delete()) { deleted++; rlog("deleted track file ${f.name} (${f.length()} bytes): $why") } else kept.add("${f.name} (delete failed)")
        }
        // RIDERLAND-2026-11-03: rider trails (imported, or made from tracks) are PUBLIC and never segmented -- put back what a land update changed
        val wImp = "carto_code_source='import' AND (COALESCE(carto_code,'')<>'OHV' OR COALESCE(use_type,'')<>'MOTORIZED' OR COALESCE(land_status,'')<>'PUBLIC' OR restricted_geom IS NOT NULL OR open_geom IS NOT NULL)"
        val wRid = "carto_code_source='rider' AND (COALESCE(carto_code,'')<>'rider' OR COALESCE(land_status,'')<>'PUBLIC' OR restricted_geom IS NOT NULL OR open_geom IS NOT NULL)"
        val nImp = count(sp, "SELECT COUNT(*) FROM trails WHERE " + wImp)
        val nRid = count(sp, "SELECT COUNT(*) FROM trails WHERE " + wRid)
        try {
            if (nImp > 0) sp.execSQL("UPDATE trails SET carto_code='OHV', use_type='MOTORIZED', land_status='PUBLIC', restricted_geom=NULL, open_geom=NULL WHERE " + wImp)
            if (nRid > 0) sp.execSQL("UPDATE trails SET carto_code='rider', use_type='" + com.geeksville.mesh.convoy.TrailClassifier.useOf("rider") + "', land_status='PUBLIC', restricted_geom=NULL, open_geom=NULL WHERE " + wRid)
        } catch (e: Exception) { rlog("rider trails: put back failed: ${e.message}") }
        rlog("rider trails put back to PUBLIC / unsegmented: $nImp imported, $nRid made from tracks (changed by a land update)")
        rlog("track files with no row: deleted $deleted, kept ${kept.size}${if (kept.isEmpty()) "" else ": " + kept.take(15).joinToString("; ")}")
        // 2. extension rows that point at nothing
        val ext = SpatialDbManager.getExtensionDb() ?: run { rlog("extension database not open"); return }
        val path = sp.path ?: run { rlog("no spatial path"); return }
        ext.execSQL("ATTACH DATABASE ? AS sp", arrayOf<Any>(path))
        try {
            val dates = try { ext.rawQuery("SELECT IFNULL(MIN(created_at),''), IFNULL(MAX(created_at),'') FROM route_notes p WHERE NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=p.route_id)", null)
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
                val n = count(ext, "SELECT COUNT(*) FROM $table WHERE $where")
                if (n == 0) continue
                try { ext.execSQL("DELETE FROM $table WHERE $where"); rlog("deleted $n $what pointing at nothing") }
                catch (e: Exception) { rlog("$what: delete failed: ${e.message}") }
            }
        } finally { try { ext.execSQL("DETACH DATABASE sp") } catch (e: Exception) { rlog("detach: ${e.message}") } }
        rlog("done in ${System.currentTimeMillis() - t0} ms")
    }

    fun run(ctx: Context) {
        SpatialDbManager.init(ctx)
        val sp = SpatialDbManager.getSpatialDb() ?: run { log("spatial database not open"); return }
        fun one(db: android.database.sqlite.SQLiteDatabase, sql: String): String = try {
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) (c.getString(0) ?: "null") else "-" }
        } catch (e: Exception) { "ERR ${e.message?.take(80)}" }
        fun rows(db: android.database.sqlite.SQLiteDatabase, sql: String, max: Int = 12): String = try {
            db.rawQuery(sql, null).use { c -> val l = ArrayList<String>()
                while (c.moveToNext() && l.size < max) l.add((0 until c.columnCount).joinToString("=") { c.getString(it) ?: "null" })
                l.joinToString("; ") }
        } catch (e: Exception) { "ERR ${e.message?.take(80)}" }
        val t0 = System.currentTimeMillis()

        // ---- tracks
        log("tracks: total=${one(sp, "SELECT COUNT(*) FROM tracks")}  by type: ${rows(sp, "SELECT IFNULL(type,'NULL'), COUNT(*) FROM tracks GROUP BY type")}")
        val dir = ConvoyTrackOps.tracksDir()
        val files = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".gpx") && !it.name.startsWith(".") }.map { it.name.removeSuffix(".gpx") }.toSet()
        val missing = ArrayList<String>(); val withRow = HashSet<String>()
        sp.rawQuery("SELECT IFNULL(geom_hash,''), IFNULL(name,''), IFNULL(type,'NULL') FROM tracks", null).use { c ->
            while (c.moveToNext()) {
                val h = c.getString(0); withRow.add(h)
                if ((c.getString(2) == "TRACK" || c.getString(2) == "NULL") && h !in files) missing.add(c.getString(1).ifBlank { "(no name)" } + " [" + h.take(10) + "]")
            }
        }
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
        val ext = SpatialDbManager.getExtensionDb()
        val path = sp.path
        if (ext == null || path.isNullOrEmpty()) log("extension database not open -- orphans not checked")
        else try {
            ext.execSQL("ATTACH DATABASE ? AS sp", arrayOf<Any>(path))
            try {
                for ((type, table, id) in listOf(Triple("trail", "trails", "trail_id"), Triple("track", "tracks", "track_id"),
                        Triple("waypoint", "waypoints", "waypoint_id"), Triple("route", "routes", "route_id"))) {
                    log("aliases $type: total=${one(ext, "SELECT COUNT(*) FROM artifact_aliases WHERE artifact_type='$type'")}  pointing at nothing=" +
                        one(ext, "SELECT COUNT(*) FROM artifact_aliases a WHERE a.artifact_type='$type' AND NOT EXISTS (SELECT 1 FROM sp.$table x WHERE x.$id=a.artifact_id)"))
                }
                for ((props, table, id) in listOf(Triple("trail_properties", "trails", "trail_id"), Triple("track_properties", "tracks", "track_id"),
                        Triple("waypoint_properties", "waypoints", "waypoint_id"), Triple("route_properties", "routes", "route_id"),
                        Triple("route_notes", "routes", "route_id"), Triple("track_surveys", "tracks", "track_id"))) {
                    log("$props: total=${one(ext, "SELECT COUNT(*) FROM $props")}  pointing at nothing=" +
                        one(ext, "SELECT COUNT(*) FROM $props p WHERE NOT EXISTS (SELECT 1 FROM sp.$table x WHERE x.$id=p.$id)"))
                }
            } finally { try { ext.execSQL("DETACH DATABASE sp") } catch (e: Exception) { log("detach: ${e.message}") } }
        } catch (e: Exception) { log("extension check failed: ${e.message}") }
        log("done in ${System.currentTimeMillis() - t0} ms -- read only, nothing changed")
    }
}
