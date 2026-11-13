package com.grouptrack.shared.cert

import com.grouptrack.shared.mapsource.GtMapSpec
import com.grouptrack.shared.mapsource.GtMapSpecEnv

/**
 * Cert17g1c — the ONE runner for step 17 group G1c (GtMapSpec). STEP17G1C-CERT-2026-11-13
 *
 * WHAT IS UNDER TEST: where the tile URLs come from. The vector TileJSON resolution, the elevation
 * URL template, the two downloadSources descriptors, and the recipe's old-tile count.
 *
 * ⚠ NOT UNDER TEST — and deliberately absent from the OLD side too: the nine VectorTiles members
 * that are already front doors to GtVectorQueue, CERTIFIED AT 16c (6,399 values, Droid OLD = NEW,
 * iPad = Droid). Re-running them here would re-test closed work.
 *
 * ══════════════════════════════════════════════════════════════════
 *  THE THREE THINGS THIS HARNESS HAS TO GET RIGHT
 * ══════════════════════════════════════════════════════════════════
 *
 * ⛔ 1. THE ASSET AND THE HTTP ANSWER ARE BOTH SCRIPTED, and both sides get the SAME env (the G2f
 *       rule). The network cannot be in a certification, and an asset read differing between sides
 *       would make every key meaningless.
 *       ⭐ But the GOOD asset text is NOT invented: the harness reads the REAL map_sources.json out
 *       of the APK and hands that string to both sides, because the shipped asset is deterministic.
 *       Only the broken variants are synthetic. An invented "good" fixture would certify the port
 *       against a file that does not ship.
 *
 * ⛔ 2. `resolved` IS PROCESS-WIDE STATE AND MUST BE RESET BETWEEN FIXTURES. 2.7e caches the
 *       resolved tile template in a @Volatile var with no invalidation, so without a reset the
 *       SECOND fixture would answer with the FIRST fixture's template and every later key would be
 *       a lie that happens to match on both sides. [resetForCert] is called before each fixture.
 *       ⭐ And one fixture tests the cache ON PURPOSE (CACHE2): resolve once, then ask again with a
 *       DIFFERENT http answer. The correct 2.7e behaviour is to return the FIRST one. That wart is
 *       the behaviour, so it is a compared key rather than something the harness tidies away.
 *
 * ⛔ 3. THE STRICT / LENIENT DIVERGENCE IS SCRIPTED, NOT ASSUMED. 2.7e parses with org.json
 *       (lenient); the port parses with GtOrgJson over kotlinx (strict). GtOrgJson's own header
 *       lists what it does NOT reproduce: comments, single quotes, unquoted keys, '='/';'
 *       separators, trailing text. So LENIENT1-3 feed exactly those. ⚠ A DIFF there is NOT a port
 *       defect — it is the known, recorded GtConfigs divergence from G2g. Both sides' catch-alls
 *       return the same give-up answer (null / 0), so the expectation is SAME; if it is not, the
 *       difference gets written down, not patched.
 *
 * COMPARED: every returned value. These are pure given the env, so the return value IS the decision.
 * NOT COMPARED: the HTTP status code and the log text. 2.7e logs the code and returns null either
 * way, so the code is an observation.
 */
object Cert17g1c {

