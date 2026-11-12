package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyUserCertOld
import com.geeksville.mesh.convoy.ConvoyUserType
import com.geeksville.mesh.convoy.UserCertOldEnv
import com.geeksville.mesh.convoy.UserStoreCertOld
import com.grouptrack.shared.cert.Cert17c2
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.time.LocalDate

/**
 * STEP17G2C2-2026-11-12 (2.8 step 17, G2c-2) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the V2 local
 * user, OLD (2.7e, the generated UserStoreCertOld) = NEW (shared GtUserStore on GtSettingsAndroid) on the same cases (Cert17c2, one
 * copy for every side). Each side has its OWN folder in cacheDir and its OWN prefs file (gtcert17c2_old / gtcert17c2_new, emptied and
 * removed after). The rider's convoy_users.json and convoy_prefs are only READ, and the file is handed in as one more case.
 * Request mode "user17c2". Lines: U17C <tab> key <tab> SAME|DIFF|INPUT <tab> value; footer "# U17C done: N SAME, N DIFF in S s".
 */
object GtCert17c2 {
    private const val TAG = "CertRun"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private class OldSide(private val app: Context, private val dir: File) : Cert17c2.Side {
        private val file = File(dir, "convoy_users.json")
        private val prefs = app.getSharedPreferences("gtcert17c2_old", Context.MODE_PRIVATE)
        private var created: ConvoyUserCertOld? = null
        private fun u(g: ConvoyUserCertOld) = Cert17c2.U(g.userId, g.userType.name, g.firstName, g.lastName, g.email, g.cellPhone, g.vehicleType,
            g.createdDate, g.expirationDate, g.knownDevices.toList(), g.fullName)
        override fun reset(fileText: String?) {
            dir.deleteRecursively(); dir.mkdirs(); if (fileText != null) file.writeText(fileText)
            prefs.edit().clear().commit(); created = null; UserCertOldEnv.certDir = dir
        }
        override fun fileText(): String? = if (file.exists()) file.readText() else null
        override fun prefActive(): String? = prefs.getString("active_user_id", null)
        override fun loadAll() = UserStoreCertOld.loadAll(app).map { u(it) }
        override fun saveAllAsLoaded() = UserStoreCertOld.saveAll(app, UserStoreCertOld.loadAll(app))
        override fun saveUpdated(id: String, vehicle: String) {
            UserStoreCertOld.loadAll(app).firstOrNull { it.userId == id }?.let { UserStoreCertOld.save(app, it.copy(vehicleType = vehicle)) }
        }
        override fun create(type: String, first: String, last: String, email: String, cell: String, vehicle: String, today: String, newId: String): Cert17c2.U {
            UserCertOldEnv.certToday = LocalDate.parse(today); UserCertOldEnv.certIds.clear(); UserCertOldEnv.certIds.add(newId)
            return ConvoyUserCertOld.create(ConvoyUserType.valueOf(type), first, last, email, cell, vehicle).also { created = it }.let { u(it) }
        }
        override fun saveCreated() { created?.let { UserStoreCertOld.save(app, it) } }
        override fun getActive() = UserStoreCertOld.getActiveUser(app)?.let { u(it) }
        override fun setActive(id: String) = UserStoreCertOld.setActiveUser(app, id)
        override fun isEnrolled() = UserStoreCertOld.isEnrolled(app)
        override fun addDevice(hw: String) = UserStoreCertOld.addDeviceToActiveUser(app, hw)
        override fun resolvePairing(hw: String) = UserStoreCertOld.resolvePairing(app, hw)?.let { u(it) }
        override fun isExpired(expiry: String, today: String): String = try {
            UserCertOldEnv.certToday = LocalDate.parse(today)
            "${ConvoyUserCertOld("x", ConvoyUserType.RIDER, "", "", "", "", "", "", expiry).isExpired}"
        } catch (e: Exception) { "EXC" }
        override fun cleanup() { dir.deleteRecursively(); prefs.edit().clear().commit(); app.deleteSharedPreferences("gtcert17c2_old"); UserCertOldEnv.certDir = null }
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0
        w.write("# U17C droid ${android.os.Build.MODEL}: the V2 local user (ConvoyUser / ConvoyUserStore), OLD = NEW on ${Cert17c2.CASES.size} cases + the rider's own file\n")
        val own = File(app.filesDir, "convoy_users.json")
        val ownText = if (own.isFile) own.readText() else null   // READ ONLY
        val ownActive = app.getSharedPreferences("convoy_prefs", Context.MODE_PRIVATE).getString("active_user_id", null)   // READ ONLY
        w.write("U17C\tinput|riderOwn\tINPUT\t${ownText?.let { esc(it) } ?: "NOFILE"}\n")
        w.write("U17C\tinput|riderActive\tINPUT\t${if (ownActive == null) "null" else "set"}\n")
        val oldMap = LinkedHashMap<String, String>(); val newMap = LinkedHashMap<String, String>()
        val work = File(app.cacheDir, "gtcert17c2"); work.deleteRecursively()
        try {
            Cert17c2.run(OldSide(app, File(work, "old")), ownText) { k, v -> oldMap[k] = v }
            val np = GtSettingsAndroid(app).store("gtcert17c2_new")
            Cert17c2.run(Cert17c2.NewSide(FileSystem.SYSTEM, File(work, "new").toOkioPath(), np), ownText) { k, v -> newMap[k] = v }
            app.deleteSharedPreferences("gtcert17c2_new")
            for (k in (oldMap.keys + newMap.keys)) {
                val o = oldMap[k]; val n = newMap[k]
                if (o == n) { same++; w.write("U17C\t$k\tSAME\t${esc(n ?: "")}\n") }
                else { diff++; w.write("U17C\t$k\tDIFF\n  OLD\t${o?.let { esc(it) } ?: "(missing)"}\n  NEW\t${n?.let { esc(it) } ?: "(missing)"}\n") }
            }
        } catch (e: Throwable) {
            diff++; w.write("# U17C: ABORTED $e\n"); Log.e(TAG, "GTCERT user17c2 aborted", e)
        } finally { work.deleteRecursively() }
        val stillOwn = if (own.isFile) own.readText() else null
        if (stillOwn != ownText) { diff++; w.write("# U17C: ⚠ the rider's convoy_users.json CHANGED during the cert\n") }
        w.write("# U17C done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT user17c2: $same SAME, $diff DIFF")
    }
}
