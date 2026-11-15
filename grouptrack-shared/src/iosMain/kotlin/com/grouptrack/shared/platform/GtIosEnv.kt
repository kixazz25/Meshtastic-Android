package com.grouptrack.shared.platform

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.mbtiles.GtTileStoreEnv
import com.grouptrack.shared.text.GtLocalTime
import com.grouptrack.shared.text.GtTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear

/**
 * IOSSOCKETS-2026-11-15 (2.8b phase 1): the PRODUCTION iOS halves of the four map-critical
 * sockets. Promoted from the certified iosTest doubles -- MacFiles, MacPaths, MacClock and
 * MacTileEnv -- which are real implementations (okio FileSystem, the real gtOpenDatabase on
 * BundledSQLiteDriver) and passed 28 of 37 certifications on the simulator 2026-11-15.
 *
 * ⛔ THIS IS PROMOTION AND HARDENING, NOT A COPY. Three things the doubles got away with
 * because a test injected its own world, and production cannot:
 *   1. setLastModified returned false. It is implementable on iOS (NSFileModificationDate) and
 *      its no-op already caused two phantom DIFFs in pass 2. IMPLEMENTED here.
 *   2. MacPaths put my_tracks UNDER the root and never created it. GtImportPaths' own contract
 *      says "a SIBLING of the root, created". FIXED here.
 *   3. MacPaths' cacheDir/filesDir were children of the injected root. On iOS they are the real
 *      sandbox Caches and Library directories. FIXED here.
 *
 * ⚠ FIRST DRAFT OF THE FOUNDATION SIGNATURES. Every Foundation call below is written from the
 * documented API, not from a compile. The COMPILER is the check -- run
 * ./gradlew :grouptrack-shared:compileKotlinIosSimulatorArm64 and tie each error to a line here.
 * Do not assume this file is correct because it is committed.
 */

private fun sysDir(which: ULong): String =
    (NSSearchPathForDirectoriesInDomains(which, NSUserDomainMask, true).firstOrNull() as? String)
        ?: error("iOS sandbox directory $which not available")   // CODE RULE 1: no nullable fallback -- a missing sandbox dir is not recoverable

/** GtFiles on okio, plus the two things okio cannot do. */
class IosFiles : GtFiles {
    override val fs: FileSystem = FileSystem.SYSTEM

    /** IOSSOCKETS-2026-11-15: okio cannot SET a file time; NSFileManager can. 2.7e keeps a track's
     *  earliest <time> as its file time, and MacFiles returning false made prune run on different
     *  inputs -- one of pass 2's two phantom DIFFs. */
    override fun setLastModified(path: Path, epochMs: Long): Boolean = try {
        NSFileManager.defaultManager.setAttributes(
            mapOf<Any?, Any?>(NSFileModificationDate to NSDate(timeIntervalSince1970 = epochMs / 1000.0)),
            ofItemAtPath = path.toString(),
            error = null,
        )
    } catch (e: Throwable) { false }

    /** 0 when the file is missing, as java.io.File. */
    override fun lastModified(path: Path): Long = fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L

    /** SORTED -- okio's order. Owner decision 11: the iPad is sorted, Android is listFiles order.
     *  The track resync's order decides which of two same-geometry files becomes the track and
     *  which an alias (INVENTORY T8), so this is a DECISION already taken, not an accident. */
    override fun list(dir: Path): List<Path> = try { fs.list(dir) } catch (e: Exception) { emptyList() }
}

/** Where GroupTrack keeps things inside the iOS sandbox. Resolved on each call, as the contract says. */
class IosPaths : GtImportPaths {
    private val documents: Path get() = sysDir(NSDocumentDirectory).toPath()

    override fun groupTrackRoot(): Path = (documents / "GroupTrack").also { ensure(it) }

    /** ⛔ A SIBLING of the root, created -- NOT a child. MacPaths had this wrong. */
    override fun tracksDir(): Path = (documents / "my_tracks").also { ensure(it) }

    /** A named child of the root ("data/trail_areas", "osm", "imports", "config"). NOT created. */
    override fun dir(name: String): Path = groupTrackRoot() / name

    override fun cacheDir(): Path = (sysDir(NSCachesDirectory).toPath() / "GroupTrack").also { ensure(it) }

