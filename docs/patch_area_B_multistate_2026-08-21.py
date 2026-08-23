#!/usr/bin/env python3
"""
patch_area_B_multistate_2026-08-21.py

PATCH B of 3 — the controller becomes area-capable.
REQUIRES PATCH A APPLIED FIRST (anchors below are post-A text).

The shape, per Fred 2026-08-21: the bbox is the decider, where it comes from
is the driver. State run and area run share one pipeline; only the inputs
differ.

  execute(context, state)                    -> thin wrapper, unchanged callers
  executeArea(context, bbox, states)         -> NEW entry point
  executeRun(...)                            -> the shared core

buildManifest takes the bbox + the state LIST and emits ONE Geofabrik row per
state the bbox touches. The multi-state loop is therefore NOT a new loop -- the
existing source loop simply sees N rows instead of 1. That is what makes
granular recovery latent: the whole plan is written down before any of it runs.

The bbox lives ONCE at area.bbox. Rows carry only their own identity (slug,
url, name). A per-row bbox copy would be the same number written N times, and
the moment they could disagree is the moment something is wrong.

Also: setPendingImport's scope string now follows the process type. A
geofabrik_area row must not tell the OSM worker it is a whole-state import.

DRY RUN BY DEFAULT.  Re-run with --apply.
Marker: AREABUILD-2026-08-21B
"""

import sys, os, shutil, datetime

MARKER = "AREABUILD-2026-08-21B"
PRIOR  = "AREAREFACTOR-2026-08-21A"
REPO = r"C:\Users\kixaz\Meshtastic-Android"
REL  = r"app\src\main\java\com\geeksville\mesh\convoy\HomeStateImportController.kt"
BACKUP_DIR = r"C:\Users\kixaz\Downloads"

# ── B1. buildManifest signature + the area block ─────────────────────
B1_OLD = '''    private fun buildManifest(
        state: GeofabrikState,
        catalogSourceIds: List<String>,
        catalogSourceNames: Map<String, String>
    ): JSONObject {
        val area = JSONObject().apply {
            put("type", "state")
            put("state", state.name)
            put("bbox", JSONArray(listOf(state.bboxWest, state.bboxSouth, state.bboxEast, state.bboxNorth)))
            put("states", JSONArray(listOf(state.parentState ?: state.name)))
        }'''

B1_NEW = '''    // ''' + MARKER + ''': takes the RUN's bbox and the state LIST. A state import
    // passes one state and its own corners; an area import passes the drawn box
    // and every state it touches. Nothing downstream can tell the difference.
    private fun buildManifest(
        areaType: String,
        areaLabel: String,
        bboxSouth: Double, bboxWest: Double, bboxNorth: Double, bboxEast: Double,
        states: List<GeofabrikState>,
        geofabrikProcess: String,
        catalogSourceIds: List<String>,
        catalogSourceNames: Map<String, String>
    ): JSONObject {
        val area = JSONObject().apply {
            put("type", areaType)
            put("state", areaLabel)
            // bbox order is W,S,E,N -- matches the existing file and the design spec.
            put("bbox", JSONArray(listOf(bboxWest, bboxSouth, bboxEast, bboxNorth)))
            put("states", JSONArray(states.map { it.parentState ?: it.name }.distinct()))
        }'''

# ── B2. one Geofabrik row per state ──────────────────────────────────
B2_OLD = '''        // Geofabrik entry last
        sources.put(JSONObject().apply {
            put("id", "geofabrik_${state.slug.replace("/", "_")}")
            put("name", "${state.name} Open Source Maps")
            put("process", "geofabrik_full_state")
            put("slug", state.slug)
            put("gpkg_url", state.gpkgUrl)
            put("status", "pending")
            put("imported", 0)
            // MANIFESTCOUNTS-2026-08-21: recap counters. selected = dupes + adds + errors.
            put("processed", 0)
            put("selected", 0)
            put("dupes", 0)
            put("adds", 0)
            put("errors", 0)
        })'''

