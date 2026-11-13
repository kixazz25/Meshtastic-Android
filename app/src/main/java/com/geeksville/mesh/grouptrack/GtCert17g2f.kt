package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.geeksville.mesh.convoy.StartupHousekeepingCertOld
import com.grouptrack.shared.cert.Cert17g2f
import com.grouptrack.shared.startup.GtHkFixture
import java.io.File
import java.io.Writer

/**
 * STEP17G2F1-2026-11-13 (2.8 step 17, G2f cert, Droid side) ⚠ TEST-ONLY
 * (CODE RULE 3: remove with GtCertRun before 2.8 is released).
 *
 * PIECE 1 -- everyLaunch. OLD = 2.7e's StartupHousekeeping running as REAL ANDROID CODE
 * (StartupHousekeepingCertOld, a copy with a counted substitution manifest) against real
 * Android objects. NEW = the shared GtStartupHousekeeping through Cert17g2f.NewSide.
 *
 * Lines: G2F1 <tab> key <tab> SAME|DIFF <tab> value; footer "# G2F1 done: N SAME, N DIFF in S s".
 *
 * ⚠ ONE ASYMMETRY, STATED PLAINLY. NEW's "no effects" is ENFORCED at run time -- Cert17g2f's
 * TripwireEnv throws on every collaborator, so a port that quietly grew a dependency fails loudly.
 * OLD's "no effects" is ESTABLISHED BY READING ITS BODY: StartupHousekeepingCertOld.everyLaunch is
 * forty lines, in the repo, and calls nothing but the log. There is no way to tripwire Android's
 * real statics, so the claim is auditable rather than enforced. ⛔ Anyone extending the OLD copy in
 * piece 4 must re-check that sentence.
 */
object GtCert17g2f {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** OLD: 2.7e, as real Android code. */
    private class OldSide(private val ctx: Context) : Cert17g2f.Side {
        override fun reset() { GtHkCertLog.reset() }
        override fun everyLaunch() { StartupHousekeepingCertOld.everyLaunch(ctx) }
        override fun logs(): List<String> = GtHkCertLog.all()
        // 2.7e's everyLaunch calls nothing but Log -- see the asymmetry note above.
        override fun effects(): List<String> = emptyList()
    }

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()

