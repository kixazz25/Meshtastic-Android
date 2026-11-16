package com.geeksville.mesh.grouptrack

import android.content.Context
import com.grouptrack.shared.cert.CertAssets
import com.grouptrack.shared.log.gtLogger
import java.io.Writer

/**
 * CERTASSETS-2026-11-16 \u26a0 TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the Droid legs of seam 2's ASSET service. Request mode "certassets".
 *
 * THREE LEGS:
 *   1. OLD Droid  org.json -- LENIENT, what every asset read uses today
 *   2. NEW Droid  GtOrgJson -- STRICT (kotlinx), what the shared code and the iPad use
 *   3. iPad       GtOrgJson on the same bytes -- GtCertAssetsMacTest
 *
 * \u26d4 THE BYTES ARE ALSO COMPARED, not just the parse. Before either parser runs, the asset is read
 * BOTH ways on the Droid -- ctx.assets.open(name) directly, and env.platform.readAsset(name) through
 * the socket -- and the two texts must be identical. That is the conversion the 8 remaining call
 * sites will make, so it is proved before the patch rather than after (the mistake that cost two
 * builds on the paths service at 11:49).
 *
 * WHAT A FAILURE LOOKS LIKE AND WHY IT MATTERS. GtHelpLinks.load wraps its parse in runCatching and
 * falls back to an EMPTY link table. GtMapSpec.spec() catches and returns null. So a strictness
 * difference does not crash: the app starts, nothing throws, and every HELP button quietly opens the
 * manual's front page. That is why this compares every leaf PATH and not merely "did it parse".
 *
 * Lines: CERTASSETS <tab> key <tab> SAME|EXPECTED|DIFF|FACT <tab> value
 */
object GtCertAssets {
    private const val TAG = "CertRun"
    private val log = gtLogger(TAG)

    private fun esc(s: String) =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /**
     * Flattens what ORG.JSON parsed, into CertAssets.Leaf's exact shape.
     * \u26a0 The KIND is taken from the Java type org.json hands back, because that is the only way to
     * tell the number 1 from the string "1" -- optString would render both as "1" and the comparison
     * would pass while the two parsers disagreed.
     */
    private fun flattenOld(text: String): List<CertAssets.Leaf> {
        val out = ArrayList<CertAssets.Leaf>()
        fun walk(v: Any?, path: String) {
            when (v) {
                null, org.json.JSONObject.NULL -> out.add(CertAssets.Leaf(path, "null", "null"))
                is org.json.JSONObject -> {
                    val keys = v.keys().asSequence().toList().sorted()
                    if (keys.isEmpty()) out.add(CertAssets.Leaf(path, "obj", "{}"))
                    for (k in keys) walk(v.get(k), if (path.isEmpty()) k else "$path.$k")
                }
                is org.json.JSONArray -> {
                    if (v.length() == 0) out.add(CertAssets.Leaf(path, "arr", "[]"))
                    for (i in 0 until v.length()) walk(v.get(i), path + "[" + i + "]")
                }
                is String -> out.add(CertAssets.Leaf(path, "s", v))
                is Boolean -> out.add(CertAssets.Leaf(path, "b", v.toString()))
                else -> out.add(CertAssets.Leaf(path, "n", v.toString()))
            }
        }
        // org.json needs to be told which container it is; an asset is one or the other.
        val t = text.trimStart()
        if (t.startsWith("[")) walk(org.json.JSONArray(text), "") else walk(org.json.JSONObject(text), "")
        return out.sortedBy { it.path + "|" + it.kind }
    }

