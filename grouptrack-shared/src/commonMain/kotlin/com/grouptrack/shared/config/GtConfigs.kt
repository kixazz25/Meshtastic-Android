package com.grouptrack.shared.config

import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.user.GtIsoDate
import com.grouptrack.shared.user.GtUser
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path

/**
 * STEP17G2D-2026-11-12 (2.8 step 17, G2d): the V2 config files -- convoy/ConvoyMasterConfig.kt (the master radio config:
 * assets/master_config.json, copied to filesDir) and convoy/ConvoyEventConfig.kt (ConvoyEventConfig + ConvoyEventStore,
 * filesDir/convoy_events/, one .json file per event) -- ported AS IS. (FIX17DCOMMENT-2026-11-12: no slash-star in a comment.) The 58 + 30 fields, every toJson key and every fromJson read and fallback were
 * generated from the 2.7e source (gen_shared_17d.py; order asserted), not typed. Changes, and only these:
 *   org.json            -> GtOrgJson (the same text: key order, toString() / toString(2); the same opt / get values)
 *   File / assets       -> okio [fs] + paths given in; the asset as [readAsset] (throws when there is none, as assets.open)
 *   listFiles()         -> [list] given in (Android passes File.listFiles' own order -- 2.7e's; the iPad passes fs.list, sorted)
 *   LocalDate / UUID    -> [today] / [newId] given in; parse / plusDays -> GtIsoDate (+ [plusDays] here)
 *   android.util.Log    -> gtLogger (the same tags and lines)
 * ⚠ NOT REPRODUCED (as GtOrgJson): Android's lenient JSON. 2.7e only reads files it wrote, the asset and event files other GroupTrack
 *   devices wrote with toString(2) -- none lenient.
 * ⚠ AS IS, noted: the shipped asset has no "configVersion", so load() finds the device copy "v0 < 2" and restores it from the asset
 *   every time. Kept (port as is); on the 2.8 list to decide.
 */
object GtIsoDays {
    /** LocalDate.plusDays */
    fun plusDays(s: String, n: Int): String {
        var (y, m, d) = GtIsoDate.parse(s)
        d += n
        while (d > GtIsoDate.days(y, m)) { d -= GtIsoDate.days(y, m); m++; if (m > 12) { m = 1; y++ } }
        while (d < 1) { m--; if (m < 1) { m = 12; y-- }; d += GtIsoDate.days(y, m) }
        return GtIsoDate.format(y, m, d)
    }
}

private fun req(o: JsonObject, k: String): String = GtOrgJson.valueText(o[k]) ?: throw IllegalArgumentException("No value for $k")

