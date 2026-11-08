package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyRideJsonWriterCertOld
import com.geeksville.mesh.convoy.ConvoyRideStoreCertOld
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.GtRidesAndroid
import com.geeksville.mesh.convoy.RideApplySourceCertOld
import com.geeksville.mesh.convoy.RideCertHooks
import com.geeksville.mesh.convoy.RideCertPaths
import com.geeksville.mesh.convoy.RideImportCertOld
import com.geeksville.mesh.convoy.SpatialDbManager
import com.grouptrack.shared.ride.GDateCert
import com.grouptrack.shared.ride.GtRideApply
import com.grouptrack.shared.ride.GtRideEnv
import com.grouptrack.shared.ride.GtRideImport
import com.grouptrack.shared.ride.GtRideJson
import com.grouptrack.shared.ride.GtRideStore
import com.grouptrack.shared.ride.RideFiles
import com.grouptrack.shared.ride.RideForApply
import com.grouptrack.shared.ride.RideImportPlatform
import com.grouptrack.shared.ride.RideNet
import com.grouptrack.shared.ride.RideNetOwner
import com.grouptrack.shared.ride.RideNetworks
import com.grouptrack.shared.ride.RideRow
import com.grouptrack.shared.ride.RouteSummary
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/**
 * STEP14C-2026-11-08: the ride-model certification -- 2.7e's ConvoyRideStore / ConvoyRideJsonWriter / RideApplySource / RideImport (the
 * TEST-ONLY copies *CertOld, made by make_old_copies.py) against the shared GtRideStore / GtRideJson / GtRideApply / GtRideImport (step 14),
 * on COPIES of the frozen databases and a COPY of the rides folder. NOTHING LIVE IS WRITTEN:
 *  - each side gets its own folder app.cacheDir/gtcert_rides/<old|new>/ : the two frozen databases copied in, rides/ (seeded with copies
 *    of the live rides folder's *.json -- the live folder is only READ), staging/, export/;
 *  - SpatialDbManager's two handles point at that side's copies (certSwapDatabases; put back in finally);
 *  - OLD: RideCertPaths.rides / .staging; NEW: GtRideStore.env replaced by a cert env (put back in finally) and RideFiles = the wrapper's
 *    own GtRidesAndroid.Files with ONLY ridesDir / stagingDir pointed at the side's folders;
 *  - the clock and the ids come from RideCertHooks, reset at every case, so both sides see the same values (nowUtc ticks one second per
 *    call, so the clock columns are compared as they are -- none is replaced by T);
 *  - network configs (ConvoyNetworkStore create / deleteUnused), MapSourceManager.init, RideImportLauncher.offer and bringing the app
 *    forward are RECORDED, never done. createRideNetwork and ensureMyNetworkName are not called; saveRide only with rideNetworkId null
 *    or DEFAULT_CHANNEL (Fred 11-08: network-config files are live state).
 * Order: OLD runs cases 0-5, NEW runs 0-5 and imports OLD's packages (6), OLD imports NEW's packages (6). Each side writes its lines to
 * its own file (one side in memory at a time); then the two files are compared line by line: RIDES|<case>|<key>|SAME or DIFF (+ both).
 * Case 7 (GDate against java.time, 1e6 random cases + the edge years) is side-free and runs last.
 * ⚠ While it runs, the app's ride screens see the copies (as in the writes certification): run it with the app idle.
 * ⚠ TEST-ONLY (CODE RULE 3): remove with GtCertRun before 2.8 is released.
 */
object GtCertRides {
    private const val TAG = "CertRun"

    // =================================================================================================================================
    // the two sides behind one face
    // =================================================================================================================================

    /** the RideChoice fields; each side builds its own type from it. */
    private class CertRide(val rideId: String, val name: String, val date: String, val startTime: String,
                           val organizerName: String, val organizerId: String, val isPublic: Boolean)

    /** a build() result: OLD's JSONObject as 2.7e handed it, NEW's as the wrapper makes it (JSONObject(r.text)). */
    private class Built(val missing: String, val complete: Boolean, val json: org.json.JSONObject)

    private interface Side {
        val name: String
        fun listRoutesDefault(): List<RouteSummary>
        fun listRoutes(limit: Int): List<RouteSummary>
        fun routeGeometry(routeId: String): String?
        fun parseWktLine(wkt: String): List<Pair<Double, Double>>
        fun listRidesDefault(): List<RideRow>
        fun listRides(limit: Int): List<RideRow>
        fun rideForEdit(rideId: String): String
        fun isDistributed(rideId: String): Boolean
        fun isOrganizer(): Boolean
        fun myNetworkName(): String?
        fun openRecentRides(): String
        fun build(rideId: String): Built?
        fun save(rideId: String): String
        fun rideFile(rideId: String): String
        fun mergeWpts(gpx: String, wpts: String): String
        // CODE RULE 1: net null is the API's own choice (the organizer's own network).
        fun saveRide(n: String, d: String, st: String, desc: String, zip: String, pub: Boolean, net: String?, route: String): String?
        fun updateRide(id: String, n: String, d: String, st: String, desc: String, zip: String, pub: Boolean, route: String): Boolean
        fun updateRideDate(id: String, d: String): Boolean
        fun markDistributed(id: String): Boolean
        fun setRideWaypoints(id: String, wps: List<String>): Boolean
        fun renameRoute(id: String, n: String): Boolean
        // CODE RULE 1: r null is the API's own "No scheduled ride".
        fun checkIn(r: CertRide?, callsign: String, role: String): String
        fun saveSurvey(trackId: String, rideId: String, rating: Int, difficulty: String, recommend: Boolean, notes: String, share: Boolean): Boolean
        fun deleteRide(id: String, reason: String): Boolean
        fun expireRides()
        fun heal()
        fun loadAll(includeRecent: Boolean): List<RideForApply>
        fun isCurrent(date: String, includeRecent: Boolean): Boolean
        fun importRide(content: String, bringForward: Boolean): String
    }

    private fun editText(name: String, date: String, st: String, desc: String, pub: Boolean, route: String, unique: Boolean, cfg: String?) =
        "$name|$date|$st|$desc|$pub|$route|$unique|$cfg"
    private fun choiceText(id: String, name: String, date: String, st: String, on: String, oid: String, pub: Boolean) =
        "$id|$name|$date|$st|$on|$oid|$pub"
    private fun checkText(id: String?, name: String?, pub: Boolean, cs: String, role: String) = "$id|$name|$pub|$cs|$role"

    // STEP14C-B: 2.7e's own classes stay live during the certification (the switch is a later step), so OLD's results are copied field
    // for field into the shared classes before they are compared. The fields are identical (checked against src_snapshot_0825).
    private fun rs(x: com.geeksville.mesh.convoy.RouteSummary) =
        RouteSummary(x.routeId, x.name, x.geomHash, x.minLat, x.maxLat, x.minLon, x.maxLon)
    private fun rr(x: com.geeksville.mesh.convoy.RideRow) =
        RideRow(x.rideId, x.rideName, x.rideDate, x.startTime, x.routeId, x.routeName, x.organizerName, x.createdAt)
    private fun ra(x: com.geeksville.mesh.convoy.RideForApply) =
        RideForApply(x.eventId, x.eventName, x.eventDate, x.channelName, x.channelPsk, x.loraRegion, x.loraModemPreset,
            x.loraHopLimit, x.loraTxPower, x.loraChannelNum)

    private class OldSide(private val app: Context) : Side {
        private val o = ConvoyRideStoreCertOld
        override val name = "OLD"
        override fun listRoutesDefault(): List<RouteSummary> = o.listRoutes().map { rs(it) }
        override fun listRoutes(limit: Int): List<RouteSummary> = o.listRoutes(limit).map { rs(it) }
        override fun routeGeometry(routeId: String): String? = o.routeGeometry(routeId)
        override fun parseWktLine(wkt: String): List<Pair<Double, Double>> = o.parseWktLine(wkt)
        override fun listRidesDefault(): List<RideRow> = o.listRides().map { rr(it) }
        override fun listRides(limit: Int): List<RideRow> = o.listRides(limit).map { rr(it) }
        override fun rideForEdit(rideId: String): String =
            o.rideForEdit(rideId)?.let { e -> editText(e.name, e.date, e.startTime, e.description, e.isPublic, e.routeId, e.unique, e.configId) } ?: "null"
        override fun isDistributed(rideId: String): Boolean = o.isDistributed(rideId)
        override fun isOrganizer(): Boolean = o.isOrganizer()
        override fun myNetworkName(): String? = o.myNetworkName()
        override fun openRecentRides(): String = o.openRecentRides().joinToString(" ; ") { c ->
            choiceText(c.rideId, c.name, c.date, c.startTime, c.organizerName, c.organizerId, c.isPublic) }
        override fun build(rideId: String): Built? =
            ConvoyRideJsonWriterCertOld.build(app, rideId)?.let { r -> Built(r.missing.toString(), r.complete, r.json) }
        override fun save(rideId: String): String = ConvoyRideJsonWriterCertOld.save(app, rideId)?.toString() ?: "null"
        override fun rideFile(rideId: String): String = ConvoyRideJsonWriterCertOld.rideFile(app, rideId).path
        override fun mergeWpts(gpx: String, wpts: String): String = ConvoyRideJsonWriterCertOld.mergeWpts(gpx, wpts)
        override fun saveRide(n: String, d: String, st: String, desc: String, zip: String, pub: Boolean, net: String?, route: String): String? =
            o.saveRide(n, d, st, desc, zip, pub, net, route)
        override fun updateRide(id: String, n: String, d: String, st: String, desc: String, zip: String, pub: Boolean, route: String): Boolean =
            o.updateRide(id, n, d, st, desc, zip, pub, route)
        override fun updateRideDate(id: String, d: String): Boolean = o.updateRideDate(id, d)
        override fun markDistributed(id: String): Boolean = o.markDistributed(id)
        override fun setRideWaypoints(id: String, wps: List<String>): Boolean = o.setRideWaypoints(id, wps)
        override fun renameRoute(id: String, n: String): Boolean = o.renameRoute(id, n)
        override fun checkIn(r: CertRide?, callsign: String, role: String): String =
            o.checkIn(r?.let { ConvoyRideStoreCertOld.RideChoice(it.rideId, it.name, it.date, it.startTime, it.organizerName, it.organizerId, it.isPublic) },
                callsign, role)?.let { c -> checkText(c.rideId, c.rideName, c.isPublic, c.callsign, c.role) } ?: "null"
        override fun saveSurvey(trackId: String, rideId: String, rating: Int, difficulty: String, recommend: Boolean, notes: String, share: Boolean): Boolean =
            o.saveSurvey(trackId, ConvoyRideStoreCertOld.RideSurveyInput(rideId, rating, difficulty, recommend, notes, share))
        override fun deleteRide(id: String, reason: String): Boolean = o.deleteRide(app, id, reason)
        override fun expireRides() { o.expireRides() }
        override fun heal() { o.healFromFiles(app) }
        override fun loadAll(includeRecent: Boolean): List<RideForApply> = RideApplySourceCertOld.loadAll(app, includeRecent).map { ra(it) }
        override fun isCurrent(date: String, includeRecent: Boolean): Boolean = RideApplySourceCertOld.isCurrent(date, includeRecent)
        override fun importRide(content: String, bringForward: Boolean): String = RideImportCertOld.importRide(app, content, bringForward)
    }

