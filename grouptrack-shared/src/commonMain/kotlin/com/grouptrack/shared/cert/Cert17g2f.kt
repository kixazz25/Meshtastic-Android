package com.grouptrack.shared.cert
// STEP17G2F1-2026-11-13: installed for step 17 group G2f, PIECE 1 (everyLaunch)

import com.grouptrack.shared.startup.GtDbAuditPort
import com.grouptrack.shared.startup.GtStartupHousekeeping as Hk
import com.grouptrack.shared.startup.GtHkDb
import com.grouptrack.shared.startup.GtHkFixture
import com.grouptrack.shared.startup.GtHkScriptEnv
import com.grouptrack.shared.startup.GtHkEnv
import com.grouptrack.shared.startup.GtHkFile
import com.grouptrack.shared.startup.GtStartupHousekeeping

/**
 * STEP17G2F-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released):
 * the G2f cert script (startup housekeeping + the database audit), ONE copy for every side.
 *
 * ⭐ FOUR PIECES, CERTIFIED ONE AT A TIME (Fred, 11-13 10:16: "replace a piece and certify, then
 * another piece -- too many variables to isolate changes that break something"). Each piece adds
 * exactly ONE new class of thing, so a DIFF names its own cause:
 *
 *   PIECE 1  everyLaunch          nothing new -- it proves the PIPE          ← this file, today
 *   PIECE 2  GtDbAudit.run        the file + database seam, read-only
 *   PIECE 3  repairNow            destruction, the clock rule, classification
 *   PIECE 4  StartupHousekeeping.run   the container and its three jobs
 *
 * ⛔ PIECE 1 IS DELIBERATELY TRIVIAL. everyLaunch is a one-line no-op, so the only thing under
 * test is the wiring: fixture -> OLD (real 2.7e on real Android objects) -> NEW (the shared port)
 * -> the Droid report -> the iPad replay -> the cross-device fingerprint match. If the pipe is
 * wrong we learn it against FOUR keys in five minutes, not against thirty keys in two hours.
 *
 * ⭐ AND IT ASSERTS SOMETHING REAL. 2.7e's own rule for everyLaunch is CHEAP, IDEMPOTENT,
 * NON-DESTRUCTIVE -- "the second run must find nothing to do". [TripwireEnv] enforces exactly
 * that: every collaborator THROWS, so if either side reaches for the clock, a database, a file or
 * the manifest sweep, the run fails loudly and names what it touched. A green piece 1 means
 * everyLaunch really is inert on both platforms.
 */
object Cert17g2f {

    interface Side {
        fun reset()
        fun everyLaunch()
        /** The lines the side logged, as 2.7e passed them -- message only, never the tag. */
        fun logs(): List<String>
        /** Anything the side DID: statements, file writes, file deletes, marker writes. */
        fun effects(): List<String>
    }

    fun run(side: Side, emit: (String, String) -> Unit) {
        piece1(side, emit)
    }

    /** PIECE 1 -- everyLaunch. Four keys. */
    private fun piece1(side: Side, emit: (String, String) -> Unit) {
        side.reset()
        side.everyLaunch()
        emit("p1|logs", side.logs().joinToString(" | "))
        emit("p1|effects", side.effects().joinToString(","))

        // ⭐ 2.7e's own rule: "IDEMPOTENT -- the second run must find nothing to do." There is no
        // latch in everyLaunch, so two calls must produce two identical lines and still no effects.
        side.reset()
        side.everyLaunch()
        side.everyLaunch()
        emit("p1|twice.logs", side.logs().joinToString(" | "))
        emit("p1|twice.effects", side.effects().joinToString(","))
    }

    /**
     * ⛔ THE PIECE-1 TRIPWIRE. Every collaborator throws. everyLaunch is documented as a no-op, so
     * the correct behaviour is that none of this is ever reached -- and if the port quietly grew a
     * dependency, the exception names it instead of the cert going green on a lie.
     */
    class TripwireEnv : GtHkEnv {
        private val lines = ArrayList<String>()
        private val eff = ArrayList<String>()
        fun resetAll() { lines.clear(); eff.clear() }
        fun logs(): List<String> = lines.toList()
        fun effects(): List<String> = eff.toList()

