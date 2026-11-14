package com.grouptrack.shared.cert

import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.mapstate.GtMapSnapshot
import com.grouptrack.shared.mapstate.GtMapState

/**
 * CERT17MAPSTATE-2026-11-14 (2.8 step 17, Phase 2 pass 1): MapStateStore, OLD vs NEW.
 *
 * SIMULATED BEFORE IT WAS WRITTEN (sim_mapstate_v2_2026-11-14, Fred's standing rule extended 11-13
 * to cover the harness itself): 19 scenarios, EVERY ONE TERMINATES, 323 compared keys, exactly
 * 3 EXPECTED_DIFF. Nothing here is a sampled observation -- every key is a decision the service makes.
 *
 * \u26a0 WHY A [Side] INTERFACE AND A FLATTENED STRUCT: the OLD side reads with org.json, which does not
 * exist in commonMain, and its MapSnapshot is a different type from GtMapSnapshot. So each side
 * flattens into [Snap] below and the runner compares THAT -- the Cert16c "Est" pattern.
 *
 * \u26a0 saveMap IS CERTIFIED BY ROUND-TRIP, not by file text: org.json's JSONObject is HashMap-backed,
 * so its key ORDER is unspecified. Comparing written bytes would compare an artefact of a hash table.
 * The key is "write this snapshot, read it back, compare what came back".
 *
 * \u26d4 THE 3 EXPECTED_DIFF ARE THE G2g GtConfigs PRECEDENT, not defects: org.json tolerates comments,
 * single quotes and a trailing comma; GtOrgJson (kotlinx) refuses them, throws, and lands in
 * defaults() -- a branch 2.7e already has and already calls correct ("SAFE LOAD ... never throws to
 * the caller"). Nothing GroupTrack writes is lenient, so only a hand-edited file can reach it.
 */
object Cert17mapstate {

    /**
     * IPADTEST-2026-11-14: the cert's OWN map key -- never the app's rideKey(), so no stray live file
     * can be read. In commonMain because BOTH devices must use the identical key: hardcoded twice,
     * a typo would read as 349 DIFFs with no hint of the cause.
     */
    const val MAP_KEY: String = "cert17mapstate"

    /** One side's flattened answer. Strings only, so there is nothing to compare but decisions. */
    data class Snap(
        val why: String,
        val perType: List<String>,      // "<type>|state|rows|checkedIds" in TYPES order
        val panel: String,
        val bbox: String,
        val fit: String,
        val route: String,
    )

    interface Side {
        /** write [text] as mapKey's panel file (or delete it when [exists] is false), then readMap it */
        fun readMapAfter(mapKey: String, text: String, exists: Boolean): Snap
        /** write mapKey's view file (or delete it), then readView it: "mapType|offline" */
        fun readViewAfter(mapKey: String, text: String, exists: Boolean): String
        /** saveMap the fixture snapshot, read it back, flatten: the ROUND-TRIP key */
        fun roundTrip(mapKey: String, text: String): Snap
        /** the constants a caller depends on */
        fun rideKey(): String
        fun typeDefaults(): String      // "Trails=1;Tracks=0;..."
    }

