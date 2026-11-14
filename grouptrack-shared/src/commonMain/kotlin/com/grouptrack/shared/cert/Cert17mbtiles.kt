package com.grouptrack.shared.cert

import com.grouptrack.shared.mbtiles.GtMBTiles
import com.grouptrack.shared.mbtiles.GtTileStoreEnv
import com.grouptrack.shared.queue.GtTileKey

/**
 * CERT17MBTILES-2026-11-14 (2.8 step 17, Phase 2 pass 3): MBTilesStore, OLD vs NEW.
 *
 * SIMULATED FIRST (sim_mbtiles_v1_2026-11-14, via Python's sqlite3 -- SQLite is SQLite, so the
 * expected values are MEASURED, not modelled): 63 compared keys, no collisions, every call returned.
 * The simulation caught its own first fixture being useless for reclaimableBytes (26 tiles of 16
 * bytes fit in four pages, and deleting rows INSIDE a page frees none), which is why [CHURN] exists.
 *
 * ⛔ THE DRIVING SEQUENCE LIVES HERE AND NOWHERE ELSE. [Side] exposes all 18 members and
 * [run] calls them in one place, so neither side chooses what to exercise or in what order. Passes
 * 1 and 2 both paid for this lesson in a different shape (a private formatter, then a per-side
 * read-back with different defaults): anything duplicated per side can differ per side, and then a
 * difference in the HARNESS reads as a difference in the SERVICE.
 *
 * ⚠ auto_vacuum IS A COMPARED KEY, on purpose. reclaimableBytes is freelist_count * page_size,
 * and on an auto_vacuum=FULL store the freelist never fills, so the member is structurally zero.
 * The OLD side's handle comes from SQLiteDatabase.openOrCreateDatabase and the NEW side's from
 * gtOpenDatabase; if those two disagree about auto_vacuum then reclaimableBytes will DIFF for a
 * reason that is the HANDLE, not the arithmetic. Recording the pragma makes the cause visible
 * instead of leaving it to be inferred from a number.
 */
object Cert17mbtiles {

    const val SLOT = "CERTTILES"
    const val CHURN_SLOT = "CERTCHURN"

    /** Deterministic and distinguishable per key, so a mixed-up row is visible rather than plausible. */
    fun blobFor(z: Int, x: Int, y: Int): ByteArray {
        val n = ((z * 7919 + x * 104729 + y * 1299709) and 0xff)
        val head = byteArrayOf(n.toByte(), ((n * 31) and 0xff).toByte(), 0, -1)
        return head + ByteArray(12) { (((it * 17) + n) and 0xff).toByte() }
    }

    /** 4 KB, so a page is actually filled -- see [CHURN]. */
    fun bigBlob(): ByteArray = ByteArray(4096) { ((it * 7) and 0xff).toByte() }

    /**
     * The logic store: a sparse diagonal corridor, a dense 4x4 block, one lone low tile, and the
     * extreme corner at z18. Small and exact, so every expected value is derivable.
     */
    val LOGIC: List<GtTileKey> =
        (0 until 8).map { GtTileKey(12, 100 + it, 200 + it) } +
        (400 until 404).flatMap { x -> (800 until 804).map { y -> GtTileKey(13, x, y) } } +
        listOf(GtTileKey(9, 1, 1)) +
        listOf(GtTileKey(18, 262143, 262143))

    /**
     * ⛔ THE CHURN STORE EXISTS BECAUSE THE SMALL ONE PROVES NOTHING ABOUT THE FREELIST.
     * 300 tiles of 4 KB spans ~342 pages; deleting the first 48 columns frees whole pages. Measured
     * 11-14: with auto_vacuum=NONE the freelist reaches 263 pages (1,077,248 bytes reclaimable);
     * with FULL it stays at 0 and the FILE SHRINKS instead. Either answer is informative. A fixture
     * that cannot move the freelist would have certified reclaimableBytes against a constant 0.
     */
    val CHURN: List<GtTileKey> = (0 until 300).map { GtTileKey(13, it % 64, it / 64) }