data class GtMasterConfig(
    val hardwareModel: String,
    val firmwareVersion: String,
    val pioEnv: String,
    val longName: String,
    val shortName: String,
    val nodeRole: String,
    val isManaged: Boolean,
    val serialEnabled: Boolean,
    val loraRegion: String,
    val loraModemPreset: String,
    val loraBandwidth: Int,
    val loraSpreadFactor: Int,
    val loraCodingRate: Int,
    val loraHopLimit: Int,
    val loraTxEnabled: Boolean,
    val loraTxPower: Int,
    val loraChannelNum: Int,
    val primaryChannelName: String,
    val primaryChannelPsk: String,
    val channelId: Int,
    val channelUplinkEnabled: Boolean,
    val channelDownlinkEnabled: Boolean,
    val positionPrecision: Int,
    val channelIsMuted: Boolean,
    val gpsEnabled: Boolean,
    val gpsMode: String,
    val gpsUpdateSecs: Int,
    val gpsAttemptTime: Int,
    val positionBroadcastSecs: Int,
    val smartPositionEnabled: Boolean,
    val fixedPosition: Boolean,
    val positionFlags: Int,
    val smartMinIntervalSecs: Int,
    val smartMinDistanceMeters: Int,
    val displayUnits: Int,
    val screenTimeout: Int,
    val autoScreenBrightness: Boolean,
    val compassNorthTop: Boolean,
    val telemetryDeviceInterval: Int,
    val telemetryEnvInterval: Int,
    val telemetryEnvEnabled: Boolean,
    val mqttEnabled: Boolean,
    val mqttAddress: String,
    val mqttUsername: String,
    val mqttEncryptionEnabled: Boolean,
    val mqttJsonEnabled: Boolean,
    val serialModuleEnabled: Boolean,
    val serialBaud: Int,
    val extNotificationEnabled: Boolean,
    val extNotificationAlertMsg: Boolean,
    val rangeTestEnabled: Boolean,
    val storeForwardEnabled: Boolean,
    val neighborInfoEnabled: Boolean,
    val detectionSensorEnabled: Boolean,
    val audioEnabled: Boolean,
    val deviceProfileBase64: String,
    val capturedDate: String,
    val capturedFirmware: String
) {
    fun toJson(): JsonObject = JsonObject(linkedMapOf(
            "hardwareModel" to GtOrgJson.str(hardwareModel),
            "firmwareVersion" to GtOrgJson.str(firmwareVersion),
            "pioEnv" to GtOrgJson.str(pioEnv),
            "longName" to GtOrgJson.str(longName),
            "shortName" to GtOrgJson.str(shortName),
            "nodeRole" to GtOrgJson.str(nodeRole),
            "isManaged" to GtOrgJson.bool(isManaged),
            "serialEnabled" to GtOrgJson.bool(serialEnabled),
            "loraRegion" to GtOrgJson.str(loraRegion),
            "loraModemPreset" to GtOrgJson.str(loraModemPreset),
            "loraBandwidth" to GtOrgJson.num(loraBandwidth),
            "loraSpreadFactor" to GtOrgJson.num(loraSpreadFactor),
            "loraCodingRate" to GtOrgJson.num(loraCodingRate),
            "loraHopLimit" to GtOrgJson.num(loraHopLimit),
            "loraTxEnabled" to GtOrgJson.bool(loraTxEnabled),
            "loraTxPower" to GtOrgJson.num(loraTxPower),
            "loraChannelNum" to GtOrgJson.num(loraChannelNum),
            "primaryChannelName" to GtOrgJson.str(primaryChannelName),
            "primaryChannelPsk" to GtOrgJson.str(primaryChannelPsk),
            "channelId" to GtOrgJson.num(channelId),
            "channelUplinkEnabled" to GtOrgJson.bool(channelUplinkEnabled),
            "channelDownlinkEnabled" to GtOrgJson.bool(channelDownlinkEnabled),
            "positionPrecision" to GtOrgJson.num(positionPrecision),
            "channelIsMuted" to GtOrgJson.bool(channelIsMuted),
            "gpsEnabled" to GtOrgJson.bool(gpsEnabled),
            "gpsMode" to GtOrgJson.str(gpsMode),
            "gpsUpdateSecs" to GtOrgJson.num(gpsUpdateSecs),
            "gpsAttemptTime" to GtOrgJson.num(gpsAttemptTime),
            "positionBroadcastSecs" to GtOrgJson.num(positionBroadcastSecs),
            "smartPositionEnabled" to GtOrgJson.bool(smartPositionEnabled),
            "fixedPosition" to GtOrgJson.bool(fixedPosition),
            "positionFlags" to GtOrgJson.num(positionFlags),
            "smartMinIntervalSecs" to GtOrgJson.num(smartMinIntervalSecs),
            "smartMinDistanceMeters" to GtOrgJson.num(smartMinDistanceMeters),
            "displayUnits" to GtOrgJson.num(displayUnits),
            "screenTimeout" to GtOrgJson.num(screenTimeout),
            "autoScreenBrightness" to GtOrgJson.bool(autoScreenBrightness),
            "compassNorthTop" to GtOrgJson.bool(compassNorthTop),
            "telemetryDeviceInterval" to GtOrgJson.num(telemetryDeviceInterval),
            "telemetryEnvInterval" to GtOrgJson.num(telemetryEnvInterval),
            "telemetryEnvEnabled" to GtOrgJson.bool(telemetryEnvEnabled),
            "mqttEnabled" to GtOrgJson.bool(mqttEnabled),
            "mqttAddress" to GtOrgJson.str(mqttAddress),
            "mqttUsername" to GtOrgJson.str(mqttUsername),
            "mqttEncryptionEnabled" to GtOrgJson.bool(mqttEncryptionEnabled),
            "mqttJsonEnabled" to GtOrgJson.bool(mqttJsonEnabled),
            "serialModuleEnabled" to GtOrgJson.bool(serialModuleEnabled),
            "serialBaud" to GtOrgJson.num(serialBaud),
            "extNotificationEnabled" to GtOrgJson.bool(extNotificationEnabled),
            "extNotificationAlertMsg" to GtOrgJson.bool(extNotificationAlertMsg),
            "rangeTestEnabled" to GtOrgJson.bool(rangeTestEnabled),
            "storeForwardEnabled" to GtOrgJson.bool(storeForwardEnabled),
            "neighborInfoEnabled" to GtOrgJson.bool(neighborInfoEnabled),
            "detectionSensorEnabled" to GtOrgJson.bool(detectionSensorEnabled),
            "audioEnabled" to GtOrgJson.bool(audioEnabled),
            "deviceProfileBase64" to GtOrgJson.str(deviceProfileBase64),
            "capturedDate" to GtOrgJson.str(capturedDate),
            "capturedFirmware" to GtOrgJson.str(capturedFirmware)))

    companion object {
        const val TAG = "ConvoyMasterConfig"
        const val ASSET_FILE = "master_config.json"
        const val DEVICE_FILE = "master_config.json"
        const val CONFIG_VERSION = 2
        fun fromJson(o: JsonObject): GtMasterConfig = GtMasterConfig(
            hardwareModel = GtOrgJson.optString(o, "hardwareModel", "Unknown"),
            firmwareVersion = GtOrgJson.optString(o, "firmwareVersion", "Unknown"),
            pioEnv = GtOrgJson.optString(o, "pioEnv", ""),
            longName = GtOrgJson.optString(o, "longName", ""),
            shortName = GtOrgJson.optString(o, "shortName", ""),
            nodeRole = GtOrgJson.optString(o, "nodeRole", "CLIENT"),
            isManaged = GtOrgJson.optBoolean(o, "isManaged", false),
            serialEnabled = GtOrgJson.optBoolean(o, "serialEnabled", false),
            loraRegion = GtOrgJson.optString(o, "loraRegion", "US"),
            loraModemPreset = GtOrgJson.optString(o, "loraModemPreset", "LONG_FAST"),
            loraBandwidth = GtOrgJson.optInt(o, "loraBandwidth", 0),
            loraSpreadFactor = GtOrgJson.optInt(o, "loraSpreadFactor", 0),
            loraCodingRate = GtOrgJson.optInt(o, "loraCodingRate", 0),
            loraHopLimit = GtOrgJson.optInt(o, "loraHopLimit", 3),
            loraTxEnabled = GtOrgJson.optBoolean(o, "loraTxEnabled", true),
            loraTxPower = GtOrgJson.optInt(o, "loraTxPower", 27),
            loraChannelNum = GtOrgJson.optInt(o, "loraChannelNum", 0),
            primaryChannelName = GtOrgJson.optString(o, "primaryChannelName", ""),
            primaryChannelPsk = GtOrgJson.optString(o, "primaryChannelPsk", ""),
            channelId = GtOrgJson.optInt(o, "channelId", 0),
            channelUplinkEnabled = GtOrgJson.optBoolean(o, "channelUplinkEnabled", false),
            channelDownlinkEnabled = GtOrgJson.optBoolean(o, "channelDownlinkEnabled", false),
            positionPrecision = GtOrgJson.optInt(o, "positionPrecision", 32),
            channelIsMuted = GtOrgJson.optBoolean(o, "channelIsMuted", false),
            gpsEnabled = GtOrgJson.optBoolean(o, "gpsEnabled", true),
            gpsMode = GtOrgJson.optString(o, "gpsMode", "ENABLED"),
            gpsUpdateSecs = GtOrgJson.optInt(o, "gpsUpdateSecs", 1),
            gpsAttemptTime = GtOrgJson.optInt(o, "gpsAttemptTime", 900),
            positionBroadcastSecs = GtOrgJson.optInt(o, "positionBroadcastSecs", 5),
            smartPositionEnabled = GtOrgJson.optBoolean(o, "smartPositionEnabled", true),
            fixedPosition = GtOrgJson.optBoolean(o, "fixedPosition", false),
            positionFlags = GtOrgJson.optInt(o, "positionFlags", 811),
            smartMinIntervalSecs = GtOrgJson.optInt(o, "smartMinIntervalSecs", 3),
            smartMinDistanceMeters = GtOrgJson.optInt(o, "smartMinDistanceMeters", 10),
            displayUnits = GtOrgJson.optInt(o, "displayUnits", 0),
            screenTimeout = GtOrgJson.optInt(o, "screenTimeout", 0),
            autoScreenBrightness = GtOrgJson.optBoolean(o, "autoScreenBrightness", false),
            compassNorthTop = GtOrgJson.optBoolean(o, "compassNorthTop", false),
            telemetryDeviceInterval = GtOrgJson.optInt(o, "telemetryDeviceInterval", 0),
            telemetryEnvInterval = GtOrgJson.optInt(o, "telemetryEnvInterval", 0),
            telemetryEnvEnabled = GtOrgJson.optBoolean(o, "telemetryEnvEnabled", false),
            mqttEnabled = GtOrgJson.optBoolean(o, "mqttEnabled", false),
            mqttAddress = GtOrgJson.optString(o, "mqttAddress", ""),
            mqttUsername = GtOrgJson.optString(o, "mqttUsername", ""),
            mqttEncryptionEnabled = GtOrgJson.optBoolean(o, "mqttEncryptionEnabled", false),
            mqttJsonEnabled = GtOrgJson.optBoolean(o, "mqttJsonEnabled", false),
            serialModuleEnabled = GtOrgJson.optBoolean(o, "serialModuleEnabled", false),
            serialBaud = GtOrgJson.optInt(o, "serialBaud", 0),
            extNotificationEnabled = GtOrgJson.optBoolean(o, "extNotificationEnabled", false),
            extNotificationAlertMsg = GtOrgJson.optBoolean(o, "extNotificationAlertMsg", false),
            rangeTestEnabled = GtOrgJson.optBoolean(o, "rangeTestEnabled", false),
            storeForwardEnabled = GtOrgJson.optBoolean(o, "storeForwardEnabled", false),
            neighborInfoEnabled = GtOrgJson.optBoolean(o, "neighborInfoEnabled", false),
            detectionSensorEnabled = GtOrgJson.optBoolean(o, "detectionSensorEnabled", false),
            audioEnabled = GtOrgJson.optBoolean(o, "audioEnabled", false),
            deviceProfileBase64 = GtOrgJson.optString(o, "deviceProfileBase64", ""),
            capturedDate = GtOrgJson.optString(o, "capturedDate", ""),
            capturedFirmware = GtOrgJson.optString(o, "capturedFirmware", ""))
    }
}

