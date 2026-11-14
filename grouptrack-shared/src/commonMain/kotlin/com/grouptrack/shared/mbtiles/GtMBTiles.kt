package com.grouptrack.shared.mbtiles

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.queue.GtTileKey
import com.grouptrack.shared.queue.GtTileMath

/**
 * GTMBTILES-2026-11-14 (2.8 step 17, Phase 2 pass 3): 2.7e MBTilesStore's 18 LIVE members, ported AS IS.
 *
 * TWO MEMBERS ARE DELIBERATELY ABSENT, both measured dead 11-14 (see the master's step 3 review):
 *   - closeAll    : ZERO call sites anywhere in the app.
 *   - sourceInfo  : its only caller is ConvoyTileDownloader.sourceInfo, which has zero callers.
 * Skipped, not removed (M2). A dead chain ports as nothing.
 *
 * ⛔ THE HANDLE CACHE DOES NOT LIVE HERE, AND THAT IS THE MAIN DESIGN DECISION OF THIS PASS.
 * 2.7e holds `private val handles = HashMap<String, SQLiteDatabase>()`, @Synchronized, with an
 * isOpen recheck -- so MBTilesStore is STATEFUL, unlike the stateless ports in passes 1 and 2.
 * That cache is PLATFORM STATE (handle lifetime, thread safety, the framework's own open/create),
 * not business logic. It stays on Android behind [GtTileStoreEnv.connection], which leaves this
 * class stateless and certifiable and is why `closeAll` being dead is convenient rather than awkward.
 *
 * ⚠ tilesInBounds REUSES GtTileMath.lon2tile / lat2tile, AND THAT REUSE WAS VERIFIED, NOT ASSUMED.
 * Pass 2 taught this the hard way: GtVectorQueue's latOf is algebraically equal to ConvoyMapRecipe's
 * and differs in floating point, so sharing it would have been an unfaithful port. Here the
 * expressions are IDENTICAL IN FORM -- checked over z0-z18 across 247 latitudes, zero differences --
 * so the reuse is faithful. The check is the point, not the outcome: it went the other way last time.
 *
 * ⚠ reclaimableBytes IS KEPT FAITHFUL AND IS KNOWN TO BE MEANINGLESS. Measured 11-14: on an
 * auto_vacuum=FULL database (which the Droid reports) freed pages return to the OS, the freelist
 * never fills, and freelist_count * page_size is STRUCTURALLY ZERO. It will certify at 0 DIFF and
 * that will prove nothing. This answers the open DELETE-BANDING-2026-07-25 question in 2.7e's own
 * comment ("0 MB reclaimable after removing 38,896 tiles ... most likely WAL"): not WAL -- the file
 * sets journal_mode=TRUNCATE -- the space was already returned. See the master.
 */
class GtMBTiles(private val env: GtTileStoreEnv) {

    // ---- helpers. Every member is best effort with 2.7e's own fallback, never a throw. ----

    private inline fun <T> read(type: String, sql: String, bind: (SQLiteStatement) -> Unit, fallback: T, body: (SQLiteStatement) -> T): T {
        val c = env.connection(type) ?: return fallback
        return try {
            val st = c.prepare(sql)
            try { bind(st); body(st) } finally { st.close() }
        } catch (e: Exception) { fallback }
    }

    /** 2.7e's d.delete(...) / d.execSQL(...) return the affected row count; androidx needs changes(). */
    private fun changes(c: SQLiteConnection): Int {
        val st = c.prepare("SELECT changes()")
        return try { if (st.step()) st.getLong(0).toInt() else 0 } finally { st.close() }
    }

    private fun exec(c: SQLiteConnection, sql: String) {
        val st = c.prepare(sql); try { st.step() } finally { st.close() }
    }

    // ---- 1. writes ----------------------------------------------------------------------------

    /** 2.7e insertTile: RAW z/x/y, no flip. REPLACE keeps last-write-wins resume semantics. */
    fun insertTile(type: String, z: Int, x: Int, y: Int, bytes: ByteArray): Boolean {
        val c = env.connection(type) ?: return false
        return try {
            val st = c.prepare("INSERT OR REPLACE INTO tiles(zoom_level,tile_column,tile_row,tile_data) VALUES(?,?,?,?)")
            try {
                st.bindLong(1, z.toLong()); st.bindLong(2, x.toLong()); st.bindLong(3, y.toLong()); st.bindBlob(4, bytes)
                st.step()
                true
            } finally { st.close() }
        } catch (e: Exception) { false }
    }

