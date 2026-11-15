package com.geeksville.mesh.convoy

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.mbtiles.GtMBTiles
import com.grouptrack.shared.mbtiles.GtTileStoreEnv
import com.grouptrack.shared.queue.GtTileKey
import java.io.File

/**
 * MBTilesStore -- V2.6 Pass 1 tile storage. One MBTiles SQLite database PER TYPE:
 *     <TILE_DIR>/<type>.mbtiles
 * where <type> is the old cache_dir / directory name (SAT, SAT_LABELS_TRANSPORT,
 * SAT_LABELS_PLACES, TOPO, TOPO+).
 *
 * GTSWITCH17MBTILES-2026-11-14 (2.8 step 17, Phase 2 switch 3 of 3)
 * ------------------------------------------------------------------------------------
 * THE EIGHTEEN LIVE MEMBERS NOW DELEGATE TO GtMBTiles, the certified shared port
 * (84 SAME / 0 DIFF / 0 EXPECTED, two clean runs, Droid and iPad). The object name,
 * every public signature and the TileKey types are unchanged, so NO CALL SITE MOVED.
 *
 * WHAT STAYED HERE, AND WHY -- this file is now the PLATFORM HALF the port excludes:
 *   - THE HANDLE CACHE. 2.7e held HashMap<String, SQLiteDatabase>; it is now
 *     HashMap<String, SQLiteConnection> on the shared driver. Handle lifetime and
 *     thread safety are platform state, not business logic, which is what keeps
 *     GtMBTiles stateless and certifiable.
 *   - OPEN-OR-CREATE, THE SCHEMA AND THE THREE SAFETY PRAGMAS (below).
 *   - TileCodec.encode. BitmapFactory and Bitmap.CompressFormat are Android-only and
 *     unportable, so insertTile encodes HERE and hands shared the resulting bytes.
 *     The port takes bytes and stores bytes, which is correct.
 *   - sourceInfo and closeAll. Both measured DEAD 11-14 (closeAll: zero call sites;
 *     sourceInfo: its only caller is ConvoyTileDownloader.sourceInfo, itself callerless).
 *     The port skips them (M2: skip, do not remove, until after the first iPad
 *     generation). They are reimplemented here over the connection cache so this
 *     file's public surface is unchanged.
 *
 * TWO KNOWN GAPS, RECORDED RATHER THAN FIXED -- a port is not the place to fix either:
 *   1. CERT GAP: insertTile's codec dispatch was never exercised. Both live slots are
 *      role "overlay", and TileCodec.encode returns overlay bytes unchanged, so the
 *      isOverlay=false branch is unreachable today. It was still a hole in the cert.
 *   2. PER-MEMBER ERROR LOGGING IS GONE from the data path. The shared port swallows
 *      exceptions and returns 2.7e's own fallbacks (null / 0 / emptyList / false)
 *      SILENTLY -- the exception never crosses the boundary, so there is nothing to
 *      log here. Return values are byte-identical to 2.7e; diagnostics are thinner.
 *      The informational logs that DID survive are kept below.
 *
 * COORDINATE CONTRACT -- CRITICAL, UNCHANGED:
 *   Stored RAW z/x/y, NO TMS y-flip. This replicates the loose-file scheme
 *   (<type>/<z>/<x>/<y>.png) so every existing read path resolves the same tile with
 *   the same key. Do NOT introduce a flip -- it would silently blank offline tiles.
 *
 * Schema (canonical MBTiles):
 *   tiles(zoom_level, tile_column, tile_row, tile_data)
 *   metadata(name, value)
 *   UNIQUE INDEX tile_index ON tiles(zoom_level, tile_column, tile_row)   [mandatory]
 */
object MBTilesStore : GtTileStoreEnv {

    private const val TAG = "MBTilesStore"

    /** The certified shared port. This object is its platform env. */
    private val shared: GtMBTiles by lazy { GtMBTiles(this) }

    /** One cached open connection per type (= per old cache_dir). Platform state. */
    private val handles = HashMap<String, SQLiteConnection>()

    // ---- mirrored-type mapping -----------------------------------------------------
    // NAMED ARGUMENTS on every mapping. TileKey(Int,Int,Int) and GtTileKey(Int,Int,Int)
    // are the same shape, so a positional swap would COMPILE and silently transpose x and y.
    private fun gt(k: TileKey) = GtTileKey(z = k.z, x = k.x, y = k.y)
    private fun old(k: GtTileKey) = TileKey(z = k.z, x = k.x, y = k.y)

    // ---- platform half: files, handles, schema, pragmas ----------------------------

