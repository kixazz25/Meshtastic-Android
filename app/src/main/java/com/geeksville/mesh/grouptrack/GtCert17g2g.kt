package com.geeksville.mesh.grouptrack

import android.content.Context
import com.grouptrack.shared.cert.Cert17g2g
import java.io.Writer

/**
 * STEP17G2G-2026-11-13 (2.8 step 17, G2g cert, Droid side) ⚠ TEST-ONLY
 * (CODE RULE 3: remove with GtCertRun before 2.8 is released).
 *
 * OLD = 2.7e's GtHelp as real Android code over real org.json (GtHelpCertOld, counted manifest).
 * NEW = the shared GtHelpLinks over GtOrgJson. Both read the same fixture text.
 *
 * Lines: G2G <tab> key <tab> SAME|DIFF <tab> value; footer "# G2G done: N SAME, N DIFF in S s".
 */
object GtCert17g2g {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** OLD: 2.7e, real org.json. */
    private class OldSide : Cert17g2g.Side {
        override fun reset() { GtHelpCertOld.resetForCert(); GtHelpCertLog.reset() }
        override fun load(text: String, missing: Boolean) = GtHelpCertOld.load(Cert17g2g.TextAssets(text, missing))
        override fun size(): Int = GtHelpCertOld.size()
        override fun has(id: String): Boolean = GtHelpCertOld.has(id)
        override fun linkFor(id: String): String = GtHelpCertOld.linkFor(id)
    }

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try { Cert17g2g.run(OldSide()) { k, v -> oldMap[k] = v } }
        catch (e: Exception) { w.write("G2G\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try { Cert17g2g.run(Cert17g2g.NewSide()) { k, v -> newMap[k] = v } }
        catch (e: Exception) { w.write("G2G\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0
        var diff = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            if (o == n) { same++; w.write("G2G\t$k\tSAME\t${esc(o)}\n") }
            else { diff++; w.write("G2G\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# G2G done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
