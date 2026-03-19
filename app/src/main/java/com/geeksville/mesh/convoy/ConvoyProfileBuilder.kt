package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Base64
import android.util.Log
import okio.ByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import java.io.File

/**
 * ConvoyProfileBuilder
 *
 * Builds a Meshtastic DeviceProfile binary (.cfg) from a
 * ConvoyMasterConfig (WorkingConfig JSON) with no radio required.
 *
 * Input:  ConvoyMasterConfig  — fully resolved by rules engine
 * Output: DeviceProfile binary — identical to what a radio would export
 *
 * Test: feed master_config.json → compare output against master.cfg
 */
object ConvoyProfileBuilder {

    private const val TAG = "ConvoyProfileBuilder"

    /**
     * Build a DeviceProfile from a fully resolved WorkingConfig.
     */
    fun buildProfile(config: ConvoyMasterConfig): DeviceProfile {

        // ── LoRa Config ───────────────────────────────────────────────────────
        val loraRegion = try {
            Config.LoRaConfig.RegionCode.valueOf(config.loraRegion)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown LoRa region ${config.loraRegion}, defaulting to US")
            Config.LoRaConfig.RegionCode.US
        }

        val modemPreset = try {
            Config.LoRaConfig.ModemPreset.valueOf(config.loraModemPreset)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown modem preset ${config.loraModemPreset}, defaulting to LONG_FAST")
            Config.LoRaConfig.ModemPreset.LONG_FAST
        }

        val loraConfig = Config.LoRaConfig(
            region       = loraRegion,
            modem_preset = modemPreset,
            bandwidth    = config.loraBandwidth,
            spread_factor = config.loraSpreadFactor,
            coding_rate  = config.loraCodingRate,
            hop_limit    = config.loraHopLimit,
            tx_enabled   = config.loraTxEnabled,
            tx_power     = config.loraTxPower,
            channel_num  = config.loraChannelNum,
            use_preset   = true
        )

        // ── Device Config ─────────────────────────────────────────────────────
        val nodeRole = try {
            Config.DeviceConfig.Role.valueOf(config.nodeRole)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown node role ${config.nodeRole}, defaulting to CLIENT")
            Config.DeviceConfig.Role.CLIENT
        }

        val deviceConfig = Config.DeviceConfig(
            role       = nodeRole,
            is_managed = config.isManaged
        )

        // ── Position Config ───────────────────────────────────────────────────
        val gpsMode = try {
            Config.PositionConfig.GpsMode.valueOf(config.gpsMode)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown GPS mode ${config.gpsMode}, defaulting to ENABLED")
            Config.PositionConfig.GpsMode.ENABLED
        }

        val positionConfig = Config.PositionConfig(
            gps_update_interval                  = config.gpsUpdateSecs,
            gps_attempt_time                     = config.gpsAttemptTime,
            position_broadcast_secs              = config.positionBroadcastSecs,
            position_broadcast_smart_enabled     = config.smartPositionEnabled,
            fixed_position                       = config.fixedPosition,
            position_flags                       = config.positionFlags,
            broadcast_smart_minimum_distance     = config.smartMinDistanceMeters,
            broadcast_smart_minimum_interval_secs = config.smartMinIntervalSecs,
            gps_mode                             = gpsMode
        )

        // ── Display Config ────────────────────────────────────────────────────
        val displayUnits = Config.DisplayConfig.DisplayUnits.fromValue(config.displayUnits)
            ?: Config.DisplayConfig.DisplayUnits.METRIC

        val displayConfig = Config.DisplayConfig(
            screen_on_secs    = config.screenTimeout,
            compass_north_top = config.compassNorthTop,
            units             = displayUnits
        )

        // ── LocalConfig ───────────────────────────────────────────────────────
        val localConfig = LocalConfig(
            lora     = loraConfig,
            device   = deviceConfig,
            position = positionConfig,
            display  = displayConfig
        )

        // ── LocalModuleConfig ─────────────────────────────────────────────────
        val moduleConfig = LocalModuleConfig(
            telemetry = ModuleConfig.TelemetryConfig(
                device_update_interval          = config.telemetryDeviceInterval,
                environment_update_interval     = config.telemetryEnvInterval,
                environment_measurement_enabled = config.telemetryEnvEnabled
            ),
            mqtt = ModuleConfig.MQTTConfig(
                enabled            = config.mqttEnabled,
                address            = config.mqttAddress,
                username           = config.mqttUsername,
                encryption_enabled = config.mqttEncryptionEnabled,
                json_enabled       = config.mqttJsonEnabled
            ),
            serial = ModuleConfig.SerialConfig(
                enabled = config.serialModuleEnabled
            ),
            external_notification = ModuleConfig.ExternalNotificationConfig(
                enabled       = config.extNotificationEnabled,
                alert_message = config.extNotificationAlertMsg
            ),
            range_test    = ModuleConfig.RangeTestConfig(
                enabled = config.rangeTestEnabled
            ),
            store_forward = ModuleConfig.StoreForwardConfig(
                enabled = config.storeForwardEnabled
            ),
            neighbor_info = ModuleConfig.NeighborInfoConfig(
                enabled = config.neighborInfoEnabled
            ),
            detection_sensor = ModuleConfig.DetectionSensorConfig(
                enabled = config.detectionSensorEnabled
            ),
            audio = ModuleConfig.AudioConfig(
                codec2_enabled = config.audioEnabled
            )
        )

        // ── Channel ───────────────────────────────────────────────────────────
        val pskBytes = try {
            ByteString.of(*Base64.decode(config.primaryChannelPsk, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode PSK, using empty")
            ByteString.EMPTY
        }

        val channelSettings = ChannelSettings(
            name             = config.primaryChannelName,
            psk              = pskBytes,
            uplink_enabled   = config.channelUplinkEnabled,
            downlink_enabled = config.channelDownlinkEnabled
        )

        // ── ChannelSet -> channel_url
        val channelSet = org.meshtastic.proto.ChannelSet(
            settings    = listOf(channelSettings),
            lora_config = loraConfig
        )
        val channelUrl = "https://meshtastic.org/e/#" +
            android.util.Base64.encodeToString(
                channelSet.encode(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
        // ── DeviceProfile ─────────────────────────────────────────────────────
        val profile = DeviceProfile(
            long_name     = config.longName.ifEmpty { null },
            short_name    = null,   // always stripped — riders keep own callsign
            channel_url   = channelUrl,
            config        = localConfig,
            module_config = moduleConfig
        )

        Log.i(TAG, "DeviceProfile built — region=${config.loraRegion} " +
                "preset=${config.loraModemPreset} channel=${config.primaryChannelName}")

        return profile
    }

    /**
     * Build binary bytes from WorkingConfig.
     * Direct input to InstallProfileUseCase.
     */
    fun buildBytes(config: ConvoyMasterConfig): ByteArray = buildProfile(config).encode()

    /**
     * Build and save to file.
     * Returns the saved file path.
     */
    fun buildAndSave(
        context: Context,
        config: ConvoyMasterConfig,
        fileName: String
    ): Result<String> = runCatching {
        val outFile = File(context.filesDir, "convoy_profiles/$fileName")
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(buildBytes(config))
        Log.i(TAG, "Profile saved: ${outFile.absolutePath}")
        outFile.absolutePath
    }

    /**
     * TEST — compare generated binary against master.cfg field by field.
     * Call from ConvoySettingsPanel (hidden developer menu).
     * Check logcat tag ConvoyProfileBuilder for results.
     */
    fun testAgainstMasterCfg(context: Context): Boolean {
        Log.i(TAG, "=== ConvoyProfileBuilder TEST START ===")

        // Load master_config.json
        val masterConfig = ConvoyMasterConfig.load(context) ?: run {
            Log.e(TAG, "FAIL — could not load master_config.json")
            return false
        }
        Log.i(TAG, "master_config.json loaded OK")

        // Build binary from JSON
        val generatedBytes = try {
            buildBytes(masterConfig)
        } catch (e: Exception) {
            Log.e(TAG, "FAIL — builder threw: ${e.message}")
            return false
        }
        Log.i(TAG, "Generated: ${generatedBytes.size} bytes")

        // Load master.cfg
        val masterBytes = try {
            context.assets.open("master.cfg").readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "FAIL — could not load master.cfg: ${e.message}")
            return false
        }
        Log.i(TAG, "master.cfg: ${masterBytes.size} bytes")

        // Parse both
        val generated = DeviceProfile.ADAPTER.decode(generatedBytes)
        val master    = DeviceProfile.ADAPTER.decode(masterBytes)

        var pass = true

        fun <T> check(name: String, gen: T, mst: T) {
            if (gen == mst) Log.i(TAG, "  PASS  $name = $gen")
            else { Log.e(TAG, "  FAIL  $name: generated=$gen  master=$mst"); pass = false }
        }

        // LoRa
        check("lora.region",        generated.config?.lora?.region,        master.config?.lora?.region)
        check("lora.modem_preset",  generated.config?.lora?.modem_preset,  master.config?.lora?.modem_preset)
        check("lora.hop_limit",     generated.config?.lora?.hop_limit,     master.config?.lora?.hop_limit)
        check("lora.tx_enabled",    generated.config?.lora?.tx_enabled,    master.config?.lora?.tx_enabled)
        check("lora.tx_power",      generated.config?.lora?.tx_power,      master.config?.lora?.tx_power)
        check("lora.channel_num",   generated.config?.lora?.channel_num,   master.config?.lora?.channel_num)
        check("lora.use_preset",    generated.config?.lora?.use_preset,    master.config?.lora?.use_preset)

        // Device
        check("device.role",        generated.config?.device?.role,        master.config?.device?.role)
        check("device.is_managed",  generated.config?.device?.is_managed,  master.config?.device?.is_managed)

        // Position
        check("position.broadcast_secs",    generated.config?.position?.position_broadcast_secs,          master.config?.position?.position_broadcast_secs)
        check("position.smart_enabled",     generated.config?.position?.position_broadcast_smart_enabled, master.config?.position?.position_broadcast_smart_enabled)
        check("position.gps_mode",          generated.config?.position?.gps_mode,                         master.config?.position?.gps_mode)
        check("position.flags",             generated.config?.position?.position_flags,                   master.config?.position?.position_flags)
        check("position.smart_distance",    generated.config?.position?.broadcast_smart_minimum_distance, master.config?.position?.broadcast_smart_minimum_distance)
        check("position.smart_interval",    generated.config?.position?.broadcast_smart_minimum_interval_secs, master.config?.position?.broadcast_smart_minimum_interval_secs)

        // Display
        check("display.screen_timeout",    generated.config?.display?.screen_on_secs,    master.config?.display?.screen_on_secs)
        check("display.compass_north_top", generated.config?.display?.compass_north_top, master.config?.display?.compass_north_top)
        check("display.units",             generated.config?.display?.units,             master.config?.display?.units)

        // Names
        check("long_name",  generated.long_name,  master.long_name)
        check("short_name", generated.short_name, master.short_name)

        if (pass) Log.i(TAG, "=== TEST PASSED ===")
        else      Log.e(TAG, "=== TEST FAILED — see mismatches above ===")

        return pass
    }

    /**
     * Build a DeviceProfile from a WorkingConfig (rules engine output).
     * This is the primary production path — WorkingConfig is the resolved
     * four-source merge output from the rules engine.
     */
    fun buildProfile(wconfig: WorkingConfig): DeviceProfile {
        // Convert WorkingConfig to ConvoyMasterConfig and delegate
        val config = ConvoyMasterConfig(
            hardwareModel          = "",
            firmwareVersion        = "",
            pioEnv                 = "",
            longName               = wconfig.longName,
            shortName              = wconfig.shortName,
            nodeRole               = wconfig.nodeRole,
            isManaged              = wconfig.isManaged,
            serialEnabled          = wconfig.serialEnabled,
            loraRegion             = wconfig.loraRegion,
            loraModemPreset        = wconfig.loraModemPreset,
            loraBandwidth          = wconfig.loraBandwidth,
            loraSpreadFactor       = wconfig.loraSpreadFactor,
            loraCodingRate         = wconfig.loraCodingRate,
            loraHopLimit           = wconfig.loraHopLimit,
            loraTxEnabled          = wconfig.loraTxEnabled,
            loraTxPower            = wconfig.loraTxPower,
            loraChannelNum         = wconfig.loraChannelNum,
            primaryChannelName     = wconfig.channelName,
            primaryChannelPsk      = wconfig.channelPsk,
            channelId              = wconfig.channelId,
            channelUplinkEnabled   = wconfig.channelUplinkEnabled,
            channelDownlinkEnabled = wconfig.channelDownlinkEnabled,
            gpsEnabled             = wconfig.gpsEnabled,
            gpsMode                = wconfig.gpsMode,
            gpsUpdateSecs          = wconfig.gpsUpdateSecs,
            gpsAttemptTime         = wconfig.gpsAttemptTime,
            positionBroadcastSecs  = wconfig.positionBroadcastSecs,
            smartPositionEnabled   = wconfig.smartPositionEnabled,
            fixedPosition          = wconfig.fixedPosition,
            positionFlags          = wconfig.positionFlags,
            smartMinIntervalSecs   = wconfig.smartMinIntervalSecs,
            smartMinDistanceMeters = wconfig.smartMinDistanceMeters,
            displayUnits           = wconfig.displayUnits,
            screenTimeout          = wconfig.screenTimeout,
            autoScreenBrightness   = wconfig.autoScreenBrightness,
            compassNorthTop        = wconfig.compassNorthTop,
            telemetryDeviceInterval= wconfig.telemetryDeviceInterval,
            telemetryEnvInterval   = wconfig.telemetryEnvInterval,
            telemetryEnvEnabled    = wconfig.telemetryEnvEnabled,
            mqttEnabled            = wconfig.mqttEnabled,
            mqttAddress            = wconfig.mqttAddress,
            mqttUsername           = wconfig.mqttUsername,
            mqttEncryptionEnabled  = wconfig.mqttEncryptionEnabled,
            mqttJsonEnabled        = wconfig.mqttJsonEnabled,
            serialModuleEnabled    = wconfig.serialModuleEnabled,
            serialBaud             = wconfig.serialBaud,
            extNotificationEnabled = wconfig.extNotificationEnabled,
            extNotificationAlertMsg= wconfig.extNotificationAlertMsg,
            rangeTestEnabled       = wconfig.rangeTestEnabled,
            storeForwardEnabled    = wconfig.storeForwardEnabled,
            neighborInfoEnabled    = wconfig.neighborInfoEnabled,
            detectionSensorEnabled = wconfig.detectionSensorEnabled,
            audioEnabled           = wconfig.audioEnabled,
            deviceProfileBase64    = "",
            capturedDate           = "",
            capturedFirmware       = ""
        )
        return buildProfile(config)
    }
}
