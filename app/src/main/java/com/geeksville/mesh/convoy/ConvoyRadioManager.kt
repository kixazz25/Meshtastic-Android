package com.geeksville.mesh.convoy

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.ChannelSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ConvoyRadioManager — V2 radio read / backup / write
 *
 * Read:   Collects current DeviceProfile + MyNodeInfo snapshot
 * Backup: Full fidelity JSON — firmware, hardware, all config, all channels
 *         Stored at context.filesDir/convoy_backups/[hardwareId]/[label]_[datetime].json
 * Write:  Applies convoy channel + master LoRa config to paired radio
 *         Called via ChannelViewModel.setChannels() and setConfig()
 *
 * Radio write sequence:
 *   1. Verify device is connected
 *   2. Read current config snapshot
 *   3. Save full backup
 *   4. Write new channel (name + PSK from event config)
 *   5. Confirm write acknowledged
 *
 * Nobody configures radios. Master config is the single source.
 * Only channel name and PSK change per event.
 */
object ConvoyRadioManager {

    private const val TAG          = "ConvoyRadioManager"
    private const val BACKUPS_DIR = "convoy_backups"

    // ── Full radio snapshot ───────────────────────────────────────────────────
    data class RadioSnapshot(
        val hardwareId: String,
        val deviceId: String,
        val hardwareModel: String,
        val firmwareVersion: String,
        val pioEnv: String,
        val hasGPS: Boolean,
        val hasWifi: Boolean,
        val maxChannels: Int,
        val deviceProfile: DeviceProfile?,
        val localConfig: LocalConfig?,
        val channelSet: ChannelSet?,
        val snapshotTime: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("hardwareId",      hardwareId)
            put("deviceId",        deviceId)
            put("hardwareModel",   hardwareModel)
            put("firmwareVersion", firmwareVersion)
            put("pioEnv",          pioEnv)
            put("hasGPS",          hasGPS)
            put("hasWifi",         hasWifi)
            put("maxChannels",     maxChannels)
            put("snapshotTime",    snapshotTime)
            // Full DeviceProfile as base64 protobuf — complete restore fidelity
            deviceProfile?.let {
                put("deviceProfileBase64",
                    Base64.encodeToString(it.encode(), Base64.NO_WRAP))
            }
            // Human-readable fields for quick troubleshooting reference
            channelSet?.settings?.firstOrNull()?.let {
                put("primaryChannelName", it.name)
            }
            localConfig?.lora?.let { lora ->
                put("loraRegion",      lora.region?.name ?: "UNSET")
                put("loraModemPreset", lora.modem_preset?.name ?: "UNSET")
                put("loraTxPower",     lora.tx_power)
                put("loraHopLimit",    lora.hop_limit)
            }
        }
    }

    // ── Build snapshot from ViewModel state ───────────────────────────────────
    fun buildSnapshot(
        myNodeNum: Int,
        deviceId: String?,
        model: String?,
        firmwareVersion: String?,
        pioEnv: String?,
        hasGPS: Boolean,
        hasWifi: Boolean,
        maxChannels: Int,
        deviceProfile: DeviceProfile?,
        localConfig: LocalConfig?,
        channelSet: ChannelSet?
    ): RadioSnapshot {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return RadioSnapshot(
            hardwareId      = "!%08x".format(myNodeNum),
            deviceId        = deviceId ?: "",
            hardwareModel   = model ?: "Unknown",
            firmwareVersion = firmwareVersion ?: "Unknown",
            pioEnv          = pioEnv ?: "",
            hasGPS          = hasGPS,
            hasWifi         = hasWifi,
            maxChannels     = maxChannels,
            deviceProfile   = deviceProfile,
            localConfig     = localConfig,
            channelSet      = channelSet,
            snapshotTime    = LocalDateTime.now().format(fmt)
        )
    }

    // ── Save backup ───────────────────────────────────────────────────────────
    fun saveBackup(context: android.content.Context, snapshot: RadioSnapshot, label: String = "pre_convoy"): String {
        val fmt      = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val ts       = LocalDateTime.now().format(fmt)
        val safeName = label.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir      = java.io.File(context.filesDir, "$BACKUPS_DIR/${snapshot.hardwareId}").also { it.mkdirs() }
        val file     = java.io.File(dir, "${safeName}_${ts}.json")
        file.writeText(snapshot.toJson().toString(2))
        Log.i(TAG, "Backup saved: ${file.absolutePath}")
        return file.absolutePath
    }

    // ── List backups for a hardware ID ────────────────────────────────────────
    fun listBackups(context: android.content.Context, hardwareId: String): List<java.io.File> {
        return java.io.File(context.filesDir, "$BACKUPS_DIR/$hardwareId")
            .takeIf { it.exists() }
            ?.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    // ── Validate snapshot has required data for write ─────────────────────────
    fun canWrite(snapshot: RadioSnapshot): Boolean =
        snapshot.hardwareId.isNotBlank() &&
        snapshot.hardwareId != "!00000000" &&
        snapshot.deviceProfile != null

    // ── Result codes ──────────────────────────────────────────────────────────
    enum class RadioOpResult {
        SUCCESS,
        NO_DEVICE,
        NO_MASTER_CONFIG,
        BACKUP_FAILED,
        WRITE_FAILED
    }
}
