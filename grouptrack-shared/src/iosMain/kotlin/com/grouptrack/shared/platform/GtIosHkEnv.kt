package com.grouptrack.shared.platform

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.GtSchema
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.filter.TrailFilterState
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.text.GtTime
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.startup.GtHkDb
import com.grouptrack.shared.startup.GtManifestSweep
import com.grouptrack.shared.startup.GtHkEnv
import com.grouptrack.shared.startup.GtHkFile
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * GTIOSHK-2026-11-16: the iPad's GtHkEnv and GtHkDb -- the real collaborators the shared startup
 * housekeeping and database audit talk to on this platform. Mirrors GtHkAndroid (101 lines) member
 * for member. NOTHING HERE DECIDES ANYTHING; every branch lives in the shared code.
 *
 * 25 OF THE 26 MEMBERS ARE PORTS OVER CODE THAT IS ALREADY SHARED, measured 11-16:
 *   initDb                 -> gtOpenDatabase x2 + GtSchema.ensure, whose own doc reads
 *                             "Android: the app's assets; iPad: the app bundle"
 *   parseGpxTrackPoints    -> GtRead.parseGpxTrackPoints (Android delegates to the same, STEP7)
 *   computeGeomHash        -> GtRead.computeGeomHash (same, STEP7)
 *   ensureDefaults         -> shared TrailFilterState.ensureDefaults(readDefaults)
 *   mergeShippedCategories -> shared TrailFilterState.mergeShippedCategories(readDefaults)
 *   the file and track members -> okio + the storage socket
 *   useOf                  -> IosTrailClass, closed 11-16
 *
 * THE ONE EXCEPTION IS sweepManifests, AND IT THROWS AS A TEMPORARY, NAMED HOLE -- not as a
 * design. Android's is HomeStateImportController.sweepManifests, 90 lines: it reads each manifest's
 * `sources` array, requires EVERY source to carry `imported` AND status == "completed", and when one
 * does not it stamps process_state = "killed" + killed_at, remembers the area
 * (KILLEDIMPORT-2026-10-02: "the gate re-opens the picker, the picker says so"), then copy-then-deletes
 * the manifest into history/ with a ':'-to-'-' filename sanitisation (':' fails the create on FAT,
 * measured on Droid 2 2026-08-21).
 *
 * ⚠ SO IT IS THE INTERRUPTED-IMPORT DETECTOR FOR TRAIL DATA, NOT HOUSEKEEPING CLUTTER. Fred,
 * 15:57: "it is the import json for trails. did it complete properly do it need to be rerun." While
 * this throws, an iPad rider whose trail import was interrupted is NEVER TOLD and never prompted to
 * rerun it, and manifests accumulate in imports/ instead of moving to history/. That is a functional
 * loss, and it matters MORE on iPad than on Android because iPadOS suspends a backgrounded import.
 *
 * The throw is safe for STARTUP only -- GtStartupHousekeeping.jobManifestSweep catches and logs, and
 * its "an un-swept manifest is clutter, not a fault" note is about the SWEEP failing, not about what
 * the manifest means. Per SVCFIRST-2026-11-16 the throw names itself rather than degrading silently.
 * ⛔ THIS MEMBER IS THE NEXT ITEM: port those 90 lines to commonMain (okio + GtOrgJson + GtTime
 * cover all of it; importsDir -> paths.dir("imports"), iso8601Now -> GtTime.local(clock.localNow(),
 * "yyyy-MM-dd'T'HH:mm:ss") as GtOsmLedger already does, and killedImportArea RETURNED rather than set
 * as global state, since HomeStatePickerScreen:258 is its only reader).
 *
 * ⚠ initDb MUST NOT THROW. GtStartupHousekeeping:102 calls env.initDb() UNGUARDED, unlike every other
 * member. So a failure is caught here and leaves the connections null -- which spatialDb()/extDb()
 * report as null, and the interface documents null as "database unavailable" with the shared code
 * handling it ("2.7e returns Result(false, 0, false, 'database unavailable')").
 *
 * ⚠ TrailFilterState.pathProvider IS A PLATFORM INPUT AND IS SET HERE. The shared object throws
 * IllegalStateException("pathProvider not set by the platform") if it is not, and ensureDefaults IS
 * guarded by its caller -- so a missed assignment would present as an app with no trail filter
 * defaults and one line in the log.
 * ⛔ TRAILPATH-2026-11-22 CORRECTION. This note used to end "Set in initDb, before anything can
 * call load()". THAT WAS BACKWARDS and it cost five hours on 11-22: shared run() calls jobPalette
 * (ensureDefaults, mergeShippedCategories, and the load() inside them) at :97 and initDb at :102,
 * so initDb is AFTER the first caller, not before it. It is now set in this class's init{} block.
 * The symptom was the rider's trail filter and map keys reverting on every cold start.
 *
 * ⚠ trackFiles() IS OKIO-SORTED where java.io.File.listFiles() guarantees no order. Accepted under
 * owner decision 11 (recorded in GtIosEnv.kt: "list() stays okio-sorted per owner decision 11").
 *
 * ⚠ readText IS NOT EAGER. 2.7e reads a track only after the .gpx filter, the "has a row" check and
 * the two-hour window have skipped most files; returning text inside GtHkFile was found at the G2f
 * switch on 11-13 to read every track on the device at startup. GtHkFile is built here WITHOUT text,
 * points or geomHash, exactly as GtHkAndroid builds it.
 */

