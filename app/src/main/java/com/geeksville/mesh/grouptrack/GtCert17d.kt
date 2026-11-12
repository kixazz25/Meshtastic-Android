package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyUser
import com.geeksville.mesh.convoy.ConvoyUserType
import com.geeksville.mesh.convoy.EventCertOldEnv
import com.geeksville.mesh.convoy.EventConfigCertOld
import com.geeksville.mesh.convoy.EventStoreCertOld
import com.geeksville.mesh.convoy.MasterCertOldEnv
import com.geeksville.mesh.convoy.MasterConfigCertOld
import com.grouptrack.shared.cert.Cert17d
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * STEP17G2D-2026-11-12 (2.8 step 17, G2d) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the config files,
 * OLD (2.7e, the generated MasterConfigCertOld / EventConfigCertOld) = NEW (shared GtMasterConfigStore / GtEventStore) on one script
 * (Cert17d). Each side works in its OWN folder in cacheDir; the asset is the app's real master_config.json (read). The rider's own
 * filesDir/master_config.json and filesDir/convoy_events/ are only READ and handed in as more cases -- checked unchanged after.
 * Request mode "config17d". Lines: D17C <tab> key <tab> SAME|DIFF|INPUT <tab> value; footer "# D17C done: N SAME, N DIFF in S s".
 */
object GtCert17d {
    private const val TAG = "CertRun"
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private val ORG_OLD = Cert17d.ORG.let { ConvoyUser(it.userId, ConvoyUserType.valueOf(it.userType.name), it.firstName, it.lastName, it.email, it.cellPhone, it.vehicleType, it.createdDate, it.expirationDate) }

    private class OldSide(private val app: Context, private val dir: File) : Cert17d.Side {
        private val files = File(dir, "files"); private val devFile = File(files, "master_config.json"); private val evDir = File(files, "convoy_events")
        private var created: EventConfigCertOld? = null
        override fun reset() { dir.deleteRecursively(); files.mkdirs(); created = null; MasterCertOldEnv.certDir = files; EventCertOldEnv.certDir = files }
        override fun putDevice(text: String?) { if (text != null) devFile.writeText(text) }
        override fun setAsset(text: String?) { MasterCertOldEnv.certAsset = text }
        override fun deviceText(): String? = if (devFile.exists()) devFile.readText() else null
        override fun masterLoad(): String = MasterConfigCertOld.load(app)?.toJson()?.toString() ?: "null"
        override fun masterExists(): String = "${MasterConfigCertOld.exists(app)}"
        override fun masterPretty(): String = MasterConfigCertOld.load(app)?.toJson()?.toString(2) ?: "null"
        override fun eventParse(text: String): String = try { EventConfigCertOld.fromJson(JSONObject(text)).toJson().toString(2) } catch (e: Exception) { "EXC" }
        override fun eventPut(name: String, text: String) {
            evDir.mkdirs(); if (name.startsWith("dir:")) File(evDir, name.removePrefix("dir:")).mkdirs() else File(evDir, name).writeText(text)
        }
        override fun eventCreate(eventName: String, eventDate: String, eventDescription: String, channelName: String, today: String, newId: String): String {
            EventCertOldEnv.certToday = LocalDate.parse(today); EventCertOldEnv.certIds.clear(); EventCertOldEnv.certIds.add(newId)
            val m = MasterConfigCertOld.fromJson(JSONObject(MasterCertOldEnv.certAsset!!))
            val e = EventConfigCertOld.createFromMaster(m, ORG_OLD, "!a1b2c3d4", "dev-1", eventName, eventDate, eventDescription, channelName, "PSK==")
            created = e; return e.toJson().toString()
        }
        override fun eventSaveCreated(): String {
            val e = created!!; val before = (evDir.list() ?: emptyArray()).toSet()
            EventStoreCertOld.save(app, e)
            return ((evDir.list() ?: emptyArray()).toSet() - before).singleOrNull() ?: "(no new file)"
        }
        override fun eventFiles(): String = if (!evDir.exists()) "NODIR" else (evDir.listFiles() ?: emptyArray()).sortedBy { it.name }.joinToString("\n") { f ->
            if (f.isDirectory) "${f.name}/" else "${f.name}=" + f.readText() }
        override fun eventLoadAll(): String = EventStoreCertOld.loadAll(app).map { it.toJson().toString() }.sorted().joinToString("\n")
        override fun cleanup() { dir.deleteRecursively(); MasterCertOldEnv.certDir = null; MasterCertOldEnv.certAsset = null; EventCertOldEnv.certDir = null }
    }