    /** The tiles root -- identical to ConvoyConfig.TILE_DIR (Documents/GroupTrack/maps/tiles). */
    private fun tilesDir(): File {
        val dir = ConvoyConfig.TILE_DIR
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Resolve a type to its .mbtiles file. TOPO+ keeps its '+' verbatim -- it is a
     *  filename, not a URL segment here, so no encoding is needed. */
    private fun dbFileFor(type: String): File = File(tilesDir(), "$type.mbtiles")

    /**
     * 2.7e db(type): open-or-create, ensure the canonical schema, cache the connection.
     * Null only on hard failure.
     *
     * NOTE vs 2.7e: the cached-handle branch had an isOpen recheck. androidx's
     * SQLiteConnection exposes no isOpen, so the cached connection is returned as held;
     * it is closed only by deleteStore or closeAll, both of which drop it from the map.
     */
    @Synchronized
    override fun connection(type: String): SQLiteConnection? {
        handles[type]?.let { return it }
        return try {
            val f = dbFileFor(type)
            val fresh = !f.exists()
            val c = gtOpenDatabase(f.absolutePath, false)
            ensureSchema(c)
            if (fresh) initMetadata(c, type)
            handles[type] = c
            c
        } catch (e: Exception) {
            android.util.Log.e(TAG, "open failed for $type: ${e.message}")
            null
        }
    }

    /** 2.7e storeExists / dbFileFor(type).exists(): checked WITHOUT opening, so a check
     *  can never CREATE an empty .mbtiles for a layer that was never downloaded. */
    @Synchronized
    override fun storeExists(type: String): Boolean = dbFileFor(type).exists()

    /** 2.7e deleteSource's body: drop the handle, then the file AND its sidecars. */
    @Synchronized
    override fun deleteStore(type: String): Boolean {
        handles.remove(type)?.let { try { it.close() } catch (_: Exception) {} }
        val f = dbFileFor(type)
        // also clear sqlite sidecars, mirroring SpatialDbManager's delete-gate care
        var ok = true
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            val s = File(f.parentFile, f.name + suffix)
            if (s.exists() && !s.delete()) ok = false
        }
        return ok
    }

    private fun exec(c: SQLiteConnection, sql: String) {
        val st = c.prepare(sql); try { st.step() } finally { st.close() }
    }

    /**
     * MBPRAGMA-2026-08-09D/G: per-connection pragmas, set on EVERY open, ONE TRY EACH.
     *
     * secure_delete: Android ships it ON, which overwrites freed content with zeros
     * instead of marking pages free. Tiles average ~23 KB, so a 21,852-tile delete wrote
     * ~500 MB of zeros -- measured 08-09 at ~2.6 MB/s, which is what made the corridor
     * delete ~98 tiles/sec.
     *
     * synchronous NORMAL: the default forces an fsync on every commit. NORMAL keeps the
     * rollback journal, so a crash still rolls back cleanly and a rolled-back delete is
     * simply re-run.
     *
     * All three are PER-CONNECTION: none is persisted in the file, nothing already
     * written is touched, and there is no migration.
     *
     * *** DO NOT SWITCH THIS TO A NON-DURABLE JOURNAL SETTING. *** The modes that skip
     * the rollback journal leave the file UNDEFINED if a write is interrupted -- not a
     * partial delete you can re-run, a potentially corrupt database. The write-ahead
     * option is PERSISTENT (file format change, companion files, a real migration) and
     * SQLite documents it as failing above a gigabyte per transaction, which a 600k-tile
     * delete exceeds. These stores are ~14 GB with NO BACKUP, and a database the
     * framework considers corrupt can be DELETED by the default error handler.
     * Crash-safety is not tradeable for delete speed.
     *
     * ONE TRY EACH is not stylistic: the earlier attempt used execSQL for two of the
     * three and set NOTHING on device -- the first call threw and took the other two
     * down with it, including the one already written correctly.
     */
    private fun ensureSchema(c: SQLiteConnection) {
        for (p in listOf("secure_delete=OFF", "synchronous=NORMAL", "journal_mode=TRUNCATE")) {
            try { exec(c, "PRAGMA $p") } catch (e: Exception) {
                android.util.Log.w(TAG, "MBPRAGMA-2026-08-09G: $p FAILED: ${e.message}")
            }
        }
        exec(c, "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        exec(c, "CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        exec(c, "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles(zoom_level, tile_column, tile_row)")
    }

    /** 2.7e initSchema's metadata half. format is the dominant tile format for the type. */
    private fun initMetadata(c: SQLiteConnection, type: String) {
        try {
            val format = "webp"   // [V2.6a-WEBP] all tiles stored WebP (base lossy, overlay lossless)
            for ((k, v) in listOf("name" to type, "format" to format, "scheme" to "xyz")) {
                val st = c.prepare("INSERT OR REPLACE INTO metadata(name,value) VALUES(?,?)")
                try { st.bindText(1, k); st.bindText(2, v); st.step() } finally { st.close() }
            }
            android.util.Log.i(TAG, "created $type.mbtiles (format=$format, scheme=xyz raw z/x/y)")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "metadata init $type: ${e.message}")
        }
    }