/** The database seam. Mirrors GtHkAndroid.Db over androidx.sqlite instead of SQLiteDatabase. */
class IosHkDb(private val c: SQLiteConnection, override val path: String?) : GtHkDb {

    /**
     * ⚠ DOES NOT SWALLOW -- as GtHkAndroid.Db.rawQueryInt. 2.7e lets the count's failure reach
     * jobSchemaConverge's own catch, which is how PIECE4_THROWS is certified.
     */
    override fun rawQueryInt(sql: String): Int {
        val st = c.prepare(sql)
        try {
            return if (st.step()) st.getLong(0).toInt() else 0
        } finally { st.close() }
    }

    override fun rawQueryRows(sql: String): List<List<String?>> {
        val st = c.prepare(sql)
        try {
            val out = ArrayList<List<String?>>()
            while (st.step()) {
                val n = st.getColumnCount()
                out.add((0 until n).map { i -> if (st.isNull(i)) null else st.getText(i) })
            }
            return out
        } finally { st.close() }
    }

    /** [bindings] keeps 2.7e's bound ATTACH faithful: execSQL("ATTACH DATABASE ? AS sp", [path]). */
    override fun execSQL(sql: String, bindings: List<String>) {
        val st = c.prepare(sql)
        try {
            bindings.forEachIndexed { i, v -> st.bindText(i + 1, v) }
            st.step()
        } finally { st.close() }
    }

    /** ⭐ 2.7e detects a missing column exactly this way: the probe THROWS (jobSchemaConverge). */
    override fun probeColumnThrows(col: String): Boolean = try {
        val st = c.prepare("SELECT $col FROM trails LIMIT 1")
        try { st.step() } finally { st.close() }
        false
    } catch (e: Exception) { true }
}