    /** Every member, so [run] drives the sequence and no side decides it. */
    interface Side {
        /** Create the store fresh for [slot] and insert [tiles] with [blobFor]. */
        fun seed(slot: String, tiles: List<GtTileKey>, big: Boolean)
        /** PRAGMA auto_vacuum on this side's handle -- the cause-maker for reclaimableBytes. */
        fun autoVacuum(slot: String): String
        fun insertTile(slot: String, z: Int, x: Int, y: Int, bytes: ByteArray): Boolean
        fun readTile(slot: String, z: Int, x: Int, y: Int): ByteArray?
        fun hasTile(slot: String, z: Int, x: Int, y: Int): Boolean
        fun countInTileRange(slot: String, z: Int, xMin: Long, xMax: Long, yMin: Long, yMax: Long): Int
        fun countTileRange(slot: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int
        fun tileExtentAtZoom(slot: String, z: Int): LongArray?
        fun zoomLevelsPresent(slot: String): List<Int>
        fun countAtZoom(slot: String, z: Int): Int
        fun xyAtZoom(slot: String, z: Int): List<Pair<Long, Long>>
        fun countByParent(slot: String, z: Int, shift: Int): List<LongArray>
        fun scanKeys(slot: String): List<GtTileKey>
        fun tilesInBounds(slot: String, north: Double, south: Double, east: Double, west: Double): List<GtTileKey>
        fun deleteTileRange(slot: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int): Int
        fun countTiles(slot: String, tiles: List<GtTileKey>): Int
        fun deleteTiles(slot: String, tiles: List<GtTileKey>): Int
        fun storeExists(slot: String): Boolean
        fun reclaimableBytes(slot: String): Long
        fun deleteSource(slot: String): Boolean
    }

    // ---------------------------------------------------------------- THE ONE FORMATTER
    fun hex(b: ByteArray?): String {
        if (b == null) return "null"
        val d = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xff; sb.append(d[v shr 4]).append(d[v and 0xf]) }
        return sb.toString()
    }
    fun keys(ks: List<GtTileKey>): String =
        ks.map { "${it.z}/${it.x}/${it.y}" }.sorted().joinToString(";")
    fun pairs(ps: List<Pair<Long, Long>>): String =
        ps.map { "${it.first}/${it.second}" }.sorted().joinToString(";")
    fun parents(rs: List<LongArray>): String =
        rs.map { "${it[0]}/${it[1]}=${it[2]}" }.sorted().joinToString(";")
    fun extent(e: LongArray?): String = if (e == null) "null" else e.joinToString(",")

    /** ⛔ Never reorder or rename these -- a golden run is keyed on them. */
    val EXPECTED_DIFF: Map<String, String> = emptyMap()

    // ---------------------------------------------------------------- THE ONE NEW SIDE
    /**
     * MBTILESNEWSIDE-2026-11-14: NEW, in COMMON code, so the Droid and the iPad run the same bytes and only
     * their throwaway directory differs. It needs nothing the port does not already need: seeding is
     * deleteStore -> connection (which opens-or-creates and applies the schema) -> insertTile.
     */
    fun newSide(env: GtTileStoreEnv): Side = object : Side {
        private val store = GtMBTiles(env)

        override fun seed(slot: String, tiles: List<GtTileKey>, big: Boolean) {
            env.deleteStore(slot)                    // fresh every time -- a previous run cannot seed this one
            env.connection(slot)                     // opens, creates, applies the schema and the pragmas
            for (k in tiles) store.insertTile(slot, k.z, k.x, k.y, if (big) bigBlob() else blobFor(k.z, k.x, k.y))
        }

        override fun autoVacuum(slot: String): String {
            val c = env.connection(slot) ?: return "(no handle)"
            val st = c.prepare("PRAGMA auto_vacuum")
            return try { if (st.step()) st.getLong(0).toString() else "(no row)" } catch (e: Exception) { "THREW" } finally { st.close() }
        }

        override fun insertTile(slot: String, z: Int, x: Int, y: Int, bytes: ByteArray) = store.insertTile(slot, z, x, y, bytes)
        override fun readTile(slot: String, z: Int, x: Int, y: Int) = store.readTile(slot, z, x, y)
        override fun hasTile(slot: String, z: Int, x: Int, y: Int) = store.hasTile(slot, z, x, y)
        override fun countInTileRange(slot: String, z: Int, xMin: Long, xMax: Long, yMin: Long, yMax: Long) =
            store.countInTileRange(slot, z, xMin, xMax, yMin, yMax)
        override fun countTileRange(slot: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int) =
            store.countTileRange(slot, z, xMin, xMax, yMin, yMax)
        override fun tileExtentAtZoom(slot: String, z: Int) = store.tileExtentAtZoom(slot, z)
        override fun zoomLevelsPresent(slot: String) = store.zoomLevelsPresent(slot)
        override fun countAtZoom(slot: String, z: Int) = store.countAtZoom(slot, z)
        override fun xyAtZoom(slot: String, z: Int) = store.xyAtZoom(slot, z)
        override fun countByParent(slot: String, z: Int, shift: Int) = store.countByParent(slot, z, shift)
        override fun scanKeys(slot: String) = store.scanKeys(slot)
        override fun tilesInBounds(slot: String, north: Double, south: Double, east: Double, west: Double) =
            store.tilesInBounds(slot, north, south, east, west)
        override fun deleteTileRange(slot: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int) =
            store.deleteTileRange(slot, z, xMin, xMax, yMin, yMax)
        override fun countTiles(slot: String, tiles: List<GtTileKey>) = store.countTiles(slot, tiles)
        override fun deleteTiles(slot: String, tiles: List<GtTileKey>) = store.deleteTiles(slot, tiles)
        override fun storeExists(slot: String) = store.storeExists(slot)
        override fun reclaimableBytes(slot: String) = store.reclaimableBytes(slot)
        override fun deleteSource(slot: String) = store.deleteSource(slot)
    }

