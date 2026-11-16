package com.grouptrack.shared.cert

import com.grouptrack.shared.json.GtOrgJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CERTASSETS-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * seam 2's ASSET service -- the bundled .json files the app reads at startup.
 *
 * THE RISK THIS EXISTS FOR, recorded by GtHelpLinks and never run until now:
 *   "⚠ ONE KNOWN DIVERGENCE, FOLLOWING THE GtConfigs PRECEDENT: 2.7e parses with org.json, which
 *    is LENIENT; this parses with GtOrgJson, which is strict. A malformed asset that Android would
 *    tolerate is rejected here -- and both sides then fall back to an empty table, so every id gets
 *    the front page. Not reproduced, recorded."
 * A silent fallback to an empty table is the worst shape of failure: the app starts, nothing throws,
 * and HELP sends every rider to the manual's front page. This flattens each asset through BOTH
 * parsers and compares every path and value, so a strictness difference shows as a named key rather
 * than as an empty table nobody notices.
 *
 * WHAT IS COMPARED. Each asset is flattened to one line per leaf: "a.b[0].c = <value>", sorted. That
 * form is parser-agnostic -- it does not depend on key order, on JSONObject vs JsonObject, or on how
 * either library renders a container -- so org.json and GtOrgJson can be held to the same answer.
 *
 * ⛔ THE TYPE IS PART OF THE VALUE. org.json's optString("x") on the number 1 returns "1", and on
 * the boolean true returns "true", so a flattening that only compared strings would pass while one
 * parser read a number and the other read a quoted string. Each leaf carries its kind -- n: s: b:
 * null -- which is what makes "1" and 1 fail rather than agree.
 */
object CertAssets {

    val EXPECTED_DIFF: Map<String, String> = emptyMap()

    /**
     * The assets the app actually reads at runtime, measured from the call sites on 11-16.
     * ⚠ utah_trailheads.json (332 KB) and firmware_releases.json (138 KB) are included because size
     * is exactly where a lenient parser's tolerance and a strict one's refusal diverge, and because
     * they ship -- "we do not sample" (Fred 11-16 08:06).
     */
    val ASSETS: List<String> = listOf(
        "map_sources.json",
        "grouptrack_help_links.json",
        "trail_sources.json",
        "osm_layers.json",
        "grouptrack_default.json",
        "master_config.json",
        "convoy_apply_list.json",
        "geofabrik_states.json",
        "map_keys_defaults.json",
        "device_hardware.json",
        "device_bootloader_ota_quirks.json",
        "firmware_releases.json",
        "utah_trailheads.json",
    )

    /** One leaf of a parsed document: its path, its kind, and its text. */
    data class Leaf(val path: String, val kind: String, val text: String)

    /** Flattens what GtOrgJson parsed. The shared side -- the iPad runs exactly this. */
    fun flattenShared(text: String): List<Leaf> {
        val out = ArrayList<Leaf>()
        fun walk(e: JsonElement, path: String) {
            when (e) {
                is JsonObject -> {
                    if (e.isEmpty()) out.add(Leaf(path, "obj", "{}"))
                    for (k in e.keys.sorted()) walk(e.getValue(k), if (path.isEmpty()) k else "$path.$k")
                }
                is JsonArray -> {
                    if (e.isEmpty()) out.add(Leaf(path, "arr", "[]"))
                    for ((i, v) in e.withIndex()) walk(v, path + "[" + i + "]")
                }
                is JsonNull -> out.add(Leaf(path, "null", "null"))
                is JsonPrimitive ->
                    // ⛔ isString is the whole point: it is what separates the number 1 from the
                    // string "1", which org.json's optString would render identically.
                    out.add(Leaf(path, if (e.isString) "s" else if (e.content == "true" || e.content == "false") "b" else "n", e.content))
            }
        }
        walk(GtOrgJson.parse(text), "")
        return out.sortedBy { it.path + "|" + it.kind }
    }

    /**
     * The SUMMARY for one asset: how many leaves, and a hash over every path|kind|text.
     * ⛔ THE LEAVES ARE NOT EMITTED HERE. utah_trailheads.json is 332 KB and flattens to thousands
     * of leaves; emitting all of them for all 13 assets would put tens of thousands of lines in a
     * report that has to be pulled off the device and diffed. The hash is the comparison; [leavesOf]
     * is called ONLY for an asset whose hash differs, which is what localises the difference to a
     * path without paying for it on every run.
     */
    fun run(name: String, leaves: List<Leaf>, sink: (String, String) -> Unit) {
        sink("ASSET|$name|leaves", leaves.size.toString())
        sink("ASSET|$name|hash", hashOf(leaves).toString())
    }

    fun hashOf(leaves: List<Leaf>): Long {
        var h = 0L
        for (l in leaves) {
            val s = l.path + "|" + l.kind + "|" + l.text
            for (c in s) h = h * 31 + c.code
        }
        return h
    }

    /** Per-leaf keys, for ONE asset that already failed its hash -- the localiser. */
    fun leavesOf(name: String, leaves: List<Leaf>, sink: (String, String) -> Unit) {
        for (l in leaves) sink("LEAF|$name|" + l.path, l.kind + "|" + esc(l.text))
    }

    /** A parse failure is a RESULT, not a crash: it is what the recorded divergence would look like. */
    fun tryFlatten(text: String): Pair<List<Leaf>, String?> =
        try { Pair(flattenShared(text), null) } catch (e: Exception) { Pair(emptyList(), e.message ?: e.toString()) }

    fun esc(s: String): String = buildString {
        for (c in s) {
            val n = c.code
            if (n in 32..126 && c != '\t' && c != '|') append(c)
            else append("\\u").append(n.toString(16).padStart(4, '0'))
        }
    }
}