    // ---- 1. writes -----------------------------------------------------------------

    /**
     * Insert (or replace) one tile. RAW z/x/y -- no flip. REPLACE keeps the
     * resume/overwrite semantics simple (last write wins).
     *
     * TileCodec.encode RUNS HERE, BEFORE the shared call: it needs BitmapFactory and
     * Bitmap.CompressFormat, which are Android-only. See cert gap 1 in the class note --
     * isOverlay=false is unreachable today because both live slots are overlays.
     */
    fun insertTile(type: String, z: Int, x: Int, y: Int, bytes: ByteArray, isOverlay: Boolean = false): Boolean {
        val stored = TileCodec.encode(bytes, isOverlay)   // [V2.6a-WEBP] lossy base / lossless overlay
        return shared.insertTile(type, z, x, y, stored)
    }

    // ---- 2. point reads ------------------------------------------------------------

    /** Read one tile's bytes, or null if absent. RAW z/x/y -- same key as insert.
     *  This is the read contract every shouldInterceptRequest branch calls. */
    fun readTile(type: String, z: Int, x: Int, y: Int): ByteArray? {
        // OFFTRACE-2026-08-11M: what the store actually returns. A null here with tiles
        // on disk means the lookup, not the map.
        android.util.Log.i("OFFTRACE", "OFFTRACE-2026-08-11M readTile type=$type z=$z x=$x y=$y")
        return shared.readTile(type, z, x, y)
    }

    /** True if a tile exists (resume-skip check; replaces dest.exists()). */
    fun hasTile(type: String, z: Int, x: Int, y: Int): Boolean = shared.hasTile(type, z, x, y)

    // ---- 3. counts and extents -----------------------------------------------------

    /** RECREATE-2026-08-11G: how many STORED tiles fall inside a tile-coordinate range
     *  at one zoom. The whole basis of Recreate: never ask what a box COULD cover, ask
     *  what it HAS. */
    fun countInTileRange(type: String, z: Int, xMin: Long, xMax: Long, yMin: Long, yMax: Long): Int =
        shared.countInTileRange(type, z, xMin, xMax, yMin, yMax)

