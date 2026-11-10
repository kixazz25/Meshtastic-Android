package com.geeksville.mesh.convoy

import android.content.Context
import com.grouptrack.shared.imports.GtTrailImport
import com.grouptrack.shared.json.GtOrgJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import org.json.JSONObject

/**
 * TrailImporter -- V2.5 Trail data import engine.
 *
 * STEP15F-SWITCH-2026-11-10: THE FRONT DOOR. Every import runs the certified shared GtTrailImport (2.8 step 15f, 6b4400f56: Droid
 * 572,503 / 0 DIFF, iPad 116,299 / 0 DIFF) on the app's own database handles (SpatialDbManager.sharedTrailImport -- GtAppDb, the one
 * shared writer) and the app's real environment (GtImportAndroid: the assets, GroupTrackStorage, GtHttpAndroid = 2.7e's httpGet).
 * The public API is 2.7e's, unchanged for every caller: the same classes, the same progress flow, the same launch mode; each shared
 * result is mapped back field for field. The 2.7e bodies are in git history (67d395072) and in the TEST-ONLY TrailImporterCertOld.
 * With NO database open (SpatialDbManager.init could not open the handles) a call answers what that state allows: an import
 * "DB not available" (2.7e's own message for it), a fetch false, trailheads 0, the area records nothing / null / false.
 *
 * Two import methods:
 *   Method A: importFullSource() -- imports all trails from one source
 *   Method B: importByArea() -- imports trails from multiple sources within a bounding box
 */
object TrailImporter {

    data class ImportResult(
        val sourceId: String, val inserted: Int, val skipped: Int,
        val errors: Int, val message: String,
        val dropped: Int = 0, val aliased: Int = 0, val rejected: Int = 0
    )

    data class ImportProgress(
        val sourceId: String = "",
        val sourceName: String = "",
        val totalSources: Int = 1,
        val currentSourceIndex: Int = 0,
        val fetched: Int = 0,
        val inserted: Int = 0,
        val skipped: Int = 0,
        val dropped: Int = 0,
        val aliased: Int = 0,
        val rejected: Int = 0,
        val errors: Int = 0,
        val offset: Int = 0,
        val isComplete: Boolean = false,
        val message: String = ""
    )

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    enum class UpdateMode { CARTO_ONLY, ALL }
    /** Set by the import/update panel before an import run. CARTO_ONLY = safe carto refresh;
     *  ALL = refresh all property fields + name on existing records (never geometry). */
    @Volatile var updateMode: UpdateMode = UpdateMode.CARTO_ONLY

    /**
     * Explicit launch mode for the trail-source screen. Each launch point sets
     * this before navigating; the screen derives its starting step from it.
     */
    enum class LaunchMode { SELECT_SOURCE, BY_AREA }

    @Volatile
    var launchMode: LaunchMode = LaunchMode.SELECT_SOURCE

    data class Src(
        val id: String, val name: String, val queryUrl: String, val maxRecords: Int,
        val fieldId: String, val fieldName: String, val fieldType: String, val fieldUse: String,
        val fieldExtras: Map<String, String>,
        val sourceType: String = "arcgis_rest"
    )

    // ---- the shared code ----
    private fun map(r: GtTrailImport.ImportResult) = ImportResult(r.sourceId, r.inserted, r.skipped, r.errors, r.message, r.dropped, r.aliased, r.rejected)
    private fun map(p: GtTrailImport.ImportProgress) = ImportProgress(p.sourceId, p.sourceName, p.totalSources, p.currentSourceIndex, p.fetched,
        p.inserted, p.skipped, p.dropped, p.aliased, p.rejected, p.errors, p.offset, p.isComplete, p.message)
    private fun dbFail(sourceId: String) = ImportResult(sourceId, 0, 0, 1, "DB not available")

