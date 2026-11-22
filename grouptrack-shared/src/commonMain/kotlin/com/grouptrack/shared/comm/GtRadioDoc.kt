package com.grouptrack.shared.comm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.net.GtMeshDefaults
import com.grouptrack.shared.ride.RideFiles

/**
 * MESHFIELDS-2026-11-22 -- THE ONE OWNER OF THE CONFIG-DOC FIELD NAMES.
 *
 * [stated] Fred 11-22 08:04: "the service should be serving the field name from the json and
 * moving them over before the update is applied."
 *
 * ⛔ WHY THIS OBJECT EXISTS, and it is not a style preference. The names of the keys inside
 * grouptrack_default.json (and inside a ride's own config doc, which has the same layout) were
 * hand-typed in SIX places. Five spelled them as the file does. IosMeshDefaults spelled four of
 * them as GtNetworkStore's DATABASE COLUMNS -- modem_preset / hop_limit / tx_power /
 * frequency_slot -- which are not keys in the file at all. Its lookup threw on the first one, its
 * catch swallowed the throw, and because that reader is deliberately ALL SIX OR NONE, every value
 * fell back to GtMeshDefaults()'s literals. Five of those literals equal the file by coincidence.
 * hopLimit does not: the file says 2, the fallback is 3. So the iPad wrote hop limit 3 into every
 * ride network while the Droid wrote 2 -- one silent field, in a ride package the two platforms
 * must write identically.
 *
 * ⇒ So the field names live HERE, once. A caller asks for values and marshals them into whatever
 * it is about to write -- a radio, or the network_configs row. A caller never names a key.
 *
 * ⭐ WITNESS that this is working: hopLimit 2 in a network the iPad created, and no
 * "ONEDEFAULT: ... not read" line in its log. hopLimit 3 means the parse fell back. One field
 * settles it in either direction, which is what makes it a witness and not a hope.
 *
 * ⚠ ANDROID IS NOT CHANGED BY THIS FILE. NetworkGenerator.MeshDefaults.loadFrom,
 * NetworkGeneratorCertOld.loadFrom and RadioConfigurator.fromRideFile keep their own correct
 * copies of the names for now; they delegate here when consumers are counted at the end of the
 * loop (Fred 11-21 07:29: "build the second service, flag the services to be evaluated when we
 * have completed this loop ... not before"). FLAGGED, deliberately, not forgotten.
 *
 * ⚠ NO PROTO TYPES HERE. The five enum-valued fields are carried as their enum NAMES in Strings.
 * commonMain holds zero org.meshtastic imports, and the iPad's SDK protos are a different
 * generation from core/proto (11-20 SDK TRAP 2), so an enum typed here could not be shared.
 * Each platform maps a name to its own generation at the write site.
 */
object GtRadioDoc {

    /** network.mesh -- the names, exactly as the file spells them. */
    object MeshField {
        const val REGION = "region"
        const val PRESET = "preset"
        const val HOP_LIMIT = "hopLimit"
        const val TX_POWER = "txPower"
        const val FREQUENCY_SLOT = "frequencySlot"
        const val ROLE = "role"
        const val CHANNEL_NAME = "channelName"
        const val KEY = "key"
    }

    /** network.standalone -- the reporting group plus the Bluetooth pairing mode. */
    object StandaloneField {
        const val BROADCAST_SECS = "positionBroadcastSecs"
        const val SMART_ENABLED = "smartPositionEnabled"
        const val SMART_MIN_INTERVAL_SECS = "smartMinIntervalSecs"
        const val SMART_MIN_DISTANCE_METERS = "smartMinDistanceMeters"
        const val GPS_MODE = "gpsMode"
        const val GPS_UPDATE_SECS = "gpsUpdateSecs"
        const val TX_ENABLED = "txEnabled"
        const val FIXED_POSITION = "fixedPosition"
        const val POSITION_PRECISION = "positionPrecision"
        const val POSITION_FLAGS = "positionFlags"
        const val SERIAL_ENABLED = "serialEnabled"
        const val BLUETOOTH_MODE = "bluetoothMode"
    }

