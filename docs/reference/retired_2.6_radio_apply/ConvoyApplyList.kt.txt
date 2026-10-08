package com.geeksville.mesh.convoy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ConvoyApplyList — full inventory of radio config fields
 * written to rider radios on ride install.
 *
 * All fields selected by default.
 * Channel Name and PSK are locked — always applied.
 * Uncheck fields you do NOT want overwritten on rider radios.
 */

// ── LoRa fields ───────────────────────────────────────────────────────────────
enum class LoraField(val label: String, val description: String) {
    REGION          ("LoRa Region",        "Regulatory region (US, EU, AU...)"),
    MODEM_PRESET    ("Modem Preset",       "Range vs speed (LONG_FAST, SHORT_TURBO...)"),
    BANDWIDTH       ("Bandwidth",          "Channel bandwidth in kHz"),
    SPREAD_FACTOR   ("Spread Factor",      "Signal spread — affects range and speed"),
    CODING_RATE     ("Coding Rate",        "Error correction overhead"),
    HOP_LIMIT       ("Hop Limit",          "Max relay hops (1-7)"),
    TX_ENABLED      ("TX Enabled",         "Allow radio to transmit"),
    TX_POWER        ("TX Power",           "Transmit power in dBm"),
    CHANNEL_NUM     ("Channel Number",     "Channel slot on the radio"),
}

// ── Channel fields (locked — always applied) ──────────────────────────────────
enum class ChannelField(val label: String, val description: String) {
    CHANNEL_NAME    ("Channel Name",       "Convoy mesh channel name (auto-generated)"),
    ENCRYPTION_KEY  ("Encryption Key",     "AES-256 PSK — required for convoy mesh"),
    CHANNEL_ID      ("Channel ID",          "Channel index (0=primary)"),
    UPLINK_ENABLED  ("MQTT Uplink",          "Enable MQTT uplink for this channel"),
    DOWNLINK_ENABLED("MQTT Downlink",        "Enable MQTT downlink for this channel"),
    MODULE_SETTINGS ("Module Settings",      "Per-channel module configuration"),
}

// ── Device fields ─────────────────────────────────────────────────────────────
enum class DeviceField(val label: String, val description: String) {
    LONG_NAME       ("Long Name",          "Full node display name"),
    SHORT_NAME      ("Short Name",         "4-char node identifier"),
    NODE_ROLE       ("Node Role",          "CLIENT, ROUTER, ROUTER_CLIENT..."),
    IS_MANAGED      ("Is Managed",         "Device managed by remote admin"),
    SERIAL_ENABLED  ("Serial Enabled",     "Enable serial console output"),
}

// ── Position fields ───────────────────────────────────────────────────────────
enum class PositionField(val label: String, val description: String) {
    GPS_ENABLED                 ("GPS Enabled",                "Enable/disable GPS hardware"),
    GPS_MODE                    ("GPS Mode",                   "DISABLED, ENABLED, NOT_PRESENT"),
    GPS_UPDATE_INTERVAL         ("GPS Update Interval",        "How often GPS polls (seconds)"),
    GPS_ATTEMPT_TIME            ("GPS Attempt Time",           "Max time to wait for GPS fix (seconds)"),
    POSITION_BROADCAST_SECS     ("Position Broadcast Interval","How often position is broadcast (seconds)"),
    POSITION_BROADCAST_SMART    ("Smart Broadcast",            "Only broadcast when position changes"),
    FIXED_POSITION              ("Fixed Position",             "Lock position to a static coordinate"),
    POSITION_FLAGS              ("Position Flags",             "Which position fields are included in packets"),
}

// ── Display fields ────────────────────────────────────────────────────────────
enum class DisplayField(val label: String, val description: String) {
    UNITS                       ("Units",                      "Metric or Imperial measurements"),
    SCREEN_TIMEOUT              ("Screen Timeout",             "Seconds before screen sleeps (0=never)"),
    AUTO_SCREEN_BRIGHTNESS      ("Auto Screen Brightness",     "Automatically adjust screen brightness"),
    COMPASS_NORTH_TOP           ("Compass North Top",          "Fix north at top of compass display"),
}

