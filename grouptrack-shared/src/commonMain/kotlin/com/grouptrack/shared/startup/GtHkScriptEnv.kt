package com.grouptrack.shared.startup
// STEP17G2F2-2026-11-13: NEW's env, built from the fixture (2.8 step 17, group G2f)

/**
 * GtHkScriptEnv — the shared port's collaborators, all scripted from one [GtHkFixture].
 *
 * ⭐ THE SAME CLASS RUNS ON THE DROID AND THE IPAD, over the same fixture, so NEW's answers are
 * identical on both platforms by construction. That is what makes the cross-device half of the
 * certification meaningful rather than circular: the Droid half proves NEW == 2.7e, and this
 * proves NEW is the same code behaving the same way on the other platform.
 *
 * ⛔ WHAT IS NOT MODELLED, DELIBERATELY. This does not implement SQL. [rawQueryInt] answers
 * COUNT(*) queries with a fixture-derived number where the fixture knows one and -1 otherwise,
 * because in GtDbAudit.run every count reaches a LOG LINE and nothing else. ⚠ Log lines are NOT a
 * compared key in piece 2 (OLD's counts come from a real SQLite database, NEW's from here --
 * comparing them would measure the fixture). ⭐ The DECISIONS -- which rows are missing a file,
 * which files have no row, whether the orphan block is entered, the statements built, the DETACH --
 * are computed in Kotlin on both sides from the same inputs, and those are what is compared.
 */
class GtHkScriptEnv(private val fx: GtHkFixture) : GtHkEnv {

    private val OWN = "/data/cert/ownership.gpkg"

    private val lines = ArrayList<String>()
    private val eff = ArrayList<String>()

    fun resetAll() { lines.clear(); eff.clear() }
    fun logs(): List<String> = lines.toList()
    fun effects(): List<String> = eff.toList()

    private inner class ScriptDb(override val path: String?) : GtHkDb {

        override fun rawQueryInt(sql: String): Int = when {
            sql == "SELECT COUNT(*) FROM tracks" -> fx.trackRows.size
            // ---- PIECE 4. ⛔ The count 2.7e clears by, and the arm where reading it THROWS.
            sql == "SELECT COUNT(*) FROM trails" ->
                if (fx.countThrows) throw IllegalStateException("database is locked") else fx.trailsPlain
            // ---- PIECE 3. ⛔ THESE COUNTS DRIVE DECISIONS, so they are not free to be wrong:
            // 2.7e runs the UPDATE only `if (nImp > 0)` and skips a table on `if (n == 0) continue`.
            // The Droid seeds its scratch database with exactly these many matching rows, so the
            // real SQLite count and this number agree and both sides branch for the same reason.
            sql.contains("carto_code_source='import'") -> fx.nImp
            sql.contains("carto_code_source='rider'") -> fx.nRid
            sql.startsWith("SELECT COUNT(*) FROM ") && sql.contains("NOT EXISTS") -> orphanCount(sql)
            else -> -1          // ⚠ reaches a log line only -- see the class note
        }

        /** 2.7e keys its orphan tables by a `what` label; the SQL is what reaches [rawQueryInt]. */
        private fun orphanCount(sql: String): Int {
            val what = when {
                sql.contains("route_notes.route_id") -> "route notes"
                sql.contains("artifact_type='route'") -> "route names"
                sql.contains("artifact_type='track'") -> "track names"
                sql.contains("artifact_type='trail'") -> "trail names"
                sql.contains("artifact_type='waypoint'") -> "waypoint names"
                sql.contains("track_properties.track_id") -> "track properties"
                sql.contains("trail_properties.trail_id") -> "trail properties"
                sql.contains("route_properties.route_id") -> "route properties"
                sql.contains("waypoint_properties.waypoint_id") -> "waypoint properties"
                sql.contains("track_surveys.track_id") -> "track surveys"
                else -> return 0
            }
            return fx.orphans[what] ?: 0
        }

        override fun rawQueryRows(sql: String): List<List<String?>> = when (sql) {
            // 2.7e GtDbAudit.run line 112
            "SELECT IFNULL(geom_hash,''), IFNULL(name,''), IFNULL(type,'NULL') FROM tracks" ->
                fx.trackRows.map { listOf(it.geomHash, it.name, it.type) }
            // 2.7e GtDbAudit.repairNow line 39 (piece 3)
            "SELECT geom_hash FROM tracks WHERE geom_hash IS NOT NULL" ->
                fx.trackHashes.map { listOf(it) }
            else -> emptyList()
        }

        override fun execSQL(sql: String, bindings: List<String>) {
            // ⛔ THE STATEMENT, NEVER THE BINDING. The only bound statement in G2f is
            //     ATTACH DATABASE ? AS sp   with the spatial database's file path
            // and that path is an ENVIRONMENT detail -- the Droid's scratch file, the iPad's
            // fixture string -- not a decision either side makes. Recording it would DIFF on
            // every run for a reason that has nothing to do with the port. Same reasoning as
            // rule 3: what was decided, not what the surroundings happened to be.
            eff.add(sql)
        }

        /** ⭐ PIECE 4: 2.7e detects a missing column BY THE PROBE THROWING, then ALTERs it in. */
        override fun probeColumnThrows(col: String): Boolean = col !in fx.columnsPresent
    }

