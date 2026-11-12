package com.grouptrack.shared.cert

import com.grouptrack.shared.imports.GtFiles
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ptr
import platform.posix.stat
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.timeval
import platform.posix.utimes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G2E1-2026-11-12 ⚠ TEST-ONLY: a state's kept data on the iPad simulator, VALUE FOR VALUE with the Droid's (gt17e_droid.txt):
 * the same Cert17e1 script on the shared GtStateData, on its own root, the same clock, files dated through NSFileManager.
 */
@OptIn(ExperimentalForeignApi::class)
class GtStateDataMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    // FIX17E1MTIME-2026-11-12: POSIX utimes, exact to the microsecond (NSDate put every date 1.777 s late)
    private fun setMtime(p: Path, ms: Long): Boolean = memScoped {
        val tv = allocArray<timeval>(2)
        for (i in 0..1) { tv[i].tv_sec = ms / 1000; tv[i].tv_usec = ((ms % 1000) * 1000).toInt() }
        utimes(p.toString(), tv) == 0
    }
    // FIX17E1MTIMEREAD-2026-11-12: a file's date by POSIX stat -- okio's lastModifiedAtMillis on Apple adds seconds/1e6 ms (1777 ms today)
    private fun mtimeOf(p: Path): Long = memScoped {
        val st = alloc<stat>()
        if (stat(p.toString(), st.ptr) != 0) 0L else st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L
    }

    @Test
    fun stateData() {
        val sys = FileSystem.SYSTEM; val rp = "$outDir/gt17e_droid.txt".toPath()
        if (!sys.exists(rp)) { println("GTE17C mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>()
        sys.read(rp) { while (true) { val l = readUtf8Line() ?: break
            if (!l.startsWith("E17C\t")) continue
            val p = l.split('\t', limit = 4); if (p.size == 4 && p[2] == "SAME") droid[p[1]] = p[3] } }
        val files = object : GtFiles {
            override val fs: FileSystem = sys
            override fun setLastModified(path: Path, epochMs: Long): Boolean = setMtime(path, epochMs)
            override fun lastModified(path: Path): Long = mtimeOf(path)
            override fun list(dir: Path): List<Path> = sys.list(dir)
        }
        val mine = LinkedHashMap<String, String>()
        Cert17e1.run(Cert17e1.NewSide(sys, "$outDir/mac17e_work".toPath(), files, Cert17e1.MemRun()), sys, ::setMtime, ::mtimeOf) { k, v -> mine[k] = esc(v) }
        var same = 0; var bad = 0
        for ((k, v) in mine) { val d = droid[k]; if (d == v) same++ else { bad++; println("GTE17C mac: DIFF $k\n  droid=${d?.take(400)}\n  mac  =${v.take(400)}")
            // FIX17E1DIFFPRINT-2026-11-12: the lines only one side has
            val dl = (d ?: "").split("\\n"); val ml = v.split("\\n")
            for (x in dl) if (x !in ml) println("  droid only: $x")
            for (x in ml) if (x !in dl) println("  mac only:   $x") } }
        val extra = droid.keys - mine.keys
        if (extra.isNotEmpty()) { bad += extra.size; println("GTE17C mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GTE17C mac: $same SAME, $bad DIFF, cross-device COMPARED -- done")
        assertEquals(0, bad, "the iPad's state data differs from the Droid's")
    }
}