    /** The reporting group. Strings carry proto enum NAMES. */
    data class Reporting(
        val broadcastSecs: Int,
        val smartEnabled: Boolean,
        val smartMinIntervalSecs: Int,
        val smartMinDistanceMeters: Int,
        /** Config.PositionConfig.GpsMode name, e.g. "ENABLED". */
        val gpsMode: String,
        val gpsUpdateSecs: Int,
        val txEnabled: Boolean,
        val fixedPosition: Boolean,
        /** On the CHANNEL (module settings), group 2. 32 = full precision; 0 = not shared. */
        val positionPrecision: Int,
        /** Which fields a position carries -- group 1b. */
        val positionFlags: Int,
        /** The serial module enabled -- group 3. */
        val serialEnabled: Boolean,
    )

    /**
     * Everything an apply manages.
     *
     * ⭐ [mesh] IS THE EXISTING SHARED TYPE, [GtMeshDefaults] -- the same six values the ride-network
     * generator uses, read by the same code. That is the de-duplication: one type, one reader, so
     * the generator and the radio apply cannot drift from each other the way they just did.
     */
    data class Managed(
        /** CODE RULE 1: null = KEEP the radio's own name (nothing typed) -- group 1a skipped. */
        val ownerLongName: String?,
        val mesh: GtMeshDefaults,
        val channelName: String,
        /** Base64, exactly as the doc holds it. The platform decodes it (okio on Android). */
        val keyBase64: String,
        val reporting: Reporting,
        /** Config.BluetoothConfig.PairingMode name. GroupTrack's standard is NO_PIN. */
        val bluetoothMode: String,
    )

    // ---------------------------------------------------------------- strict readers
    //
    // Every read THROWS on a missing or wrong-typed field, naming it. That is deliberate and it
    // matches Android, which reads with getString/getInt. A config doc with no region must fail
    // loudly: an unset region means the radio will not transmit legally.

    private fun obj(parent: JsonObject?, key: String, where: String): JsonObject =
        parent?.get(key) as? JsonObject
            ?: throw IllegalArgumentException("config doc: " + where + "." + key + " is missing or not an object")

    private fun str(o: JsonObject, key: String, where: String): String =
        GtOrgJson.valueText(o[key])
            ?: throw IllegalArgumentException("config doc: " + where + "." + key + " is missing")

    private fun int(o: JsonObject, key: String, where: String): Int {
        val p = o[key] as? JsonPrimitive
            ?: throw IllegalArgumentException("config doc: " + where + "." + key + " is missing")
        return p.content.trim().toIntOrNull()
            ?: throw IllegalArgumentException("config doc: " + where + "." + key + " is not a number: " + p.content)
    }

    private fun bool(o: JsonObject, key: String, where: String): Boolean {
        val p = o[key] as? JsonPrimitive
            ?: throw IllegalArgumentException("config doc: " + where + "." + key + " is missing")
        return when (p.content.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("config doc: " + where + "." + key + " is not a boolean: " + p.content)
        }
    }

    private fun root(text: String, which: String): JsonObject =
        GtOrgJson.parse(text) as? JsonObject
            ?: throw IllegalArgumentException(which + " config doc is not a JSON object")

    private fun meshOf(text: String, which: String): JsonObject =
        obj(obj(root(text, which), "network", which), "mesh", which + ".network")

    private fun standaloneOf(text: String, which: String): JsonObject =
        obj(obj(root(text, which), "network", which), "standalone", which + ".network")

    // ---------------------------------------------------------------- what callers ask for

