package com.grouptrack.shared.net

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtRead

/**
 * STEP17G2C1-2026-11-12 (2.8 step 17, G2c-1): the rider's profile, the radio networks and the network generator, ported AS IS from 2.7e
 * (com.grouptrack.core.NetworkGenerator, convoy/ConvoyProfileStore, convoy/ConvoyNetworkStore). Changes, and only these:
 *   the database      -> a connection given in ([db], Android: GtAppDb.wrap(SpatialDbManager.getExtensionDb()))
 *   SecureRandom      -> [GtRandom] (Android: the same SecureRandom; the cert gives both sides java.util.Random(seed))
 *   Base64 (java)     -> GtBase64 (standard alphabet, padded -- java.util.Base64.getEncoder)
 *   UUID / the clock  -> [newId] / [nowUtc] ("yyyy-MM-dd'T'HH:mm:ss'Z'", UTC)
 *   MeshDefaults      -> [GtMeshDefaults] given in (Android reads grouptrack_default.json as before and passes the values)
 *   android.util.Log  -> gtLogger (the same tags)
 * Cursor.getString -> GtRead.cellText (null for NULL, as Android); getInt -> getLong().toInt().
 */
interface GtRandom {
    /** java.util.Random.nextInt(bound) */
    fun nextInt(bound: Int): Int
    /** java.util.Random.nextBytes */
    fun nextBytes(b: ByteArray)
}

enum class GtOwnerType(val db: String) { ORG("org"), LEADER("leader"), RIDE("ride") }

data class GtNetworkConfig(
    val configId: String, val ownerType: GtOwnerType, val ownerId: String, val displayName: String, val baseVersion: Int,
    val region: String, val modemPreset: String, val hopLimit: Int, val txPower: Int, val frequencySlot: Int, val psk: String,
    val role: String, val networkSsid: String,
    /** CODE RULE 1 (2.7e): nullable only for rows read back from older tablets -- the column allows NULL */
    val networkPassword: String?
)

/** 2.7e NetworkGenerator.MeshDefaults: the fallback literals; the app passes what grouptrack_default.json said. */
data class GtMeshDefaults(val region: String = "US", val modemPreset: String = "MEDIUM_FAST", val hopLimit: Int = 3, val txPower: Int = 0,
                          val frequencySlot: Int = 0, val role: String = "TAK_TRACKER") {
    companion object { const val BASE_VERSION = 1; const val TX_POWER_CAP = 30 }
}

object GtBase64 {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    /** java.util.Base64.getEncoder().encodeToString -- standard alphabet, '=' padding, no line breaks */
    fun encode(b: ByteArray): String = buildString((b.size + 2) / 3 * 4) {
        var i = 0
        while (i + 2 < b.size) {
            val n = (b[i].toInt() and 0xff shl 16) or (b[i + 1].toInt() and 0xff shl 8) or (b[i + 2].toInt() and 0xff)
            append(A[n ushr 18 and 63]); append(A[n ushr 12 and 63]); append(A[n ushr 6 and 63]); append(A[n and 63]); i += 3
        }
        val r = b.size - i
        if (r == 1) { val n = b[i].toInt() and 0xff shl 16; append(A[n ushr 18 and 63]); append(A[n ushr 12 and 63]); append("==") }
        if (r == 2) { val n = (b[i].toInt() and 0xff shl 16) or (b[i + 1].toInt() and 0xff shl 8)
            append(A[n ushr 18 and 63]); append(A[n ushr 12 and 63]); append(A[n ushr 6 and 63]); append('=') }
    }
}

