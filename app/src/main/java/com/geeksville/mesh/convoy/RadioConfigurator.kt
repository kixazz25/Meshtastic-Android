package com.geeksville.mesh.convoy

import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile

/*
 * RADIOCFG-2026-09-25 (GroupTrack 2.7) -- the new radio configurator, part 1: the OVERLAY BUILDER.
 *
 * Model: Natak's radio apply (V3_OS meshtastic_api.py), plus the reporting group proven necessary on 09-25.
 *   retrieve ALL values (a DeviceProfile) -> replace ONLY the managed fields -> write in Nathan's groups ->
 *   retrieve again -> verify the managed fields -> save the result as a backup named after the config applied.
 *
 * MANAGED = Nathan's eleven (owner long name, region, preset, frequency slot, hop limit, TX power, role,
 *   channel name, key; psk_random is implicitly off because the key is explicit; the short name is never written)
 *   + the REPORTING group a radio needs to report on its own (09-25: a radio with all eleven correct went silent
 *   for a night on a once-a-day broadcast interval): broadcast interval, smart position + its interval and
 *   distance, GPS mode, GPS update interval, and transmit enabled.
 * Everything else on the radio is left exactly as retrieved; differences are REPORTED, never written.
 *
 * This file is pure logic: no radio, no Android. It never builds a partial section: every section it returns is
 * the radio's own retrieved section with only managed fields changed (a Meshtastic config write REPLACES the whole
 * section -- the Meshtastic tool reads the section before writing for the same reason).
 * Scope: radios GroupTrack configures (T1000-E and app-attached radios). Nucleus radios are Natak's.
 */

/** The reporting group: what a radio needs to send its own position. */
data class ReportingValues(
    val broadcastSecs: Int,
    val smartEnabled: Boolean,
    val smartMinIntervalSecs: Int,
    val smartMinDistanceMeters: Int,
    val gpsMode: Config.PositionConfig.GpsMode,
    val gpsUpdateSecs: Int,
    val txEnabled: Boolean,
)

/** Every value the configurator manages. All required: a target without one of them is not a usable target. */
data class ManagedValues(
    val ownerLongName: String,
    val region: Config.LoRaConfig.RegionCode,
    val preset: Config.LoRaConfig.ModemPreset,
    val frequencySlot: Int,
    val hopLimit: Int,
    val txPower: Int,
    val role: Config.DeviceConfig.Role,
    val channelName: String,
    val key: ByteString,
    val reporting: ReportingValues,
)

/**
 * The writes for one apply, in Nathan's groups. A null member means "already correct on the radio -> that write
 * is SKIPPED" (Nathan skips unchanged groups the same way).
 *   group 1a: owner       group 1b: lora + device (one settings transaction)
 *   group 2:  channel     group 3:  position
 */
class ConfigPlan(
    val ownerLongName: String?, // CODE RULE 1: null = unchanged -> group 1a skipped
    val lora: Config.LoRaConfig?, // null = unchanged
    val device: Config.DeviceConfig?, // null = unchanged
    val channel: Channel?, // null = unchanged -> group 2 skipped
    val position: Config.PositionConfig?, // null = unchanged -> group 3 skipped
    val changes: List<String>, // "field: old -> new", managed fields only
) {
    val isEmpty: Boolean get() = changes.isEmpty()
}

data class FieldCheck(val field: String, val expected: String, val actual: String) {
    val ok: Boolean get() = expected == actual
}

object RadioConfigurator {

    // ---------------------------------------------------------------- targets

