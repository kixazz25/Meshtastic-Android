package com.grouptrack.shared.startup
// STEP17G2F-2026-11-13: the shared port of 2.7e GtDbAudit (2.8 step 17, group G2f)

/**
 * GtDbAuditPort — the shared port of 2.7e's GtDbAudit.
 *
 * ⛔ PORTED AS IS, WARTS AND ALL. 2.7e's order, 2.7e's swallowed exceptions,
 * 2.7e's two-hour window. Concerns go to the living master, not into this file.
 *
 * TWO HALVES, and they are not the same thing:
 *   run()        DBAUDIT-2026-11-03 -- READ-ONLY. Counts and classifies; changes nothing.
 *   repairNow()  TRACKSFIX-2026-11-03 -- DESTRUCTIVE. Deletes track files that can
 *                never be used, puts rider trails back to PUBLIC, and deletes
 *                extension rows that point at nothing.
 * ⚠ The file header in 2.7e says "read-only"; it describes run() only, and
 * repair was added under it later. Not a defect, a stale header scope.
 *
 * ⛔⛔ THREE PORT NOTES.
 *
 * 1. repair()'s THREAD IS THE PLATFORM'S. 2.7e spawns Thread { sleep(10_000);
 *    repairNow(ctx) }. commonMain has no Thread, and a 10-second sleep in a cert
 *    harness is the nondeterminism that cost us patch k on 11-13. The port takes
 *    [spawn] and hands it the delay and the body, so the platform decides HOW and
 *    the port decides WHETHER. ⭐ WHETHER is the only decision repair() makes --
 *    the @Volatile latch means it spawns once per process and never again.
 *
 * 2. THE GtCertRun.active GUARD IS NOT PORTED, and that is correct. It is a
 *    TEST-ONLY line added to repairNow on the 2.8 branch (STEP14C-G-2026-11-08),
 *    not 2.7e code. The OLD copy is 2.7e verbatim and has no such branch, so a
 *    scenario exercising it would compare a guard against nothing.
 *
 * 3. f.isFile is not probed. 2.7e skips non-files; [GtHkFile] only ever describes
 *    a file, so the branch has no input that reaches it.
 */
object GtDbAuditPort {

    private const val TAG = "DbAudit"
    private fun log(env: GtHkEnv, s: String) = env.log("DBAUDIT $s")
    private fun rlog(env: GtHkEnv, s: String) = env.log("DBREPAIR $s")

    /** TRACKSFIX-2026-11-03: once per app start. ⚠ Reset only by a new process -- or [resetForCert]. */
    private var repaired = false

    /** ⚠ TEST-ONLY (CODE RULE 3): a cert run exercises the latch twice and needs it back. */
    fun resetForCert() { repaired = false }

    /**
     * THE DISPATCH DECISION, and the whole of it. Returns true when this call is
     * the one that spawned; false every time after.
     *
     * @param spawn the platform's "run this after a delay" -- 2.7e's
     *        Thread { Thread.sleep(10_000); repairNow(ctx) }.
     */
    fun repair(env: GtHkEnv, spawn: (delayMs: Long, body: () -> Unit) -> Unit): Boolean {
        if (repaired) return false
        repaired = true
        spawn(10_000L) {
            try { repairNow(env) } catch (e: Exception) { env.log("DBREPAIR failed: ${e.message}") }
        }
        return true
    }

    /** 2.7e's count(): catches, logs and answers 0. */
    private fun count(env: GtHkEnv, db: GtHkDb, sql: String): Int =
        try { db.rawQueryInt(sql) } catch (e: Exception) { rlog(env, "count failed: ${e.message}"); 0 }

    // RIDERLAND-2026-11-03: rider trails (imported, or made from tracks) are PUBLIC and never
    // segmented -- put back what a land update changed.
    private const val W_IMP = "carto_code_source='import' AND (COALESCE(carto_code,'')<>'OHV' OR COALESCE(use_type,'')<>'MOTORIZED' OR COALESCE(land_status,'')<>'PUBLIC' OR restricted_geom IS NOT NULL OR open_geom IS NOT NULL)"
    private const val W_RID = "carto_code_source='rider' AND (COALESCE(carto_code,'')<>'rider' OR COALESCE(land_status,'')<>'PUBLIC' OR restricted_geom IS NOT NULL OR open_geom IS NOT NULL)"

