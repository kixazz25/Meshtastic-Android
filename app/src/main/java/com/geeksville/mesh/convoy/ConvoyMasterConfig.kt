package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * ConvoyMasterConfig — V2 master radio configuration
 *
 * Created ONCE by the developer (Fred) before the app ships.
 * Bundled as app/src/main/assets/master_config.json
 * Copied to C:/ConvoyProto/master_config.json on first install.
 * Restored from asset if missing on device.
 *
 * NO user in the shipped app can create or modify this file.
 * Every event config is derived from this master.
 * Only channel name and PSK change per event.
 */
data class ConvoyMasterConfig(
    val hardwareModel: String,
    val firmwareVersion: String,
    val pioEnv: String,
    // ── Device ────────────────────────────────────────────────────────────────
    val longName: String,
    val shortName: String,
    val nodeRole: String,
    val isManaged: Boolean,
    val serialEnabled: Boolean,
    // ── LoRa ──────────────────────────────────────────────────────────────────
    val loraRegion: String,
    val loraModemPreset: String,
    val loraBandwidth: Int,
    val loraSpreadFactor: Int,
    val loraCodingRate: Int,
    val loraHopLimit: Int,
    val loraTxEnabled: Boolean,
    val loraTxPower: Int,
    val loraChannelNum: Int,
    // ── Channel ───────────────────────────────────────────────────────────────
    val primaryChannelName: String,
    val primaryChannelPsk: String,
    val channelId: Int,
    val channelUplinkEnabled: Boolean,
    val channelDownlinkEnabled: Boolean,
    // ── Position ──────────────────────────────────────────────────────────────
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
    // ── Display ───────────────────────────────────────────────────────────────
    val displayUnits: Int,
    val screenTimeout: Int,
    val autoScreenBrightness: Boolean,
    val compassNorthTop: Boolean,
    // ── Module ────────────────────────────────────────────────────────────────
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
    // ── Metadata ──────────────────────────────────────────────────────────────
    val deviceProfileBase64: String,
    val capturedDate: String,
    val capturedFirmware: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hardwareModel",             hardwareModel)
        put("firmwareVersion",           firmwareVersion)
        put("pioEnv",                    pioEnv)
        put("longName",                  longName)
        put("shortName",                 shortName)
        put("nodeRole",                  nodeRole)
        put("isManaged",                 isManaged)
        put("serialEnabled",             serialEnabled)
        put("loraRegion",                loraRegion)
        put("loraModemPreset",           loraModemPreset)
        put("loraBandwidth",             loraBandwidth)
        put("loraSpreadFactor",          loraSpreadFactor)
        put("loraCodingRate",            loraCodingRate)
        put("loraHopLimit",              loraHopLimit)
        put("loraTxEnabled",             loraTxEnabled)
        put("loraTxPower",               loraTxPower)
        put("loraChannelNum",            loraChannelNum)
        put("primaryChannelName",        primaryChannelName)
        put("primaryChannelPsk",         primaryChannelPsk)
        put("channelId",                 channelId)
        put("channelUplinkEnabled",      channelUplinkEnabled)
        put("channelDownlinkEnabled",    channelDownlinkEnabled)
        put("gpsEnabled",                gpsEnabled)
        put("gpsMode",                   gpsMode)
        put("gpsUpdateSecs",             gpsUpdateSecs)
        put("gpsAttemptTime",            gpsAttemptTime)
        put("positionBroadcastSecs",     positionBroadcastSecs)
        put("smartPositionEnabled",      smartPositionEnabled)
        put("fixedPosition",             fixedPosition)
        put("positionFlags",             positionFlags)
        put("smartMinIntervalSecs",      smartMinIntervalSecs)
        put("smartMinDistanceMeters",    smartMinDistanceMeters)
        put("displayUnits",              displayUnits)
        put("screenTimeout",             screenTimeout)
        put("autoScreenBrightness",      autoScreenBrightness)
        put("compassNorthTop",           compassNorthTop)
        put("telemetryDeviceInterval",   telemetryDeviceInterval)
        put("telemetryEnvInterval",      telemetryEnvInterval)
        put("telemetryEnvEnabled",       telemetryEnvEnabled)
        put("mqttEnabled",               mqttEnabled)
        put("mqttAddress",               mqttAddress)
        put("mqttUsername",              mqttUsername)
        put("mqttEncryptionEnabled",     mqttEncryptionEnabled)
        put("mqttJsonEnabled",           mqttJsonEnabled)
        put("serialModuleEnabled",       serialModuleEnabled)
        put("serialBaud",                serialBaud)
        put("extNotificationEnabled",    extNotificationEnabled)
        put("extNotificationAlertMsg",   extNotificationAlertMsg)
        put("rangeTestEnabled",          rangeTestEnabled)
        put("storeForwardEnabled",       storeForwardEnabled)
        put("neighborInfoEnabled",       neighborInfoEnabled)
        put("detectionSensorEnabled",    detectionSensorEnabled)
        put("audioEnabled",              audioEnabled)
        put("deviceProfileBase64",       deviceProfileBase64)
        put("capturedDate",              capturedDate)
        put("capturedFirmware",          capturedFirmware)
    }
    
    companion object {
        private const val TAG          = "ConvoyMasterConfig"
        private const val ASSET_FILE   = "master_config.json"
        private const val DEVICE_FILE  = "master_config.json"

        fun fromJson(obj: JSONObject): ConvoyMasterConfig = ConvoyMasterConfig(
            hardwareModel          = obj.optString("hardwareModel", "Unknown"),
            firmwareVersion        = obj.optString("firmwareVersion", "Unknown"),
            pioEnv                 = obj.optString("pioEnv", ""),
            longName               = obj.optString("longName", ""),
            shortName              = obj.optString("shortName", ""),
            nodeRole               = obj.optString("nodeRole", "CLIENT"),
            isManaged              = obj.optBoolean("isManaged", false),
            serialEnabled          = obj.optBoolean("serialEnabled", false),
            loraRegion             = obj.optString("loraRegion", "US"),
            loraModemPreset        = obj.optString("loraModemPreset", "LONG_FAST"),
            loraBandwidth          = obj.optInt("loraBandwidth", 0),
            loraSpreadFactor       = obj.optInt("loraSpreadFactor", 0),
            loraCodingRate         = obj.optInt("loraCodingRate", 0),
            loraHopLimit           = obj.optInt("loraHopLimit", 3),
            loraTxEnabled          = obj.optBoolean("loraTxEnabled", true),
            loraTxPower            = obj.optInt("loraTxPower", 27),
            loraChannelNum         = obj.optInt("loraChannelNum", 0),
            primaryChannelName     = obj.optString("primaryChannelName", ""),
            primaryChannelPsk      = obj.optString("primaryChannelPsk", ""),
            channelId              = obj.optInt("channelId", 0),
            channelUplinkEnabled   = obj.optBoolean("channelUplinkEnabled", false),
            channelDownlinkEnabled = obj.optBoolean("channelDownlinkEnabled", false),
            gpsEnabled             = obj.optBoolean("gpsEnabled", true),
            gpsMode                = obj.optString("gpsMode", "ENABLED"),
            gpsUpdateSecs          = obj.optInt("gpsUpdateSecs", 1),
            gpsAttemptTime         = obj.optInt("gpsAttemptTime", 900),
            positionBroadcastSecs  = obj.optInt("positionBroadcastSecs", 5),
            smartPositionEnabled   = obj.optBoolean("smartPositionEnabled", true),
            fixedPosition          = obj.optBoolean("fixedPosition", false),
            positionFlags          = obj.optInt("positionFlags", 811),
            smartMinIntervalSecs   = obj.optInt("smartMinIntervalSecs", 3),
            smartMinDistanceMeters = obj.optInt("smartMinDistanceMeters", 10),
            displayUnits           = obj.optInt("displayUnits", 0),
            screenTimeout          = obj.optInt("screenTimeout", 0),
            autoScreenBrightness   = obj.optBoolean("autoScreenBrightness", false),
            compassNorthTop        = obj.optBoolean("compassNorthTop", false),
            telemetryDeviceInterval= obj.optInt("telemetryDeviceInterval", 0),
            telemetryEnvInterval   = obj.optInt("telemetryEnvInterval", 0),
            telemetryEnvEnabled    = obj.optBoolean("telemetryEnvEnabled", false),
            mqttEnabled            = obj.optBoolean("mqttEnabled", false),
            mqttAddress            = obj.optString("mqttAddress", ""),
            mqttUsername           = obj.optString("mqttUsername", ""),
            mqttEncryptionEnabled  = obj.optBoolean("mqttEncryptionEnabled", false),
            mqttJsonEnabled        = obj.optBoolean("mqttJsonEnabled", false),
            serialModuleEnabled    = obj.optBoolean("serialModuleEnabled", false),
            serialBaud             = obj.optInt("serialBaud", 0),
            extNotificationEnabled = obj.optBoolean("extNotificationEnabled", false),
            extNotificationAlertMsg= obj.optBoolean("extNotificationAlertMsg", false),
            rangeTestEnabled       = obj.optBoolean("rangeTestEnabled", false),
            storeForwardEnabled    = obj.optBoolean("storeForwardEnabled", false),
            neighborInfoEnabled    = obj.optBoolean("neighborInfoEnabled", false),
            detectionSensorEnabled = obj.optBoolean("detectionSensorEnabled", false),
            audioEnabled           = obj.optBoolean("audioEnabled", false),
            deviceProfileBase64    = obj.optString("deviceProfileBase64", ""),
            capturedDate           = obj.optString("capturedDate", ""),
            capturedFirmware       = obj.optString("capturedFirmware", "")
        )
        
        fun load(context: Context): ConvoyMasterConfig? {
            val deviceFile = File(context.filesDir, DEVICE_FILE)

            // Try device file first
            if (deviceFile.exists()) {
                return try {
                    fromJson(JSONObject(deviceFile.readText()))
                        .also { Log.i(TAG, "Master config loaded from device") }
                } catch (e: Exception) {
                    Log.w(TAG, "Device master config corrupt, restoring from asset", e)
                    restoreFromAsset(context)
                }
            }

            // Missing — restore from bundled asset
            Log.i(TAG, "Master config missing on device, restoring from asset")
            return restoreFromAsset(context)
        }

        /**
         * Copy master config from bundled asset to device storage.
         * Called on first install and whenever device file is missing.
         */
        private fun restoreFromAsset(context: Context): ConvoyMasterConfig? {
            return try {
                val json = context.assets.open(ASSET_FILE)
                    .bufferedReader().use { it.readText() }
                File(context.filesDir, DEVICE_FILE).also {
                    it.parentFile?.mkdirs()
                    it.writeText(json)
                }
                fromJson(JSONObject(json))
                    .also { Log.i(TAG, "Master config restored from asset") }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load master config from asset", e)
                null
            }
        }

        /**
         * Check if master config is present — device or asset.
         */
        fun exists(context: Context): Boolean {
            if (File(context.filesDir, DEVICE_FILE).exists()) return true
            return try {
                context.assets.open(ASSET_FILE).close()
                true
            } catch (e: Exception) { false }
        }
    }
}
