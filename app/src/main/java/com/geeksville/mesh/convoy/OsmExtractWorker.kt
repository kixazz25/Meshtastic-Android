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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * C2 (REDUCE): the Geofabrik zip becomes the skinny DB, as ONE operation.
 *
 * OSM-C2-WORKER-2026-07-28
 *
 * RULE R2 -- only the zip and the skinny DB are recovery points. The .gpkg in
 * between never is, so it is created inside this worker and deleted before it
 * returns. RULE R3 -- existence means COMPLETE, which is why the skinny is
 * built at a .part path and atomically renamed only after subset_meta is
 * written. A killed run leaves debris that sweepDebris() removes; it can never
 * leave something that looks finished.
 *
 * ⚠⚠ THE SHARPEST EDGE IN THIS FILE. OsmImportStage.verifySkinny() does
 * SELECT COUNT(*) FROM osm_trails and compares it against subset_meta
 * feature_count. If feature_count were the TOTAL across every extracted layer,
 * verification would fail, stageOf() would DELETE the skinny, and the panel
 * would drop back to REDUCE with nothing logged as an error. feature_count is
 * the osm_trails count ONLY -- see writeMeta().
 *
 * ⚠ THE ZIP IS NEVER DELETED TO MAKE ROOM. It is recovery point 1, and on a
 * marginal connection re-acquiring it costs hours (measured: a state download
 * ran ~2h on device WiFi 2026-07-28). The free-space gate runs BEFORE the
 * unzip so the run fails cheaply instead of half-way.
 */
class OsmExtractWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "OsmExtract"

        const val KEY_SLUG = "slug"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "osm_extract"
        private const val NOTIF_ID = 0x0
        private const val BATCH_ROWS = 5_000
        private const val UNZIP_BUF = 1 shl 16

        /** Uncompressed size plus a quarter. Arizona: gpkg 1,214 MB, trails
         *  skinny 149.6 MB (12%), and places + natural sit on top of that. */
        private const val SPACE_HEADROOM_NUM = 5L
        private const val SPACE_HEADROOM_DEN = 4L

        fun uniqueName(slug: String) = "osm_extract_$slug"

        fun enqueue(ctx: Context, slug: String) {
            val req = OneTimeWorkRequestBuilder<OsmExtractWorker>()
                .setInputData(workDataOf(KEY_SLUG to slug))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                uniqueName(slug), ExistingWorkPolicy.KEEP, req
            )
            Log.i(TAG, "enqueued extract for $slug")
        }
    }

    private val items = LinkedHashMap<String, OsmExtractProgress.Item>()
    private var typeStartMs = 0L
    private var lastPublishMs = 0L
    // OSM-C2-PROGRESS-COUNTERS-2026-07-28: the notification is rebuilt far less often than the
    // panel is updated -- see the two constants in OsmExtractProgress.
    private var lastNotifyMs = 0L

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val slug = inputData.getString(KEY_SLUG)
            ?: return fail("no slug supplied")

        val zip = OsmImportStage.zipFor(ctx, slug)
        if (!zip.exists()) return fail("zip missing for $slug")

        val gpkg = OsmImportStage.gpkgFor(ctx, slug)
        val skinny = OsmImportStage.skinnyFor(ctx, slug)
        val part = File(skinny.absolutePath + ".part")

        val layers = OsmLayerCatalog.enabledLayers(ctx)
        if (layers.isEmpty()) return fail("no enabled layers in catalog")

        // Point layers FIRST -- see the class KDoc. Ordering is enforced here
        // rather than trusted to the JSON, so a reordered catalog cannot
        // quietly move the self-test after the expensive pass.
        val ordered = layers.sortedBy { if (it.isPoint) 0 else 1 }

        seedItems(ordered)

        var db: SQLiteDatabase? = null
        var src: SQLiteDatabase? = null
        try {
            setForegroundSafely("Preparing")

            // -- 1. free space ------------------------------------------
            val uncompressed = uncompressedSize(zip)
            if (uncompressed <= 0L) return fail("cannot read uncompressed size from zip")
            val needed = uncompressed * SPACE_HEADROOM_NUM / SPACE_HEADROOM_DEN
            val free = OsmImportStage.dirFor(ctx, slug).usableSpace
            Log.i(TAG, "space: need ${mb(needed)} MB, free ${mb(free)} MB " +
                "(uncompressed ${mb(uncompressed)} MB)")
            if (free < needed) {
                return fail("not enough space: need ${mb(needed)} MB, ${mb(free)} MB free")
            }

            // -- 2. unzip ------------------------------------------------
            part.delete()
            gpkg.delete()
            if (!unzipGpkg(zip, gpkg, uncompressed)) return fail("unzip failed")
            complete(OsmExtractProgress.ID_UNZIP)

            // -- 3. open both databases ----------------------------------
            src = SQLiteDatabase.openDatabase(
                gpkg.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            db = SQLiteDatabase.openOrCreateDatabase(part.absolutePath, null)
            // OSM-JOURNAL-PRAGMA-2026-07-28: journal_mode RETURNS A ROW -- SQLite reports
            // back the mode it settled on -- so it is a QUERY, and execSQL
            // rejects any statement that returns data. The Python this was
            // ported from does the identical thing legally, because
            // sqlite3.execute does not care. synchronous returns nothing and
            // stays on execSQL.
            db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=OFF")

            var trailRows = 0
            var badTotal = 0
            var candidateTotal = 0
            val classes = mutableListOf<String>()

            for (layer in ordered) {
                val res = runLayer(src, db, layer, slug) ?: return fail(
                    "layer '${layer.id}' aborted -- see log"
                )
                candidateTotal += res.candidates
                // OSM-C2-PROGRESS-COUNTERS-2026-07-28: the ledger's badGeometry field means
                // FAILED DECODES. Dropped-for-no-name rows are expected
                // filtering and would swamp it -- Utah alone contributes
                // 89,390 of them from the natural layer.
                badTotal += res.badGeometry
                if (layer.targetTable == "osm_trails") {
                    trailRows = res.kept
                    classes.addAll(layer.filterValues)
                }
                complete(layer.id)
            }

            // -- 4. meta, then the atomic rename -------------------------
            writeMeta(db, zip.name, classes, trailRows)
            db.close(); db = null
            src.close(); src = null

            if (trailRows <= 0) return fail("no trails extracted -- refusing to publish skinny DB")

            if (!part.renameTo(skinny)) {
                part.delete()
                return fail("atomic rename failed")
            }
            gpkg.delete()

            // OSM-ZIPDEL-2026-07-30 (Fred): "when we extract the zip should be
            // removed. that is the process."
            //
            // The zip is recovery point 1 ONLY until the skinny exists. It
            // exists now -- published by the atomic rename above -- so the zip
            // is redundant from here on. C4 never removed it: discardState()
            // sweeps osm/<slug>/ and has no business in the user's Downloads
            // folder, so every import left the original behind.
            //
            // Matched by SLUG against the same shape OsmImportStage:104
            // accepts, including the "(1)" a browser adds to a repeat
            // download. Duplicates of this state therefore all go in one pass,
            // which a retained URI would not have caught.
            //
            // \u26a0 NOT MediaStore -- its delete moves to trash and this must be
            // permanent. All Files Access is held, so File.delete() is direct.
            //
            // \u26a0 GUARDED: a failure here must NEVER fail the extract. The
            // skinny is published and the run stands regardless.
            try {
                val dl = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val zipPat = Regex(
                    "^" + Regex.escape(slug) +
                        "-(?:latest|\\d{6,8})-free\\.gpkg(?:\\s*\\(\\d+\\))?" +
                        "\\.zip(?:\\s*\\(\\d+\\))?$",
                    RegexOption.IGNORE_CASE
                )
                var removed = 0
                var freedMb = 0L
                dl?.listFiles()?.forEach { f ->
                    if (!f.isDirectory && zipPat.matches(f.name)) {
                        val mb = f.length() / 1048576L
                        if (f.delete()) {
                            removed++; freedMb += mb
                            Log.i(TAG, "deleted download ${f.name} ($mb MB)")
                        } else {
                            Log.w(TAG, "could not delete download ${f.name}")
                        }
                    }
                }
                Log.i(TAG, "downloads swept for $slug: $removed file(s), $freedMb MB")
            } catch (e: Exception) {
                Log.w(TAG, "download sweep skipped: ${e.javaClass.simpleName}")
            }

            OsmImportLedger.recordExtract(
                ctx = ctx,
                slug = slug,
                gpkgBytes = uncompressed,
                classes = classes,
                candidateRows = candidateTotal,
                kept = trailRows,
                badGeometry = badTotal,
                skinnyBytes = skinny.length()
            )
            publish(force = true)
            Log.i(TAG, "extract complete: $slug trails=$trailRows skinny=${mb(skinny.length())} MB")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "extract failed: ${e.javaClass.simpleName} ${e.message}", e)
            return fail("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { db?.close() } catch (_: Exception) { }
            try { src?.close() } catch (_: Exception) { }
            // R3: never leave anything that could look complete.
            if (part.exists()) part.delete()
            if (gpkg.exists()) gpkg.delete()
        }
    }

    // -- progress -----------------------------------------------------------

    private fun seedItems(layers: List<OsmLayerCatalog.Layer>) {
        items[OsmExtractProgress.ID_UNZIP] =
            OsmExtractProgress.Item(OsmExtractProgress.ID_UNZIP, "Unzip", 0, 0, false, -1)
        for (l in layers) {
            items[l.id] = OsmExtractProgress.Item(l.id, l.label, 0, 0, false, -1)
        }
    }

    private fun startType(id: String, total: Long) {
        typeStartMs = System.currentTimeMillis()
        items[id]?.let { items[id] = it.copy(done = 0, total = total, etaSec = -1) }
        publish(force = true)
    }

    /**
     * Rate is measured from THIS type's start only -- never carried across a
     * boundary. See the note in OsmExtractProgress.
     */
    private fun advance(id: String, done: Long) {
        val cur = items[id] ?: return
        val now = System.currentTimeMillis()
        val elapsed = now - typeStartMs
        val eta = if (done > 0L && elapsed > 2_000L && cur.total > 0L) {
            val perUnit = elapsed.toDouble() / done.toDouble()
            (((cur.total - done).toDouble() * perUnit) / 1000.0).toLong().coerceAtLeast(0L)
        } else -1L
        items[id] = cur.copy(done = done, etaSec = eta)
        publish(force = false)
    }

    private fun complete(id: String) {
        items[id]?.let {
            items[id] = it.copy(done = if (it.total > 0) it.total else it.done,
                complete = true, etaSec = -1)
        }
        publish(force = true)
    }

    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishMs < OsmExtractProgress.PUBLISH_MS) return
        lastPublishMs = now
        val json = OsmExtractProgress.toJson(items.values.toList())
        setProgressAsync(Data.Builder().putString(OsmExtractProgress.KEY, json).build())

        // OSM-C2-PROGRESS-COUNTERS-2026-07-28: the notification is the expensive half, so it
        // keeps its own slower clock. Forced publishes (type start, type
        // complete) always refresh it, so the user still sees each stage.
        if (force || now - lastNotifyMs >= OsmExtractProgress.NOTIFY_MS) {
            lastNotifyMs = now
            val active = items.values.firstOrNull { !it.complete }
            setForegroundSafely(
                if (active == null) "Finishing" else "${active.label} ${active.percent}%"
            )
        }
    }

    // -- unzip --------------------------------------------------------------

    private fun uncompressedSize(zip: File): Long = try {
        ZipFile(zip).use { zf ->
            zf.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith(".gpkg", true) }
                ?.size ?: -1L
        }
    } catch (e: Exception) {
        Log.e(TAG, "cannot read zip: ${e.javaClass.simpleName} ${e.message}")
        -1L
    }

    /**
     * Geofabrik nests the .gpkg inside a directory named after the archive, so
     * the entry is found by SUFFIX rather than by an assumed path.
     */
    private fun unzipGpkg(zip: File, out: File, total: Long): Boolean {
        startType(OsmExtractProgress.ID_UNZIP, total)
        return try {
            ZipFile(zip).use { zf ->
                val entry = zf.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.endsWith(".gpkg", true) }
                    ?: return false
                zf.getInputStream(entry).use { ins ->
                    FileOutputStream(out).use { outs ->
                        val buf = ByteArray(UNZIP_BUF)
                        var written = 0L
                        while (true) {
                            if (isStopped) return false
                            val n = ins.read(buf)
                            if (n < 0) break
                            outs.write(buf, 0, n)
                            written += n
                            advance(OsmExtractProgress.ID_UNZIP, written)
                        }
                        outs.flush()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "unzip failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    // -- passes -------------------------------------------------------------

    /**
     * OSM-C2-PROGRESS-COUNTERS-2026-07-28: badGeometry and droppedNoName are OPPOSITE SIGNALS
     * and must not share a counter.
     *
     * droppedNoName is the layer working as designed -- gis_osm_natural_free
     * is overwhelmingly unnamed, and Geofabrik blanks names it has already
     * flagged as junk. badGeometry means the GPKG header maths is wrong.
     * Counted together, Utah's 89,390 expected drops would have hidden any
     * number of real decode failures.
     */
    private data class LayerResult(
        val candidates: Int,
        val kept: Int,
        val badGeometry: Int,
        val droppedNoName: Int
    )

    /**
     * Rows are read by KEYSET pagination on fid, not LIMIT/OFFSET -- OFFSET is
     * O(n) on a 1.2 GB table, and small pages keep each CursorWindow well under
     * its 2 MB ceiling, which a long way's geometry blob could otherwise
     * approach.
     */
    private fun runLayer(
        src: SQLiteDatabase,
        dst: SQLiteDatabase,
        layer: OsmLayerCatalog.Layer,
        slug: String,          // CARTO0-2026-08-30
    ): LayerResult? {
        val types = sourceColumnTypes(src, layer.sourceTable)
        if (types.isEmpty()) {
            Log.e(TAG, "source table missing or empty schema: ${layer.sourceTable}")
            return null
        }
        val geomCol = when {
            types.containsKey("geom") -> "geom"
            types.containsKey("geometry") -> "geometry"
            else -> {
                Log.e(TAG, "no geom column on ${layer.sourceTable}")
                return null
            }
        }
        for (c in layer.columns) {
            if (!types.containsKey(c)) {
                Log.e(TAG, "column '$c' not on ${layer.sourceTable} -- catalog is wrong")
                return null
            }
        }

        val where = StringBuilder()
        val args = mutableListOf<String>()
        if (layer.filterColumn != null) {
            where.append(layer.filterColumn).append(" IN (")
            where.append(layer.filterValues.joinToString(",") { "?" })
            where.append(")")
            args.addAll(layer.filterValues)
        }

        val countSql = "SELECT COUNT(*) FROM ${layer.sourceTable}" +
            if (where.isNotEmpty()) " WHERE $where" else ""
        var candidates = 0
        src.rawQuery(countSql, args.toTypedArray()).use { c ->
            if (c.moveToFirst()) candidates = c.getInt(0)
        }
        Log.i(TAG, "layer ${layer.id}: $candidates candidate rows from ${layer.sourceTable}")
        startType(layer.id, candidates.toLong())

        createTarget(dst, layer, types)

        val extent = if (layer.isPoint) declaredExtent(src, layer.sourceTable) else null

        val cols = layer.columns.joinToString(",")
        val baseSql = "SELECT fid,$cols,$geomCol FROM ${layer.sourceTable} WHERE fid > ?" +
            (if (where.isNotEmpty()) " AND $where" else "") +
            " ORDER BY fid LIMIT $BATCH_ROWS"

        var lastFid = 0L
        var kept = 0
        var badGeom = 0
        var noName = 0
        var seen = 0
        // EXTRACTJOIN-2026-08-30: opened once per layer, read-only, closed in
        // the finally below. ⚠ Absent or unreadable is NOT fatal -- every row
        // then classifies on shape alone, which is exactly today's behaviour,
        // and the log says so rather than the import failing.
        var classified = 0
        var excluded = 0   // EXTRACTSKIP-2026-08-30
        // CARTO0-2026-08-30: slug is a LOCAL in doWork (:90), not a property.
        val tagDb: SQLiteDatabase? =
            if (layer.targetTable == "osm_trails") openTagDb(slug) else null
        if (layer.targetTable == "osm_trails" && tagDb == null) {
            Log.w(TAG, "no tag table for ${layer.id} -- classifying on shape alone")
        }
        try {

        while (true) {
            if (isStopped) return null
            val a = ArrayList<String>()
            a.add(lastFid.toString())
            a.addAll(args)
            var rowsThisPage = 0

            dst.beginTransaction()
            try {
                src.rawQuery(baseSql, a.toTypedArray()).use { c ->
                    while (c.moveToNext()) {
                        rowsThisPage++
                        lastFid = c.getLong(0)
                        val blob = c.getBlob(layer.columns.size + 1)
                        val vals = ArrayList<Any?>(layer.columns.size + 8)
                        for (i in layer.columns.indices) {
                            vals.add(if (c.isNull(i + 1)) null else c.getString(i + 1))
                        }
                        if (layer.isPoint) {
                            val p = OsmGpkgDecoder.decodePoint(blob)
                            if (p == null) { badGeom++; continue }
                            if (extent != null && !OsmGpkgDecoder.withinExtent(
                                    p, extent[0], extent[1], extent[2], extent[3])
                            ) {
                                // ⛔ THE ABORT. A point outside the layer's own
                                // declared extent means the envelope offset is
                                // wrong, and writing that into a recovery point
                                // is the worst outcome available here, because
                                // existence means complete.
                                Log.e(TAG, "EXTENT VIOLATION in ${layer.id}: " +
                                    "lon=${p.lon} lat=${p.lat} outside " +
                                    "W${extent[0]} S${extent[1]} E${extent[2]} N${extent[3]} " +
                                    "-- envelope offset is wrong, aborting before trails")
                                dst.endTransaction()
                                return null
                            }
                            // Names Geofabrik blanked are junk it already
                            // flagged ("fixme", "none"), not merely missing.
                            val nameIdx = layer.columns.indexOf("name")
                            if (nameIdx >= 0) {
                                val n = vals[nameIdx] as? String
                                if (n.isNullOrBlank()) { noName++; continue }
                            }
                            vals.add(p.lat)
                            vals.add(p.lon)
                        } else {
                            val g = OsmGpkgDecoder.decodeLine(blob)
                            if (g == null) { badGeom++; continue }
                            vals.add(g.wkt)
                            vals.add(g.geomHash)
                            vals.add(g.minLat); vals.add(g.maxLat)
                            vals.add(g.minLon); vals.add(g.maxLon)
                            // EXTRACTJOIN-2026-08-30: classify here, once.
                            // ⚠ osm_id is layer.columns[0] -- see the catalogue.
                            // A row with no tag match keeps its shape answer
                            // rather than being dropped: the join is a lookup,
                            // not a gate.
                            if (layer.targetTable == "osm_trails") {
                                val oid = (vals.getOrNull(0) as? String)?.toLongOrNull()
                                val r = classifyRow(tagDb, oid)
                                // EXTRACTSKIP-2026-08-30: only what a machine can
                                // be on. The widened net casts 423,255 candidates
                                // on Utah; the rules include ~125,831. Writing the
                                // rest would triple the trails table with service
                                // roads, residential streets and footpaths, each
                                // carrying WKT.
                                // ⭐ The reclassification benefit does NOT depend
                                // on storing them: osm_way_tags.db is kept, so a
                                // rule change re-reads the tags and rebuilds the
                                // skinny. Keeping unrideable rows adds nothing.
                                // ⚠ `unclassified unknown` IS still written --
                                // motorized=true, in on shape alone, which is
                                // exactly today's behaviour for every OSM trail.
                                if (!r.motorized) { excluded++; continue }
                                vals.add(r.type)
                                vals.add(r.carto)
                                vals.add(1)
                                if (r.carto != null) classified++
                            }
                        }
                        insertRow(dst, layer, vals)
                        kept++
                    }
                }
                dst.setTransactionSuccessful()
            } finally {
                dst.endTransaction()
            }

            seen += rowsThisPage
            advance(layer.id, seen.toLong())
            if (rowsThisPage < BATCH_ROWS) break
        }

        createIndexes(dst, layer)
        Log.i(TAG, "layer ${layer.id}: kept=$kept badGeom=$badGeom " +
            "noName=$noName classified=$classified excluded=$excluded " +   // EXTRACTSKIP-2026-08-30
            "of $candidates")
        return LayerResult(candidates, kept, badGeom, noName)
        } finally {
            // EXTRACTJOIN-2026-08-30
            try { tagDb?.close() } catch (_: Exception) {}
        }
    }

    /**
     * EXTRACTJOIN-2026-08-30. The interim tag table built by OsmPbfTagReader,
     * read-only. ⛔ NOT deleted when the skinny is built: it is the input the
     * rules run against, so keeping it makes a rule tweak a local
     * reclassification pass rather than a re-download and re-extract.
     */
    private fun openTagDb(slug: String): SQLiteDatabase? {
        return try {
            // CARTO0-2026-08-30: applicationContext -- OsmExtractWorker is a
            // CoroutineWorker (:50), so it is a real property here. The ctx at
            // :89 is a local alias inside doWork and not visible from here.
            val f = OsmImportStage.tagsFor(applicationContext, slug)
            if (!OsmImportStage.verifyTags(f)) return null
            SQLiteDatabase.openDatabase(
                f.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
        } catch (e: Exception) {
            Log.w(TAG, "tag table would not open: ${e.message}")
            null
        }
    }

    /**
     * EXTRACTJOIN-2026-08-30. One lookup, one classification. A miss returns
     * the shape answer -- unclassified unknown, motorized -- which is what
     * every row got before this existed.
     */
    private fun classifyRow(tagDb: SQLiteDatabase?, osmId: Long?):
        OsmTrailClassifier.Result {
        if (tagDb == null || osmId == null) {
            return OsmTrailClassifier.Result(
                OsmTrailClassifier.UNCLASSIFIED_UNKNOWN, null, true
            )
        }
        return try {
            tagDb.rawQuery(
                "SELECT highway,surface,tracktype,access,motor_vehicle,vehicle," +
                    "four_wd_only,ohv,atv,motorcar,foot,bicycle " +
                    "FROM ${OsmPbfTagReader.TABLE} WHERE osm_id=?",
                arrayOf(osmId.toString())
            ).use { c ->
                if (!c.moveToFirst()) {
                    OsmTrailClassifier.Result(
                        OsmTrailClassifier.UNCLASSIFIED_UNKNOWN, null, true
                    )
                } else {
                    fun s(i: Int) = if (c.isNull(i)) null else c.getString(i)
                    OsmTrailClassifier.classify(
                        highway = s(0), surface = s(1), tracktype = s(2),
                        access = s(3), motorVehicle = s(4), vehicle = s(5),
                        fourWdOnly = s(6), ohv = s(7), atv = s(8),
                        motorcar = s(9), foot = s(10), bicycle = s(11),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tag lookup failed for $osmId: ${e.message}")
            OsmTrailClassifier.Result(
                OsmTrailClassifier.UNCLASSIFIED_UNKNOWN, null, true
            )
        }
    }

    private fun sourceColumnTypes(db: SQLiteDatabase, table: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            db.rawQuery("PRAGMA table_info(\"" + table + "\")", null).use { c ->
                val iName = c.getColumnIndex("name")
                val iType = c.getColumnIndex("type")
                while (c.moveToNext()) {
                    out[c.getString(iName)] = c.getString(iType) ?: "TEXT"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PRAGMA failed for $table: ${e.message}")
        }
        return out
    }

    private fun declaredExtent(db: SQLiteDatabase, table: String): DoubleArray? = try {
        var r: DoubleArray? = null
        db.rawQuery(
            "SELECT min_x,min_y,max_x,max_y FROM gpkg_contents WHERE table_name=?",
            arrayOf(table)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                r = doubleArrayOf(c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3))
            }
        }
        r
    } catch (e: Exception) {
        Log.w(TAG, "no declared extent for $table: ${e.message}")
        null
    }

    /**
     * Source column TYPES are mirrored rather than guessed from names, so
     * population stays INTEGER and "ORDER BY population DESC" sorts as a
     * number instead of a string.
     */
    private fun createTarget(
        db: SQLiteDatabase,
        layer: OsmLayerCatalog.Layer,
        types: Map<String, String>
    ) {
        val defs = mutableListOf<String>()
        for (c in layer.columns) defs.add("\"" + c + "\" " + (types[c] ?: "TEXT"))
        if (layer.isPoint) {
            defs.add("lat REAL NOT NULL")
            defs.add("lon REAL NOT NULL")
        } else {
            defs.add("wkt TEXT NOT NULL")
            defs.add("geom_hash TEXT NOT NULL")
            defs.add("min_lat REAL"); defs.add("max_lat REAL")
            defs.add("min_lon REAL"); defs.add("max_lon REAL")
            // EXTRACTJOIN-2026-08-30: derived, like the geometry columns above
            // -- NOT catalogue columns, because the GeoPackage does not supply
            // them. ⛔ This order must match the order they are appended to
            // `vals` in runLayer; insertRow binds positionally.
            if (layer.targetTable == "osm_trails") {
                defs.add("trail_type TEXT")
                defs.add("carto_code TEXT")
                defs.add("motorized INTEGER")
            }
        }
        db.execSQL("DROP TABLE IF EXISTS " + layer.targetTable)
        db.execSQL("CREATE TABLE " + layer.targetTable + " (" + defs.joinToString(",") + ")")
    }

    private fun insertRow(
        db: SQLiteDatabase,
        layer: OsmLayerCatalog.Layer,
        vals: List<Any?>
    ) {
        val sb = StringBuilder("INSERT INTO ").append(layer.targetTable).append(" VALUES (")
        for (i in vals.indices) { if (i > 0) sb.append(','); sb.append('?') }
        sb.append(')')
        val st = db.compileStatement(sb.toString())
        st.use { s ->
            for (i in vals.indices) {
                when (val v = vals[i]) {
                    null -> s.bindNull(i + 1)
                    is Double -> s.bindDouble(i + 1, v)
                    is Long -> s.bindLong(i + 1, v)
                    else -> s.bindString(i + 1, v.toString())
                }
            }
            s.executeInsert()
        }
    }

    private fun createIndexes(db: SQLiteDatabase, layer: OsmLayerCatalog.Layer) {
        val t = layer.targetTable
        try {
            if (layer.isPoint) {
                db.execSQL("CREATE INDEX IF NOT EXISTS ix_${t}_ll ON $t(lat,lon)")
                if (layer.columns.contains("name"))
                    db.execSQL("CREATE INDEX IF NOT EXISTS ix_${t}_name ON $t(name)")
            } else {
                db.execSQL("CREATE INDEX IF NOT EXISTS ix_${t}_hash ON $t(geom_hash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS ix_${t}_bbox ON $t(min_lat,max_lat,min_lon,max_lon)")
                if (layer.columns.contains("name"))
                    db.execSQL("CREATE INDEX IF NOT EXISTS ix_${t}_name ON $t(name)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "index creation failed on $t: ${e.message}")
        }
    }

    /**
     * ⚠⚠ feature_count IS THE osm_trails COUNT ONLY. verifySkinny() compares
     * it against SELECT COUNT(*) FROM osm_trails; a total across all layers
     * makes verification fail, which makes stageOf() DELETE this file.
     *
     * The attribution row is not decoration -- ODbL requires attribution for
     * what is displayed, and this is where the provenance travels with the
     * data rather than living only in a UI string someone can forget.
     */
    private fun writeMeta(
        db: SQLiteDatabase,
        sourceFile: String,
        classes: List<String>,
        trailRows: Int
    ) {
        db.execSQL("DROP TABLE IF EXISTS subset_meta")
        db.execSQL("CREATE TABLE subset_meta (key TEXT PRIMARY KEY, value TEXT)")
        val meta = listOf(
            "source_file" to sourceFile,
            "classes" to classes.joinToString(","),
            "feature_count" to trailRows.toString(),
            "built_at" to java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US
            ).format(java.util.Date()),
            "wkt_format" to "app-exact: comma-no-space, Kotlin Double.toString()",
            "hash_algo" to "SHA-256(wkt utf-8) hex lowercase",
            "attribution" to "\u00a9 OpenStreetMap contributors (ODbL)"
        )
        for ((k, v) in meta) {
            db.execSQL("INSERT INTO subset_meta VALUES (?,?)", arrayOf<Any?>(k, v))
        }
    }

    // -- plumbing -----------------------------------------------------------

    private fun mb(bytes: Long): Long = bytes / 1_048_576L

    private fun fail(msg: String): Result {
        Log.e(TAG, "FAIL: $msg")
        return Result.failure(workDataOf(KEY_ERROR to msg))
    }

    /**
     * Best-effort by design: if the app's manifest does not permit a
     * dataSync foreground service, the extract still runs as ordinary
     * background work rather than failing outright.
     */
    private fun setForegroundSafely(text: String) {
        try {
            setForegroundAsync(buildForegroundInfo(text))
        } catch (e: Exception) {
            Log.w(TAG, "foreground unavailable: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun buildForegroundInfo(text: String): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "OSM extract", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val n: Notification = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Extracting OSM trail data")
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