/** 2.7e ConvoyMasterConfig.load / restoreFromAsset / exists. [readAsset] = assets.open(ASSET_FILE) read whole (throws when missing). */
class GtMasterConfigStore(private val fs: FileSystem, private val deviceFile: Path, private val readAsset: () -> String) {
    private val log = gtLogger(GtMasterConfig.TAG)
    private fun obj(text: String): JsonObject = GtOrgJson.parse(text) as? JsonObject ?: throw IllegalArgumentException("not a JSON object")
    fun load(): GtMasterConfig? {
        // Try device file first
        if (fs.exists(deviceFile)) {
            return try {
                val json = obj(fs.read(deviceFile) { readUtf8() })
                val deviceVersion = GtOrgJson.optInt(json, "configVersion", 0)
                if (deviceVersion < GtMasterConfig.CONFIG_VERSION) {
                    log.i { "Master config v$deviceVersion < ${GtMasterConfig.CONFIG_VERSION}, restoring from asset" }
                    restoreFromAsset()
                } else {
                    GtMasterConfig.fromJson(json).also { log.i { "Master config loaded from device v$deviceVersion" } }
                }
            } catch (e: Exception) {
                log.w(e) { "Device master config corrupt, restoring from asset" }
                restoreFromAsset()
            }
        }
        // Missing — restore from bundled asset
        log.i { "Master config missing on device, restoring from asset" }
        return restoreFromAsset()
    }
    private fun restoreFromAsset(): GtMasterConfig? = try {
        val json = readAsset()
        deviceFile.parent?.let { fs.createDirectories(it) }
        fs.write(deviceFile) { writeUtf8(json) }
        GtMasterConfig.fromJson(obj(json)).also { log.i { "Master config restored from asset" } }
    } catch (e: Exception) {
        log.e(e) { "Failed to load master config from asset" }
        null
    }
    fun exists(): Boolean {
        if (fs.exists(deviceFile)) return true
        return try { readAsset(); true } catch (e: Exception) { false }
    }
}

