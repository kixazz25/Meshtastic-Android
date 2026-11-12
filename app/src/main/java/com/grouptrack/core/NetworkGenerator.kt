package com.grouptrack.core

import java.security.SecureRandom
import com.grouptrack.shared.net.GtMeshDefaults
import com.grouptrack.shared.net.GtNetworkConfig
import com.grouptrack.shared.net.GtNetworkGenerator
import com.grouptrack.shared.net.GtOwnerType
import com.grouptrack.shared.net.GtRandom

/**
 * NETGEN-2026-09-23 — makes a network config. PURE KOTLIN: no Android, no database.
 *
 * Structure rule R1/R2 (Implementation Targets v23): new GroupTrack code lives under
 * com.grouptrack.core and never imports Meshtastic or com.geeksville. Storage is the
 * app's job (ConvoyNetworkStore); this object only generates values.
 *
 * ONE WRITE PATH (09-22): networks are generated in exactly three places — an org is
 * added, a leader chooses "my own network", a ride asks for its own. All three call
 * generate() here, so the id rule, the key source and the TX cap live in ONE place.
 */
enum class OwnerType(val db: String) { ORG("org"), LEADER("leader"), RIDE("ride") }

data class NetworkConfig(
    val configId: String,          // = the channel name = the Nucleus network name (SSID)
    val ownerType: OwnerType,
    val ownerId: String,
    val displayName: String,       // the OWNER's name — shown as "Kanab UTV Club · 9QXR7A"
    val baseVersion: Int,
    val region: String,
    val modemPreset: String,
    val hopLimit: Int,
    val txPower: Int,
    val frequencySlot: Int,
    val psk: String,               // base64 of 32 random bytes — the SAME key on every device in the ride
    val role: String,
    val networkSsid: String,
    // Generated WITH the channel id (Fred 09-23): the channel id is the network name, so the
    // network's password is born beside it. 8 lowercase letters = OUR PROPOSAL to Natak.
    // Nullable only for rows read back from older tablets (CODE RULE 1): the column allows NULL.
    val networkPassword: String?
)

object NetworkGenerator {

    // ---- names (Fred 09-23) --------------------------------------------------------------
    /** Channel prefix. Replaces CONVOY-. Prefix + suffix = 11, Meshtastic's channel-name limit. */
    const val CHANNEL_PREFIX = "Group"
    const val CHANNEL_SUFFIX_LEN = 6
    /** RESERVED: the default channel written to every new radio at install. Never generated. */
    const val DEFAULT_CHANNEL = "GroupTrack"
    private const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    // ---- WiFi password: OUR PROPOSAL to Natak (8 random lowercase letters, new each ride) --
    const val WIFI_PASSWORD_LEN = 8
    private const val PW_ALPHABET = "abcdefghijklmnopqrstuvwxyz"

    // ---- callsign: the strictest carrier is the Meshtastic long name (max_size:40 -> 39) ---
    const val CALLSIGN_MAX = 39
    fun isValidCallsign(s: String): Boolean = GtNetworkGenerator.isValidCallsign(s)   // SWITCH17G2C1-2026-11-12: the shared rule (same pattern)

    // ---- mesh ride values -------------------------------------------------------------------------
    // ONEDEFAULT-2026-09-26 (Fred): ONE standard -- the radio values every new ride carries come from
    // grouptrack_default.json (network.mesh). loadFrom() runs just before a ride is saved; the literals below are
    // only the FALLBACK when the file cannot be read (logged -- never silent). They were LONG_FAST / 27 / TRACKER
    // until 09-26, which is why every ride made before then carried those.
    object MeshDefaults {
        const val BASE_VERSION = 1          // provisional until T3 freezes base v1
        const val TX_POWER_CAP = 30         // 30 dBm = 1 W
        @Volatile var REGION = "US"
        @Volatile var MODEM_PRESET = "MEDIUM_FAST"
        @Volatile var HOP_LIMIT = 3
        @Volatile var TX_POWER = 0          // 0 = the radio's own maximum within the legal limit
        @Volatile var FREQUENCY_SLOT = 0    // 0 = derived from the channel name
        @Volatile var ROLE = "TAK_TRACKER"

