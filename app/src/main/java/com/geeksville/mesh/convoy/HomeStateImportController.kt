package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HomeStateImportController — orchestrates the full home state import.
 *
 * ONE ENTRY POINT:
 *   HomeStateImportController.execute(context, geofabrikState)
 *
 * Builds a JSON manifest listing every source, loops through each,
 * dispatches by type (Geofabrik → download + extract + import workers;
 * catalog → TrailImporter.importByArea), updates the manifest at every
 * state change for recovery, and reports progress via StateFlow.
 *
 * RECOVERY: on restart, scan importsDir() for any manifest with
 * process_state == "in_progress" and resume from the first non-completed source.
 */

private const val TAG = "HomeStateImport"
private const val MINUTES_PER_SOURCE = 7

// ── Progress data exposed to the UI ──────────────────────────────

data class ImportSourceProgress(
    val id: String,
    val name: String,
    val processType: String,        // "geofabrik_full_state" | "trails_list_area"
    val status: String,             // "pending" | "in_progress" | "completed" | "failed"
    val imported: Int = 0,
    // MANIFESTUI-2026-08-21: the recap five. -1 means NOT REPORTED by this source,
    // which is not the same as zero -- the UI must be able to say so.
    val processed: Int = -1,
    val selected: Int = -1,
    val dupes: Int = -1,
    val adds: Int = -1,
    val errors: Int = -1,
    val currentStep: String? = null, // "Downloading" | "Extracting" | "Importing" | "Cleanup"
    val stepDetail: String? = null,  // "47,200 of 112,000"
)

data class ImportProgress(
    val stateName: String,
    val totalSources: Int,
    val currentSourceIndex: Int,    // 0-based
    val sources: List<ImportSourceProgress>,
    val phase: String,              // "running" | "completed" | "failed"
    val elapsedMs: Long = 0,
) {
    val estimatedMinutesRemaining: Int
        get() = (totalSources - sources.count { it.status == "completed" }) * MINUTES_PER_SOURCE
}

object HomeStateImportController {

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    internal val downloadDetailFlow = MutableStateFlow<String?>(null)
    val progress: StateFlow<ImportProgress?> = _progress

    // ── Manifest I/O ─────────────────────────────────────────────

