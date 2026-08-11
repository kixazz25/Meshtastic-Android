package com.geeksville.mesh.convoy

import android.os.Environment
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * MBTilesStore — V2.6 Pass 1 tile storage.
 *
 * Replaces loose-file tile storage (<TILE_DIR>/<type>/<z>/<x>/<y>.png)
 * with one MBTiles SQLite database PER TYPE:
 *     <TILE_DIR>/<type>.mbtiles
 * where <type> is the old cache_dir / directory name
 * (SAT, SAT_LABELS_TRANSPORT, SAT_LABELS_PLACES, TOPO, TOPO+).
 *
 * COORDINATE CONTRACT — CRITICAL:
 *   Stored RAW z/x/y, NO TMS y-flip. This exactly replicates the loose-file
 *   scheme (<type>/<z>/<x>/<y>.png) so every existing read path resolves the
 *   same tile with the same key. The downloader wrote raw tile.y; both
 *   intercepts read raw y (they only reorder Esri's z/y/x URL back to z/x/y).
 *   insertTile and readTile therefore both use raw z/x/y. Do NOT introduce a
 *   flip here — it would silently blank offline tiles.
 *
 * Schema (canonical MBTiles):
 *   tiles(zoom_level, tile_column, tile_row, tile_data)
 *   metadata(name, value)
 *   UNIQUE INDEX tile_index ON tiles(zoom_level, tile_column, tile_row)   [mandatory]
 *
 * Storage: Documents/GroupTrack/maps/tiles/  (same TILE_DIR the loose tree used;
 * survives reinstall). One open handle cached per type.
 *
 * Mirrors SpatialDbManager conventions: object singleton, openOrCreateDatabase,
 * execSQL with bound args, cached handles, best-effort try/catch + Log tags.
 */
object MBTilesStore {

    private const val TAG = "MBTilesStore"

    // One cached open DB handle per type (= per old cache_dir).
    private val handles = HashMap<String, SQLiteDatabase>()

    /** The tiles root — identical to ConvoyConfig.TILE_DIR
     *  (Documents/GroupTrack/maps/tiles). */
    private fun tilesDir(): File {
        val dir = ConvoyConfig.TILE_DIR
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Resolve a type to its .mbtiles file. TOPO+ keeps its '+' verbatim —
     *  it's a filename, not a URL segment here, so no encoding needed. */
    private fun dbFileFor(type: String): File = File(tilesDir(), "$type.mbtiles")

    /** Open-or-create the DB for a type, create canonical schema on first use,
     *  and cache the handle. Returns null only on hard failure. */
    @Synchronized
    private fun db(type: String): SQLiteDatabase? {
        handles[type]?.let { if (it.isOpen) return it else handles.remove(type) }
        return try {
            val f = dbFileFor(type)
            val fresh = !f.exists()
            val d = SQLiteDatabase.openOrCreateDatabase(f, null)
            if (fresh) initSchema(d, type)
            // Guard: an existing file might predate the index (defensive).
            ensureSchema(d)
            handles[type] = d
            d
        } catch (e: Exception) {
            android.util.Log.e(TAG, "open failed for $type: ${e.message}")
            null
        }
    }

    private fun initSchema(d: SQLiteDatabase, type: String) {
        ensureSchema(d)
        // Minimal MBTiles metadata. format is the dominant tile format for the
        // type; overlays are WebP (alpha), bases are png/jpg — recorded for the
        // reader's content-type. bounds/min-max are optional and left for a
        // later pass; name identifies the tileset.
        try {
            val format = "webp"   // [V2.6a-WEBP] all tiles stored WebP (base lossy, overlay lossless)
            d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('name',?)", arrayOf<Any>(type))
            d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('format',?)", arrayOf<Any>(format))
            d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('scheme',?)", arrayOf<Any>("xyz"))
            android.util.Log.i(TAG, "created $type.mbtiles (format=$format, scheme=xyz raw z/x/y)")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "metadata init $type: ${e.message}")
        }
    }

    private fun ensureSchema(d: SQLiteDatabase) {
        // MBPRAGMA-2026-08-09D: per-connection pragmas, set on EVERY open.
        //
        // No pragmas were set anywhere in this file before this, so every
        // store ran on Android defaults.
        //
        // secure_delete: Android ships it ON, which overwrites freed content
        // with zeros instead of marking pages free. Tiles average ~23 KB, so
        // a 21,852-tile delete wrote ~500 MB of zeros -- measured 08-09 at
        // ~2.6 MB/s, which is what made the corridor delete ~98 tiles/sec.
        //
        // synchronous NORMAL: the default forces an fsync on every commit.
        // SQLite runs millions of statements per second, so statement volume
        // alone cannot explain the measured time -- commit cost can. NORMAL
        // keeps the rollback journal, so a crash still rolls back cleanly and
        // a rolled-back delete is simply re-run.
        //
        // All three are PER-CONNECTION: none is persisted in the file,
        // nothing already written is touched, and there is no migration.
        // That is why this needs no upgrade path for existing stores.
        //
        // *** DO NOT SWITCH THIS TO A NON-DURABLE JOURNAL SETTING. *** The
        // two modes that skip the rollback journal entirely leave the file in
        // an UNDEFINED STATE if a write is interrupted -- not a partial
        // delete you can re-run, a potentially corrupt database. And the
        // write-ahead option is PERSISTENT: it changes the file format and
        // adds companion files, so it WOULD need a real migration, and
        // SQLite documents it as performing badly above ~100 MB per
        // transaction and failing outright above a gigabyte -- which a
        // 600k-tile delete exceeds. These stores are ~14 GB with NO BACKUP,
        // on storage that already logs SELinux denials, and a database the
        // framework considers corrupt can be DELETED by the default error
        // handler. Crash-safety is not tradeable here for delete speed.
        // MBPRAGMA-2026-08-09G: every pragma goes through rawQuery, one try each.
        //
        // The previous attempt used execSQL for two of the three and set
        // NOTHING on device: Android refuses any statement that returns a
        // row, and these return their new value. The first call threw and
        // took the other two down with it, including the one already written
        // correctly. Separate try blocks so a single failure cannot silence
        // the rest, and each logs what SQLite actually reports back.
        for (p in listOf("secure_delete=OFF", "synchronous=NORMAL",
                         "journal_mode=TRUNCATE")) {
            try {
                d.rawQuery("PRAGMA $p", null).use { c ->
                    val got = if (c.moveToFirst()) c.getString(0) else "(no row)"
                    android.util.Log.i(TAG, "MBPRAGMA-2026-08-09G: $p -> $got")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "MBPRAGMA-2026-08-09G: $p FAILED: ${e.message}")
            }
        }
        d.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        d.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles(zoom_level, tile_column, tile_row)")
    }

    /**
     * Insert (or replace) one tile. RAW z/x/y — no flip. type = old cache_dir.
     * REPLACE keeps the resume/overwrite semantics simple (last write wins),
     * matching the old dest.writeBytes overwrite behavior.
     * Returns true on success.
     */
    fun insertTile(type: String, z: Int, x: Int, y: Int, bytes: ByteArray, isOverlay: Boolean = false): Boolean {
        val d = db(type) ?: return false
        val stored = TileCodec.encode(bytes, isOverlay)   // [V2.6a-WEBP] lossy base / lossless overlay
        return try {
            d.execSQL(
                "INSERT OR REPLACE INTO tiles(zoom_level,tile_column,tile_row,tile_data) VALUES(?,?,?,?)",
                arrayOf<Any>(z, x, y, stored)
            )
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "insertTile $type z$z/$x/$y: ${e.message}")
            false
        }
    }

    /**
     * Read one tile's bytes, or null if absent. RAW z/x/y — same key as insert.
     * This is the read contract every shouldInterceptRequest branch calls.
     */
    fun readTile(type: String, z: Int, x: Int, y: Int): ByteArray? {
        val d = db(type) ?: return null
        return try {
            d.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                arrayOf(z.toString(), x.toString(), y.toString())
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "readTile $type z$z/$x/$y: ${e.message}")
            null
        }
    }

    /** True if a tile exists (resume-skip check; replaces dest.exists()). */
    fun hasTile(type: String, z: Int, x: Int, y: Int): Boolean {
        val d = db(type) ?: return false
        return try {
            d.rawQuery(
                "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                arrayOf(z.toString(), x.toString(), y.toString())
            ).use { it.moveToFirst() }
        } catch (e: Exception) { false }
    }

    /**
     * RECREATE-2026-08-11G: how many STORED tiles fall inside a tile-coordinate range at one
     * zoom. Indexed range scan on tile_index -- no blobs, so it stays cheap on a
     * multi-gigabyte store even when called hundreds of times by the quadtree.
     *
     * This is the whole basis of Recreate: never ask what a box COULD cover,
     * ask what it HAS.
     */
    fun countInTileRange(type: String, z: Int, xMin: Long, xMax: Long,
                         yMin: Long, yMax: Long): Int {
        val d = db(type) ?: return 0
        return try {
            d.rawQuery(
                "SELECT COUNT(*) FROM tiles WHERE zoom_level=? " +
                "AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                arrayOf(z.toString(), xMin.toString(), xMax.toString(),
                        yMin.toString(), yMax.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "RECREATE-2026-08-11G countInTileRange $type z$z: ${e.message}")
            0
        }
    }

    /**
     * RECREATE-2026-08-11G: the tile-coordinate extent actually occupied at one zoom, as
     * [xMin, xMax, yMin, yMax]. Null when the level holds nothing.
     *
     * MIN/MAX only, so this is the store's bounding box -- deliberately. It is
     * the STARTING box for the quadtree, which then discards whatever inside it
     * turns out to be empty. The extent lies about disjoint coverage; the
     * quadtree is what corrects for that.
     */
    fun tileExtentAtZoom(type: String, z: Int): LongArray? {
        val d = db(type) ?: return null
        return try {
            d.rawQuery(
                "SELECT MIN(tile_column), MAX(tile_column), " +
                "MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level=?",
                arrayOf(z.toString())
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0))
                    longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3))
                else null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "RECREATE-2026-08-11G tileExtentAtZoom $type z$z: ${e.message}")
            null
        }
    }

    /** Tile count + size (MB) for a type. Replaces sourceInfo's dir walk. */
    fun sourceInfo(type: String): Pair<Int, Float> {
        val d = db(type) ?: return Pair(0, 0f)
        return try {
            var count = 0
            d.rawQuery("SELECT COUNT(*) FROM tiles", null).use { if (it.moveToFirst()) count = it.getInt(0) }
            val bytes = dbFileFor(type).length()
            Pair(count, bytes / (1024f * 1024f))
        } catch (e: Exception) { Pair(0, 0f) }
    }

    /** All (z,x,y) keys for a type. Replaces scanTilesToKeys' dir walk. */
    fun scanKeys(type: String): List<TileKey> {
        val d = db(type) ?: return emptyList()
        val keys = ArrayList<TileKey>()
        try {
            d.rawQuery("SELECT zoom_level, tile_column, tile_row FROM tiles", null).use { c ->
                while (c.moveToNext()) keys.add(TileKey(c.getInt(0), c.getInt(1), c.getInt(2)))
            }
        } catch (e: Exception) { android.util.Log.e(TAG, "scanKeys $type: ${e.message}") }
        android.util.Log.i(TAG, "scanKeys $type: ${keys.size} tiles")
        return keys
    }

    // [V2.6-PASS1-S4] (x,y) tile columns/rows present at a zoom for a type.
    // Feeds the coverage-highlight walks (replaces listFiles on <type>/<z>).
    /**
     * RECREATE-2026-08-11A: which zoom levels actually hold tiles for this store.
     *
     * The whole point of Recreate-by-Source: never ask what a bbox COULD cover,
     * ask what the store HAS. A level with no rows is simply absent from this
     * list and never becomes work.
     */
    fun zoomLevelsPresent(type: String): List<Int> {
        val d = db(type) ?: return emptyList()
        val out = ArrayList<Int>()
        try {
            d.rawQuery("SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level", null).use { c ->
                while (c.moveToNext()) out.add(c.getInt(0))
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "RECREATE-2026-08-11A zoomLevelsPresent $type: ${e.message}")
        }
        return out
    }

    /**
     * RECREATE-2026-08-11A: how many tiles exist at one zoom. COUNT only -- no rows
     * materialised, no blobs touched, so this is cheap even on a 15 GB store.
     */
    fun countAtZoom(type: String, z: Int): Int {
        val d = db(type) ?: return 0
        try {
            d.rawQuery("SELECT COUNT(*) FROM tiles WHERE zoom_level=?",
                arrayOf(z.toString())).use { c ->
                if (c.moveToFirst()) return c.getInt(0)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "RECREATE-2026-08-11A countAtZoom $type z$z: ${e.message}")
        }
        return 0
    }

    fun xyAtZoom(type: String, z: Int): List<Pair<Long, Long>> {
        val d = db(type) ?: return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        try {
            d.rawQuery("SELECT tile_column, tile_row FROM tiles WHERE zoom_level=?", arrayOf(z.toString())).use { c ->
                while (c.moveToNext()) out.add(Pair(c.getLong(0), c.getLong(1)))
            }
        } catch (e: Exception) { android.util.Log.e(TAG, "xyAtZoom $type z$z: ${e.message}") }
        return out
    }

    /** Delete all tiles for a type — drops the whole .mbtiles file.
     *  Replaces deleteSource's deleteRecursively. */
    @Synchronized
    /** DELETE-AREA-2026-07-25: does a store file exist for this type?
     *  Checked before deleting so we do not CREATE an empty .mbtiles for a
     *  layer that was never downloaded -- db() opens-or-creates. */
    fun storeExists(type: String): Boolean = dbFileFor(type).exists()

    /** DELETE-AREA-2026-07-25: how many tiles sit inside this z/x/y rectangle.
     *  Indexed count via tile_index. Used to report what a delete actually
     *  removed, and available for an exact pre-count off the main thread. */
    fun countTileRange(type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int {
        if (!storeExists(type)) return 0
        val d = db(type) ?: return 0
        var n = 0
        try {
            d.rawQuery(
                "SELECT COUNT(*) FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                arrayOf(z.toString(), xMin.toString(), xMax.toString(), yMin.toString(), yMax.toString())
            ).use { c -> if (c.moveToFirst()) n = c.getInt(0) }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "countTileRange $type z$z: ${e.message}")
        }
        return n
    }

    /** DELETE-AREA-2026-07-25: remove every tile inside a z/x/y rectangle.
     *  Returns rows deleted.
     *
     *  ONE STATEMENT PER ZOOM, not one per tile. A bbox is a CONTIGUOUS
     *  RECTANGLE in x/y at every zoom, so BETWEEN is EXACTLY equivalent to
     *  enumerating the tiles -- not an approximation. ~18 statements replace
     *  ~50,000. (A CORRIDOR delete could not use this form: its tile set is
     *  a buffered line, not a rectangle.)
     *
     *  RAW z/x/y, NO FLIP -- same convention as insertTile/readTile. Using
     *  the TMS-flipped row here would silently delete THE WRONG TILES and
     *  look like a partial success.
     *
     *  NOTE: space is NOT returned to the filesystem by this. SQLite frees
     *  the pages inside the file; the .mbtiles keeps its size until a VACUUM.
     *  That is a separate, explicit action -- VACUUM needs roughly the file's
     *  own size in free space, so it cannot be fired blindly here. */
    fun deleteTileRange(type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int {
        if (!storeExists(type)) return 0
        val d = db(type) ?: return 0
        return try {
            d.delete(
                "tiles",
                "zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                arrayOf(z.toString(), xMin.toString(), xMax.toString(), yMin.toString(), yMax.toString())
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "deleteTileRange $type z$z: ${e.message}")
            0
        }
    }

    /**
     * MBTILESDELTILES-2026-08-06
     *
     * Delete an EXPLICIT LIST of tiles. The primitive a corridor delete needs.
     *
     * WHY IT HAD TO EXIST: this store could previously delete only a RANGE
     * (deleteTileRange, min/max x and y) or a WHOLE SOURCE (deleteSource).
     * A corridor is neither. Deleting a corridor by range would remove its
     * BOUNDING BOX - the same hull that makes an area refresh over corridors
     * wrong, except inverted: instead of downloading ground the user never
     * had, it would delete ground the user still wants.
     *
     * Grouped by (z, x) and deleted with tile_row IN (...). A corridor is a
     * thin strip, so most (z, x) pairs hold a short run of consecutive rows -
     * this collapses tens of thousands of statements into a few hundred.
     * Chunked at 500 binds; SQLite allows 999 and the query already uses two.
     *
     * One transaction for the whole call - a half-deleted corridor is not a
     * state worth leaving behind, and it is markedly faster.
     *
     * Returns the number of rows actually removed, which is what the worker
     * should report: the caller's tile list is what COULD be there, not what
     * WAS. (Same distinction the DELETE-BANDING-2026-07-25 note draws.)
     */
    /** CORRDELETE-ONDISK-2026-08-07D: how many of these EXACT tiles are on
     *  disk. Deliberately mirrors deleteTiles() below -- same grouping, same
     *  500-bind chunking, same WHERE shape -- because a pre-count that selects
     *  on different criteria than the delete is worse than no pre-count: it
     *  reads as authoritative while disagreeing with what actually happens.
     *
     *  countTileRange() cannot serve here. It takes min/max x and y, which is a
     *  BOUNDING BOX, and a corridor is not one -- the box around a corridor
     *  includes ground the corridor never touches.
     *
     *  Indexed COUNT(*) per (z, x) group. Read-only, no transaction, safe to
     *  run before the user has confirmed anything. Background thread only. */
    fun countTiles(type: String, tiles: List<TileKey>): Int {
        if (tiles.isEmpty()) return 0
        if (!storeExists(type)) return 0
        val d = db(type) ?: return 0
        var found = 0
        return try {
            for ((key, group) in tiles.groupBy { it.z to it.x }) {
                val (z, x) = key
                val rows = group.map { it.y }
                var i = 0
                while (i < rows.size) {
                    val chunk = rows.subList(i, minOf(i + 500, rows.size))
                    val placeholders = chunk.joinToString(",") { "?" }
                    val args = ArrayList<String>(chunk.size + 2)
                    args.add(z.toString())
                    args.add(x.toString())
                    for (y in chunk) args.add(y.toString())
                    d.rawQuery(
                        "SELECT COUNT(*) FROM tiles " +
                            "WHERE zoom_level=? AND tile_column=? AND tile_row IN ($placeholders)",
                        args.toTypedArray()
                    ).use { c -> if (c.moveToFirst()) found += c.getInt(0) }
                    i += chunk.size
                }
            }
            android.util.Log.i(TAG,
                "countTiles $type: ${tiles.size} requested, $found present")
            found
        } catch (e: Exception) {
            android.util.Log.e(TAG, "countTiles $type: ${e.message}")
            found
        }
    }

    fun deleteTiles(type: String, tiles: List<TileKey>): Int {
        if (tiles.isEmpty()) return 0
        if (!storeExists(type)) return 0
        val d = db(type) ?: return 0
        var removed = 0
        return try {
            d.beginTransaction()
            try {
                for ((key, group) in tiles.groupBy { it.z to it.x }) {
                    val (z, x) = key
                    val rows = group.map { it.y }
                    var i = 0
                    while (i < rows.size) {
                        val chunk = rows.subList(i, minOf(i + 500, rows.size))
                        val placeholders = chunk.joinToString(",") { "?" }
                        val args = ArrayList<String>(chunk.size + 2)
                        args.add(z.toString())
                        args.add(x.toString())
                        for (y in chunk) args.add(y.toString())
                        removed += d.delete(
                            "tiles",
                            "zoom_level=? AND tile_column=? AND tile_row IN ($placeholders)",
                            args.toTypedArray()
                        )
                        i += chunk.size
                    }
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            android.util.Log.i(TAG,
                "deleteTiles $type: ${tiles.size} requested, $removed actually removed")
            removed
        } catch (e: Exception) {
            android.util.Log.e(TAG, "deleteTiles $type: ${e.message}")
            removed
        }
    }
    /** DELETE-AREA-2026-07-25: reclaimable bytes sitting in this store's
     *  freelist. freelist_count * page_size. Lets the UI say "delete freed
     *  1.2 GB -- reclaim it?" instead of a blind "compacting...". */
    fun reclaimableBytes(type: String): Long {
        if (!storeExists(type)) return 0L
        val d = db(type) ?: return 0L
        var pages = 0L
        var pageSize = 0L
        try {
            d.rawQuery("PRAGMA freelist_count", null).use { c -> if (c.moveToFirst()) pages = c.getLong(0) }
            d.rawQuery("PRAGMA page_size", null).use { c -> if (c.moveToFirst()) pageSize = c.getLong(0) }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "reclaimableBytes $type: ${e.message}")
        }
        // DELETE-BANDING-2026-07-25: patch M reported "0 MB reclaimable" after
        // removing 38,896 tiles, which is not credible - those pages went
        // somewhere. Most likely WAL: freed pages may not appear in the
        // freelist until a checkpoint. Log the raw values so we know whether
        // the pragma returns zero or the arithmetic is wrong, BEFORE anyone
        // designs a VACUUM flow on top of a number that may be meaningless.
        android.util.Log.i(TAG,
            "reclaimable $type: freelist_count=$pages page_size=$pageSize -> ${pages * pageSize} bytes")
        return pages * pageSize
    }

    fun deleteSource(type: String): Boolean {
        handles.remove(type)?.let { try { it.close() } catch (_: Exception) {} }
        val f = dbFileFor(type)
        // also clear sqlite sidecars, mirroring SpatialDbManager's delete-gate care
        var ok = true
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            val s = File(f.parentFile, f.name + suffix)
            if (s.exists() && !s.delete()) ok = false
        }
        android.util.Log.i(TAG, "deleteSource $type = $ok")
        return ok
    }

    /** Close all open handles (app teardown). */
    @Synchronized
    fun closeAll() {
        for ((_, d) in handles) try { d.close() } catch (_: Exception) {}
        handles.clear()
    }
}
