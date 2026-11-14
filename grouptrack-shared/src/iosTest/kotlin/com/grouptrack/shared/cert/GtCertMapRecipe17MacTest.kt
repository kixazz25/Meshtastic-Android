package com.grouptrack.shared.cert

import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.text.GtLocalTime
import com.grouptrack.shared.text.GtTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MAPRECIPEIPAD-2026-11-14 !! TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * Phase 2 pass 2 (ConvoyMapRecipe) on the iPad simulator, VALUE FOR VALUE with the Droid's
 * (gt17maprecipe_droid.txt).
 *
 * !! NOTHING IS REIMPLEMENTED HERE. The NEW side, the fixtures and both formatters are
 * Cert17maprecipe's -- the same common code the Droid ran. Only the throwaway directory differs.
 *
 * !! THE CLOCK IS THE SHARED Cert17maprecipe.PINNED_LOCAL. java.time does not exist here, so this
 * machine cannot derive NOW's UTC breakdown the way the Droid does; the Droid asserts the constant
 * is correct (CertClock.selfCheck) and this side trusts it. Deriving it twice is exactly the drift
 * that would read as a port defect.
 *
 * !! EVERY BOX COORDINATE IS COMPARED AS BITS AND AS TEXT (Cert17maprecipe.boxLine). Kotlin's
 * Double.toString() is platform-specific, so bits agreeing while text differs is a FORMATTING
 * finding, not a math one -- and the two want different fixes.
 */
class GtCertMapRecipe17MacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private class MacFiles : GtFiles {
        override val fs: FileSystem = FileSystem.SYSTEM
        override fun setLastModified(path: Path, epochMs: Long): Boolean = false
        override fun lastModified(path: Path): Long = fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L
        override fun list(dir: Path): List<Path> = try { fs.list(dir) } catch (e: Exception) { emptyList() }
    }

    private class MacPaths(private val root: Path) : GtImportPaths {
        override fun groupTrackRoot(): Path = root
        override fun tracksDir(): Path = root / "my_tracks"
        override fun dir(name: String): Path = root / name
        override fun cacheDir(): Path = root / "cache"
        override fun filesDir(): Path = root / "files"
    }

    private object MacClock : GtClock {
        override fun nowInstant(): String = GtTime.instantTextMs(Cert17maprecipe.NOW)
        override fun localNow(): GtLocalTime = Cert17maprecipe.PINNED_LOCAL
        override fun epochMs(): Long = Cert17maprecipe.NOW
    }

    @Test
    fun mapRecipe() {
        val fs = FileSystem.SYSTEM
        val rp = "$outDir/gt17maprecipe_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GT17MAPRECIPE mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }

        val droid = LinkedHashMap<String, String>()
        var droidToday: String? = null
        fs.read(rp) {
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("# MAPRECIPE-TODAY\t")) { droidToday = l.substringAfter('\t'); continue }
                if (!l.startsWith("MAPRECIPE\t")) continue
                val p = l.split('\t', limit = 4)
                if (p.size == 4 && p[2] == "SAME") droid[p[1]] = p[3]
            }
        }
        assertTrue(droid.isNotEmpty(), "the Droid report has no SAME lines -- OLD and NEW must agree on the Droid first")

        // !! the Droid's own today, read back. Both derive it from the same pinned constant, so this
        // is a CHECK, not a transfer: if they disagree the pinned clock drifted and every date key
        // would DIFF for a reason that is not the port.
        val mine = GtTime.local(MacClock.localNow(), "yyyy-MM-dd")
        assertEquals(droidToday, mine, "the Droid and the iPad disagree on the pinned date -- PINNED_LOCAL drifted")

        val root = "$outDir/mac17maprecipe_tmp".toPath()
        try { fs.deleteRecursively(root) } catch (e: Exception) { }
        fs.createDirectories(root)

        val out = LinkedHashMap<String, String>()
        Cert17maprecipe.run(Cert17maprecipe.newSide(MacFiles(), MacPaths(root), MacClock), mine) { k, v -> out[k] = esc(v) }

        fun lookup(k: String) = if (Cert17maprecipe.EXPECTED_DIFF.containsKey(k)) "$k|newonly" else k

        var same = 0
        var bad = 0
        var bitsOkTextBad = 0
        for ((k, v) in out) {
            val d = droid[lookup(k)]
            if (d == v) { same++; continue }
            bad++
            // separate the two failure modes the box lines are built to distinguish
            if (d != null && k.contains("|box")) {
                val db = d.substringBefore("|text="); val mb = v.substringBefore("|text=")
                if (db == mb) {
                    bitsOkTextBad++
                    println("GT17MAPRECIPE mac: FORMATTING-ONLY ${lookup(k)} -- the bits agree, the decimal text does not\n  droid=$d\n  mac  =$v")
                    continue
                }
            }
            println("GT17MAPRECIPE mac: DIFF ${lookup(k)}\n  droid=${d?.take(400)}\n  mac  =${v.take(400)}")
        }
        val extra = droid.keys - out.keys.map { lookup(it) }.toSet()
        if (extra.isNotEmpty()) { bad += extra.size; println("GT17MAPRECIPE mac: DIFF the Droid has keys the iPad did not run: $extra") }
        if (bitsOkTextBad > 0) println("GT17MAPRECIPE mac: $bitsOkTextBad key(s) differ in DECIMAL TEXT ONLY -- Double.toString differs across platforms; the computed values are identical")
        println("GT17MAPRECIPE mac: $same SAME, $bad DIFF, cross-device COMPARED -- done")
        assertEquals(0, bad, "the iPad's map-recipe decisions differ from the Droid's")
    }
}