    /** the wrapper's own java.io file calls (GtRidesAndroid.Files), with only the two folders pointed at this side's copies. */
    private class CertFiles(app: Context, private val rides: File, private val staging: File) : RideFiles by GtRidesAndroid.Files(app) {
        override fun ridesDir(): String = rides.path
        override fun stagingDir(): String = staging.path
    }

    /** the import's platform work, recorded exactly as the OLD copy records it. */
    private object CertPlatform : RideImportPlatform {
        override fun initMapSources() { RideCertHooks.event("initMapSources") }
        override fun offer(gpxPath: String) { RideCertHooks.event("offer " + gpxPath) }
        override fun bringForward() { RideCertHooks.event("bringForward") }
    }

    private class NewSide(private val fs: RideFiles) : Side {
        private val s = GtRideStore
        override val name = "NEW"
        override fun listRoutesDefault(): List<RouteSummary> = s.listRoutes()
        override fun listRoutes(limit: Int): List<RouteSummary> = s.listRoutes(limit)
        override fun routeGeometry(routeId: String): String? = s.routeGeometry(routeId)
        override fun parseWktLine(wkt: String): List<Pair<Double, Double>> = s.parseWktLine(wkt)
        override fun listRidesDefault(): List<RideRow> = s.listRides()
        override fun listRides(limit: Int): List<RideRow> = s.listRides(limit)
        override fun rideForEdit(rideId: String): String =
            s.rideForEdit(rideId)?.let { e -> editText(e.name, e.date, e.startTime, e.description, e.isPublic, e.routeId, e.unique, e.configId) } ?: "null"
        override fun isDistributed(rideId: String): Boolean = s.isDistributed(rideId)
        override fun isOrganizer(): Boolean = s.isOrganizer()
        override fun myNetworkName(): String? = s.myNetworkName()
        override fun openRecentRides(): String = s.openRecentRides().joinToString(" ; ") { c ->
            choiceText(c.rideId, c.name, c.date, c.startTime, c.organizerName, c.organizerId, c.isPublic) }
        override fun build(rideId: String): Built? =
            GtRideJson.build(fs, rideId)?.let { r -> Built(r.missing.toString(), r.complete, org.json.JSONObject(r.text)) }   // the wrapper's Result
        override fun save(rideId: String): String = GtRideJson.save(fs, rideId)?.toString() ?: "null"
        override fun rideFile(rideId: String): String = GtRideJson.rideFile(fs, rideId)
        override fun mergeWpts(gpx: String, wpts: String): String = GtRideJson.mergeWpts(gpx, wpts)
        override fun saveRide(n: String, d: String, st: String, desc: String, zip: String, pub: Boolean, net: String?, route: String): String? =
            s.saveRide(n, d, st, desc, zip, pub, net, route)
        override fun updateRide(id: String, n: String, d: String, st: String, desc: String, zip: String, pub: Boolean, route: String): Boolean =
            s.updateRide(id, n, d, st, desc, zip, pub, route)
        override fun updateRideDate(id: String, d: String): Boolean = s.updateRideDate(id, d)
        override fun markDistributed(id: String): Boolean = s.markDistributed(id)
        override fun setRideWaypoints(id: String, wps: List<String>): Boolean = s.setRideWaypoints(id, wps)
        override fun renameRoute(id: String, n: String): Boolean = s.renameRoute(id, n)
        override fun checkIn(r: CertRide?, callsign: String, role: String): String =
            s.checkIn(r?.let { GtRideStore.RideChoice(it.rideId, it.name, it.date, it.startTime, it.organizerName, it.organizerId, it.isPublic) },
                callsign, role)?.let { c -> checkText(c.rideId, c.rideName, c.isPublic, c.callsign, c.role) } ?: "null"
        override fun saveSurvey(trackId: String, rideId: String, rating: Int, difficulty: String, recommend: Boolean, notes: String, share: Boolean): Boolean =
            s.saveSurvey(trackId, GtRideStore.RideSurveyInput(rideId, rating, difficulty, recommend, notes, share))
        override fun deleteRide(id: String, reason: String): Boolean = s.deleteRide(fs, id, reason)
        override fun expireRides() { s.expireRides() }
        override fun heal() { s.healFromFiles(fs) }
        override fun loadAll(includeRecent: Boolean): List<RideForApply> = GtRideApply.loadAll(fs, includeRecent)
        override fun isCurrent(date: String, includeRecent: Boolean): Boolean = GtRideApply.isCurrent(date, includeRecent)
        override fun importRide(content: String, bringForward: Boolean): String = GtRideImport.importRide(fs, content, bringForward, CertPlatform)
    }

    /** NEW's environment: the live wrapper's own pieces (the database suppliers -- now the swapped copies -- the profile, the spatial
     *  helpers, org.json's packageText), with the clock and ids from RideCertHooks and the network create / deleteUnused recorded only. */
    private fun certEnv(live: GtRideEnv): GtRideEnv = GtRideEnv(
        spatialDb = live.spatialDb,
        dataDb = live.dataDb,
        nowUtc = { RideCertHooks.nowUtc() },
        today = { RideCertHooks.today().toString() },
        epochMsToLocalDate = { ms -> RideCertHooks.epochMsToLocalDate(ms).toString() },
        newId = { RideCertHooks.newId() },
        profile = live.profile,
        networks = object : RideNetworks {
            override fun load(configId: String): RideNet? = live.networks.load(configId)
            override fun create(owner: RideNetOwner, ownerId: String, name: String): RideNet? =
                RideCertHooks.networkCreateNew(owner, ownerId, name)
            override fun deleteUnused(configId: String) { RideCertHooks.networkDeleteUnused(configId) }
        },
        defaultChannel = live.defaultChannel,
        spatial = live.spatial,
        packageText = live.packageText,
    )

    // =================================================================================================================================
    // files, lines, hashes
    // =================================================================================================================================

    private class Dirs(val root: File) {
        val rides = File(root, "rides")
        val staging = File(root, "staging")
        val export = File(root, "export")
        val spatial = File(root, "grouptrack_spatial.db")
        val data = File(root, "grouptrack_data.db")
    }

    /** one side's result lines: "<case>|<key>\t<value>", one per line; the side's root folder written as {ROOT}. */
    // append: phase C adds OLD's case 6 after its cases 0-5 (the file phase A wrote).
    private class Out(file: File, private val root: String, append: Boolean) : java.io.Closeable {
        private val bw = java.io.OutputStreamWriter(java.io.FileOutputStream(file, append), Charsets.UTF_8).buffered()
        fun line(case: String, key: String, value: String) {
            bw.write(clean("$case|$key", 400)); bw.write("\t"); bw.write(clean(value, 6000)); bw.write("\n")
        }
        private fun clean(s0: String, cap: Int): String {
            val s = s0.replace(root, "{ROOT}").replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
            return if (s.length <= cap) s else "len=${s.length} sha=${sha(s)} head=${s.take(300)}"
        }
        fun flush() { bw.flush() }
        override fun close() { bw.close() }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun sha(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b)).take(32)
    private fun sha(s: String): String = sha(s.toByteArray(Charsets.UTF_8))
    private fun shaFile(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(65536)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return hex(md.digest()).take(32)
    }

    private fun q(n: String): String = "\"" + n.replace("\"", "\"\"") + "\""

