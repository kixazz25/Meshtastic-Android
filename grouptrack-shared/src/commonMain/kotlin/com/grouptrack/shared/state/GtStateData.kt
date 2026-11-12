package com.grouptrack.shared.state

import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.osm.GtOsmStage
import com.grouptrack.shared.text.GtTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.Path
import okio.Path.Companion.toPath

/**
 * STEP17G2E1-2026-11-12 (2.8 step 17, G2e-1): a state's kept download data -- convoy/StateData.kt (STATEDATA / FETCHFIRST / DATAQUEUE
 * 2026-11-02), ported AS IS. GroupTrack/states/<slug>/ keeps the state's OSM PBF and GeoPackage zip (under OsmImportStage's own names),
 * its PAD-US pages and its agency pages; the files MOVE between there and osm/<slug>/ so their date is the download date. Changes, and
 * only these:
 *   GroupTrackStorage.dir      -> env.paths.dir (the same root)
 *   OsmImportStage.pbfFor / zipFor / verifyPbf / verifyZip -> [stage] (GtOsmStage -- already the shared code since step 15g)
 *   File.exists / length / lastModified / renameTo / delete -> okio + env.files (renameTo = atomicMove; false on failure, as renameTo)
 *   System.currentTimeMillis / Instant.now / Instant.ofEpochMilli(...).toString() -> env.clock.epochMs / GtTime.instantTextMs
 *   the two in-memory sets (answers, refreshing)  -> [run] (Android: ConcurrentHashMap, as 2.7e; one per process)
 *   org.json (the state_data.json record)          -> GtOrgJson (the same text, toString(1)); the import's rows -> [Row]
 *   android.util.Log -> gtLogger (the same tag and lines)
 */
interface GtStateDataRun {
    fun putAnswer(slug: String, refresh: Boolean)
    /** answers.remove(slug) */
    fun removeAnswer(slug: String): Boolean?
    fun hasAnswer(slug: String): Boolean
    fun addRefreshing(slug: String)
    fun refreshing(): List<String>
    fun clearRefreshing()
}

class GtStateData(private val env: GtImportEnv, private val stage: GtOsmStage, private val run: GtStateDataRun) {
    companion object {
        const val TAG = "StateData"
        /** Fred 10:34: "more than six months old". */
        const val STALE_DAYS = 183
        val USED_BY = mapOf(
            "pbf" to "OSM trails import -- trail access tags (Open Source Maps)",
            "gpkg" to "OSM trails import -- trail lines (Open Source Maps)",
            "land" to "public / private / restricted land (PAD-US) -- step 8 and the map's land areas",
            "agency" to "agency trails import",
            "ownership" to "(retired 11-02 -- PAD-US replaced it)")
    }
    /** one of the import's file rows, as writeRecords reads it (optString of each key) */
    data class Row(val stateSlug: String, val fetchKind: String, val name: String, val dest: String, val status: String)

    private val log = gtLogger(TAG)
    private val fs get() = env.files.fs
    private fun exists(p: Path) = fs.exists(p)
    private fun length(p: Path): Long = if (exists(p)) (fs.metadataOrNull(p)?.size ?: 0L) else 0L
    private fun rename(a: Path, b: Path): Boolean = try { fs.atomicMove(a, b); true } catch (e: Exception) { false }
    private fun mkdirs(d: Path) { if (!exists(d)) fs.createDirectories(d) }

    fun dirFor(slug: String): Path = env.paths.dir("states/$slug")
    private fun savedPbf(slug: String) = dirFor(slug) / stage.pbfFor(slug).name
    private fun savedZip(slug: String) = dirFor(slug) / stage.zipFor(slug).name

    fun landFile(folder: String, code: String): Path = dirFor(folder) / "padus_state_$code.zip"
    fun landPages(folder: String, code: String): Path = dirFor(folder) / "padus_$code.jsonl"
    fun agencyFile(slug: String, sourceId: String): Path = dirFor(slug) / "agency" / "$sourceId.jsonl"

    fun has(slug: String): Boolean = exists(savedPbf(slug)) && exists(savedZip(slug))
    fun isRefresh(slug: String): Boolean = run.refreshing().any { it == slug || it.startsWith("$slug/") }
    fun endRun() { run.clearRefreshing() }

