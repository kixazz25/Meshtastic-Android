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
    val loraRegion: String,
    val loraModemPreset: String,
    val loraBandwidth: Int,
    val loraSpreadFactor: Int,
    val loraCodingRate: Int,
    val loraHopLimit: Int,
    val loraTxEnabled: Boolean,
    val loraTxPower: Int,
    val primaryChannelName: String,
    val deviceProfileBase64: String,   // full DeviceProfile proto — base64
    val capturedDate: String,
    val capturedFirmware: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hardwareModel",       hardwareModel)
        put("firmwareVersion",     firmwareVersion)
        put("pioEnv",              pioEnv)
        put("loraRegion",          loraRegion)
        put("loraModemPreset",     loraModemPreset)
        put("loraBandwidth",       loraBandwidth)
        put("loraSpreadFactor",    loraSpreadFactor)
        put("loraCodingRate",      loraCodingRate)
        put("loraHopLimit",        loraHopLimit)
        put("loraTxEnabled",       loraTxEnabled)
        put("loraTxPower",         loraTxPower)
        put("primaryChannelName",  primaryChannelName)
        put("deviceProfileBase64", deviceProfileBase64)
        put("capturedDate",        capturedDate)
        put("capturedFirmware",    capturedFirmware)
    }

    companion object {
        private const val TAG          = "ConvoyMasterConfig"
        private const val ASSET_FILE   = "master_config.json"
        private const val DEVICE_FILE  = "master_config.json"

        fun fromJson(obj: JSONObject) = ConvoyMasterConfig(
            hardwareModel      = obj.optString("hardwareModel", "Unknown"),
            firmwareVersion    = obj.optString("firmwareVersion", "Unknown"),
            pioEnv             = obj.optString("pioEnv", ""),
            loraRegion         = obj.optString("loraRegion", "US"),
            loraModemPreset    = obj.optString("loraModemPreset", "LONG_FAST"),
            loraBandwidth      = obj.optInt("loraBandwidth", 250),
            loraSpreadFactor   = obj.optInt("loraSpreadFactor", 11),
            loraCodingRate     = obj.optInt("loraCodingRate", 8),
            loraHopLimit       = obj.optInt("loraHopLimit", 3),
            loraTxEnabled      = obj.optBoolean("loraTxEnabled", true),
            loraTxPower        = obj.optInt("loraTxPower", 27),
            primaryChannelName = obj.optString("primaryChannelName", ""),
            deviceProfileBase64= obj.optString("deviceProfileBase64", ""),
            capturedDate       = obj.optString("capturedDate", ""),
            capturedFirmware   = obj.optString("capturedFirmware", "")
        )

        /**
         * Load master config — device file first, falls back to bundled asset.
         * Restores from asset if device file is missing.
         * Returns null only if asset is also missing (should never happen in release).
         */
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
