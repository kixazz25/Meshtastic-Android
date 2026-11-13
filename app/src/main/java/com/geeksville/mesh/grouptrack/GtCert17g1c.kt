package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.MapSpecCertOld
import com.grouptrack.shared.cert.Cert17g1c
import com.grouptrack.shared.mapsource.GtMapSpecEnv
import java.io.Writer

/**
 * STEP17G1C-2026-11-13 (2.8 step 17, G1c cert, Droid side) ⚠ TEST-ONLY
 * (CODE RULE 3: remove with GtCertRun before 2.8 is released).
 *
 * OLD = 2.7e's VectorTiles + ElevationTiles source resolution as real Android code over real
 * org.json (MapSpecCertOld, counted manifest). NEW = the shared GtMapSpec over GtOrgJson.
 *
 * ⛔ THE ASSET IS CARRIED IN THE REPORT. The iPad has no APK, so it cannot read map_sources.json.
 * If the two devices ran different asset text every key would be meaningless, so the REAL asset is
 * read here, handed to both sides, AND written into the report on a "# G1C-ASSET" line for the iPad
 * to read back. 3,386 bytes, one escaped line.
 *
 * Lines: G1C <tab> key <tab> SAME|DIFF <tab> value; footer "# G1C done: N SAME, N DIFF in S s".
 */
object GtCert17g1c {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** OLD: 2.7e, real org.json, real MapSourceManager.TileLayer. */
    private class OldSide : Cert17g1c.Side {
        override fun resetForCert() = MapSpecCertOld.resetForCert()
        override fun vectorTileTemplate(env: GtMapSpecEnv) = MapSpecCertOld.vectorTileTemplate(env)
        override fun vectorDownloadSources(env: GtMapSpecEnv) =
            MapSpecCertOld.vectorDownloadSources(env).flatMap { (slot, ls) ->
                ls.map { "$slot|${it.role}|${it.urlTemplate}|${it.tileFormat}|${it.cacheDir}|${it.renderOrder}" }
            }
        override fun elevationUrlTemplate(env: GtMapSpecEnv) = MapSpecCertOld.elevationUrlTemplate(env)
        override fun elevationDownloadSources(env: GtMapSpecEnv) =
            MapSpecCertOld.elevationDownloadSources(env).flatMap { (slot, ls) ->
                ls.map { "$slot|${it.role}|${it.urlTemplate}|${it.tileFormat}|${it.cacheDir}|${it.renderOrder}" }
            }
        override fun oldTilesOf(recipeText: String) = MapSpecCertOld.oldTilesOf(recipeText)
    }

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()

        val asset = try {
            ctx.assets.open("map_sources.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            w.write("G1C\tASSET-UNREADABLE\tDIFF\t${esc(e.toString())}\n"); w.flush(); return
        }
        // the iPad reads this back, so both devices run byte-identical input
        w.write("# G1C-ASSET\t${esc(asset)}\n")

        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try { Cert17g1c.run(OldSide(), asset) { k, v -> oldMap[k] = v } }
        catch (e: Exception) { w.write("G1C\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try { Cert17g1c.run(Cert17g1c.NewSide, asset) { k, v -> newMap[k] = v } }
        catch (e: Exception) { w.write("G1C\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0
        var diff = 0
        var expected = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            val why = Cert17g1c.EXPECTED_DIFF[k]
            if (o == n) {
                same++; w.write("G1C\t$k\tSAME\t${esc(o)}\n")
                // a divergence that stopped diverging means the table is stale -- say so, never pass quietly
                if (why != null) w.write("# G1C-NOTE\tEXPECTED_DIFF[$k] is STALE: OLD and NEW now agree ($why)\n")
            } else if (why != null) {
                // RECORDED, not chased: org.json is lenient, GtOrgJson is strict (the G2g GtConfigs precedent)
                expected++
                w.write("G1C\t$k\tEXPECTED\t${esc(o)} | ${esc(n)}  [$why]\n")
                // ⛔ the port's own answer, so the iPad still compares NEW against NEW on this fixture
                w.write("G1C\t$k|newonly\tSAME\t${esc(n)}\n")
            } else { diff++; w.write("G1C\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# G1C done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
