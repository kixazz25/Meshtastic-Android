package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.mbtiles.GtTileStoreEnv
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MBTILESIPAD-2026-11-14 !! TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * Phase 2 pass 3 (MBTilesStore) on the iPad simulator, VALUE FOR VALUE with the Droid's
 * (gt17mbtiles_droid.txt).
 *
 * !! NOTHING IS REIMPLEMENTED HERE. The NEW side, the fixtures, the driving sequence and every
 * formatter are Cert17mbtiles' -- the same common code the Droid ran. Only the directory differs.
 *
 * !! auto_vacuum IS READ AND REPORTED SEPARATELY. reclaimableBytes is freelist_count * page_size,
 * and the two platforms do not run the same SQLite (Android = AndroidSQLiteDriver, the OS's own;
 * here = BundledSQLiteDriver). If auto_vacuum differs then reclaimableBytes differs for a reason
 * that is the STORAGE ENGINE, not the port -- so it is called out rather than buried in a DIFF list.
 */
class GtCertMBTiles17MacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** The same platform half the Droid harness writes, on this machine's filesystem. */
    private class MacTileEnv(private val dir: String) : GtTileStoreEnv {
        private val fs = FileSystem.SYSTEM
        private val handles = HashMap<String, SQLiteConnection>()
        private fun path(type: String) = "$dir/$type.mbtiles"

        override fun connection(type: String): SQLiteConnection? {
            handles[type]?.let { return it }
            return try {
                fs.createDirectories(dir.toPath())
                val fresh = !fs.exists(path(type).toPath())
                val c = gtOpenDatabase(path(type), false)
                ensureSchema(c)
                if (fresh) initMetadata(c, type)
                handles[type] = c
                c
            } catch (e: Exception) { null }
        }
        override fun storeExists(type: String): Boolean = fs.exists(path(type).toPath())
        override fun deleteStore(type: String): Boolean {
            handles.remove(type)?.let { try { it.close() } catch (e: Exception) { } }
            var ok = true
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                val p = (path(type) + suffix).toPath()
                if (fs.exists(p)) try { fs.delete(p) } catch (e: Exception) { ok = false }
            }
            return ok
        }
        private fun exec(c: SQLiteConnection, sql: String) { val st = c.prepare(sql); try { st.step() } finally { st.close() } }
        private fun ensureSchema(c: SQLiteConnection) {
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
        fun teardown() { for ((_, c) in handles) try { c.close() } catch (e: Exception) { }; handles.clear() }
    }

    @Test
    fun mbtiles() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt17mbtiles_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GT17MBTILES mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>()
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("MBTILES\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size == 4 && p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")

        val dir = "$outDir/mac17mbtiles_tmp"
        try { fs.deleteRecursively(dir.toPath()) } catch (e: Exception) { }
        fs.createDirectories(dir.toPath())
        val env = MacTileEnv(dir)
        val mine = LinkedHashMap<String, String>()
        try { Cert17mbtiles.run(Cert17mbtiles.newSide(env)) { k, v -> mine[k] = esc(v) } } finally { env.teardown() }

        fun lookup(k: String) = if (Cert17mbtiles.EXPECTED_DIFF.containsKey(k)) "$k|newonly" else k

        var same = 0
        val engine = ArrayList<String>()
        val real = ArrayList<String>()
        for ((k, v) in mine) {
            val d = droid[lookup(k)]
            if (d == v) { same++; continue }
            // auto_vacuum and anything derived from it are STORAGE ENGINE facts, not port defects
            if (k.startsWith("autoVacuum|") || k.startsWith("reclaimable|")) engine.add("$k  droid=$d  mac=$v")
            else real.add("$k  droid=$d  mac=$v")
        }
        val extra = droid.keys - mine.keys.map { lookup(it) }.toSet()
        for (s in engine) println("GT17MBTILES mac: ENGINE-FACT $s")
        for (s in real) println("GT17MBTILES mac: DIFF $s")
        if (extra.isNotEmpty()) println("GT17MBTILES mac: DIFF the Droid has keys the iPad did not run: $extra")
        println("GT17MBTILES mac: $same SAME, ${real.size + extra.size} DIFF, ${engine.size} ENGINE-FACT, cross-device COMPARED -- done")
        assertEquals(0, real.size + extra.size, "the iPad's tile-store decisions differ from the Droid's")
    }
}