    /**
     * A ride (or the GroupTrack default, which has the same layout): network.mesh from the ride; the reporting group
     * from the DEFAULT's network.standalone (the default is the base under every ride); the owner is the rider's
     * callsign from their profile.
     */
    fun fromRideFile(ride: JSONObject, default: JSONObject, callsign: String): ManagedValues {
        require(callsign.isNotBlank()) { "no callsign -- the rider profile comes first" }
        val mesh = ride.getJSONObject("network").getJSONObject("mesh")
        val st = default.getJSONObject("network").getJSONObject("standalone")
        return ManagedValues(
            ownerLongName = callsign.trim(),
            region = Config.LoRaConfig.RegionCode.valueOf(mesh.getString("region")),
            preset = Config.LoRaConfig.ModemPreset.valueOf(mesh.getString("preset")),
            frequencySlot = mesh.getInt("frequencySlot"),
            hopLimit = mesh.getInt("hopLimit"),
            txPower = mesh.getInt("txPower"),
            role = Config.DeviceConfig.Role.valueOf(mesh.getString("role")),
            channelName = mesh.getString("channelName"),
            key = mesh.getString("key").decodeBase64() ?: throw IllegalArgumentException("key is not base64"),
            reporting = ReportingValues(
                broadcastSecs = st.getInt("positionBroadcastSecs"),
                smartEnabled = st.getBoolean("smartPositionEnabled"),
                smartMinIntervalSecs = st.getInt("smartMinIntervalSecs"),
                smartMinDistanceMeters = st.getInt("smartMinDistanceMeters"),
                gpsMode = Config.PositionConfig.GpsMode.valueOf(st.getString("gpsMode")),
                gpsUpdateSecs = st.getInt("gpsUpdateSecs"),
                txEnabled = st.getBoolean("txEnabled"),
            ),
        )
    }

    /** A saved backup as a target (reapply): its managed values, taken from its own sections. */
    fun fromProfile(p: DeviceProfile): ManagedValues {
        val lora = requireNotNull(p.config?.lora) { "backup has no lora section" }
        val device = requireNotNull(p.config?.device) { "backup has no device section" }
        val pos = requireNotNull(p.config?.position) { "backup has no position section" }
        val primary = primaryChannel(p)
        return ManagedValues(
            // RADIOCFGFIX-2026-09-25: long_name is nullable in the generated class -- refuse, never default.
            ownerLongName = requireNotNull(p.long_name?.takeIf { it.isNotBlank() }) { "backup has no owner name" },
            region = lora.region, preset = lora.modem_preset, frequencySlot = lora.channel_num,
            hopLimit = lora.hop_limit, txPower = lora.tx_power, role = device.role,
            channelName = primary.name, key = primary.psk,
            reporting = ReportingValues(
                pos.position_broadcast_secs, pos.position_broadcast_smart_enabled,
                pos.broadcast_smart_minimum_interval_secs, pos.broadcast_smart_minimum_distance,
                pos.gps_mode, pos.gps_update_interval, lora.tx_enabled,
            ),
        )
    }

    // ---------------------------------------------------------------- the overlay

