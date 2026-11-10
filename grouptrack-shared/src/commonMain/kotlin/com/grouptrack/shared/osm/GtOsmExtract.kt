package com.grouptrack.shared.osm

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtImportConverter
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.text.GtTime
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmExtractWorker -- C2 (REDUCE): the Geofabrik zip becomes the skinny DB, as ONE operation
 * -- PORTED AS IS. Free-space gate, unzip (the .gpkg is never a recovery point), point layers FIRST (the extent self-test), keyset pages
 * of 5,000 by fid, the GPKG decode, the trails' tag join + classification (EXTRACTJOIN / EXTRACTSKIP: only motorized rows written),
 * subset_meta (feature_count = the osm_trails count ONLY), the .part -> skinny atomic rename, the download sweep, the ledger record.
 * WHAT CHANGED, and only this:
 *  - CoroutineWorker shims -> this class (2.7e already ran it in process, INPROCESS-2026-10-02): setProgressAsync -> [onProgress] with
 *    the same JSON; setForegroundSafely -> plat.foreground (2.7e still builds the notification there); isStopped stays false.
 *  - ZipFile -> okio's zip FileSystem: the entry is the FIRST ".gpkg" in CENTRAL-DIRECTORY order (GtImportConverter.zipEntryNames, as
 *    ZipFile.entries()), its uncompressed size from the zip's metadata; the unzip copies in 64 KB reads, advance() after each read.
 *  - SQLiteDatabase -> gtOpenDatabase; rawQuery / Cursor -> statements (getString = GtRead.cellText, Android's text for every type;
 *    getBlob of NULL -> null; getLong / getDouble / getInt as SQLite's); beginTransaction / setTransactionSuccessful / endTransaction ->
 *    BEGIN / COMMIT / ROLLBACK, INCLUDING 2.7e's double endTransaction after an EXTENT VIOLATION (Android throws
 *    IllegalStateException("Cannot perform this operation because there is no current transaction.") from the finally, and that --
 *    not "layer aborted" -- is the failure 2.7e reports; reproduced as such).
 *  - insertRow's compileStatement binding is 2.7e's: null -> NULL, Double -> REAL, Long -> INTEGER, ANYTHING ELSE -> TEXT of toString()
 *    (so the classification's motorized `1` -- an Int -- is bound as the TEXT "1", and the column's INTEGER affinity stores 1).
 *    execSQL(sql, args) binds as Android's execSQL (DatabaseUtils.bindObjectToProgram).
 *  - File / usableSpace / delete / renameTo -> okio via env.files.fs + plat.usableSpace; a failed rename -> false.
 *  - System.currentTimeMillis -> env.clock.epochMs; SimpleDateFormat(Date()) "yyyy-MM-dd'T'HH:mm:ss" -> GtTime.local(env.clock.localNow()).
 *  - The Downloads sweep -> plat.sweepDownloadedZips(slug), guarded as 2.7e (a failure never fails the extract). Log -> gtLogger.
 */
