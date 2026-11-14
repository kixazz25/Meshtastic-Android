package com.geeksville.mesh.convoy

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * MBTILESCERTOLD-2026-11-14: the OLD side of the MBTilesStore certification (Cert17mbtiles).
 * 2.7e MBTilesStore's LOGIC for its 18 LIVE members, copied as is.
 *
 * WHAT STAYS 2.7e ON PURPOSE -- these are what the cert measures:
 *   - android.database.sqlite.SQLiteDatabase, openOrCreateDatabase, rawQuery, execSQL, delete,
 *     beginTransaction / setTransactionSuccessful / endTransaction
 *   - the three safety pragmas through rawQuery, EACH IN ITS OWN TRY
 *   - java.lang.Math for tilesInBounds' forward mercator
 *   - the real TileKey, built in 2.7e's own argument order
 *
 * SUBSTITUTION MANIFEST -- counted, and these are the ONLY changes from 2.7e:
 *   1. object -> class(certDir)                                                          x1
 *   2. tilesDir() = ConvoyConfig.TILE_DIR -> the injected certDir                        x1
 *      ⚠ WHY: 2.7e reads the live tile directory. An OLD side that kept it would run the cert
 *      against Droid 1's real 3.8 GB of maps and could DELETE them -- deleteTileRange,
 *      deleteTiles and deleteSource are four of the eighteen members under test.
 *   3. closeAll NOT copied -- measured dead (zero call sites anywhere)                    x1
 *   4. sourceInfo NOT copied -- measured dead (its only caller has zero callers)          x1
 *   5. android.util.Log lines dropped (a cert reads return values, not logcat)            x17
 *   TOTAL: 21 substitutions, of which 2 are omissions. Nothing else changes -- not a default,
 *   not a branch, not a chunk size, not an argument order.
 *
 * ⚠ THE HANDLE CACHE IS COPIED, NOT INJECTED. The shared port deliberately leaves the cache on
 * the platform, but THIS side is 2.7e, and 2.7e's cache is part of what 2.7e does -- including that
 * db() applies the schema and the pragmas on first open. Removing it here would make the OLD side a
 * different program.
 */
class MBTilesStoreCertOld(private val certDir: File) {

    private val handles = HashMap<String, SQLiteDatabase>()

    private fun tilesDir(): File {                                        // substitution 2
        if (!certDir.exists()) certDir.mkdirs()
        return certDir
    }

    private fun dbFileFor(type: String): File = File(tilesDir(), "$type.mbtiles")

    @Synchronized
    private fun db(type: String): SQLiteDatabase? {
        handles[type]?.let { if (it.isOpen) return it else handles.remove(type) }
        return try {
            val f = dbFileFor(type)
            val fresh = !f.exists()
            val d = SQLiteDatabase.openOrCreateDatabase(f, null)
            if (fresh) initSchema(d, type)
            ensureSchema(d)
            handles[type] = d
            d
        } catch (e: Exception) { null }
    }

    private fun initSchema(d: SQLiteDatabase, type: String) {
        ensureSchema(d)
        d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('name',?)", arrayOf<Any>(type))
        d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('format',?)", arrayOf<Any>("pbf"))
        d.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('scheme',?)", arrayOf<Any>("xyz"))
    }

    private fun ensureSchema(d: SQLiteDatabase) {
        // 2.7e MBPRAGMA-2026-08-09G: every pragma goes through rawQuery, ONE TRY EACH. The structure
        // matters -- an earlier attempt used execSQL for two of the three and set NOTHING on device,
        // because Android refuses any statement that returns a row and the first failure took the
        // others down with it.
        for (p in listOf("secure_delete=OFF", "synchronous=NORMAL", "journal_mode=TRUNCATE")) {
            try { d.rawQuery("PRAGMA $p", null).use { it.moveToFirst() } } catch (e: Exception) { }
        }
        d.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        d.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles(zoom_level, tile_column, tile_row)")
    }

