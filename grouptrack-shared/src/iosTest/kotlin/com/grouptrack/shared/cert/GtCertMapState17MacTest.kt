package com.grouptrack.shared.cert

import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IPADTEST-2026-11-14 !! TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * Phase 2 pass 1 (MapStateStore) on the iPad simulator, VALUE FOR VALUE with the Droid's
 * (gt17mapstate_droid.txt).
 *
 * !! THERE IS NO INPUT TO CARRY ACROSS, unlike G1c. G1c had to ship map_sources.json in the report
 * because the iPad has no APK; here every fixture is a string literal in Cert17mapstate, so both
 * devices already run byte-identical input by construction. What the report carries is the Droid's
 * ANSWERS, which is all this test needs.
 *
 * !! THE NEW SIDE IS NOT REIMPLEMENTED HERE. It is Cert17mapstate.newSide, the same common code the
 * Droid ran, over this machine's own throwaway directory -- which is the only thing that differs
 * between the two runs. A second implementation, or a second formatter, would make a difference
 * between two TEST FILES read as a difference between two DEVICES.
 */
class GtCertMapState17MacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private class MacFiles : GtFiles {
        override val fs: FileSystem = FileSystem.SYSTEM
        override fun setLastModified(path: Path, epochMs: Long): Boolean = false
        override fun lastModified(path: Path): Long = fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L
        override fun list(dir: Path): List<Path> = try { fs.list(dir) } catch (e: Exception) { emptyList() }
    }

    /** This run's own root and nothing else -- no real path is reachable through this env. */
    private class MacPaths(private val root: Path) : GtImportPaths {
        override fun groupTrackRoot(): Path = root
        override fun tracksDir(): Path = root / "my_tracks"
        override fun dir(name: String): Path = root / name
        override fun cacheDir(): Path = root / "cache"
        override fun filesDir(): Path = root / "files"
    }

    @Test
    fun mapState() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt17mapstate_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GT17MAPSTATE mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }

        val droid = LinkedHashMap<String, String>()
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (!l.startsWith("MAPSTATE\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size == 4 && p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")

        // this machine's own throwaway root, wiped first so a previous run cannot seed this one
        val root = "$outDir/mac17mapstate_tmp".toPath()
        try { fs.deleteRecursively(root) } catch (e: Exception) { }
        fs.createDirectories(root)

        val mine = LinkedHashMap<String, String>()
        Cert17mapstate.run(Cert17mapstate.newSide(MacFiles(), MacPaths(root)), Cert17mapstate.MAP_KEY) { k, v -> mine[k] = esc(v) }

        // !! For a key in EXPECTED_DIFF the Droid's SAME line is "<key>|newonly" (OLD and NEW are known
        // to differ there, so the base key is recorded as EXPECTED). The iPad still compares NEW to
        // NEW -- an expected OLD/NEW divergence is no reason to stop checking the two devices agree.
        fun lookup(k: String) = if (Cert17mapstate.EXPECTED_DIFF.containsKey(k)) "$k|newonly" else k

        var same = 0
        var bad = 0
        for ((k, v) in mine) {
            val d = droid[lookup(k)]
            if (d == v) same++
            else { bad++; println("GT17MAPSTATE mac: DIFF ${lookup(k)}\n  droid=${d?.take(400)}\n  mac  =${v.take(400)}") }
        }
        val extra = droid.keys - mine.keys.map { lookup(it) }.toSet()
        if (extra.isNotEmpty()) { bad += extra.size; println("GT17MAPSTATE mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GT17MAPSTATE mac: $same SAME, $bad DIFF, cross-device COMPARED -- done")
        assertEquals(0, bad, "the iPad's map-state decisions differ from the Droid's")
    }
}