    /** \u26d4 Fixture names are keys. Never reorder or rename -- a golden run is keyed on them. */
    val FIXTURES: List<Triple<String, String, Boolean>> = listOf(
        Triple("good", GOOD, true),
        Triple("no-file", "", false),
        Triple("empty-text", "", true),
        Triple("not-object", "[1,2,3]", true),
        Triple("no-artifacts", "{\"panel\":{\"tilesChecked\":true}}", true),
        Triple("missing-type", "{\"artifacts\":{\"Trails\":{\"state\":2,\"rows\":[]}}}", true),
        Triple("state-wrong-type", "{\"artifacts\":{\"Trails\":{\"state\":\"two\",\"rows\":[]}}}", true),
        Triple("rows-not-array", "{\"artifacts\":{\"Trails\":{\"state\":1,\"rows\":\"nope\"}}}", true),
        Triple("row-not-object", "{\"artifacts\":{\"Trails\":{\"state\":1,\"rows\":[1,2]}}}", true),
        Triple("all-empty-ids", "{\"artifacts\":{\"Trails\":{\"state\":2,\"rows\":[{\"id\":\"\",\"checked\":true}]}}}", true),
        Triple("no-panel", "{\"artifacts\":{\"Trails\":{\"state\":1,\"rows\":[]}}}", true),
        Triple("bbox-partial", "{\"artifacts\":{},\"bbox\":{\"south\":1.0}}", true),
        Triple("bbox-no-zoom", "{\"artifacts\":{\"Trails\":{\"state\":1,\"rows\":[]}},\"bbox\":{\"south\":1,\"west\":2,\"north\":3,\"east\":4}}", true),
        Triple("route-no-status", "{\"artifacts\":{\"Trails\":{\"state\":1,\"rows\":[]}},\"routeState\":{\"open\":true}}", true),
        Triple("route-empty-status", "{\"artifacts\":{\"Trails\":{\"state\":1,\"rows\":[]}},\"routeState\":{\"open\":true,\"status\":\"\"}}", true),
        Triple("fit-partial", "{\"artifacts\":{\"Trails\":{\"state\":1,\"rows\":[]}},\"fitArtifact\":{\"id\":\"x\"}}", true),
        // LENIENTFIX-2026-11-14: real content, so OLD-parses vs NEW-defaults diverges on state, rows and panel
        Triple("LENIENT-comment", LEN_BODY + " /* note */ }", true),
        Triple("LENIENT-squote", LEN_SQUOTE, true),
        Triple("LENIENT-trailing", LEN_BODY + ",}", true),
        Triple("malformed", "{\"artifacts\":", true),
    )

    /**
     * \u26d4 EXPECTED_DIFF, with the reason on each. org.json parses these; GtOrgJson refuses them and
     * falls to defaults(). Compared as EXPECTED and ALSO recorded as <key>|newonly so the NEW side's
     * own answer is still pinned by the golden run (the G1c convention).
     */
    val EXPECTED_DIFF: Map<String, String> = mapOf(
        // EXPECTEDKEYS-2026-11-14: \u26d4 EXACT KEYS, not fixture names. Every emitted key is
        // "<PHASE>|<fixture>|<field>", so a name-keyed table matched nothing: all 9 real divergences
        // would have read DIFF (a failed cert) and 27 agreeing keys would have read STALE.
        // The 9 below are what sim_mapstate_v4_2026-11-14 measured -- no more, no less. A 10th
        // divergence is a real finding and MUST surface as DIFF.
        "READ|LENIENT-comment|why" to "org.json skips block comments; kotlinx refuses -> defaults()",
        "READ|LENIENT-comment|type0" to "ditto: OLD parses Trails state 2 + 1 row; NEW defaults to 1 + none",
        "READ|LENIENT-comment|panel" to "ditto: OLD reads tilesChecked=true; NEW defaults to all false",
        "READ|LENIENT-squote|why" to "org.json accepts single-quoted strings; kotlinx refuses -> defaults()",
        "READ|LENIENT-squote|type0" to "ditto: OLD parses Trails state 2 + 1 row; NEW defaults to 1 + none",
        "READ|LENIENT-squote|panel" to "ditto: OLD reads tilesChecked=true; NEW defaults to all false",
        // TRAILINGMEASURED-2026-11-14: \u26d4 THE THREE LENIENT-trailing KEYS WERE HERE AND ARE GONE.
        // MEASURED on Droid 1 (GroupTrack_CERT_droid_2026-11-14_1347.txt): Android's org.json
        // REJECTS a trailing comma, exactly as kotlinx does -- both sides land in defaults() and
        // AGREE. The simulation had modelled org.json as lenient there and predicted 9 EXPECTED;
        // the real answer is 6. org.json's tolerance covers comments and single quotes, NOT
        // trailing commas. The fixture stays, and now proves the two parsers agree on that input.
    )

    /**
     * The fixtures whose two sides parse differently, so a ROUND-TRIP would compare OLD's parsed
     * snapshot against NEW's defaults snapshot and prove nothing. Skipped in the RT phase.
     * \u26a0 SEPARATE FROM [EXPECTED_DIFF] now that that table is key-keyed, not name-keyed.
     */
    val LENIENT: Set<String> = setOf("LENIENT-comment", "LENIENT-squote", "LENIENT-trailing")

