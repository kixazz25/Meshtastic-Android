package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.StateCertOldEnv
import com.geeksville.mesh.convoy.StateDataCertOld
import com.grouptrack.shared.cert.Cert17e1
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.state.GtStateData
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.json.JSONObject
import java.io.File

/**
 * STEP17G2E1-2026-11-12 (2.8 step 17, G2e-1) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): a state's kept
 * data, OLD (2.7e, the generated StateDataCertOld) = NEW (shared GtStateData) on one script (Cert17e1), each side on its OWN root in
 * cacheDir with the same fixed clock. The rider's GroupTrack/states and osm folders are never touched. Request mode "state17e1".
 * Lines: E17C <tab> key <tab> SAME|DIFF <tab> value; footer "# E17C done: N SAME, N DIFF in S s".
 */
object GtCert17e1 {
    private const val TAG = "CertRun"
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private fun setMtime(p: Path, ms: Long): Boolean = File(p.toString()).setLastModified(ms)
    private val files = object : GtFiles {
        override val fs: FileSystem = FileSystem.SYSTEM
        override fun setLastModified(path: Path, epochMs: Long): Boolean = File(path.toString()).setLastModified(epochMs)
        override fun lastModified(path: Path): Long = File(path.toString()).lastModified()
        override fun list(dir: Path): List<Path> = File(dir.toString()).listFiles()?.map { it.toOkioPath() } ?: emptyList()
    }

    private class OldSide(private val app: Context, private val dir: File) : Cert17e1.Side {
        override val root: Path = dir.toOkioPath()
        private fun f(p: File): Path = p.toOkioPath()
        override fun reset() { dir.deleteRecursively(); dir.mkdirs(); StateCertOldEnv.root = dir; StateCertOldEnv.now = Cert17e1.NOW }
        override fun landFile(folder: String, code: String) = f(StateDataCertOld.landFile(folder, code))
        override fun landPages(folder: String, code: String) = f(StateDataCertOld.landPages(folder, code))
        override fun agencyFile(slug: String, sourceId: String) = f(StateDataCertOld.agencyFile(slug, sourceId))
        override fun has(slug: String) = StateDataCertOld.has(app, slug)
        override fun isRefresh(slug: String) = StateDataCertOld.isRefresh(slug)
        override fun endRun() = StateDataCertOld.endRun()
        override fun writeRecords(rows: List<GtStateData.Row>) = StateDataCertOld.writeRecords(app, rows.map {
            JSONObject().put("state_slug", it.stateSlug).put("fetch_kind", it.fetchKind).put("name", it.name).put("dest", it.dest).put("status", it.status) })
        override fun downloadedMs(slug: String) = StateDataCertOld.downloadedMs(app, slug)
        override fun ageDays(slug: String) = StateDataCertOld.ageDays(app, slug)
        override fun sizeMb(slug: String) = StateDataCertOld.sizeMb(app, slug)
        override fun staleSlugs(slugs: List<String>) = StateDataCertOld.staleSlugs(app, slugs)
        override fun answer(slugs: List<String>, refresh: Boolean) = StateDataCertOld.answer(slugs, refresh)
        override fun restore(slug: String) = StateDataCertOld.restore(app, slug)
        override fun save(slug: String) = StateDataCertOld.save(app, slug)
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0
        w.write("# E17C droid ${android.os.Build.MODEL}: a state's kept data (StateData), OLD = NEW on one script, each side on its own root\n")
        val oldMap = LinkedHashMap<String, String>(); val newMap = LinkedHashMap<String, String>()
        val work = File(app.cacheDir, "gtcert17e1"); work.deleteRecursively()
        try {
            Cert17e1.run(OldSide(app, File(work, "old")), FileSystem.SYSTEM, ::setMtime, { File(it.toString()).lastModified() }) { k, v -> oldMap[k] = v }   // FIX17E1MTIMEREAD-2026-11-12
            Cert17e1.run(Cert17e1.NewSide(FileSystem.SYSTEM, File(work, "new").toOkioPath(), files, Cert17e1.MemRun()), FileSystem.SYSTEM, ::setMtime, { File(it.toString()).lastModified() }) { k, v -> newMap[k] = v }
            for (k in (oldMap.keys + newMap.keys)) {
                val o = oldMap[k]; val n = newMap[k]
                if (o == n) { same++; w.write("E17C\t$k\tSAME\t${esc(n ?: "")}\n") }
                else { diff++; w.write("E17C\t$k\tDIFF\n  OLD\t${o?.let { esc(it) } ?: "(missing)"}\n  NEW\t${n?.let { esc(it) } ?: "(missing)"}\n") }
            }
        } catch (e: Throwable) {
            diff++; w.write("# E17C: ABORTED $e\n"); Log.e(TAG, "GTCERT state17e1 aborted", e)
        } finally { work.deleteRecursively(); StateCertOldEnv.root = null }
        w.write("# E17C done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT state17e1: $same SAME, $diff DIFF")
    }
}
