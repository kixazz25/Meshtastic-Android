package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtStorageAndroid
import com.grouptrack.shared.config.GtMasterConfig
import com.grouptrack.shared.config.GtMasterConfigStore
import com.grouptrack.shared.json.GtOrgJson
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import org.json.JSONObject

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
 *
 * SWITCH17G2D-2026-11-12 (2.8 step 17, G2d): front doors to the shared GtMasterConfig / GtMasterConfigStore (certified at 0ed882ae7: Droid 69 SAME,
 * 0 DIFF; iPad = Droid). Every field, name and type kept; the same asset, the same filesDir/master_config.json, the same log lines.
 * toJson / fromJson keep their org.json JSONObject for the callers (the shared code writes and reads the text).
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
    val positionPrecision: Int,
    val channelIsMuted: Boolean,
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
    fun toJson(): JSONObject = JSONObject(GtOrgJson.compact(toShared().toJson()))

    fun toShared(): GtMasterConfig = GtMasterConfig(hardwareModel, firmwareVersion, pioEnv, longName, shortName, nodeRole, isManaged, serialEnabled, loraRegion, loraModemPreset, loraBandwidth, loraSpreadFactor, loraCodingRate, loraHopLimit, loraTxEnabled, loraTxPower, loraChannelNum, primaryChannelName, primaryChannelPsk, channelId, channelUplinkEnabled, channelDownlinkEnabled, positionPrecision, channelIsMuted, gpsEnabled, gpsMode, gpsUpdateSecs, gpsAttemptTime, positionBroadcastSecs, smartPositionEnabled, fixedPosition, positionFlags, smartMinIntervalSecs, smartMinDistanceMeters, displayUnits, screenTimeout, autoScreenBrightness, compassNorthTop, telemetryDeviceInterval, telemetryEnvInterval, telemetryEnvEnabled, mqttEnabled, mqttAddress, mqttUsername, mqttEncryptionEnabled, mqttJsonEnabled, serialModuleEnabled, serialBaud, extNotificationEnabled, extNotificationAlertMsg, rangeTestEnabled, storeForwardEnabled, neighborInfoEnabled, detectionSensorEnabled, audioEnabled, deviceProfileBase64, capturedDate, capturedFirmware)

    companion object {
        private const val ASSET_FILE   = "master_config.json"

        fun fromShared(g: GtMasterConfig): ConvoyMasterConfig = ConvoyMasterConfig(g.hardwareModel, g.firmwareVersion, g.pioEnv, g.longName, g.shortName, g.nodeRole, g.isManaged, g.serialEnabled, g.loraRegion, g.loraModemPreset, g.loraBandwidth, g.loraSpreadFactor, g.loraCodingRate, g.loraHopLimit, g.loraTxEnabled, g.loraTxPower, g.loraChannelNum, g.primaryChannelName, g.primaryChannelPsk, g.channelId, g.channelUplinkEnabled, g.channelDownlinkEnabled, g.positionPrecision, g.channelIsMuted, g.gpsEnabled, g.gpsMode, g.gpsUpdateSecs, g.gpsAttemptTime, g.positionBroadcastSecs, g.smartPositionEnabled, g.fixedPosition, g.positionFlags, g.smartMinIntervalSecs, g.smartMinDistanceMeters, g.displayUnits, g.screenTimeout, g.autoScreenBrightness, g.compassNorthTop, g.telemetryDeviceInterval, g.telemetryEnvInterval, g.telemetryEnvEnabled, g.mqttEnabled, g.mqttAddress, g.mqttUsername, g.mqttEncryptionEnabled, g.mqttJsonEnabled, g.serialModuleEnabled, g.serialBaud, g.extNotificationEnabled, g.extNotificationAlertMsg, g.rangeTestEnabled, g.storeForwardEnabled, g.neighborInfoEnabled, g.detectionSensorEnabled, g.audioEnabled, g.deviceProfileBase64, g.capturedDate, g.capturedFirmware)

        fun fromJson(obj: JSONObject): ConvoyMasterConfig = fromShared(GtMasterConfig.fromJson(GtOrgJson.parse(obj.toString()) as JsonObject))

        private fun store(context: Context) = GtMasterConfigStore(FileSystem.SYSTEM, GtStorageAndroid.of(context).filesDir() / GtMasterConfig.DEVICE_FILE) {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }

        /** Device file first; missing, older than CONFIG_VERSION or corrupt -> restored from the bundled asset. */
        fun load(context: Context): ConvoyMasterConfig? = store(context).load()?.let { fromShared(it) }

        /** Check if master config is present — device or asset. */
        fun exists(context: Context): Boolean = store(context).exists()
    }
}