class GtOsmExtract(
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
    private val catalog = GtOsmLayers(env)
    private val isStopped = false

    private val items = LinkedHashMap<String, GtOsmProgress.Item>()
    private var typeStartMs = 0L
    private var lastPublishMs = 0L
    private var lastNotifyMs = 0L

    fun doWork(): Result {
        val slug = runSlug

        val zip = stage.zipFor(slug)
        if (!fs.exists(zip)) return fail("zip missing for $slug")

        val gpkg = stage.gpkgFor(slug)
        val skinny = stage.skinnyFor(slug)
        val part = (skinny.toString() + ".part").toPath()

        val layers = catalog.enabledLayers()
        if (layers.isEmpty()) return fail("no enabled layers in catalog")

        val ordered = layers.sortedBy { if (it.isPoint) 0 else 1 }

        seedItems(ordered)

        var db: SQLiteConnection? = null
        var src: SQLiteConnection? = null
        try {
            setForegroundSafely("Preparing")

            // -- 1. free space ------------------------------------------
            val uncompressed = uncompressedSize(zip)
            if (uncompressed <= 0L) return fail("cannot read uncompressed size from zip")
            val needed = uncompressed * SPACE_HEADROOM_NUM / SPACE_HEADROOM_DEN
            val free = plat.usableSpace(stage.dirFor(slug))
            log.i { "space: need ${mb(needed)} MB, free ${mb(free)} MB (uncompressed ${mb(uncompressed)} MB)" }
            if (free < needed) {
                return fail("not enough space: need ${mb(needed)} MB, ${mb(free)} MB free")
            }

            // -- 2. unzip ------------------------------------------------
            deleteQuiet(part)
            deleteQuiet(gpkg)
            if (!unzipGpkg(zip, gpkg, uncompressed)) return fail("unzip failed")
            complete(GtOsmProgress.ID_UNZIP)

            // -- 3. open both databases ----------------------------------
            src = gtOpenDatabase(gpkg.toString(), true)
            db = gtOpenDatabase(part.toString(), false)
            query1(db, "PRAGMA journal_mode=OFF")
            exec(db, "PRAGMA synchronous=OFF", emptyList())

            var trailRows = 0
            var badTotal = 0
            var candidateTotal = 0
            val classes = mutableListOf<String>()

            for (layer in ordered) {
                val res = runLayer(src, db, layer, slug) ?: return fail("layer '${layer.id}' aborted -- see log")
                candidateTotal += res.candidates
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

            val renamed = try { fs.atomicMove(part, skinny); true } catch (_: Exception) { false }
            if (!renamed) {
                deleteQuiet(part)
                return fail("atomic rename failed")
            }
            deleteQuiet(gpkg)

            // OSM-ZIPDEL-2026-07-30: the browser's copies of this state's zip -- guarded: never fails the extract.
            try {
                plat.sweepDownloadedZips(slug)
            } catch (e: Exception) {
                log.w { "download sweep skipped: ${e::class.simpleName}" }
            }

            ledger.recordExtract(
                slug = slug, gpkgBytes = uncompressed, classes = classes, candidateRows = candidateTotal,
                kept = trailRows, badGeometry = badTotal, skinnyRows = skinnyRows(skinny, ordered.map { it.targetTable })   // STEP15G-FIXA-2026-11-10 D16
            )
            publish(force = true)
            log.i { "extract complete: $slug trails=$trailRows skinny=${mb(fs.metadataOrNull(skinny)?.size ?: 0L)} MB" }
            return Result.Success
        } catch (e: Exception) {
            log.e { "extract failed: ${e::class.simpleName} ${e.message}" }
            return fail("${javaName(e)}: ${e.message}")
        } finally {
            try { db?.close() } catch (_: Exception) { }
            try { src?.close() } catch (_: Exception) { }
            if (fs.exists(part)) deleteQuiet(part)
            if (fs.exists(gpkg)) deleteQuiet(gpkg)
        }
    }

    // -- progress -----------------------------------------------------------

    private fun seedItems(layers: List<GtOsmLayers.Layer>) {
        items[GtOsmProgress.ID_UNZIP] = GtOsmProgress.Item(GtOsmProgress.ID_UNZIP, "Unzip", 0, 0, false, -1)
        for (l in layers) items[l.id] = GtOsmProgress.Item(l.id, l.label, 0, 0, false, -1)
    }

    private fun startType(id: String, total: Long) {
        typeStartMs = env.clock.epochMs()
        items[id]?.let { items[id] = it.copy(done = 0, total = total, etaSec = -1) }
        publish(force = true)
    }

    private fun advance(id: String, done: Long) {
        val cur = items[id] ?: return
        val now = env.clock.epochMs()
        val elapsed = now - typeStartMs
        val eta = if (done > 0L && elapsed > 2_000L && cur.total > 0L) {
            val perUnit = elapsed.toDouble() / done.toDouble()
            (((cur.total - done).toDouble() * perUnit) / 1000.0).toLong().coerceAtLeast(0L)
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

    // -- unzip --------------------------------------------------------------

    /** The FIRST .gpkg entry (not a directory) in central-directory order, as ZipFile.entries().firstOrNull. */
    private fun gpkgEntry(zip: Path): String? =
        GtImportConverter.zipEntryNames(fs, zip).firstOrNull { !it.endsWith("/") && it.endsWith(".gpkg", true) }

    private fun uncompressedSize(zip: Path): Long = try {
        val e = gpkgEntry(zip)
        if (e == null) -1L else fs.openZip(zip).metadata(("/" + e).toPath()).size ?: -1L
    } catch (e: Exception) {
        log.e { "cannot read zip: ${e::class.simpleName} ${e.message}" }
        -1L
    }

    private fun unzipGpkg(zip: Path, out: Path, total: Long): Boolean {
        startType(GtOsmProgress.ID_UNZIP, total)
        return try {
            val entry = gpkgEntry(zip) ?: return false
            val zfs = fs.openZip(zip)
            zfs.source(("/" + entry).toPath()).buffer().use { ins ->
                fs.sink(out).buffer().use { outs ->
                    val buf = ByteArray(UNZIP_BUF)
                    var written = 0L
                    while (true) {
                        if (isStopped) return false
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        written += n
                        advance(GtOsmProgress.ID_UNZIP, written)
                    }
                    outs.flush()
                }
            }
            true
        } catch (e: Exception) {
            log.e { "unzip failed: ${e::class.simpleName} ${e.message}" }
            false
        }
    }

    // -- passes -------------------------------------------------------------

    private data class LayerResult(val candidates: Int, val kept: Int, val badGeometry: Int, val droppedNoName: Int)

    private fun runLayer(src: SQLiteConnection, dst: SQLiteConnection, layer: GtOsmLayers.Layer, slug: String): LayerResult? {
        val types = sourceColumnTypes(src, layer.sourceTable)
        if (types.isEmpty()) {
            log.e { "source table missing or empty schema: ${layer.sourceTable}" }
            return null
        }
        val geomCol = when {
            types.containsKey("geom") -> "geom"
            types.containsKey("geometry") -> "geometry"
            else -> {
                log.e { "no geom column on ${layer.sourceTable}" }
                return null
            }
        }
        for (c in layer.columns) {
            if (!types.containsKey(c)) {
                log.e { "column '$c' not on ${layer.sourceTable} -- catalog is wrong" }
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

        val countSql = "SELECT COUNT(*) FROM ${layer.sourceTable}" + if (where.isNotEmpty()) " WHERE $where" else ""
        var candidates = 0
        rawQuery(src, countSql, args) { c -> if (c.step()) candidates = c.getLong(0).toInt() }
        log.i { "layer ${layer.id}: $candidates candidate rows from ${layer.sourceTable}" }
        startType(layer.id, candidates.toLong())

        createTarget(dst, layer, types)

        val extent = if (layer.isPoint) declaredExtent(src, layer.sourceTable) else null

        val cols = layer.columns.joinToString(",")
        val baseSql = "SELECT fid,$cols,$geomCol FROM ${layer.sourceTable} WHERE fid > ?" +
            (if (where.isNotEmpty()) " AND $where" else "") + " ORDER BY fid LIMIT $BATCH_ROWS"

        var lastFid = 0L
        var kept = 0
        var badGeom = 0
        var noName = 0
        var seen = 0
        var classified = 0
        var excluded = 0
        val tagDb: SQLiteConnection? = if (layer.targetTable == "osm_trails") openTagDb(slug) else null
        if (layer.targetTable == "osm_trails" && tagDb == null) {
            log.w { "no tag table for ${layer.id} -- classifying on shape alone" }
        }
        try {
            while (true) {
                if (isStopped) return null
                val a = ArrayList<String>()
                a.add(lastFid.toString())
                a.addAll(args)
                var rowsThisPage = 0

                // 2.7e: dst.beginTransaction(); try { ...; setTransactionSuccessful() } finally { endTransaction() }
                val tx = Tx(dst)
                tx.begin()
                try {
                    var abortEarly = false
                    rawQuery(src, baseSql, a) { c ->
                        while (c.step()) {
                            rowsThisPage++
                            lastFid = c.getLong(0)
                            val bi = layer.columns.size + 1
                            val blob = if (c.isNull(bi)) null else c.getBlob(bi)
                            val vals = ArrayList<Any?>(layer.columns.size + 8)
                            for (i in layer.columns.indices) vals.add(GtRead.cellText(c, i + 1))
                            if (layer.isPoint) {
                                val p = GtGpkgDecoder.decodePoint(blob)
                                if (p == null) { badGeom++; continue }
                                if (extent != null && !GtGpkgDecoder.withinExtent(p, extent[0], extent[1], extent[2], extent[3])) {
                                    log.e { "EXTENT VIOLATION in ${layer.id}: lon=${p.lon} lat=${p.lat} outside " +
                                        "W${extent[0]} S${extent[1]} E${extent[2]} N${extent[3]} -- envelope offset is wrong, aborting before trails" }
                                    tx.end()          // 2.7e: dst.endTransaction() here ...
                                    abortEarly = true
                                    break
                                }
                                val nameIdx = layer.columns.indexOf("name")
                                if (nameIdx >= 0) {
                                    val n = vals[nameIdx] as? String
                                    if (n.isNullOrBlank()) { noName++; continue }
                                }
                                vals.add(p.lat)
                                vals.add(p.lon)
                            } else {
                                val g = GtGpkgDecoder.decodeLine(blob)
                                if (g == null) { badGeom++; continue }
                                vals.add(g.wkt)
                                vals.add(g.geomHash)
                                vals.add(g.minLat); vals.add(g.maxLat)
                                vals.add(g.minLon); vals.add(g.maxLon)
                                if (layer.targetTable == "osm_trails") {
                                    val oid = (vals.getOrNull(0) as? String)?.toLongOrNull()
                                    val r = classifyRow(tagDb, oid)
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
                    if (abortEarly) return null   // ... and return null -- the finally's second endTransaction throws (below)
                    tx.successful()
                } finally {
                    tx.end()   // 2.7e's endTransaction: a second call (after the extent abort) throws IllegalStateException, as Android
                }

                seen += rowsThisPage
                advance(layer.id, seen.toLong())
                if (rowsThisPage < BATCH_ROWS) break
            }

            createIndexes(dst, layer)
            log.i { "layer ${layer.id}: kept=$kept badGeom=$badGeom noName=$noName classified=$classified excluded=$excluded of $candidates" }
            return LayerResult(candidates, kept, badGeom, noName)
        } finally {
            try { tagDb?.close() } catch (_: Exception) {}
        }
    }

    /** SQLiteDatabase's transaction calls on one connection: endTransaction with none open throws as Android's does. */
    private class Tx(private val c: SQLiteConnection) {
        private var open = false
        private var ok = false
        fun begin() { exec(c, "BEGIN", emptyList()); open = true; ok = false }
        fun successful() { ok = true }
        fun end() {
            if (!open) throw IllegalStateException("Cannot perform this operation because there is no current transaction.")
            open = false
            exec(c, if (ok) "COMMIT" else "ROLLBACK", emptyList())
        }
    }

    private fun openTagDb(slug: String): SQLiteConnection? {
        return try {
            val f = stage.tagsFor(slug)
            if (!stage.verifyTags(f)) return null
            gtOpenDatabase(f.toString(), true)
        } catch (e: Exception) {
            log.w { "tag table would not open: ${e.message}" }
            null
        }
    }

    private fun classifyRow(tagDb: SQLiteConnection?, osmId: Long?): GtOsmClassifier.Result {
        if (tagDb == null || osmId == null) return GtOsmClassifier.Result(GtOsmClassifier.UNCLASSIFIED_UNKNOWN, null, true)
        return try {
            var r: GtOsmClassifier.Result? = null
            rawQuery(tagDb, "SELECT highway,surface,tracktype,access,motor_vehicle,vehicle,four_wd_only,ohv,atv,motorcar,foot,bicycle " +
                "FROM ${GtPbfTags.TABLE} WHERE osm_id=?", listOf(osmId.toString())) { c ->
                r = if (!c.step()) {
                    GtOsmClassifier.Result(GtOsmClassifier.UNCLASSIFIED_UNKNOWN, null, true)
                } else {
                    fun s(i: Int) = GtRead.cellText(c, i)
                    GtOsmClassifier.classify(
                        highway = s(0), surface = s(1), tracktype = s(2),
                        access = s(3), motorVehicle = s(4), vehicle = s(5),
                        fourWdOnly = s(6), ohv = s(7), atv = s(8),
                        motorcar = s(9), foot = s(10), bicycle = s(11),
                    )
                }
            }
            r!!
        } catch (e: Exception) {
            log.w { "tag lookup failed for $osmId: ${e.message}" }
            GtOsmClassifier.Result(GtOsmClassifier.UNCLASSIFIED_UNKNOWN, null, true)
        }
    }

    private fun sourceColumnTypes(db: SQLiteConnection, table: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            rawQuery(db, "PRAGMA table_info(\"" + table + "\")", emptyList()) { c ->
                val iName = c.getColumnNames().indexOf("name")
                val iType = c.getColumnNames().indexOf("type")
                while (c.step()) {
                    out[GtRead.cellText(c, iName)!!] = GtRead.cellText(c, iType) ?: "TEXT"
                }
            }
        } catch (e: Exception) {
            log.e { "PRAGMA failed for $table: ${e.message}" }
        }
        return out
    }

    private fun declaredExtent(db: SQLiteConnection, table: String): DoubleArray? = try {
        var r: DoubleArray? = null
        rawQuery(db, "SELECT min_x,min_y,max_x,max_y FROM gpkg_contents WHERE table_name=?", listOf(table)) { c ->
            if (c.step() && !c.isNull(0)) r = doubleArrayOf(c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3))
        }
        r
    } catch (e: Exception) {
        log.w { "no declared extent for $table: ${e.message}" }
        null
    }

    private fun createTarget(db: SQLiteConnection, layer: GtOsmLayers.Layer, types: Map<String, String>) {
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
            if (layer.targetTable == "osm_trails") {
                defs.add("trail_type TEXT")
                defs.add("carto_code TEXT")
                defs.add("motorized INTEGER")
            }
        }
        exec(db, "DROP TABLE IF EXISTS " + layer.targetTable, emptyList())
        exec(db, "CREATE TABLE " + layer.targetTable + " (" + defs.joinToString(",") + ")", emptyList())
    }

    /** 2.7e's compileStatement binding: null / Double / Long, and toString() TEXT for everything else (an Int included). */
    private fun insertRow(db: SQLiteConnection, layer: GtOsmLayers.Layer, vals: List<Any?>) {
        val sb = StringBuilder("INSERT INTO ").append(layer.targetTable).append(" VALUES (")
        for (i in vals.indices) { if (i > 0) sb.append(','); sb.append('?') }
        sb.append(')')
        val st = db.prepare(sb.toString())
        try {
            for (i in vals.indices) {
                when (val v = vals[i]) {
                    null -> st.bindNull(i + 1)
                    is Double -> st.bindDouble(i + 1, v)
                    is Long -> st.bindLong(i + 1, v)
                    else -> st.bindText(i + 1, v.toString())
                }
            }
            st.step()
        } finally { st.close() }
    }

    private fun createIndexes(db: SQLiteConnection, layer: GtOsmLayers.Layer) {
        val t = layer.targetTable
        try {
            if (layer.isPoint) {
                exec(db, "CREATE INDEX IF NOT EXISTS ix_${t}_ll ON $t(lat,lon)", emptyList())
                if (layer.columns.contains("name")) exec(db, "CREATE INDEX IF NOT EXISTS ix_${t}_name ON $t(name)", emptyList())
            } else {
                exec(db, "CREATE INDEX IF NOT EXISTS ix_${t}_hash ON $t(geom_hash)", emptyList())
                exec(db, "CREATE INDEX IF NOT EXISTS ix_${t}_bbox ON $t(min_lat,max_lat,min_lon,max_lon)", emptyList())
                if (layer.columns.contains("name")) exec(db, "CREATE INDEX IF NOT EXISTS ix_${t}_name ON $t(name)", emptyList())
            }
        } catch (e: Exception) {
            log.w { "index creation failed on $t: ${e.message}" }
        }
    }

    /** ⚠⚠ feature_count IS THE osm_trails COUNT ONLY (verifySkinny compares it). The attribution row carries ODbL provenance. */
    private fun writeMeta(db: SQLiteConnection, sourceFile: String, classes: List<String>, trailRows: Int) {
        exec(db, "DROP TABLE IF EXISTS subset_meta", emptyList())
        exec(db, "CREATE TABLE subset_meta (key TEXT PRIMARY KEY, value TEXT)", emptyList())
        val meta = listOf(
            "source_file" to sourceFile,
            "classes" to classes.joinToString(","),
            "feature_count" to trailRows.toString(),
            "built_at" to GtTime.local(env.clock.localNow(), "yyyy-MM-dd'T'HH:mm:ss"),
            "wkt_format" to "app-exact: comma-no-space, Kotlin Double.toString()",
            "hash_algo" to "SHA-256(wkt utf-8) hex lowercase",
            "attribution" to "© OpenStreetMap contributors (ODbL)"
        )
        for ((k, v) in meta) exec(db, "INSERT INTO subset_meta VALUES (?,?)", listOf(k, v))
    }

    // -- plumbing -----------------------------------------------------------

    /** STEP15G-FIXA-2026-11-10 D16 (Fred 11-10): the published skinny's rows over [tables], counted by SQL -- total rows, not the file size
     *  (the size differs by platform and carries deleted-row holes). Any failure (a table missing) -> -1. */
    private fun skinnyRows(f: Path, tables: List<String>): Long = try {
        val c = gtOpenDatabase(f.toString(), true)
        try { var n = 0L; for (t in tables) rawQuery(c, "SELECT COUNT(*) FROM " + t, emptyList()) { st -> if (st.step()) n += st.getLong(0) }; n } finally { c.close() }
    } catch (e: Exception) { -1L }

    private fun mb(bytes: Long): Long = bytes / 1_048_576L

    private fun fail(msg: String): Result {
        log.e { "FAIL: $msg" }
        return Result.Failure(msg)
    }

    private fun deleteQuiet(p: Path) { try { fs.delete(p) } catch (_: Exception) {} }

    private fun setForegroundSafely(text: String) {
        try {
            plat.foreground("extract", text)
        } catch (e: Exception) {
            log.w { "foreground unavailable: ${e::class.simpleName} ${e.message}" }
        }
    }

    companion object {
        private const val TAG = "OsmExtract"
        private const val BATCH_ROWS = 5_000
        private const val UNZIP_BUF = 1 shl 16
        private const val SPACE_HEADROOM_NUM = 5L
        private const val SPACE_HEADROOM_DEN = 4L

        /** e.javaClass.simpleName as the failure text carries it (the shared exception's own class name). */
        internal fun javaName(e: Throwable): String = e::class.simpleName ?: "Exception"

        /** Android execSQL(sql, args) binding (DatabaseUtils.bindObjectToProgram): null, Double / Float, whole numbers, else TEXT. */
        internal fun exec(c: SQLiteConnection, sql: String, args: List<Any?>) {
            val st = c.prepare(sql)
            try {
                args.forEachIndexed { i, a ->
                    when (a) {
                        null -> st.bindNull(i + 1)
                        is Double -> st.bindDouble(i + 1, a)
                        is Float -> st.bindDouble(i + 1, a.toDouble())
                        is Long -> st.bindLong(i + 1, a)
                        is Int -> st.bindLong(i + 1, a.toLong())
                        is Short -> st.bindLong(i + 1, a.toLong())
                        is Byte -> st.bindLong(i + 1, a.toLong())
                        is Boolean -> st.bindLong(i + 1, if (a) 1L else 0L)
                        is ByteArray -> st.bindBlob(i + 1, a)
                        else -> st.bindText(i + 1, a.toString())
                    }
                }
                st.step()
            } finally { st.close() }
        }

        /** rawQuery(sql, String[] args): every argument bound as TEXT; [body] steps the cursor. */
        internal fun rawQuery(c: SQLiteConnection, sql: String, args: List<String>, body: (SQLiteStatement) -> Unit) {
            val st = c.prepare(sql)
            try {
                args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
                body(st)
            } finally { st.close() }
        }

        /** A query statement run for its row (2.7e's rawQuery("PRAGMA journal_mode=OFF").use { moveToFirst() }). */
        internal fun query1(c: SQLiteConnection, sql: String) { val st = c.prepare(sql); try { st.step() } finally { st.close() } }
    }
}