    /**
     * ⚠ 2.7e's repairNow returns Unit and reports only through the log. This returns
     * [GtRepairFindings] for the same reason run() returns [GtAuditFindings]: the decisions are
     * local variables that die with the function, and the log lines carry a .take(15) sample in
     * hash order plus a duration -- neither of which can be compared. ⛔ Nothing in production
     * reads the return value, so behaviour is unchanged; `whyBy` is written beside 2.7e's own
     * bookkeeping and never read by control flow.
     */
    fun repairNow(env: GtHkEnv): GtRepairFindings {
        env.initDb()
        val sp = env.spatialDb() ?: run { rlog(env, "spatial database not open"); return GtRepairFindings(exit = "spatial database not open") }
        val t0 = env.now()

        // 1. track files with no row
        val hashes = HashSet<String>()
        for (r in sp.rawQueryRows("SELECT geom_hash FROM tracks WHERE geom_hash IS NOT NULL")) r.getOrNull(0)?.let { hashes.add(it) }
        val recent = env.now() - 2 * 3_600_000L          // ⛔ RULE 1: env.now(), never a real clock
        var deleted = 0
        val kept = ArrayList<String>()
        val whyBy = LinkedHashMap<String, String>()        // observation only; never read by control flow
        for (f in env.trackFiles()) {                     // ⛔ RULE 2: fixture-ordered, never listFiles()
            if (!f.name.endsWith(".gpx") || f.name.startsWith(".")) continue
            if (f.name.removeSuffix(".gpx") in hashes) continue
            if (f.lastModified > recent) { kept.add("${f.name} (changed in the last 2 hours)"); whyBy[f.name] = "recent"; continue }
            val pts = try { env.parseGpxTrackPoints(env.readText(f)) } catch (e: Exception) { emptyList() }
            val why = when {
                pts.isEmpty() -> "no points"
                env.computeGeomHash("LINESTRING(" + pts.joinToString(",") { "${it.first} ${it.second}" } + ")") in hashes -> "a copy of a track GroupTrack has"
                else -> ""
            }
            if (why.isEmpty()) { kept.add("${f.name} (${pts.size} points -- a real track GroupTrack does not have; Resync tracks brings it in)"); whyBy[f.name] = "keep"; continue }
            if (!f.deleteFails) { deleted++; rlog(env, "deleted track file ${f.name} (${f.length} bytes): $why"); whyBy[f.name] = why }
            else { kept.add("${f.name} (delete failed)"); whyBy[f.name] = "delete failed" }
        }

        val nImp = count(env, sp, "SELECT COUNT(*) FROM trails WHERE " + W_IMP)
        val nRid = count(env, sp, "SELECT COUNT(*) FROM trails WHERE " + W_RID)
        try {
            if (nImp > 0) sp.execSQL("UPDATE trails SET carto_code='OHV', use_type='MOTORIZED', land_status='PUBLIC', restricted_geom=NULL, open_geom=NULL WHERE " + W_IMP)
            if (nRid > 0) sp.execSQL("UPDATE trails SET carto_code='rider', use_type='" + env.useOf("rider") + "', land_status='PUBLIC', restricted_geom=NULL, open_geom=NULL WHERE " + W_RID)
        } catch (e: Exception) { rlog(env, "rider trails: put back failed: ${e.message}") }
        rlog(env, "rider trails put back to PUBLIC / unsegmented: $nImp imported, $nRid made from tracks (changed by a land update)")
        rlog(env, "track files with no row: deleted $deleted, kept ${kept.size}${if (kept.isEmpty()) "" else ": " + kept.take(15).joinToString("; ")}")

        // 2. extension rows that point at nothing
        val base = GtRepairFindings(deleted = deleted, kept = kept.sorted(), why = whyBy.toList().sortedBy { it.first })
        val ext = env.extDb() ?: run { rlog(env, "extension database not open"); return base.copy(ext = "extension database not open") }
        val path = sp.path ?: run { rlog(env, "no spatial path"); return base.copy(ext = "no spatial path") }
        ext.execSQL("ATTACH DATABASE ? AS sp", listOf(path))
        try {
            val dates = try {
                val r = ext.rawQueryRows("SELECT IFNULL(MIN(created_at),''), IFNULL(MAX(created_at),'') FROM route_notes p WHERE NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=p.route_id)")
                if (r.isNotEmpty()) "${r[0].getOrNull(0)} .. ${r[0].getOrNull(1)}" else ""
            } catch (e: Exception) { "?" }
            val orphanWhere = listOf(
                Triple("route_notes", "NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=route_notes.route_id)", "route notes (written $dates)"),
                Triple("artifact_aliases", "artifact_type='route' AND NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=artifact_aliases.artifact_id)", "route names"),
                Triple("artifact_aliases", "artifact_type='track' AND NOT EXISTS (SELECT 1 FROM sp.tracks x WHERE x.track_id=artifact_aliases.artifact_id)", "track names"),
                Triple("artifact_aliases", "artifact_type='trail' AND NOT EXISTS (SELECT 1 FROM sp.trails x WHERE x.trail_id=artifact_aliases.artifact_id)", "trail names"),
                Triple("artifact_aliases", "artifact_type='waypoint' AND NOT EXISTS (SELECT 1 FROM sp.waypoints x WHERE x.waypoint_id=artifact_aliases.artifact_id)", "waypoint names"),
                Triple("track_properties", "NOT EXISTS (SELECT 1 FROM sp.tracks x WHERE x.track_id=track_properties.track_id)", "track properties"),
                Triple("trail_properties", "NOT EXISTS (SELECT 1 FROM sp.trails x WHERE x.trail_id=trail_properties.trail_id)", "trail properties"),
                Triple("route_properties", "NOT EXISTS (SELECT 1 FROM sp.routes x WHERE x.route_id=route_properties.route_id)", "route properties"),
                Triple("waypoint_properties", "NOT EXISTS (SELECT 1 FROM sp.waypoints x WHERE x.waypoint_id=waypoint_properties.waypoint_id)", "waypoint properties"),
                Triple("track_surveys", "NOT EXISTS (SELECT 1 FROM sp.tracks x WHERE x.track_id=track_surveys.track_id)", "track surveys"))
            for ((table, where, what) in orphanWhere) {
                val n = count(env, ext, "SELECT COUNT(*) FROM $table WHERE $where")
                if (n == 0) continue
                try { ext.execSQL("DELETE FROM $table WHERE $where"); rlog(env, "deleted $n $what pointing at nothing") }
                catch (e: Exception) { rlog(env, "$what: delete failed: ${e.message}") }
            }
        } finally { try { ext.execSQL("DETACH DATABASE sp") } catch (e: Exception) { rlog(env, "detach: ${e.message}") } }
        rlog(env, "done in ${env.now() - t0} ms")
        return base.copy(ext = "checked")
    }