    /**
     * ⛔ KEYS WHERE OLD AND NEW ARE KNOWN TO DIFFER, with the reason. Measured on 11-13, not assumed:
     * the first G1c run reported 58 SAME / 6 DIFF and these were the six.
     *
     * ⭐ WHY THIS IS A RECORDED DIVERGENCE AND NOT A PORT DEFECT. 2.7e parses with org.json, whose
     * JSONTokener is LENIENT; the port parses with GtOrgJson over kotlinx, which is STRICT and says
     * so in its own header: "NOT REPRODUCED (kotlinx is strict, Android's JSONTokener is lenient):
     * comments, single quotes, unquoted keys, '=' / ';' separators, and text after the first value.
     * Android accepts those; [parse] throws (the golden run marks them EXPECTED)." These three
     * fixtures feed exactly those cases, so OLD finds the tilejson and NEW gives up.
     *
     * ⭐ AND WHY IT IS UNREACHABLE IN PRACTICE. GtMapSpec reads context.assets.open("map_sources.json")
     * -- the asset WE SHIP, which is well-formed (a strict parser reads it) and contains no comments,
     * single quotes or unquoted keys. ⚠ It is NOT the user-editable external map_sources.json that
     * MapSourceManager.externalJsonFile() reads; that file never reaches this code. The TileJSON comes
     * from OpenFreeMap, serving valid JSON.
     *
     * ⛔ THESE ARE STILL RUN AND STILL COMPARED CROSS-DEVICE. An expected key is recorded as EXPECTED
     * rather than DIFF, and the harness ALSO emits "<key>|newonly" carrying the port's own answer, so
     * the iPad still checks NEW against NEW. Marking a divergence expected must never mean testing it
     * less -- only that OLD disagreeing with NEW is not news.
     * ⚠ If an expected key ever comes back SAME, the entry here is STALE: the harness says so on a
     * "# G1C-NOTE" line rather than quietly passing.
     */
    val EXPECTED_DIFF: Map<String, String> = mapOf(
        "VTPL|lenient_unquoted" to "org.json accepts unquoted keys; GtOrgJson (kotlinx) throws",
        "VDL|lenient_unquoted" to "org.json accepts unquoted keys; GtOrgJson (kotlinx) throws",
        "VTPL|lenient_single" to "org.json accepts single-quoted strings; GtOrgJson throws",
        "VDL|lenient_single" to "org.json accepts single-quoted strings; GtOrgJson throws",
        "VTPL|lenient_trailing" to "org.json stops at the first value; GtOrgJson rejects trailing text",
        "VDL|lenient_trailing" to "org.json stops at the first value; GtOrgJson rejects trailing text",
    )

    interface Side {
        fun resetForCert()
        fun vectorTileTemplate(env: GtMapSpecEnv): String?
        /** flattened "slot|role|url|format|dir|order" per layer, so the layer TYPE never matters */
        fun vectorDownloadSources(env: GtMapSpecEnv): List<String>
        fun elevationUrlTemplate(env: GtMapSpecEnv): String?
        fun elevationDownloadSources(env: GtMapSpecEnv): List<String>
        fun oldTilesOf(recipeText: String): Long
    }

    /** A scripted env. [assetText] null = readAsset throws (the asset is missing). */
    class FixtureEnv(
        private val assetText: String?,
        private val httpAnswers: List<String?>,
    ) : GtMapSpecEnv {
        var httpCalls = 0; private set
        override fun readAsset(name: String): String =
            assetText ?: throw RuntimeException("asset not found: $name")
        override fun httpGet(url: String, timeoutMs: Int): String? {
            val i = httpCalls.coerceAtMost(httpAnswers.size - 1)
            httpCalls++
            return if (httpAnswers.isEmpty()) null else httpAnswers[i]
        }
    }

    // ---- TileJSON bodies ------------------------------------------------
    private const val TJ_GOOD = """{"tiles":["https://tiles.example.org/v1/{z}/{x}/{y}.pbf"],"maxzoom":14}"""
    private const val TJ_SECOND = """{"tiles":["https://tiles.example.org/v2/{z}/{x}/{y}.pbf"]}"""
    private const val TJ_NO_TILES = """{"maxzoom":14}"""
    private const val TJ_EMPTY_TILES = """{"tiles":[]}"""
    private const val TJ_BLANK_TILE = """{"tiles":[""]}"""
    private const val TJ_NOT_JSON = """<html>503 Service Unavailable</html>"""