B2_NEW = '''        // Geofabrik entries last (sparse data -- catalog sources enrich first).
        // ''' + MARKER + ''': ONE ROW PER STATE the bbox touches. The execution loop
        // is unchanged -- it just sees N pending rows. Border trails arrive WHOLE
        // in both states' extracts and geom_hash collapses them, so overlap is
        // absorbed rather than duplicated (measured 07-28: 313 UT/AZ border trails,
        // all 313 hash-matching).
        for (gs in states) {
            sources.put(JSONObject().apply {
                put("id", "geofabrik_${gs.slug.replace("/", "_")}")
                put("name", "${gs.name} Open Source Maps")
                put("process", geofabrikProcess)
                put("slug", gs.slug)
                put("gpkg_url", gs.gpkgUrl)
                put("status", "pending")
                put("imported", 0)
                // MANIFESTCOUNTS-2026-08-21: recap counters. selected = dupes + adds + errors.
                put("processed", 0)
                put("selected", 0)
                put("dupes", 0)
                put("adds", 0)
                put("errors", 0)
            })
        }'''

# ── B3. manifest_id ──────────────────────────────────────────────────
B3_OLD = '''            put("manifest_id", "${state.slug}-${iso8601Now()}")'''
B3_NEW = '''            // ''' + MARKER + ''': areaLabel is already slug-safe (see executeArea).
            put("manifest_id", "${areaLabel.lowercase().replace(' ', '-')}-${iso8601Now()}")'''

# ── B4. execute -> wrapper + executeArea + shared core ───────────────
B4_OLD = '''    suspend fun execute(context: Context, state: GeofabrikState): Boolean =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            Log.i(TAG, "Starting home state import for ${state.name}")
            // 1. Find catalog sources that intersect the state bbox
            val catalogSources = findCatalogSourcesInBbox(
                context, state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast
            )
            val catalogIds = catalogSources.map { it.first }
            val catalogNames = catalogSources.associate { it.first to it.second }
            // 2. Build and write the manifest
            val manifest = buildManifest(state, catalogIds, catalogNames)
            val mFile = manifestFile(context, state.name)'''

B4_NEW = '''    // ''' + MARKER + ''': STATE entry point -- unchanged for every existing caller.
    // A state import is an area import whose bbox happens to be a state's corners.
    suspend fun execute(context: Context, state: GeofabrikState): Boolean =
        executeRun(
            context,
            areaType = "state",
            areaLabel = state.name,
            bboxSouth = state.bboxSouth, bboxWest = state.bboxWest,
            bboxNorth = state.bboxNorth, bboxEast = state.bboxEast,
            states = listOf(state),
            geofabrikProcess = "geofabrik_full_state"
        )

    /**
     * ''' + MARKER + ''': AREA entry point. The rider draws a box; the caller
     * resolves the states with GeofabrikCatalog.findByBbox() and hands them here.
     *
     * ⚠ The states' own Geofabrik bboxes are the REFERENCE used to select them,
     * never the area imported. Utah's bbox clips corners of NV/AZ/CO/WY -- filtering
     * by it would pull ground the rider never drew. Every source filters by the
     * drawn bbox.
     */
    suspend fun executeArea(
        context: Context,
        bboxSouth: Double, bboxWest: Double, bboxNorth: Double, bboxEast: Double,
        states: List<GeofabrikState>
    ): Boolean {
        // Slug-safe: this label becomes the manifest filename AND the manifest_id.
        val label = if (states.size == 1) "area-${states[0].slug}"
                    else "area-${states.size}-states"
        return executeRun(
            context,
            areaType = "bbox",
            areaLabel = label,
            bboxSouth = bboxSouth, bboxWest = bboxWest,
            bboxNorth = bboxNorth, bboxEast = bboxEast,
            states = states,
            geofabrikProcess = "geofabrik_area"
        )
    }

    private suspend fun executeRun(
        context: Context,
        areaType: String,
        areaLabel: String,
        bboxSouth: Double, bboxWest: Double, bboxNorth: Double, bboxEast: Double,
        states: List<GeofabrikState>,
        geofabrikProcess: String
    ): Boolean =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            Log.i(TAG, "Starting import: $areaLabel type=$areaType " +
                "states=${states.size} bbox S=$bboxSouth W=$bboxWest N=$bboxNorth E=$bboxEast")
            // 1. Catalog sources intersecting the RUN's bbox
            val catalogSources = findCatalogSourcesInBbox(
                context, bboxSouth, bboxWest, bboxNorth, bboxEast
            )
            val catalogIds = catalogSources.map { it.first }
            val catalogNames = catalogSources.associate { it.first to it.second }
            // 2. Build and write the manifest -- the whole plan, before any of it runs
            val manifest = buildManifest(
                areaType, areaLabel,
                bboxSouth, bboxWest, bboxNorth, bboxEast,
                states, geofabrikProcess, catalogIds, catalogNames
            )
            val mFile = manifestFile(context, areaLabel)'''

