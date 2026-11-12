package com.grouptrack.shared.cert

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
import com.grouptrack.shared.text.GtTime
import okio.FileSystem
import okio.Path

/**
 * STEP17G2E1-2026-11-12 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released): the G2e-1 cert script (StateData),
 * ONE copy for every side. Each side has its own root folder (states/ and osm/ under it) and the same fixed clock; the files the script
 * makes are written with okio and dated with [setMtime] (the platform's), so ages are the same on every device. Paths are shown
 * relative to the side's root ("<ROOT>"), so the Droid's two sides and the iPad compare as text.
 */
object Cert17e1 {
    const val NOW = 1_794_484_800_000L          // 2026-11-12T12:00:00Z
    private const val DAY = 86_400_000L

    /** one side's StateData, 2.7e's API one for one (slugs, paths as Path, rows as GtStateData.Row) */
    interface Side {
        val root: Path
        fun reset()
        fun landFile(folder: String, code: String): Path
        fun landPages(folder: String, code: String): Path
        fun agencyFile(slug: String, sourceId: String): Path
        fun has(slug: String): Boolean
        fun isRefresh(slug: String): Boolean
        fun endRun()
        fun writeRecords(rows: List<GtStateData.Row>)
        fun downloadedMs(slug: String): Long
        fun ageDays(slug: String): Int
        fun sizeMb(slug: String): Long
        fun staleSlugs(slugs: List<String>): List<String>
        fun answer(slugs: List<String>, refresh: Boolean)
        fun restore(slug: String)
        fun save(slug: String)
    }

    /** a PBF the shared verifyPbf accepts: 4-byte header length, a header blob naming OSMHeader, padded to [size] */
    fun pbf(size: Int, good: Boolean = true): ByteArray {
        val hdr = (if (good) "\n\tOSMHeader\u0018\u0010" else "\n\tOSMDataX\u0018\u0010").encodeToByteArray()
        val b = ByteArray(size); b[3] = hdr.size.toByte(); hdr.copyInto(b, 4)
        for (i in 4 + hdr.size until size) b[i] = (i * 7).toByte()
        return b
    }
    /** a zip the shared verifyZip accepts: padding, then a central directory naming [entry] and the END record */
    fun zip(pad: Int, entry: String): ByteArray {
        val name = entry.encodeToByteArray()
        val out = ArrayList<Byte>(); repeat(pad) { out.add((it * 13).toByte()) }
        val cdOff = out.size
        fun u16(v: Int) { out.add((v and 0xff).toByte()); out.add((v shr 8 and 0xff).toByte()) }
        fun u32(v: Long) { u16((v and 0xffff).toInt()); u16((v shr 16 and 0xffff).toInt()) }
        u32(0x02014b50L); u16(20); u16(20); u16(0); u16(0); u16(0); u16(0); u32(0); u32(0); u32(0); u16(name.size); u16(0); u16(0); u16(0); u16(0); u32(0); u32(0)
        name.forEach { out.add(it) }
        val cdSize = out.size - cdOff
        u32(0x06054b50L); u16(0); u16(0); u16(1); u16(1); u32(cdSize.toLong()); u32(cdOff.toLong()); u16(0)
        return out.toByteArray()
    }

