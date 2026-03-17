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
        val longName: String,              // node long name at snapshot time
        val primaryChannelName: String,    // primary channel name
        val primaryChannelPsk: String,     // AES-256 PSK base64
        val loraRegion: String,            // LoRa region
        val loraModemPreset: String,       // modem preset
        val loraBandwidth: Int,            // bandwidth
        val loraSpreadFactor: Int,         // spread factor
        val loraCodingRate: Int,           // coding rate
        val loraHopLimit: Int,             // hop limit
        val loraTxEnabled: Boolean,        // TX enabled
        val loraTxPower: Int,              // TX power dBm
        val deviceProfile: DeviceProfile?,
        val localConfig: LocalConfig?,
        val channelSet: ChannelSet?,
        val snapshotTime: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("hardwareId",          hardwareId)
            put("deviceId",            deviceId)
            put("hardwareModel",       hardwareModel)
            put("firmwareVersion",     firmwareVersion)
            put("pioEnv",              pioEnv)
            put("hasGPS",              hasGPS)
            put("hasWifi",             hasWifi)
            put("maxChannels",         maxChannels)
            put("snapshotTime",        snapshotTime)
            put("longName",            longName)
            put("primaryChannelName",  primaryChannelName)
            put("primaryChannelPsk",   primaryChannelPsk)
            put("loraRegion",          loraRegion)
            put("loraModemPreset",     loraModemPreset)
            put("loraBandwidth",       loraBandwidth)
            put("loraSpreadFactor",    loraSpreadFactor)
            put("loraCodingRate",      loraCodingRate)
            put("loraHopLimit",        loraHopLimit)
            put("loraTxEnabled",       loraTxEnabled)
            put("loraTxPower",         loraTxPower)
            // Full DeviceProfile as base64 protobuf — complete restore fidelity
            deviceProfile?.let {
                put("deviceProfileBase64",
                    Base64.encodeToString(it.encode(), Base64.NO_WRAP))
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
        longName: String = "",
        deviceProfile: DeviceProfile?,
        localConfig: LocalConfig?,
        channelSet: ChannelSet?
    ): RadioSnapshot {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        // Extract all readable fields at snapshot time
        val primaryChannel  = channelSet?.settings?.firstOrNull()
        val channelName     = primaryChannel?.name ?: ""
        val pskBase64       = primaryChannel?.psk?.let { psk ->
            if (psk.size > 0) Base64.encodeToString(psk.toByteArray(), Base64.NO_WRAP) else ""
        } ?: ""
        val lora            = localConfig?.lora
        return RadioSnapshot(
            hardwareId         = "!%08x".format(myNodeNum),
            deviceId           = deviceId ?: "",
            hardwareModel      = model ?: "Unknown",
            firmwareVersion    = firmwareVersion ?: "Unknown",
            pioEnv             = pioEnv ?: "",
            hasGPS             = hasGPS,
            hasWifi            = hasWifi,
            maxChannels        = maxChannels,
            longName           = longName,
            primaryChannelName = channelName,
            primaryChannelPsk  = pskBase64,
            loraRegion         = lora?.region?.name ?: "US",
            loraModemPreset    = lora?.modem_preset?.name ?: "LONG_FAST",
            loraBandwidth      = lora?.bandwidth ?: 0,
            loraSpreadFactor   = lora?.spread_factor ?: 0,
            loraCodingRate     = lora?.coding_rate ?: 0,
            loraHopLimit       = lora?.hop_limit ?: 3,
            loraTxEnabled      = lora?.tx_enabled ?: true,
            loraTxPower        = lora?.tx_power ?: 27,
            deviceProfile      = deviceProfile,
            localConfig        = localConfig,
            channelSet         = channelSet,
            snapshotTime       = LocalDateTime.now().format(fmt)
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