    // ---- 2. point reads ----------------------------------------------------------------------

    /** 2.7e readTile. Null when not held. */
    fun readTile(type: String, z: Int, x: Int, y: Int): ByteArray? = read(
        type, "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
        { it.bindLong(1, z.toLong()); it.bindLong(2, x.toLong()); it.bindLong(3, y.toLong()) }, null
    ) { if (it.step()) it.getBlob(0) else null }

    /** 2.7e hasTile. */
    fun hasTile(type: String, z: Int, x: Int, y: Int): Boolean = read(
        type, "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
        { it.bindLong(1, z.toLong()); it.bindLong(2, x.toLong()); it.bindLong(3, y.toLong()) }, false
    ) { it.step() }

    // ---- 3. counts and extents ----------------------------------------------------------------

    /**
     * 2.7e countInTileRange (RECREATE-2026-08-11G): how many STORED tiles fall inside a
     * tile-coordinate range at one zoom. "Never ask what a box COULD cover, ask what it HAS."
     */
    fun countInTileRange(type: String, z: Int, xMin: Long, xMax: Long, yMin: Long, yMax: Long): Int = read(
        type, "SELECT COUNT(*) FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
        { it.bindLong(1, z.toLong()); it.bindLong(2, xMin); it.bindLong(3, xMax); it.bindLong(4, yMin); it.bindLong(5, yMax) }, 0
    ) { if (it.step()) it.getLong(0).toInt() else 0 }

