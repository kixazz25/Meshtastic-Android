package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.NetworkStoreCertOld
import com.geeksville.mesh.convoy.ProfileStoreCertOld
import com.geeksville.mesh.convoy.RiderProfile
import com.grouptrack.core.NetworkConfig
import com.grouptrack.core.NetworkGenerator
import com.grouptrack.core.NetworkGeneratorCertOld
import com.grouptrack.core.OwnerType
import com.grouptrack.shared.net.GtNetworkConfig
import com.grouptrack.shared.net.GtNetworkGenerator
import com.grouptrack.shared.net.GtNetworkStore
import com.grouptrack.shared.net.GtOwnerType
import com.grouptrack.shared.net.GtProfileStore
import com.grouptrack.shared.net.GtRiderProfile
import java.io.File
import java.security.MessageDigest

/**
 * STEP17G2C1-2026-11-12 (2.8 step 17, G2c-1) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the rider profile,
 * the networks and the generator, OLD (2.7e, the generated *CertOld copies) = NEW (shared) on two COPIES of the pushed data database
 * (cert15c_input/grouptrack_data.db -- the Mac's live_2026-11-08 copy; the rider's own database is never opened). Request mode "net17c".
 * The clock, the new ids and the random seed are the same on both sides (Random(17)); the mesh values are the app's own.
 * Steps: reads · profile update · refusal · profile create · org / leader / ride networks · refusal · deleteUnused -- after each, the rows.
 * Lines: N17C <tab> key <tab> SAME|DIFF|INPUT <tab> value; footer "# N17C done: N SAME, N DIFF in S s".
 */