    private fun snapshot(app: Context): String {
        val m = File(app.filesDir, "master_config.json"); val ev = File(app.filesDir, "convoy_events")
        return (if (m.isFile) "m=" + m.readText() else "m=NOFILE") + "\u001e" +
            ((ev.listFiles() ?: emptyArray()).sortedBy { it.name }.joinToString("\u001e") { if (it.isFile) it.name + "=" + it.readText() else it.name + "/" })
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0
        w.write("# D17C droid ${android.os.Build.MODEL}: the config files (ConvoyMasterConfig, ConvoyEventConfig / ConvoyEventStore), OLD = NEW on one script\n")
        val asset = app.assets.open("master_config.json").bufferedReader().use { it.readText() }   // READ
        val before = snapshot(app)                                                                // the rider's files, READ ONLY
        val mf = File(app.filesDir, "master_config.json")
        val riderMaster = if (mf.isFile) mf.readText() else null
        val riderEvents = (File(app.filesDir, "convoy_events").listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .sortedBy { it.name }.map { it.name to it.readText() }
        w.write("D17C\tinput|asset\tINPUT\t${esc(asset)}\n")
        w.write("D17C\tinput|riderMaster\tINPUT\t${riderMaster?.let { esc(it) } ?: "NOFILE"}\n")
        w.write("D17C\tinput|riderEvents\tINPUT\t${riderEvents.size}\n")
        riderEvents.forEachIndexed { i, (n, t) -> w.write("D17C\tinput|riderEvent|$i\tINPUT\t${esc(n)}\t${esc(t)}\n") }
        val oldMap = LinkedHashMap<String, String>(); val newMap = LinkedHashMap<String, String>()
        val work = File(app.cacheDir, "gtcert17d"); work.deleteRecursively()
        try {
            Cert17d.run(OldSide(app, File(work, "old")), asset, riderMaster, riderEvents) { k, v -> oldMap[k] = v }
            val lister: (okio.Path) -> List<okio.Path>? = { p -> File(p.toString()).listFiles()?.map { it.toOkioPath() } }   // 2.7e's own order
            Cert17d.run(Cert17d.NewSide(FileSystem.SYSTEM, File(work, "new").toOkioPath(), lister), asset, riderMaster, riderEvents) { k, v -> newMap[k] = v }
            for (k in (oldMap.keys + newMap.keys)) {
                val o = oldMap[k]; val n = newMap[k]
                if (o == n) { same++; w.write("D17C\t$k\tSAME\t${esc(n ?: "")}\n") }
                else { diff++; w.write("D17C\t$k\tDIFF\n  OLD\t${o?.let { esc(it) } ?: "(missing)"}\n  NEW\t${n?.let { esc(it) } ?: "(missing)"}\n") }
            }
        } catch (e: Throwable) {
            diff++; w.write("# D17C: ABORTED $e\n"); Log.e(TAG, "GTCERT config17d aborted", e)
        } finally { work.deleteRecursively() }
        if (snapshot(app) != before) { diff++; w.write("# D17C: ⚠ the rider's master_config.json or convoy_events CHANGED during the cert\n") }
        w.write("# D17C done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT config17d: $same SAME, $diff DIFF")
    }
}
