package com.grouptrack.shared.osm

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtImportConverter
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.text.GtJava
import okio.Path
import okio.buffer
import okio.use

/** Which stage a state's OSM import is at. DERIVED FROM DISK, never stored (2.7e OsmStage, PBFSTAGE-2026-08-30). */
enum class GtOsmStageName { ACQUIRE, ACQUIRE_GPKG, PREPARE_TAGS, REDUCE, IMPORT }

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmImportStage -- the rules engine (R1-R7) -- the DISK half, PORTED AS IS: the paths, the
 * stage derivation, the four verifiers, the trail extent / box count, discard and the debris sweep.
 * NOT HERE (Android-only, they stay in the app's OsmImportStage): hasAllFilesAccess, the MediaStore discovery (probeDownloads /
 * findCandidates), adoptCandidate, deleteOriginal -- a browser download found through MediaStore has no iPad equivalent. The legacy
 * rescue in rootDir and the legacy sweep in discardState go through [GtOsmPlatform] at 2.7e's exact points.
 * WHAT CHANGED, and only this:
 *  - GroupTrackStorage.dir("osm") -> env.paths.dir("osm") (created, as 2.7e); java.io.File -> okio via env.files.fs; listFiles() ->
 *    env.files.list (the platform's order); File.delete()'s false -> a failed okio delete caught as false; length() -> metadata size.
 *  - SQLiteDatabase.openDatabase(READONLY) + rawQuery / Cursor -> gtOpenDatabase(readOnly) + a statement; getString = GtRead.cellText.
 *  - ZipFile(f).entries() -> GtImportConverter.zipEntryNames (central-directory order, as ZipFile; not a zip -> IOException -> false).
 *  - DataInputStream.readInt / readFully -> okio (big-endian, EOF throws); String(hdr, ISO_8859_1) -> one char per byte.
 *  - The box arguments n.toString() ... -> GtJava.jd (Java's Double text, bound as TEXT, as rawQuery binds them).
 *  - Log -> gtLogger (same tag).
 */
class GtOsmStage(private val env: GtImportEnv, private val plat: GtOsmPlatform) {

    private val log = gtLogger(TAG)
    private val fs get() = env.files.fs

    // -- paths --------------------------------------------------------------

    fun rootDir(): Path {
        val d = env.paths.dir("osm")
        if (!fs.exists(d)) fs.createDirectories(d)
        plat.onOsmRoot(d)
        return d
    }

    fun dirFor(slug: String): Path {
        val d = rootDir() / slug
        if (!fs.exists(d)) fs.createDirectories(d)
        return d
    }

    fun zipFor(slug: String): Path = dirFor(slug) / "$slug-latest-free.gpkg.zip"
    fun gpkgFor(slug: String): Path = dirFor(slug) / "$slug.gpkg"
    fun skinnyFor(slug: String): Path = dirFor(slug) / SKINNY_NAME
    fun pbfFor(slug: String): Path = dirFor(slug) / "$slug-latest.osm.pbf"
    fun tagsFor(slug: String): Path = dirFor(slug) / TAGS_NAME

    private fun exists(p: Path) = fs.exists(p)
    private fun length(p: Path): Long = fs.metadataOrNull(p)?.size ?: 0L
    private fun delete(p: Path): Boolean = try { fs.delete(p, mustExist = true); true } catch (_: Exception) { false }
    private fun isDir(p: Path) = fs.metadataOrNull(p)?.isDirectory == true

    // -- derivation ---------------------------------------------------------

    fun stageOf(slug: String): GtOsmStageName {
        val skinny = skinnyFor(slug)
        if (exists(skinny)) {
            if (verifySkinny(skinny)) return GtOsmStageName.IMPORT
            log.w { "skinny DB failed verification, removing: ${skinny.name}" }
            delete(skinny)
        }
        val tags = tagsFor(slug)
        if (exists(tags)) {
            if (verifyTags(tags)) return GtOsmStageName.REDUCE
            log.w { "tag table failed verification, removing: ${tags.name}" }
            delete(tags)
        }
        val zip = zipFor(slug)
        val pbf = pbfFor(slug)
        var zipOk = false
        if (exists(zip)) {
            zipOk = verifyZip(zip)
            if (!zipOk) {
                log.w { "zip failed verification, removing: ${zip.name}" }
                delete(zip)
            }
        }
        var pbfOk = false
        if (exists(pbf)) {
            pbfOk = verifyPbf(pbf)
            if (!pbfOk) {
                log.w { "pbf failed verification, removing: ${pbf.name}" }
                delete(pbf)
            }
        }
        if (zipOk && pbfOk) return GtOsmStageName.PREPARE_TAGS
        if (pbfOk) return GtOsmStageName.ACQUIRE_GPKG
        return GtOsmStageName.ACQUIRE
    }

    /** Every state currently in flight (a directory holding one of the recovery points), sorted. */
    fun statesInFlight(): List<String> =
        env.files.list(rootDir())
            .filter { dir ->
                isDir(dir) && (
                    exists(dir / "${dir.name}-latest-free.gpkg.zip") ||
                        exists(dir / "${dir.name}-latest.osm.pbf") ||
                        exists(dir / TAGS_NAME) ||
                        exists(dir / SKINNY_NAME)
                    )
            }
            .map { it.name }
            .sorted()

    // -- verification -------------------------------------------------------

    fun verifyPbf(f: Path): Boolean {
        if (!exists(f) || length(f) < 1024L) return false
        return try {
            fs.source(f).buffer().use { din ->
                val hdrLen = din.readInt()
                if (hdrLen <= 0 || hdrLen > 64 * 1024) {
                    log.w { "pbf header length implausible ($hdrLen): ${f.name}" }
                    return false
                }
                val hdr = din.readByteArray(hdrLen.toLong())
                val latin1 = CharArray(hdr.size) { (hdr[it].toInt() and 0xFF).toChar() }.concatToString()
                val ok = latin1.contains("OSMHeader")
                if (!ok) log.w { "pbf first blob is not OSMHeader: ${f.name}" }
                ok
            }
        } catch (e: Exception) {
            log.w { "pbf unreadable ${f.name}: ${e::class.simpleName} ${e.message}" }
            false
        }
    }

    fun verifyTags(f: Path): Boolean {
        if (!exists(f) || length(f) < 4096L) return false
        var db: SQLiteConnection? = null
        return try {
            db = gtOpenDatabase(f.toString(), true)
            val rows = count(db, "SELECT COUNT(*) FROM osm_way_tags", emptyList())
            if (rows <= 0) log.w { "tag table has no rows: ${f.name}" }
            rows > 0
        } catch (e: Exception) {
            log.w { "tag table unreadable ${f.name}: ${e::class.simpleName} ${e.message}" }
            false
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }

    fun verifyZip(f: Path): Boolean {
        if (!exists(f) || length(f) < 1024L) return false
        return try {
            val hasGpkg = GtImportConverter.zipEntryNames(fs, f).any { it.endsWith(".gpkg", ignoreCase = true) }
            if (!hasGpkg) log.w { "zip has no .gpkg entry: ${f.name}" }
            hasGpkg
        } catch (e: Exception) {
            log.w { "zip unreadable ${f.name}: ${e::class.simpleName} ${e.message}" }
            false
        }
    }

    fun verifySkinny(f: Path): Boolean {
        if (!exists(f) || length(f) < 4096L) return false
        var db: SQLiteConnection? = null
        return try {
            db = gtOpenDatabase(f.toString(), true)
            val rows = count(db, "SELECT COUNT(*) FROM osm_trails", emptyList())
            if (rows <= 0) {
                log.w { "skinny DB has no rows: ${f.name}" }
                return false
            }
            var expected = -1
            try {
                val st = db.prepare("SELECT value FROM subset_meta WHERE key = ?")
                try {
                    st.bindText(1, "feature_count")
                    if (st.step()) expected = GtRead.cellText(st, 0)?.toIntOrNull() ?: -1
                } finally { st.close() }
            } catch (_: Exception) {
                // subset_meta absent -- tolerated; the row count already passed.
            }
            if (expected >= 0 && expected != rows) {
                log.w { "skinny DB count mismatch: rows=$rows expected=$expected" }
                return false
            }
            true
        } catch (e: Exception) {
            log.w { "skinny DB unreadable ${f.name}: ${e::class.simpleName} ${e.message}" }
            false
        } finally {
            try { db?.close() } catch (_: Exception) { }
        }
    }

    /** Cursor.getInt(0) of the first row (the cursor's moveToFirst), or -1 when there is no row. */
    private fun count(db: SQLiteConnection, sql: String, args: List<String>): Int {
        val st = db.prepare(sql)
        try {
            args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
            return if (st.step()) st.getLong(0).toInt() else -1
        } finally { st.close() }
    }

    // -- extent / box count -------------------------------------------------

    /** The WHOLE STATE bbox from the trails themselves: [s, w, n, e] (the order the ledger's pending record uses), or null. */
    fun trailExtent(slug: String): DoubleArray? {
        val f = skinnyFor(slug)
        if (!exists(f)) return null
        var db: SQLiteConnection? = null
        return try {
            db = gtOpenDatabase(f.toString(), true)
            var out: DoubleArray? = null
            val st = db.prepare("SELECT MIN(min_lat), MAX(max_lat), MIN(min_lon), MAX(max_lon) FROM osm_trails")
            try {
                if (st.step() && !st.isNull(0)) {
                    val minLat = st.getDouble(0); val maxLat = st.getDouble(1)
                    val minLon = st.getDouble(2); val maxLon = st.getDouble(3)
                    out = doubleArrayOf(minLat, minLon, maxLat, maxLon)
                }
            } finally { st.close() }
            log.i { "trailExtent $slug = ${out?.joinToString(",") { GtJava.jd(it) } ?: "(none)"}" }
            out
        } catch (e: Exception) {
            log.w { "trailExtent failed $slug: ${e::class.simpleName} ${e.message}" }
            null
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }

    /** How many trails OVERLAP a box (the test the import applies), or -1. */
    fun countTrailsInBbox(slug: String, s: Double, w: Double, n: Double, e: Double): Int {
        val f = skinnyFor(slug)
        if (!exists(f)) return -1
        var db: SQLiteConnection? = null
        return try {
            db = gtOpenDatabase(f.toString(), true)
            count(db, "SELECT COUNT(*) FROM osm_trails WHERE min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?",
                listOf(GtJava.jd(n), GtJava.jd(s), GtJava.jd(e), GtJava.jd(w)))
        } catch (ex: Exception) {
            log.w { "countTrailsInBbox failed $slug: ${ex::class.simpleName}" }
            -1
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }

    // -- discard / debris ---------------------------------------------------

    /** OSM-CANCEL-BUTTON-2026-07-28: throw a state away entirely -- flat, NOT recursive; then the legacy sweep (platform). */
    fun discardState(slug: String): Boolean {
        val dir = dirFor(slug)
        var ok = true
        var n = 0
        env.files.list(dir).forEach { f ->
            if (isDir(f)) {
                log.w { "unexpected directory in working dir, left alone: ${f.name}" }
                ok = false
            } else if (delete(f)) {
                n++
            } else {
                log.w { "could not delete ${f.name}" }
                ok = false
            }
        }
        if (ok && !delete(dir)) {
            log.w { "removed $n file(s) but could not remove the directory itself" }
        }
        try {
            plat.discardLegacy(slug)
        } catch (e: Exception) {
            log.w { "legacy sweep skipped: ${e::class.simpleName} ${e.message}" }
        }
        log.i { "discarded $slug ($n file(s) removed, ok=$ok)" }
        return ok
    }

    /** R7: .part / .tmp / .gpkg in the state's directory. */
    fun sweepDebris(slug: String): Int {
        val dir = dirFor(slug)
        var n = 0
        env.files.list(dir).forEach { f ->
            val name = f.name.lowercase()
            val debris = name.endsWith(".part") || name.endsWith(".tmp") || name.endsWith(".gpkg")
            if (debris && delete(f)) {
                n++
                log.i { "swept debris: ${f.name}" }
            }
        }
        return n
    }

    companion object {
        private const val TAG = "OsmStage"
        /** OSM-SKINNY-CONSTANT-2026-07-28: ONE name for all fifty states. */
        const val SKINNY_NAME = "ready_for_import.db"
        /** The interim tag table: osm_id -> the access tags Geofabrik strips. */
        const val TAGS_NAME = "osm_way_tags.db"
        /** OSM-PUBLIC-STORAGE-2026-07-28. */
        const val GROUPTRACK_DIR = "Documents/GroupTrack"

        /** "north-carolina" -> "North Carolina". Display only. */
        fun displayName(slug: String): String =
            slug.split("-", "_")
                .filter { it.isNotBlank() }
                .joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
    }
}
