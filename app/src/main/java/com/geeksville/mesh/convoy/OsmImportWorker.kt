package com.geeksville.mesh.convoy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Instant
import java.util.UUID

/**
 * C3 IMPORT: skinny DB -> the production spatial DB.
 *
 * OSM-C3A-IMPORT-2026-07-28
 *
 * Reads the bbox from the ledger's pending_import record and copies every
 * overlapping trail into `trails` + `trail_properties` through the SAME add
 * core the ArcGIS importer uses -- SpatialDbManager.insertTrail, which computes
 * geom_hash and returns INSERT / DROP / ALIAS.
 *
 * ⭐ WHOLE STATE IS NOT A SPECIAL CASE. The extent comes from the trails
 * themselves, so every trail overlaps it by definition and a full-extent import
 * returns exactly what "SELECT *" would -- identically, not approximately.
 * There is one path here, exercised two ways.
 *
 * ⚠ OVERLAP, NOT CONTAINMENT. A trail crossing the box comes over WHOLE,
 * geometry outside included. Clipping would change the WKT, which changes
 * geom_hash, which breaks dedup against a later whole-state import of the same
 * trail. The 2026-07-28 hash comparison (99.902% agreement, 0 format
 * differences) is what proves that chain holds.
 *
 * ⚠ DEDUP IS IN-MEMORY. beginDedupSession() fills the hash maps and the
 * source-uid set; without it every trail looks new and 89,536 duplicates land
 * with nothing reporting an error.
 */
class OsmImportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "OsmImport"

        const val KEY_SLUG = "slug"
        const val KEY_ERROR = "error"

        /**
         * ONE source id for all states, not one per state.
         *
         * Dedup is (source_id, source_unique_id). Per-state ids would make a
         * trail crossing the Utah/Arizona line import twice, once under each.
         * The state is recoverable from geometry; the source is not.
         */
        const val OSM_SOURCE_ID = "osm"

        private const val CHANNEL_ID = "osm_import"
        private const val NOTIF_ID = 0x0
        private const val PAGE = 2_000

        fun uniqueName(slug: String) = "osm_import_$slug"

        fun enqueue(ctx: Context, slug: String) {
            val req = OneTimeWorkRequestBuilder<OsmImportWorker>()
                .setInputData(workDataOf(KEY_SLUG to slug))
                .build()
            // KEEP: the panel relaunches on every refresh while a pending bbox
            // exists, so a second enqueue must be a no-op rather than a second
            // import.
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                uniqueName(slug), ExistingWorkPolicy.KEEP, req
            )
            Log.i(TAG, "enqueued import for $slug")
        }
    }

    private val items = LinkedHashMap<String, OsmExtractProgress.Item>()
    private var typeStartMs = 0L
    private var lastPublishMs = 0L
    private var lastNotifyMs = 0L

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val slug = inputData.getString(KEY_SLUG) ?: return fail("no slug supplied")

        val skinnyFile = OsmImportStage.skinnyFor(ctx, slug)
        if (!skinnyFile.exists()) return fail("no skinny DB for $slug")

        val bbox = OsmImportLedger.pendingBbox(ctx, slug)
            ?: return fail("no pending bbox for $slug")
        val scope = OsmImportLedger.pendingScope(ctx, slug) ?: "state"
        val (s, w, n, e) = listOf(bbox[0], bbox[1], bbox[2], bbox[3])
        Log.i(TAG, "import $slug scope=$scope bbox S$s W$w N$n E$e")

        val sDb = SpatialDbManager.getSpatialDb()
            ?: return fail("spatial DB not initialised")
        val eDb = SpatialDbManager.getExtensionDb()
            ?: return fail("extension DB not initialised")

        var skinny: SQLiteDatabase? = null
        var found = 0
        var inserted = 0
        var aliased = 0
        var dropped = 0
        var geomChanged = 0
        var errors = 0
        var ptFound = 0
        var ptInserted = 0
        var ptDropped = 0
        var ptMoved = 0

        try {
            setForegroundSafely("Preparing")
            skinny = SQLiteDatabase.openDatabase(
                skinnyFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )

            // ⚠ Without this every trail looks new. See the class KDoc.
            SpatialDbManager.beginDedupSession()

            val where = "max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?"
            val args = arrayOf(s.toString(), n.toString(), w.toString(), e.toString())

            skinny.rawQuery("SELECT COUNT(*) FROM osm_trails WHERE $where", args).use { c ->
                if (c.moveToFirst()) found = c.getInt(0)
            }
            Log.i(TAG, "$found trail(s) overlap the bbox")
            startType("trails", "Trails and tracks", found.toLong())

            if (found == 0) {
                // Legitimate -- an area with no OSM trails in it. Must not look
                // like a failed import.
                complete("trails")
                OsmImportLedger.appendImport(
                    ctx, slug, scope, bboxJson(s, w, n, e), 0, 0, 0, 0, 0, 0
                )
                Log.i(TAG, "no trails in bbox -- nothing imported")
                return Result.success()
            }

            var lastRow = 0L
            var seen = 0
            // CARTOCARRY-2026-08-31: the classification was computed at
            // extract and never read here, so every OSM row arrived with an
            // empty carto_code. Appended at indices 9, 10, 11 so every
            // existing getString(n) keeps its meaning.
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

                sDb.beginTransaction()
                eDb.beginTransaction()
                try {
                    skinny.rawQuery(sql, a.toTypedArray()).use { c ->
                        while (c.moveToNext()) {
                            rows++
                            lastRow = c.getLong(0)
                            val uid = c.getString(1) ?: ""
                            if (uid.isEmpty()) { errors++; continue }
                            val name = c.getString(2)?.ifBlank { null }
                            val wkt = c.getString(3) ?: ""
                            val hash = c.getString(4) ?: ""
                            // CARTOCARRY-2026-08-31
                            val trailType = if (c.isNull(9)) null else c.getString(9)
                            val cartoCode = if (c.isNull(10)) null else c.getString(10)
                            val motorized = if (c.isNull(11)) null else c.getInt(11)
                            val minLat = c.getDouble(5); val maxLat = c.getDouble(6)
                            val minLon = c.getDouble(7); val maxLon = c.getDouble(8)
                            if (wkt.isEmpty()) { errors++; continue }

                            val now = Instant.now().toString()

                            if (SpatialDbManager.sourceUidSeen(OSM_SOURCE_ID, uid)) {
                                // Already imported once. Did the shape change?
                                //
                                // resolveByGeom answers from the in-memory hash
                                // map: INSERT means this geometry is not in the
                                // DB, so the trail moved since last time.
                                val d = SpatialDbManager.resolveByGeom(
                                    "trail", name ?: "Not Named", hash
                                )
                                if (d != SpatialDbManager.AddDecision.INSERT) {
                                    dropped++
                                    continue
                                }
                                // ⭐ Fred's call: insert as a SECOND trail rather
                                // than replacing. Replacing means delete and
                                // reinsert, which orphans every saved route that
                                // snapped to this lineId. Adding touches nothing.
                                if (insertNew(sDb, eDb, uid, name, wkt,
                                        minLat, maxLat, minLon, maxLon, now, changed = true,
                                        trailType = trailType, cartoCode = cartoCode,
                                        motorized = motorized)) {
                                    geomChanged++
                                } else {
                                    errors++
                                }
                                continue
                            }

                            val tid = UUID.randomUUID().toString()
                            val (anchorId, decision) = SpatialDbManager.insertTrail(
                                tid, name, wkt, minLat, maxLat, minLon, maxLon, now
                            )
                            SpatialDbManager.markSourceUid(OSM_SOURCE_ID, uid)
                            // CARTOCARRY-2026-08-31
                            writeProperties(eDb, anchorId, uid, now, orIgnore = true,
                                trailType = trailType, cartoCode = cartoCode,
                                motorized = motorized)
                            writeSpatialCarto(sDb, anchorId, cartoCode)

                            when (decision) {
                                SpatialDbManager.AddDecision.INSERT -> inserted++
                                SpatialDbManager.AddDecision.ALIAS -> aliased++
                                SpatialDbManager.AddDecision.DROP -> dropped++
                            }
                        }
                    }
                    sDb.setTransactionSuccessful()
                    eDb.setTransactionSuccessful()
                } finally {
                    eDb.endTransaction()
                    sDb.endTransaction()
                }

                seen += rows
                advance("trails", seen.toLong())
                if (rows < PAGE) break
            }

            complete("trails")

            // OSM-C3C-POINTS-2026-07-28: points, in the SAME job and the SAME dedup
            // session. Whole state regardless of the trail bbox -- labels you
            // cannot see when you pan past the box are worse than useless, and
            // 7,722 rows is nothing.
            val ptResult = importPoints(ctx, skinny, sDb)
            ptFound = ptResult[0]; ptInserted = ptResult[1]
            ptDropped = ptResult[2]; ptMoved = ptResult[3]

            logIngestion(eDb, found - errors, dropped, s, w, n, e)
            OsmImportLedger.appendImport(
                ctx, slug, scope, bboxJson(s, w, n, e),
                found, inserted, aliased, dropped, geomChanged, errors,
                ptFound, ptInserted, ptDropped, ptMoved
            )
            Log.i(TAG, "import complete: $slug found=$found inserted=$inserted " +
                "aliased=$aliased dropped=$dropped geomChanged=$geomChanged errors=$errors " +
                "| points found=$ptFound inserted=$ptInserted moved=$ptMoved dropped=$ptDropped")
            publish(force = true)
            return Result.success()
        } catch (ex: Exception) {
            Log.e(TAG, "import failed: ${ex.javaClass.simpleName} ${ex.message}", ex)
            return fail("${ex.javaClass.simpleName}: ${ex.message}")
        } finally {
            try { SpatialDbManager.endDedupSession() } catch (_: Exception) { }
            try { skinny?.close() } catch (_: Exception) { }
        }
    }

    // -- points -------------------------------------------------------------

    /**
     * OSM-C3C-POINTS-2026-07-28: every point layer the catalog enabled, into
     * reference_points.
     *
     * Returns [found, inserted, dropped, moved].
     *
     * ⛔ NOT `waypoints`. The shape matches and dedup already knows that type,
     * which makes it tempting -- but waypoints are USER-AUTHORED: editable,
     * deletable, listed in ArtifactListPanel, in scope for V3 sharing. Putting
     * 3,674 OSM localities there buries the user's own waypoints and makes
     * "remove all OSM data" impossible, because nothing tells the rows apart.
     *
     * ⭐ ONE TABLE, IN THE SPATIAL DB, NOT SPLIT. Trails split because their
     * attributes are numerous and rarely needed while drawing. Points are the
     * opposite -- a viewport label query wants name, fclass, lat and lon
     * together, so a split would add a join per viewport for nothing.
     *
     * ⭐ IDENTITY IS THE HASH, AND ONLY THE HASH.
     * Fred: "it is very important across sources that we do not duplicate geo
     * hashes." No source_id in the key -- because insertTrail already resolves
     * on hash alone regardless of source, and that is what makes a UGRC trail
     * and an OSM trail over the same ground ONE artifact instead of two
     * overlapping lines. A source-scoped point key would put the opposite rule
     * in the table next door.
     *
     * ⚠ So a peak and the locality named after it, on one OSM node, collapse to
     * one row -- first import wins the fclass. Chosen, not overlooked.
     */
    private fun importPoints(
        ctx: Context,
        skinny: SQLiteDatabase,
        sDb: SQLiteDatabase
    ): IntArray {
        sDb.execSQL(
            "CREATE TABLE IF NOT EXISTS reference_points (" +
                "point_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, " +
                "source_uid TEXT NOT NULL, name TEXT NOT NULL, fclass TEXT, " +
                "population INTEGER, lat REAL NOT NULL, lon REAL NOT NULL, " +
                "geom_hash TEXT NOT NULL, ingested_at TEXT)"
        )
        sDb.execSQL("CREATE INDEX IF NOT EXISTS ix_refpt_bbox ON reference_points(lat, lon)")
        sDb.execSQL("CREATE INDEX IF NOT EXISTS ix_refpt_fclass ON reference_points(fclass)")
        // Identity is the hash alone -- see the KDoc. Matches how insertTrail
        // resolves trails, so the two artifact types cannot drift apart.
        sDb.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS ix_refpt_identity " +
                "ON reference_points(geom_hash)"
        )

        // Small enough to hold: 7,722 for Utah. One query instead of one per row.
        // ⚠ NOT filtered by source -- identity is the hash across ALL sources,
        // so a point already present from anywhere must be seen here.
        val seen = HashSet<String>()
        try {
            sDb.rawQuery("SELECT geom_hash FROM reference_points", null).use { c ->
                while (c.moveToNext()) seen.add(c.getString(0))
            }
        } catch (ex: Exception) {
            Log.w(TAG, "reference_points preload failed: ${ex.message}")
        }
        val uidsKnown = HashSet<String>()
        try {
            sDb.rawQuery(
                "SELECT DISTINCT source_uid FROM reference_points WHERE source_id=?",
                arrayOf(OSM_SOURCE_ID)
            ).use { c -> while (c.moveToNext()) uidsKnown.add(c.getString(0)) }
        } catch (_: Exception) { }

        var found = 0
        var inserted = 0
        var dropped = 0
        var moved = 0
        val now = Instant.now().toString()

        for (layer in OsmLayerCatalog.enabledLayers(ctx).filter { it.isPoint }) {
            if (!tableExists(skinny, layer.targetTable)) {
                // A skinny built before this layer was enabled. Not an error.
                Log.i(TAG, "points: ${layer.targetTable} absent, skipping")
                continue
            }
            var n = 0
            skinny.rawQuery("SELECT COUNT(*) FROM ${layer.targetTable}", null).use { c ->
                if (c.moveToFirst()) n = c.getInt(0)
            }
            startType(layer.id, layer.label, n.toLong())
            found += n

            val hasPop = layer.columns.contains("population")
            val cols = "osm_id, name, fclass, lat, lon" + if (hasPop) ", population" else ""
            var done = 0

            sDb.beginTransaction()
            try {
                skinny.rawQuery("SELECT $cols FROM ${layer.targetTable}", null).use { c ->
                    while (c.moveToNext()) {
                        done++
                        val uid = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val fclass = c.getString(2) ?: ""
                        val lat = c.getDouble(3)
                        val lon = c.getDouble(4)
                        val pop = if (hasPop && !c.isNull(5)) c.getInt(5) else 0

                        // Same hash function trails use, over the same WKT
                        // conventions -- one rule, one identity scheme.
                        val hash = OsmGpkgDecoder.geomHash("POINT($lon $lat)")
                        if (seen.contains(hash)) { dropped++; continue }

                        val isMove = uidsKnown.contains(uid)
                        try {
                            sDb.execSQL(
                                "INSERT INTO reference_points (point_id,source_id,source_uid," +
                                    "name,fclass,population,lat,lon,geom_hash,ingested_at) " +
                                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                                arrayOf<Any?>(
                                    UUID.randomUUID().toString(), OSM_SOURCE_ID, uid,
                                    name, fclass, pop, lat, lon, hash, now
                                )
                            )
                            seen.add(hash)
                            uidsKnown.add(uid)
                            if (isMove) moved++ else inserted++
                        } catch (ex: Exception) {
                            // The unique index caught a row the preload missed.
                            dropped++
                        }
                        if (done % 1_000 == 0) advance(layer.id, done.toLong())
                    }
                }
                sDb.setTransactionSuccessful()
            } finally {
                sDb.endTransaction()
            }
            complete(layer.id)
            Log.i(TAG, "points ${layer.id}: $n rows")
        }

        Log.i(TAG, "points total: found=$found inserted=$inserted moved=$moved dropped=$dropped")
        return intArrayOf(found, inserted, dropped, moved)
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean = try {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)
        ).use { it.moveToFirst() }
    } catch (ex: Exception) { false }

    /**
     * OSM-C3C-POINTS-2026-07-28: the undo that trails do not have yet.
     *
     * Not called from anywhere in this patch -- it exists so the first real
     * import is safe to get wrong. Restoring the spatial DB by hand is not an
     * acceptable recovery path.
     */
    fun deleteAllOsmPoints(sDb: SQLiteDatabase): Int = try {
        val before = sDb.rawQuery(
            "SELECT COUNT(*) FROM reference_points WHERE source_id=?",
            arrayOf(OSM_SOURCE_ID)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        sDb.execSQL("DELETE FROM reference_points WHERE source_id=?", arrayOf<Any?>(OSM_SOURCE_ID))
        Log.i(TAG, "removed $before OSM reference point(s)")
        before
    } catch (ex: Exception) {
        Log.w(TAG, "deleteAllOsmPoints failed: ${ex.message}")
        0
    }

    // -- inserts ------------------------------------------------------------

    /**
     * A trail whose osm_id is known but whose geometry is new.
     *
     * ⚠ THE PROPERTIES WRITE USES A PLAIN INSERT, NOT "OR IGNORE", ON PURPOSE.
     * I do not know whether trail_properties carries a UNIQUE constraint on
     * (source_id, source_unique_id). If it does, OR IGNORE would silently
     * swallow this row -- leaving a trail in `trails` with no properties row,
     * invisible to the uid set, RE-INSERTED ON EVERY FUTURE RUN. Silent
     * unbounded growth. A plain INSERT makes the constraint announce itself on
     * the first run instead.
     */
    private fun insertNew(
        sDb: SQLiteDatabase, eDb: SQLiteDatabase, uid: String, name: String?,
        wkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        now: String, changed: Boolean,
        // CARTOCARRY-INSERTNEW-2026-08-31: required, not defaulted. A
        // geometry-changed row must carry the same classification as any other
        // -- otherwise this one branch keeps writing the empty strings the
        // whole change exists to remove. 0.74% of Utah geometries changed in a
        // month, so it is not a rare path.
        trailType: String?, cartoCode: String?, motorized: Int?
    ): Boolean {
        return try {
            val tid = UUID.randomUUID().toString()
            val (anchorId, _) = SpatialDbManager.insertTrail(
                tid, name, wkt, minLat, maxLat, minLon, maxLon, now
            )
            writeProperties(eDb, anchorId, uid, now, orIgnore = !changed,
                trailType = trailType, cartoCode = cartoCode, motorized = motorized)
            writeSpatialCarto(sDb, anchorId, cartoCode)   // CARTOCARRY-INSERTNEW-2026-08-31
            true
        } catch (ex: Exception) {
            Log.w(TAG, "second-geometry insert for uid=$uid failed: " +
                "${ex.javaClass.simpleName} ${ex.message} -- if this is a UNIQUE " +
                "constraint on (source_id, source_unique_id), the geometry-change " +
                "design needs revisiting")
            false
        }
    }

    /**
     * CARTOCARRY-2026-08-31: THE PARAGRAPH BELOW IS NO LONGER TRUE and is
     * kept only so the reversal is visible. OSM now HAS a carto classification
     * -- OsmTrailClassifier assigns one from the access tags the PBF carries,
     * and the extract writes it into the skinny. What was missing was this
     * method reading it. (superseded text follows)
     *
     * carto_code was written BLANK, deliberately.
     *
     * OSM has no carto classification, so anything else would be invented. Blank
     * renders cyan "Unspecified", which is visually distinct from every coded
     * government source and touches none of the three hand-synchronised files
     * that map carto_code to colour.
     */
    /**
     * CARTOCARRY-2026-08-31. Was passing EMPTY STRINGS for every attribute,
     * carto_code included -- so the classification computed at extract was
     * thrown away at the moment of import and 93,153 OSM rows landed untyped.
     *
     * ⚠ The parameters are REQUIRED, not defaulted. A caller that does not know
     * the classification is the bug this signature exists to catch (CODE RULE
     * 1): an optional would silently reinstate the empty-string behaviour for
     * whichever path forgot to pass it.
     */
    private fun writeProperties(
        eDb: SQLiteDatabase, trailId: String, uid: String, now: String, orIgnore: Boolean,
        trailType: String?, cartoCode: String?, motorized: Int?
    ) {
        val verb = if (orIgnore) "INSERT OR IGNORE" else "INSERT"
        // motorized_allowed carries a real value for the first time -- the
        // classifier already decides it, and it is what the router filters on.
        val motorizedText = when (motorized) {
            1 -> "Y"
            0 -> "N"
            else -> ""
        }
        eDb.execSQL(
            "$verb INTO trail_properties (trail_id,source_id,source_unique_id," +
                "designated_uses,motorized_allowed,surface_type,carto_code," +
                "owner_steward,county,agency_id,ingested_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(trailId, OSM_SOURCE_ID, uid, trailType ?: "", motorizedText,
                "", cartoCode ?: "", "", "", uid, now)
        )
    }

    /**
     * CARTOCARRY-2026-08-31. TrailImporter writes carto to `trails` as well as
     * trail_properties -- "both stores populated" since 06-21, and
     * SpatialDbManager:1689 arbitrates between them ("spatial wins only if it
     * has a real value"). The OSM path must do the same or the two stores
     * disagree about the same trail.
     * ⚠ Guarded: a failure here must never fail the import. The extension DB
     * has the value regardless.
     */
    private fun writeSpatialCarto(sDb: SQLiteDatabase, trailId: String, cartoCode: String?) {
        if (cartoCode.isNullOrEmpty()) return
        try {
            sDb.execSQL("UPDATE trails SET carto_code=? WHERE trail_id=?",
                arrayOf<Any?>(cartoCode, trailId))
        } catch (ex: Exception) {
            Log.w(TAG, "spatial carto write failed for $trailId: ${ex.message}")
        }
    }

    private fun logIngestion(
        eDb: SQLiteDatabase, count: Int, dupes: Int,
        s: Double, w: Double, n: Double, e: Double
    ) {
        try {
            eDb.execSQL(
                "INSERT INTO source_ingestions (ingestion_id,source_id,ingested_at," +
                    "trail_count,dupes_skipped,bounds_json) VALUES (?,?,?,?,?,?)",
                arrayOf<Any?>(
                    UUID.randomUUID().toString(), OSM_SOURCE_ID,
                    Instant.now().toString(), count, dupes, bboxJson(s, w, n, e)
                )
            )
        } catch (ex: Exception) {
            Log.w(TAG, "source_ingestions write failed: ${ex.message}")
        }
    }

    private fun bboxJson(s: Double, w: Double, n: Double, e: Double) =
        "{\"s\":$s,\"w\":$w,\"n\":$n,\"e\":$e}"

    // -- progress (same model C2 uses) --------------------------------------

    private fun startType(id: String, label: String, total: Long) {
        typeStartMs = System.currentTimeMillis()
        items[id] = OsmExtractProgress.Item(id, label, 0, total, false, -1)
        publish(force = true)
    }

    private fun advance(id: String, done: Long) {
        val cur = items[id] ?: return
        val elapsed = System.currentTimeMillis() - typeStartMs
        val eta = if (done > 0L && elapsed > 2_000L && cur.total > 0L) {
            (((cur.total - done).toDouble() * (elapsed.toDouble() / done)) / 1000.0)
                .toLong().coerceAtLeast(0L)
        } else -1L
        items[id] = cur.copy(done = done, etaSec = eta)
        publish(force = false)
    }

    private fun complete(id: String) {
        items[id]?.let {
            items[id] = it.copy(
                done = if (it.total > 0) it.total else it.done,
                complete = true, etaSec = -1
            )
        }
        publish(force = true)
    }

    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishMs < OsmExtractProgress.PUBLISH_MS) return
        lastPublishMs = now
        setProgressAsync(
            Data.Builder()
                .putString(OsmExtractProgress.KEY, OsmExtractProgress.toJson(items.values.toList()))
                .build()
        )
        if (force || now - lastNotifyMs >= OsmExtractProgress.NOTIFY_MS) {
            lastNotifyMs = now
            val active = items.values.firstOrNull { !it.complete }
            setForegroundSafely(
                if (active == null) "Finishing" else "${active.label} ${active.percent}%"
            )
        }
    }

    // -- plumbing -----------------------------------------------------------

    private fun fail(msg: String): Result {
        Log.e(TAG, "FAIL: $msg")
        return Result.failure(workDataOf(KEY_ERROR to msg))
    }

    private fun setForegroundSafely(text: String) {
        try {
            setForegroundAsync(buildForegroundInfo(text))
        } catch (ex: Exception) {
            Log.w(TAG, "foreground unavailable: ${ex.javaClass.simpleName}")
        }
    }

    private fun buildForegroundInfo(text: String): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OSM import", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Importing OSM trails")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }
}