        override fun log(line: String) { lines.add(line) }

        private fun trip(what: String): Nothing =
            throw IllegalStateException("PIECE 1 TRIPWIRE: everyLaunch must touch nothing, but it called $what")

        override fun now(): Long = trip("now()")
        override fun initDb() = trip("initDb()")
        override fun dbDir(): String = trip("dbDir()")
        override fun spatialDb(): GtHkDb? = trip("spatialDb()")
        override fun extDb(): GtHkDb? = trip("extDb()")
        override fun spatialPath(): String? = trip("spatialPath()")
        override fun parseGpxTrackPoints(text: String): List<Pair<Double, Double>> = trip("parseGpxTrackPoints()")
        override fun computeGeomHash(wkt: String): String = trip("computeGeomHash()")
        override fun ensureDefaults() = trip("ensureDefaults()")
        override fun mergeShippedCategories(): List<String> = trip("mergeShippedCategories()")
        override fun ownershipFilePath(): String = trip("ownershipFilePath()")
        override fun fileExists(path: String): Boolean = trip("fileExists()")
        override fun fileLength(path: String): Long = trip("fileLength()")
        override fun fileDelete(path: String): Boolean = trip("fileDelete()")
        override fun trackFiles(): List<GtHkFile> = trip("trackFiles()")
        override fun tracksDirName(): String = trip("tracksDirName()")
        override fun useOf(cartoCode: String): String = trip("useOf()")
        override fun sweepManifests() = trip("sweepManifests()")
        override fun markerExists(name: String): Boolean = trip("markerExists()")
        override fun markerWrite(name: String, body: String) = trip("markerWrite()")
    }

    /** NEW: the shared port. The same class runs on the Droid and on the iPad. */
    class NewSide : Side {
        private val env = TripwireEnv()
        override fun reset() = env.resetAll()
        override fun everyLaunch() = GtStartupHousekeeping.everyLaunch(env)
        override fun logs(): List<String> = env.logs()
        override fun effects(): List<String> = env.effects()
    }

    // ══════════════════════════════════════════════════════════════════
    //  PIECE 2 -- GtDbAudit.run. Read-only. The first piece with real inputs.
    //  ⛔ PIECE 1 IS CERTIFIED AND IS NOT TOUCHED. Side2 / run2 live beside Side / run rather
    //  than replacing them, so a change here can never invalidate a green piece 1.
    // ══════════════════════════════════════════════════════════════════

    interface Side2 {
        /** Run 2.7e's audit (OLD) or the shared port (NEW) over this fixture. */
        fun auditRun(fx: GtHkFixture)
        /** The DECISIONS, keyed: missing, noRow, ext, sql, detached, exit. */
        fun probes(): Map<String, String>
        /** The statements the side actually executed, in order. */
        fun effects(): List<String>
        fun reset()
    }

    /**
     * ⛔ WHAT IS COMPARED, AND WHAT IS NOT. Compared: the sorted `missing` and `noRow` collections,
     * which arm of the extension branch was taken, the statements built, and whether the DETACH ran.
     * NOT compared: every log line. OLD's logs carry counts read from a real SQLite database and
     * NEW's come from the fixture script -- comparing them would measure the fixture rather than the
     * port. ⭐ The 11-13 simulation is why `missing` and `noRow` are compared SORTED: under two
     * platform flavours the raw lists and the .take(10) samples flipped every time while the sorted
     * collections were identical in all five scenarios.
     */
    private val PIECE2_FIXTURES = listOf(
        "full" to GtHkFixture.PIECE2,
        "noext" to GtHkFixture.PIECE2_NO_EXT,
        // ⚠ PIECE2_NO_PATH IS DELIBERATELY NOT RUN. 2.7e's guard is
        //     if (ext == null || path.isNullOrEmpty())
        // and `path` is sp.path read from a REAL open SQLiteDatabase, which always has one. There
        // is no way to hand OLD an open database with an empty path, so the second arm of that OR
        // is unreachable on a real device as well -- not just in the harness. ⭐ Recorded as an
        // observation (living master), not faked with a fixture OLD could never produce. The first
        // arm, ext == null, is covered by "noext" and reaches the same branch.
        "nospatial" to GtHkFixture.PIECE2_NO_SPATIAL,
    )

