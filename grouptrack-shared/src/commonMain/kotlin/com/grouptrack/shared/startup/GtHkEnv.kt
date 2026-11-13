package com.grouptrack.shared.startup
// STEP17G2F-2026-11-13: installed for step 17 group G2f (startup housekeeping + the database audit)

/**
 * GtHkEnv — the ONE seam for G2f.
 *
 * ⛔ WHY IT EXISTS. 2.7e's StartupHousekeeping and GtDbAudit reach six Android
 * objects and the system clock. The shared port cannot call any of them. Neither
 * can the certification: jobSchemaConverge DELETES EVERY TRAIL when its marker is
 * missing, and repairNow deletes track files -- which is why both already carry
 * TEST-ONLY guards on 2.8 (GtCertGuard.skip / GtCertRun.active).
 *
 * ⭐ Every collaborator is a method here. OLD and NEW are handed the SAME env, so a
 * difference in what they report is a difference in the two implementations and
 * nothing else. This is a PRODUCTION interface, not a test fixture: the Android
 * side implements it with the real objects, exactly as GtQueueRow wraps JSONObject.
 *
 * ══════════════════════════════════════════════════════════════════
 *  THE FOUR RULES, from the 2026-11-13 harness simulation (sim17g2f)
 * ══════════════════════════════════════════════════════════════════
 *
 * ⛔ 1. now() IS SCRIPTED. repairNow decides a track file's fate by
 *       currentTimeMillis() - 2h (2.7e DbAudit line 40). The simulation ran every
 *       scenario under two flavours 45 seconds apart: a file sitting 30 s inside
 *       that window flipped from "changed in the last 2 hours" to "a real track".
 *       The Droid run and the iPad run are minutes apart, so a real clock here is
 *       a guaranteed DIFF. ⚠ Sorting does not save it -- the recorded TEXT changes.
 *
 * ⛔ 2. trackFiles() IS FIXTURE-ORDERED. 2.7e iterates listFiles(), whose order is
 *       a filesystem property, and builds `kept` in that order.
 *
 * ⛔ 3. COLLECTIONS ARE COMPARED SORTED, never as a .take(n) log string. 2.7e
 *       DbAudit line 119 logs (files - withRow).take(10); the two flavours gave
 *       identical SETS and different SAMPLES. Membership is the decision.
 *
 * ⛔ 4. THE SCHEMA MARKER'S TEXT IS NEVER COMPARED -- jobSchemaConverge writes
 *       "cleared=$cleared at ${currentTimeMillis()}" into it. Compare written-or-not
 *       and the cleared value, not the bytes.
 *
 * ⚠ NOT A FIX LIST. The two-hour window, the listFiles order and the truncated logs
 * are correct 2.7e behaviour and port AS IS. These rules govern the TEST, so that a
 * DIFF means the port diverged and not that the clock moved.
 */
interface GtHkEnv {

    // ---- clock (rule 1) -------------------------------------------------
    /** Scripted. Advances only when a scenario says so. */
    fun now(): Long

    // ---- SpatialDbManager ------------------------------------------------
    fun initDb()
    fun dbDir(): String
    /** null models "database unavailable" -- 2.7e returns Result(false, 0, false, "database unavailable"). */
    fun spatialDb(): GtHkDb?
    fun extDb(): GtHkDb?
    fun spatialPath(): String?
    fun parseGpxTrackPoints(text: String): List<Pair<Double, Double>>
    fun computeGeomHash(wkt: String): String

    // ---- TrailFilterState -------------------------------------------------
    /** 2.7e catches and logs; a scenario makes it throw by scripting it. */
    fun ensureDefaults()
    /** The categories 2.7e reports as added; drives jobPalette's log line. */
    fun mergeShippedCategories(): List<String>

    // ---- OwnershipReclass / PRUNEUGRC --------------------------------------
    fun ownershipFilePath(): String
    fun fileExists(path: String): Boolean
    fun fileLength(path: String): Long
    fun fileDelete(path: String): Boolean

    // ---- ConvoyTrackOps (rule 2) -------------------------------------------
    /** Ordered by the fixture. ⛔ NEVER a live directory listing. */
    fun trackFiles(): List<GtHkFile>
    fun tracksDirName(): String

    // ---- TrailClassifier ----------------------------------------------------
    fun useOf(cartoCode: String): String

    // ---- HomeStateImportController -------------------------------------------
    /** Throws when the scenario scripts a sweep failure; 2.7e catches and logs. */
    fun sweepManifests()

    // ---- the schema marker (rule 4) -------------------------------------------
    fun markerExists(name: String): Boolean
    fun markerWrite(name: String, body: String)

    // ---- observation sink -------------------------------------------------------
    /** 2.7e's Log calls land here. Captured for the report, ⛔ NEVER a compared key. */
    fun log(line: String)
}

/**
 * A scripted track file. lastModified is FIXTURE DATA, never a filesystem stat (rule 1).
 *
 * ⛔ [points] AND [geomHash] ARE SCRIPTED TOO, and that is deliberate. 2.7e's repairNow classifies
 * a file with SpatialDbManager.parseGpxTrackPoints and .computeGeomHash. Those are a GPX parser and
 * a hash function -- content-shaped maths that belongs to the database/geometry groups, not to G2f.
 * ⭐ Certifying them HERE would make a G2f DIFF mean "the hash differs", which tells us nothing
 * about startup housekeeping. So both sides are handed the same answers and G2f certifies what it
 * is for: the CLASSIFICATION 2.7e makes once it has them.
 */
data class GtHkFile(
    val name: String,
    val lastModified: Long,
    val length: Long,
    val text: String,
    /** What parseGpxTrackPoints returns for [text]. Empty models a file with no points. */
    val points: List<Pair<Double, Double>> = emptyList(),
    /** What computeGeomHash returns for the LINESTRING built from [points]. */
    val geomHash: String = "",
    /** The file's delete() returns false -- 2.7e then keeps it and says "(delete failed)". */
    val deleteFails: Boolean = false,
)

/**
 * The database seam.
 *
 * ⭐ execSQL RECORDS the statement rather than running it. The statement a side
 * DECIDED to run is a decision and is compared; whether SQLite would honour it is
 * the database groups' certification, not this one.
 */
interface GtHkDb {
    fun rawQueryInt(sql: String): Int
    fun rawQueryRows(sql: String): List<List<String?>>
    /** [bindings] keeps 2.7e's bound ATTACH faithful: execSQL("ATTACH DATABASE ? AS sp", arrayOf(path)). */
    fun execSQL(sql: String, bindings: List<String> = emptyList())
    /** Scripted: 2.7e detects a missing column by the probe THROWING. */
    fun probeColumnThrows(col: String): Boolean
    val path: String?
}