    /** 2.7e countTileRange -- the Int-argument twin of countInTileRange. Both exist in 2.7e; both port. */
    fun countTileRange(type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int = read(
        type, "SELECT COUNT(*) FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
        { it.bindLong(1, z.toLong()); it.bindLong(2, xMin.toLong()); it.bindLong(3, xMax.toLong()); it.bindLong(4, yMin.toLong()); it.bindLong(5, yMax.toLong()) }, 0
    ) { if (it.step()) it.getLong(0).toInt() else 0 }

    /**
     * 2.7e tileExtentAtZoom: [xMin, xMax, yMin, yMax] actually occupied, or null when the level is
     * empty. ⚠ MIN/MAX only -- the store's bounding box, deliberately. "The extent lies about
     * disjoint coverage; the quadtree is what corrects for that."
     */
    fun tileExtentAtZoom(type: String, z: Int): LongArray? = read(
        type, "SELECT MIN(tile_column), MAX(tile_column), MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level=?",
        { it.bindLong(1, z.toLong()) }, null
    ) { st ->
        // 2.7e: moveToFirst() && !isNull(0) -- MIN over an empty set is NULL, not 0
        if (st.step() && !st.isNull(0)) longArrayOf(st.getLong(0), st.getLong(1), st.getLong(2), st.getLong(3)) else null
    }

    /** 2.7e zoomLevelsPresent. */
    fun zoomLevelsPresent(type: String): List<Int> = read(
        type, "SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level", { }, emptyList()
    ) { st -> val out = ArrayList<Int>(); while (st.step()) out.add(st.getLong(0).toInt()); out }

    /** 2.7e countAtZoom. */
    fun countAtZoom(type: String, z: Int): Int = read(
        type, "SELECT COUNT(*) FROM tiles WHERE zoom_level=?", { it.bindLong(1, z.toLong()) }, 0
    ) { if (it.step()) it.getLong(0).toInt() else 0 }

    /** 2.7e xyAtZoom: the (x,y) pairs present at a zoom. Feeds the coverage-highlight walks. */
    fun xyAtZoom(type: String, z: Int): List<Pair<Long, Long>> = read(
        type, "SELECT tile_column, tile_row FROM tiles WHERE zoom_level=?", { it.bindLong(1, z.toLong()) }, emptyList()
    ) { st -> val out = ArrayList<Pair<Long, Long>>(); while (st.step()) out.add(st.getLong(0) to st.getLong(1)); out }

    /**
     * 2.7e countByParent: COUNT grouped by the parent cell at [shift] levels up, via
     * `tile_column >> shift` and GROUP BY ordinals. ⚠ The shift is INTERPOLATED, not bound --
     * SQLite will not accept a bind parameter as a shift operand, and 2.7e interpolates it too.
     */
    fun countByParent(type: String, z: Int, shift: Int): List<LongArray> = read(
        type, "SELECT tile_column >> $shift, tile_row >> $shift, COUNT(*) FROM tiles WHERE zoom_level=? GROUP BY 1, 2",
        { it.bindLong(1, z.toLong()) }, emptyList()
    ) { st -> val out = ArrayList<LongArray>(); while (st.step()) out.add(longArrayOf(st.getLong(0), st.getLong(1), st.getLong(2))); out }

    // ---- 4. whole-store scans -----------------------------------------------------------------

    /** 2.7e scanKeys: every (z,x,y). */
    fun scanKeys(type: String): List<GtTileKey> = read(
        type, "SELECT zoom_level, tile_column, tile_row FROM tiles", { }, emptyList()
    ) { st ->
        val out = ArrayList<GtTileKey>()
        while (st.step()) out.add(GtTileKey(z = st.getLong(0).toInt(), x = st.getLong(1).toInt(), y = st.getLong(2).toInt()))
        out
    }

    /**
     * 2.7e tilesInBounds (REFRESHOOM-2026-08-11L): the tiles this store ACTUALLY HOLDS inside a
     * lat/lon box. Bounded by what is STORED, not by the size of the box -- the whole point: the
     * previous version built every tile in the rectangle first and exhausted a 512 MB heap on
     * 08-11. ⚠ RAW z/x/y, no TMS flip, and NORTH IS THE SMALLER TILE ROW, which is why north
     * maps to yMin. Same convention calculateTiles uses.
     */
    fun tilesInBounds(type: String, north: Double, south: Double, east: Double, west: Double): List<GtTileKey> {
        val out = ArrayList<GtTileKey>()
        for (z in zoomLevelsPresent(type)) {
            val n = 1L shl z
            // verified identical in form to 2.7e's inline lon2x/lat2y -- see the class note
            val xMin = GtTileMath.lon2tile(west, z).toLong().coerceIn(0L, n - 1)
            val xMax = GtTileMath.lon2tile(east, z).toLong().coerceIn(0L, n - 1)
            val yMin = GtTileMath.lat2tile(north, z).toLong().coerceIn(0L, n - 1)
            val yMax = GtTileMath.lat2tile(south, z).toLong().coerceIn(0L, n - 1)
            out.addAll(read(
                type, "SELECT tile_column, tile_row FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                { it.bindLong(1, z.toLong()); it.bindLong(2, xMin); it.bindLong(3, xMax); it.bindLong(4, yMin); it.bindLong(5, yMax) },
                emptyList()
            ) { st ->
                val rows = ArrayList<GtTileKey>()
                while (st.step()) rows.add(GtTileKey(z = z, x = st.getLong(0).toInt(), y = st.getLong(1).toInt()))
                rows
            })
        }
        return out
    }

    // ---- 5. deletes ---------------------------------------------------------------------------

    /**
     * 2.7e deleteTileRange. ⚠ "space is NOT returned to the filesystem by this. SQLite frees the
     * pages inside the file; the .mbtiles keeps its size until a VACUUM." (2.7e's own note -- and see
     * reclaimableBytes: on an auto_vacuum=FULL store the pages ARE returned, which is why that
     * member reads zero.)
     */
    fun deleteTileRange(type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int {
        if (!storeExists(type)) return 0                 // 2.7e checks first: never CREATE a store to delete from
        val c = env.connection(type) ?: return 0
        return try {
            val st = c.prepare("DELETE FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?")
            try {
                st.bindLong(1, z.toLong()); st.bindLong(2, xMin.toLong()); st.bindLong(3, xMax.toLong())
                st.bindLong(4, yMin.toLong()); st.bindLong(5, yMax.toLong())
                st.step()
            } finally { st.close() }
            changes(c)
        } catch (e: Exception) { 0 }
    }

    /**
     * 2.7e countTiles (MBTILESDELTILES-2026-08-06): an EXPLICIT LIST, grouped by (z,x) and queried
     * with `tile_row IN (...)`. ⚠ CHUNKED AT 500 -- "SQLite allows 999 and the query already
     * uses two." A corridor is a thin strip, so most (z,x) pairs hold a short run of consecutive
     * rows and this collapses tens of thousands of statements into a few hundred.
     */
    fun countTiles(type: String, tiles: List<GtTileKey>): Int {
        if (tiles.isEmpty()) return 0
        if (!storeExists(type)) return 0
        val c = env.connection(type) ?: return 0
        var found = 0
        return try {
            for ((key, group) in tiles.groupBy { it.z to it.x }) {
                val (z, x) = key
                val rows = group.map { it.y }
                var i = 0
                while (i < rows.size) {
                    val chunk = rows.subList(i, minOf(i + 500, rows.size))
                    val st = c.prepare(
                        "SELECT COUNT(*) FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row IN (" +
                            chunk.joinToString(",") { "?" } + ")")
                    try {
                        st.bindLong(1, z.toLong()); st.bindLong(2, x.toLong())
                        for ((j, y) in chunk.withIndex()) st.bindLong(3 + j, y.toLong())
                        if (st.step()) found += st.getLong(0).toInt()
                    } finally { st.close() }
                    i += chunk.size
                }
            }
            found
        } catch (e: Exception) { found }   // 2.7e returns what it found so far, not 0
    }

    /**
     * 2.7e deleteTiles. Same grouping and chunking as countTiles, inside ONE TRANSACTION --
     * "a half-deleted corridor is not a state worth leaving behind, and it is markedly faster."
     */
    fun deleteTiles(type: String, tiles: List<GtTileKey>): Int {
        if (tiles.isEmpty()) return 0
        if (!storeExists(type)) return 0
        val c = env.connection(type) ?: return 0
        var removed = 0
        return try {
            exec(c, "BEGIN")                               // 2.7e beginTransaction()
            try {
                for ((key, group) in tiles.groupBy { it.z to it.x }) {
                    val (z, x) = key
                    val rows = group.map { it.y }
                    var i = 0
                    while (i < rows.size) {
                        val chunk = rows.subList(i, minOf(i + 500, rows.size))
                        val st = c.prepare(
                            "DELETE FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row IN (" +
                                chunk.joinToString(",") { "?" } + ")")
                        try {
                            st.bindLong(1, z.toLong()); st.bindLong(2, x.toLong())
                            for ((j, y) in chunk.withIndex()) st.bindLong(3 + j, y.toLong())
                            st.step()
                        } finally { st.close() }
                        removed += changes(c)
                        i += chunk.size
                    }
                }
                exec(c, "COMMIT")                          // 2.7e setTransactionSuccessful()
            } catch (e: Exception) {
                try { exec(c, "ROLLBACK") } catch (e2: Exception) { }
                throw e
            }
            removed
        } catch (e: Exception) { removed }
    }

    // ---- 6. store-level -----------------------------------------------------------------------

    /** 2.7e storeExists: the FILE, without opening it -- so a check never creates an empty store. */
    fun storeExists(type: String): Boolean = env.storeExists(type)

    /**
     * 2.7e reclaimableBytes (DELETE-AREA-2026-07-25): freelist_count * page_size.
     * ⛔ KNOWN MEANINGLESS ON auto_vacuum=FULL -- see the class note. Ported faithfully anyway,
     * because a port is not the place to fix it.
     */
    fun reclaimableBytes(type: String): Long {
        if (!storeExists(type)) return 0L
        val c = env.connection(type) ?: return 0L
        var pages = 0L
        var pageSize = 0L
        // 2.7e reads the two pragmas in ONE try; a failure leaves both at 0
        try {
            val a = c.prepare("PRAGMA freelist_count")
            try { if (a.step()) pages = a.getLong(0) } finally { a.close() }
            val b = c.prepare("PRAGMA page_size")
            try { if (b.step()) pageSize = b.getLong(0) } finally { b.close() }
        } catch (e: Exception) { }
        return pages * pageSize
    }

    /** 2.7e deleteSource: drop the handle, then the file AND its -journal / -wal / -shm sidecars. */
    fun deleteSource(type: String): Boolean = env.deleteStore(type)
}

/**
 * What the tile store needs from the platform. Each one is platform state or platform file access,
 * never a decision -- which is what keeps [GtMBTiles] stateless and certifiable.
 */
interface GtTileStoreEnv {
    /**
     * 2.7e MBTilesStore.db(type): the cached, open-or-created, schema-ensured handle for a slot.
     * ⛔ The CACHE and the SCHEMA/PRAGMA setup stay on the platform deliberately. The three
     * pragmas 2.7e sets (secure_delete=OFF, synchronous=NORMAL, journal_mode=TRUNCATE) carry an
     * explicit crash-safety rationale and each must be set in its OWN try -- a structure added after
     * execSQL silently set NOTHING on device. Null on hard failure, exactly as 2.7e.
     */
    fun connection(type: String): SQLiteConnection?
    /** 2.7e dbFileFor(type).exists() -- checked WITHOUT opening, so it cannot create an empty store. */
    fun storeExists(type: String): Boolean
    /** 2.7e deleteSource: close and forget the handle, delete the file and its sidecars. */
    fun deleteStore(type: String): Boolean
}
