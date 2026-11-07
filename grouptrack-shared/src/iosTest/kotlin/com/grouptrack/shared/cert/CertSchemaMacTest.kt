package com.grouptrack.shared.cert

import com.grouptrack.shared.db.GtSchema
import com.grouptrack.shared.db.gtOpenDatabase
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test

/**
 * STEP12-2026-11-07: the shared schema (GtSchema) on the iPad simulator, three checks, nothing on the Droids touched:
 *  FRESH   -- a new pair of databases built by GtSchema has exactly Droid 1's structure (the 11-06 copy): every table, column, index.
 *  UPGRADE -- GtSchema on a COPY of Droid 1's 2.7e databases changes no structure and no row count; both end at version 1.
 *  AGAIN   -- running it a second time changes nothing (the steps are "if missing").
 * Writes $GT_SPIKE/_data/cert/GroupTrack_SCHEMA_mac_<time>.txt. Run: gt_schemacheck_2026-11-07.sh. TEST-ONLY.
 */
class CertSchemaMacTest {
    private val dataDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/droid1_2026-11-06"
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private val assets = "/Users/kixazz25/Projects/Meshtastic-Android/app/src/main/assets"

    @Test
    fun schema() {
        val fs = FileSystem.SYSTEM
        if (!fs.exists("$dataDir/grouptrack_spatial.db".toPath())) { println("GTSCHEMA mac: no data at $dataDir -- skipped"); return }
        val read: (String) -> String = { n -> fs.read("$assets/$n".toPath()) { readUtf8() } }
        val tmp = "$outDir/schema_tmp"; fs.deleteRecursively(tmp.toPath()); fs.createDirectories(tmp.toPath())
        val stamp = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd_HHmm" }.stringFromDate(NSDate())
        val out = "$outDir/GroupTrack_SCHEMA_mac_$stamp.txt".toPath()
        val lines = ArrayList<String>()
        fun compare(tag: String, db: String, a: List<String>, b: List<String>) {
            val onlyA = a - b.toSet(); val onlyB = b - a.toSet()
            lines.add("SCHEMA|$tag|$db|a=${a.size}|b=${b.size}|onlyA=${onlyA.size}|onlyB=${onlyB.size}|" + if (onlyA.isEmpty() && onlyB.isEmpty()) "IDENTICAL" else "DIFFERENT")
            onlyA.forEach { lines.add("  A-ONLY|$tag|$db|$it") }; onlyB.forEach { lines.add("  B-ONLY|$tag|$db|$it") }
        }
        // live = Droid 1's copy, read-only
        val ls = gtOpenDatabase("$dataDir/grouptrack_spatial.db", true); val ld = gtOpenDatabase("$dataDir/grouptrack_data.db", true)
        val liveS = CertSchema.dump(ls, true); val liveD = CertSchema.dump(ld, true); val liveSn = CertSchema.dump(ls, false); val liveDn = CertSchema.dump(ld, false); ls.close(); ld.close()
        // FRESH: A = built by GtSchema, B = Droid 1
        val fsC = gtOpenDatabase("$tmp/fresh_spatial.db", false); val fdC = gtOpenDatabase("$tmp/fresh_data.db", false)
        val v = GtSchema.ensure(fsC, fdC, read)
        compare("FRESH", "spatial", CertSchema.dump(fsC, false), liveSn); compare("FRESH", "data", CertSchema.dump(fdC, false), liveDn)
        lines.add("VERSION|FRESH|$v"); fsC.close(); fdC.close()
        // UPGRADE: A = after GtSchema on a copy of Droid 1, B = before
        fs.copy("$dataDir/grouptrack_spatial.db".toPath(), "$tmp/up_spatial.db".toPath()); fs.copy("$dataDir/grouptrack_data.db".toPath(), "$tmp/up_data.db".toPath())
        val us = gtOpenDatabase("$tmp/up_spatial.db", false); val ud = gtOpenDatabase("$tmp/up_data.db", false)
        val cS = CertSchema.counts(us); val cD = CertSchema.counts(ud)
        val v2 = GtSchema.ensure(us, ud, read)
        compare("UPGRADE", "spatial", CertSchema.dump(us, true), liveS); compare("UPGRADE", "data", CertSchema.dump(ud, true), liveD)
        val rowsSame = CertSchema.counts(us) == cS && CertSchema.counts(ud) == cD
        lines.add("ROWS|UPGRADE|${if (rowsSame) "UNCHANGED" else "CHANGED"}|spatial ${cS.values.sum()} rows, data ${cD.values.sum()} rows")
        lines.add("VERSION|UPGRADE|$v2")
        // AGAIN: a second run changes nothing
        val dS = CertSchema.dump(us, true); val dD = CertSchema.dump(ud, true)
        val v3 = GtSchema.ensure(us, ud, read)
        compare("AGAIN", "spatial", CertSchema.dump(us, true), dS); compare("AGAIN", "data", CertSchema.dump(ud, true), dD)
        lines.add("VERSION|AGAIN|$v3")
        us.close(); ud.close(); fs.deleteRecursively(tmp.toPath())
        fs.write(out) { writeUtf8("# GTSCHEMA mac $stamp live=$dataDir assets=$assets\n"); lines.forEach { writeUtf8("$it\n") } }
        lines.filter { !it.startsWith("  ") }.forEach { println("GTSCHEMA mac: $it") }
        println("GTSCHEMA mac done -> $out")
    }
}