    // ---------------------------------------------------------------- THE ONE FORMATTER
    /**
     * SHAREDNEWSIDE-2026-11-14: \u26d4 THE COMPARED STRINGS ARE BUILT HERE AND NOWHERE ELSE.
     * Each side holds different types (MapStateStore.* on the Droid's OLD side, Gt* here), so every
     * side could flatten its own result -- and then a difference between two FLATTENERS reads as a
     * difference between two IMPLEMENTATIONS, or between two DEVICES. These five functions exist
     * once, in common code, and every side hands them plain scalars. A compared key can then only
     * differ because the service differed.
     */
    fun typeLine(type: String, state: Int, rowCount: Int, ids: Set<String>?): String =
        type + "|" + state + "|" + rowCount + "|" + (ids?.sorted()?.joinToString(",") ?: "null")
    fun panelLine(tiles: Boolean, trails: Boolean, remove: Boolean): String = "$tiles|$trails|$remove"
    fun bboxLine(s: Double?, w: Double?, n: Double?, e: Double?, z: Double?): String =
        if (s == null) "null" else "$s|$w|$n|$e|$z"
    fun fitLine(id: String?, name: String?, type: String?): String =
        if (id == null) "null" else "$id|$name|$type"
    fun routeLine(open: Boolean?, status: String?, draft: String?): String =
        if (open == null) "null" else "$open|$status|$draft"

    /**
     * "why" is DERIVED from the result, and that is sound: it asks "did this input land in the
     * defaults() branch?", which IS a decision the service makes. It is read off the result rather
     * than reported by the implementation, because asking the port to announce its own branch would
     * mean changing the port to suit the test. A file that genuinely encodes the defaults reads
     * "defaults" on BOTH sides, so the two still agree -- the key can only differ when the branch did.
     */
    fun why(flat: List<String>, flatDefaults: List<String>): String =
        if (flat == flatDefaults) "defaults" else "parsed"

    /** The 8-element flat list -> a Snap. Built one way, for every side. */
    fun snapOf(flat: List<String>, flatDefaults: List<String>): Snap =
        Snap(why(flat, flatDefaults), flat.take(4), flat[4], flat[5], flat[6], flat[7])

    /** Flatten a shared snapshot the one way. Also used by the Droid's OLD side, via its own scalars. */
    fun flatten(s: GtMapSnapshot): List<String> =
        GtMapState.TYPES.map { t ->
            val ts = s.types[t]
            typeLine(t, ts?.state ?: 0, ts?.rows?.size ?: 0, GtMapState.checkedIdsFor(s, t))
        } + listOf(
            panelLine(s.panel.tilesChecked, s.panel.trailsChecked, s.panel.removeTilesChecked),
            bboxLine(s.bbox?.south, s.bbox?.west, s.bbox?.north, s.bbox?.east, s.bbox?.zoom),
            fitLine(s.fitArtifact?.id, s.fitArtifact?.name, s.fitArtifact?.type),
            routeLine(s.routeState?.open, s.routeState?.status, s.routeState?.draftName),
        )

    // ---------------------------------------------------------------- THE ONE NEW SIDE
    /**
     * SHAREDNEWSIDE-2026-11-14: the NEW side, in COMMON code, so the Droid and the iPad run the same bytes.
     * [files] and [paths] are the caller's -- each device points them at its own throwaway directory,
     * which is the only thing that differs between the two runs.
     */
    fun newSide(files: GtFiles, paths: GtImportPaths): Side = object : Side {
        private val store = GtMapState(files, paths)

        private fun write(name: String, text: String, exists: Boolean) {
            val d = paths.dir("state"); files.fs.createDirectories(d)
            val p = d / name
            if (exists) files.fs.write(p) { writeUtf8(text) } else try { files.fs.delete(p) } catch (e: Exception) { }
        }

        private fun snap(s: GtMapSnapshot): Snap = snapOf(flatten(s), flatten(store.defaults()))

        override fun readMapAfter(mapKey: String, text: String, exists: Boolean): Snap {
            write(mapKey + "_panel.json", text, exists); return snap(store.readMap(mapKey))
        }
        override fun readViewAfter(mapKey: String, text: String, exists: Boolean): String {
            write(mapKey + "_view.json", text, exists)
            val v = store.readView(mapKey); return v.mapType + "|" + v.offline
        }
        override fun roundTrip(mapKey: String, text: String): Snap {
            write(mapKey + "_panel.json", text, true)
            store.saveMap(mapKey, store.readMap(mapKey))   // read it, write it back, read it again
            return snap(store.readMap(mapKey))
        }
        override fun rideKey(): String = GtMapState.rideKey()
        override fun typeDefaults(): String =
            GtMapState.TYPES.joinToString(";") { it + "=" + GtMapState.typeDefault(it) }
    }