    /**
     * The six mesh values, as the EXISTING shared type. This is what the ride-network generator
     * needs, and [IosMeshDefaults] delegates here for it.
     *
     * THROWS if any of the six is missing -- which is the contract Android's loadFrom relies on:
     * it catches and keeps ALL SIX fallbacks rather than mixing a file value with a literal.
     * Callers keep that try/catch; this function must not soften it into per-field defaults.
     */
    fun meshDefaults(docText: String): GtMeshDefaults {
        val m = meshOf(docText, "default")
        return GtMeshDefaults(
            region = str(m, MeshField.REGION, "mesh"),
            modemPreset = str(m, MeshField.PRESET, "mesh"),
            hopLimit = int(m, MeshField.HOP_LIMIT, "mesh"),
            txPower = int(m, MeshField.TX_POWER, "mesh"),
            frequencySlot = int(m, MeshField.FREQUENCY_SLOT, "mesh"),
            role = str(m, MeshField.ROLE, "mesh"),
        )
    }

    /**
     * Everything a radio apply manages: network.mesh from the RIDE doc (the ride's own network),
     * the reporting group from the DEFAULT doc's network.standalone (the default is the base under
     * every ride), the owner from the rider's callsign.
     *
     * The sources and the owner rule are Android's, from RadioConfigurator.fromRideFile.
     */
    fun fromRideDoc(rideDoc: String, defaultDoc: String, callsign: String): Managed {
        val m = meshOf(rideDoc, "ride")
        val s = standaloneOf(defaultDoc, "default")
        return Managed(
            ownerLongName = callsign.trim().ifEmpty { null },
            mesh = GtMeshDefaults(
                region = str(m, MeshField.REGION, "mesh"),
                modemPreset = str(m, MeshField.PRESET, "mesh"),
                hopLimit = int(m, MeshField.HOP_LIMIT, "mesh"),
                txPower = int(m, MeshField.TX_POWER, "mesh"),
                frequencySlot = int(m, MeshField.FREQUENCY_SLOT, "mesh"),
                role = str(m, MeshField.ROLE, "mesh"),
            ),
            channelName = str(m, MeshField.CHANNEL_NAME, "mesh"),
            keyBase64 = str(m, MeshField.KEY, "mesh"),
            reporting = Reporting(
                broadcastSecs = int(s, StandaloneField.BROADCAST_SECS, "standalone"),
                smartEnabled = bool(s, StandaloneField.SMART_ENABLED, "standalone"),
                smartMinIntervalSecs = int(s, StandaloneField.SMART_MIN_INTERVAL_SECS, "standalone"),
                smartMinDistanceMeters = int(s, StandaloneField.SMART_MIN_DISTANCE_METERS, "standalone"),
                gpsMode = str(s, StandaloneField.GPS_MODE, "standalone"),
                gpsUpdateSecs = int(s, StandaloneField.GPS_UPDATE_SECS, "standalone"),
                txEnabled = bool(s, StandaloneField.TX_ENABLED, "standalone"),
                fixedPosition = bool(s, StandaloneField.FIXED_POSITION, "standalone"),
                positionPrecision = int(s, StandaloneField.POSITION_PRECISION, "standalone"),
                positionFlags = int(s, StandaloneField.POSITION_FLAGS, "standalone"),
                serialEnabled = bool(s, StandaloneField.SERIAL_ENABLED, "standalone"),
            ),
            bluetoothMode = str(s, StandaloneField.BLUETOOTH_MODE, "standalone"),
        )
    }

    /**
     * The same, reached the way the screen reaches it: a ride's config doc BY FILE NAME against the
     * bundled default. Pairs with [GtRadioApply.configDoc] / [GtRadioApply.defaultConfigDoc], which
     * already do this file half on the iPad.
     *
     * CODE RULE 1: null = that file is not there. A doc that IS there and is malformed throws,
     * because a malformed config doc is a defect and must not read as "no config".
     */
    fun fromRideFileName(fs: RideFiles, fileName: String, callsign: String): Managed? {
        val doc = GtRadioApply.configDoc(fs, fileName) ?: return null
        return fromRideDoc(doc, GtRadioApply.defaultConfigDoc(fs), callsign)
    }
}
