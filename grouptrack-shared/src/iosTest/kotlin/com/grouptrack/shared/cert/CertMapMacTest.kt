package com.grouptrack.shared.cert

import com.grouptrack.shared.db.gtOpenDatabase
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test

/**
 * STEP9BC-2026-11-07: checks 9(b) and 9(c) (CertMap) on the iPad simulator, on the Mac copy of Droid 1's databases.
 * Writes $GT_SPIKE/_data/cert/GroupTrack_MAPCHECK_mac_<time>.txt.
 * Run: ./gradlew :grouptrack-shared:iosSimulatorArm64Test --rerun --tests '*CertMapMacTest*'   (gt_mapcheck_2026-11-07.sh). TEST-ONLY.
 */
class CertMapMacTest {
    private val dataDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/droid1_2026-11-06"
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun mapChecks() {
        val fs = FileSystem.SYSTEM
        val spatial = "$dataDir/grouptrack_spatial.db"
        if (!fs.exists(spatial.toPath())) { println("GTMAP mac: no data at $dataDir -- skipped"); return }
        val conn = gtOpenDatabase(spatial, true)
        val data = "$dataDir/grouptrack_data.db"
        val dconn = if (fs.exists(data.toPath())) gtOpenDatabase(data, true) else null   // as GtSpatial: optional, see its doc
        val stamp = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd_HHmm" }.stringFromDate(NSDate())
        fs.createDirectories(outDir.toPath())
        val out = "$outDir/GroupTrack_MAPCHECK_mac_$stamp.txt".toPath()
        var n = 0
        fs.write(out) {
            writeUtf8("# GTMAP mac $stamp spatial=$spatial\n")
            CertMap.run(conn, dconn, { l -> writeUtf8("$l\n"); n++ }, { p -> println("GTMAP mac: $p") })
        }
        conn.close(); dconn?.close()
        println("GTMAP mac done: $n lines -> $out")
    }
}
