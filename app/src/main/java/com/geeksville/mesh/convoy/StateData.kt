package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtImportAndroid
import com.geeksville.mesh.grouptrack.GtOsmAndroid
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtHttp
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.imports.GtTrailClass
import com.grouptrack.shared.osm.GtOsmPlatform
import com.grouptrack.shared.osm.GtOsmStage
import com.grouptrack.shared.state.GtStateData
import com.grouptrack.shared.state.GtStateDataRun
import com.grouptrack.shared.text.GtLocalTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * STATEDATA-2026-11-02 -- a state's downloaded data is KEPT on the tablet and reused (Fred 11-02 10:28-10:34).
 *
 * "leave it in a state folder and check for existence before pulling. gives us one pull process that keeps the data
 * current on the device." / "if more than six months old we ask if they want to refresh this data. if it does not
 * exist we pull ... at the end of the job where the delete exists now we move the data to a saved-by-state folder."
 *
 * WHY NOW: maps no longer take the space they did (2.7e: a tenth or less), so the state's downloads -- the OSM PBF and
 * the Geofabrik GeoPackage zip -- can stay. Every later pull for that state (whole state, by area) starts from them
 * with no download. Later the state's land file (public / restricted areas, PAD-US) joins this folder.
 *
 * THE FOLDER: GroupTrack/states/<slug>/ holds the two files under the same names OsmImportStage uses in its working
 * folder osm/<slug>/. Files MOVE between the two (a rename on the same storage -- instant, and the file's date is kept),
 * so the AGE of the saved data is the date the files were downloaded: the PBF's own modified time. No record file.
 *
 * THE FLOW:
 *   1. Before a run starts, the picker asks [staleSlugs]: saved data older than [STALE_DAYS] -> the refresh question.
 *      The answer is recorded with [answer] (refresh or use saved) for THIS run only.
 *   2. The run calls [restore] before its download step: refresh asked -> the saved files are deleted and the run
 *      downloads; otherwise saved files move into osm/<slug>/ and the existing "download only if missing" checks
 *      skip the download.
 *   3. At the end of the run (Step 5, where everything used to be deleted) [save] moves the verified PBF and zip into
 *      states/<slug>/ before the working folder is cleared.
 *
 * SWITCH17G2E1-2026-11-12 (2.8 step 17, G2e-1): front doors to the shared GtStateData (certified at 34f000359: Droid 58 SAME, 0 DIFF;
 * iPad = Droid). Every public name, parameter and type kept -- the callers (HomeStateImportController, HomeStatePickerScreen,
 * LandAreas, UtahPrivate) are unchanged. The two in-memory sets stay here, one per process, as 2.7e (ConcurrentHashMap).
 * The three path functions take no Context in 2.7e, so they use [pathsOnly]: GroupTrackStorage.dir(name) as 2.7e called it.
 */
object StateData {
    /** Fred 10:34: "more than six months old". */
    const val STALE_DAYS = GtStateData.STALE_DAYS

    /** 2.7e's two in-memory sets: the rider's answers for the run about to start, and the states refreshing this run. */
    private object Run : GtStateDataRun {
        private val answers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val refreshing = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        override fun putAnswer(slug: String, refresh: Boolean) { answers[slug] = refresh }
        override fun removeAnswer(slug: String): Boolean? = answers.remove(slug)
        override fun hasAnswer(slug: String): Boolean = answers.containsKey(slug)
        override fun addRefreshing(slug: String) { refreshing.add(slug) }
        override fun refreshing(): List<String> = refreshing.toList()
        override fun clearRefreshing() { refreshing.clear() }
    }

    private fun sd(ctx: Context): GtStateData { val env = GtImportAndroid(ctx); return GtStateData(env, GtOsmStage(env, GtOsmAndroid(ctx)), Run) }
    private fun f(p: Path): File = File(p.toString())

    /** No Context (as 2.7e): only the folder rules are used -- GroupTrackStorage.dir(name), the OSM stage is never reached. */
    private val pathsOnly: GtStateData by lazy {
        val env = object : GtImportEnv {
            override val clock = object : GtClock {
                override fun nowInstant(): String = java.time.Instant.now().toString()
                override fun localNow(): GtLocalTime = throw UnsupportedOperationException("StateData paths only")
                override fun epochMs(): Long = System.currentTimeMillis()
            }
            override val ids = object : GtIds { override fun newId(): String = throw UnsupportedOperationException("StateData paths only") }
            override val files = object : GtFiles {
                override val fs: FileSystem = FileSystem.SYSTEM
                override fun setLastModified(path: Path, epochMs: Long): Boolean = File(path.toString()).setLastModified(epochMs)
                override fun lastModified(path: Path): Long = File(path.toString()).lastModified()
                override fun list(dir: Path): List<Path> = File(dir.toString()).listFiles()?.map { it.toOkioPath() } ?: emptyList()
            }
            override val paths = object : GtImportPaths {
                override fun groupTrackRoot(): Path = GroupTrackStorage.root().toOkioPath()
                override fun tracksDir(): Path = throw UnsupportedOperationException("StateData paths only")
                override fun dir(name: String): Path = GroupTrackStorage.dir(name).toOkioPath()
                override fun cacheDir(): Path = throw UnsupportedOperationException("StateData paths only")
                override fun filesDir(): Path = throw UnsupportedOperationException("StateData paths only")
            }
            override val http = object : GtHttp { override fun get(url: String, readTimeoutMs: Int): String? = throw UnsupportedOperationException("StateData paths only") }
            override val trailClass = object : GtTrailClass { override fun useOf(category: String): String = throw UnsupportedOperationException("StateData paths only") }
            override val platform = object : GtImportPlatform {
                override fun readAsset(name: String): String = throw UnsupportedOperationException("StateData paths only")
                override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int = throw UnsupportedOperationException("StateData paths only")
                override fun saveToDownloads(file: Path): String = throw UnsupportedOperationException("StateData paths only")
                override fun onTrailsChanged() {}
            }
        }
        val noPlat = object : GtOsmPlatform {
            override fun onOsmRoot(root: Path) = throw UnsupportedOperationException("StateData paths only")
            override fun discardLegacy(slug: String) = throw UnsupportedOperationException("StateData paths only")
            override fun usableSpace(dir: Path): Long = throw UnsupportedOperationException("StateData paths only")
            override fun sweepDownloadedZips(slug: String) = throw UnsupportedOperationException("StateData paths only")
            override fun foreground(worker: String, text: String) = throw UnsupportedOperationException("StateData paths only")
        }
        GtStateData(env, GtOsmStage(env, noPlat), Run)
    }

    /** FETCHFIRST-2026-11-02: the state's PAD-US zip, kept in the state's folder (California's halves share states/california). */
    fun landFile(folder: String, code: String): File = f(pathsOnly.landFile(folder, code))
    /** PADUSSVC-2026-11-02: the state's PAD-US areas from the USGS service -- one JSON page per line. Replaces the zip. */
    fun landPages(folder: String, code: String): File = f(pathsOnly.landPages(folder, code))
    /** FETCHFIRST-2026-11-02: one agency trail source's saved pages for the state (one JSON page per line). */
    fun agencyFile(slug: String, sourceId: String): File = f(pathsOnly.agencyFile(slug, sourceId))

    /** True when the state's two files are saved. */
    fun has(ctx: Context, slug: String): Boolean = sd(ctx).has(slug)
    /** DATAQUEUE-2026-11-02: download this state's files again (California's halves share the "california" land folder). */
    fun isRefresh(slug: String): Boolean = pathsOnly.isRefresh(slug)
    /** DATAQUEUE-2026-11-02: the run is over -- the next one uses the saved data again. */
    fun endRun() = pathsOnly.endRun()

    /** DATAQUEUE-2026-11-02: write states/<slug>/state_data.json for every state in [rows] (the import's file rows). */
    fun writeRecords(ctx: Context, rows: List<org.json.JSONObject>) = sd(ctx).writeRecords(rows.map {
        GtStateData.Row(it.optString("state_slug"), it.optString("fetch_kind"), it.optString("name"), it.optString("dest"), it.optString("status")) })

    /** When the saved data was downloaded (ms), or 0 when nothing is saved. CODE RULE 1: 0 = none, not null. */
    fun downloadedMs(ctx: Context, slug: String): Long = sd(ctx).downloadedMs(slug)
    /** Whole days since the saved data was downloaded, or -1 when nothing is saved. */
    fun ageDays(ctx: Context, slug: String): Int = sd(ctx).ageDays(slug)
    /** Saved size in MB (both files), 0 when nothing is saved. */
    fun sizeMb(ctx: Context, slug: String): Long = sd(ctx).sizeMb(slug)
    /** The states in [slugs] whose saved data is over [STALE_DAYS] old and not yet answered for this run. */
    fun staleSlugs(ctx: Context, slugs: List<String>): List<String> = sd(ctx).staleSlugs(slugs)
    /** The rider's answer to the refresh question for [slugs]. */
    fun answer(slugs: List<String>, refresh: Boolean) = pathsOnly.answer(slugs, refresh)
    /** Before the run's download step: refresh -> marked; saved files move into the working folder (no download). */
    fun restore(ctx: Context, slug: String) = sd(ctx).restore(slug)
    /** At the end of the run: move the verified PBF and zip into states/<slug>/ before the working folder is cleared. */
    fun save(ctx: Context, slug: String) = sd(ctx).save(slug)
}