class IosHkEnv(
    private val paths: GtImportPaths = IosImportPaths(),
    private val clock: GtClock = IosClock,
    /**
     * ⚠ THE TAG IS PER CALLER, NOT PER ENV -- the same reason GtHkAndroid carries it: 2.7e logged
     * housekeeping under "Housekeeping" and the audit under "DbAudit", and `logcat -s DbAudit`
     * depends on it. Hardcoding one tag sent the audit's DBREPAIR lines out mis-tagged on Android,
     * caught on the first real launch 11-13 12:16.
     */
    private val tag: String = "Housekeeping",
) : GtHkEnv {

    private val fs = FileSystem.SYSTEM
    private val log = gtLogger(tag)

    /**
     * ⛔⛔ TRAILPATH-2026-11-22: THE PATH PROVIDER IS SET AT CONSTRUCTION, NOT IN initDb.
     *
     * MEASURED on the simulator 14:17:48 -- six log lines inside 2.4ms proving the order:
     * jobPalette ran, ensureDefaults threw "pathProvider not set", load() threw and fell back to
     * reset() while latching `loaded = true`, mergeShippedCategories added 11 categories to those
     * defaults, save() threw -- and THEN initDb logged its schema line. Shared
     * GtStartupHousekeeping.run() calls jobPalette at :97 and initDb at :102 IN THAT ORDER BY
     * DESIGN (":94 PHASE 1 -- the database FILES may move. They must be CLOSED."), so a platform
     * input set inside initDb does not exist when PHASE 1 needs it.
     *
     * The rider's symptom was the trail filter and the map keys reverting on every cold start,
     * reported 11-22 09:57 and chased for five hours. The rider's file was never damaged -- save()
     * threw too -- which is what made every "something overwrote it" theory wrong.
     *
     * ⭐ WHY HERE IS THE RIGHT LAYER, not simply earlier inside initDb: the path has NO database
     * dependency. It is paths.groupTrackRoot() -- the same root initDb computes on its own first
     * line -- so there is nothing to wait for. Setting it at construction means every GtHkEnv
     * member can rely on it regardless of the order shared code calls them in, and a future job
     * added ahead of initDb cannot reintroduce this.
     * ⛔ DO NOT "fix" this by reordering GtStartupHousekeeping.run(). That order is deliberate,
     * it is Android's, and 2.8a is frozen.
     */
    init {
        TrailFilterState.pathProvider = {
            (paths.groupTrackRoot() / TrailFilterState.FILE_NAME).toString()
        }
    }

    private var spatial: SQLiteConnection? = null
    private var data: SQLiteConnection? = null
    private var spatialFile: String? = null
    private var attempted = false

    private companion object {
        const val SPATIAL_DB = "grouptrack_spatial.db"      // SpatialDbManager.SPATIAL_DB
        const val DATA_DB = "grouptrack_data.db"            // SpatialDbManager.EXTENSION_DB
        const val DEFAULTS_ASSET = "map_keys_defaults.json" // TrailFilterState.DEFAULTS_ASSET (private on Android)
        const val OWNERSHIP_FILE = "Land_Ownership.geojson" // OwnershipReclass.FILE_NAME (private on Android)
    }

    override fun now(): Long = clock.epochMs()

    // ---- SpatialDbManager ----------------------------------------------------

    /** ⚠ MUST NOT THROW -- its caller is unguarded. See the class note. */
    override fun initDb() {
        if (attempted) return
        attempted = true
        try {
            // DBDIR-2026-11-18: groupTrackRoot() is CONTRACTUALLY NOT CREATED -- GtImportPaths says
            // "GroupTrackStorage.root()" for this member and "created" only for tracksDir(), and
            // IosStorageBase.root() repeats it: "Not created -- see rootReady." The comment that used to
            // sit here claimed rootReady()'s behaviour and was wrong, so gtOpenDatabase was asked to make
            // a file inside a directory that did not exist. SQLite failed, the catch below left both
            // connections null, and DBPROBE-2026-11-18 reported "DATABASE UNAVAILABLE" with no other clue.
            // Android creates it at the DATABASE layer (GroupTrackStorage.rootReady(ctx), as MapEngine.kt:22
            // and MapSourceManager.kt:63 do), not in its paths object -- so this is the right layer for it.
            // \u26d4 NOT a copy of rootReady(): its .nomedia keeps Android's gallery out of the folder, and
            // iPadOS has no gallery scanner and no such file. Parity of INTENT, not of artefacts.
            val dir = paths.groupTrackRoot()
            fs.createDirectories(dir)                  // no-op when it already exists
            val sp = (dir / SPATIAL_DB).toString()
            val dt = (dir / DATA_DB).toString()
            val s = gtOpenDatabase(sp, false)
            val d = gtOpenDatabase(dt, false)
            val v = GtSchema.ensure(s, d) { name -> iosBundleAsset(name) }
            spatial = s; data = d; spatialFile = sp
            // TRAILPATH-2026-11-22: the pathProvider assignment WAS HERE and was too late --
            // jobPalette (shared run():97) runs before initDb (:102) by design, so it ran without
            // the platform input and fell back to defaults. It is now set in this class's init{}
            // block; one source, and nothing in PHASE 1 can find it missing. `dir` below is the
            // same paths.groupTrackRoot() the init block uses.
            log.i { "GTIOSHK-2026-11-16 initDb: schema v$v at $dir" }
        } catch (e: Exception) {
            // leave the connections null -- the contract's "database unavailable"
            log.e { "GTIOSHK-2026-11-16 initDb FAILED (databases unavailable): ${e.message}" }
        }
    }

    override fun dbDir(): String = paths.groupTrackRoot().toString()
    override fun spatialDb(): GtHkDb? = spatial?.let { IosHkDb(it, spatialFile) }
    override fun extDb(): GtHkDb? = data?.let { IosHkDb(it, (paths.groupTrackRoot() / DATA_DB).toString()) }
    override fun spatialPath(): String? = spatialFile

    override fun parseGpxTrackPoints(text: String): List<Pair<Double, Double>> =
        GtRead.parseGpxTrackPoints(text)

    override fun computeGeomHash(wkt: String): String = GtRead.computeGeomHash(wkt)

    // ---- TrailFilterState ----------------------------------------------------

    override fun ensureDefaults() = TrailFilterState.ensureDefaults { iosBundleAsset(DEFAULTS_ASSET) }

    override fun mergeShippedCategories(): List<String> =
        TrailFilterState.mergeShippedCategories { iosBundleAsset(DEFAULTS_ASSET) }

    // ---- OwnershipReclass / PRUNEUGRC ----------------------------------------

    override fun ownershipFilePath(): String = (paths.groupTrackRoot() / OWNERSHIP_FILE).toString()
    override fun fileExists(path: String): Boolean = try { fs.exists(path.toPath()) } catch (e: Exception) { false }
    override fun fileLength(path: String): Long =
        try { fs.metadataOrNull(path.toPath())?.size ?: 0L } catch (e: Exception) { 0L }
    /** ⛔ OKIODEL-2026-11-17: mustExist = true IS LOAD-BEARING. Android is `File(path).delete()`
     *  (GtHkAndroid.kt:77), which answers FALSE for a missing file; okio's delete defaults to
     *  mustExist = false and SUCCEEDS on one, so this answered TRUE. Do not simplify it back. */
    override fun fileDelete(path: String): Boolean =
        try { fs.delete(path.toPath(), mustExist = true); true } catch (e: Exception) { false }

    // ---- ConvoyTrackOps ------------------------------------------------------

    /** ⚠ The text is NOT loaded here -- see readText and the class note. */
    override fun trackFiles(): List<GtHkFile> {
        val dir = paths.tracksDir()
        val entries = try { fs.list(dir) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<GtHkFile>()
        for (p in entries) {
            val md = try { fs.metadataOrNull(p) } catch (e: Exception) { null } ?: continue
            if (md.isDirectory) continue
            out.add(GtHkFile(name = p.name, lastModified = md.lastModifiedAtMillis ?: 0L, length = md.size ?: 0L))
        }
        return out
    }

    override fun readText(f: GtHkFile): String =
        fs.read(paths.tracksDir() / f.name) { readUtf8() }

    override fun tracksDirName(): String = paths.tracksDir().name

    // ---- TrailClassifier -----------------------------------------------------

    override fun useOf(cartoCode: String): String = IosTrailClass().useOf(cartoCode)

    // ---- HomeStateImportController -------------------------------------------

    /**
     * SWEEPPORT-2026-11-16: ported, no longer a hole. The 90 lines live in GtManifestSweep; this
     * supplies the platform's three inputs and keeps the one piece of app state Android keeps.
     *
     * [killedImportArea] mirrors HomeStateImportController's @Volatile var -- the shared port RETURNS
     * it rather than holding it, and HomeStatePickerScreen's analogue reads it from here.
     */
    var killedImportArea: String? = null
        private set

    override fun sweepManifests() {
        val dir = paths.dir("imports")
        try { fs.createDirectories(dir) } catch (e: Exception) { }
        val r = GtManifestSweep.run(
            fs = fs,
            importsDir = dir,
            nowIso = GtTime.local(clock.localNow(), "yyyy-MM-dd'T'HH:mm:ss"),
            logI = { log.i { it } },
            logW = { log.w { it } },
            logE = { log.e { it } },
        )
        if (r.killedArea != null) killedImportArea = r.killedArea
    }

    // ---- the schema marker ---------------------------------------------------

    override fun markerExists(name: String): Boolean =
        try { fs.exists(paths.groupTrackRoot() / name) } catch (e: Exception) { false }

    override fun markerWrite(name: String, body: String) {
        val m = paths.groupTrackRoot() / name
        m.parent?.let { try { fs.createDirectories(it) } catch (e: Exception) { } }
        fs.write(m) { writeUtf8(body) }
    }

    // ---- observation sink ----------------------------------------------------

    override fun log(line: String) { log.i { line } }
}
