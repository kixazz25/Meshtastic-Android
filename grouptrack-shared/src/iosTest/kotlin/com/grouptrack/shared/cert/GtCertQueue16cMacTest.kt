package com.grouptrack.shared.cert

import com.grouptrack.shared.db.gtOpenDatabase
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * STEP16C-2026-11-11 (2.8 step 16c) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the save-by-area writes on
 * the iPad simulator. Runs the SAME runner as the Droid (Cert16c.run with NewSide) on the SAME input: the tracks of $dataDir's spatial
 * database (the file gt_cert pushes to the Droid as cert15c_input), the recipes in $recipesIn (gt_cert_v24 pushes these to the Droid's
 * cert16c_input/recipes), and the download-queue boxes the Droid wrote into its report (input|qbox|...). Crafted folders are made in
 * $outDir/q16c_work. Writes $outDir/GroupTrack_Q16C_mac_<time>.txt ending "# Q16C mac done: …", prints "GTQ16C mac: …", fails on any DIFF.
 * VALUE FOR VALUE with the Droid's NEW (its report $outDir/gt16c_droid.txt, copied by gt_cert_v24): the input fingerprints (the
 * database, the tracks as parsed, every recipe, the zoom) must EQUAL the Droid's (else "cross-device NOT COMPARED" and DIFF); then every
 * value must equal. No tolerance. The math under it is GtMath (MATHTILE 16a); what this adds is the HashMap order, the JSON text, the
 * labels and the cells on the iPad.
 */
class GtCertQueue16cMacTest {
    private val spike = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data"
    private val dataDir = "$spike/live_2026-11-08"
    private val recipesIn = "$spike/droid1_2026-11-06/recipes"
    private val outDir = "$spike/cert"

    private fun sha256(fs: FileSystem, p: Path, say: (String) -> Unit): String {
        val hs = HashingSink.sha256(blackholeSink()); val out = hs.buffer(); var done = 0L; var chunks = 0
        fs.read(p) { val buf = okio.Buffer()
            while (true) { val n = read(buf, 1L shl 20); if (n < 0) break; done += n; out.write(buf, n); if (++chunks % 100 == 0) say("hashing ${p.name}: ${done shr 20} MB") } }
        out.flush()
        return hs.hash.hex()
    }

    @Test
    fun queueWrites() {
        val fs = FileSystem.SYSTEM
        val t0 = TimeSource.Monotonic.markNow()
        val srcS = "$dataDir/grouptrack_spatial.db".toPath(); val rec = recipesIn.toPath()
        if (!fs.exists(srcS) || !fs.exists(rec)) { println("GTQ16C mac: no data ($dataDir, $recipesIn) -- skipped"); return }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmm"; val stamp = f.stringFromDate(NSDate())
        val out = ArrayList<String>()
        fun put(s: String) { out.add(s); if (s.startsWith("#") || s.contains("\tDIFF\t")) println("GTQ16C mac: $s") }
        fun say(s: String) = println("GTQ16C mac: $s")

        // ---- the Droid's report: its NEW values ----
        val report = "$outDir/gt16c_droid.txt".toPath()
        val droid = LinkedHashMap<String, String>()
        if (fs.exists(report)) fs.read(report) {
            var pending: String? = null   // CODE RULE 1: null = the previous line was not a DIFF / EXPECTED head
            while (true) {
                val l = readUtf8Line() ?: break
                if (l.startsWith("Q16C\t")) {
                    val p = l.split('\t', limit = 4); if (p.size < 4) continue
                    droid[p[1]] = p[3]; pending = if (p[2] == "SAME") null else p[1]
                } else if (l.startsWith("  NEW\t") && pending != null) { droid[pending!!] = l.removePrefix("  NEW\t"); pending = null }
            }
        }
        put("# Q16C mac $stamp data=$dataDir recipes=$recipesIn")

        // ---- the input: computed here, compared with the Droid's ----
        val inputFp = LinkedHashMap<String, String>()
        inputFp["input|db|${srcS.name}"] = "len=${fs.metadata(srcS).size};sha256=${sha256(fs, srcS, ::say)}"
        val tracks = run { val c = gtOpenDatabase(srcS.toString(), true); try { Cert16c.readTracks(c) } finally { c.close() } }
        inputFp["input|tracks"] = Cert16c.tracksText(tracks)
        val recFiles = Cert16c.recipeFiles(fs, rec)
        for (p in recFiles) { val b = fs.read(p) { readByteString() }; inputFp["input|recipe|" + p.name] = "size=${b.size};sha256=" + b.sha256().hex() }
        inputFp["input|zoom"] = "DOWNLOAD_ZOOM=18"
        // the queue boxes come FROM the Droid (its download_queue.json, read there)
        val qboxes = droid.keys.filter { it.startsWith("input|qbox|") }.sorted().map { Cert16c.boxOf(droid.getValue(it)) }
        val work = "$outDir/q16c_work".toPath()
        val crafted = Cert16c.craft(fs, work / "craft")
        put("# Q16C mac: input: ${tracks.size} tracks, ${recFiles.size} recipes, ${qboxes.size} queue boxes (from the Droid report)")

        val mine = LinkedHashMap<String, String>()
        Cert16c.run(Cert16c.NewSide(fs), Cert16c.Inputs(tracks, rec, recFiles, qboxes, crafted), fs, { k, v -> mine[k] = Cert15d.esc(v) }, ::say)

        var same = 0; var bad = 0
        val inputsDiffer = ArrayList<String>()
        for ((k, v) in inputFp) if (droid[k] != v) inputsDiffer.add(k)
        for (k in droid.keys) if (k.startsWith("input|") && !k.startsWith("input|qbox|") && k !in inputFp) inputsDiffer.add(k)
        val cross = droid.isNotEmpty() && inputsDiffer.isEmpty()
        if (droid.isEmpty()) { bad++; put("Q16C\tdroid-report\tDIFF\tno Droid report at $report -- run gt_cert_v24 queue16c") }
        for (k in inputsDiffer) { bad++; put("Q16C\t$k\tDIFF\tthe iPad's input differs from the Droid's: droid=${droid[k]?.take(120)} mac=${inputFp[k]?.take(120)}") }
        if (droid.isNotEmpty()) {
            for ((k, v) in mine) {
                val d = droid[k]
                if (d == v) same++ else { bad++; put("Q16C\t$k\tDIFF\tdroid=${d?.take(300)}\tmac=${v.take(300)}") }
            }
            for (k in droid.keys) if (!k.startsWith("input|") && !k.endsWith(" (only OLD)") && k !in mine) { bad++; put("Q16C\t$k\tDIFF\tonly on the Droid") }
        }
        val secs = t0.elapsedNow().inWholeSeconds
        put("# Q16C mac done: $same SAME, $bad DIFF, 0 EXPECTED, cross-device ${if (cross) "COMPARED" else "NOT COMPARED"} in $secs s")
        val file = "$outDir/GroupTrack_Q16C_mac_$stamp.txt".toPath()
        fs.write(file) { writeUtf8(out.joinToString("\n") + "\n") }
        println("GTQ16C mac: report $file")
        assertEquals(0, bad, "Q16C: the iPad's NEW differs (see $file)")
    }
}