    /** the first column of the first row as text. CODE RULE 1: null = no row, or the value is NULL (both are real answers here). */
    private fun query1(db: SQLiteDatabase, sql: String): String? = try {
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) { null }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    /** the cell of triple i (typeof, value-or-ends, length) -- REAL as its exact bits. */
    private fun cell(c: Cursor, i: Int): String {
        val v = 3 * i + 1
        return when (c.getString(3 * i)) {
            "null" -> "N"
            "integer" -> "I" + c.getLong(v)
            "real" -> "R" + java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(c.getDouble(v)))
            "text" -> {
                val s = c.getString(v) ?: ""
                val len = c.getLong(3 * i + 2)
                if (len > 200_000L) "S#$len~" + sha(s) else if (s.length > 200) "S#${s.length}:" + sha(s) else "S" + s
            }
            "blob" -> {
                val len = c.getLong(3 * i + 2)
                "B#$len:" + (if (c.getType(v) == Cursor.FIELD_TYPE_BLOB) sha(c.getBlob(v)) else sha(c.getString(v) ?: ""))
            }
            else -> "?" + c.getString(3 * i)
        }
    }

    /** every row of [table], ordered by its primary key (rowid when it has none); one (key, value) per row. Long values are hashed in
     *  SQL-side pieces (the first and last 4000 characters + the length) so no row overflows the cursor window. */
    private fun rows(db: SQLiteDatabase, table: String, each: (key: String, value: String) -> Unit) {
        if (!tableExists(db, table)) { each("NO TABLE", ""); return }
        val cols = ArrayList<String>()
        val pk = ArrayList<Pair<Int, Int>>()   // (pk order, column index)
        db.rawQuery("PRAGMA table_info(" + q(table) + ")", null).use { c ->
            while (c.moveToNext()) { val p = c.getInt(5); if (p > 0) pk.add(Pair(p, cols.size)); cols.add(c.getString(1)) }
        }
        val pkIdx = pk.sortedBy { it.first }.map { it.second }
        val order = if (pkIdx.isEmpty()) "rowid" else pkIdx.joinToString(", ") { q(cols[it]) }
        val sel = cols.joinToString(", ") { col ->
            val x = q(col)
            "typeof($x), CASE WHEN length($x) > 200000 THEN substr($x, 1, 4000) || '~' || substr($x, -4000) ELSE $x END, length($x)"
        }
        each("columns", cols.joinToString(","))
        var n = 0
        db.rawQuery("SELECT $sel FROM ${q(table)} ORDER BY $order", null).use { c ->
            while (c.moveToNext()) {
                n++
                val cells = (0 until cols.size).map { cell(c, it) }
                val key = if (pkIdx.isEmpty()) "#$n" else pkIdx.joinToString("/") { cells[it] }
                each("row:$key", cells.joinToString("|"))
            }
        }
        each("count", n.toString())
    }

    private fun fingerprint(o: Out, case: String, db: SQLiteDatabase, table: String) {
        rows(db, table) { k, v -> o.line(case, "fp:$table:$k", v) }
    }

    /** one hash of every row of [table] (for the heal's idempotence check). */
    private fun tableHash(db: SQLiteDatabase, table: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        rows(db, table) { k, v -> md.update((k + "\t" + v + "\n").toByteArray(Charsets.UTF_8)) }
        return hex(md.digest()).take(32)
    }

    /** every file under [dir] (sorted relative paths: length + sha256), plus the folder's own listFiles order (heal's first-wins). */
    private fun hashFiles(o: Out, case: String, dir: File, label: String) {
        val all = ArrayList<String>()
        fun walk(f: File, rel: String) {
            val kids = f.listFiles() ?: return
            for (k in kids) {
                val r = if (rel.isEmpty()) k.name else rel + "/" + k.name
                if (k.isDirectory) { all.add("$r/"); walk(k, r) } else all.add(r)
            }
        }
        walk(dir, "")
        o.line(case, "files:$label:listFiles-order", dir.listFiles()?.joinToString(",") { it.name } ?: "null")
        for (r in all.sorted()) {
            val f = File(dir, r.removeSuffix("/"))
            o.line(case, "files:$label:$r", if (r.endsWith("/")) "dir" else "len=${f.length()} sha=${shaFile(f)}")
        }
        o.line(case, "files:$label:count", all.size.toString())
    }

    private fun fingerprintAll(o: Out, case: String, sp: SQLiteDatabase, dt: SQLiteDatabase, d: Dirs) {
        for (t in listOf("rides", "enrollments", "ride_surveys", "users", "ride_waypoints")) fingerprint(o, case, dt, t)
        fingerprint(o, case, sp, "routes")
        hashFiles(o, case, d.rides, "rides")
        hashFiles(o, case, d.staging, "staging")
        o.flush()
    }

    /** runs [body] inside a transaction on the data copy that is ROLLED BACK (openRecentRides at a moved date also expires rides). */
    private fun <T> rolledBack(dt: SQLiteDatabase, body: () -> T): T {
        dt.beginTransaction()
        try { return body() } finally { dt.endTransaction() }
    }

    private fun safe(f: () -> String): String = try { f() } catch (e: Throwable) { "EXCEPTION $e" }

    private fun progress(case: String, side: Side, what: String) { Log.i(TAG, "GTCERT rides $case ${side.name} $what") }

    // =================================================================================================================================
    // the run
    // =================================================================================================================================

    fun run(app: Context, spatialPath: String, dataPath: String?, stamp: String, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0
        val ext = GroupTrackStorage.dir("gtcert_rides_" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(java.util.Date()) + ".txt").bufferedWriter()
        fun put(s: String) { w.write(s); ext.write(s) }
        fun check(key: String, a: String, b: String) {
            if (a == b) { same++; put("RIDES|$key|SAME\n") }
            else { diff++; put("RIDES|$key|DIFF\n  OLD|$a\n  NEW|$b\n"); Log.w(TAG, "GTCERT rides DIFF $key\n OLD ${a.take(300)}\n NEW ${b.take(300)}") }
        }
        put("# rides: frozen copy $stamp\n")
        if (dataPath == null) {
            put("# rides: the frozen copy has no data database -- not run\n"); ext.close(); return
        }
        GtRidesAndroid.ready()                 // the live env is set (and so can be saved and put back)
        val liveEnv = GtRideStore.env
        val base = File(app.cacheDir, "gtcert_rides")
        deleteTree(base, base); base.mkdirs()
        val oldD = Dirs(File(base, "old")); val newD = Dirs(File(base, "new"))
        val oldFile = File(base, "old.txt"); val newFile = File(base, "new.txt")
        val realToday = LocalDate.now()
        RideCertHooks.zone = java.time.ZoneId.systemDefault()
        put("# rides: today $realToday, zone ${RideCertHooks.zone}, clock from 2026-11-08T12:00:00Z + case days, ids UUID-shaped counters\n")
        // STEP14C-F: the frozen files at the start (run _1207: NEW's copy had no narrative row for one route; OLD's had one)
        for (path in listOf(spatialPath, dataPath)) for (sfx in listOf("", "-wal", "-journal", "-shm"))
            put("# rides: frozen ${describe(File(path + sfx), !(sfx == "" && path == spatialPath))}\n")
        var aborted: String? = null   // CODE RULE 1: null = the run was not aborted
        try {
            // ---- phase A: OLD, cases 0-5 ----
            put("# rides: OLD copy before opening: ${prepare(app, base, oldD, spatialPath, dataPath)}\n")
            Out(oldFile, oldD.root.path, false).use { o ->
                withSide(oldD) { sp, dt ->
                    pointOld(oldD)
                    script(OldSide(app), oldD, sp, dt, o, realToday)
                }
            }
            put("# rides: OLD cases 0-5 done in ${(System.currentTimeMillis() - t0) / 1000} s\n")
            // ---- phase B: NEW, cases 0-5, then imports OLD's packages ----
            put("# rides: NEW copy before opening: ${prepare(app, base, newD, spatialPath, dataPath)}\n")
            Out(newFile, newD.root.path, false).use { o ->
                withSide(newD) { sp, dt ->
                    RideCertHooks.root = newD.root.path
                    GtRideStore.env = certEnv(liveEnv)
                    try {
                        val side = NewSide(CertFiles(app, newD.rides, newD.staging))
                        script(side, newD, sp, dt, o, realToday)
                        importCase(side, newD, sp, dt, o, oldD.export, realToday); handles("6", sp, dt)
                        lenientCase(side, newD, dt, o, realToday); handles("8", sp, dt)
                    } finally { GtRideStore.env = liveEnv }
                }
            }
            put("# rides: NEW cases 0-6 done in ${(System.currentTimeMillis() - t0) / 1000} s\n")
            // ---- phase C: OLD imports NEW's packages ----
            Out(oldFile, oldD.root.path, true).use { o ->
                withSide(oldD) { sp, dt ->
                    pointOld(oldD)
                    val side = OldSide(app)
                    importCase(side, oldD, sp, dt, o, newD.export, realToday); handles("6", sp, dt)
                    lenientCase(side, oldD, dt, o, realToday); handles("8", sp, dt)
                }
            }
            put("# rides: OLD case 6 done in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        } catch (e: Throwable) {
            aborted = e.toString()
            put("# rides: ABORTED $e\n"); Log.e(TAG, "GTCERT rides aborted", e)
        } finally {
            GtRideStore.env = liveEnv
        }
        // ---- compare, line by line ----
        if (oldFile.exists() && newFile.exists()) compare(oldFile, newFile) { k, a, b -> check(k, a, b) }
        // STEP14C-C: each side's exported packages, copied where adb can read them (the app is not debuggable, so run-as cannot reach
        // cache/). GroupTrack/gtcert_rides_export/<old|new>/ -- emptied first, test copies only.
        try {
            val exp = GroupTrackStorage.dir("gtcert_rides_export", app)
            exp.deleteRecursively()
            for ((side, dir) in listOf("old" to oldD.export, "new" to newD.export)) {
                val to = File(exp, side).also { it.mkdirs() }
                dir.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(to, it.name), overwrite = true) }
            }
            put("# rides: packages copied to ${exp.path}\n")
        } catch (e: Throwable) { put("# rides: package copy failed $e\n") }
        ext.flush()
        // ---- case 7: GDate against java.time ----
        try { gdate { k, a, b -> check(k, a, b) } } catch (e: Throwable) { put("# rides: case 7 ABORTED $e\n") }
        put("# rides done: same $same diff $diff in ${(System.currentTimeMillis() - t0) / 1000} s" +
            (if (aborted != null) " (ABORTED: $aborted)" else "") + "\n")
        Log.i(TAG, "GTCERT rides done: same $same diff $diff")
        try { ext.close() } catch (_: Exception) {}
    }

    /** the OLD copies' folders and the event root for this side. */
    private fun pointOld(d: Dirs) {
        RideCertPaths.rides = d.rides
        RideCertPaths.staging = d.staging
        RideCertHooks.root = d.root.path
    }

    /** deletes [f] and everything under it -- only inside [base] (app.cacheDir/gtcert_rides), never anywhere else. */
    private fun deleteTree(f: File, base: File) {
        val b = base.canonicalPath
        if (!(f.canonicalPath == b || f.canonicalPath.startsWith(b + File.separator))) throw IllegalStateException("refusing to delete $f")
        f.deleteRecursively()
    }

    /** a side's folder: the frozen databases COPIED in (with a -wal when the frozen copy has one), empty rides/ staging/ export/, and
     *  rides/ seeded with copies of the live rides folder's *.json (sorted by name, so both sides are created in the same order).
     *  The live folder is only listed and read. */
    /** STEP14C-F: the files as they are (length, last-modified, and for the data database and any journal a fingerprint). */
    private fun describe(f: File, fingerprint: Boolean): String =
        if (!f.exists()) "${f.name}: none"
        else "${f.name}: len=${f.length()} mtime=${f.lastModified()}" + (if (fingerprint) " sha=${shaFile(f)}" else "")

    private fun prepare(app: Context, base: File, d: Dirs, spatialPath: String, dataPath: String): String {
        deleteTree(d.root, base)
        d.root.mkdirs(); d.rides.mkdirs(); d.staging.mkdirs(); d.export.mkdirs()
        for ((src, dst) in listOf(Pair(File(spatialPath), d.spatial), Pair(File(dataPath), d.data))) {
            src.copyTo(dst, overwrite = true)
            for (sfx in listOf("-wal", "-journal")) { val s = File(src.path + sfx); if (s.isFile) s.copyTo(File(dst.path + sfx), overwrite = true) }
        }
        val live = GroupTrackStorage.dir("rides", app)
        live.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            f.copyTo(File(d.rides, f.name), overwrite = true)
        }
        // STEP14C-F: what the copy holds before anything opens it
        return d.root.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?.joinToString(" | ") { describe(it, it.name != "grouptrack_spatial.db") } ?: "empty"
    }

    private fun withSide(d: Dirs, body: (SQLiteDatabase, SQLiteDatabase) -> Unit) {
        val sp = SQLiteDatabase.openDatabase(d.spatial.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val dt = SQLiteDatabase.openDatabase(d.data.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val prev = SpatialDbManager.certSwapDatabases(sp, dt)
                try { body(sp, dt) } finally { SpatialDbManager.certSwapDatabases(prev.first, prev.second) }
            } finally { dt.close() }
        } finally { sp.close() }
    }

    private fun splitLine(l: String?): Pair<String, String> {
        if (l == null) return Pair("<end of file>", "MISSING")
        val t = l.indexOf('\t')
        return if (t < 0) Pair(l, "") else Pair(l.substring(0, t), l.substring(t + 1))
    }

    /** STEP14C-B: the compare re-syncs on keys. When the keys differ, the nearer match (within 2000 lines) decides which side has
     *  extra lines; each extra line is one DIFF ("only OLD" / "only NEW") and the compare carries on in step. One extra line no
     *  longer turns everything after it into DIFFs (run 2026-11-08_0918: one lenient ride gave 201). */
    private fun compare(a: File, b: File, emit: (key: String, va: String, vb: String) -> Unit) {
        val la = a.readLines().map { splitLine(it) }; val lb = b.readLines().map { splitLine(it) }
        var i = 0; var j = 0; val win = 2000
        fun find(list: List<Pair<String, String>>, from: Int, key: String): Int {
            var k = from; val end = minOf(list.size, from + win)
            while (k < end) { if (list[k].first == key) return k - from; k++ }
            return -1
        }
        while (i < la.size || j < lb.size) {
            if (i >= la.size) { emit("${lb[j].first} (only NEW)", "MISSING", lb[j].second); j++; continue }
            if (j >= lb.size) { emit("${la[i].first} (only OLD)", la[i].second, "MISSING"); i++; continue }
            val (ka, va) = la[i]; val (kb, vb) = lb[j]
            if (ka == kb) { emit(ka, va, vb); i++; j++; continue }
            val inB = find(lb, j, ka)     // OLD's key further on in NEW: NEW has extra lines first
            val inA = find(la, i, kb)     // NEW's key further on in OLD: OLD has extra lines first
            if (inB >= 0 && (inA < 0 || inB <= inA)) { repeat(inB) { emit("${lb[j].first} (only NEW)", "MISSING", lb[j].second); j++ } }
            else if (inA >= 0) { repeat(inA) { emit("${la[i].first} (only OLD)", la[i].second, "MISSING"); i++ } }
            else { emit("$ka (only OLD)", va, "MISSING"); emit("$kb (only NEW)", "MISSING", vb); i++; j++ }
        }
    }

    // =================================================================================================================================
    // the scripted cases (the SAME calls on each side)
    // =================================================================================================================================

    /** what the seed found on the copy. CODE RULE 1: myCfg null = the rider has no network yet; routeName / anchorRoute null = none. */
    private class Seed(val self: String, val myCfg: String?, val route: String, val routeName: String?, val wps: List<String>,
                       val fut: String, val past: String, val anchorRoute: String?)

    private fun script(s: Side, d: Dirs, sp: SQLiteDatabase, dt: SQLiteDatabase, o: Out, realToday: LocalDate) {
        o.line("0-seed", "copy-opened:route_notes", notesState(dt))   // STEP14C-F
        val seed = case0(s, d, sp, dt, o, realToday); o.flush(); handles("0", sp, dt)
        o.line("0-seed", "after-case0:route_notes", notesState(dt))
        case1(s, sp, dt, o, realToday); o.flush(); handles("1", sp, dt)
        o.line("1-read", "after-case1:route_notes", notesState(dt))
        case2(s, d, dt, o, realToday); o.flush(); handles("2", sp, dt)
        case3(s, d, sp, dt, o, realToday, seed); handles("3", sp, dt)
        case4(s, d, dt, o, realToday); handles("4", sp, dt)
        case5(s, d, sp, dt, o, realToday); handles("5", sp, dt)
    }

    /** STEP14C-F: route_notes as rows: count, narrative count, fingerprint, and the copy's journal mode. */
    private fun notesState(dt: SQLiteDatabase): String = safe {
        val n = query1(dt, "SELECT count(*) FROM route_notes"); val k = query1(dt, "SELECT count(*) FROM route_notes WHERE kind='narrative'")
        val jm = query1(dt, "PRAGMA journal_mode")
        "rows=$n narrative=$k hash=${tableHash(dt, "route_notes")} journal_mode=$jm"
    }

    /** STEP14C-D: the app's two database handles must still be THIS side's copies after every case. Run 2026-11-08_0928 lost one
     *  ride's recipe and waypoints on the NEW side (both read from the data database, both swallow errors) and run _1002 did not --
     *  something else in the app touched the databases during the run. A changed handle aborts the run (NOT CERTIFIED). */
    private fun handles(case: String, sp: SQLiteDatabase, dt: SQLiteDatabase) {
        val a = SpatialDbManager.getSpatialDb(); val b = SpatialDbManager.getExtensionDb()
        if (a !== sp || b !== dt) throw IllegalStateException("database handles changed during case $case: ${a?.path} / ${b?.path}")
        if (!sp.isOpen || !dt.isOpen) throw IllegalStateException("a copy was closed during case $case")
    }

    // CODE RULE 1: route / cfgId / expires are nullable because a NULL column is itself a case under test (no route, network, expiry).
    private fun seedRide(o: Out, dt: SQLiteDatabase, n: Int, id: String, organizerId: String, route: String?, date: String, desc: String,
                         mode: String, cfgId: String?, expires: String?, isPublic: Int) {
        val r = try {
            dt.execSQL("INSERT INTO rides (ride_id, organizer_id, organizer_name, route_id, ride_name, ride_date, start_time, description, " +
                "zip_code, is_public, config_mode, config_id, expires_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, organizerId, "Cert Organizer", route, "Cert $id", date, "08:00", desc, "84101", isPublic, mode, cfgId, expires,
                    String.format(Locale.US, "2026-11-01T00:%02d:00Z", n), "2026-11-01T00:00:00Z"))
            "OK"
        } catch (e: Exception) { "FAILED ${e.message}" }
        o.line("0-seed", "row:$id", r)
    }

    private fun case0(s: Side, d: Dirs, sp: SQLiteDatabase, dt: SQLiteDatabase, o: Out, realToday: LocalDate): Seed {
        val c = "0-seed"; progress(c, s, "start")
        RideCertHooks.reset(0); RideCertHooks.todayDate = realToday
        o.line(c, "ride_waypoints table", safe {
            dt.execSQL("CREATE TABLE IF NOT EXISTS ride_waypoints (ride_id TEXT NOT NULL, waypoint_id TEXT NOT NULL, seq INTEGER NOT NULL, " +
                "PRIMARY KEY (ride_id, waypoint_id))"); "OK" })
        val self = query1(dt, "SELECT user_id FROM users WHERE is_self = 1 LIMIT 1") ?: "cert-user"
        val myCfg = query1(dt, "SELECT config_id FROM users WHERE is_self = 1 AND config_id IS NOT NULL LIMIT 1")
        val route = query1(sp, "SELECT route_id FROM routes ORDER BY route_id LIMIT 1") ?: "cert-no-route-on-this-tablet"
        val routeName = query1(sp, "SELECT name FROM routes ORDER BY route_id LIMIT 1")
        val wps = ArrayList<String>()
        if (tableExists(sp, "waypoints")) {
            var pkCol: String? = null
            sp.rawQuery("PRAGMA table_info(waypoints)", null).use { k -> while (k.moveToNext()) if (k.getInt(5) == 1) pkCol = k.getString(1) }
            val pc = pkCol
            if (pc != null) sp.rawQuery("SELECT ${q(pc)} FROM waypoints ORDER BY ${q(pc)} LIMIT 3", null).use { k ->
                while (k.moveToNext()) k.getString(0)?.let { wps.add(it) } }
        }
        while (wps.size < 3) wps.add("cert-wp-${wps.size + 1}")
        // the anchor named with & < > " (PORT_NOTES f2): the first narrative recipe with an anchor, renamed ON THE COPY
        var anchorRoute: String? = null
        if (tableExists(dt, "route_notes")) {
            var hit: Triple<Long, String, String>? = null
            dt.rawQuery("SELECT rowid, route_id, payload FROM route_notes WHERE kind='narrative' ORDER BY rowid", null).use { k ->
                while (hit == null && k.moveToNext()) {
                    val p = try { org.json.JSONObject(k.getString(2) ?: "") } catch (e: Exception) { null } ?: continue
                    val r = p.optJSONObject("recipe") ?: continue
                    if (r.has("anchorLat") && r.has("anchorLon")) {
                        r.put("anchorName", "Cert & <Trail> \"Head\"")
                        hit = Triple(k.getLong(0), k.getString(1) ?: "", p.toString())
                    }
                }
            }
            val h = hit
            if (h != null) {
                o.line(c, "anchor-escape", safe { dt.execSQL("UPDATE route_notes SET payload = ? WHERE rowid = ?", arrayOf<Any?>(h.third, h.first)); "OK ${h.second}" })
                anchorRoute = h.second
            } else o.line(c, "anchor-escape", "no narrative recipe with an anchor on this tablet")
        } else o.line(c, "anchor-escape", "no route_notes table")
        val fut = realToday.plusDays(10).toString()
        val past = realToday.minusDays(40).toString()
        val dflt = com.grouptrack.core.NetworkGenerator.DEFAULT_CHANNEL
        o.line(c, "facts", "self=$self myCfg=$myCfg route=$route routeName=$routeName wps=$wps fut=$fut past=$past default=$dflt anchor=$anchorRoute")
        // case-2 rides (the package variants) and case-3 targets
        var n = 0
        seedRide(o, dt, n++, "cert-b-default", self, route, fut, "Cert ride", "inherit_org", dflt, null, 1)
        seedRide(o, dt, n++, "cert-b-own", self, route, fut, "Cert ride", "unique", myCfg, null, 0)
        seedRide(o, dt, n++, "cert-b-own2", self, route, fut, "Cert ride", "unique", myCfg, null, 0)
        seedRide(o, dt, n++, "cert-b-nonet", self, route, fut, "Cert ride", "inherit_leader", null, null, 0)
        seedRide(o, dt, n++, "cert-b-noroute", self, null, fut, "Cert ride", "inherit_org", dflt, null, 0)
        seedRide(o, dt, n++, "cert-b-badroute", self, "cert-no-such-route", fut, "", "inherit_org", dflt, null, 0)
        seedRide(o, dt, n++, "cert-b-anchor", self, anchorRoute ?: route, fut, "Cert ride", "inherit_org", dflt, null, 1)
        seedRide(o, dt, n++, "cert-b-wpts", self, route, fut, "Cert ride", "inherit_org", dflt, null, 0)
        seedRide(o, dt, n++, "cert-b-thtext", self, route, fut, "Trailhead: the gate by the cattle guard", "inherit_org", dflt, null, 0)
        seedRide(o, dt, n++, "cert-b-past", self, route, past, "Cert ride", "inherit_org", dflt, null, 0)
        // case-4 rows: expires_at as a date, seconds, ms, empty, NULL, garbage; short and unreadable ride dates
        seedRide(o, dt, n++, "cert-x-date", self, route, "2025-12-02", "x", "inherit_org", dflt, "2026-01-01", 0)
        seedRide(o, dt, n++, "cert-x-datefut", self, route, "2098-12-02", "x", "inherit_org", dflt, "2099-01-01", 0)
        seedRide(o, dt, n++, "cert-x-sec", self, route, "2023-10-15", "x", "inherit_org", dflt, "1700000000", 0)
        seedRide(o, dt, n++, "cert-x-ms", self, route, "2023-10-15", "x", "inherit_org", dflt, "1700000000000", 0)
        seedRide(o, dt, n++, "cert-x-secfut", self, route, "2099-12-01", "x", "inherit_org", dflt, "4102444800", 0)
        seedRide(o, dt, n++, "cert-x-empty", self, route, past, "x", "inherit_org", dflt, "", 0)
        seedRide(o, dt, n++, "cert-x-null", self, route, "2026-1-5", "x", "inherit_org", dflt, null, 0)
        seedRide(o, dt, n++, "cert-x-garbage", self, route, "", "x", "inherit_org", dflt, "garbage", 0)
        seedRide(o, dt, n++, "cert-x-nodate", self, route, "soon", "x", "inherit_org", dflt, null, 0)
        seedRide(o, dt, n++, "cert-heal-short2", self, route, "2026-10-5", "x", "unique", null, null, 0)
        wps.forEachIndexed { i, wp ->
            o.line(c, "ride_waypoint:$i", safe { dt.execSQL("INSERT OR IGNORE INTO ride_waypoints (ride_id, waypoint_id, seq) VALUES (?,?,?)",
                arrayOf<Any?>("cert-b-wpts", wp, i)); "OK" })
        }
        // the heal's files (PORT_NOTES f4), next to the live copies
        val rn = if (routeName == null) "null" else org.json.JSONObject.quote(routeName)
        val mesh = """"mesh":{"channelName":"CertNet","key":"AQ==","region":"US","preset":"LONG_FAST","hopLimit":3,"txPower":27.0,"frequencySlot":0}"""
        val files = listOf(
            "cert_heal_short.json" to """{"kind":"grouptrack.ride","formatVersion":3,"ride":{"rideId":"cert-heal-short","name":"Cert short","date":"2026-10-5","startTime":"08:00","description":"d","zipCode":"84101","isPublic":"true","expiresAt":null,"createdAt":"2026-10-01T00:00:00Z"},"originator":{"name":"Cert Creator","userId":"cert-creator"},"network":{"id":"cert-net-1",$mesh},"rideData":{"route":{"name":$rn}}}""",
            "cert-heal-short2.json" to """{"kind":"grouptrack.ride","formatVersion":3,"ride":{"rideId":"cert-heal-short2","name":"Cert short 2","date":"2026-10-5","isPublic":true},"originator":{"name":"Cert Creator"},"network":{"id":"cert-net-2",$mesh},"rideData":{"route":null}}""",
            "cert_heal_norow.json" to """{"kind":"grouptrack.ride","formatVersion":3,"ride":{"rideId":"cert-heal-norow","name":"Cert no row","date":"$fut","isPublic":1},"originator":{"name":"Cert Creator","userId":""},"network":{$mesh},"rideData":{"route":{"name":"cert no such route"}}}""",
            "cert_heal_netnull.json" to """{"kind":"grouptrack.ride","ride":{"rideId":"cert-heal-netnull","name":null,"date":"$fut"},"network":{"id":"null","mesh":{"channelName":"CertNet","key":"AQ==","hopLimit":"5","txPower":30.7,"frequencySlot":12}}}""",
            "cert_heal_nonride.json" to """{"kind":"something.else","ride":{"rideId":"cert-heal-nonride"}}""",
            "cert_heal_unreadable.json" to """{ this is not json""",
            "cert_heal_noride.json" to """{"kind":"grouptrack.ride"}""",
            "cert_heal_blankid.json" to """{"kind":"grouptrack.ride","ride":{"rideId":"","date":"$fut"}}""",
            "cert_heal_dup_a.json" to """{"kind":"grouptrack.ride","ride":{"rideId":"cert-heal-dup","name":"Dup A","date":"$fut"},"network":{"id":"cert-net-a",$mesh}}""",
            "cert_heal_dup_b.json" to """{"kind":"grouptrack.ride","ride":{"rideId":"cert-heal-dup","name":"Dup B","date":"$fut"},"network":{"id":"cert-net-b",$mesh}}""",
            // STEP14C-B: the lenient (single-quoted) file moved to case 8, the last case -- a file only OLD can read shifted every later line
            "cert_heal_note.txt" to "not a ride file"
        )
        for ((fn, text) in files) o.line(c, "file:$fn", safe { File(d.rides, fn).writeText(text); "OK" })
        o.line(c, "file:cert_dir.json/", safe { File(d.rides, "cert_dir.json").mkdirs().toString() })
        o.line(c, "file:cert-b-own.jpg", safe { File(d.rides, "cert-b-own.jpg").writeText("cert picture"); "OK" })
        fingerprintAll(o, c, sp, dt, d)
        progress(c, s, "done")
        return Seed(self, myCfg, route, routeName, wps, fut, past, anchorRoute)
    }

    /** case 1: read-only, every ride and route; openRecentRides at moved dates (each rolled back). */
    private fun case1(s: Side, sp: SQLiteDatabase, dt: SQLiteDatabase, o: Out, realToday: LocalDate) {
        val c = "1-read"; progress(c, s, "start")
        RideCertHooks.reset(1); RideCertHooks.todayDate = realToday
        s.listRoutesDefault().forEachIndexed { i, r -> o.line(c, "listRoutes()#$i", r.toString()) }
        s.listRoutes(500).forEachIndexed { i, r -> o.line(c, "listRoutes(500)#$i", r.toString()) }
        val routeIds = ArrayList<String>()
        if (tableExists(sp, "routes")) sp.rawQuery("SELECT route_id FROM routes ORDER BY route_id", null).use { k ->
            while (k.moveToNext()) k.getString(0)?.let { routeIds.add(it) } }
        for (rid in routeIds) {
            val g = s.routeGeometry(rid)
            o.line(c, "routeGeometry:$rid", if (g == null) "null" else "len=${g.length} sha=${sha(g)}")
            val pts = s.parseWktLine(g ?: "")
            val md = MessageDigest.getInstance("SHA-256")
            for (p in pts) md.update((java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(p.first)) + "," +
                java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(p.second)) + ";").toByteArray(Charsets.UTF_8))
            o.line(c, "parseWktLine:$rid", "n=${pts.size} sha=${hex(md.digest()).take(32)}")
        }
        for (wkt in listOf("", "LINESTRING()", "LINESTRING(1 2, 3 4)", "LINESTRING (  -111.5 37.25 , x 1, 5, 6 7 8)", "POINT(1 2)", "no parens"))
            o.line(c, "parseWktLine:literal:$wkt", s.parseWktLine(wkt).toString())
        s.listRidesDefault().forEachIndexed { i, r -> o.line(c, "listRides()#$i", r.toString()) }
        s.listRides(5).forEachIndexed { i, r -> o.line(c, "listRides(5)#$i", r.toString()) }
        val ids = ArrayList<String>()
        dt.rawQuery("SELECT ride_id FROM rides ORDER BY ride_id", null).use { k -> while (k.moveToNext()) k.getString(0)?.let { ids.add(it) } }
        for (id in ids) {
            o.line(c, "rideForEdit:$id", safe { s.rideForEdit(id) })
            o.line(c, "isDistributed:$id", safe { s.isDistributed(id).toString() })
        }
        o.line(c, "rideForEdit:cert-no-such-ride", safe { s.rideForEdit("cert-no-such-ride") })
        o.line(c, "isDistributed:cert-no-such-ride", safe { s.isDistributed("cert-no-such-ride").toString() })
        o.line(c, "isOrganizer", safe { s.isOrganizer().toString() })
        o.line(c, "myNetworkName", safe { s.myNetworkName().toString() })
        // the days to ask openRecentRides on: the real date, 1970-01-01, 31 days past the newest ride, each ride's expiry day (40 at most)
        val days = java.util.TreeSet<LocalDate>()
        var newest: LocalDate? = null
        dt.rawQuery("SELECT ride_date, expires_at FROM rides ORDER BY ride_id", null).use { k ->
            while (k.moveToNext()) {
                val date = k.getString(0) ?: ""
                val rd = try { LocalDate.parse(date.take(10)) } catch (e: Exception) { null }
                val nw = newest
                if (rd != null && (nw == null || rd.isAfter(nw))) newest = rd
                val e = k.getString(1)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                val day = e?.toLongOrNull()?.let { n -> RideCertHooks.epochMsToLocalDate(if (n < 100_000_000_000L) n * 1000 else n) }
                    ?: e?.let { try { LocalDate.parse(it.take(10)) } catch (x: Exception) { null } }
                    ?: rd?.plusDays(30)
                if (day != null) days.add(day)
            }
        }
        val asks = ArrayList<Pair<String, LocalDate>>()
        asks.add(Pair("real", realToday))
        asks.add(Pair("1970", LocalDate.of(1970, 1, 1)))
        newest?.let { asks.add(Pair("newest+31", it.plusDays(31))) }
        days.take(40).forEach { asks.add(Pair("expiry", it)) }
        for ((why, day) in asks) {
            RideCertHooks.todayDate = day
            o.line(c, "openRecentRides@$why:$day", safe { rolledBack(dt) { s.openRecentRides() } })
        }
        RideCertHooks.todayDate = realToday
        val probes = listOf("", "garbage", "2026-1-5", "2026-02-30", "+12026-01-01", " 2026-12-01 ", "2026-12-01T08:00", "2026-13-01",
            realToday.toString(), realToday.minusDays(1).toString(), realToday.minusDays(30).toString(), realToday.minusDays(31).toString(),
            realToday.plusDays(1).toString(), "-1-1-1", "2026--1-01")
        for (p in probes) for (inc in listOf(false, true)) o.line(c, "isCurrent:'$p':$inc", safe { s.isCurrent(p, inc).toString() })
        progress(c, s, "done")
    }

    /** case 2: the package for every ride -- build (missing, the JSON, each top-level block, the Java types of network.mesh), save (the
     *  stored bytes), rideFile; each stored file is copied to export/ for case 6. */
    private fun case2(s: Side, d: Dirs, dt: SQLiteDatabase, o: Out, realToday: LocalDate) {
        val c = "2-package"; progress(c, s, "start")
        RideCertHooks.reset(2); RideCertHooks.todayDate = realToday
        val ids = ArrayList<String>()
        dt.rawQuery("SELECT ride_id FROM rides ORDER BY ride_id", null).use { k -> while (k.moveToNext()) k.getString(0)?.let { ids.add(it) } }
        ids.add("cert-no-such-ride")
        for (id in ids) {
            probe(o, c, "probe-before", id, dt)
            val b = try { s.build(id) } catch (e: Throwable) { o.line(c, "build:$id", "EXCEPTION $e"); null }
            probe(o, c, "probe-after", id, dt)
            if (b == null) o.line(c, "build:$id", "null")
            else {
                o.line(c, "build:$id:missing", "${b.missing} complete=${b.complete}")
                o.line(c, "build:$id:json", b.json.toString())
                val keys = b.json.keys().asSequence().toList()
                o.line(c, "build:$id:keys", keys.joinToString(","))
                for (k in keys) o.line(c, "build:$id:json.$k", b.json.opt(k).toString())
                // PORT_NOTES (a) row 2: values equal, but a Double holding an integral value may come back an Integer (informational)
                val mesh = b.json.optJSONObject("network")?.optJSONObject("mesh")
                val types = if (mesh == null) "no mesh"
                    else mesh.keys().asSequence().joinToString(",") { k -> k + "=" + (mesh.opt(k)?.javaClass?.simpleName ?: "null") }
                o.line(c, "build:$id:mesh-types (PORT_NOTES a2: may DIFF)", types)
            }
            o.line(c, "save:$id", safe { s.save(id) })
            val f = File(d.rides, "$id.json")
            o.line(c, "saved:$id", if (f.isFile) "len=${f.length()} sha=${shaFile(f)}" else "no file")
            if (f.isFile) f.copyTo(File(d.export, id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json"), overwrite = true)
            o.line(c, "rideFile:$id", safe { s.rideFile(id) })
        }
        val merges = listOf(
            Pair("<gpx><metadata>m</metadata><rte/></gpx>", "<wpt lat=\"1\" lon=\"2\"/>\n"),
            Pair("<gpx version=\"1.1\"><rte/></gpx>", "<wpt/>"),
            Pair("no gpx here", "<wpt/>"),
            Pair("<gpx", "<wpt/>"),
            Pair("<gpx><rte/></gpx>", "  \n"),
            Pair("", "<wpt/>"))
        merges.forEachIndexed { i, (g, wp) -> o.line(c, "mergeWpts#$i", safe { s.mergeWpts(g, wp) }) }
        o.line(c, "events", RideCertHooks.takeEvents())
        progress(c, s, "done")
    }

    /** STEP14C-E: what the recipe lookup sees for this ride, right before and after build (run _0928 / _1020: NEW built
     *  "broken ridge" with no recipe and no trailhead; the copy has one narrative row with an anchor). The same lines on both sides. */
    private fun probe(o: Out, c: String, tag: String, id: String, dt: SQLiteDatabase) {
        val rid = try { dt.rawQuery("SELECT route_id FROM rides WHERE ride_id=?", arrayOf(id)).use { k -> if (k.moveToFirst() && !k.isNull(0)) k.getString(0) else null } }
                  catch (e: Exception) { "EXC ${e.message}" }
        o.line(c, "$tag:$id:route", rid ?: "null")
        if (rid == null) return
        o.line(c, "$tag:$id:notes-in-copy", safe { dt.rawQuery("SELECT count(*), max(length(payload)) FROM route_notes WHERE route_id=? AND kind='narrative'", arrayOf(rid))
            .use { k -> k.moveToFirst(); "${k.getInt(0)} rows, ${k.getString(1)} chars" } })
        o.line(c, "$tag:$id:handle-is-copy", (SpatialDbManager.getExtensionDb() === dt).toString())
        o.line(c, "$tag:$id:readRouteNotes", safe { SpatialDbManager.readRouteNotes(rid)?.toString()?.let { "len=${it.length} sha=${sha(it)}" } ?: "null" })
        o.line(c, "$tag:$id:routeRecipe", safe { SpatialDbManager.routeRecipe(rid)?.toString()?.let { "len=${it.length} sha=${sha(it)}" } ?: "null" })
        o.line(c, "$tag:$id:envRecipe", safe { GtRideStore.env.spatial.routeRecipe(rid)?.let { "len=${it.length} sha=${sha(it)}" } ?: "null" })
    }

    /** case 3: the writes (network modes null and DEFAULT_CHANNEL only; the network create / deleteUnused are recorded). */
    private fun case3(s: Side, d: Dirs, sp: SQLiteDatabase, dt: SQLiteDatabase, o: Out, realToday: LocalDate, seed: Seed) {
        val c = "3-writes"; progress(c, s, "start")
        RideCertHooks.reset(3); RideCertHooks.todayDate = realToday
        val dflt = com.grouptrack.core.NetworkGenerator.DEFAULT_CHANNEL
        fun ev(k: String) { o.line(c, "$k:events", RideCertHooks.takeEvents()) }
        val r1 = try { s.saveRide("Cert Null", seed.fut, "08:00", "Cert desc", "84101", true, null, seed.route) } catch (e: Throwable) { o.line(c, "saveRide:null:exception", e.toString()); null }
        o.line(c, "saveRide:null", r1.toString()); ev("saveRide:null")
        val r2 = try { s.saveRide(" Cert Default ", seed.fut, " 09:00 ", " d ", " 84101 ", false, dflt, seed.route) } catch (e: Throwable) { o.line(c, "saveRide:default:exception", e.toString()); null }
        o.line(c, "saveRide:default", r2.toString()); ev("saveRide:default")
        // a forced INSERT failure (a TEMP trigger on the copy's connection)
        o.line(c, "trigger:create", safe { dt.execSQL("CREATE TEMP TRIGGER cert_fail_insert BEFORE INSERT ON rides BEGIN SELECT RAISE(ABORT, 'cert forced failure'); END"); "OK" })
        o.line(c, "saveRide:null:forced-failure", safe { s.saveRide("Cert Fail", seed.fut, "08:00", "x", "", true, null, seed.route).toString() }); ev("saveRide:null:forced-failure")
        o.line(c, "saveRide:default:forced-failure", safe { s.saveRide("Cert Fail", seed.fut, "08:00", "x", "", true, dflt, seed.route).toString() }); ev("saveRide:default:forced-failure")
        o.line(c, "trigger:drop", safe { dt.execSQL("DROP TRIGGER IF EXISTS temp.cert_fail_insert"); "OK" })
        val tgt = r2 ?: "cert-b-default"
        val other = r1 ?: "cert-b-nonet"
        o.line(c, "isDistributed:tgt", safe { s.isDistributed(tgt).toString() })
        o.line(c, "updateRide:in-progress", safe { s.updateRide(tgt, " Cert Edited ", seed.fut, "10:00", "Edited desc", "84102", true, seed.route).toString() })
        o.line(c, "setRideWaypoints:dups", safe { s.setRideWaypoints(tgt, listOf(seed.wps[0], seed.wps[1], seed.wps[0], seed.wps[2])).toString() })
        o.line(c, "setRideWaypoints:other", safe { s.setRideWaypoints(other, listOf(seed.wps[2])).toString() })
        o.line(c, "markDistributed:1", safe { s.markDistributed(tgt).toString() })
        o.line(c, "markDistributed:2", safe { s.markDistributed(tgt).toString() })
        o.line(c, "isDistributed:tgt-after", safe { s.isDistributed(tgt).toString() })
        o.line(c, "updateRide:distributed", safe { s.updateRide(tgt, "Refused", seed.fut, "11:00", "x", "x", false, seed.route).toString() })
        o.line(c, "setRideWaypoints:distributed", safe { s.setRideWaypoints(tgt, listOf(seed.wps[1])).toString() })
        for (dd in listOf("2026-1-5", "2026-02-30", "+12026-01-01", "", " 2026-12-24 "))
            o.line(c, "updateRideDate:'$dd'", safe { s.updateRideDate(tgt, dd).toString() })
        o.line(c, "updateRideDate:no-such-ride", safe { s.updateRideDate("cert-no-such-ride", "2026-12-01").toString() })
        o.line(c, "renameRoute:blank", safe { s.renameRoute(seed.route, "   ").toString() })
        o.line(c, "renameRoute:name", safe { s.renameRoute(seed.route, " Cert Renamed ").toString() })
        o.line(c, "renameRoute:no-such-route", safe { s.renameRoute("cert-no-such-route", "x").toString() })
        val choice = CertRide("cert-b-default", "Cert cert-b-default", seed.fut, "08:00", "Cert Organizer", seed.self, true)
        o.line(c, "checkIn:none", safe { s.checkIn(null, "  ", "rider") })
        o.line(c, "checkIn:ride:1", safe { s.checkIn(choice, " CERT1 ", "lead") })
        o.line(c, "checkIn:ride:2", safe { s.checkIn(choice, " CERT1 ", "lead") })
        o.line(c, "checkIn:ride:blank-callsign", safe { s.checkIn(choice, "", "sweep") })
        o.line(c, "saveSurvey:1", safe { s.saveSurvey("cert-track-1", tgt, 4, "moderate", true, "", true).toString() })
        o.line(c, "saveSurvey:2", safe { s.saveSurvey("cert-track-1", tgt, 5, "hard", false, "good ride", false).toString() })
        o.line(c, "deleteRide:shared", safe { s.deleteRide("cert-b-own", "cert shared network").toString() }); ev("deleteRide:shared")
        o.line(c, "deleteRide:last", safe { s.deleteRide("cert-b-own2", "cert last user").toString() }); ev("deleteRide:last")
        o.line(c, "deleteRide:default", safe { s.deleteRide("cert-b-noroute", "cert default channel").toString() }); ev("deleteRide:default")
        o.line(c, "deleteRide:no-such-ride", safe { s.deleteRide("cert-no-such-ride", "cert none").toString() }); ev("deleteRide:no-such-ride")
        o.line(c, "isOrganizer", safe { s.isOrganizer().toString() })
        o.line(c, "myNetworkName", safe { s.myNetworkName().toString() })
        fingerprintAll(o, c, sp, dt, d)
        progress(c, s, "done")
    }

    /** case 4: expireRides, then the heal twice (the second must change nothing), then the open rides. */
    private fun case4(s: Side, d: Dirs, dt: SQLiteDatabase, o: Out, realToday: LocalDate) {
        val c = "4-heal"; progress(c, s, "start")
        RideCertHooks.reset(4); RideCertHooks.todayDate = realToday
        o.line(c, "expireRides", safe { s.expireRides(); "OK" })
        fingerprint(o, "$c:after-expire", dt, "rides")
        o.line(c, "heal:1", safe { s.heal(); "OK" })
        o.line(c, "heal:1:events", RideCertHooks.takeEvents())
        val h1 = tableHash(dt, "rides")
        fingerprint(o, "$c:after-heal1", dt, "rides")
        hashFiles(o, "$c:after-heal1", d.rides, "rides")
        o.line(c, "heal:2", safe { s.heal(); "OK" })
        val h2 = tableHash(dt, "rides")
        o.line(c, "heal:2:rides-unchanged", (h1 == h2).toString())
        fingerprint(o, "$c:after-heal2", dt, "rides")
        hashFiles(o, "$c:after-heal2", d.rides, "rides")
        o.line(c, "openRecentRides", safe { s.openRecentRides() })
        o.flush()
        progress(c, s, "done")
    }

    /** case 5: loadAll (Apply = false, Send = true) over the same folder -- the list ORDER included. */
    private fun case5(s: Side, d: Dirs, sp: SQLiteDatabase, dt: SQLiteDatabase, o: Out, realToday: LocalDate) {
        val c = "5-loadAll"; progress(c, s, "start")
        RideCertHooks.reset(5); RideCertHooks.todayDate = realToday
        for (inc in listOf(false, true)) {
            val l = try { s.loadAll(inc) } catch (e: Throwable) { o.line(c, "loadAll($inc)", "EXCEPTION $e"); emptyList<RideForApply>() }
            o.line(c, "loadAll($inc):count", l.size.toString())
            l.forEachIndexed { i, r -> o.line(c, "loadAll($inc)#$i", r.toString()) }
        }
        fingerprintAll(o, "$c:final", sp, dt, d)
        progress(c, s, "done")
    }

    /** case 6: import -- the made-up inputs, then every package the OTHER side stored in case 2; then the heal and loadAll over the
     *  result. Message, recorded platform calls (in order) and the staging folder per import; the tables and both folders at the end. */
    private fun importCase(s: Side, d: Dirs, sp: SQLiteDatabase, dt: SQLiteDatabase, o: Out, otherExport: File, realToday: LocalDate) {
        val c = "6-import"; progress(c, s, "start")
        RideCertHooks.reset(6); RideCertHooks.todayDate = realToday
        // a stale staging folder: a file and a sub-folder holding a file (2.7e's delete leaves the non-empty folder)
        o.line(c, "stale-staging", safe { File(d.staging, "stale.gpx").writeText("old"); File(d.staging, "sub").mkdirs(); File(d.staging, "sub/inner.gpx").writeText("inner"); "OK" })
        val made = listOf(
            Triple("not-json", "this is not json", false),
            Triple("kind-mismatch", """{"kind":"grouptrack.route","ride":{"rideId":"cert-imp-kind","name":"Kind"}}""", false),
            Triple("no-ride-id", """{"kind":"grouptrack.ride","ride":{"name":"No id"}}""", false),
            Triple("ride-id-null", """{"kind":"grouptrack.ride","ride":{"rideId":null,"name":"Null id"}}""", false),
            Triple("no-gpx", """{"kind":"grouptrack.ride","ride":{"rideId":"cert-imp-nogpx","name":"No GPX"},"rideData":{"gpx":null}}""", false),
            Triple("sanitise", """{"kind":"grouptrack.ride","ride":{"rideId":"cert/imp a?b:c","name":"Fred's <Ride>/x"},"rideData":{"gpx":"<gpx><trk><name>t</name></trk></gpx>"}}""", true),
            Triple("blank-name", """{"kind":"grouptrack.ride","ride":{"rideId":"cert-imp-blank","name":"  "},"rideData":{"gpx":"<gpx/>"}}""", false),
            Triple("symbol-name", """{"kind":"grouptrack.ride","ride":{"rideId":"cert-imp-sym","name":"???"},"rideData":{"gpx":"<gpx/>"}}""", true))
            // STEP14C-B: the lenient import moved to case 8
        for ((k, content, bf) in made) {
            o.line(c, "made:$k:message", safe { s.importRide(content, bf) })
            o.line(c, "made:$k:events", RideCertHooks.takeEvents())
            hashFiles(o, "$c:made:$k", d.staging, "staging")
        }
        val pkgs = otherExport.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        o.line(c, "packages", pkgs.size.toString())
        for (f in pkgs) {
            val content = try { f.readText() } catch (e: Exception) { "" }
            o.line(c, "pkg:${f.name}:message", safe { s.importRide(content, false) })
            o.line(c, "pkg:${f.name}:events", RideCertHooks.takeEvents())
            hashFiles(o, "$c:pkg:${f.name}", d.staging, "staging")
            o.flush()
        }
        o.line(c, "heal", safe { s.heal(); "OK" })
        o.line(c, "heal:events", RideCertHooks.takeEvents())
        val l = try { s.loadAll(true) } catch (e: Throwable) { o.line(c, "loadAll(true)", "EXCEPTION $e"); emptyList<RideForApply>() }
        o.line(c, "loadAll(true):count", l.size.toString())
        l.forEachIndexed { i, r -> o.line(c, "loadAll(true)#$i", r.toString()) }
        fingerprintAll(o, "$c:final", sp, dt, d)
        progress(c, s, "done")
    }

    /** STEP14C-B case 8, the LAST case on each side: single-quoted (lenient) JSON. org.json (OLD) reads it, kotlinx (NEW) does not
     *  (PORT_NOTES a3) -- every line here is an EXPECTED DIFF, and nothing runs after it, so it cannot shift other lines. */
    private fun lenientCase(s: Side, d: Dirs, dt: SQLiteDatabase, o: Out, realToday: LocalDate) {
        val c = "8-lenient (PORT_NOTES a3: expected DIFF)"; progress("8-lenient", s, "start")
        RideCertHooks.reset(8); RideCertHooks.todayDate = realToday
        val fut = realToday.plusDays(10).toString()
        o.line(c, "file", safe { File(d.rides, "cert_heal_lenient.json").writeText("""{'kind':'grouptrack.ride','ride':{'rideId':'cert-heal-lenient','date':'$fut'}}"""); "OK" })
        o.line(c, "heal", safe { s.heal(); "OK" })
        o.line(c, "heal:events", RideCertHooks.takeEvents())
        fingerprint(o, "$c:after-heal", dt, "rides")
        o.line(c, "import:message", safe { s.importRide("""{'kind':'grouptrack.ride','ride':{'rideId':'cert-imp-lenient','name':'Lenient'}}""", false) })
        o.line(c, "import:events", RideCertHooks.takeEvents())
        hashFiles(o, "$c:after-import", d.rides, "rides")
        o.flush()
        progress("8-lenient", s, "done")
    }

    // =================================================================================================================================
    // case 7: GDate against java.time (side-free)
    // =================================================================================================================================

    private class K(val name: String) {
        var n = 0L; var d = 0L; val ex = ArrayList<String>()
        fun cmp(input: String, j: String, g: String) { n++; if (j != g) { d++; if (ex.size < 8) ex.add("$input: java=$j gdate=$g") } }
    }

    private fun jParse(t: String): String = try { LocalDate.parse(t).toString() } catch (e: Exception) { "ERR" }
    private fun jOf(y: Int, m: Int, d: Int): String = try { LocalDate.of(y, m, d).toString() } catch (e: Exception) { "ERR" }
    private fun jPlus(y: Int, m: Int, d: Int, n: Long): String = try { LocalDate.of(y, m, d).plusDays(n).toString() } catch (e: Exception) { "ERR" }
    private fun jMinus(y: Int, m: Int, d: Int, n: Long): String = try { LocalDate.of(y, m, d).minusDays(n).toString() } catch (e: Exception) { "ERR" }
    private fun jEpoch(y: Int, m: Int, d: Int): String = try { LocalDate.of(y, m, d).toEpochDay().toString() } catch (e: Exception) { "ERR" }
    private fun jOfEpoch(e: Long): String = try { LocalDate.ofEpochDay(e).toString() } catch (x: Exception) { "ERR" }
    private fun jBefore(a: String, b: String): String = try { LocalDate.parse(a).isBefore(LocalDate.parse(b)).toString() } catch (e: Exception) { "ERR" }

    private fun gdate(check: (key: String, a: String, b: String) -> Unit) {
        val t0 = System.currentTimeMillis()
        val rnd = java.util.Random(14L)
        val alph = "0123456789-+T: x\u0663\uFF11"
        val minE = LocalDate.MIN.toEpochDay(); val maxE = LocalDate.MAX.toEpochDay()
        val anchors = listOf(LocalDate.of(10000, 1, 1), LocalDate.of(0, 1, 1), LocalDate.of(-1, 12, 31), LocalDate.of(1000, 1, 1),
            LocalDate.of(-1000, 1, 1), LocalDate.of(2000, 2, 29), LocalDate.of(1970, 1, 1), LocalDate.of(9999, 12, 31)).map { it.toEpochDay() }
        val kEpoch = K("ofEpochDay"); val kOf = K("of(valid)"); val kOfR = K("of(random y m d)"); val kTo = K("toEpochDay")
        val kP30 = K("plusDays(30)"); val kM30 = K("minusDays(30)"); val kPN = K("plusDays(random n)"); val kParse = K("parse(random text)")
        val kBefore = K("isBefore"); val kEdge = K("edge years")
        fun digits(n: Int): String { val sb = StringBuilder(); repeat(n) { sb.append(('0' + rnd.nextInt(10))) }; return sb.toString() }
        fun d2(): String = String.format(Locale.US, "%02d", rnd.nextInt(40))
        fun randEpoch(): Long = when (rnd.nextInt(4)) {
            0 -> (minE + (rnd.nextDouble() * (maxE - minE).toDouble()).toLong()).coerceIn(minE, maxE)
            1 -> rnd.nextInt(2_000_000) - 1_000_000L
            2 -> (if (rnd.nextBoolean()) minE else maxE) + rnd.nextInt(81) - 40L
            else -> anchors[rnd.nextInt(anchors.size)] + rnd.nextInt(801) - 400L
        }
        // CODE RULE 1: ld null = the random epoch day was outside LocalDate's range (no date to start the text from).
        fun randText(ld: LocalDate?): String {
            val base = ld?.toString() ?: "2026-11-08"
            return when (rnd.nextInt(10)) {
                0, 1, 2 -> base
                3 -> if (ld == null) "2026-1-5" else "${ld.year}-${ld.monthValue}-${ld.dayOfMonth}"
                4 -> { val i = rnd.nextInt(base.length); base.substring(0, i) + alph[rnd.nextInt(alph.length)] + base.substring(i + 1) }
                5 -> { val i = rnd.nextInt(base.length); if (rnd.nextBoolean()) base.substring(0, i) + base.substring(i + 1)
                       else base.substring(0, i) + alph[rnd.nextInt(alph.length)] + base.substring(i) }
                6 -> (if (rnd.nextBoolean()) "+" else if (rnd.nextBoolean()) "-" else "") + digits(rnd.nextInt(12) + 1) + "-" + d2() + "-" + d2()
                7 -> { val sb = StringBuilder(); repeat(rnd.nextInt(15)) { sb.append(alph[rnd.nextInt(alph.length)]) }; sb.toString() }
                8 -> if (rnd.nextBoolean()) base + listOf("T00:00", " ", "Z", "-", "0")[rnd.nextInt(5)] else " " + base
                else -> String.format(Locale.US, "%04d-%02d-%02d", rnd.nextInt(10000), rnd.nextInt(14), rnd.nextInt(33))
            }
        }
        var prevText = "2026-11-08"
        for (i in 0 until 1_000_000) {
            val e = randEpoch()
            kEpoch.cmp("ofEpochDay($e)", jOfEpoch(e), GDateCert.ofEpochDay(e))
            val ld = if (e in minE..maxE) LocalDate.ofEpochDay(e) else null
            if (ld != null) {
                val y = ld.year; val m = ld.monthValue; val dd = ld.dayOfMonth
                kOf.cmp("of($y,$m,$dd)", jOf(y, m, dd), GDateCert.of(y, m, dd))
                kTo.cmp("toEpochDay($y,$m,$dd)", jEpoch(y, m, dd), GDateCert.toEpochDay(y, m, dd))
                kP30.cmp("plusDays($ld,30)", jPlus(y, m, dd, 30L), GDateCert.plusDays(y, m, dd, 30L))
                kM30.cmp("minusDays($ld,30)", jMinus(y, m, dd, 30L), GDateCert.minusDays(y, m, dd, 30L))
                val n = when (rnd.nextInt(4)) { 0 -> rnd.nextInt(200_001) - 100_000L; 1 -> rnd.nextLong(); 2 -> Long.MAX_VALUE; else -> Long.MIN_VALUE }
                kPN.cmp("plusDays($ld,$n)", jPlus(y, m, dd, n), GDateCert.plusDays(y, m, dd, n))
            }
            val ry = when (rnd.nextInt(3)) { 0 -> rnd.nextInt(); 1 -> rnd.nextInt(15001) - 3000; else -> listOf(-999_999_999, 999_999_999, -1_000_000_000, 1_000_000_000, 0, -1)[rnd.nextInt(6)] }
            val rm = rnd.nextInt(16) - 1; val rd = rnd.nextInt(35) - 1
            kOfR.cmp("of($ry,$rm,$rd)", jOf(ry, rm, rd), GDateCert.of(ry, rm, rd))
            val t = randText(ld)
            kParse.cmp("parse('$t')", jParse(t), GDateCert.parse(t))
            if (i % 10 == 0) { kBefore.cmp("isBefore('$t','$prevText')", jBefore(t, prevText), GDateCert.isBefore(t, prevText)); prevText = t }
            if (i % 100_000 == 99_999) Log.i(TAG, "GTCERT rides 7-gdate ${i + 1} cases, ${(System.currentTimeMillis() - t0) / 1000} s")
        }
        // the edge years (PORT_NOTES f7)
        val years = listOf(-999_999_999, 999_999_999, -1_000_000_000, 1_000_000_000, 0, -1, 1, 9999, 10000, 999, 1000, -999, -1000,
            -9999, -10000, 1970, 2000, 1900, 2100, 2400, 2026)
        val mds = listOf(Pair(1, 1), Pair(12, 31), Pair(2, 28), Pair(2, 29), Pair(2, 30), Pair(13, 1), Pair(0, 1), Pair(4, 31), Pair(6, 30))
        for (y in years) for ((m, dd) in mds) {
            kEdge.cmp("of($y,$m,$dd)", jOf(y, m, dd), GDateCert.of(y, m, dd))
            kEdge.cmp("toEpochDay($y,$m,$dd)", jEpoch(y, m, dd), GDateCert.toEpochDay(y, m, dd))
            for (n in listOf(30L, -30L, 1_000_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE, 0L)) {
                kEdge.cmp("plusDays($y-$m-$dd,$n)", jPlus(y, m, dd, n), GDateCert.plusDays(y, m, dd, n))
                kEdge.cmp("minusDays($y-$m-$dd,$n)", jMinus(y, m, dd, n), GDateCert.minusDays(y, m, dd, n))
            }
            val txt = jOf(y, m, dd)
            if (txt != "ERR") kEdge.cmp("parse('$txt')", jParse(txt), GDateCert.parse(txt))
        }
        for (y in years) for (t in listOf("+$y-01-01", "$y-01-01", String.format(Locale.US, "%04d-01-01", y), String.format(Locale.US, "+%05d-01-01", y),
                String.format(Locale.US, "-%04d-01-01", y), "-0000-01-01", "+0000-01-01", "0000-01-01"))
            kEdge.cmp("parse('$t')", jParse(t), GDateCert.parse(t))
        for (e in listOf(minE, maxE, minE - 1, maxE + 1, Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L))
            kEdge.cmp("ofEpochDay($e)", jOfEpoch(e), GDateCert.ofEpochDay(e))
        for (k in listOf(kEpoch, kOf, kOfR, kTo, kP30, kM30, kPN, kParse, kBefore, kEdge)) {
            val key = "7-gdate|${k.name} (${k.n} cases)"
            if (k.d == 0L) check(key, "all equal", "all equal")
            else check(key, "${k.d} of ${k.n} differ: " + k.ex.joinToString(" ; "), "(java.time is the OLD side)")
        }
        Log.i(TAG, "GTCERT rides 7-gdate done in ${(System.currentTimeMillis() - t0) / 1000} s")
    }
}