        /** Reads network.mesh from the GroupTrack default asset. Returns false (and keeps the fallback) on failure. */
        fun loadFrom(context: android.content.Context): Boolean = try {
            val mesh = org.json.JSONObject(context.assets.open("grouptrack_default.json").bufferedReader().use { it.readText() })
                .getJSONObject("network").getJSONObject("mesh")
            REGION = mesh.getString("region")
            MODEM_PRESET = mesh.getString("preset")
            HOP_LIMIT = mesh.getInt("hopLimit")
            TX_POWER = mesh.getInt("txPower")
            FREQUENCY_SLOT = mesh.getInt("frequencySlot")
            ROLE = mesh.getString("role")
            android.util.Log.i("NetworkGenerator", "ONEDEFAULT: mesh values from grouptrack_default.json -- $REGION $MODEM_PRESET hop $HOP_LIMIT tx $TX_POWER slot $FREQUENCY_SLOT $ROLE")
            true
        } catch (e: Exception) {
            android.util.Log.e("NetworkGenerator", "ONEDEFAULT: grouptrack_default.json not read (${e.message}) -- using the fallback values")
            false
        }
    }

    // SWITCH17G2C1-2026-11-12 (2.8 step 17 G2c-1): the generating lives in the shared GtNetworkGenerator (certified OLD = NEW at 803ecd4c0, 19 SAME,
    // iPad = Droid). Same SecureRandom source, same draw order (id, then key, then password), same values from MeshDefaults above.
    private val rng = SecureRandom()
    /** the shared generator on this object's SecureRandom -- ConvoyNetworkStore uses it too (one random source, as 2.7e) */
    val shared = GtNetworkGenerator(object : GtRandom {
        override fun nextInt(bound: Int): Int = rng.nextInt(bound)
        override fun nextBytes(b: ByteArray) = rng.nextBytes(b)
    })

    /** the MeshDefaults values right now, as the shared code takes them */
    fun meshNow(): GtMeshDefaults = GtMeshDefaults(MeshDefaults.REGION, MeshDefaults.MODEM_PRESET, MeshDefaults.HOP_LIMIT, MeshDefaults.TX_POWER,
        MeshDefaults.FREQUENCY_SLOT, MeshDefaults.ROLE)
    fun toShared(t: OwnerType): GtOwnerType = GtOwnerType.valueOf(t.name)
    fun fromShared(t: GtOwnerType): OwnerType = OwnerType.valueOf(t.name)
    fun fromShared(c: GtNetworkConfig): NetworkConfig = NetworkConfig(configId = c.configId, ownerType = fromShared(c.ownerType), ownerId = c.ownerId,
        displayName = c.displayName, baseVersion = c.baseVersion, region = c.region, modemPreset = c.modemPreset, hopLimit = c.hopLimit,
        txPower = c.txPower, frequencySlot = c.frequencySlot, psk = c.psk, role = c.role, networkSsid = c.networkSsid, networkPassword = c.networkPassword)

    /** Group + 6 from a secure source. Generated ONCE, at save — never per keystroke. */
    fun newChannelId(): String = shared.newChannelId()

    /** 32 random bytes, base64. psk_random is never used: every device must hold THIS key. */
    fun newKey(): String = shared.newKey()

    /** 8 random lowercase letters — read aloud to a straggler at the trailhead. */
    fun newWifiPassword(): String = shared.newWifiPassword()

    /**
     * A new config for [ownerType]/[ownerId]. [isTaken] checks the id against configs already
     * on this tablet; ids are retried until free (10 tries). Returns null only on that failure.
     */
    fun generate(
        ownerType: OwnerType,
        ownerId: String,
        displayName: String,
        isTaken: (String) -> Boolean
    ): NetworkConfig? = shared.generate(toShared(ownerType), ownerId, displayName, meshNow(), isTaken)?.let { fromShared(it) }
}