    private fun emit(sink: (String, String) -> Unit, key: String, s: Snap) {
        sink("$key|why", s.why)
        // EMITFIX-2026-11-14: \u26d4 INDEX THE KEY. This was "$key|type" for all four types, so a
        // runner collecting into a map kept only the LAST one and compared 1 type of 4 while
        // reporting SAME on all of them. The index is the TYPES position, so the key is stable
        // across runs and devices.
        for ((i, t) in s.perType.withIndex()) sink("$key|type$i", t)
        sink("$key|panel", s.panel)
        sink("$key|bbox", s.bbox)
        sink("$key|fit", s.fit)
        sink("$key|route", s.route)
    }

    /** One side's whole run. [mapKey] is the cert's own key, never the app's. */
    fun run(side: Side, mapKey: String, sink: (String, String) -> Unit) {
        sink("CONST|rideKey", side.rideKey())
        sink("CONST|typeDefaults", side.typeDefaults())
        sink("CONST|zoomDefault", GtMapState.DEFAULT_ZOOM.toString())
        sink("CONST|statusDefault", GtMapState.DEFAULT_STATUS)
        sink("CONST|types", GtMapState.TYPES.joinToString(","))
        for ((name, text, exists) in FIXTURES) {
            emit(sink, "READ|$name", side.readMapAfter(mapKey, text, exists))
            sink("VIEW|$name", side.readViewAfter(mapKey, text, exists))
        }
        // the ROUND-TRIP key: only fixtures that parse on BOTH sides can round-trip meaningfully
        for ((name, text, exists) in FIXTURES) {
            if (!exists || name in LENIENT) continue   // EXPECTEDKEYS-2026-11-14
            emit(sink, "RT|$name", side.roundTrip(mapKey, text))
        }
    }
}

private const val GOOD: String =
    "{\"artifacts\":{" +
    "\"Trails\":{\"state\":2,\"rows\":[{\"id\":\"t1\",\"name\":\"Rim\",\"checked\":true}," +
    "{\"id\":\"t2\",\"name\":\"Wash\",\"checked\":false},{\"id\":\"\",\"name\":\"dropme\",\"checked\":true}]}," +
    "\"Tracks\":{\"state\":1,\"rows\":[]},\"Waypoints\":{\"state\":0,\"rows\":[]}," +
    "\"Routes\":{\"state\":2,\"rows\":[{\"id\":\"r9\",\"name\":\"Loop\",\"checked\":true}]}}," +
    "\"panel\":{\"tilesChecked\":true,\"trailsChecked\":false,\"removeTilesChecked\":true}," +
    "\"bbox\":{\"south\":37.8,\"west\":-113.0,\"north\":38.3,\"east\":-112.4,\"zoom\":12.5}," +
    "\"fitArtifact\":{\"id\":\"a1\",\"name\":\"Bar 10\",\"type\":\"Trails\"}," +
    "\"routeState\":{\"open\":true,\"status\":\"draft\",\"draftName\":\"wip-1\"}}"

/**
 * LENIENTFIX-2026-11-14: the LENIENT fixtures' shared body -- VALID json except for the one tolerance each
 * adds. \u26d4 IT MUST CARRY REAL CONTENT. With "{\"artifacts\":{}}" the two sides agreed on every key but
 * one, because an empty artifacts map flattens exactly like defaults() -- the divergence the fixture
 * exists to prove was invisible. With a state, a row and a panel box, OLD (org.json, parses) and NEW
 * (kotlinx, refuses -> defaults) differ on the type row, the checked ids and the panel.
 */
private const val LEN_BODY: String =
    "{\"artifacts\":{\"Trails\":{\"state\":2,\"rows\":[{\"id\":\"x1\",\"name\":\"Rim\",\"checked\":true}]}}," +
    "\"panel\":{\"tilesChecked\":true,\"trailsChecked\":false,\"removeTilesChecked\":false}"

/** The same body with every double quote turned into a single quote -- org.json accepts it, kotlinx does not. */
private val LEN_SQUOTE: String = (LEN_BODY + "}").replace('"', '\'')
