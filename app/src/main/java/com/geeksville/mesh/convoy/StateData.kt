package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
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
 */
object StateData {
    private const val TAG = "StateData"

    /** Fred 10:34: "more than six months old". */
    const val STALE_DAYS = 183

    /** The rider's answers for the run about to start: slug -> true = refresh, false = use saved. Cleared by [restore]. */
    private val answers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun dirFor(slug: String): File = GroupTrackStorage.dir("states/$slug")

    private fun savedPbf(ctx: Context, slug: String) = File(dirFor(slug), OsmImportStage.pbfFor(ctx, slug).name)
    private fun savedZip(ctx: Context, slug: String) = File(dirFor(slug), OsmImportStage.zipFor(ctx, slug).name)

    /**
     * FETCHFIRST-2026-11-02: the state's PAD-US zip, kept in the state's folder and never moved or discarded. [folder] is the state's
     * folder name -- California's two halves (california/norcal, california/socal) share one CA file in states/california.
     */
    fun landFile(folder: String, code: String): File = File(dirFor(folder), "padus_state_$code.zip")

    /** FETCHFIRST-2026-11-02: one agency trail source's saved pages for the state (one JSON page per line). */
    fun agencyFile(slug: String, sourceId: String): File = File(File(dirFor(slug), "agency"), "$sourceId.jsonl")

    /** True when the state's two files are saved. */
    fun has(ctx: Context, slug: String): Boolean = savedPbf(ctx, slug).exists() && savedZip(ctx, slug).exists()

    /** When the saved data was downloaded (ms), or 0 when nothing is saved. CODE RULE 1: 0 = none, not null. */
    fun downloadedMs(ctx: Context, slug: String): Long = if (has(ctx, slug)) savedPbf(ctx, slug).lastModified() else 0L

    /** Whole days since the saved data was downloaded, or -1 when nothing is saved. */
    fun ageDays(ctx: Context, slug: String): Int {
        val t = downloadedMs(ctx, slug)
        return if (t <= 0L) -1 else ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    }

    /** Saved size in MB (both files), 0 when nothing is saved. */
    fun sizeMb(ctx: Context, slug: String): Long =
        if (has(ctx, slug)) (savedPbf(ctx, slug).length() + savedZip(ctx, slug).length()) / 1_048_576L else 0L

    /** The states in [slugs] whose saved data is over [STALE_DAYS] old and not yet answered for this run. */
    fun staleSlugs(ctx: Context, slugs: List<String>): List<String> =
        slugs.distinct().filter { !answers.containsKey(it) && ageDays(ctx, it) > STALE_DAYS }

    /** The rider's answer to the refresh question for [slugs]. */
    fun answer(slugs: List<String>, refresh: Boolean) {
        for (s in slugs) answers[s] = refresh
        Log.i(TAG, "STATEDATA refresh question: ${slugs.joinToString()} -> " + if (refresh) "REFRESH" else "use saved")
    }

    /**
     * Before the run's download step: a refresh asked -> delete the saved files (the run downloads); otherwise move the
     * saved files into the working folder so the existing exists() checks skip the download.
     */
    fun restore(ctx: Context, slug: String) {
        val refresh = answers.remove(slug) == true
        val sp = savedPbf(ctx, slug); val sz = savedZip(ctx, slug)
        if (refresh) {
            val a = sp.exists() && sp.delete(); val b = sz.exists() && sz.delete()
            // FETCHFIRST-2026-11-02: a refresh is the WHOLE state -- the PAD-US zip and the agency pages come fresh too
            val ag = File(dirFor(slug), "agency"); val c = ag.exists() && ag.deleteRecursively()
            val land = dirFor(slug).listFiles { f -> f.isFile && f.name.startsWith("padus_state_") }?.count { it.delete() } ?: 0
            Log.i(TAG, "STATEDATA $slug: refresh asked -- saved data removed (pbf=$a zip=$b agency=$c padus=$land), downloading")
            return
        }
        if (!sp.exists() && !sz.exists()) { Log.i(TAG, "STATEDATA $slug: nothing saved -- downloading"); return }
        val age = ageDays(ctx, slug)   // before the move: the age is read from the saved PBF
        val wp = OsmImportStage.pbfFor(ctx, slug); val wz = OsmImportStage.zipFor(ctx, slug)
        var moved = 0
        if (sp.exists() && !wp.exists() && sp.renameTo(wp)) moved++
        if (sz.exists() && !wz.exists() && sz.renameTo(wz)) moved++
        Log.i(TAG, "STATEDATA $slug: using saved data ($moved file(s), downloaded ${age.coerceAtLeast(0)} day(s) ago by file date) -- no download")
    }

    /** At the end of the run: move the verified PBF and zip into states/<slug>/ before the working folder is cleared. */
    fun save(ctx: Context, slug: String) {
        try {
            val d = dirFor(slug); if (!d.exists()) d.mkdirs()
            val wp = OsmImportStage.pbfFor(ctx, slug); val wz = OsmImportStage.zipFor(ctx, slug)
            var kept = 0
            if (wp.exists() && OsmImportStage.verifyPbf(wp)) {
                val t = savedPbf(ctx, slug); if (t.exists()) t.delete()
                if (wp.renameTo(t)) kept++
            }
            if (wz.exists() && OsmImportStage.verifyZip(wz)) {
                val t = savedZip(ctx, slug); if (t.exists()) t.delete()
                if (wz.renameTo(t)) kept++
            }
            Log.i(TAG, "STATEDATA $slug: $kept file(s) saved to ${d.path} (${sizeMb(ctx, slug)} MB)")
        } catch (e: Exception) {
            // Never fail an import over keeping its files: the import itself is complete.
            Log.w(TAG, "STATEDATA $slug: save skipped -- ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