    // ---- synthetic assets (the GOOD one comes from the real APK asset) --
    private const val A_NO_VECTOR = """{"elevation_sources":[{"url_template":"https://dem.example.org/{z}/{x}/{y}.png"}]}"""
    private const val A_EMPTY_VECTOR = """{"vector_sources":[],"elevation_sources":[]}"""
    private const val A_BLANK_TJ = """{"vector_sources":[{"tilejson":""}],"elevation_sources":[{"url_template":""}]}"""
    private const val A_NO_ELEV = """{"vector_sources":[{"tilejson":"https://tj.example.org/t.json"}]}"""
    private const val A_WRONG_TYPES = """{"vector_sources":{"tilejson":"x"},"elevation_sources":"nope"}"""
    // the three the strict parser is documented NOT to accept
    private const val A_LENIENT_UNQUOTED = """{vector_sources:[{tilejson:"https://tj.example.org/t.json"}]}"""
    private const val A_LENIENT_SINGLE = """{'vector_sources':[{'tilejson':'https://tj.example.org/t.json'}]}"""
    private const val A_LENIENT_TRAILING = """{"vector_sources":[{"tilejson":"https://tj.example.org/t.json"}]} trailing junk"""

    // ---- recipes for oldTilesOf -----------------------------------------
    private const val R_MIXED = """{"slots":[{"slot":"VEC","tiles":100},{"slot":"DEM","tiles":50},{"slot":"SAT","tiles":7000},{"slot":"TOPO","tiles":300}]}"""
    private const val R_ONLY_NEW = """{"slots":[{"slot":"VEC","tiles":100},{"slot":"DEM","tiles":50}]}"""
    private const val R_NO_SLOTS = """{"date":"2026-11-13"}"""
    private const val R_EMPTY = """{"slots":[]}"""
    private const val R_STRING_TILES = """{"slots":[{"slot":"SAT","tiles":"1234"}]}"""
    private const val R_DOUBLE_TILES = """{"slots":[{"slot":"SAT","tiles":12.9}]}"""
    private const val R_MISSING_TILES = """{"slots":[{"slot":"SAT"}]}"""
    private const val R_NULL_ENTRY = """{"slots":[null,{"slot":"SAT","tiles":5}]}"""
    private const val R_NOT_JSON = """not json at all"""

