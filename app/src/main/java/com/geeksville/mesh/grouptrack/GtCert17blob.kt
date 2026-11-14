package com.geeksville.mesh.grouptrack

import android.content.Context
import com.grouptrack.shared.cert.GtBlobProbe
import java.io.File
import java.io.Writer

/**
 * BLOB17-2026-11-14 (2.8 step 17, the pass 3 pre-flight) !! TEST-ONLY
 * (CODE RULE 3: remove with GtCertRun before 2.8 is released).
 *
 * NOT an OLD/NEW certification -- a PROBE, in the mathprobe / mathpow / mathtile tradition: it asks
 * what the shared SQLite driver actually DOES on this device, so pass 3 is not built on an
 * assumption. The iPad runs the identical common code and the two reports are compared.
 *
 * WHY IT EXISTS: pass 3 is 18 live MBTilesStore members over SQLite, and readTile / insertTile are
 * entirely BLOB I/O on the hottest path in the app. Measured 11-14: no blob has ever crossed the
 * shared driver anywhere in this project, and the two platforms do not run the same SQLite
 * (Android = AndroidSQLiteDriver, the OS's own; iPad = BundledSQLiteDriver, a fixed copy).
 *
 * Writes only app.cacheDir/gtcert17blob/probe.db, deleted first.
 * Lines: BLOB <tab> key <tab> value.   Footer: "# BLOB done: N keys in S s".
 */
object GtCert17blob {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val base = File(ctx.cacheDir, "gtcert17blob")
        base.deleteRecursively(); base.mkdirs()
        val db = File(base, "probe.db")
        var n = 0
        try {
            GtBlobProbe.run(db.absolutePath) { k, v -> n++; w.write("BLOB\t${esc(k)}\t${esc(v)}\n") }
        } catch (e: Exception) {
            w.write("BLOB\tPROBE-THREW\t${esc(e.toString())}\n")
        }
        w.write("# BLOB done: $n keys in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
