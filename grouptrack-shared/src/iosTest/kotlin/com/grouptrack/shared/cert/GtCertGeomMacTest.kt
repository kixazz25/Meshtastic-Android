package com.grouptrack.shared.cert

import com.grouptrack.shared.db.gtOpenDatabase
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GEOMONE-2026-11-09 ⚠ TEST-ONLY: THE ONE geometry module (GtGeom) on the iPad simulator, against every stored row of the Mac's copy of
 * Droid 1's live databases (pulled 11-08 17:33, opened READ-ONLY). Same check as the Droid's (CertGeom). Writes
 * $GT_SPIKE/_data/cert/GroupTrack_GEOM_mac_<time>.txt, prints "GTGEOM mac: ..." and fails on any DIFF. No copy -> "skipped".
 */
class GtCertGeomMacTest {
    private val dataDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/live_2026-11-08"
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun everyStoredRow() {
        val fs = FileSystem.SYSTEM
        val spatial = "$dataDir/grouptrack_spatial.db"
        if (!fs.exists(spatial.toPath())) { println("GTGEOM mac: no data at $dataDir -- skipped"); return }
        val f = NSDateFormatter(); f.dateFormat = "yyyy-MM-dd_HHmm"; val stamp = f.stringFromDate(NSDate())
        val out = "$outDir/GroupTrack_GEOM_mac_$stamp.txt".toPath()
        val conn = gtOpenDatabase(spatial, true)
        var s: CertGeom.Summary? = null
        try {
            fs.write(out) {
                writeUtf8("# GTGEOM mac $stamp spatial=$spatial\n")
                s = CertGeom.run(conn, { l -> writeUtf8(l); writeUtf8("\n"); if (!l.startsWith("GEOM\t") || l.contains("\tDIFF\t")) println("GTGEOM mac: $l") },
                    { p -> println("GTGEOM mac: $p") })
            }
        } finally { conn.close() }
        val r = s!!
        println("GTGEOM mac done: ${r.rows} rows, ${r.same} SAME, ${r.noncanonical} NONCANONICAL, ${r.diff} DIFF; end-ride lookup ${r.lookupOk}/${r.tracks} -> $out")
        assertEquals(0, r.diff, "GtGeom differs from the stored data on the iPad -- see $out")
    }
}