    fun run2(side: Side2, emit: (String, String) -> Unit) {
        for ((name, fx) in PIECE2_FIXTURES) {
            side.reset()
            side.auditRun(fx)
            val p = side.probes()
            for (k in listOf("exit", "missing", "noRow", "ext", "sql")) emit("p2.$name|$k", p[k] ?: "(none)")
            emit("p2.$name|effects", side.effects().joinToString(" ; "))
        }
    }

    /** NEW for piece 2: the shared port over the scripted env. The same class on both platforms. */
    class NewSide2 : Side2 {
        private var env: GtHkScriptEnv? = null
        private val p = LinkedHashMap<String, String>()
        override fun reset() { p.clear(); env = null }
        override fun auditRun(fx: GtHkFixture) {
            val e = GtHkScriptEnv(fx); env = e
            val f = GtDbAuditPort.run(e)
            // ⭐ WRITE ONLY WHAT WAS REACHED. OLD probes from inside 2.7e's control flow, so a key
            // it never reached is ABSENT. NEW must mirror that or every skipped branch DIFFs on
            // "(none)" vs "" -- which is exactly what the 11-13 11:02 run showed.
            f.exit?.let { p["exit"] = it }
            f.missing?.let { p["missing"] = it.joinToString(" | ") }
            f.noRow?.let { p["noRow"] = it.joinToString(" | ") }
            f.ext?.let { p["ext"] = it }
            f.sql?.let { p["sql"] = it.joinToString(" || ") }
        }
        override fun probes(): Map<String, String> = p
        override fun effects(): List<String> = env?.effects() ?: emptyList()
    }

    // ══════════════════════════════════════════════════════════════════
    //  PIECE 3 -- repair + repairNow. The destructive half.
    //  ⛔ Pieces 1 and 2 above are CERTIFIED and are not touched.
    // ══════════════════════════════════════════════════════════════════

    interface Side3 {
        fun reset()
        fun repairNow(fx: GtHkFixture)
        /** Two calls in one process: the @Volatile latch must spawn once and then never. */
        fun repairLatch(): String
        fun probes(): Map<String, String>
        fun effects(): List<String>
    }

    /**
     * ⭐ THE PAIR THAT MATTERS is cccc.gpx and hhhh.gpx in GtHkFixture.PIECE3 -- identical except
     * that one sits 30 s inside the two-hour window and the other 30 s outside it. With a live
     * clock the 11-13 simulation flipped that classification between runs 45 seconds apart. With
     * now() scripted on both sides they must classify differently from each other and identically
     * across platforms, which is rule 1 being certified instead of asserted.
     */
    private val PIECE3_FIXTURES = listOf(
        "full" to GtHkFixture.PIECE3,
        "norider" to GtHkFixture.PIECE3_NO_RIDER,
        "noorphans" to GtHkFixture.PIECE3_NO_ORPHANS,
        "noext" to GtHkFixture.PIECE3_NO_EXT,
        "nospatial" to GtHkFixture.PIECE3_NO_SPATIAL,
    )

    fun run3(side: Side3, emit: (String, String) -> Unit) {
        for ((name, fx) in PIECE3_FIXTURES) {
            side.reset()
            side.repairNow(fx)
            val p = side.probes()
            for (k in listOf("exit", "deleted", "kept", "why", "ext")) emit("p3.$name|$k", p[k] ?: "(none)")
            emit("p3.$name|effects", side.effects().joinToString(" ; "))
        }
        emit("p3|latch", side.repairLatch())
    }