    /**
     * [realAsset] = the REAL map_sources.json text, read from the APK by the harness and handed to
     * both sides. ⛔ Required, not defaulted: a cert that silently ran against an invented asset
     * would certify nothing (CODE RULE 1).
     */
    fun run(side: Side, realAsset: String, sink: (String, String) -> Unit) {

        fun layers(l: List<String>) = if (l.isEmpty()) "(empty)" else l.joinToString(";")
        fun s(v: String?) = v ?: "(null)"

        // ---- 1. the real shipped asset, with each TileJSON answer ----
        val tjCases = listOf(
            "good" to TJ_GOOD, "no_tiles" to TJ_NO_TILES, "empty_tiles" to TJ_EMPTY_TILES,
            "blank_tile" to TJ_BLANK_TILE, "not_json" to TJ_NOT_JSON,
        )
        for ((name, tj) in tjCases) {
            side.resetForCert()
            val env = FixtureEnv(realAsset, listOf(tj))
            sink("VTPL|real|$name", s(side.vectorTileTemplate(env)))
            side.resetForCert()
            val env2 = FixtureEnv(realAsset, listOf(tj))
            sink("VDL|real|$name", layers(side.vectorDownloadSources(env2)))
        }
        // the fetch itself failing
        side.resetForCert()
        sink("VTPL|real|fetch_null", s(side.vectorTileTemplate(FixtureEnv(realAsset, listOf(null)))))

        // ---- 2. THE CACHE WART (see note 2) ----
        side.resetForCert()
        val cacheEnv = FixtureEnv(realAsset, listOf(TJ_GOOD, TJ_SECOND))
        val first = side.vectorTileTemplate(cacheEnv)
        val second = side.vectorTileTemplate(cacheEnv)
        sink("CACHE2|first", s(first))
        sink("CACHE2|second_must_equal_first", s(second))
        sink("CACHE2|http_calls", cacheEnv.httpCalls.toString())
        // and after a reset the SECOND answer is taken
        side.resetForCert()
        sink("CACHE2|after_reset", s(side.vectorTileTemplate(FixtureEnv(realAsset, listOf(TJ_SECOND)))))

        // ---- 3. the elevation side, on the real asset ----
        side.resetForCert()
        sink("EUTPL|real", s(side.elevationUrlTemplate(FixtureEnv(realAsset, emptyList()))))
        side.resetForCert()
        sink("EDL|real", layers(side.elevationDownloadSources(FixtureEnv(realAsset, emptyList()))))

        // ---- 4. synthetic assets ----
        val assets = listOf(
            "missing" to null, "no_vector" to A_NO_VECTOR, "empty_vector" to A_EMPTY_VECTOR,
            "blank_tj" to A_BLANK_TJ, "no_elev" to A_NO_ELEV, "wrong_types" to A_WRONG_TYPES,
            "lenient_unquoted" to A_LENIENT_UNQUOTED, "lenient_single" to A_LENIENT_SINGLE,
            "lenient_trailing" to A_LENIENT_TRAILING,
        )
        for ((name, text) in assets) {
            side.resetForCert(); sink("VTPL|$name", s(side.vectorTileTemplate(FixtureEnv(text, listOf(TJ_GOOD)))))
            side.resetForCert(); sink("VDL|$name", layers(side.vectorDownloadSources(FixtureEnv(text, listOf(TJ_GOOD)))))
            side.resetForCert(); sink("EUTPL|$name", s(side.elevationUrlTemplate(FixtureEnv(text, emptyList()))))
            side.resetForCert(); sink("EDL|$name", layers(side.elevationDownloadSources(FixtureEnv(text, emptyList()))))
        }

        // ---- 5. oldTilesOf ----
        val recipes = listOf(
            "mixed" to R_MIXED, "only_new" to R_ONLY_NEW, "no_slots" to R_NO_SLOTS,
            "empty" to R_EMPTY, "string_tiles" to R_STRING_TILES, "double_tiles" to R_DOUBLE_TILES,
            "missing_tiles" to R_MISSING_TILES, "null_entry" to R_NULL_ENTRY, "not_json" to R_NOT_JSON,
        )
        for ((name, text) in recipes) sink("OLDTILES|$name", side.oldTilesOf(text).toString())

        // ---- 6. the constants ----
        sink("CONST|vec", "${GtMapSpec.VEC_SLOT}|${GtMapSpec.VEC_Z_MAX}")
        sink("CONST|dem", "${GtMapSpec.DEM_SLOT}|${GtMapSpec.DEM_Z_MAX}")
    }

    /** The NEW side — the shared port. */
    object NewSide : Side {
        override fun resetForCert() = GtMapSpec.resetForCert()
        override fun vectorTileTemplate(env: GtMapSpecEnv) = GtMapSpec.vectorTileTemplate(env)
        override fun vectorDownloadSources(env: GtMapSpecEnv) =
            GtMapSpec.vectorDownloadSources(env).flatMap { (slot, ls) ->
                ls.map { "$slot|${it.role}|${it.urlTemplate}|${it.tileFormat}|${it.cacheDir}|${it.renderOrder}" }
            }
        override fun elevationUrlTemplate(env: GtMapSpecEnv) = GtMapSpec.elevationUrlTemplate(env)
        override fun elevationDownloadSources(env: GtMapSpecEnv) =
            GtMapSpec.elevationDownloadSources(env).flatMap { (slot, ls) ->
                ls.map { "$slot|${it.role}|${it.urlTemplate}|${it.tileFormat}|${it.cacheDir}|${it.renderOrder}" }
            }
        override fun oldTilesOf(recipeText: String) = GtMapSpec.oldTilesOf(recipeText)
    }
}