    /** the shared importer on the app's handles, its progress into [progress], [updateMode] passed on; null = no database open */
    private fun shared(context: Context?): GtTrailImport? {
        if (context != null) SpatialDbManager.init(context)
        return SpatialDbManager.sharedTrailImport { p -> _progress.value = map(p) }?.also {
            it.updateMode = if (updateMode == UpdateMode.ALL) GtTrailImport.UpdateMode.ALL else GtTrailImport.UpdateMode.CARTO_ONLY
        }
    }

    /** Method A: Import all trails from a single source (no bbox filter) */
    suspend fun importFullSource(context: Context, sourceId: String): ImportResult = withContext(Dispatchers.IO) {
        shared(context)?.let { map(it.importFullSource(sourceId)) } ?: dbFail(sourceId)
    }

    /** Method B: Import trails from multiple sources within a bounding box */
    suspend fun importByArea(
        context: Context, sourceIds: List<String>,
        south: Double, west: Double, north: Double, east: Double
    ): List<ImportResult> = withContext(Dispatchers.IO) {
        shared(context)?.importByArea(sourceIds, south, west, north, east)?.map { map(it) } ?: sourceIds.map { dbFail(it) }
    }

    /** FETCHFIRST-2026-11-02: import one source from its SAVED pages instead of the web. [files] = the source's saved file per state. */
    suspend fun importSavedByArea(
        context: Context, sourceId: String, files: List<java.io.File>,
        south: Double, west: Double, north: Double, east: Double
    ): List<ImportResult> = withContext(Dispatchers.IO) {
        shared(context)?.importSavedByArea(sourceId, files.map { it.toOkioPath() }, south, west, north, east)?.map { map(it) } ?: listOf(dbFail(sourceId))
    }

    /** FETCHFIRST-2026-11-02 / FETCHFIX-2026-11-02: query one agency source for an area ONCE and save every page into [dest]. */
    fun fetchSourceToFile(
        context: Context, sourceId: String,
        south: Double, west: Double, north: Double, east: Double,
        dest: java.io.File, onPage: (Int, Int, Int) -> Unit
    ): Boolean = shared(context)?.fetchSourceToFile(sourceId, south, west, north, east, dest.toOkioPath(), onPage) ?: false

    fun geojsonGeomToWkt(geom: JSONObject): String =
        shared(null)?.geojsonGeomToWkt(GtOrgJson.parse(geom.toString()) as kotlinx.serialization.json.JsonObject) ?: ""

    fun computeBbox(geom: JSONObject): DoubleArray =
        shared(null)?.computeBbox(GtOrgJson.parse(geom.toString()) as kotlinx.serialization.json.JsonObject) ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)

    /** Write pending area JSON (unprocessed) for Method B signal */
    fun writePendingArea(north: Double, south: Double, east: Double, west: Double) { shared(null)?.writePendingArea(north, south, east, west) }

    /** Read pending area JSON, return it or null (the same JSON, read back as org.json for the screen) */
    fun readPendingArea(): JSONObject? = shared(null)?.readPendingArea()?.let { JSONObject(GtOrgJson.compact(it)) }

    /** Delete pending area JSON after processing */
    fun clearPendingArea() { shared(null)?.clearPendingArea() }

    /** Scan all processed trail area JSONs for map overlay */
    fun scanTrailAreas(): List<JSONObject> = shared(null)?.scanTrailAreas()?.map { JSONObject(GtOrgJson.compact(it)) } ?: emptyList()

    /** Check if a source has been fully imported */
    fun isSourceFullyImported(sourceId: String): Boolean = shared(null)?.isSourceFullyImported(sourceId) ?: false

    fun importTrailheads(
        context: Context,
        assetFile: String,
        sourceId: String = "ugrc",
        south: Double? = null,
        west: Double? = null,
        north: Double? = null,
        east: Double? = null
    ): Int = shared(context)?.importTrailheads(assetFile, sourceId, south, west, north, east) ?: 0
}
