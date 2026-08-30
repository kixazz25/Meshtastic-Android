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
                put("pbf_url", gs.pbfUrl)   // PBFURL-2026-08-30
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
        val pbfUrl = src.getString("pbf_url")   // PBFDL-2026-08-30

        // ── PBFDL-2026-08-30 ──────────────────────────────────────────────
        // Step 1a: the PBF, FIRST and on its own.
        //
        // WHY FIRST: it is the smaller file (~167 MB vs ~332 MB) and it carries
        // the NEW capability -- the access tags (ohv, motor_vehicle, access,
        // 4wd_only) that Geofabrik's shapefile-derived GeoPackage drops
        // entirely. A wrong catalogue row therefore fails at 167 MB rather
        // than after half a gigabyte.
        //
        // WHY NOT CONCURRENT: both report through downloadDetailFlow, so two
        // at once interleave into one string and the rider watches two
        // counters fight over the same line.
        updateSourceStep(src, "Downloading tags", pbfUrl)
        val pbfFile = OsmImportStage.pbfFor(context, slug)
        if (!pbfFile.exists()) {
            val okPbf = downloadFile(pbfUrl, pbfFile, src)
            if (!okPbf) {
                Log.e(TAG, "PBF download failed for $slug")
                return false
            }
        }
        // ⚠ VERIFIED HERE, unlike the zip. stageOf() checks both on the next
        // derivation, but a truncated PBF would otherwise reach the tag pass,
        // which would classify from a partial tag set and report success --
        // the failure class that marked three FAILED runs as imported on
        // 07-27. Delete it so the retry starts clean.
        if (!OsmImportStage.verifyPbf(pbfFile)) {
            Log.e(TAG, "PBF failed verification for $slug, removing: ${pbfFile.name}")
            pbfFile.delete()
            return false
        }
        downloadDetailFlow.value = null
        Log.i(TAG, "PBF complete: ${pbfFile.name} (${pbfFile.length() / 1_048_576} MB)")

        // Step 1b: Download the GeoPackage
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

        // ── TAGPASS-2026-08-30 ────────────────────────────────────────────
        // Step 1c: read the PBF into the interim tag table.
        //
        // ⭐ SYNCHRONOUS AND VISIBLE, not a worker (Fred 08-30): a background
        // job nobody can watch is one a rider doubts and force-quits, and a
        // force-quit mid-parse leaves a half-written artifact. The extract
        // uses a worker because it is short; a 167 MB parse is not.
        //
        // ⚠ THE DEAD STRETCH IS THE FILE LAYOUT. PBF stores every node before
        // any way, so ROWS stays at 0 for most of the run -- measured on Utah:
        // 3,400 blocks with 0 ways, then 2.1M ways in the last ~80. The line
        // therefore reports WAYS WALKED too, which moves immediately.
        val tagsFile = OsmImportStage.tagsFor(context, slug)
        if (!OsmImportStage.verifyTags(tagsFile)) {
            updateSourceStep(src, "Reading permissions", "starting")
            val builtRows = withContext(Dispatchers.IO) {
                OsmPbfTagReader.build(pbfFile, tagsFile) { waysWalked, rowsWritten ->
                    downloadDetailFlow.value =
                        "Reading trail permissions - " +
                            "${waysWalked / 1000}k ways read, $rowsWritten kept"
                }
            }
            downloadDetailFlow.value = null
            if (builtRows <= 0) {
                // ⛔ build() already removed its own output. Fail the source
                // rather than let the join run on a partial tag set.
                Log.e(TAG, "Tag pass failed for $slug (rows=$builtRows)")
                return false
            }
            Log.i(TAG, "Tag pass complete for $slug: $builtRows rows")
        } else {
            Log.i(TAG, "Tag table already present for $slug, skipping tag pass")
        }

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
    /**
     * TRAILFILTER-2026-08-24K -- wipe the trails table exactly once per device.
     *
     * WHY A WIPE. The filter change above stops `path` and `bridleway` arriving
     * in FUTURE imports. It does nothing about the ones already stored, and
     * they cannot be picked out: `fclass` is extracted into the skinny DB and
     * never carried into `trails`, so a foot path and a jeep track are the same
     * twelve columns once they land. There is no WHERE clause that finds them.
     *
     * Fred, 08-24: "clear the trails period. They all import together in the
     * install." Which is the honest move and costs nothing extra -- the gate
     * already launches the home-state picker at trails.count == 0, so the wipe
     * IS the trigger. No dialog: the picker explains itself.
     *
     * ⚠⚠ UNREADABLE IS NOT ABSENT. If the marker cannot be read we must NOT
     * conclude "never ran" -- that is the create-if-missing shape that
     * destroyed the spatial DB on 2026-08-01, and here it would wipe a rider's
     * trails on every launch. exists() is the whole test, and a File.exists()
     * that throws means we do nothing.
     *
     * ⚠ THE MARKER IS WRITTEN AFTER THE DELETE, never before. A run that dies
     * half way leaves no marker and retries; a marker written first would turn
     * one interruption into a permanent half-cleared database.
     *
     * Returns rows removed, or -1 when it did not run.
     */
    fun clearTrailsOnce(ctx: Context): Int {
        // TRAILCLEAR-2026-08-24L: NEW NAME. K's marker already exists on any
        // device that ran the broken clear, and reusing it would leave those
        // devices with orphaned trail_properties forever. A corrected clear
        // needs a marker the broken one cannot satisfy.
        // MARKERFIX-2026-08-25M: THE MARKER LIVES BESIDE THE DATABASES IT
        // PROTECTS, not in imports/. sweepManifests empties that directory
        // every launch and could not tell a marker from a manifest, so the
        // guard was swept to history/ and the clear ran again on the next
        // launch. Every launch. Both K's and L's markers were found in
        // history/ on Droid 2, which is the proof.
        //
        // ⭐ A GUARD AND THE THING IT GUARDS MUST SHARE A LIFETIME. Same
        // defect as the 08-16 reinstall wipe -- there the guard was
        // app-private and the data public; here the guard sits in a
        // directory another routine is chartered to empty.
        //
        // No .json extension, deliberately: it is not a manifest and must
        // never again be mistaken for one by a filter that only reads
        // filenames.
        val marker = File(SpatialDbManager.dbDir(), ".trails_cleared_2026-08-24L")

        // ⭐ ADOPT A MARKER THE OLD CODE WROTE, wherever the sweep left it.
        // It is proof the clear already ran, and honouring it costs a
        // rider zero further wipes instead of exactly one more. Devices
        // that never ran the old code have neither file and fall through.
        if (!marker.exists()) {
            val legacy = listOf(
                File(importsDir(ctx), "trails_cleared_2026-08-24L.json"),
                File(File(importsDir(ctx), "history"),
                    "trails_cleared_2026-08-24L.json")
            )
            val found = try {
                legacy.firstOrNull { it.exists() }
            } catch (e: Exception) {
                // Same standing-down rule as below: cannot tell, do nothing.
                Log.e(TAG, "clearTrailsOnce: legacy marker unreadable: " + e.message)
                return -1
            }
            if (found != null) {
                writeClearMarker(marker, -1, -1)
                Log.i(
                    TAG,
                    "clearTrailsOnce: adopted the prior marker at " +
                        found.absolutePath + " -- NOT clearing again"
                )
                return -1
            }
        }

        val alreadyRan = try {
            marker.exists()
        } catch (e: Exception) {
            // Cannot tell. Do NOTHING -- see the note above. A wipe we are not
            // certain is needed is worse than a wipe we skip.
            Log.e(TAG, "clearTrailsOnce: marker unreadable, standing down: " + e.message)
            return -1
        }
        if (alreadyRan) return -1

        val dbFile = File(SpatialDbManager.dbDir(), "grouptrack_spatial.db")
        if (!dbFile.exists()) {
            // Nothing to clear. Still mark it: a fresh install imports under the
            // new filter anyway, and leaving the marker off would run this again
            // after the rider's first import.
            writeClearMarker(marker, 0)
            Log.i(TAG, "clearTrailsOnce: no spatial DB yet, marked and skipped")
            return 0
        }

        var removed = 0
        var props = 0
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            db.use { d ->
                d.rawQuery("SELECT COUNT(*) FROM trails", null).use { c ->
                    if (c.moveToFirst()) removed = c.getInt(0)
                }
                d.execSQL("DELETE FROM trails")
            }

            // TRAILCLEAR-2026-08-24L: AND THE EXTENSION DB, which K missed.
            //
            // Every UGRC identifier lives here -- carto_code, motorized_allowed,
            // designated_uses, surface_type, owner_steward, county. Keyed on
            // trail_id, which is a UUID minted at insert, NOT on geom_hash. So
            // once the trails are gone these rows join to nothing and can never
            // be reached again.
            //
            // ⚠ Worse than orphaned: TrailImporter inserts properties with
            // INSERT OR IGNORE on (source_id, source_unique_id). A re-imported
            // trail whose uid a stale row already holds is silently ignored, so
            // the orphan KEEPS THE SLOT and the new trail gets no properties.
            // Utah would come back uncategorised with nothing reporting an
            // error.
            //
            // source_ingestions is deliberately NOT cleared -- it is the record
            // of what each import brought in, and comparing the old counts to
            // the next ones is how the filter change gets measured on real
            // imports rather than inferred from the .gpkg.
            val extFile = File(SpatialDbManager.dbDir(), "grouptrack_data.db")
            if (extFile.exists()) {
                val ext = android.database.sqlite.SQLiteDatabase.openDatabase(
                    extFile.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                )
                ext.use { e ->
                    try {
                        e.rawQuery("SELECT COUNT(*) FROM trail_properties", null).use { c ->
                            if (c.moveToFirst()) props = c.getInt(0)
                        }
                        e.execSQL("DELETE FROM trail_properties")
                    } catch (inner: Exception) {
                        // Absent on a device that never imported. Not an error,
                        // and not a reason to fail the whole clear.
                        Log.i(TAG, "clearTrailsOnce: no trail_properties (" +
                            inner.message + ")")
                    }
                }
            }
        } catch (e: Exception) {
            // No marker written, so this retries next launch.
            Log.e(TAG, "clearTrailsOnce: FAILED, will retry: " + e.message)
            return -1
        }

        writeClearMarker(marker, removed, props)
        Log.i(TAG, "clearTrailsOnce: removed " + removed + " trail(s) and " +
            props + " trail_properties row(s) -- home state picker will " +
            "re-import under the new filter")
        return removed
    }

    /** Best effort. The delete has already happened by the time this is called,
     *  so a failure here costs one repeated wipe, not a corrupt database. */
    private fun writeClearMarker(marker: File, removed: Int, props: Int = 0) {
        try {
            marker.parentFile?.mkdirs()
            val o = JSONObject()
            o.put("reason", "TRAILCLEAR-2026-08-24L: path and bridleway dropped from the OSM filter; trails and trail_properties both cleared")
            o.put("trails_removed", removed)
            o.put("properties_removed", props)
            o.put("cleared_at", java.time.Instant.now().toString())
            marker.writeText(o.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "clearTrailsOnce: marker write failed: " + e.message)
        }
    }

    fun sweepManifests(ctx: Context): Int {
        val dir = importsDir(ctx)
        val history = File(dir, "history").apply { if (!exists()) mkdirs() }
        // MARKERFIX-2026-08-25M: A MANIFEST HAS A `sources` ARRAY. Filtering
        // on the extension alone is what swept clearTrailsOnce's own marker
        // into history/ and stamped it "killed" -- a file this routine had
        // no business touching.
        //
        // ⭐ THIS IS THE DURABLE HALF OF THE FIX. Moving the marker solves
        // today's file; this stops the NEXT file parked here from being
        // eaten the same way.
        //
        // ⚠ Unreadable is left in place, not swept. The same rule the rest
        // of this routine already follows.
        val files = (dir.listFiles { f -> f.isFile && f.extension == "json" } ?: return 0)
            .filter { f ->
                try {
                    JSONObject(f.readText()).optJSONArray("sources") != null
                } catch (e: Exception) {
                    Log.w(TAG, "sweep: " + f.name + " is not a manifest, left alone")
                    false
                }
            }
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
                // SWEEPNAME-2026-08-21I: SANITIZE THE FILENAME. manifest_id carries an
                // ISO timestamp (HH:mm:ss) and Android's external storage is
                // FAT/exFAT-derived -- a ':' in a filename fails the create with
                // EPERM. Measured on Droid 2 2026-08-21. The id INSIDE the file is
                // untouched: it is what gets read back on a support call, and the
                // contents were never the problem.
                val safeId = id.replace(':', '-')
                val target = if (safeId.isBlank()) File(history, f.name)
                             else File(history, "$safeId.json")

                // SWEEPMOVE-2026-08-21H: log the CLASSIFICATION before attempting the
                // move. The previous log said only "could not move", which made a
                // correct classification look like a broken one.
                val verdict = if (allCounted) "completed" else "KILLED"
                Log.i(TAG, "sweep: ${f.name} classified $verdict -> history/${target.name}")

                // SWEEPMOVE-2026-08-21H: COPY-THEN-DELETE, not renameTo.
                // renameTo() returns false on FUSE-mounted external storage when
                // the move crosses into a subdirectory -- measured on Droid 2
                // 2026-08-21 with a writable target directory and a valid file.
                // It fails by RETURNING FALSE, so there is no exception to catch.
                // Order matters: verify the copy landed before deleting the
                // original, so a part-way failure leaves a DUPLICATE, never a loss.
                var moved = false
                try {
                    target.writeText(f.readText())
                    if (target.exists() && target.length() == f.length()) {
                        if (f.delete()) {
                            moved = true
                        } else {
                            // Copy is safe; the original would not delete. Say so
                            // plainly -- next launch will see both.
                            Log.w(TAG, "sweep: copied ${f.name} but could not delete " +
                                "the original -- duplicate left in imports/")
                        }
                    } else {
                        Log.w(TAG, "sweep: copy of ${f.name} did not verify " +
                            "(target ${target.length()}B vs source ${f.length()}B) -- " +
                            "original left in place")
                        target.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sweep: move of ${f.name} failed: ${e.message} -- " +
                        "original left in place, will retry next launch")
                }

                if (moved) swept++
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
