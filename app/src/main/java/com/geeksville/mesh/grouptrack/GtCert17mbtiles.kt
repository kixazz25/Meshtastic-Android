package com.geeksville.mesh.grouptrack

import android.content.Context
import androidx.sqlite.SQLiteConnection
import com.geeksville.mesh.convoy.MBTilesStoreCertOld
import com.geeksville.mesh.convoy.TileKey
import com.grouptrack.shared.cert.Cert17mbtiles
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.mbtiles.GtTileStoreEnv
import com.grouptrack.shared.queue.GtTileKey
import java.io.File
import java.io.Writer

/**
 * GTCERT17MBTILES-2026-11-14 (2.8 step 17, Phase 2 pass 3, Droid side) !! TEST-ONLY
 * (CODE RULE 3: remove with GtCertRun before 2.8 is released).
 *
 * OLD = 2.7e's MBTilesStore over android.database.sqlite.SQLiteDatabase (MBTilesStoreCertOld,
 * 21-item counted manifest). NEW = Cert17mbtiles.newSide, which is COMMON code.
 *
 * !! THIS FILE HOLDS THE PLATFORM HALF THE PORT DELIBERATELY EXCLUDES. GtMBTiles is stateless
 * because the handle cache, the open-or-create and the schema/pragma setup are platform state --
 * [CertTileEnv] below is where they live for the NEW side, mirroring 2.7e's db() step for step,
 * including the three safety pragmas EACH IN ITS OWN TRY.
 *
 * NOTHING LIVE IS READ OR WRITTEN. Both sides work under app.cacheDir/gtcert17mbtiles/{old,new},
 * wiped at the start. \u26d4 THIS MATTERS MORE HERE THAN IN ANY PREVIOUS PASS: four of the eighteen
 * members under test DELETE (deleteTileRange, deleteTiles, deleteSource, and deleteTileRange again
 * on the churn store), and the live store is Droid 1's real 3.8 GB of maps. 2.7e reads
 * ConvoyConfig.TILE_DIR directly; substitution 2 of the OLD side exists precisely so that it cannot.
 *
 * SIMULATED FIRST (sim_mbtiles_v1_2026-11-14, real SQL via sqlite3): 63 keys, no collisions, every
 * call returned. The simulation caught its own reclaimableBytes fixture being unable to move a
 * freelist, which is why the churn store exists.
 *
 * Lines: MBTILES <tab> key <tab> SAME|EXPECTED|DIFF <tab> value
 * Footer: "# MBTILES done: N SAME, N DIFF, N EXPECTED in S s".
 */
object GtCert17mbtiles {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /**
     * The NEW side's platform half: 2.7e's db() rewritten on the shared driver, cache and all.
     * Mirrors MBTilesStore.db / initSchema / ensureSchema step for step.
     */
    private class CertTileEnv(private val dir: File) : GtTileStoreEnv {
        private val handles = HashMap<String, SQLiteConnection>()

        private fun fileFor(type: String) = File(dir.also { if (!it.exists()) it.mkdirs() }, "$type.mbtiles")

        @Synchronized
        override fun connection(type: String): SQLiteConnection? {
            handles[type]?.let { return it }
            return try {
                val f = fileFor(type)
                val fresh = !f.exists()
                val c = gtOpenDatabase(f.absolutePath, false)
                ensureSchema(c)
                if (fresh) initMetadata(c, type)
                handles[type] = c
                c
            } catch (e: Exception) { null }
        }

        override fun storeExists(type: String): Boolean = fileFor(type).exists()