# ── B5. dispatch: both geofabrik types, run bbox ─────────────────────
B5_OLD = '''                // ''' + PRIOR + ''': runners now take the run's bbox, not a state object.
                val ok = when (src.getString("process")) {
                    "geofabrik_full_state" -> runGeofabrikSource(
                        context, src,
                        state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast)
                    "trails_list_area" -> runCatalogSource(
                        context, src,
                        state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast)'''

B5_NEW = '''                // ''' + PRIOR + ''': runners take the run's bbox, not a state object.
                // ''' + MARKER + ''': geofabrik_area routes to the SAME runner --
                // download and extract are identical (Geofabrik only ships whole
                // states); only the import filter differs, and that is the bbox.
                val ok = when (src.getString("process")) {
                    "geofabrik_full_state", "geofabrik_area" -> runGeofabrikSource(
                        context, src,
                        bboxSouth, bboxWest, bboxNorth, bboxEast)
                    "trails_list_area" -> runCatalogSource(
                        context, src,
                        bboxSouth, bboxWest, bboxNorth, bboxEast)'''

# ── B6. scope string follows the process type ────────────────────────
B6_OLD = '''        val bbox = doubleArrayOf(bboxSouth, bboxWest, bboxNorth, bboxEast)
        OsmImportLedger.setPendingImport(context, slug, "state", bbox)'''

B6_NEW = '''        val bbox = doubleArrayOf(bboxSouth, bboxWest, bboxNorth, bboxEast)
        // ''' + MARKER + ''': the scope must match the row. Telling the OSM worker
        // "state" on an area row would invite it to treat the run as whole-state.
        val scope = if (src.optString("process") == "geofabrik_area") "area" else "state"
        OsmImportLedger.setPendingImport(context, slug, scope, bbox)'''

EDITS = [
    ("buildManifest signature + area", B1_OLD, B1_NEW),
    ("geofabrik row per state",        B2_OLD, B2_NEW),
    ("manifest_id from areaLabel",     B3_OLD, B3_NEW),
    ("execute -> wrapper + core",      B4_OLD, B4_NEW),
    ("dispatch both geofabrik types",  B5_OLD, B5_NEW),
    ("setPendingImport scope",         B6_OLD, B6_NEW),
]


def main():
    apply = "--apply" in sys.argv
    target = os.path.join(REPO, REL)

    if not os.path.isfile(target):
        print("ABORT: not found:\n  %s" % target); return 1

    with open(target, "r", encoding="utf-8") as f:
        src = f.read()

    if PRIOR not in src:
        print("ABORT: patch A (%s) is NOT applied. Run it first." % PRIOR); return 4
    if MARKER in src:
        print("Already applied (%s present)." % MARKER); return 0

    problems = []
    for name, old, _new in EDITS:
        n = src.count(old)
        if n != 1:
            problems.append("  %-32s found %d times (need 1)" % (name, n))
        else:
            print("  OK  %-32s anchor matched" % name)
    if problems:
        print("\nABORT -- no write:"); print("\n".join(problems)); return 2

    out = src
    for _name, old, new in EDITS:
        out = out.replace(old, new, 1)

    # B7: every remaining `state.name` belongs to the old execute body -> areaLabel
    n_state_name = out.count("state.name")
    out = out.replace("state.name", "areaLabel")
    print("\n  OK  replaced %d remaining `state.name` -> `areaLabel`" % n_state_name)

    leftovers = [t for t in ("state.bbox", "state.slug", "state.parentState", "state.gpkgUrl")
                 if t in out]
    if leftovers:
        print("\nABORT -- stale `state.` references remain after patch:")
        for t in leftovers:
            print("    %s  x%d" % (t, out.count(t)))
        print("  These would not compile. No write.")
        return 5

    print("  OK  no stale state.* references remain")
    print("\nMarker occurrences after patch: %d (expect 7)" % out.count(MARKER))

    if not apply:
        print("\nDRY RUN -- NOTHING WRITTEN.")
        print("  python %s --apply" % os.path.basename(__file__)); return 0

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = os.path.join(BACKUP_DIR, "HomeStateImportController.kt.bak_%s" % stamp)
    shutil.copy2(target, backup)
    with open(target, "w", encoding="utf-8") as f:
        f.write(out)
    print("Backup: %s" % backup)
    print("APPLIED: %s" % target)
    print("\nSTATE PATH BEHAVIOUR MUST BE UNCHANGED. Compile gate:")
    print("  ./gradlew compileGoogleReleaseKotlin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
