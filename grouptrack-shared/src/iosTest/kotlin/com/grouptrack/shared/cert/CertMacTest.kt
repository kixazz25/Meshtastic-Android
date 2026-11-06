package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import kotlin.test.Test

/**
 * GTCERT-2026-11-06: the MAC side of the whole-database certification -- the same shared code (GtCert.runNew) on the iPad simulator, on
 * the Mac copy of Droid 1's databases. Writes $GT_SPIKE/_data/cert/GroupTrack_CERT_mac_<time>.txt ("MAC|..." lines).
 * Run: ./gradlew :grouptrack-shared:iosSimulatorArm64Test --tests '*CertMacTest*'   (gt_cert.sh does this). TEST-ONLY.
 */
class CertMacTest {
    private val dataDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/droid1_2026-11-06"
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"

    @Test
    fun wholeDatabase() {
        val fs = FileSystem.SYSTEM
        val spatial = "$dataDir/grouptrack_spatial.db"
        if (!fs.exists(spatial.toPath())) { println("GTCERT mac: no data at $dataDir -- skipped"); return }
        val conn = gtOpenDatabase(spatial, true)
        val data = "$dataDir/grouptrack_data.db"
        val dconn = if (fs.exists(data.toPath())) gtOpenDatabase(data, true) else null   // as GtSpatial: optional, see its doc
        val keys = Json.parseToJsonElement(fs.read("$dataDir/map_keys.json".toPath()) { readUtf8() }).jsonObject
        fun s(k: String, d: String) = (keys[k] as? JsonPrimitive)?.contentOrNull ?: d
        val saved = GtCert.Combo("saved", s("land", "PUBLIC").uppercase(), (keys["restricted"] as? JsonPrimitive)?.booleanOrNull ?: true,
            s("use", "ALL").uppercase(), (keys["off"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet())
        val stamp = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd_HHmm" }.stringFromDate(NSDate())
        fs.createDirectories(outDir.toPath())
        val out = "$outDir/GroupTrack_CERT_mac_$stamp.txt".toPath()
        var n = 0
        fs.write(out) {
            writeUtf8("# GTCERT mac $stamp spatial=$spatial\n")
            GtCert.runNew(conn, dconn, saved, { id -> notes(dconn, id) }, { l -> writeUtf8("MAC|$l\n"); n++ }, { p -> println("GTCERT mac: $p ($n lines)") })
        }
        conn.close(); dconn?.close()
        println("GTCERT mac done: $n lines -> $out")
    }

    /** (headline, notes JSON) for a route: the stored payload as it is, its narrative headline. */
    private fun notes(d: SQLiteConnection?, routeId: String): Pair<String, String?> {
        if (d == null) return "" to null
        val st = d.prepare("SELECT payload FROM route_notes WHERE route_id=? AND kind='narrative' LIMIT 1")
        try {
            st.bindText(1, routeId)
            if (!st.step() || st.isNull(0)) return "" to null
            val p = st.getText(0)
            val headline = try { ((Json.parseToJsonElement(p).jsonObject["narrative"] as? JsonObject)?.get("headline") as? JsonPrimitive)?.contentOrNull ?: "" } catch (e: Exception) { "" }
            return headline to p
        } catch (e: Exception) { return "" to null } finally { st.close() }
    }
}
