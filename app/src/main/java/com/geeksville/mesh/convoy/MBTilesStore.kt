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
            val format = if (type.startsWith("SAT_LABELS")) "webp" else "png"
            d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('name',?)", arrayOf<Any>(type))
            d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('format',?)", arrayOf<Any>(format))
            d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('scheme',?)", arrayOf<Any>("xyz"))
            android.util.Log.i(TAG, "created $type.mbtiles (format=$format, scheme=xyz raw z/x/y)")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "metadata init $type: ${e.message}")
        }
    }

    private fun ensureSchema(d: SQLiteDatabase) {
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
    fun insertTile(type: String, z: Int, x: Int, y: Int, bytes: ByteArray): Boolean {
        val d = db(type) ?: return false
        return try {
            d.execSQL(
                "INSERT OR REPLACE INTO tiles(zoom_level,tile_column,tile_row,tile_data) VALUES(?,?,?,?)",
                arrayOf<Any>(z, x, y, bytes)
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