    fun run(app: Context, w: Writer): Unit {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0; var expected = 0; var facts = 0
        val env = GtImportAndroid(app)

        w.write("# CERTASSETS droid ${android.os.Build.MODEL}: ${CertAssets.ASSETS.size} bundled assets\n")
        w.write("# CERTASSETS legs: 1 OLD org.json (lenient) | 2 NEW GtOrgJson (strict) | 3 iPad GtOrgJson\n")

        for (name in CertAssets.ASSETS) {
            // ---- the bytes, both ways: direct, and through the socket the 8 sites will use ----
            val direct = try { app.assets.open(name).bufferedReader().use { it.readText() } }
                         catch (e: Exception) { null }
            if (direct == null) {
                diff++; w.write("CERTASSETS\tASSET|$name|readable\tDIFF\tctx.assets.open THREW\n"); continue
            }
            val viaSocket = try { env.platform.readAsset(name) } catch (e: Exception) { "THREW: " + e.message }
            if (direct == viaSocket) { same++; w.write("CERTASSETS\tASSET|$name|socket-bytes\tSAME\tlen=${direct.length}\n") }
            else { diff++; w.write("CERTASSETS\tASSET|$name|socket-bytes\tDIFF\tdirect len=${direct.length} | socket ${esc(viaSocket.take(80))}\n") }

            // ---- THE ACCESSOR THE 8 CONVERTED SITES ACTUALLY CALL ----
            // Fred 11-16 12:27: "looks identical to the last certification. did we run the right
            // process." Identical IS the required result -- but he is right that this cert could not
            // tell the difference, because it called env.platform.readAsset directly and never
            // GtAssetsAndroid.text(), which is what the 8 patched sites call. Exactly the hole he
            // caught on the paths patch at 11:49. Closed the same way: compared against the direct
            // read, as a STRICT key.
            val viaAccessor = try { GtAssetsAndroid.text(app, name) } catch (e: Exception) { "THREW: " + e.message }
            if (direct == viaAccessor) { same++; w.write("CERTASSETS\tASSET|$name|accessor-bytes\tSAME\tlen=${direct.length}\n") }
            else { diff++; w.write("CERTASSETS\tASSET|$name|accessor-bytes\tDIFF\tdirect len=${direct.length} | accessor ${esc(viaAccessor.take(80))}\n") }

            // ---- leg 1: org.json, lenient ----
            val old = try { Pair(flattenOld(direct), null as String?) }
                      catch (e: Exception) { Pair(emptyList<CertAssets.Leaf>(), e.message ?: e.toString()) }
            // ---- leg 2: GtOrgJson, strict ----
            val new = CertAssets.tryFlatten(direct)

            if (old.second != null || new.second != null) {
                // \u26d4 THE RECORDED DIVERGENCE, if it is real: one parser accepted and the other did not.
                diff++
                w.write("CERTASSETS\tASSET|$name|parse\tDIFF\torg.json=${if (old.second == null) "ok" else "FAILED: " + esc(old.second!!)}" +
                    " | GtOrgJson=${if (new.second == null) "ok" else "FAILED: " + esc(new.second!!)}\n")
                continue
            }
            w.write("CERTASSETS\tASSET|$name|parse\tSAME\tboth parsers accepted it\n"); same++

            val oldMap = LinkedHashMap<String, String>()
            val newMap = LinkedHashMap<String, String>()
            CertAssets.run(name, old.first) { k, v -> oldMap[k] = v }
            CertAssets.run(name, new.first) { k, v -> newMap[k] = v }
            // ⚠ the per-leaf keys are added ONLY when this asset's summary already disagrees. On a
            // clean run the report carries 2 lines per asset instead of thousands; on a dirty one it
            // carries every leaf of the offending asset, which is what names the differing path.
            if (oldMap != newMap) {
                CertAssets.leavesOf(name, old.first) { k, v -> oldMap[k] = v }
                CertAssets.leavesOf(name, new.first) { k, v -> newMap[k] = v }
                w.write("# CERTASSETS $name DISAGREES -- every leaf emitted below to localise it\n")
            }
            for (k in (oldMap.keys + newMap.keys).distinct()) {
                val o = oldMap[k]; val n = newMap[k]
                val why = CertAssets.EXPECTED_DIFF[k]
                when {
                    o != null && o == n -> { same++; w.write("CERTASSETS\t$k\tSAME\t${esc(n!!)}\n") }
                    why != null -> {
                        expected++
                        w.write("CERTASSETS\t$k\tEXPECTED\t${esc(o.toString())} | ${esc(n.toString())}  [$why]\n")
                        w.write("CERTASSETS\t$k|newonly\tSAME\t${esc(n.toString())}\n")
                    }
                    else -> { diff++; w.write("CERTASSETS\t$k\tDIFF\tOLD ${esc(o.toString())} | NEW ${esc(n.toString())}\n") }
                }
            }
        }
        w.write("# CERTASSETS done: $same SAME, $diff DIFF, $expected EXPECTED, $facts FACT in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
        log.i { "GTCERT certassets done: $same SAME, $diff DIFF, $expected EXPECTED" }
    }
}
