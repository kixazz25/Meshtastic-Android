package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.ConvoyMapRecipeCertOld
import com.grouptrack.shared.cert.Cert17maprecipe
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.text.GtLocalTime
import com.grouptrack.shared.text.GtTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Writer

/**
 * GTCERT17MAPRECIPE-2026-11-14 (2.8 step 17, Phase 2 pass 2, Droid side) !! TEST-ONLY
 * (CODE RULE 3: remove with GtCertRun before 2.8 is released).
 *
 * OLD = 2.7e's ConvoyMapRecipe as real Android code over real org.json, real java.lang.Math and
 * real SimpleDateFormat (ConvoyMapRecipeCertOld, 26-item counted manifest).
 * NEW = Cert17maprecipe.newSide, which is COMMON code -- the pass 1 lesson applied from the start,
 * so the iPad runs the same bytes and only its throwaway directory differs.
 *
 * !! THE ORG.JSON READ-BACK LIVES HERE, NOT IN CertOld. 2.7e never reads a recipe back (the restore
 * was never built), so a read-back function is CERT SCAFFOLDING and does not belong in a file whose
 * whole claim is that it is a counted copy of 2.7e. Each side reads with its own library and both
 * flatten through Cert17maprecipe's shared formatters: the PARSE must differ, the FORMATTING must not.
 *
 * NOTHING LIVE IS READ OR WRITTEN. Each side gets its own root under
 * app.cacheDir/gtcert17maprecipe/{old,new}, and CertPaths exposes no live path at all. The clock is
 * pinned to Cert17maprecipe.NOW in UTC on both sides.
 *
 * SIMULATED FIRST (sim_maprecipe_v2_2026-11-14): 12 fixtures x 2 entry points = 24 scenarios, every
 * one terminates, 182 compared keys, no collisions. The simulation caught one of its own fixtures
 * seeding a typed date that did not match the pinned clock.
 *
 * Lines: MAPRECIPE <tab> key <tab> SAME|EXPECTED|DIFF <tab> value
 * Footer: "# MAPRECIPE done: N SAME, N DIFF, N EXPECTED in S s".
 */
object GtCert17maprecipe {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private class CertFiles : GtFiles {
        override val fs: FileSystem = FileSystem.SYSTEM
        override fun setLastModified(path: Path, epochMs: Long): Boolean = File(path.toString()).setLastModified(epochMs)
        override fun lastModified(path: Path): Long = File(path.toString()).lastModified()
        override fun list(dir: Path): List<Path> = (File(dir.toString()).listFiles() ?: emptyArray()).map { it.toOkioPath() }
    }

    private class CertPaths(private val root: Path, private val cache: Path) : GtImportPaths {
        override fun groupTrackRoot(): Path = root
        override fun tracksDir(): Path = root / "my_tracks"
        override fun dir(name: String): Path = root / name
        override fun cacheDir(): Path = cache
        override fun filesDir(): Path = root / "files"
    }

    /**
     * Pinned, and UTC on purpose -- see ConvoyMapRecipeCertOld substitution 12. Both sides see the
     * same instant AND the same calendar breakdown of it, so what is compared is the formatting.
     */
    private object CertClock : GtClock {
        override fun nowInstant(): String = java.time.Instant.ofEpochMilli(Cert17maprecipe.NOW).toString()
        /** PINNEDLOCAL-2026-11-14: the SHARED constant, so the iPad cannot drift from the Droid. */
        override fun localNow(): GtLocalTime = Cert17maprecipe.PINNED_LOCAL
        override fun epochMs(): Long = Cert17maprecipe.NOW

