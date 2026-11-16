package com.grouptrack.shared.platform

/** GTIOSENVFIX-2026-11-15: NSDate has NO timeIntervalSince1970 CONSTRUCTOR -- only
 *  timeIntervalSinceReferenceDate, which counts from 2001-01-01. The class method
 *  dateWithTimeIntervalSince1970 is the Unix-epoch door, and GtCertImport15eMacTest:75
 *  has been compiling with it all along. Taken from working code, not documentation:
 *  the first version of this file was written from docs and never compiled, which is
 *  what blocked the whole iOS test build. */

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.mbtiles.GtTileStoreEnv
import com.grouptrack.shared.text.GtLocalTime
import com.grouptrack.shared.text.GtTime
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import com.grouptrack.shared.imports.GtHttp
import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.imports.GtTrailClass
import com.grouptrack.shared.storage.GtStorage

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

/** GtFiles on okio, plus the two things okio cannot do. */
/** GTIOSENVFIX2-2026-11-15: ExperimentalForeignApi is required by setAttributes's `error`
 *  parameter, which is a CPointer. GtCertImport15eMacTest:53 carries the same opt-in on the
 *  class that makes the same call -- this is copied from code the compiler already accepts,
 *  not inferred from documentation. */
@OptIn(ExperimentalForeignApi::class)
class IosFiles : GtFiles {
    override val fs: FileSystem = FileSystem.SYSTEM

    /** IOSSOCKETS-2026-11-15: okio cannot SET a file time; NSFileManager can. 2.7e keeps a track's
     *  earliest <time> as its file time, and MacFiles returning false made prune run on different
     *  inputs -- one of pass 2's two phantom DIFFs. */
    override fun setLastModified(path: Path, epochMs: Long): Boolean = try {
        NSFileManager.defaultManager.setAttributes(
            mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)),
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


/**
 * IOSIMPORTENV-2026-11-16: the iPad's GtImportEnv -- register item G7, "production import wiring".
 *
 * Nothing new is implemented here. All seven parts existed before this class; what was missing was
 * the assembly, and until 1d8b7e87c it could not be written safely because TWO GtImportPaths
 * implementations sat in iosMain and disagreed about cacheDir()/filesDir(). One has been removed.
 *
 * Mirrors GtImportAndroid(context), member for member:
 *     clock       GtClock            IosClock            (java.time.Instant -> NSDate)
 *     ids         GtIds              IosIds              (UUID -> NSUUID)
 *     files       GtFiles            IosFiles            (okio + NSFileManager for setLastModified)
 *     paths       GtImportPaths      IosImportPaths      (through GtStorage, as Android's st)
 *     http        GtHttp             IosHttp             (HttpURLConnection -> NSURLSession)
 *     trailClass  GtTrailClass       IosTrailClass       (TrailClassifier.useOf, ported as data)
 *     platform    GtImportPlatform   IosImportPlatform   (readAsset + saveToDownloads real;
 *                                                         enqueueCorridor and onTrailsChanged throw)
 *
 * ONE PATHS INSTANCE, SHARED. IosImportPlatform's parameter is the CONCRETE IosImportPaths, not the
 * interface, so a careless assembly would construct two independent instances. Android holds a single
 * st = GtStorageAndroid.of(app) for the same reason. One instance here, passed to both.
 *
 * CODE RULE 1 -- the st parameter is a non-null default, and the reason is certification, not
 * convenience: production takes the real sandbox via iosStorage(), while GtCertPathsMacTest already
 * injects a GtStorage rooted at a temp directory (IosImportPaths(st)). A cert that could not point the
 * env at a scratch root would have to write into the real container to prove anything.
 *
 * ⚠ WHAT THIS DOES NOT MAKE TRUE. The env now exists and type-checks; it has not been RUN. Two of
 * IosImportPlatform's four members still throw -- enqueueCorridor needs the iOS download queue
 * (GtQueuePlatform has no implementation on either platform) and onTrailsChanged needs a map to
 * reload. So iOS GPX IMPORT must not be claimed as working: the export half is certified, the import
 * half reaches two throwing members. Per SVCFIRST-2026-11-16 they throw loudly and name themselves
 * rather than degrading into something that reads as a bug.
 */
class IosImportEnv(st: GtStorage = iosStorage()) : GtImportEnv {
    /** ONE instance -- see the note above; IosImportPlatform takes the concrete type. */
    private val p = IosImportPaths(st)

    override val clock: GtClock = IosClock
    override val ids: GtIds = IosIds
    override val files: GtFiles = IosFiles()
    override val paths: GtImportPaths = p
    override val http: GtHttp = IosHttp
    override val trailClass: GtTrailClass = IosTrailClass()
    override val platform: GtImportPlatform = IosImportPlatform(p, FileSystem.SYSTEM)
}