    /** Build the writes: the radio's own sections with ONLY managed fields replaced; unchanged groups skipped. */
    fun build(current: DeviceProfile, t: ManagedValues): ConfigPlan {
        val lora = requireNotNull(current.config?.lora) { "retrieve incomplete: no lora section -- nothing written" }
        val device = requireNotNull(current.config?.device) { "retrieve incomplete: no device section -- nothing written" }
        val pos = requireNotNull(current.config?.position) { "retrieve incomplete: no position section -- nothing written" }
        val primary = primaryChannel(current)
        val changes = mutableListOf<String>()
        fun <T> note(field: String, old: T, new: T) { if (old != new) changes += "$field: $old -> $new" }

        val owner = if (current.long_name != t.ownerLongName) t.ownerLongName else null
        note("owner", current.long_name, t.ownerLongName)

        val newLora = lora.copy(
            use_preset = true, modem_preset = t.preset, region = t.region, channel_num = t.frequencySlot,
            hop_limit = t.hopLimit, tx_power = t.txPower, tx_enabled = t.reporting.txEnabled,
        )
        note("use_preset", lora.use_preset, true); note("region", lora.region, t.region)
        note("preset", lora.modem_preset, t.preset); note("frequency slot", lora.channel_num, t.frequencySlot)
        note("hop limit", lora.hop_limit, t.hopLimit); note("tx power", lora.tx_power, t.txPower)
        note("tx enabled", lora.tx_enabled, t.reporting.txEnabled)

        val newDevice = device.copy(role = t.role)
        note("role", device.role, t.role)

        val newPrimary = primary.copy(name = t.channelName, psk = t.key)
        note("channel name", primary.name, t.channelName)
        if (primary.psk != t.key) changes += "channel key: changed"

        val r = t.reporting
        val newPos = pos.copy(
            position_broadcast_secs = r.broadcastSecs, position_broadcast_smart_enabled = r.smartEnabled,
            broadcast_smart_minimum_interval_secs = r.smartMinIntervalSecs,
            broadcast_smart_minimum_distance = r.smartMinDistanceMeters,
            gps_mode = r.gpsMode, gps_update_interval = r.gpsUpdateSecs,
        )
        note("broadcast interval", pos.position_broadcast_secs, r.broadcastSecs)
        note("smart position", pos.position_broadcast_smart_enabled, r.smartEnabled)
        note("smart interval", pos.broadcast_smart_minimum_interval_secs, r.smartMinIntervalSecs)
        note("smart distance", pos.broadcast_smart_minimum_distance, r.smartMinDistanceMeters)
        note("gps mode", pos.gps_mode, r.gpsMode); note("gps update", pos.gps_update_interval, r.gpsUpdateSecs)

        return ConfigPlan(
            ownerLongName = owner,
            lora = newLora.takeIf { it != lora },
            device = newDevice.takeIf { it != device },
            channel = if (newPrimary != primary) Channel(index = 0, settings = newPrimary, role = Channel.Role.PRIMARY) else null,
            position = newPos.takeIf { it != pos },
            changes = changes,
        )
    }

    // ---------------------------------------------------------------- the verify

    /** After the apply: every managed field, expected vs what the radio actually holds. */
    fun verify(after: DeviceProfile, t: ManagedValues): List<FieldCheck> {
        val lora = after.config?.lora
        val device = after.config?.device
        val pos = after.config?.position
        val primary = runCatching { primaryChannel(after) }.getOrNull()
        val r = t.reporting
        fun c(f: String, e: Any?, a: Any?) = FieldCheck(f, e.toString(), a.toString())
        return listOf(
            c("owner", t.ownerLongName, after.long_name),
            c("region", t.region, lora?.region), c("preset", t.preset, lora?.modem_preset),
            c("use preset", true, lora?.use_preset), c("frequency slot", t.frequencySlot, lora?.channel_num),
            c("hop limit", t.hopLimit, lora?.hop_limit), c("tx power", t.txPower, lora?.tx_power),
            c("tx enabled", r.txEnabled, lora?.tx_enabled), c("role", t.role, device?.role),
            c("channel name", t.channelName, primary?.name),
            c("channel key", t.key.sha256().hex().take(12), primary?.psk?.sha256()?.hex()?.take(12)),
            c("broadcast interval", r.broadcastSecs, pos?.position_broadcast_secs),
            c("smart position", r.smartEnabled, pos?.position_broadcast_smart_enabled),
            c("smart interval", r.smartMinIntervalSecs, pos?.broadcast_smart_minimum_interval_secs),
            c("smart distance", r.smartMinDistanceMeters, pos?.broadcast_smart_minimum_distance),
            c("gps mode", r.gpsMode, pos?.gps_mode), c("gps update", r.gpsUpdateSecs, pos?.gps_update_interval),
        )
    }

    // ---------------------------------------------------------------- helpers

    /** The primary channel's settings, decoded from the profile's channel URL (".../e/#<base64url ChannelSet>"). */
    fun primaryChannel(p: DeviceProfile): ChannelSettings {
        val url = requireNotNull(p.channel_url) { "profile has no channel URL -- nothing written" } // RADIOCFGFIX-2026-09-25
        val frag = url.substringAfter('#', "").substringBefore('?')
        require(frag.isNotEmpty()) { "profile has no channel URL -- nothing written" }
        val set = ChannelSet.ADAPTER.decode(java.util.Base64.getUrlDecoder().decode(frag))
        return requireNotNull(set.settings.firstOrNull()) { "channel URL holds no channel" }
    }
}