    fun writeRecords(rows: List<Row>) {
        for ((slug, list) in rows.groupBy { it.stateSlug }) {
            if (slug.isEmpty()) continue
            val files = ArrayList<JsonObject>()
            for (r in list) {
                val kind = r.fetchKind
                val dest = r.dest.toPath()
                val kept = if (kind == "pbf" || kind == "gpkg") dirFor(slug) / dest.name else dest
                val f = if (exists(dest)) dest else kept
                files.add(JsonObject(linkedMapOf(
                    "kind" to GtOrgJson.str(kind), "name" to GtOrgJson.str(r.name), "path" to GtOrgJson.str(kept.toString()),
                    "mb" to GtOrgJson.num(if (exists(f)) length(f) / 1_048_576L else 0L),
                    "date" to GtOrgJson.str(if (exists(f)) GtTime.instantTextMs(env.files.lastModified(f)) else ""),
                    "status" to GtOrgJson.str(r.status), "used_by" to GtOrgJson.str(USED_BY[kind] ?: ""))))
            }
            val d = dirFor(slug); mkdirs(d)
            val rec = JsonObject(linkedMapOf("state" to GtOrgJson.str(slug), "written" to GtOrgJson.str(GtTime.instantTextMs(env.clock.epochMs())),
                "files" to JsonArray(files)))
            fs.write(d / "state_data.json") { writeUtf8(GtOrgJson.pretty(rec, 1)) }
            log.i { "STATEDATA $slug: record written (${files.size} file(s))" }
        }
    }

    /** When the saved data was downloaded (ms), or 0 when nothing is saved. CODE RULE 1: 0 = none, not null. */
    fun downloadedMs(slug: String): Long = if (has(slug)) env.files.lastModified(savedPbf(slug)) else 0L
    /** Whole days since the saved data was downloaded, or -1 when nothing is saved. */
    fun ageDays(slug: String): Int { val t = downloadedMs(slug); return if (t <= 0L) -1 else ((env.clock.epochMs() - t) / 86_400_000L).toInt() }
    fun sizeMb(slug: String): Long = if (has(slug)) (length(savedPbf(slug)) + length(savedZip(slug))) / 1_048_576L else 0L
    fun staleSlugs(slugs: List<String>): List<String> = slugs.distinct().filter { !run.hasAnswer(it) && ageDays(it) > STALE_DAYS }
    fun answer(slugs: List<String>, refresh: Boolean) {
        for (s in slugs) run.putAnswer(s, refresh)
        log.i { "STATEDATA refresh question: ${slugs.joinToString()} -> " + if (refresh) "REFRESH" else "use saved" }
    }

    fun restore(slug: String) {
        val refresh = run.removeAnswer(slug) == true
        val sp = savedPbf(slug); val sz = savedZip(slug)
        if (refresh) {
            run.addRefreshing(slug)
            log.i { "STATEDATA $slug: refresh asked -- every file downloads again; the saved copy is kept until each new one is complete" }
        }
        if (!exists(sp) && !exists(sz)) { log.i { "STATEDATA $slug: nothing saved -- downloading" }; return }
        val age = ageDays(slug)
        val wp = stage.pbfFor(slug); val wz = stage.zipFor(slug)
        var moved = 0
        if (exists(sp) && !exists(wp) && rename(sp, wp)) moved++
        if (exists(sz) && !exists(wz) && rename(sz, wz)) moved++
        log.i { "STATEDATA $slug: using saved data ($moved file(s), downloaded ${age.coerceAtLeast(0)} day(s) ago by file date) -- no download" }
    }

    fun save(slug: String) {
        try {
            val d = dirFor(slug); mkdirs(d)
            val wp = stage.pbfFor(slug); val wz = stage.zipFor(slug)
            var kept = 0
            if (exists(wp) && stage.verifyPbf(wp)) {
                val t = savedPbf(slug); if (exists(t)) try { fs.delete(t) } catch (_: Exception) {}
                if (rename(wp, t)) kept++
            }
            if (exists(wz) && stage.verifyZip(wz)) {
                val t = savedZip(slug); if (exists(t)) try { fs.delete(t) } catch (_: Exception) {}
                if (rename(wz, t)) kept++
            }
            log.i { "STATEDATA $slug: $kept file(s) saved to $d (${sizeMb(slug)} MB)" }
        } catch (e: Exception) {
            log.w { "STATEDATA $slug: save skipped -- ${e::class.simpleName} ${e.message}" }
        }
    }
}