    /** DELETE-AREA-2026-07-25: the Int-argument twin. Both exist; both are live. */
    fun countTileRange(type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int =
        shared.countTileRange(type, z, xMin, xMax, yMin, yMax)

    /** RECREATE-2026-08-11G: [xMin, xMax, yMin, yMax] actually occupied at one zoom, or
     *  null when the level holds nothing. MIN/MAX only -- the store's bounding box,
     *  deliberately. The extent lies about disjoint coverage; the quadtree corrects that. */
    fun tileExtentAtZoom(type: String, z: Int): LongArray? = shared.tileExtentAtZoom(type, z)

    /** RECREATE-2026-08-11A: which zoom levels actually hold tiles. A level with no rows
     *  is simply absent from this list and never becomes work. */
    fun zoomLevelsPresent(type: String): List<Int> = shared.zoomLevelsPresent(type)

    /** RECREATE-2026-08-11A: how many tiles exist at one zoom. COUNT only. */
    fun countAtZoom(type: String, z: Int): Int = shared.countAtZoom(type, z)

    /** [V2.6-PASS1-S4] the (x,y) pairs present at a zoom. Feeds the coverage-highlight walks. */
    fun xyAtZoom(type: String, z: Int): List<Pair<Long, Long>> = shared.xyAtZoom(type, z)

    /** RECIPESCAN-2026-10-30: one level's tiles grouped by their ancestor `shift` levels
     *  up -- (ax, ay, count). ONE indexed pass per level. */
    fun countByParent(type: String, z: Int, shift: Int): List<LongArray> = shared.countByParent(type, z, shift)

    // ---- 4. whole-store scans ------------------------------------------------------

    /** All (z,x,y) keys for a type. Replaces scanTilesToKeys' dir walk. */
    fun scanKeys(type: String): List<TileKey> {
        val keys = shared.scanKeys(type).map { old(it) }
        android.util.Log.i(TAG, "scanKeys $type: ${keys.size} tiles")
        return keys
    }

    /**
     * REFRESHOOM-2026-08-11L: the tiles this store ACTUALLY HOLDS inside a lat/lon box.
     * Bounded by what is STORED, not by the size of the box -- the previous version built
     * every tile in the rectangle first and exhausted a 512 MB heap on 08-11.
     *
     * RAW z/x/y, no TMS flip -- and NORTH is the SMALLER tile row, which is why north maps
     * to yMin. Same convention calculateTiles uses.
     */
    fun tilesInBounds(type: String, north: Double, south: Double, east: Double, west: Double): List<TileKey> =
        shared.tilesInBounds(type, north, south, east, west).map { old(it) }

    // ---- 5. deletes ----------------------------------------------------------------

    /**
     * DELETE-AREA-2026-07-25: remove every tile inside a z/x/y rectangle. Returns rows deleted.
     *
     * ONE STATEMENT PER ZOOM, not one per tile. A bbox is a CONTIGUOUS RECTANGLE in x/y at
     * every zoom, so BETWEEN is EXACTLY equivalent to enumerating the tiles. (A CORRIDOR
     * delete could not use this form: its tile set is a buffered line, not a rectangle.)
     *
     * NOTE: space is NOT returned to the filesystem by this -- SQLite frees the pages inside
     * the file. See reclaimableBytes: on an auto_vacuum=FULL store the pages ARE returned,
     * which is why that member reads zero.
     */
    fun deleteTileRange(type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int =
        shared.deleteTileRange(type, z, xMin, xMax, yMin, yMax)

    /** CORRDELETE-ONDISK-2026-08-07D: how many of these EXACT tiles are on disk.
     *  Deliberately mirrors deleteTiles -- same grouping, same 500-bind chunking, same
     *  WHERE shape -- because a pre-count that selects on different criteria than the
     *  delete reads as authoritative while disagreeing with what actually happens.
     *  countTileRange cannot serve here: it takes a BOUNDING BOX, and a corridor is not one. */
    fun countTiles(type: String, tiles: List<TileKey>): Int {
        val found = shared.countTiles(type, tiles.map { gt(it) })
        android.util.Log.i(TAG, "countTiles $type: ${tiles.size} requested, $found present")
        return found
    }

    /**
     * MBTILESDELTILES-2026-08-06: delete an EXPLICIT LIST of tiles -- the primitive a
     * corridor delete needs. This store could previously delete only a RANGE or a WHOLE
     * SOURCE; a corridor is neither, and deleting it by range would remove its BOUNDING BOX.
     *
     * Returns rows actually removed, which is what the worker should report: the caller's
     * tile list is what COULD be there, not what WAS.
     */
    fun deleteTiles(type: String, tiles: List<TileKey>): Int {
        val removed = shared.deleteTiles(type, tiles.map { gt(it) })
        android.util.Log.i(TAG, "deleteTiles $type: ${tiles.size} requested, $removed actually removed")
        return removed
    }

    // ---- 6. store-level ------------------------------------------------------------

    /**
     * DELETE-AREA-2026-07-25: reclaimable bytes in this store's freelist
     * (freelist_count * page_size).
     *
     * KNOWN MEANINGLESS AND KEPT FAITHFUL. Measured 11-14: on an auto_vacuum=FULL database
     * (which the Droid reports) freed pages return to the OS, the freelist never fills, and
     * the product is STRUCTURALLY ZERO. This answers the open DELETE-BANDING-2026-07-25
     * question -- "0 MB reclaimable after removing 38,896 tiles ... most likely WAL": not
     * WAL, this file sets journal_mode=TRUNCATE; the space was already returned.
     *
     * 2.7e logged freelist_count and page_size separately. Both now live inside the shared
     * port, so only the product is available to log here.
     */
    fun reclaimableBytes(type: String): Long {
        val bytes = shared.reclaimableBytes(type)
        android.util.Log.i(TAG, "reclaimable $type: $bytes bytes")
        return bytes
    }

    /** Delete all tiles for a type -- drops the whole .mbtiles file and its sidecars. */
    fun deleteSource(type: String): Boolean {
        val ok = shared.deleteSource(type)
        android.util.Log.i(TAG, "deleteSource $type = $ok")
        return ok
    }

    // ---- 7. not in the shared port (both measured dead 11-14; M2 skip, not remove) --

    /** Tile count + size (MB) for a type. Replaces sourceInfo's dir walk.
     *  DEAD: its only caller is ConvoyTileDownloader.sourceInfo, which has zero callers. */
    fun sourceInfo(type: String): Pair<Int, Float> {
        val c = connection(type) ?: return Pair(0, 0f)
        return try {
            var count = 0
            val st = c.prepare("SELECT COUNT(*) FROM tiles")
            try { if (st.step()) count = st.getLong(0).toInt() } finally { st.close() }
            val bytes = dbFileFor(type).length()
            Pair(count, bytes / (1024f * 1024f))
        } catch (e: Exception) { Pair(0, 0f) }
    }

    /** Close all open connections (app teardown). DEAD: zero call sites anywhere. */
    @Synchronized
    fun closeAll() {
        for ((_, c) in handles) try { c.close() } catch (_: Exception) {}
        handles.clear()
    }
}
