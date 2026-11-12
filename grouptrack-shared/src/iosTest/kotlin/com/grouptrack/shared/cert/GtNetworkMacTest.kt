package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.net.GtMeshDefaults
import com.grouptrack.shared.net.GtNetworkConfig
import com.grouptrack.shared.net.GtNetworkGenerator
import com.grouptrack.shared.net.GtNetworkStore
import com.grouptrack.shared.net.GtOwnerType
import com.grouptrack.shared.net.GtProfileStore
import com.grouptrack.shared.net.GtRandom
import com.grouptrack.shared.net.GtRiderProfile
import com.grouptrack.shared.spatial.GtRead
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEP17G2C1-2026-11-12 ⚠ TEST-ONLY: the rider profile, the networks and the generator on the iPad simulator, VALUE FOR VALUE with the
 * Droid's NEW (GtCert17c, report gt17n_droid.txt): a COPY of the Mac's live_2026-11-08/grouptrack_data.db (the same file the Droid's
 * cert15c_input holds -- the sha256 must match the Droid's), the Droid's mesh values, the same clock, ids and seed, the same steps.
 * java.util.Random(17) is ported below (the 48-bit generator Java documents), so the iPad draws the same channel ids, keys and passwords.
 */
class GtNetworkMacTest {
    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private val inDb = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/live_2026-11-08/grouptrack_data.db"
    private val now = "2026-11-12T12:00:00Z"

    /** java.util.Random, as the JDK documents it: seed scrambled, 48-bit linear congruential, nextInt(bound) and nextBytes. */
    private class JRandom(seed: Long) : GtRandom {
        private var s = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)
        private fun next(bits: Int): Int { s = (s * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1); return (s ushr (48 - bits)).toInt() }
        override fun nextInt(bound: Int): Int {
            require(bound > 0)
            var r = next(31); val m = bound - 1
            if ((bound and m) == 0) return ((bound.toLong() * r.toLong()) shr 31).toInt()
            var u = r
            while (true) { r = u % bound; if (u - r + m >= 0) break; u = next(31) }
            return r
        }
        override fun nextBytes(b: ByteArray) {
            var i = 0
            while (i < b.size) { var rnd = next(32); var n = minOf(b.size - i, 4); while (n-- > 0) { b[i++] = rnd.toByte(); rnd = rnd shr 8 } }
        }
    }
    private fun prof(p: GtRiderProfile?): String = p?.let { listOf(it.userId, it.firstName, it.lastName, it.callsign, it.email, it.cell, it.defaultRole, it.vehicleType, it.team).joinToString("|") } ?: "null"
    private fun cfg(c: GtNetworkConfig?): String = c?.let { listOf(it.configId, it.ownerType.db, it.ownerId, it.displayName, it.baseVersion, it.region, it.modemPreset, it.hopLimit, it.txPower, it.frequencySlot, it.psk, it.role, it.networkSsid, it.networkPassword).joinToString("|") } ?: "null"
    private fun dump(c: SQLiteConnection, table: String, order: String): String {
        val st = c.prepare("SELECT * FROM $table ORDER BY $order")
        try {
            val rows = ArrayList<String>()
            while (st.step()) rows.add((0 until st.getColumnCount()).joinToString("\u001f") { GtRead.cellText(st, it) ?: "NULL" })
            return rows.joinToString("\u001e")
        } finally { st.close() }
    }
    private fun col0(c: SQLiteConnection, sql: String): List<String> {
        val st = c.prepare(sql)
        try { val l = ArrayList<String>(); while (st.step()) l.add(GtRead.cellText(st, 0) ?: ""); return l } finally { st.close() }
    }

    @Test
    fun networks() {
        val fs = FileSystem.SYSTEM; val rp = "$outDir/gt17n_droid.txt".toPath()
        if (!fs.exists(rp)) { println("GTN17C mac: no Droid report ($rp)"); assertTrue(false, "no Droid report"); return }
        val droid = LinkedHashMap<String, String>(); var dbIn = ""; var meshIn = ""
        fs.read(rp) { while (true) { val l = readUtf8Line() ?: break
            if (!l.startsWith("N17C\t")) continue
            val p = l.split('\t', limit = 4); if (p.size < 4) continue
            when { p[1] == "input|db" -> dbIn = p[3]; p[1] == "input|mesh" -> meshIn = p[3]; p[2] == "SAME" -> droid[p[1]] = p[3] } } }
        val src = inDb.toPath()
        assertTrue(fs.exists(src), "no $inDb")
        val len = fs.metadata(src).size ?: -1L
        val sha = fs.read(src) { readByteString() }.sha256().hex()
        val myIn = "len=$len;sha256=$sha"
        if (myIn != dbIn) { println("GTN17C mac: NOT THE SAME INPUT droid=$dbIn mac=$myIn"); assertEquals(dbIn, myIn, "not the same input database"); return }
        val m = meshIn.split('|'); assertEquals(6, m.size, "no mesh values in the Droid report")
        val mesh = GtMeshDefaults(m[0], m[1], m[2].toInt(), m[3].toInt(), m[4].toInt(), m[5])
        // ---- a fresh COPY (the original is only read, by the copy) ----
        val work = "$outDir/mac17n_work".toPath()
        if (fs.exists(work)) fs.deleteRecursively(work)
        fs.createDirectories(work); fs.copy(src, work / "new.db")
        val b = gtOpenDatabase((work / "new.db").toString(), false)
        val mine = LinkedHashMap<String, String>()
        try {
            val ids = ArrayDeque(listOf("cert-user-1"))
            val np = GtProfileStore({ b }, { now }, { ids.removeFirst() })
            val nn = GtNetworkStore({ b }, GtNetworkGenerator(JRandom(17)), { mesh }, { now })
            // 1. reads
            mine["read|profile.exists"] = "${np.exists()}"
            val ln = np.load(); mine["read|profile.load"] = prof(ln)
            val myCfg = col0(b, "SELECT config_id FROM users WHERE is_self = 1").firstOrNull() ?: ""
            mine["read|net.load(own)"] = cfg(nn.load(myCfg))
            mine["read|net.listByOwner(leader,self)"] = nn.listByOwner(GtOwnerType.LEADER, ln?.userId ?: "").joinToString(";") { cfg(it) }
            mine["read|net.exists(own)"] = "${nn.exists(myCfg)}"
            mine["read|net.exists(GroupZZZZZZ)"] = "${nn.exists("GroupZZZZZZ")}"
            // 1b. deleteUnused on the rider's own network: in use, so it stays
            nn.deleteUnused(myCfg)
            mine["net.deleteUnused(own, in use)|rows"] = dump(b, "network_configs", "config_id")
            // 2. profile update
            val pn = (ln ?: GtRiderProfile()).copy(callsign = "Cert Rider", team = "Blue", cell = "555-0100", email = (ln?.email ?: "").ifBlank { "cert@example.com" })
            mine["profile.update|id"] = "${np.save(pn)}"
            mine["profile.update|users"] = dump(b, "users", "user_id")
            // 3. refusal
            mine["profile.refused"] = "${np.save(pn.copy(email = ""))}"
            // 4. create (no own row)
            b.prepare("DELETE FROM users WHERE is_self = 1").let { st -> try { st.step() } finally { st.close() } }
            mine["profile.create|id"] = "${np.save(GtRiderProfile("", "Cert", "Rider", "CertR", "cert@example.com", "555-0101", "rider", "UTV", "Red"))}"
            mine["profile.create|users"] = dump(b, "users", "user_id")
            mine["profile.create|load"] = prof(np.load())
            // 5. networks
            mine["net.create|org"] = cfg(nn.create(GtOwnerType.ORG, "org-cert-1", "Kanab UTV Club"))
            mine["net.create|leader"] = cfg(nn.create(GtOwnerType.LEADER, "cert-user-1", "Cert Rider"))
            mine["net.create|ride"] = cfg(nn.create(GtOwnerType.RIDE, "pending-cert", "Cert Rider"))
            mine["net.create|refused"] = cfg(nn.create(GtOwnerType.RIDE, "", "x"))
            mine["net.create|rows"] = dump(b, "network_configs", "config_id")
            // 6. deleteUnused: every config -- the own row is gone and the rides use GroupTrack, so none is in use now
            for (id in col0(b, "SELECT config_id FROM network_configs ORDER BY config_id")) nn.deleteUnused(id)
            mine["net.deleteUnused|rows"] = dump(b, "network_configs", "config_id")
        } finally { b.close(); fs.deleteRecursively(work) }
        var same = 0; var bad = 0
        for ((k, v) in mine) { val d = droid[k]; if (d == v) same++ else { bad++; println("GTN17C mac: DIFF $k\n  droid=$d\n  mac  =$v") } }
        val extra = droid.keys - mine.keys
        if (extra.isNotEmpty()) { bad += extra.size; println("GTN17C mac: DIFF the Droid has keys the iPad did not run: $extra") }
        println("GTN17C mac: $same SAME, $bad DIFF, cross-device COMPARED (input sha256 ${sha.take(16)}..., mesh $meshIn) -- done")
        assertEquals(0, bad, "the iPad's profile / networks differ from the Droid's")
    }
}
