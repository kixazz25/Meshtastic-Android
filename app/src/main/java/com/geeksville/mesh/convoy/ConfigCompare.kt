package com.geeksville.mesh.convoy

import com.squareup.wire.Message
import com.squareup.wire.WireField
import okio.ByteString
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.DeviceProfile

/*
 * CONFIGCOMPARE-2026-09-25 (GroupTrack 2.7) -- the radio configurator, part 3: the COMPARE ENGINE.
 *
 * "Every other difference between two configs": flattens a whole DeviceProfile into path -> value lines -- every
 * config section, the module configs, the owner, the fixed position, and EVERY CHANNEL decoded from the channel URL
 * (that is where channel differences hide) -- and lists what differs. The 19 MANAGED fields are left out (the
 * verify reports those); everything else is information only, never written.
 * Uses: the preview (radio now vs target), the result (radio now vs its last backup), drift (backup vs backup),
 * box vs box. Any two configs.
 *
 * Fields are found by Wire's own @WireField annotation on every generated field -- no hand-kept list, so new
 * protocol fields appear automatically. Their NAMES survive the release build's minifying through the keep rule
 * CONFIGCOMPARE-2026-09-25 in app/proguard-rules.pro. Key bytes are never shown: size + a short hash only.
 */

data class ConfigDiff(val path: String, val a: String, val b: String)

object ConfigCompare {

    /** The managed fields (part 1's verify), as paths in the flattened form. */
    val MANAGED: Set<String> = setOf(
        "owner.long_name",
        "config.lora.use_preset", "config.lora.modem_preset", "config.lora.region", "config.lora.channel_num",
        "config.lora.hop_limit", "config.lora.tx_power", "config.lora.tx_enabled",
        "config.device.role",
        "config.position.position_broadcast_secs", "config.position.position_broadcast_smart_enabled",
        "config.position.broadcast_smart_minimum_interval_secs", "config.position.broadcast_smart_minimum_distance",
        "config.position.gps_mode", "config.position.gps_update_interval", "config.position.fixed_position",
        "channel[0].name", "channel[0].psk", "channel[0].module_settings.position_precision",
    )

    /** Every other field that differs between [a] and [b] (managed fields excluded unless asked). */
    fun differences(a: DeviceProfile, b: DeviceProfile, includeManaged: Boolean = false): List<ConfigDiff> {
        val fa = flatten(a)
        val fb = flatten(b)
        return (fa.keys + fb.keys).distinct()
            .filter { includeManaged || it !in MANAGED }
            .mapNotNull { k ->
                val x = fa[k] ?: "(absent)"
                val y = fb[k] ?: "(absent)"
                if (x != y) ConfigDiff(k, x, y) else null
            }
    }

    /** A whole profile as ordered path -> value lines. */
    fun flatten(p: DeviceProfile): Map<String, String> {
        val out = linkedMapOf<String, String>()
        out["owner.long_name"] = p.long_name.orEmpty()
        out["owner.short_name"] = p.short_name.orEmpty()
        walk(p.config, "config", out)
        walk(p.module_config, "module_config", out)
        walk(p.fixed_position, "fixed_position", out)
        out["ringtone"] = p.ringtone.orEmpty()
        out["canned_messages"] = p.canned_messages.orEmpty()
        // The channel URL's own lora_config copy is NOT walked -- it duplicates config.lora.
        channelSet(p)?.settings?.forEachIndexed { i, s -> walk(s, "channel[$i]", out) }
            ?: run { out["channel"] = "(no channel URL)" }
        return out
    }

    private fun walk(v: Any?, path: String, out: MutableMap<String, String>) {
        when (v) {
            null -> out[path] = "(none)"
            is Message<*, *> -> fieldsOf(v.javaClass).forEach { f -> walk(f.get(v), "$path.${f.name}", out) }
            is List<*> -> if (v.isEmpty()) out[path] = "(empty)" else v.forEachIndexed { i, x -> walk(x, "$path[$i]", out) }
            is ByteString -> out[path] = if (v.size == 0) "(empty)" else "${v.size} bytes #${v.sha256().hex().take(8)}"
            else -> out[path] = v.toString()
        }
    }

    private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<java.lang.reflect.Field>>()

    private fun fieldsOf(c: Class<*>): List<java.lang.reflect.Field> = fieldCache.getOrPut(c) {
        c.declaredFields.filter { it.isAnnotationPresent(WireField::class.java) }.onEach { it.isAccessible = true }
    }

    private fun channelSet(p: DeviceProfile): ChannelSet? {
        val frag = p.channel_url?.substringAfter('#', "")?.substringBefore('?').orEmpty()
        if (frag.isEmpty()) return null
        return runCatching { ChannelSet.ADAPTER.decode(java.util.Base64.getUrlDecoder().decode(frag)) }.getOrNull()
    }
}
