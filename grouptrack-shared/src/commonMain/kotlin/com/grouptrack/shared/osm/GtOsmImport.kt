package com.grouptrack.shared.osm

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.osm.GtOsmExtract.Companion.exec
import com.grouptrack.shared.osm.GtOsmExtract.Companion.javaName
import com.grouptrack.shared.osm.GtOsmExtract.Companion.rawQuery
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.spatial.GtWrite
import com.grouptrack.shared.text.GtJava

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmImportWorker -- C3 IMPORT: skinny DB -> the production spatial DB -- PORTED AS IS. The
 * ledger's pending box, every OVERLAPPING trail (keyset pages of 2,000 by rowid) through the SAME add core the agency import uses
 * (insertTrail: INSERT / DROP / ALIAS on geom_hash), a known osm_id with a new shape added as a SECOND trail (Fred: never replace), the
 * classification carried into trail_properties and trails (CARTOCARRY), then the point layers (whole state, identity = the hash
 * alone) into reference_points, the ingestion log and the ledger's import record.
 * WHAT CHANGED, and only this:
 *  - CoroutineWorker shims -> this class (2.7e already ran it in process); progress -> [onProgress] (the same JSON); setForegroundSafely
 *    -> plat.foreground("import", text).
 *  - SpatialDbManager.getSpatialDb() / getExtensionDb() -> [conn] / [dataConn] (CODE RULE 1: nullable exactly as 2.7e's getters --
 *    null fails "spatial DB not initialised" / "extension DB not initialised", as 2.7e). Its beginDedupSession / sourceUidSeen /
 *    resolveByGeom / insertTrail / markSourceUid / endDedupSession -> the SAME shared [writer] they already delegate to (step 11).
 *  - rawQuery / Cursor -> statements (getString = GtRead.cellText; getInt / getLong / getDouble as SQLite's); execSQL(args) -> Android's
 *    binding (GtOsmExtract.exec). A page's two transactions -> BEGIN on both, COMMIT on both, ROLLBACK on both when it threw (as 15f).
 *  - UUID.randomUUID / Instant.now -> env.ids / env.clock; System.currentTimeMillis -> env.clock.epochMs.
 *  - THE HASH TRAP (D13): "POINT($lon $lat)" (the point's hash) and the bbox JSON's "$s" ... are Kotlin templates = Java's
 *    Double.toString on Android -> GtJava.jd, so the hash and the stored text match on both; the box arguments -> GtJava.jd (TEXT).
 *  - OsmLayerCatalog.enabledLayers(ctx) -> GtOsmLayers(env).enabledLayers(); Log -> gtLogger (same tag).
 */
class GtOsmImport(
    private val conn: SQLiteConnection?,
    private val dataConn: SQLiteConnection?,
    private val writer: GtWrite,
    private val env: GtImportEnv,
    private val plat: GtOsmPlatform,
    private val runSlug: String,
    private val onProgress: (String) -> Unit
) {
    sealed class Result {
        object Success : Result()
        class Failure(val error: String) : Result()
    }

    private val log = gtLogger(TAG)
    private val fs get() = env.files.fs
    private val stage = GtOsmStage(env, plat)
    private val ledger = GtOsmLedger(env, stage)
    private val isStopped = false

    private val items = LinkedHashMap<String, GtOsmProgress.Item>()
    private var typeStartMs = 0L
    private var lastPublishMs = 0L
    private var lastNotifyMs = 0L

    fun doWork(): Result {
        val slug = runSlug

        val skinnyFile = stage.skinnyFor(slug)
        if (!fs.exists(skinnyFile)) return fail("no skinny DB for $slug")

        val bbox = ledger.pendingBbox(slug) ?: return fail("no pending bbox for $slug")
        val scope = ledger.pendingScope(slug) ?: "state"
        val (s, w, n, e) = listOf(bbox[0], bbox[1], bbox[2], bbox[3])
        log.i { "import $slug scope=$scope bbox S${GtJava.jd(s)} W${GtJava.jd(w)} N${GtJava.jd(n)} E${GtJava.jd(e)}" }

        val sDb = conn ?: return fail("spatial DB not initialised")
        val eDb = dataConn ?: return fail("extension DB not initialised")

        var skinny: SQLiteConnection? = null
        var found = 0
        var inserted = 0
        var aliased = 0
        var dropped = 0
        var geomChanged = 0
        var errors = 0
        var ptFound: Int
        var ptInserted: Int
        var ptDropped: Int
        var ptMoved: Int

        try {
            setForegroundSafely("Preparing")
            skinny = gtOpenDatabase(skinnyFile.toString(), true)

            writer.beginDedupSession()

            val where = "max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?"
            val args = listOf(GtJava.jd(s), GtJava.jd(n), GtJava.jd(w), GtJava.jd(e))

            rawQuery(skinny, "SELECT COUNT(*) FROM osm_trails WHERE $where", args) { c -> if (c.step()) found = c.getLong(0).toInt() }
            log.i { "$found trail(s) overlap the bbox" }
            startType("trails", "Trails and tracks", found.toLong())

            if (found == 0) {
                complete("trails")
                ledger.appendImport(slug, scope, bboxJson(s, w, n, e), 0, 0, 0, 0, 0, 0)
                log.i { "no trails in bbox -- nothing imported" }
                return Result.Success
            }

            var lastRow = 0L
            var seen = 0
            val sql = "SELECT rowid, osm_id, name, wkt, geom_hash, " +
                "min_lat, max_lat, min_lon, max_lon, " +
                "trail_type, carto_code, motorized FROM osm_trails " +
                "WHERE rowid > ? AND $where ORDER BY rowid LIMIT $PAGE"

            while (true) {
                if (isStopped) return fail("cancelled")
                val a = ArrayList<String>()
                a.add(lastRow.toString())
                a.addAll(args)
                var rows = 0

                exec(sDb, "BEGIN", emptyList()); exec(eDb, "BEGIN", emptyList())
                var ok = false
                try {
                    rawQuery(skinny, sql, a) { c ->
                        while (c.step()) {
                            rows++
                            lastRow = c.getLong(0)
                            val uid = GtRead.cellText(c, 1) ?: ""
                            if (uid.isEmpty()) { errors++; continue }
                            val name = GtRead.cellText(c, 2)?.ifBlank { null }
                            val wkt = GtRead.cellText(c, 3) ?: ""
                            val hash = GtRead.cellText(c, 4) ?: ""
                            val trailType = if (c.isNull(9)) null else GtRead.cellText(c, 9)
                            val cartoCode = if (c.isNull(10)) null else GtRead.cellText(c, 10)
                            val motorized = if (c.isNull(11)) null else c.getLong(11).toInt()
                            val minLat = c.getDouble(5); val maxLat = c.getDouble(6)
                            val minLon = c.getDouble(7); val maxLon = c.getDouble(8)
                            if (wkt.isEmpty()) { errors++; continue }

                            val now = env.clock.nowInstant()

                            if (writer.sourceUidSeen(OSM_SOURCE_ID, uid)) {
                                val d = writer.resolveByGeom("trail", name ?: "Not Named", hash)
                                if (d != GtWrite.Decision.INSERT) {
                                    dropped++
                                    continue
                                }
                                if (insertNew(sDb, eDb, uid, name, wkt, minLat, maxLat, minLon, maxLon, now, changed = true,
                                        trailType = trailType, cartoCode = cartoCode, motorized = motorized)) {
                                    geomChanged++
                                } else {
                                    errors++
                                }
                                continue
                            }

                            val tid = env.ids.newId()
                            val (anchorId, decision) = writer.insertTrail(tid, name, wkt, minLat, maxLat, minLon, maxLon, now)
                            writer.markSourceUid(OSM_SOURCE_ID, uid)
                            writeProperties(eDb, anchorId, uid, now, orIgnore = true,
                                trailType = trailType, cartoCode = cartoCode, motorized = motorized)
                            writeSpatialCarto(sDb, anchorId, cartoCode)

                            when (decision) {
                                GtWrite.Decision.INSERT -> inserted++
                                GtWrite.Decision.ALIAS -> aliased++
                                GtWrite.Decision.DROP -> dropped++
                            }
                        }
                    }
                    ok = true
                } finally {
                    // 2.7e: setTransactionSuccessful on both (reached only when the page finished), endTransaction eDb then sDb.
                    if (ok) { exec(eDb, "COMMIT", emptyList()); exec(sDb, "COMMIT", emptyList()) }
                    else { try { exec(eDb, "ROLLBACK", emptyList()) } catch (_: Exception) {}; try { exec(sDb, "ROLLBACK", emptyList()) } catch (_: Exception) {} }
                }

                seen += rows
                advance("trails", seen.toLong())
                if (rows < PAGE) break
            }

            complete("trails")

            val ptResult = importPoints(skinny, sDb)
            ptFound = ptResult[0]; ptInserted = ptResult[1]
            ptDropped = ptResult[2]; ptMoved = ptResult[3]

            logIngestion(eDb, found - errors, dropped, s, w, n, e)
            ledger.appendImport(slug, scope, bboxJson(s, w, n, e),
                found, inserted, aliased, dropped, geomChanged, errors, ptFound, ptInserted, ptDropped, ptMoved)
            log.i { "import complete: $slug found=$found inserted=$inserted aliased=$aliased dropped=$dropped geomChanged=$geomChanged " +
                "errors=$errors | points found=$ptFound inserted=$ptInserted moved=$ptMoved dropped=$ptDropped" }
            publish(force = true)
            return Result.Success
        } catch (ex: Exception) {
            log.e { "import failed: ${ex::class.simpleName} ${ex.message}" }
            return fail("${javaName(ex)}: ${ex.message}")
        } finally {
            try { writer.endDedupSession() } catch (_: Exception) { }
            try { skinny?.close() } catch (_: Exception) { }
        }
    }

    // -- points -------------------------------------------------------------

    /** OSM-C3C-POINTS-2026-07-28: every enabled point layer into reference_points. Returns [found, inserted, dropped, moved]. */
    private fun importPoints(skinny: SQLiteConnection, sDb: SQLiteConnection): IntArray {
        exec(sDb, "CREATE TABLE IF NOT EXISTS reference_points (" +
            "point_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, " +
            "source_uid TEXT NOT NULL, name TEXT NOT NULL, fclass TEXT, " +
            "population INTEGER, lat REAL NOT NULL, lon REAL NOT NULL, " +
            "geom_hash TEXT NOT NULL, ingested_at TEXT)", emptyList())
        exec(sDb, "CREATE INDEX IF NOT EXISTS ix_refpt_bbox ON reference_points(lat, lon)", emptyList())
        exec(sDb, "CREATE INDEX IF NOT EXISTS ix_refpt_fclass ON reference_points(fclass)", emptyList())
        exec(sDb, "CREATE UNIQUE INDEX IF NOT EXISTS ix_refpt_identity ON reference_points(geom_hash)", emptyList())

        val seen = HashSet<String>()
        try {
            rawQuery(sDb, "SELECT geom_hash FROM reference_points", emptyList()) { c -> while (c.step()) seen.add(GtRead.cellText(c, 0)!!) }
        } catch (ex: Exception) {
            log.w { "reference_points preload failed: ${ex.message}" }
        }
        val uidsKnown = HashSet<String>()
        try {
            rawQuery(sDb, "SELECT DISTINCT source_uid FROM reference_points WHERE source_id=?", listOf(OSM_SOURCE_ID)) { c ->
                while (c.step()) uidsKnown.add(GtRead.cellText(c, 0)!!)
            }
        } catch (_: Exception) { }

        var found = 0
        var inserted = 0
        var dropped = 0
        var moved = 0
        val now = env.clock.nowInstant()

        for (layer in GtOsmLayers(env).enabledLayers().filter { it.isPoint }) {
            if (!tableExists(skinny, layer.targetTable)) {
                log.i { "points: ${layer.targetTable} absent, skipping" }
                continue
            }
            var n = 0
            rawQuery(skinny, "SELECT COUNT(*) FROM ${layer.targetTable}", emptyList()) { c -> if (c.step()) n = c.getLong(0).toInt() }
            startType(layer.id, layer.label, n.toLong())
            found += n

            val hasPop = layer.columns.contains("population")
            val cols = "osm_id, name, fclass, lat, lon" + if (hasPop) ", population" else ""
            var done = 0

            exec(sDb, "BEGIN", emptyList())
            var ok = false
            try {
                rawQuery(skinny, "SELECT $cols FROM ${layer.targetTable}", emptyList()) { c ->
                    while (c.step()) {
                        done++
                        val uid = GtRead.cellText(c, 0) ?: continue
                        val name = GtRead.cellText(c, 1) ?: continue
                        val fclass = GtRead.cellText(c, 2) ?: ""
                        val lat = c.getDouble(3)
                        val lon = c.getDouble(4)
                        val pop = if (hasPop && !c.isNull(5)) c.getLong(5).toInt() else 0

                        val hash = GtGpkgDecoder.geomHash("POINT(${GtJava.jd(lon)} ${GtJava.jd(lat)})")
                        if (seen.contains(hash)) { dropped++; continue }

                        val isMove = uidsKnown.contains(uid)
                        try {
                            exec(sDb, "INSERT INTO reference_points (point_id,source_id,source_uid," +
                                "name,fclass,population,lat,lon,geom_hash,ingested_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                                listOf(env.ids.newId(), OSM_SOURCE_ID, uid, name, fclass, pop, lat, lon, hash, now))
                            seen.add(hash)
                            uidsKnown.add(uid)
                            if (isMove) moved++ else inserted++
                        } catch (ex: Exception) {
                            dropped++
                        }
                        if (done % 1_000 == 0) advance(layer.id, done.toLong())
                    }
                }
                ok = true
            } finally {
                if (ok) exec(sDb, "COMMIT", emptyList()) else try { exec(sDb, "ROLLBACK", emptyList()) } catch (_: Exception) {}
            }
            complete(layer.id)
            log.i { "points ${layer.id}: $n rows" }
        }

        log.i { "points total: found=$found inserted=$inserted moved=$moved dropped=$dropped" }
        return intArrayOf(found, inserted, dropped, moved)
    }

    private fun tableExists(db: SQLiteConnection, name: String): Boolean = try {
        var r = false
        rawQuery(db, "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", listOf(name)) { c -> r = c.step() }
        r
    } catch (ex: Exception) { false }

    /** OSM-C3C-POINTS-2026-07-28: the undo (not called from anywhere in 2.7e -- kept). */
    fun deleteAllOsmPoints(sDb: SQLiteConnection): Int = try {
        var before = 0
        rawQuery(sDb, "SELECT COUNT(*) FROM reference_points WHERE source_id=?", listOf(OSM_SOURCE_ID)) { c ->
            before = if (c.step()) c.getLong(0).toInt() else 0
        }
        exec(sDb, "DELETE FROM reference_points WHERE source_id=?", listOf(OSM_SOURCE_ID))
        log.i { "removed $before OSM reference point(s)" }
        before
    } catch (ex: Exception) {
        log.w { "deleteAllOsmPoints failed: ${ex.message}" }
        0
    }

    // -- inserts ------------------------------------------------------------

    /** A known osm_id with a new geometry: a SECOND trail; the properties row a PLAIN insert (2.7e's reason stands). */
    private fun insertNew(
        sDb: SQLiteConnection, eDb: SQLiteConnection, uid: String, name: String?,
        wkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        now: String, changed: Boolean, trailType: String?, cartoCode: String?, motorized: Int?
    ): Boolean {
        return try {
            val tid = env.ids.newId()
            val (anchorId, _) = writer.insertTrail(tid, name, wkt, minLat, maxLat, minLon, maxLon, now)
            writeProperties(eDb, anchorId, uid, now, orIgnore = !changed, trailType = trailType, cartoCode = cartoCode, motorized = motorized)
            writeSpatialCarto(sDb, anchorId, cartoCode)
            true
        } catch (ex: Exception) {
            log.w { "second-geometry insert for uid=$uid failed: ${ex::class.simpleName} ${ex.message} -- if this is a UNIQUE " +
                "constraint on (source_id, source_unique_id), the geometry-change design needs revisiting" }
            false
        }
    }

    private fun writeProperties(eDb: SQLiteConnection, trailId: String, uid: String, now: String, orIgnore: Boolean,
                                trailType: String?, cartoCode: String?, motorized: Int?) {
        val verb = if (orIgnore) "INSERT OR IGNORE" else "INSERT"
        val motorizedText = when (motorized) {
            1 -> "Y"
            0 -> "N"
            else -> ""
        }
        exec(eDb, "$verb INTO trail_properties (trail_id,source_id,source_unique_id," +
            "designated_uses,motorized_allowed,surface_type,carto_code," +
            "owner_steward,county,agency_id,ingested_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            listOf(trailId, OSM_SOURCE_ID, uid, trailType ?: "", motorizedText, "", cartoCode ?: "", "", "", uid, now))
    }

    private fun writeSpatialCarto(sDb: SQLiteConnection, trailId: String, cartoCode: String?) {
        if (cartoCode.isNullOrEmpty()) return
        try {
            exec(sDb, "UPDATE trails SET carto_code=? WHERE trail_id=?", listOf(cartoCode, trailId))
        } catch (ex: Exception) {
            log.w { "spatial carto write failed for $trailId: ${ex.message}" }
        }
    }

    private fun logIngestion(eDb: SQLiteConnection, count: Int, dupes: Int, s: Double, w: Double, n: Double, e: Double) {
        try {
            exec(eDb, "INSERT INTO source_ingestions (ingestion_id,source_id,ingested_at,trail_count,dupes_skipped,bounds_json) VALUES (?,?,?,?,?,?)",
                listOf(env.ids.newId(), OSM_SOURCE_ID, env.clock.nowInstant(), count, dupes, bboxJson(s, w, n, e)))
        } catch (ex: Exception) {
            log.w { "source_ingestions write failed: ${ex.message}" }
        }
    }

    private fun bboxJson(s: Double, w: Double, n: Double, e: Double) =
        "{\"s\":${GtJava.jd(s)},\"w\":${GtJava.jd(w)},\"n\":${GtJava.jd(n)},\"e\":${GtJava.jd(e)}}"

    // -- progress (same model C2 uses) --------------------------------------

    private fun startType(id: String, label: String, total: Long) {
        typeStartMs = env.clock.epochMs()
        items[id] = GtOsmProgress.Item(id, label, 0, total, false, -1)
        publish(force = true)
    }

    private fun advance(id: String, done: Long) {
        val cur = items[id] ?: return
        val elapsed = env.clock.epochMs() - typeStartMs
        val eta = if (done > 0L && elapsed > 2_000L && cur.total > 0L) {
            (((cur.total - done).toDouble() * (elapsed.toDouble() / done)) / 1000.0).toLong().coerceAtLeast(0L)
        } else -1L
        items[id] = cur.copy(done = done, etaSec = eta)
        publish(force = false)
    }

    private fun complete(id: String) {
        items[id]?.let { items[id] = it.copy(done = if (it.total > 0) it.total else it.done, complete = true, etaSec = -1) }
        publish(force = true)
    }

    private fun publish(force: Boolean) {
        val now = env.clock.epochMs()
        if (!force && now - lastPublishMs < GtOsmProgress.PUBLISH_MS) return
        lastPublishMs = now
        onProgress(GtOsmProgress.toJson(items.values.toList()))
        if (force || now - lastNotifyMs >= GtOsmProgress.NOTIFY_MS) {
            lastNotifyMs = now
            val active = items.values.firstOrNull { !it.complete }
            setForegroundSafely(if (active == null) "Finishing" else "${active.label} ${active.percent}%")
        }
    }

    // -- plumbing -----------------------------------------------------------

    private fun fail(msg: String): Result {
        log.e { "FAIL: $msg" }
        return Result.Failure(msg)
    }

    private fun setForegroundSafely(text: String) {
        try {
            plat.foreground("import", text)
        } catch (ex: Exception) {
            log.w { "foreground unavailable: ${ex::class.simpleName}" }
        }
    }

    companion object {
        private const val TAG = "OsmImport"
        /** ONE source id for all states (dedup is (source_id, source_unique_id)). */
        const val OSM_SOURCE_ID = "osm"
        private const val PAGE = 2_000
    }
}