    /**
     * DBAUDIT-2026-11-03: READ-ONLY. Nothing is changed or deleted.
     *
     * ⛔ RULE 3. Almost every line 2.7e logs here is a COUNT or a .take(n) sample --
     * an observation, not a decision. The decisions are: which TRACK rows have no
     * file, which files have no row, whether the extension check is skipped, and
     * the statements it builds. The harness compares those; the counts come from
     * the scripted database and would agree by construction.
     */
    fun run(env: GtHkEnv): GtAuditFindings {
        env.initDb()
        // ⛔ NOTHING ELSE IS REACHED. 2.7e returns here and probes only its exit; a findings object
        // carrying empty-but-present collections would DIFF against that for a reason that is the
        // harness's, not the port's (seen 11-13 11:02: five "(none) | " lines).
        val sp = env.spatialDb() ?: run { log(env, "spatial database not open"); return GtAuditFindings(exit = "spatial database not open") }
        val t0 = env.now()

        // ---- tracks
        log(env, "tracks: total=${count(env, sp, "SELECT COUNT(*) FROM tracks")}")
        val files = env.trackFiles()
            .filter { it.name.endsWith(".gpx") && !it.name.startsWith(".") }
            .map { it.name.removeSuffix(".gpx") }
            .toSet()
        val missing = ArrayList<String>()
        val withRow = HashSet<String>()
        for (r in sp.rawQueryRows("SELECT IFNULL(geom_hash,''), IFNULL(name,''), IFNULL(type,'NULL') FROM tracks")) {
            val h = r.getOrNull(0) ?: ""
            withRow.add(h)
            val typ = r.getOrNull(2) ?: "NULL"
            if ((typ == "TRACK" || typ == "NULL") && h !in files)
                missing.add((r.getOrNull(1)?.ifBlank { "(no name)" } ?: "(no name)") + " [" + h.take(10) + "]")
        }
        val noRow = files - withRow
        log(env, "tracks: TRACK rows with NO FILE=${missing.size}")
        log(env, "tracks: files in ${env.tracksDirName()}=${files.size}; files with NO ROW=${noRow.size}")

        // ---- extension: what points at a row that is gone
        val ext = env.extDb()
        val path = env.spatialPath()
        val sql = ArrayList<String>()
        var detached = false
        // ⚠ 2.7e does NOT return here -- it skips the orphan block and still reaches the final
        // "done in ... ms" line. An early return was my first draft and it diverged; the shape below
        // is 2.7e's if/else, so both sides run the same tail.
        var extState = "orphans not checked"
        if (ext == null || path.isNullOrEmpty()) {
            log(env, "extension database not open -- orphans not checked")
        } else try {
            ext.execSQL("ATTACH DATABASE ? AS sp", listOf(path))
            try {
                for ((type, table, id) in listOf(Triple("trail", "trails", "trail_id"), Triple("track", "tracks", "track_id"),
                        Triple("waypoint", "waypoints", "waypoint_id"), Triple("route", "routes", "route_id"))) {
                    sql.add("SELECT COUNT(*) FROM artifact_aliases a WHERE a.artifact_type='$type' AND NOT EXISTS (SELECT 1 FROM sp.$table x WHERE x.$id=a.artifact_id)")
                }
                for ((props, table, id) in listOf(Triple("trail_properties", "trails", "trail_id"), Triple("track_properties", "tracks", "track_id"),
                        Triple("waypoint_properties", "waypoints", "waypoint_id"), Triple("route_properties", "routes", "route_id"),
                        Triple("route_notes", "routes", "route_id"), Triple("track_surveys", "tracks", "track_id"))) {
                    sql.add("SELECT COUNT(*) FROM $props p WHERE NOT EXISTS (SELECT 1 FROM sp.$table x WHERE x.$id=p.$id)")
                }
                for (s in sql) log(env, "orphan probe: ${count(env, ext, s)}")
            } finally { try { ext.execSQL("DETACH DATABASE sp"); detached = true } catch (e: Exception) { log(env, "detach: ${e.message}") } }
            extState = "checked"
        } catch (e: Exception) { log(env, "extension check failed: ${e.message}") }
        log(env, "done in ${env.now() - t0} ms -- read only, nothing changed")
        // ⚠ sql only when the orphan block ran -- 2.7e builds those statements inside it, so when
        // the branch is skipped there is no list to compare, not an empty one.
        return GtAuditFindings(missing = missing.sorted(), noRow = noRow.sorted(), ext = extState,
            sql = sql.takeIf { extState == "checked" }, detached = detached)
    }
}

/**
 * What [GtDbAuditPort.run] DECIDED, as opposed to what it logged.
 * ⛔ RULE 3: the collections are sorted here, at the source, so neither side can
 * report a hash-order sample as a finding.
 */
data class GtAuditFindings(
    val missing: List<String>? = null,
    val noRow: List<String>? = null,
    val ext: String? = null,
    val exit: String? = null,
    val sql: List<String>? = null,
    val detached: Boolean = false,
)

/**
 * What [GtDbAuditPort.repairNow] DECIDED. ⛔ RULE 3: `kept` and `why` are sorted at the source so
 * neither side can report an iteration-order sample as a finding.
 */
data class GtRepairFindings(
    val exit: String? = null,
    val deleted: Int? = null,
    val kept: List<String>? = null,
    val why: List<Pair<String, String>>? = null,
    val ext: String? = null,
)