        override fun deleteStore(type: String): Boolean {
            handles.remove(type)?.let { try { it.close() } catch (_: Exception) {} }
            val f = fileFor(type)
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

        private fun ensureSchema(c: SQLiteConnection) {
            // 2.7e MBPRAGMA-2026-08-09G: ONE TRY EACH, so a single failure cannot silence the rest.
            // The rationale is 2.7e's: ~14 GB stores with no backup, and a database the framework
            // considers corrupt can be deleted by the default error handler.
            for (p in listOf("secure_delete=OFF", "synchronous=NORMAL", "journal_mode=TRUNCATE")) {
                try { exec(c, "PRAGMA $p") } catch (e: Exception) { }
            }
            exec(c, "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            exec(c, "CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
            exec(c, "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles(zoom_level, tile_column, tile_row)")
        }

        private fun initMetadata(c: SQLiteConnection, type: String) {
            for ((k, v) in listOf("name" to type, "format" to "pbf", "scheme" to "xyz")) {
                val st = c.prepare("INSERT OR REPLACE INTO metadata(name,value) VALUES(?,?)")
                try { st.bindText(1, k); st.bindText(2, v); st.step() } finally { st.close() }
            }
        }

        fun teardown() {
            for ((_, c) in handles) try { c.close() } catch (_: Exception) {}
            handles.clear()
        }
    }

    // ------------------------------------------------------------------ OLD (SQLiteDatabase; cannot move)
    private class OldSide(private val store: MBTilesStoreCertOld) : Cert17mbtiles.Side {
        // \u26a0 NAMED ARGUMENTS on every mapping. TileKey(Int,Int,Int) and GtTileKey(Int,Int,Int)
        // are the same shape, so a positional swap would COMPILE and silently transpose x and y.
        private fun gt(k: TileKey) = GtTileKey(z = k.z, x = k.x, y = k.y)
        private fun old(k: GtTileKey) = TileKey(z = k.z, x = k.x, y = k.y)

        override fun seed(slot: String, tiles: List<GtTileKey>, big: Boolean) {
            store.deleteSource(slot)
            for (k in tiles) store.insertTile(slot, k.z, k.x, k.y,
                if (big) Cert17mbtiles.bigBlob() else Cert17mbtiles.blobFor(k.z, k.x, k.y))
        }
        override fun autoVacuum(slot: String) = store.autoVacuum(slot)
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
        override fun scanKeys(slot: String) = store.scanKeys(slot).map { gt(it) }
        override fun tilesInBounds(slot: String, north: Double, south: Double, east: Double, west: Double) =
            store.tilesInBounds(slot, north, south, east, west).map { gt(it) }
        override fun deleteTileRange(slot: String, z: Int, xMin: Int, xMax: Int, yMin: Int, yMax: Int) =
            store.deleteTileRange(slot, z, xMin, xMax, yMin, yMax)
        override fun countTiles(slot: String, tiles: List<GtTileKey>) = store.countTiles(slot, tiles.map { old(it) })
        override fun deleteTiles(slot: String, tiles: List<GtTileKey>) = store.deleteTiles(slot, tiles.map { old(it) })
        override fun storeExists(slot: String) = store.storeExists(slot)
        override fun reclaimableBytes(slot: String) = store.reclaimableBytes(slot)
        override fun deleteSource(slot: String) = store.deleteSource(slot)
    }

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val base = File(ctx.cacheDir, "gtcert17mbtiles")
        base.deleteRecursively()
        val oldDir = File(base, "old").also { it.mkdirs() }
        val newDir = File(base, "new").also { it.mkdirs() }

        val oldStore = MBTilesStoreCertOld(oldDir)
        val newEnv = CertTileEnv(newDir)

        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try {
            Cert17mbtiles.run(OldSide(oldStore)) { k, v -> oldMap[k] = v }
        } catch (e: Exception) {
            w.write("MBTILES\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); oldStore.certTeardown(); return
        } finally { oldStore.certTeardown() }
        try {
            Cert17mbtiles.run(Cert17mbtiles.newSide(newEnv)) { k, v -> newMap[k] = v }
        } catch (e: Exception) {
            w.write("MBTILES\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); newEnv.teardown(); return
        } finally { newEnv.teardown() }

        var same = 0
        var diff = 0
        var expected = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            val whyExp = Cert17mbtiles.EXPECTED_DIFF[k]
            if (o == n) {
                same++; w.write("MBTILES\t$k\tSAME\t${esc(o)}\n")
                if (whyExp != null) w.write("# MBTILES-NOTE\tEXPECTED_DIFF[$k] is STALE: OLD and NEW now agree ($whyExp)\n")
            } else if (whyExp != null) {
                expected++
                w.write("MBTILES\t$k\tEXPECTED\t${esc(o)} | ${esc(n)}  [$whyExp]\n")
                w.write("MBTILES\t$k|newonly\tSAME\t${esc(n)}\n")
            } else { diff++; w.write("MBTILES\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# MBTILES done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