    private val spatial = ScriptDb(fx.spatialPath)
    private val extension = ScriptDb(fx.spatialPath)

    // ---- clock (rule 1)
    override fun now(): Long = fx.now

    // ---- SpatialDbManager
    // ⚠ initDb is NOT recorded as an effect: the OLD copy has SpatialDbManager.init substituted
    // away (the harness opens the scratch databases itself), so recording it here would be a DIFF
    // against a line 2.7e no longer reaches.
    override fun initDb() {}
    override fun dbDir(): String = "/data/cert"
    override fun spatialDb(): GtHkDb? = if (fx.spatialNull) null else spatial
    override fun extDb(): GtHkDb? = if (fx.extNull) null else extension
    override fun spatialPath(): String? = fx.spatialPath
    // ⭐ Scripted, keyed by the file's own text / the WKT that text produces. See GtHkFile's note:
    // the parser and the hash are other groups' certifications; G2f certifies the classification.
    override fun parseGpxTrackPoints(text: String): List<Pair<Double, Double>> =
        fx.files.firstOrNull { it.text == text }?.points ?: emptyList()

    override fun computeGeomHash(wkt: String): String =
        fx.files.firstOrNull { wktOf(it.points) == wkt }?.geomHash ?: ""

    /** The LINESTRING 2.7e builds, character for character (GtDbAuditPort / 2.7e repairNow line 49). */
    private fun wktOf(pts: List<Pair<Double, Double>>): String =
        "LINESTRING(" + pts.joinToString(",") { "${it.first} ${it.second}" } + ")"

    // ---- TrailFilterState (piece 4)
    // ⚠ 2.7e catches each of these separately and carries on -- a palette failure must not stop
    // the container (its rule 4: no job's condition may stop the stream).
    override fun ensureDefaults() {
        if (fx.ensureThrows) throw IllegalStateException("cert: ensureDefaults throws")
        eff.add("ensureDefaults")
    }
    override fun mergeShippedCategories(): List<String> = fx.categoriesAdded

    // ---- OwnershipReclass / PRUNEUGRC (piece 4)
    // ⚠ 2.7e checks the file, then its .part and .tmp, in that fixed source order -- never a
    // directory listing -- so the order is a decision and not a filesystem property (rule 2).
    override fun ownershipFilePath(): String = OWN
    override fun fileExists(path: String): Boolean = path.removePrefix(OWN) in fx.ownershipSuffixes
    override fun fileLength(path: String): Long = 2L * 1_048_576L
    override fun fileDelete(path: String): Boolean {
        eff.add("delete " + path.substringAfterLast('/'))
        return true
    }

    // ---- ConvoyTrackOps (rule 2)
    override fun trackFiles(): List<GtHkFile> = fx.files
    override fun tracksDirName(): String = fx.tracksDirName

    // ---- TrailClassifier (piece 3)
    override fun useOf(cartoCode: String): String = "MOTORIZED"

    // ---- HomeStateImportController (piece 4)
    override fun sweepManifests() {
        if (fx.sweepThrows) throw IllegalStateException("cert: sweepManifests throws")
        eff.add("sweepManifests")
    }

    // ---- the schema marker (piece 4, rule 4)
    // ⛔ RULE 4: the marker's TEXT is never recorded. 2.7e writes
    //     "cleared=$cleared at ${currentTimeMillis()}"
    // into it, and comparing those bytes would compare a timestamp. That it was written, and the
    // cleared value, are the decisions -- and `cleared` is already its own compared key.
    override fun markerExists(name: String): Boolean = fx.markerPresent
    override fun markerWrite(name: String, body: String) { eff.add("markerWrite $name") }

    override fun log(line: String) { lines.add(line) }
}