// ── Module fields ─────────────────────────────────────────────────────────────
enum class ModuleField(val label: String, val description: String) {
    TELEMETRY_DEVICE_INTERVAL   ("Telemetry Interval",         "How often device telemetry is broadcast (seconds)"),
    TELEMETRY_ENV_INTERVAL      ("Environment Telemetry",      "How often env sensors broadcast (seconds)"),
    TELEMETRY_ENV_ENABLED       ("Env Sensor Enabled",         "Enable environment sensor module"),
    MQTT_ENABLED                ("MQTT Enabled",               "Enable MQTT uplink/downlink"),
    MQTT_ADDRESS                ("MQTT Server",                "MQTT broker address"),
    MQTT_USERNAME               ("MQTT Username",              "MQTT broker username"),
    MQTT_ENCRYPTION_ENABLED     ("MQTT Encryption",            "Encrypt MQTT messages"),
    MQTT_JSON_ENABLED           ("MQTT JSON",                  "Use JSON format for MQTT messages"),
    SERIAL_ENABLED              ("Serial Module",              "Enable serial module"),
    SERIAL_BAUD                 ("Serial Baud Rate",           "Serial port baud rate"),
    EXT_NOTIFICATION_ENABLED    ("Ext Notification",           "Enable external notification module"),
    EXT_NOTIFICATION_ALERT_MSG  ("Alert on Message",           "Trigger notification on incoming message"),
    RANGE_TEST_ENABLED          ("Range Test",                 "Enable range test module"),
    STORE_FORWARD_ENABLED       ("Store & Forward",            "Enable store and forward module"),
    NEIGHBOR_INFO_ENABLED       ("Neighbor Info",              "Broadcast neighbor node info"),
    DETECTION_SENSOR_ENABLED    ("Detection Sensor",           "Enable detection sensor module"),
    AUDIO_ENABLED               ("Audio Module",               "Enable audio/voice module"),
}

data class ConvoyApplyList(
    val loraFields:     Set<LoraField>     = LoraField.values().toSet(),
    val channelFields:  Set<ChannelField>  = ChannelField.values().toSet(),
    val deviceFields:   Set<DeviceField>   = DeviceField.values().toSet(),
    val positionFields: Set<PositionField> = PositionField.values().toSet(),
    val displayFields:  Set<DisplayField>  = DisplayField.values().toSet(),
    val moduleFields:   Set<ModuleField>   = ModuleField.values().toSet()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("loraFields",     JSONArray(loraFields.map    { it.name }))
        put("channelFields",  JSONArray(channelFields.map { it.name }))
        put("deviceFields",   JSONArray(deviceFields.map  { it.name }))
        put("positionFields", JSONArray(positionFields.map{ it.name }))
        put("displayFields",  JSONArray(displayFields.map { it.name }))
        put("moduleFields",   JSONArray(moduleFields.map  { it.name }))
    }

    companion object {
        private const val FILE_NAME = "convoy_apply_list.json"

        fun load(context: Context): ConvoyApplyList {
            val file = java.io.File(context.filesDir, FILE_NAME)
            if (!file.exists()) return ConvoyApplyList()
            return try {
                val obj = JSONObject(file.readText())
                fun <T : Enum<T>> parseSet(key: String, values: Array<T>): Set<T> {
                    val arr   = obj.optJSONArray(key) ?: return values.toSet()
                    val names = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    return values.filter { it.name in names }.toSet()
                }
                ConvoyApplyList(
                    loraFields     = parseSet("loraFields",     LoraField.values()),
                    channelFields  = ChannelField.values().toSet(), // always locked/all
                    deviceFields   = parseSet("deviceFields",   DeviceField.values()),
                    positionFields = parseSet("positionFields", PositionField.values()),
                    displayFields  = parseSet("displayFields",  DisplayField.values()),
                    moduleFields   = parseSet("moduleFields",   ModuleField.values())
                )
            } catch (e: Exception) {
                ConvoyApplyList()
            }
        }

        fun save(context: Context, list: ConvoyApplyList) {
            java.io.File(context.filesDir, FILE_NAME).writeText(list.toJson().toString(2))
        }
    }
}