    private fun importsDir(ctx: Context): File {
        val d = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ),
            "GroupTrack/imports"
        )
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun manifestFile(ctx: Context, stateName: String): File =
        File(importsDir(ctx), "import_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())}_${stateName.lowercase().replace(" ", "-")}_state.json")

    private fun writeManifest(file: File, manifest: JSONObject) {
        file.writeText(manifest.toString(2))
    }

    // AREABUILD-2026-08-21B: takes the RUN's bbox and the state LIST. A state import
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
        }

        val sources = JSONArray()

        // Catalog sources first (rich data), Geofabrik last (sparse)
        for (sid in catalogSourceIds) {
            sources.put(JSONObject().apply {
                put("id", sid)
                put("name", catalogSourceNames[sid] ?: sid)
                put("process", "trails_list_area")
                put("status", "pending")
                put("imported", 0)
                // MANIFESTCOUNTS-2026-08-21: recap counters. selected = dupes + adds + errors.
                put("processed", 0)
                put("selected", 0)
                put("dupes", 0)
                put("adds", 0)
                put("errors", 0)
            })
        }

        // Geofabrik entries last (sparse data -- catalog sources enrich first).
        // AREABUILD-2026-08-21B: ONE ROW PER STATE the bbox touches. The execution loop
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
        }

        return JSONObject().apply {
            // MANIFESTCOUNTS-2026-08-21: stable per-run identifier. Field, not filename --
            // manifests written before this change have no id and must still read
            // as valid. Slug (not name) because California ships as two entries.
            // AREABUILD-2026-08-21B: areaLabel is already slug-safe (see executeArea).
            put("manifest_id", "${areaLabel.lowercase().replace(' ', '-')}-${iso8601Now()}")
            put("process_state", "in_progress")
            put("started_at", iso8601Now())
            put("completed_at", JSONObject.NULL)
            put("area", area)
            put("sources", sources)
        }
    }

    // ── The main entry point ─────────────────────────────────────

    /**
     * Execute the full home state import. Call from a coroutine scope.
     * Updates [progress] StateFlow throughout.
     * Returns true if all sources completed successfully.
     */
    // AREABUILD-2026-08-21B: STATE entry point -- unchanged for every existing caller.
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
     * AREABUILD-2026-08-21B: AREA entry point. The rider draws a box; the caller
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
            val mFile = manifestFile(context, areaLabel)
            writeManifest(mFile, manifest)

            val sources = manifest.getJSONArray("sources")
            val totalSources = sources.length()

            Log.i(TAG, "Manifest: $totalSources sources, file=${mFile.name}")

            // 3. Publish initial progress
            publishProgress(areaLabel, totalSources, sources, "running", startMs)

            // 4. Loop through sources
            var allOk = true
            for (i in 0 until totalSources) {
                val src = sources.getJSONObject(i)
                if (src.getString("status") == "completed") continue

                // Mark in_progress
                src.put("status", "in_progress")
                src.put("started_at", iso8601Now())
                writeManifest(mFile, manifest)
                publishProgress(areaLabel, totalSources, sources, "running", startMs)

                // AREAREFACTOR-2026-08-21A: runners take the run's bbox, not a state object.
                // AREABUILD-2026-08-21B: geofabrik_area routes to the SAME runner --
                // download and extract are identical (Geofabrik only ships whole
                // states); only the import filter differs, and that is the bbox.
                val ok = when (src.getString("process")) {
                    "geofabrik_full_state", "geofabrik_area" -> runGeofabrikSource(
                        context, src,
                        bboxSouth, bboxWest, bboxNorth, bboxEast)
                    "trails_list_area" -> runCatalogSource(
                        context, src,
                        bboxSouth, bboxWest, bboxNorth, bboxEast)
                    else -> {
                        Log.e(TAG, "Unknown process type: ${src.getString("process")}")
                        false
                    }
                }

                if (ok) {
                    src.put("status", "completed")
                    src.put("completed_at", iso8601Now())
                } else {
                    src.put("status", "failed")
                    allOk = false
                }
                writeManifest(mFile, manifest)
                publishProgress(areaLabel, totalSources, sources, "running", startMs)
            }

            // 5. Mark manifest complete
            manifest.put("process_state", if (allOk) "completed" else "completed")
            manifest.put("completed_at", iso8601Now())
            writeManifest(mFile, manifest)
            publishProgress(areaLabel, totalSources, sources,
                if (allOk) "completed" else "failed", startMs)

            Log.i(TAG, "Home state import ${if (allOk) "COMPLETED" else "COMPLETED WITH ERRORS"} " +
                "for ${areaLabel} in ${(System.currentTimeMillis() - startMs) / 1000}s")
            allOk
        }

    // ── Geofabrik source: download → extract → import → cleanup ──

    // AREAREFACTOR-2026-08-21A: `state` removed. A multi-state area run has no single
    // state -- each Geofabrik row IS its own state, and its slug/url/name already
    // live on the row. The bbox is a property of the RUN, so it is passed in.
    private suspend fun runGeofabrikSource(
        context: Context, src: JSONObject,
        bboxSouth: Double, bboxWest: Double, bboxNorth: Double, bboxEast: Double
    ): Boolean {
        val slug = src.getString("slug")
        val gpkgUrl = src.getString("gpkg_url")

        // Step 1: Download
        updateSourceStep(src, "Downloading", "$gpkgUrl")
        val zipFile = OsmImportStage.zipFor(context, slug)
        if (!zipFile.exists()) {
            val ok = downloadFile(gpkgUrl, zipFile, src)
            if (!ok) {
                Log.e(TAG, "Download failed for $slug")
                return false
            }
        }
        downloadDetailFlow.value = null
        Log.i(TAG, "Download complete: ${zipFile.name} (${zipFile.length() / 1_048_576} MB)")

        // Step 2: Create the ledger (required before setPendingImport)
        OsmImportLedger.create(
            context, slug,
            OsmImportStage.displayName(slug),
            "Geofabrik ${src.optString("name", slug)} (auto download)",
            OsmImportLedger.priorImports(context, slug)
        )
        Log.i(TAG, "Ledger created for $slug")

        // Step 3: Extract
        updateSourceStep(src, "Extracting", null)
        OsmExtractWorker.enqueue(context, slug)
        val extractOk = awaitWorker(context, OsmExtractWorker.uniqueName(slug))
        if (!extractOk) {
            Log.e(TAG, "Extract failed for $slug")
            return false
        }

        // Step 4: Set pending bbox AFTER extract (extract may init the ledger)
        // AREAREFACTOR-2026-08-21A: the RUN's bbox, not the state's. For a state import these
        // are the same four numbers. For an area import they are the drawn box --
        // which is the whole point: a state's Geofabrik bbox clips neighbouring
        // states' corners and would pull ground the rider never drew.
        val bbox = doubleArrayOf(bboxSouth, bboxWest, bboxNorth, bboxEast)
        // AREABUILD-2026-08-21B: the scope must match the row. Telling the OSM worker
        // "state" on an area row would invite it to treat the run as whole-state.
        val scope = if (src.optString("process") == "geofabrik_area") "area" else "state"
        OsmImportLedger.setPendingImport(context, slug, scope, bbox)
        Log.i(TAG, "setPendingImport for $slug: bbox " +
            "S=$bboxSouth W=$bboxWest N=$bboxNorth E=$bboxEast")

        // Step 5: Import
        updateSourceStep(src, "Importing", null)
        OsmImportWorker.enqueue(context, slug)
        val importOk = awaitWorker(context, OsmImportWorker.uniqueName(slug))
        if (!importOk) {
            Log.e(TAG, "Import failed for $slug")
            return false
        }

        // Read imported count from the ledger
        val ledger = OsmImportLedger.read(context, slug)
        val imports = ledger?.optJSONArray("imports")
        if (imports != null && imports.length() > 0) {
            val last = imports.getJSONObject(imports.length() - 1)
            val count = last.optInt("inserted", 0) + last.optInt("updated", 0)
            src.put("imported", count)
            // MANIFESTCOUNTS-2026-08-21: the recap five from the OSM ledger.
            // -1 means NOT REPORTED by this ledger, which is not the same as
            // zero. The recap must be able to say "not reported" rather than
            // claim a clean run it cannot vouch for.
            val gAdds    = last.optInt("inserted", -1)
            val gDropped = last.optInt("dropped", -1)
            val gAliased = last.optInt("aliased", -1)
            val gErrors  = last.optInt("errors", -1)
            val gFound   = last.optInt("found", -1)
            val gDupes   = if (gDropped < 0 && gAliased < 0) -1
                           else maxOf(gDropped, 0) + maxOf(gAliased, 0)
            src.put("processed", gFound)
            // Whole-state import applies no out-of-area cut, so nothing is
            // rejected: selected == processed. The BY AREA path must override
            // this when it lands -- there, selected is the bbox result.
            src.put("selected", gFound)
            src.put("dupes", gDupes)
            src.put("adds", gAdds)
            src.put("errors", gErrors)
        }

        // Step 5: Cleanup
        updateSourceStep(src, "Cleanup", null)
        OsmImportStage.discardState(context, slug)

        return true
    }

    // ── Catalog source: importByArea (existing function) ─────────

    // AREAREFACTOR-2026-08-21A: `state` removed -- this path only ever wanted the bbox.
    private suspend fun runCatalogSource(
        context: Context, src: JSONObject,
        bboxSouth: Double, bboxWest: Double, bboxNorth: Double, bboxEast: Double
    ): Boolean {
        val sourceId = src.getString("id")
        updateSourceStep(src, "Importing", null)

        return try {
            val results = TrailImporter.importByArea(
                context, listOf(sourceId),
                bboxSouth, bboxWest, bboxNorth, bboxEast
            )
            val total = results.sumOf { it.inserted }
            src.put("imported", total)
            // MANIFESTCOUNTS-2026-08-21: the recap five, straight off ImportResult --
            // TrailImporter already computes every one of these and discards
            // all but `inserted`. processed = the whole tally; `rejected` is
            // out-of-area, so selected is processed minus rejected.
            val pDropped  = results.sumOf { it.dropped }
            val pAliased  = results.sumOf { it.aliased }
            val pSkipped  = results.sumOf { it.skipped }
            val pRejected = results.sumOf { it.rejected }
            val pErrors   = results.sumOf { it.errors }
            val pProcessed = total + pDropped + pAliased + pSkipped + pRejected + pErrors
            src.put("processed", pProcessed)
            src.put("selected", pProcessed - pRejected)
            src.put("dupes", pDropped + pAliased + pSkipped)
            src.put("adds", total)
            src.put("errors", pErrors)
            Log.i(TAG, "Catalog source $sourceId: $total records")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Catalog source $sourceId failed", e)
            false
        }
    }

    // ── Find catalog sources that intersect the state bbox ───────

    private fun findCatalogSourcesInBbox(
        context: Context,
        south: Double, west: Double, north: Double, east: Double
    ): List<Pair<String, String>> {
        // Uses the same loadSourceCatalog + bbox overlap filter as
        // ConvoyTrailSourceScreen:369 — same catalog, same check.
        return try {
            val all = loadSourceCatalog(context)
            all.filter { src ->
                src.status != "display_only_not_queryable" &&
                src.boundaryN >= south && src.boundaryS <= north &&
                src.boundaryE >= west && src.boundaryW <= east
            }.map { it.id to it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query catalog sources", e)
            emptyList()
        }
    }

    // ── HTTP download ────────────────────────────────────────────

    private fun downloadFile(urlStr: String, dest: File, activeSrc: JSONObject? = null): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()
            conn.instanceFollowRedirects = true
            if (conn.responseCode == 302 || conn.responseCode == 301) {
                val redirect = conn.getHeaderField("Location")
                conn.disconnect()
                if (redirect != null) return downloadFile(redirect, dest, activeSrc)
                return false
            }
            if (conn.responseCode != 200) {
                Log.e(TAG, "Download HTTP ${conn.responseCode} for $urlStr")
                conn.disconnect()
                return false
            }
            val totalBytes = conn.contentLength.toLong()
            var downloaded = 0L
            val tmp = File(dest.parent, dest.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                conn.inputStream.use { inp ->
                    val buf = ByteArray(1 shl 16)
                    var n: Int
                    while (inp.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        downloaded += n
                        if (activeSrc != null && totalBytes > 0) {
                            val dlMB = downloaded / 1_048_576
                            val totMB = totalBytes / 1_048_576
                            val detail = "$dlMB MB of $totMB MB"
                            activeSrc.put("step_detail", detail)
                            downloadDetailFlow.value = detail
                        }
                    }
                }
            }
            conn.disconnect()
            tmp.renameTo(dest)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $urlStr", e)
            false
        }
    }

    // ── Wait for a WorkManager unique work to finish ─────────────

    private suspend fun awaitWorker(context: Context, uniqueName: String): Boolean {
        val wm = WorkManager.getInstance(context)
        while (true) {
            val infos = wm.getWorkInfosForUniqueWork(uniqueName).get()
            val wi = infos.firstOrNull() ?: break
            when (wi.state) {
                WorkInfo.State.SUCCEEDED -> return true
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> return false
                else -> delay(2_000)
            }
        }
        return false
    }

    // ── Progress publishing ──────────────────────────────────────

    private fun updateSourceStep(src: JSONObject, step: String, detail: String?) {
        src.put("current_step", step)
        if (detail != null) src.put("step_detail", detail)
        else src.remove("step_detail")
    }

    private fun publishProgress(
        stateName: String, total: Int, sources: JSONArray,
        phase: String, startMs: Long
    ) {
        val list = (0 until sources.length()).map { i ->
            val s = sources.getJSONObject(i)
            ImportSourceProgress(
                id = s.getString("id"),
                name = s.getString("name"),
                processType = s.getString("process"),
                status = s.getString("status"),
                imported = s.optInt("imported", 0),
                // MANIFESTUI-2026-08-21: manifests written before 2026-08-21 carry no
                // counters. Absent must read as -1 (not reported), never 0.
                processed = s.optInt("processed", -1),
                selected = s.optInt("selected", -1),
                dupes = s.optInt("dupes", -1),
                adds = s.optInt("adds", -1),
                errors = s.optInt("errors", -1),
                currentStep = s.optString("current_step", null),
                stepDetail = s.optString("step_detail", null),
            )
        }
        _progress.value = ImportProgress(
            stateName = stateName,
            totalSources = total,
            currentSourceIndex = list.indexOfFirst { it.status == "in_progress" },
            sources = list,
            phase = phase,
            elapsedMs = System.currentTimeMillis() - startMs,
        )
    }

    // ── Recovery: find in-progress manifests ─────────────────────

    /**
     * Scan the imports directory for any manifest with process_state == "in_progress".
     * Returns the file and parsed JSON, or null if none found.
     */
    fun findIncompleteImport(ctx: Context): Pair<File, JSONObject>? {
        val dir = importsDir(ctx)
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    val j = JSONObject(f.readText())
                    if (j.optString("process_state") == "in_progress") f to j else null
                } catch (_: Exception) { null }
            }
            ?.firstOrNull()
    }

    /**
     * True if at least one completed import manifest exists.
     * No completed manifests = never imported = fresh install.
     */
    // ═══ GATEJOB-2026-08-21F — THE AUTHORITY STARTUP JOB ═══════════════
    //
    // Runs inside the authority task, AFTER the gate's checks pass and BEFORE
    // Convoy. That slot is what makes both of these safe: the all-files grant is
    // already proven by use, and nothing can have launched an import yet.

    /**
     * Resolve every manifest left in imports/. Runs EVERY launch.
     *
     *   every source has a count  -> completed -> move to history
     *   any source has no count   -> stamp killed -> move to history
     *
     * Afterwards imports/ is empty. Fred, 08-21: "completed is swept,
     * incomplete is killed on startup. Period."
     *
     * ⛔ ORDER IS LOAD-BEARING: STAMP FIRST, THEN MOVE. Same reason
     * OsmImportLedger.archiveLedger() renames before discardState() sweeps. If
     * the stamp fails nothing has moved and it retries next launch. If the move
     * fails after a good stamp, a marked file sits in imports/ -- visible and
     * recoverable. The state to avoid is a file in history that nobody stamped,
     * because then "killed" has to be INFERRED rather than read.
     *
     * ⚠ Manifests written before 2026-08-21 have no manifest_id and none of the
     * five recap counters -- only `imported`. ABSENT FIELDS MEAN OLD, NOT BROKEN.
     * A pre-today completed manifest must sweep as completed; if this test
     * demanded the new fields, a device holding 43,348 records would read as
     * never-imported.
     *
     * ⚠ NEVER call this anywhere but the startup job. Run while an import is
     * live, it would archive the controller's own manifest out from under it.
     */
    fun sweepManifests(ctx: Context): Int {
        val dir = importsDir(ctx)
        val history = File(dir, "history").apply { if (!exists()) mkdirs() }
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: return 0
        if (files.isEmpty()) {
            Log.i(TAG, "sweep: imports/ is empty, nothing to do")
            return 0
        }

        var swept = 0
        for (f in files) {
            try {
                val json = JSONObject(f.readText())
                val sources = json.optJSONArray("sources")

                // "counted" = the source reported an outcome. Old manifests carry
                // `imported` only; new ones carry the five. Either satisfies it.
                var allCounted = sources != null && sources.length() > 0
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val s = sources.getJSONObject(i)
                        val counted = s.has("imported") &&
                            s.optString("status") == "completed"
                        if (!counted) { allCounted = false; break }
                    }
                }

                if (!allCounted) {
                    // STAMP FIRST.
                    json.put("process_state", "killed")
                    json.put("killed_at", iso8601Now())
                    f.writeText(json.toString(2))
                }

                // THEN MOVE. Name by manifest_id where present -- it carries a
                // timestamp, so two runs on the same day stop colliding in
                // history. No id means an old manifest: keep its filename.
                val id = json.optString("manifest_id", "")
                val target = if (id.isBlank()) File(history, f.name)
                             else File(history, "$id.json")

                if (f.renameTo(target)) {
                    swept++
                    Log.i(TAG, "sweep: ${f.name} -> history/${target.name} " +
                        "(${if (allCounted) "completed" else "KILLED"})")
                } else {
                    Log.w(TAG, "sweep: could not move ${f.name} -- left in place, " +
                        "will retry next launch")
                }
            } catch (e: Exception) {
                // A manifest we cannot parse is still evidence. Never delete it.
                Log.e(TAG, "sweep: ${f.name} unreadable, left in place: ${e.message}")
            }
        }
        Log.i(TAG, "sweep: $swept of ${files.size} manifest(s) moved to history")
        return swept
    }

    /**
     * Does this rider need trail data? Two tests, both misses land on Home State.
     *
     *   1. spatial DB FILE does not exist  -> true  (fresh install)
     *   2. exists but holds zero trails    -> true
     *   3. otherwise                       -> false (straight to the Ride Map)
     *
     * ⛔ THE FILE CHECK MUST NOT OPEN THE DATABASE. SpatialDbManager.init() calls
     * openOrCreateDatabase, which writes an empty schema where a file is missing.
     * On a genuinely new install that is harmless; where a file is missing for any
     * OTHER reason it is the 08-01 data-loss mechanism. One File.exists() buys the
     * guarantee outright instead of depending on when it is safe.
     *
     * ⚠ A FAILED QUERY FALLS THROUGH TO THE MAP, not to Home State. Fred: "query
     * has to be successful to request home state add." Locked, mid-write or
     * unreadable is not evidence of emptiness, and the panel is always available.
     */
    fun needsTrailData(ctx: Context): Boolean {
        val dbFile = File(SpatialDbManager.dbDir(), "grouptrack_spatial.db")
        if (!dbFile.exists()) {
            Log.i(TAG, "needsTrailData: no spatial DB file -> HOME STATE")
            return true
        }
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val count = db.use { d ->
                d.rawQuery("SELECT COUNT(*) FROM trails", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
            }
            when {
                count < 0 -> {
                    Log.w(TAG, "needsTrailData: count unreadable -> RIDE MAP (panel available)")
                    false
                }
                count == 0 -> {
                    Log.i(TAG, "needsTrailData: 0 trails -> HOME STATE")
                    true
                }
                else -> {
                    Log.i(TAG, "needsTrailData: $count trails -> RIDE MAP")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "needsTrailData: query failed (${e.message}) -> RIDE MAP (panel available)")
            false
        }
    }

    fun hasCompletedImport(ctx: Context): Boolean {
        val dir = importsDir(ctx)
        return dir.listFiles()?.any { f ->
            try {
                f.extension == "json" && JSONObject(f.readText()).optString("process_state") == "completed"
            } catch (_: Exception) { false }
        } ?: false
    }

    // ── Utilities ────────────────────────────────────────────────

    private fun iso8601Now(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
}