    // FIX17E1MTIMEREAD-2026-11-12: [mtime] = the platform's own read of a file's date (okio's Apple lastModifiedAtMillis is off by seconds/1e6 ms)
    fun run(side: Side, fs: FileSystem, setMtime: (Path, Long) -> Boolean, mtime: (Path) -> Long, emit: (String, String) -> Unit) {
        val r = side.root
        fun rel(p: Path): String { val s = p.toString(); val rs = r.toString(); return if (s.startsWith(rs)) "<ROOT>" + s.substring(rs.length) else s }
        fun tree(): String {
            if (!fs.exists(r)) return "NOROOT"
            return fs.listRecursively(r).toList().map { p -> val m = fs.metadata(p)
                rel(p) + (if (m.isDirectory) "/" else (if (p.name == "state_data.json") " (record: holds full paths, compared as s8-record) (written now)" /* FIX17E1RECORDSIZE-2026-11-12 */ else " ${m.size}" + " " + GtTime.instantTextMs(mtime(p) / 1000 * 1000))) }
                .sorted().joinToString("\n")
        }
        fun put(relPath: String, bytes: ByteArray, mtime: Long) {
            val p = r / relPath; p.parent?.let { fs.createDirectories(it) }; fs.write(p) { write(bytes) }; setMtime(p, mtime)
        }
        fun state(tag: String, slug: String) {
            emit("$tag|$slug|has", "${side.has(slug)}"); emit("$tag|$slug|downloadedMs", "${side.downloadedMs(slug)}")
            emit("$tag|$slug|ageDays", "${side.ageDays(slug)}"); emit("$tag|$slug|sizeMb", "${side.sizeMb(slug)}")
            emit("$tag|$slug|isRefresh", "${side.isRefresh(slug)}")
        }
        side.reset()
        // 1. the paths
        emit("path|landFile", rel(side.landFile("california", "CA"))); emit("path|landPages", rel(side.landPages("utah", "UT")))
        emit("path|agencyFile", rel(side.agencyFile("utah", "blm-gtrn")))
        // 2. nothing saved
        state("s0-empty", "utah"); emit("s0|stale", side.staleSlugs(listOf("utah", "nevada")).toString())
        side.restore("utah"); emit("s0|restore|tree", tree())
        // 3. a run's work files -> save (200 days old: stale)
        val old = NOW - 200 * DAY
        put("osm/utah/utah-latest.osm.pbf", pbf(700_000), old); put("osm/utah/utah-latest-free.gpkg.zip", zip(500_000, "gis_osm_roads_free.gpkg"), old - DAY)
        side.save("utah"); emit("s1-save|tree", tree()); state("s1-saved", "utah")
        emit("s1|stale", side.staleSlugs(listOf("utah", "utah", "nevada")).toString())
        // 4. "use saved" -> restore moves them back; save again
        side.answer(listOf("utah"), false); emit("s2|stale.answered", side.staleSlugs(listOf("utah")).toString())
        side.restore("utah"); emit("s2-restore|tree", tree()); state("s2-restored", "utah")
        side.save("utah"); emit("s2-save|tree", tree())
        // 5. "refresh" -> refreshing (California's halves too); restore still moves; endRun clears
        side.answer(listOf("utah", "california/norcal"), true)
        side.restore("utah"); side.restore("california/norcal"); emit("s3-refresh|tree", tree())
        state("s3", "utah"); emit("s3|isRefresh(california)", "${side.isRefresh("california")}"); emit("s3|isRefresh(calif)", "${side.isRefresh("calif")}")
        emit("s3|isRefresh(california/socal)", "${side.isRefresh("california/socal")}")
        side.endRun(); emit("s3-endRun|isRefresh(utah)", "${side.isRefresh("utah")}")
        // 6. save with a newer work copy over a saved one; a bad PBF and a zip without .gpkg are not kept
        put("states/utah/utah-latest.osm.pbf", pbf(2_000), NOW - 400 * DAY)
        side.save("utah"); emit("s4-save-over|tree", tree()); state("s4", "utah")
        put("osm/nevada/nevada-latest.osm.pbf", pbf(2_000, good = false), NOW - 5 * DAY); put("osm/nevada/nevada-latest-free.gpkg.zip", zip(2_000, "readme.txt"), NOW - 5 * DAY)
        side.save("nevada"); emit("s5-bad|tree", tree()); state("s5", "nevada")
        put("osm/idaho/idaho-latest.osm.pbf", pbf(500), NOW - DAY)   // under 1024 bytes: not a PBF
        side.save("idaho"); emit("s6-small|tree", tree())
        // 7. only one file saved: has() is false, so restore still moves the one there is
        put("states/oregon/oregon-latest.osm.pbf", pbf(3_000), NOW - 10 * DAY)
        state("s7", "oregon"); side.restore("oregon"); emit("s7-restore|tree", tree())
        // 8. the record
        put("states/utah/padus_UT.jsonl", "{\"a\":1}\n".encodeToByteArray(), NOW - 3 * DAY)
        val rows = listOf(
            GtStateData.Row("utah", "gpkg", "Utah GeoPackage", (r / "osm/utah/utah-latest-free.gpkg.zip").toString(), "completed"),
            GtStateData.Row("utah", "pbf", "Utah OSM", (r / "osm/utah/utah-latest.osm.pbf").toString(), "completed"),
            GtStateData.Row("utah", "land", "Utah PAD-US", (r / "states/utah/padus_UT.jsonl").toString(), "completed"),
            GtStateData.Row("utah", "agency", "BLM \"GTRN\" / trails", (r / "states/utah/agency/blm-gtrn.jsonl").toString(), "failed"),
            GtStateData.Row("", "pbf", "no state", "/x", "completed"),
            GtStateData.Row("nevada", "ownership", "old kind", (r / "states/nevada/ownership.zip").toString(), "stopped"),
            GtStateData.Row("nevada", "mystery", "unknown kind", (r / "osm/nevada/nevada-latest.osm.pbf").toString(), "pending"))
        side.writeRecords(rows)
        for (s in listOf("utah", "nevada", "")) {
            val f = r / "states/$s/state_data.json"
            emit("s8-record|$s", if (s.isNotEmpty() && fs.exists(f)) fs.read(f) { readUtf8() }.replace(r.toString(), "<ROOT>").replace(r.toString().replace("/", "\\/"), "<ROOT>") else "NOFILE")
        }
        emit("s8|tree", tree())
        side.endRun(); side.reset()
    }

