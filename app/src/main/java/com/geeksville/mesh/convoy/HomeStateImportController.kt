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

    private fun buildManifest(
        state: GeofabrikState,
        catalogSourceIds: List<String>,
        catalogSourceNames: Map<String, String>
    ): JSONObject {
        val area = JSONObject().apply {
            put("type", "state")
            put("state", state.name)
            put("bbox", JSONArray(listOf(state.bboxWest, state.bboxSouth, state.bboxEast, state.bboxNorth)))
            put("states", JSONArray(listOf(state.parentState ?: state.name)))
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
            })
        }

        // Geofabrik entry last
        sources.put(JSONObject().apply {
            put("id", "geofabrik_${state.slug.replace("/", "_")}")
            put("name", "${state.name} Open Source Maps")
            put("process", "geofabrik_full_state")
            put("slug", state.slug)
            put("gpkg_url", state.gpkgUrl)
            put("status", "pending")
            put("imported", 0)
        })

        return JSONObject().apply {
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
    suspend fun execute(context: Context, state: GeofabrikState): Boolean =
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
            val mFile = manifestFile(context, state.name)
            writeManifest(mFile, manifest)

            val sources = manifest.getJSONArray("sources")
            val totalSources = sources.length()

            Log.i(TAG, "Manifest: $totalSources sources, file=${mFile.name}")

            // 3. Publish initial progress
            publishProgress(state.name, totalSources, sources, "running", startMs)

            // 4. Loop through sources
            var allOk = true
            for (i in 0 until totalSources) {
                val src = sources.getJSONObject(i)
                if (src.getString("status") == "completed") continue

                // Mark in_progress
                src.put("status", "in_progress")
                src.put("started_at", iso8601Now())
                writeManifest(mFile, manifest)
                publishProgress(state.name, totalSources, sources, "running", startMs)

                val ok = when (src.getString("process")) {
                    "geofabrik_full_state" -> runGeofabrikSource(context, src, state)
                    "trails_list_area" -> runCatalogSource(context, src, state)
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
                publishProgress(state.name, totalSources, sources, "running", startMs)
            }

            // 5. Mark manifest complete
            manifest.put("process_state", if (allOk) "completed" else "completed")
            manifest.put("completed_at", iso8601Now())
            writeManifest(mFile, manifest)
            publishProgress(state.name, totalSources, sources,
                if (allOk) "completed" else "failed", startMs)

            Log.i(TAG, "Home state import ${if (allOk) "COMPLETED" else "COMPLETED WITH ERRORS"} " +
                "for ${state.name} in ${(System.currentTimeMillis() - startMs) / 1000}s")
            allOk
        }

    // ── Geofabrik source: download → extract → import → cleanup ──

    private suspend fun runGeofabrikSource(
        context: Context, src: JSONObject, state: GeofabrikState
    ): Boolean {
        val slug = src.getString("slug")
        val gpkgUrl = src.getString("gpkg_url")

        // Step 1: Download
        updateSourceStep(src, "Downloading", "$gpkgUrl")
        val zipFile = OsmImportStage.zipFor(context, slug)
        if (!zipFile.exists()) {
            val ok = downloadFile(gpkgUrl, zipFile)
            if (!ok) {
                Log.e(TAG, "Download failed for $slug")
                return false
            }
        }
        Log.i(TAG, "Download complete: ${zipFile.name} (${zipFile.length() / 1_048_576} MB)")

        // Step 2: Create the ledger (required before setPendingImport)
        OsmImportLedger.create(
            context, slug,
            OsmImportStage.displayName(slug),
            "Geofabrik ${state.name} (auto download)",
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
        val bbox = doubleArrayOf(state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast)
        OsmImportLedger.setPendingImport(context, slug, "state", bbox)
        Log.i(TAG, "setPendingImport for $slug: state bbox")

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
        }

        // Step 5: Cleanup
        updateSourceStep(src, "Cleanup", null)
        OsmImportStage.discardState(context, slug)

        return true
    }

    // ── Catalog source: importByArea (existing function) ─────────

    private suspend fun runCatalogSource(
        context: Context, src: JSONObject, state: GeofabrikState
    ): Boolean {
        val sourceId = src.getString("id")
        updateSourceStep(src, "Importing", null)

        return try {
            val results = TrailImporter.importByArea(
                context, listOf(sourceId),
                state.bboxSouth, state.bboxWest, state.bboxNorth, state.bboxEast
            )
            val total = results.sumOf { it.inserted }
            src.put("imported", total)
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

    private fun downloadFile(urlStr: String, dest: File): Boolean {
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
                if (redirect != null) return downloadFile(redirect, dest)
                return false
            }
            if (conn.responseCode != 200) {
                Log.e(TAG, "Download HTTP ${conn.responseCode} for $urlStr")
                conn.disconnect()
                return false
            }
            val tmp = File(dest.parent, dest.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                conn.inputStream.use { inp ->
                    val buf = ByteArray(1 shl 16)
                    var n: Int
                    while (inp.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
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

    // ── Utilities ────────────────────────────────────────────────

    private fun iso8601Now(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
}