    override fun filesDir(): Path = (sysDir(NSLibraryDirectory).toPath() / "GroupTrack").also { ensure(it) }

    private fun ensure(p: Path) { try { FileSystem.SYSTEM.createDirectories(p) } catch (e: Exception) { } }
}

/** The device clock. */
object IosClock : GtClock {
    override fun epochMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
    override fun nowInstant(): String = GtTime.instantTextMs(epochMs())
    override fun localNow(): GtLocalTime {
        val c = NSCalendar.currentCalendar
        val u = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
                NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
        val d = c.components(u, fromDate = NSDate())
        return GtLocalTime(
            d.year.toInt(), d.month.toInt(), d.day.toInt(),
            d.hour.toInt(), d.minute.toInt(), d.second.toInt(),
        )
    }
}

/** java.util.UUID.randomUUID().toString() -- proven in the 11-06 spike. */
object IosIds : GtIds {
    override fun newId(): String = NSUUID().UUIDString.lowercase()
}

/**
 * The tile store's platform half. Mirrors the CERTIFIED MacTileEnv exactly in structure, with the
 * production differences called out.
 *
 * ⛔ THE THREE SAFETY PRAGMAS, EACH IN ITS OWN TRY. GtTileStoreEnv's own contract: "each must be
 * set in its OWN try -- a structure added after execSQL silently set NOTHING on device."
 * Until this class existed, the pragmas had NO iOS production owner: they were set only by
 * TEST-ONLY code marked for deletion before 2.8 ships (CODE RULE 3).
 */
class IosTileEnv(private val paths: GtImportPaths) : GtTileStoreEnv {
    private val fs = FileSystem.SYSTEM
    /** APP-LIFETIME cache -- MacTileEnv had a test teardown(); production has none and must not. */
    private val handles = HashMap<String, SQLiteConnection>()

    private fun dir(): Path = paths.groupTrackRoot()
    private fun path(type: String): String = (dir() / "$type.mbtiles").toString()

    override fun connection(type: String): SQLiteConnection? {
        handles[type]?.let { return it }
        return try {
            fs.createDirectories(dir())
            val fresh = !fs.exists(path(type).toPath())
            val c = gtOpenDatabase(path(type), false)
            ensureSchema(c)
            if (fresh) initMetadata(c, type)
            handles[type] = c
            c
        } catch (e: Exception) { null }   // null on hard failure, exactly as 2.7e
    }

    override fun storeExists(type: String): Boolean = fs.exists(path(type).toPath())

    override fun deleteStore(type: String): Boolean {
        handles.remove(type)?.let { try { it.close() } catch (e: Exception) { } }
        var ok = true
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            val p = (path(type) + suffix).toPath()
            if (fs.exists(p)) try { fs.delete(p) } catch (e: Exception) { ok = false }
        }
        return ok
    }

    private fun exec(c: SQLiteConnection, sql: String) { val st = c.prepare(sql); try { st.step() } finally { st.close() } }

    private fun ensureSchema(c: SQLiteConnection) {
        // ⛔ EACH IN ITS OWN TRY. Do not collapse this loop into one try.
        for (p in listOf("secure_delete=OFF", "synchronous=NORMAL", "journal_mode=TRUNCATE")) {
            try { exec(c, "PRAGMA $p") } catch (e: Exception) { }
        }
        exec(c, "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        exec(c, "CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        exec(c, "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles(zoom_level, tile_column, tile_row)")
    }

    /** ⚠ format: the Droid's MBTilesStore writes "webp" [V2.6a-WEBP]; MacTileEnv's cert env wrote
     *  "pbf". VEC.mbtiles holds vector tiles, so "pbf" is right for the vector store -- CONFIRM
     *  against the Droid's VEC.mbtiles metadata before trusting either. */
    private fun initMetadata(c: SQLiteConnection, type: String) {
        for ((k, v) in listOf("name" to type, "format" to "pbf", "scheme" to "xyz")) {
            val st = c.prepare("INSERT OR REPLACE INTO metadata(name,value) VALUES(?,?)")
            try { st.bindText(1, k); st.bindText(2, v); st.step() } finally { st.close() }
        }
    }
}