        /**
         * PINNEDLOCAL-2026-11-14: prove the constant IS NOW's UTC breakdown. The iPad has no java.time and
         * must trust the constant; the Droid has java.time and can check it, so it does. A change to
         * NOW without a change to PINNED_LOCAL fails here instead of producing a cross-device DIFF
         * that looks like a port defect.
         */
        fun selfCheck(): String? {
            val u = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(Cert17maprecipe.NOW), java.time.ZoneOffset.UTC)
            val p = Cert17maprecipe.PINNED_LOCAL
            val ok = u.year == p.year && u.monthValue == p.month && u.dayOfMonth == p.day &&
                u.hour == p.hour && u.minute == p.minute && u.second == p.second
            return if (ok) null else "PINNED_LOCAL $p is not NOW's UTC breakdown ($u)"
        }
    }

    // ------------------------------------------------------------------ OLD (org.json; cannot move)
    private class OldSide(private val files: GtFiles, private val paths: GtImportPaths) : Cert17maprecipe.Side {

        override fun run(fx: Cert17maprecipe.Fx, fresh: Boolean): Cert17maprecipe.Out {
            val dir = paths.dir("recipes")
            try { files.fs.deleteRecursively(dir) } catch (e: Exception) { }
            files.fs.createDirectories(dir)
            for ((name, ms) in fx.seed) {
                files.fs.write(dir / name) { writeUtf8("{}") }
            }
            // FIXTURETIMES-2026-11-14: the SHARED overlay, so both devices run prune on identical mtimes
            val fxFiles = Cert17maprecipe.overlay(files, fx.seed.toMap())
            val store = ConvoyMapRecipeCertOld(fxFiles, paths, CertClock, Cert17maprecipe.FxEnv(fx))
            val ret = if (fresh) store.writeFresh() else store.writeDaily()

            // READ IT BACK WITH org.json -- this side's own library -- and flatten through the
            // SHARED formatters. Identical shape to the NEW side's kotlinx read, including the
            // "$i.$j=" box prefix: the two must agree character for character.
            var version = "null"; var created = "null"; var slotCount = "null"
            val slots = ArrayList<String>(); val boxes = ArrayList<String>()
            val text = ret?.let { try { fxFiles.fs.read(it) { readUtf8() } } catch (e: Exception) { null } }
            if (text != null) try {
                val root = JSONObject(text)
                // FIXTURETIMES-2026-11-14: ABSENT reports "null", matching the NEW side. org.json's
                // optInt default (-1) is a property of org.json, not of the document.
                version = if (root.has("version")) root.optInt("version", -1).toString() else "null"
                created = root.optString("created", "null")
                val arr = root.optJSONArray("slots") ?: JSONArray()
                slotCount = arr.length().toString()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val bx = o.optJSONArray("boxes") ?: JSONArray()
                    slots.add(Cert17maprecipe.slotLine(
                        o.optString("slot", ""), o.optString("source", ""),
                        o.optInt("zMin", -1), o.optInt("zMax", -1), o.optLong("tiles", -1L), bx.length()))
                    for (j in 0 until bx.length()) {
                        val b = bx.optJSONObject(j) ?: continue
                        boxes.add("$i.$j=" + Cert17maprecipe.boxLine(
                            b.optDouble("n", 0.0), b.optDouble("s", 0.0),
                            b.optDouble("w", 0.0), b.optDouble("e", 0.0), b.optLong("tiles", -1L)))
                    }
                }
            } catch (e: Exception) { version = "PARSE-FAILED" }
            val listing = try { fxFiles.list(dir).map { it.name }.sorted().joinToString(",") } catch (e: Exception) { "?" }
            return Cert17maprecipe.Out(ret?.name ?: "null", version, created, slotCount, slots, boxes, listing)
        }
    }

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val base = File(ctx.cacheDir, "gtcert17maprecipe")
        base.deleteRecursively()
        val oldRoot = File(base, "old").also { it.mkdirs() }
        val newRoot = File(base, "new").also { it.mkdirs() }
        val cache = File(base, "cache").also { it.mkdirs() }
        val files = CertFiles()
        // both devices derive this from the SAME pinned instant in UTC, so nothing has to be carried
        CertClock.selfCheck()?.let {
            w.write("MAPRECIPE\tPINNED-CLOCK\tDIFF\t${esc(it)}\n"); w.flush(); return
        }
        val today = GtTime.local(CertClock.localNow(), "yyyy-MM-dd")
        w.write("# MAPRECIPE-TODAY\t$today\n")

        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try {
            Cert17maprecipe.run(OldSide(files, CertPaths(oldRoot.toOkioPath(), cache.toOkioPath())), today) { k, v -> oldMap[k] = v }
        } catch (e: Exception) { w.write("MAPRECIPE\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try {
            Cert17maprecipe.run(
                Cert17maprecipe.newSide(files, CertPaths(newRoot.toOkioPath(), cache.toOkioPath()), CertClock), today
            ) { k, v -> newMap[k] = v }
        } catch (e: Exception) { w.write("MAPRECIPE\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0
        var diff = 0
        var expected = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            val whyExp = Cert17maprecipe.EXPECTED_DIFF[k]
            if (o == n) {
                same++; w.write("MAPRECIPE\t$k\tSAME\t${esc(o)}\n")
                if (whyExp != null) w.write("# MAPRECIPE-NOTE\tEXPECTED_DIFF[$k] is STALE: OLD and NEW now agree ($whyExp)\n")
            } else if (whyExp != null) {
                expected++
                w.write("MAPRECIPE\t$k\tEXPECTED\t${esc(o)} | ${esc(n)}  [$whyExp]\n")
                w.write("MAPRECIPE\t$k|newonly\tSAME\t${esc(n)}\n")
            } else { diff++; w.write("MAPRECIPE\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# MAPRECIPE done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