    /** NEW for piece 3: the shared port over the scripted env. */
    class NewSide3 : Side3 {
        private var env: GtHkScriptEnv? = null
        private val p = LinkedHashMap<String, String>()
        override fun reset() { p.clear(); env = null }
        override fun repairNow(fx: GtHkFixture) {
            val e = GtHkScriptEnv(fx); env = e
            val f = GtDbAuditPort.repairNow(e)
            // ⭐ WRITE ONLY WHAT WAS REACHED -- the 11-13 11:02 lesson. A key the control flow
            // never set stays ABSENT on both sides, instead of DIFFing "(none)" against "".
            f.exit?.let { p["exit"] = it }
            f.deleted?.let { p["deleted"] = it.toString() }
            f.kept?.let { p["kept"] = it.joinToString(" | ") }
            f.why?.let { p["why"] = it.joinToString(" | ") { e2 -> "${e2.first}=${e2.second}" } }
            f.ext?.let { p["ext"] = it }
        }
        override fun repairLatch(): String {
            GtDbAuditPort.resetForCert()
            val e = GtHkScriptEnv(GtHkFixture.PIECE3)
            val first = GtDbAuditPort.repair(e) { _, _ -> }
            val second = GtDbAuditPort.repair(e) { _, _ -> }
            return "$first,$second"
        }
        override fun probes(): Map<String, String> = p
        override fun effects(): List<String> = env?.effects() ?: emptyList()
    }

    // ══════════════════════════════════════════════════════════════════
    //  PIECE 4 -- StartupHousekeeping.run and its three jobs. The last piece.
    //  ⛔ Pieces 1-3 above are CERTIFIED and are not touched.
    // ══════════════════════════════════════════════════════════════════

    interface Side4 {
        fun reset()
        fun run(fx: GtHkFixture)
        fun probes(): Map<String, String>
        fun effects(): List<String>
    }

    /**
     * ⛔ WHAT IS COMPARED: the Result the gate acts on -- (ran, cleared, needsReload, error) -- and
     * the ordered trace of everything the container DID: ensureDefaults, the DELETEs, each ALTER,
     * the marker write, the sweep, and each ownership file pruned.
     *
     * ⛔ WHAT IS NOT: the marker's TEXT (rule 4 -- 2.7e writes a timestamp into it), the categories
     * list (both sides read it from the same fixture, so comparing it compares the fixture), every
     * log line (they carry durations and counts), and THE ERROR MESSAGE. The Result records
     * err/none rather than e.message, because on the Droid that string comes from Android's SQLite
     * driver ("no such table: trails (code 1 SQLITE_ERROR)") and on the iPad from the script.
     * ⭐ WHETHER the container errored is the decision; what the driver called it is the platform.
     *
     * ⭐ THE EFFECT TRACE IS THE REAL TEST HERE. 2.7e's container rules are about ORDER: palette
     * before the boundary, converge before sweep, and no job's "nothing to do" stopping the stream.
     * A port that got the order wrong would return the same Result and a different trace.
     */
    private val PIECE4_FIXTURES = listOf(
        "full" to GtHkFixture.PIECE4,
        "marker" to GtHkFixture.PIECE4_MARKER,
        "empty" to GtHkFixture.PIECE4_EMPTY,
        "allcols" to GtHkFixture.PIECE4_ALL_COLUMNS,
        "nodb" to GtHkFixture.PIECE4_NO_DB,
        "throws" to GtHkFixture.PIECE4_THROWS,
        "jobsthrow" to GtHkFixture.PIECE4_JOBS_THROW,
        "noown" to GtHkFixture.PIECE4_NO_OWNERSHIP,
    )

    fun run4(side: Side4, emit: (String, String) -> Unit) {
        for ((name, fx) in PIECE4_FIXTURES) {
            side.reset()
            side.run(fx)
            emit("p4.$name|result", side.probes()["result"] ?: "(none)")
            emit("p4.$name|effects", side.effects().joinToString(" ; "))
        }
    }

    /** NEW for piece 4: the shared container over the scripted env. */
    class NewSide4 : Side4 {
        private var env: GtHkScriptEnv? = null
        private val p = LinkedHashMap<String, String>()
        override fun reset() { p.clear(); env = null }
        override fun run(fx: GtHkFixture) {
            val e = GtHkScriptEnv(fx); env = e
            val r = Hk.run(e)
            p["result"] = "${r.ran},${r.cleared},${r.needsReload},${if (r.error != null) "err" else "none"}"
        }
        override fun probes(): Map<String, String> = p
        override fun effects(): List<String> = env?.effects() ?: emptyList()
    }
}