        try {
            Cert17g2f.run(OldSide(ctx)) { k, v -> oldMap[k] = v }
        } catch (e: Exception) {
            w.write("G2F1\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return
        }
        try {
            Cert17g2f.run(Cert17g2f.NewSide()) { k, v -> newMap[k] = v }
        } catch (e: Exception) {
            // ⭐ The tripwire firing lands here, naming the collaborator the port reached for.
            w.write("G2F1\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return
        }

        var same = 0
        var diff = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            if (o == n) { same++; w.write("G2F1\t$k\tSAME\t${esc(o)}\n") }
            else { diff++; w.write("G2F1\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# G2F1 done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }

    // ══════════════════════════════════════════════════════════════════
    //  PIECE 2 -- GtDbAudit.run.  ⛔ Piece 1 above is CERTIFIED and untouched.
    // ══════════════════════════════════════════════════════════════════

    /**
     * ⭐ OLD RUNS OVER A REAL SQLite DATABASE AND REAL FILES, built here from the same GtHkFixture
     * that scripts NEW. That is the whole point of piece 2: OLD is 2.7e executing real Cursor loops
     * against real SQLite, so a green run means the port matches 2.7e -- not that two transcriptions
     * of 2.7e match each other.
     *
     * ⚠ THE SCHEMA IS DELIBERATELY MINIMAL -- only the columns 2.7e's run() touches. This is NOT a
     * GeoPackage and must never grow into one; the real schema belongs to the database groups.
     */
    internal var lastMarkerDir: File? = null
    internal var lastOwnFile: File? = null

    internal fun buildScratch(ctx: Context, fx: GtHkFixture): Triple<SQLiteDatabase?, SQLiteDatabase?, File> {
        val dir = File(ctx.cacheDir, "gt17g2f"); dir.deleteRecursively(); dir.mkdirs()
        val tracksDir = File(dir, fx.tracksDirName); tracksDir.mkdirs()
        for (f in fx.files) {
            val out = File(tracksDir, f.name)
            out.writeText(f.text)
            out.setLastModified(f.lastModified)          // ⛔ RULE 1: fixture data, not "now"
        }

        val sp = SQLiteDatabase.openOrCreateDatabase(File(dir, "gt_cert_sp.db"), null)
        sp.execSQL("CREATE TABLE tracks(track_id TEXT, geom_hash TEXT, name TEXT, type TEXT, created_at TEXT)")
        sp.execSQL("CREATE TABLE routes(route_id TEXT, geom_hash TEXT)")
        sp.execSQL("CREATE TABLE waypoints(waypoint_id TEXT, name TEXT, type TEXT, min_lat REAL, min_lon REAL)")
        // ⭐ PIECE 4: which of TRAIL_COLUMNS exists is a FIXTURE DECISION, because 2.7e detects a
        // missing column by the probe THROWING and then ALTERs it in. Building the table with only
        // the columns the fixture says are present is what makes that branch real rather than mocked.
        val optional = listOf("status", "land_status", "use_type", "carto_code_source").filter { it in fx.columnsPresent }
        // ⭐ countThrows is made REAL, not mocked: with no trails table at all, 2.7e's
        // "SELECT COUNT(*) FROM trails" throws for the same reason it would on a damaged device,
        // and its catch arm runs for real. ⚠ A fixture OLD could not actually reach would be a
        // scenario we only pretended to certify -- see the nopath note in Cert17g2f.
        if (!fx.countThrows)
            sp.execSQL("CREATE TABLE trails(trail_id TEXT, geom_hash TEXT, geometry TEXT, carto_code TEXT, restricted_geom TEXT, open_geom TEXT"
                + optional.joinToString("") { ", $it TEXT" } + ")")
        // ⭐ EMPTY STRINGS ARE STORED AS NULL ON PURPOSE, so 2.7e's IFNULL(...) is actually
        // exercised rather than bypassed. IFNULL(NULL,'') and a stored '' both read back as '',
        // which is what the scripted env returns -- the two sides agree through the IFNULL, not
        // around it. Same for type: the fixture's "NULL" means the column IS null.
        for (r in fx.trackRows) {
            sp.execSQL(
                "INSERT INTO tracks(track_id, geom_hash, name, type, created_at) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(
                    "t-" + r.geomHash,
                    if (r.geomHash.isEmpty()) null else r.geomHash,
                    if (r.name.isEmpty()) null else r.name,
                    if (r.type == "NULL") null else r.type,
                    "2026-11-13",
                ),
            )
        }

        // ---- PIECE 3 SEEDING. ⭐ The real SQLite count must EQUAL the fixture's number, because
        // 2.7e branches on it (`if (nImp > 0)`, `if (n == 0) continue`). Seeding real matching rows
        // is what lets OLD run genuine SQL and still agree with the scripted side.
        // ⚠ every trails INSERT is guarded: the countThrows fixture has no trails table on purpose.
        if (!fx.countThrows) for (i in 0 until fx.nImp)
            sp.execSQL("INSERT INTO trails(trail_id, carto_code_source, carto_code, use_type, land_status) VALUES(?,'import','UNKNOWN','MOTORIZED','PUBLIC')",
                arrayOf<Any?>("imp-$i"))
        if (!fx.countThrows) for (i in 0 until fx.nRid)
            sp.execSQL("INSERT INTO trails(trail_id, carto_code_source, carto_code, use_type, land_status) VALUES(?,'rider','UNKNOWN','MOTORIZED','PUBLIC')",
                arrayOf<Any?>("rid-$i"))

        val ext = SQLiteDatabase.openOrCreateDatabase(File(dir, "gt_cert_ext.db"), null)
        ext.execSQL("CREATE TABLE artifact_aliases(artifact_type TEXT, artifact_id TEXT)")
        for (t in listOf("trail_properties", "track_properties", "waypoint_properties", "route_properties", "track_surveys"))
            ext.execSQL("CREATE TABLE $t(${t.substringBefore("_")}_id TEXT)")
        ext.execSQL("CREATE TABLE route_notes(route_id TEXT, created_at TEXT)")

        // orphans: ids that exist in no sp table, so 2.7e's NOT EXISTS predicates really match
        for (i in 0 until (fx.orphans["route notes"] ?: 0))
            ext.execSQL("INSERT INTO route_notes(route_id, created_at) VALUES(?, '2026-01-0" + (i % 9 + 1) + "')", arrayOf<Any?>("orphan-$i"))
        for ((label, type) in listOf("route names" to "route", "track names" to "track", "trail names" to "trail", "waypoint names" to "waypoint"))
            for (i in 0 until (fx.orphans[label] ?: 0))
                ext.execSQL("INSERT INTO artifact_aliases(artifact_type, artifact_id) VALUES(?,?)", arrayOf<Any?>(type, "orphan-$i"))
        for ((label, table, col) in listOf(
                Triple("track properties", "track_properties", "track_id"),
                Triple("trail properties", "trail_properties", "trail_id"),
                Triple("route properties", "route_properties", "route_id"),
                Triple("waypoint properties", "waypoint_properties", "waypoint_id"),
                Triple("track surveys", "track_surveys", "track_id")))
            for (i in 0 until (fx.orphans[label] ?: 0))
                ext.execSQL("INSERT INTO $table($col) VALUES(?)", arrayOf<Any?>("orphan-$i"))

        // ---- PIECE 4 SETUP: the schema marker, the ownership files, and plain trails rows.
        val markerDir = File(dir, "db"); markerDir.mkdirs()
        if (fx.markerPresent) File(markerDir, ".schema_2026-09-03A").writeText("cleared=0 at 0\n")
        val ownFile = File(dir, "ownership.gpkg")
        for (suffix in fx.ownershipSuffixes) File(dir, "ownership.gpkg$suffix").writeText("x")
        if (!fx.countThrows) for (i in 0 until fx.trailsPlain)
            sp.execSQL("INSERT INTO trails(trail_id) VALUES(?)", arrayOf<Any?>("plain-$i"))
        lastMarkerDir = markerDir
        lastOwnFile = ownFile

        // ⛔ RULE 1: the copies read GtHkCertClock, never the system clock. ⭐ And the stubs answer
        // the parser, the hash and the classifier from this same fixture -- see GtHkCertStubs.
        GtHkCertClock.set(fx.now)
        GtHkCertStubs.load(fx)

        // ⚠ setLastModified can silently fail on some filesystems, and a wrong mtime would look
        // like a classification DIFF. Record it so a failure explains itself instead of puzzling us.
        for (f in fx.files) {
            val got = File(tracksDir, f.name).lastModified()
            if (got != f.lastModified) GtHkCertLog.effect("MTIME-SET-FAILED ${f.name} want=${f.lastModified} got=$got")
        }

        return Triple(if (fx.spatialNull) null else sp, if (fx.extNull) null else ext, tracksDir)
    }

    /** OLD: 2.7e's GtDbAudit.run, as real Android code over the scratch database. */
    private class OldSide2(private val ctx: Context) : Cert17g2f.Side2 {
        private var built: Triple<SQLiteDatabase?, SQLiteDatabase?, File>? = null
        override fun reset() { GtHkCertLog.reset() }
        override fun auditRun(fx: GtHkFixture) {
            val b = GtCert17g2f.buildScratch(ctx, fx); built = b
            try { DbAuditCertOld.run(b.first, b.second, b.third) }
            finally { try { b.first?.close() } catch (_: Exception) {}; try { b.second?.close() } catch (_: Exception) {} }
        }
        override fun probes(): Map<String, String> = GtHkCertLog.probes()
        override fun effects(): List<String> = GtHkCertLog.effects()
    }

    fun run2(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try { Cert17g2f.run2(OldSide2(ctx)) { k, v -> oldMap[k] = v } }
        catch (e: Exception) { w.write("G2F2\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try { Cert17g2f.run2(Cert17g2f.NewSide2()) { k, v -> newMap[k] = v } }
        catch (e: Exception) { w.write("G2F2\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0
        var diff = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            if (o == n) { same++; w.write("G2F2\t$k\tSAME\t${esc(o)}\n") }
            else { diff++; w.write("G2F2\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# G2F2 done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }

    // ══════════════════════════════════════════════════════════════════
    //  PIECE 3 -- repair + repairNow.  ⛔ Pieces 1 and 2 above are CERTIFIED and untouched.
    // ══════════════════════════════════════════════════════════════════

    /** OLD: 2.7e's repairNow, as real Android code, really deleting real files. */
    private class OldSide3(private val ctx: Context) : Cert17g2f.Side3 {
        override fun reset() { GtHkCertLog.reset() }
        override fun repairNow(fx: GtHkFixture) {
            val b = GtCert17g2f.buildScratch(ctx, fx)
            try { DbAuditCertOld.repairNow(b.first, b.second, b.third) }
            finally { try { b.first?.close() } catch (_: Exception) {}; try { b.second?.close() } catch (_: Exception) {} }
        }
        override fun repairLatch(): String {
            DbAuditCertOld.resetForCert()
            val first = DbAuditCertOld.repair { _, _ -> }
            val second = DbAuditCertOld.repair { _, _ -> }
            return "$first,$second"
        }
        override fun probes(): Map<String, String> = GtHkCertLog.probes()
        override fun effects(): List<String> = GtHkCertLog.effects()
    }

    fun run3(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try { Cert17g2f.run3(OldSide3(ctx)) { k, v -> oldMap[k] = v } }
        catch (e: Exception) { w.write("G2F3\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try { Cert17g2f.run3(Cert17g2f.NewSide3()) { k, v -> newMap[k] = v } }
        catch (e: Exception) { w.write("G2F3\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0
        var diff = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            if (o == n) { same++; w.write("G2F3\t$k\tSAME\t${esc(o)}\n") }
            else { diff++; w.write("G2F3\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# G2F3 done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }

    // ══════════════════════════════════════════════════════════════════
    //  PIECE 4 -- the container.  ⛔ Pieces 1-3 above are CERTIFIED and untouched.
    // ══════════════════════════════════════════════════════════════════

    /** OLD: 2.7e's StartupHousekeeping.run, as real Android code, really clearing the scratch trails. */
    private class OldSide4(private val ctx: Context) : Cert17g2f.Side4 {
        override fun reset() { GtHkCertLog.reset() }
        override fun run(fx: GtHkFixture) {
            val b = GtCert17g2f.buildScratch(ctx, fx)
            try {
                StartupHousekeepingCertOld.run(b.first, b.second, GtCert17g2f.lastMarkerDir!!, GtCert17g2f.lastOwnFile!!)
            } finally {
                try { b.first?.close() } catch (_: Exception) {}
                try { b.second?.close() } catch (_: Exception) {}
            }
        }
        override fun probes(): Map<String, String> = GtHkCertLog.probes()
        override fun effects(): List<String> = GtHkCertLog.effects()
    }

    fun run4(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try { Cert17g2f.run4(OldSide4(ctx)) { k, v -> oldMap[k] = v } }
        catch (e: Exception) { w.write("G2F4\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try { Cert17g2f.run4(Cert17g2f.NewSide4()) { k, v -> newMap[k] = v } }
        catch (e: Exception) { w.write("G2F4\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0
        var diff = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            if (o == n) { same++; w.write("G2F4\t$k\tSAME\t${esc(o)}\n") }
            else { diff++; w.write("G2F4\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# G2F4 done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