object GtCert17c {
    private const val TAG = "CertRun"
    const val NOW = "2026-11-12T12:00:00Z"
    fun prof(p: RiderProfile?): String = p?.let { listOf(it.userId, it.firstName, it.lastName, it.callsign, it.email, it.cell, it.defaultRole, it.vehicleType, it.team).joinToString("|") } ?: "null"
    fun prof(p: GtRiderProfile?): String = p?.let { listOf(it.userId, it.firstName, it.lastName, it.callsign, it.email, it.cell, it.defaultRole, it.vehicleType, it.team).joinToString("|") } ?: "null"
    fun cfg(c: NetworkConfig?): String = c?.let { listOf(it.configId, it.ownerType.db, it.ownerId, it.displayName, it.baseVersion, it.region, it.modemPreset, it.hopLimit, it.txPower, it.frequencySlot, it.psk, it.role, it.networkSsid, it.networkPassword).joinToString("|") } ?: "null"
    fun cfg(c: GtNetworkConfig?): String = c?.let { listOf(it.configId, it.ownerType.db, it.ownerId, it.displayName, it.baseVersion, it.region, it.modemPreset, it.hopLimit, it.txPower, it.frequencySlot, it.psk, it.role, it.networkSsid, it.networkPassword).joinToString("|") } ?: "null"
    private fun dump(db: SQLiteDatabase, table: String, order: String): String = db.rawQuery("SELECT * FROM $table ORDER BY $order", null).use { c ->
        val rows = ArrayList<String>()
        while (c.moveToNext()) rows.add((0 until c.columnCount).joinToString("\u001f") { if (c.isNull(it)) "NULL" else c.getString(it) })
        rows.joinToString("\u001e")
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        val inD = File(GroupTrackStorage.dir("cert15c_input", app), "grouptrack_data.db")
        if (!inD.isFile) { w.write("# N17C: STOP -- no input (${inD.path}; gt_cert_v28 pushes it). Not run.\n"); return }
        var same = 0; var diff = 0
        fun check(k: String, old: String, new: String) {
            if (old == new) { same++; w.write("N17C\t$k\tSAME\t$new\n") } else { diff++; w.write("N17C\t$k\tDIFF\n  OLD\t$old\n  NEW\t$new\n") }
        }
        val md = MessageDigest.getInstance("SHA-256")
        inD.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        w.write("# N17C droid ${android.os.Build.MODEL}: profile + networks + generator on two copies of ${inD.path}\n")
        w.write("N17C\tinput|db\tINPUT\tlen=${inD.length()};sha256=$sha\n")
        NetworkGenerator.MeshDefaults.loadFrom(app)
        val mesh = GtNetAndroid.mesh()
        NetworkGeneratorCertOld.MeshDefaults.let { it.REGION = mesh.region; it.MODEM_PRESET = mesh.modemPreset; it.HOP_LIMIT = mesh.hopLimit; it.TX_POWER = mesh.txPower; it.FREQUENCY_SLOT = mesh.frequencySlot; it.ROLE = mesh.role }
        w.write("N17C\tinput|mesh\tINPUT\t${mesh.region}|${mesh.modemPreset}|${mesh.hopLimit}|${mesh.txPower}|${mesh.frequencySlot}|${mesh.role}\n")
        val work = File(app.cacheDir, "gtcert17c"); work.deleteRecursively(); work.mkdirs()
        val fa = File(work, "old.db"); val fb = File(work, "new.db"); inD.copyTo(fa, true); inD.copyTo(fb, true)
        val a = SQLiteDatabase.openDatabase(fa.path, null, SQLiteDatabase.OPEN_READWRITE)
        val b = SQLiteDatabase.openDatabase(fb.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val cb = GtAppDb.wrap(b)
            ProfileStoreCertOld.certDb = a; ProfileStoreCertOld.certNow = NOW; ProfileStoreCertOld.certIds.clear(); ProfileStoreCertOld.certIds.add("cert-user-1")
            NetworkStoreCertOld.certDb = a; NetworkStoreCertOld.certNow = NOW; NetworkGeneratorCertOld.rng = java.util.Random(17)
            val ids = ArrayDeque(listOf("cert-user-1"))
            val np = GtProfileStore({ cb }, { NOW }, { ids.removeFirst() })
            val nn = GtNetworkStore({ cb }, GtNetworkGenerator(GtNetAndroid.JavaRng(java.util.Random(17))), { mesh }, { NOW })
            val op = ProfileStoreCertOld; val on = NetworkStoreCertOld
            // 1. reads
            check("read|profile.exists", "${op.exists()}", "${np.exists()}")
            val lo = op.load(); val ln = np.load()
            check("read|profile.load", prof(lo), prof(ln))
            val myCfg = a.rawQuery("SELECT config_id FROM users WHERE is_self = 1", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else "" }
            check("read|net.load(own)", cfg(on.load(myCfg)), cfg(nn.load(myCfg)))
            check("read|net.listByOwner(leader,self)", on.listByOwner(OwnerType.LEADER, lo?.userId ?: "").joinToString(";") { cfg(it) }, nn.listByOwner(GtOwnerType.LEADER, ln?.userId ?: "").joinToString(";") { cfg(it) })
            check("read|net.exists(own)", "${on.exists(myCfg)}", "${nn.exists(myCfg)}")
            check("read|net.exists(GroupZZZZZZ)", "${on.exists("GroupZZZZZZ")}", "${nn.exists("GroupZZZZZZ")}")
            // 1b. deleteUnused on the rider's own network: in use, so it stays
            on.deleteUnused(myCfg); nn.deleteUnused(myCfg)
            check("net.deleteUnused(own, in use)|rows", dump(a, "network_configs", "config_id"), dump(b, "network_configs", "config_id"))
            // 2. profile update
            val po = (lo ?: RiderProfile()).copy(callsign = "Cert Rider", team = "Blue", cell = "555-0100", email = (lo?.email ?: "").ifBlank { "cert@example.com" })
            val pn = GtRiderProfile(po.userId, po.firstName, po.lastName, po.callsign, po.email, po.cell, po.defaultRole, po.vehicleType, po.team)
            check("profile.update|id", "${op.save(po)}", "${np.save(pn)}")
            check("profile.update|users", dump(a, "users", "user_id"), dump(b, "users", "user_id"))
            // 3. refusal
            check("profile.refused", "${op.save(po.copy(email = ""))}", "${np.save(pn.copy(email = ""))}")
            // 4. create (no own row)
            a.execSQL("DELETE FROM users WHERE is_self = 1"); b.execSQL("DELETE FROM users WHERE is_self = 1")
            val nw = RiderProfile(firstName = "Cert", lastName = "Rider", callsign = "CertR", email = "cert@example.com", cell = "555-0101", vehicleType = "UTV", team = "Red")
            check("profile.create|id", "${op.save(nw)}", "${np.save(GtRiderProfile("", "Cert", "Rider", "CertR", "cert@example.com", "555-0101", "rider", "UTV", "Red"))}")
            check("profile.create|users", dump(a, "users", "user_id"), dump(b, "users", "user_id"))
            check("profile.create|load", prof(op.load()), prof(np.load()))
            // 5. networks
            check("net.create|org", cfg(on.create(OwnerType.ORG, "org-cert-1", "Kanab UTV Club")), cfg(nn.create(GtOwnerType.ORG, "org-cert-1", "Kanab UTV Club")))
            check("net.create|leader", cfg(on.create(OwnerType.LEADER, "cert-user-1", "Cert Rider")), cfg(nn.create(GtOwnerType.LEADER, "cert-user-1", "Cert Rider")))
            check("net.create|ride", cfg(on.create(OwnerType.RIDE, "pending-cert", "Cert Rider")), cfg(nn.create(GtOwnerType.RIDE, "pending-cert", "Cert Rider")))
            check("net.create|refused", cfg(on.create(OwnerType.RIDE, "", "x")), cfg(nn.create(GtOwnerType.RIDE, "", "x")))
            check("net.create|rows", dump(a, "network_configs", "config_id"), dump(b, "network_configs", "config_id"))
            // 6. deleteUnused: every config -- the own row is gone and the rides use GroupTrack, so none is in use now
            val all = a.rawQuery("SELECT config_id FROM network_configs ORDER BY config_id", null).use { c -> val l = ArrayList<String>(); while (c.moveToNext()) l.add(c.getString(0)); l }
            for (id in all) { on.deleteUnused(id); nn.deleteUnused(id) }
            check("net.deleteUnused|rows", dump(a, "network_configs", "config_id"), dump(b, "network_configs", "config_id"))
        } catch (e: Throwable) {
            diff++; w.write("# N17C: ABORTED $e\n"); Log.e(TAG, "GTCERT net17c aborted", e)
        } finally {
            ProfileStoreCertOld.certDb = null; NetworkStoreCertOld.certDb = null
            a.close(); b.close(); work.deleteRecursively()
        }
        w.write("# N17C done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT net17c: $same SAME, $diff DIFF")
    }
}