    /** The cert reads this to make the cause of a reclaimableBytes difference visible. */
    fun autoVacuum(type: String): String {
        val d = db(type) ?: return "(no handle)"
        return try {
            d.rawQuery("PRAGMA auto_vacuum", null).use { if (it.moveToFirst()) it.getLong(0).toString() else "(no row)" }
        } catch (e: Exception) { "THREW" }
    }

    fun insertTile(type: String, z: Int, x: Int, y: Int, bytes: ByteArray): Boolean {
        val d = db(type) ?: return false
        return try {
            d.execSQL("INSERT OR REPLACE INTO tiles(zoom_level,tile_column,tile_row,tile_data) VALUES(?,?,?,?)",
                arrayOf<Any>(z, x, y, bytes))
            true
        } catch (e: Exception) { false }
    }

    fun readTile(type: String, z: Int, x: Int, y: Int): ByteArray? {
        val d = db(type) ?: return null
        return try {
            d.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                arrayOf(z.toString(), x.toString(), y.toString())
            ).use { if (it.moveToFirst()) it.getBlob(0) else null }
        } catch (e: Exception) { null }
    }

    fun hasTile(type: String, z: Int, x: Int, y: Int): Boolean {
        val d = db(type) ?: return false
        return try {
            d.rawQuery("SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                arrayOf(z.toString(), x.toString(), y.toString())).use { it.moveToFirst() }
        } catch (e: Exception) { false }
    }

    fun countInTileRange(type: String, z: Int, xMin: Long, xMax: Long, yMin: Long, yMax: Long): Int {
        val d = db(type) ?: return 0
        return try {
            d.rawQuery("SELECT COUNT(*) FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                arrayOf(z.toString(), xMin.toString(), xMax.toString(), yMin.toString(), yMax.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

    fun countTileRange(type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int {
        val d = db(type) ?: return 0
        return try {
            d.rawQuery("SELECT COUNT(*) FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                arrayOf(z.toString(), xMin.toString(), xMax.toString(), yMin.toString(), yMax.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

    fun tileExtentAtZoom(type: String, z: Int): LongArray? {
        val d = db(type) ?: return null
        return try {
            d.rawQuery("SELECT MIN(tile_column), MAX(tile_column), MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level=?",
                arrayOf(z.toString())
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) else null
            }
        } catch (e: Exception) { null }
    }

    fun zoomLevelsPresent(type: String): List<Int> {
        val d = db(type) ?: return emptyList()
        val out = ArrayList<Int>()
        try {
            d.rawQuery("SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level", null).use { c ->
                while (c.moveToNext()) out.add(c.getInt(0))
            }
        } catch (e: Exception) { }
        return out
    }

    fun countAtZoom(type: String, z: Int): Int {
        val d = db(type) ?: return 0
        return try {
            d.rawQuery("SELECT COUNT(*) FROM tiles WHERE zoom_level=?", arrayOf(z.toString()))
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

    fun xyAtZoom(type: String, z: Int): List<Pair<Long, Long>> {
        val d = db(type) ?: return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        try {
            d.rawQuery("SELECT tile_column, tile_row FROM tiles WHERE zoom_level=?", arrayOf(z.toString())).use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to c.getLong(1))
            }
        } catch (e: Exception) { }
        return out
    }

    fun countByParent(type: String, z: Int, shift: Int): List<LongArray> {
        val d = db(type) ?: return emptyList()
        val out = ArrayList<LongArray>()
        try {
            d.rawQuery("SELECT tile_column >> $shift, tile_row >> $shift, COUNT(*) FROM tiles WHERE zoom_level=? GROUP BY 1, 2",
                arrayOf(z.toString())).use { c ->
                while (c.moveToNext()) out.add(longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2)))
            }
        } catch (e: Exception) { }
        return out
    }

    fun scanKeys(type: String): List<TileKey> {
        val d = db(type) ?: return emptyList()
        val keys = ArrayList<TileKey>()
        try {
            d.rawQuery("SELECT zoom_level, tile_column, tile_row FROM tiles", null).use { c ->
                while (c.moveToNext()) keys.add(TileKey(c.getInt(0), c.getInt(1), c.getInt(2)))
            }
        } catch (e: Exception) { }
        return keys
    }

    fun tilesInBounds(type: String, north: Double, south: Double, east: Double, west: Double): List<TileKey> {
        val d = db(type) ?: return emptyList()
        val out = ArrayList<TileKey>()
        for (z in zoomLevelsPresent(type)) {
            val n = 1L shl z
            fun lon2x(lon: Double): Long = Math.floor((lon + 180.0) / 360.0 * n).toLong()
            fun lat2y(lat: Double): Long {
                val r = Math.toRadians(lat)
                return Math.floor((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n).toLong()
            }
            val xMin = lon2x(west).coerceIn(0L, n - 1)
            val xMax = lon2x(east).coerceIn(0L, n - 1)
            val yMin = lat2y(north).coerceIn(0L, n - 1)   // NORTH is the SMALLER tile row
            val yMax = lat2y(south).coerceIn(0L, n - 1)
            try {
                d.rawQuery("SELECT tile_column, tile_row FROM tiles WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                    arrayOf(z.toString(), xMin.toString(), xMax.toString(), yMin.toString(), yMax.toString())
                ).use { c -> while (c.moveToNext()) out.add(TileKey(z, c.getInt(0), c.getInt(1))) }
            } catch (e: Exception) { }
        }
        return out
    }

    fun storeExists(type: String): Boolean = dbFileFor(type).exists()

    fun deleteTileRange(type: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int {
        if (!storeExists(type)) return 0
        val d = db(type) ?: return 0
        return try {
            d.delete("tiles", "zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                arrayOf(z.toString(), xMin.toString(), xMax.toString(), yMin.toString(), yMax.toString()))
        } catch (e: Exception) { 0 }
    }

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
                    args.add(z.toString()); args.add(x.toString())
                    for (y in chunk) args.add(y.toString())
                    d.rawQuery("SELECT COUNT(*) FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row IN ($placeholders)",
                        args.toTypedArray()).use { c -> if (c.moveToFirst()) found += c.getInt(0) }
                    i += chunk.size
                }
            }
            found
        } catch (e: Exception) { found }
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
                        args.add(z.toString()); args.add(x.toString())
                        for (y in chunk) args.add(y.toString())
                        removed += d.delete("tiles",
                            "zoom_level=? AND tile_column=? AND tile_row IN ($placeholders)", args.toTypedArray())
                        i += chunk.size
                    }
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
            removed
        } catch (e: Exception) { removed }
    }

    fun reclaimableBytes(type: String): Long {
        if (!storeExists(type)) return 0L
        val d = db(type) ?: return 0L
        var pages = 0L
        var pageSize = 0L
        try {
            d.rawQuery("PRAGMA freelist_count", null).use { c -> if (c.moveToFirst()) pages = c.getLong(0) }
            d.rawQuery("PRAGMA page_size", null).use { c -> if (c.moveToFirst()) pageSize = c.getLong(0) }
        } catch (e: Exception) { }
        return pages * pageSize
    }

    fun deleteSource(type: String): Boolean {
        handles.remove(type)?.let { try { it.close() } catch (_: Exception) {} }
        val f = dbFileFor(type)
        var ok = true
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            val s = File(f.parentFile, f.name + suffix)
            if (s.exists() && !s.delete()) ok = false
        }
        return ok
    }

    /** Close and forget everything this cert opened. Not 2.7e's closeAll (dead) -- cert teardown. */
    fun certTeardown() {
        for ((_, d) in handles) try { d.close() } catch (_: Exception) {}
        handles.clear()
    }
}