class GtNetworkGenerator(private val rng: GtRandom) {
    companion object {
        const val CHANNEL_PREFIX = "Group"
        const val CHANNEL_SUFFIX_LEN = 6
        const val DEFAULT_CHANNEL = "GroupTrack"
        private const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        const val WIFI_PASSWORD_LEN = 8
        private const val PW_ALPHABET = "abcdefghijklmnopqrstuvwxyz"
        const val CALLSIGN_MAX = 39
        private val CALLSIGN_OK = Regex("^[A-Za-z0-9 .'\\-]{1,$CALLSIGN_MAX}$")
        fun isValidCallsign(s: String): Boolean = CALLSIGN_OK.matches(s)
    }
    private fun pick(alphabet: String, n: Int): String = buildString(n) { repeat(n) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    fun newChannelId(): String = CHANNEL_PREFIX + pick(ID_ALPHABET, CHANNEL_SUFFIX_LEN)
    fun newKey(): String { val b = ByteArray(32); rng.nextBytes(b); return GtBase64.encode(b) }
    fun newWifiPassword(): String = pick(PW_ALPHABET, WIFI_PASSWORD_LEN)
    /** 2.7e generate: ids retried until free (10 tries); then the key, THEN the password -- the same order of random draws. */
    fun generate(ownerType: GtOwnerType, ownerId: String, displayName: String, mesh: GtMeshDefaults, isTaken: (String) -> Boolean): GtNetworkConfig? {
        var id = ""
        for (i in 1..10) { val c = newChannelId(); if (c != DEFAULT_CHANNEL && !isTaken(c)) { id = c; break } }
        if (id.isEmpty()) return null
        val psk = newKey()
        val pw = newWifiPassword()
        return GtNetworkConfig(configId = id, ownerType = ownerType, ownerId = ownerId, displayName = displayName,
            baseVersion = GtMeshDefaults.BASE_VERSION, region = mesh.region, modemPreset = mesh.modemPreset, hopLimit = mesh.hopLimit,
            txPower = minOf(mesh.txPower, GtMeshDefaults.TX_POWER_CAP), frequencySlot = mesh.frequencySlot, psk = psk, role = mesh.role,
            networkSsid = id, networkPassword = pw)
    }
}

data class GtRiderProfile(val userId: String = "", val firstName: String = "", val lastName: String = "", val callsign: String = "",
                          val email: String = "", val cell: String = "", val defaultRole: String = "rider", val vehicleType: String = "",
                          val team: String = "")

private fun exec(c: SQLiteConnection, sql: String, args: List<Any?>) {
    val st = c.prepare(sql)
    try {
        args.forEachIndexed { i, a -> when (a) { null -> st.bindNull(i + 1); is Int -> st.bindLong(i + 1, a.toLong()); is Long -> st.bindLong(i + 1, a)
            else -> st.bindText(i + 1, a.toString()) } }
        st.step()
    } finally { st.close() }
}
private inline fun <T> query(c: SQLiteConnection, sql: String, args: List<String>, f: (SQLiteStatement) -> T): T {
    val st = c.prepare(sql)
    try { args.forEachIndexed { i, a -> st.bindText(i + 1, a) }; return f(st) } finally { st.close() }
}
private fun txt(st: SQLiteStatement, i: Int): String? = GtRead.cellText(st, i)

/** 2.7e ConvoyProfileStore: the rider's own row (users.is_self = 1). CODE RULE 1: [db] null = no extension database (2.7e's own null). */
class GtProfileStore(private val db: () -> SQLiteConnection?, private val nowUtc: () -> String, private val newId: () -> String) {
    private val log = gtLogger("ConvoyProfileStore")
    fun exists(): Boolean {
        val c = db() ?: return false
        return try { query(c, "SELECT 1 FROM users WHERE is_self = 1 LIMIT 1", emptyList()) { it.step() } }
        catch (e: Exception) { log.w { "exists() failed: ${e.message}" }; false }
    }
    fun load(): GtRiderProfile? {
        val c = db() ?: return null
        return try {
            query(c, "SELECT user_id, first_name, last_name, callsign, email, cell, default_role, vehicle_type, team FROM users WHERE is_self = 1 LIMIT 1", emptyList()) { st ->
                if (!st.step()) null else GtRiderProfile(userId = txt(st, 0) ?: "", firstName = txt(st, 1) ?: "", lastName = txt(st, 2) ?: "",
                    callsign = txt(st, 3) ?: "", email = txt(st, 4) ?: "", cell = txt(st, 5) ?: "", defaultRole = txt(st, 6) ?: "rider",
                    vehicleType = txt(st, 7) ?: "", team = txt(st, 8) ?: "")
            }
        } catch (e: Exception) { log.w { "load() failed: ${e.message}" }; null }
    }
    fun save(p: GtRiderProfile): String? {
        val c = db() ?: return null
        if (p.callsign.isBlank() || p.email.isBlank()) { log.w { "save() refused: callsign and email are required" }; return null }
        val now = nowUtc()
        val existing = load()
        val id = existing?.userId?.takeIf { it.isNotBlank() } ?: newId()
        return try {
            if (existing == null) {
                exec(c, "INSERT INTO users (user_id, first_name, last_name, email, cell, callsign, default_role, vehicle_type, team, is_self, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,1,?,?)", listOf(id, p.firstName, p.lastName, p.email, p.cell, p.callsign, p.defaultRole, p.vehicleType, p.team, now, now))
                log.i { "PROFILE-2026-09-22: own profile created ($id)" }
            } else {
                exec(c, "UPDATE users SET first_name=?, last_name=?, email=?, cell=?, callsign=?, default_role=?, vehicle_type=?, team=?, updated_at=? WHERE user_id=?",
                    listOf(p.firstName, p.lastName, p.email, p.cell, p.callsign, p.defaultRole, p.vehicleType, p.team, now, id))
                log.i { "PROFILE-2026-09-22: own profile updated ($id)" }
            }
            id
        } catch (e: Exception) { log.e { "save() failed: ${e.message}" }; null }
    }
}

/** 2.7e ConvoyNetworkStore: network_configs. CODE RULE 1: [db] null = no extension database -- exists() then says "taken", as 2.7e. */
class GtNetworkStore(private val db: () -> SQLiteConnection?, private val gen: GtNetworkGenerator, private val mesh: () -> GtMeshDefaults,
                     private val nowUtc: () -> String) {
    private val log = gtLogger("ConvoyNetworkStore")
    private val cols = "config_id, owner_type, owner_id, display_name, base_version, region, modem_preset, hop_limit, tx_power, frequency_slot, psk, role, network_ssid, network_password"
    fun exists(configId: String): Boolean {
        val c = db() ?: return true
        return try { query(c, "SELECT 1 FROM network_configs WHERE config_id = ? LIMIT 1", listOf(configId)) { it.step() } }
        catch (e: Exception) { log.w { "exists() failed: ${e.message}" }; true }
    }
    fun create(ownerType: GtOwnerType, ownerId: String, displayName: String): GtNetworkConfig? {
        val c = db() ?: return null
        if (ownerId.isBlank() || displayName.isBlank()) { log.w { "create() refused: owner id and display name are required" }; return null }
        val cfg = gen.generate(ownerType, ownerId, displayName, mesh()) { exists(it) } ?: run { log.e { "create(): no free channel id after 10 tries" }; return null }
        return try {
            exec(c, "INSERT INTO network_configs ($cols, tak_sdk_version, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?)",
                listOf(cfg.configId, cfg.ownerType.db, cfg.ownerId, cfg.displayName, cfg.baseVersion, cfg.region, cfg.modemPreset, cfg.hopLimit,
                    cfg.txPower, cfg.frequencySlot, cfg.psk, cfg.role, cfg.networkSsid, cfg.networkPassword, nowUtc()))
            log.i { "NETGEN-2026-09-23: ${cfg.ownerType.db} config ${cfg.configId} created for '${cfg.displayName}'" }
            cfg
        } catch (e: Exception) { log.e { "create() failed: ${e.message}" }; null }
    }
    fun deleteUnused(configId: String) {
        val c = db() ?: return
        try {
            exec(c, "DELETE FROM network_configs WHERE config_id = ? AND config_id NOT IN (SELECT config_id FROM rides WHERE config_id IS NOT NULL) AND config_id NOT IN " +
                "(SELECT config_id FROM users WHERE is_self = 1 AND config_id IS NOT NULL) AND config_id NOT IN (SELECT config_id FROM organizations WHERE config_id IS NOT NULL)",
                listOf(configId))
        } catch (e: Exception) { log.w { "deleteUnused failed: ${e.message}" } }
    }
    fun load(configId: String): GtNetworkConfig? = query("config_id = ?", listOf(configId)).firstOrNull()
    fun listByOwner(ownerType: GtOwnerType, ownerId: String): List<GtNetworkConfig> =
        query("owner_type = ? AND owner_id = ? ORDER BY created_at DESC", listOf(ownerType.db, ownerId))
    private fun query(where: String, args: List<String>): List<GtNetworkConfig> {
        val c = db() ?: return emptyList()
        return try {
            query(c, "SELECT $cols FROM network_configs WHERE $where", args) { st ->
                val out = mutableListOf<GtNetworkConfig>()
                while (st.step()) {
                    val type = GtOwnerType.entries.firstOrNull { it.db == txt(st, 1) } ?: continue
                    out += GtNetworkConfig(configId = txt(st, 0) ?: "", ownerType = type, ownerId = txt(st, 2) ?: "", displayName = txt(st, 3) ?: "",
                        baseVersion = st.getLong(4).toInt(), region = txt(st, 5) ?: "", modemPreset = txt(st, 6) ?: "", hopLimit = st.getLong(7).toInt(),
                        txPower = st.getLong(8).toInt(), frequencySlot = st.getLong(9).toInt(), psk = txt(st, 10) ?: "", role = txt(st, 11) ?: "",
                        networkSsid = txt(st, 12) ?: (txt(st, 0) ?: ""), networkPassword = if (st.isNull(13)) null else txt(st, 13))
                }
                out
            }
        } catch (e: Exception) { log.w { "query() failed: ${e.message}" }; emptyList() }
    }
}