    /** a GtImportEnv on [root] with the cert's clock -- the NEW side's (Droid and iPad) */
    class Env(override val files: GtFiles, private val root: Path) : GtImportEnv {
        override val clock = object : GtClock {
            override fun nowInstant(): String = GtTime.instantTextMs(NOW)
            override fun localNow(): GtLocalTime = throw UnsupportedOperationException("not used by 17e1")
            override fun epochMs(): Long = NOW
        }
        override val ids = object : GtIds { override fun newId(): String = throw UnsupportedOperationException("not used by 17e1") }
        override val paths = object : GtImportPaths {
            override fun groupTrackRoot(): Path = root
            override fun tracksDir(): Path = throw UnsupportedOperationException("not used by 17e1")
            override fun dir(name: String): Path = root / name
            override fun cacheDir(): Path = throw UnsupportedOperationException("not used by 17e1")
            override fun filesDir(): Path = throw UnsupportedOperationException("not used by 17e1")
        }
        override val http = object : GtHttp { override fun get(url: String, readTimeoutMs: Int): String? = throw UnsupportedOperationException("not used by 17e1") }
        override val trailClass = object : GtTrailClass { override fun useOf(category: String): String = throw UnsupportedOperationException("not used by 17e1") }
        override val platform = object : GtImportPlatform {
            override fun readAsset(name: String): String = throw UnsupportedOperationException("not used by 17e1")
            override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int = throw UnsupportedOperationException("not used by 17e1")
            override fun saveToDownloads(file: Path): String = throw UnsupportedOperationException("not used by 17e1")
            override fun onTrailsChanged() {}
        }
    }
    val noOsmPlatform = object : GtOsmPlatform {
        override fun onOsmRoot(root: Path) {}
        override fun discardLegacy(slug: String) {}
        override fun usableSpace(dir: Path): Long = throw UnsupportedOperationException("not used by 17e1")
        override fun sweepDownloadedZips(slug: String) {}
        override fun foreground(worker: String, text: String) {}
    }
    class MemRun : GtStateDataRun {
        private val answers = LinkedHashMap<String, Boolean>(); private val refreshing = LinkedHashSet<String>()
        override fun putAnswer(slug: String, refresh: Boolean) { answers[slug] = refresh }
        override fun removeAnswer(slug: String): Boolean? = answers.remove(slug)
        override fun hasAnswer(slug: String): Boolean = answers.containsKey(slug)
        override fun addRefreshing(slug: String) { refreshing.add(slug) }
        override fun refreshing(): List<String> = refreshing.toList()
        override fun clearRefreshing() { refreshing.clear() }
    }
    /** NEW: the shared GtStateData on [root] */
    class NewSide(private val fs: FileSystem, override val root: Path, files: GtFiles, run: GtStateDataRun) : Side {
        private val env = Env(files, root)
        private val sd = GtStateData(env, GtOsmStage(env, noOsmPlatform), run)
        override fun reset() { if (fs.exists(root)) fs.deleteRecursively(root); fs.createDirectories(root) }
        override fun landFile(folder: String, code: String) = sd.landFile(folder, code)
        override fun landPages(folder: String, code: String) = sd.landPages(folder, code)
        override fun agencyFile(slug: String, sourceId: String) = sd.agencyFile(slug, sourceId)
        override fun has(slug: String) = sd.has(slug)
        override fun isRefresh(slug: String) = sd.isRefresh(slug)
        override fun endRun() = sd.endRun()
        override fun writeRecords(rows: List<GtStateData.Row>) = sd.writeRecords(rows)
        override fun downloadedMs(slug: String) = sd.downloadedMs(slug)
        override fun ageDays(slug: String) = sd.ageDays(slug)
        override fun sizeMb(slug: String) = sd.sizeMb(slug)
        override fun staleSlugs(slugs: List<String>) = sd.staleSlugs(slugs)
        override fun answer(slugs: List<String>, refresh: Boolean) = sd.answer(slugs, refresh)
        override fun restore(slug: String) = sd.restore(slug)
        override fun save(slug: String) = sd.save(slug)
    }
}