data class GtEventConfig(
    val eventId: String,
    val eventName: String,
    val eventDate: String,
    val eventDescription: String,
    val organizerUserId: String,
    val organizerFirstName: String,
    val organizerLastName: String,
    val organizerEmail: String,
    val organizerPhone: String,
    val organizerVehicleType: String,
    val hardwareId: String,
    val deviceId: String,
    val hardwareModel: String,
    val firmwareVersion: String,
    val channelName: String,
    val channelPsk: String,
    val loraRegion: String,
    val loraModemPreset: String,
    val loraBandwidth: Int,
    val loraSpreadFactor: Int,
    val loraCodingRate: Int,
    val loraHopLimit: Int,
    val loraTxPower: Int,
    val createdDate: String,
    val expirationDate: String,
    val mapAreaNorth: Double = 0.0,
    val mapAreaSouth: Double = 0.0,
    val mapAreaEast: Double = 0.0,
    val mapAreaWest: Double = 0.0,
    val mapZoomDepth: Int = 14
) {
    fun toJson(): JsonObject = JsonObject(linkedMapOf(
            "eventId" to GtOrgJson.str(eventId),
            "eventName" to GtOrgJson.str(eventName),
            "eventDate" to GtOrgJson.str(eventDate),
            "eventDescription" to GtOrgJson.str(eventDescription),
            "organizerUserId" to GtOrgJson.str(organizerUserId),
            "organizerFirstName" to GtOrgJson.str(organizerFirstName),
            "organizerLastName" to GtOrgJson.str(organizerLastName),
            "organizerEmail" to GtOrgJson.str(organizerEmail),
            "organizerPhone" to GtOrgJson.str(organizerPhone),
            "organizerVehicleType" to GtOrgJson.str(organizerVehicleType),
            "hardwareId" to GtOrgJson.str(hardwareId),
            "deviceId" to GtOrgJson.str(deviceId),
            "hardwareModel" to GtOrgJson.str(hardwareModel),
            "firmwareVersion" to GtOrgJson.str(firmwareVersion),
            "channelName" to GtOrgJson.str(channelName),
            "channelPsk" to GtOrgJson.str(channelPsk),
            "loraRegion" to GtOrgJson.str(loraRegion),
            "loraModemPreset" to GtOrgJson.str(loraModemPreset),
            "loraBandwidth" to GtOrgJson.num(loraBandwidth),
            "loraSpreadFactor" to GtOrgJson.num(loraSpreadFactor),
            "loraCodingRate" to GtOrgJson.num(loraCodingRate),
            "loraHopLimit" to GtOrgJson.num(loraHopLimit),
            "loraTxPower" to GtOrgJson.num(loraTxPower),
            "createdDate" to GtOrgJson.str(createdDate),
            "expirationDate" to GtOrgJson.str(expirationDate),
            "mapAreaNorth" to GtOrgJson.num(mapAreaNorth),
            "mapAreaSouth" to GtOrgJson.num(mapAreaSouth),
            "mapAreaEast" to GtOrgJson.num(mapAreaEast),
            "mapAreaWest" to GtOrgJson.num(mapAreaWest),
            "mapZoomDepth" to GtOrgJson.num(mapZoomDepth)))

    companion object {
        fun fromJson(o: JsonObject): GtEventConfig = GtEventConfig(
            eventId = req(o, "eventId"),
            eventName = req(o, "eventName"),
            eventDate = req(o, "eventDate"),
            eventDescription = GtOrgJson.optString(o, "eventDescription", ""),
            organizerUserId = req(o, "organizerUserId"),
            organizerFirstName = req(o, "organizerFirstName"),
            organizerLastName = req(o, "organizerLastName"),
            organizerEmail = req(o, "organizerEmail"),
            organizerPhone = req(o, "organizerPhone"),
            organizerVehicleType = GtOrgJson.optString(o, "organizerVehicleType", ""),
            hardwareId = req(o, "hardwareId"),
            deviceId = GtOrgJson.optString(o, "deviceId", ""),
            hardwareModel = GtOrgJson.optString(o, "hardwareModel", ""),
            firmwareVersion = GtOrgJson.optString(o, "firmwareVersion", ""),
            channelName = req(o, "channelName"),
            channelPsk = req(o, "channelPsk"),
            loraRegion = GtOrgJson.optString(o, "loraRegion", "US"),
            loraModemPreset = GtOrgJson.optString(o, "loraModemPreset", "LONG_FAST"),
            loraBandwidth = GtOrgJson.optInt(o, "loraBandwidth", 250),
            loraSpreadFactor = GtOrgJson.optInt(o, "loraSpreadFactor", 11),
            loraCodingRate = GtOrgJson.optInt(o, "loraCodingRate", 8),
            loraHopLimit = GtOrgJson.optInt(o, "loraHopLimit", 3),
            loraTxPower = GtOrgJson.optInt(o, "loraTxPower", 27),
            createdDate = req(o, "createdDate"),
            expirationDate = req(o, "expirationDate"),
            mapAreaNorth = GtOrgJson.optDouble(o, "mapAreaNorth", 0.0),
            mapAreaSouth = GtOrgJson.optDouble(o, "mapAreaSouth", 0.0),
            mapAreaEast = GtOrgJson.optDouble(o, "mapAreaEast", 0.0),
            mapAreaWest = GtOrgJson.optDouble(o, "mapAreaWest", 0.0),
            mapZoomDepth = GtOrgJson.optInt(o, "mapZoomDepth", 14))

        /** 2.7e createFromMaster: LoRa values from the master; an unreadable event date counts as [today]; expiry = event date + 30 days. */
        fun createFromMaster(master: GtMasterConfig, organizer: GtUser, hardwareId: String, deviceId: String, eventName: String,
                             eventDate: String, eventDescription: String, channelName: String, channelPsk: String,
                             today: String, newId: String): GtEventConfig {
            val evDate = try { GtIsoDate.parse(eventDate); eventDate } catch (e: Exception) { today }
            return GtEventConfig(
                eventId = newId, eventName = eventName.trim(), eventDate = eventDate, eventDescription = eventDescription.trim(),
                organizerUserId = organizer.userId, organizerFirstName = organizer.firstName, organizerLastName = organizer.lastName,
                organizerEmail = organizer.email, organizerPhone = organizer.cellPhone, organizerVehicleType = organizer.vehicleType,
                hardwareId = hardwareId, deviceId = deviceId, hardwareModel = master.hardwareModel, firmwareVersion = master.firmwareVersion,
                channelName = channelName.trim(), channelPsk = channelPsk, loraRegion = master.loraRegion, loraModemPreset = master.loraModemPreset,
                loraBandwidth = master.loraBandwidth, loraSpreadFactor = master.loraSpreadFactor, loraCodingRate = master.loraCodingRate,
                loraHopLimit = master.loraHopLimit, loraTxPower = master.loraTxPower, createdDate = today,
                expirationDate = GtIsoDays.plusDays(evDate, 30))
        }
    }
}

/** 2.7e ConvoyEventStore: filesDir/convoy_events. [list] = the folder's files in the platform's order (see the file header). */
class GtEventStore(private val fs: FileSystem, private val dir: Path, private val list: (Path) -> List<Path>?) {
    companion object { const val EVENTS_DIR = "convoy_events" }
    fun eventsDir(): Path = dir.also { fs.createDirectories(it) }
    fun fileName(event: GtEventConfig): String {
        val safeName = event.eventName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeDate = event.eventDate.replace("/", "-").replace(" ", "_")
        return "${event.eventId}_${safeName}_${safeDate}.json"
    }
    fun save(event: GtEventConfig) {
        val file = eventsDir() / fileName(event)
        file.parent?.let { fs.createDirectories(it) }
        fs.write(file) { writeUtf8(GtOrgJson.pretty(event.toJson(), 2)) }
    }
    fun loadAll(): List<GtEventConfig> =
        list(eventsDir())?.filter { it.name.substringAfterLast('.', "") == "json" }?.mapNotNull {
            try { GtEventConfig.fromJson(GtOrgJson.parse(fs.read(it) { readUtf8() }) as? JsonObject ?: throw IllegalArgumentException("not an object")) }
            catch (e: Exception) { null }
        } ?: emptyList()
}
