package com.grouptrack.core

import java.security.SecureRandom
import java.util.Base64

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
    private val CALLSIGN_OK = Regex("^[A-Za-z0-9 .'\\-]{1,$CALLSIGN_MAX}$")
    fun isValidCallsign(s: String): Boolean = CALLSIGN_OK.matches(s)

    // ---- mesh ride values: PROVISIONAL, ONE place (today's radios). Role waits on test T3. --
    object MeshDefaults {
        const val BASE_VERSION = 1          // provisional until T3 freezes base v1
        const val REGION = "US"
        const val MODEM_PRESET = "LONG_FAST"
        const val HOP_LIMIT = 3
        const val TX_POWER = 27             // dBm — never above the licence-free cap
        const val TX_POWER_CAP = 30         // 30 dBm = 1 W
        const val FREQUENCY_SLOT = 0        // 0 = derived from the channel name
        const val ROLE = "TRACKER"          // PROVISIONAL — TAK or CLIENT after T3
    }

    private val rng = SecureRandom()

    private fun pick(alphabet: String, n: Int): String =
        buildString(n) { repeat(n) { append(alphabet[rng.nextInt(alphabet.length)]) } }

    /** Group + 6 from a secure source. Generated ONCE, at save — never per keystroke. */
    fun newChannelId(): String = CHANNEL_PREFIX + pick(ID_ALPHABET, CHANNEL_SUFFIX_LEN)

    /** 32 random bytes, base64. psk_random is never used: every device must hold THIS key. */
    fun newKey(): String {
        val b = ByteArray(32); rng.nextBytes(b)
        return Base64.getEncoder().encodeToString(b)
    }

    /** 8 random lowercase letters — read aloud to a straggler at the trailhead. */
    fun newWifiPassword(): String = pick(PW_ALPHABET, WIFI_PASSWORD_LEN)

    /**
     * A new config for [ownerType]/[ownerId]. [isTaken] checks the id against configs already
     * on this tablet; ids are retried until free (collisions at 36^6 are rare, so 10 tries is
     * a bug signal, not a limit). Returns null only on that failure.
     */
    fun generate(
        ownerType: OwnerType,
        ownerId: String,
        displayName: String,
        isTaken: (String) -> Boolean
    ): NetworkConfig? {
        var id = ""
        for (i in 1..10) {
            val c = newChannelId()
            if (c != DEFAULT_CHANNEL && !isTaken(c)) { id = c; break }
        }
        if (id.isEmpty()) return null
        return NetworkConfig(
            configId = id,
            ownerType = ownerType,
            ownerId = ownerId,
            displayName = displayName,
            baseVersion = MeshDefaults.BASE_VERSION,
            region = MeshDefaults.REGION,
            modemPreset = MeshDefaults.MODEM_PRESET,
            hopLimit = MeshDefaults.HOP_LIMIT,
            txPower = minOf(MeshDefaults.TX_POWER, MeshDefaults.TX_POWER_CAP),
            frequencySlot = MeshDefaults.FREQUENCY_SLOT,
            psk = newKey(),
            role = MeshDefaults.ROLE,
            networkSsid = id,
            networkPassword = newWifiPassword()
        )
    }
}