    fun run(side: Side, sink: (String, String) -> Unit) {
        // ---- the logic store -------------------------------------------------------------------
        side.seed(SLOT, LOGIC, big = false)
        sink("storeExists|seeded", side.storeExists(SLOT).toString())
        sink("autoVacuum|logic", side.autoVacuum(SLOT))
        sink("zoomLevelsPresent", side.zoomLevelsPresent(SLOT).joinToString(","))
        sink("scanKeys|count", side.scanKeys(SLOT).size.toString())
        sink("scanKeys|all", keys(side.scanKeys(SLOT)))

        for (z in listOf(9, 12, 13, 18, 14)) {              // 14 holds nothing -- the empty branch
            sink("countAtZoom|$z", side.countAtZoom(SLOT, z).toString())
            sink("tileExtentAtZoom|$z", extent(side.tileExtentAtZoom(SLOT, z)))
            sink("xyAtZoom|$z|count", side.xyAtZoom(SLOT, z).size.toString())
            sink("xyAtZoom|$z|all", pairs(side.xyAtZoom(SLOT, z)))
        }

        val points = listOf(
            Triple("hit", GtTileKey(12, 100, 200), true),
            Triple("miss", GtTileKey(12, 999, 999), false),
            Triple("lone", GtTileKey(9, 1, 1), true),
            Triple("corner", GtTileKey(18, 262143, 262143), true),
            Triple("wrong-zoom", GtTileKey(13, 100, 200), false),
        )
        for ((name, k, _) in points) {
            sink("readTile|$name", hex(side.readTile(SLOT, k.z, k.x, k.y)))
            sink("hasTile|$name", side.hasTile(SLOT, k.z, k.x, k.y).toString())
        }

        // ranges, including an INVERTED one (xMin > xMax) -- BETWEEN yields nothing, 2.7e does not guard
        val ranges = listOf(
            Triple("all", 12, longArrayOf(0, 1000, 0, 1000)),
            Triple("none", 12, longArrayOf(0, 1, 0, 1)),
            Triple("partial", 12, longArrayOf(100, 103, 200, 203)),
            Triple("single", 9, longArrayOf(1, 1, 1, 1)),
            Triple("inverted", 12, longArrayOf(1000, 0, 1000, 0)),
        )
        for ((name, z, r) in ranges) {
            sink("countInTileRange|$name", side.countInTileRange(SLOT, z, r[0], r[1], r[2], r[3]).toString())
            sink("countTileRange|$name", side.countTileRange(SLOT, z, r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt()).toString())
        }

        for ((z, sh) in listOf(12 to 2, 13 to 1, 13 to 4, 9 to 0)) {
            sink("countByParent|z$z|s$sh", parents(side.countByParent(SLOT, z, sh)))
        }

        for ((name, box) in listOf(
            "utah" to doubleArrayOf(38.3, 37.8, -112.4, -113.0),
            "world" to doubleArrayOf(85.0, -85.0, 180.0, -180.0),
            "empty" to doubleArrayOf(1.0, 0.9, 1.0, 0.9),
        )) {
            val tb = side.tilesInBounds(SLOT, box[0], box[1], box[2], box[3])
            sink("tilesInBounds|$name|count", tb.size.toString())
            sink("tilesInBounds|$name|all", keys(tb))
        }

        sink("countTiles|mixed", side.countTiles(SLOT, listOf(
            GtTileKey(12, 100, 200), GtTileKey(12, 101, 201), GtTileKey(12, 999, 999))).toString())
        sink("countTiles|empty", side.countTiles(SLOT, emptyList()).toString())

        // a chunk boundary: 501 rows in one (z,x) group forces a second chunk at the 500 limit
        val chunky = (0 until 501).map { GtTileKey(13, 400, 800 + it) }
        sink("countTiles|chunk-boundary", side.countTiles(SLOT, chunky).toString())

        // ---- writes, then deletes, on the logic store ------------------------------------------
        sink("insertTile|new", side.insertTile(SLOT, 12, 500, 500, blobFor(12, 500, 500)).toString())
        sink("readTile|new", hex(side.readTile(SLOT, 12, 500, 500)))
        sink("insertTile|replace", side.insertTile(SLOT, 12, 500, 500, byteArrayOf(9, 9)).toString())
        sink("readTile|replaced", hex(side.readTile(SLOT, 12, 500, 500)))
        sink("countAtZoom|12|after-insert", side.countAtZoom(SLOT, 12).toString())

        sink("deleteTileRange|block", side.deleteTileRange(SLOT, 13, 400, 403, 800, 803).toString())
        sink("countAtZoom|13|after-range", side.countAtZoom(SLOT, 13).toString())
        sink("deleteTileRange|nothing", side.deleteTileRange(SLOT, 13, 400, 403, 800, 803).toString())
        sink("deleteTiles|mixed", side.deleteTiles(SLOT, listOf(
            GtTileKey(12, 100, 200), GtTileKey(12, 101, 201), GtTileKey(12, 555, 555))).toString())
        sink("countAtZoom|12|after-keys", side.countAtZoom(SLOT, 12).toString())
        sink("deleteTiles|empty", side.deleteTiles(SLOT, emptyList()).toString())
        sink("scanKeys|after|all", keys(side.scanKeys(SLOT)))

        // ---- the churn store: the ONLY place reclaimableBytes means anything -------------------
        side.seed(CHURN_SLOT, CHURN, big = true)
        sink("autoVacuum|churn", side.autoVacuum(CHURN_SLOT))
        sink("churn|count", side.countAtZoom(CHURN_SLOT, 13).toString())
        sink("reclaimable|churn|fresh", side.reclaimableBytes(CHURN_SLOT).toString())
        sink("churn|deleted", side.deleteTileRange(CHURN_SLOT, 13, 0, 47, 0, 9999).toString())
        sink("churn|count-after", side.countAtZoom(CHURN_SLOT, 13).toString())
        sink("reclaimable|churn|after-delete", side.reclaimableBytes(CHURN_SLOT).toString())

        // ---- the missing store: every member's no-store branch ---------------------------------
        sink("storeExists|absent", side.storeExists("CERTNOPE").toString())
        sink("reclaimable|absent", side.reclaimableBytes("CERTNOPE").toString())
        sink("deleteTileRange|absent", side.deleteTileRange("CERTNOPE", 1, 0, 1, 0, 1).toString())
        sink("countTiles|absent", side.countTiles("CERTNOPE", listOf(GtTileKey(1, 0, 0))).toString())
        sink("deleteTiles|absent", side.deleteTiles("CERTNOPE", listOf(GtTileKey(1, 0, 0))).toString())

        // ---- and finally deleteSource, which must leave storeExists false ----------------------
        sink("deleteSource|logic", side.deleteSource(SLOT).toString())
        sink("storeExists|after-delete", side.storeExists(SLOT).toString())
        sink("deleteSource|absent", side.deleteSource("CERTNOPE").toString())
    }
}
