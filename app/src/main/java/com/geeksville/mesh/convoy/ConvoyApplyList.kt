package com.geeksville.mesh.convoy

import android.content.Context
import org.json.JSONObject

/**
 * ConvoyApplyList — persisted checklist of radio config fields
 * that get written to rider radios on ride install.
 *
 * Unchecked fields are left as-is on the rider's radio.
 * Only checked fields are overwritten.
 *
 * Managed via the password-protected settings panel.
 * Stored at: convoy_apply_list.json in app files directory.
 */

// ── LoRa fields ───────────────────────────────────────────────────────────────
enum class LoraField(val label: String, val description: String) {
    REGION         ("LoRa Region",        "Regulatory region (US, EU, AU...)"),
    MODEM_PRESET   ("Modem Preset",       "Range vs speed (LONG_FAST, SHORT_TURBO...)"),
    BANDWIDTH      ("Bandwidth",          "Channel bandwidth in kHz"),
    SPREAD_FACTOR  ("Spread Factor",      "Signal spread — affects range and speed"),
    CODING_RATE    ("Coding Rate",        "Error correction overhead"),
    HOP_LIMIT      ("Hop Limit",          "Max relay hops (1-7)"),
    TX_ENABLED     ("TX Enabled",         "Allow radio to transmit"),
    TX_POWER       ("TX Power",           "Transmit power in dBm"),
    CHANNEL_NUM    ("Channel Number",     "Channel slot on the radio"),
}

// ── Position fields ───────────────────────────────────────────────────────────
enum class PositionField(val label: String, val description: String) {
    GPS_ENABLED                  ("GPS Enabled",              "Enable/disable GPS hardware"),
    GPS_MODE                     ("GPS Mode",                  "DISABLED, ENABLED, NOT_PRESENT"),
    GPS_UPDATE_INTERVAL          ("GPS Update Interval",       "How often GPS polls (seconds)"),
    GPS_ATTEMPT_TIME             ("GPS Attempt Time",          "Max time to wait for GPS fix (seconds)"),
    POSITION_BROADCAST_SECS      ("Position Broadcast Interval","How often position is broadcast (seconds)"),
    POSITION_BROADCAST_SMART     ("Smart Broadcast",           "Only broadcast when position changes"),
    FIXED_POSITION               ("Fixed Position",            "Lock position to a static coordinate"),
    POSITION_FLAGS               ("Position Flags",            "Which position fields are included in packets"),
}

data class ConvoyApplyList(
    val loraFields:     Set<LoraField>     = defaultLora,
    val positionFields: Set<PositionField> = defaultPosition
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("loraFields",     org.json.JSONArray(loraFields.map { it.name }))
        put("positionFields", org.json.JSONArray(positionFields.map { it.name }))
    }

    companion object {
        // Defaults — safe set for convoy use
        val defaultLora: Set<LoraField> = setOf(
            LoraField.REGION,
            LoraField.MODEM_PRESET,
            LoraField.HOP_LIMIT,
            LoraField.TX_ENABLED,
            LoraField.TX_POWER
        )
        val defaultPosition: Set<PositionField> = setOf(
            PositionField.GPS_ENABLED,
            PositionField.GPS_MODE,
            PositionField.GPS_UPDATE_INTERVAL,
            PositionField.POSITION_BROADCAST_SECS,
            PositionField.POSITION_BROADCAST_SMART
        )

        private const val FILE_NAME = "convoy_apply_list.json"

        fun load(context: Context): ConvoyApplyList {
            val file = java.io.File(context.filesDir, FILE_NAME)
            if (!file.exists()) return ConvoyApplyList()
            return try {
                val obj    = JSONObject(file.readText())
                val lora   = mutableSetOf<LoraField>()
                val pos    = mutableSetOf<PositionField>()
                val loraArr = obj.optJSONArray("loraFields")
                val posArr  = obj.optJSONArray("positionFields")
                if (loraArr != null) {
                    for (i in 0 until loraArr.length()) {
                        runCatching { lora.add(LoraField.valueOf(loraArr.getString(i))) }
                    }
                }
                if (posArr != null) {
                    for (i in 0 until posArr.length()) {
                        runCatching { pos.add(PositionField.valueOf(posArr.getString(i))) }
                    }
                }
                ConvoyApplyList(lora, pos)
            } catch (e: Exception) {
                ConvoyApplyList()
            }
        }

        fun save(context: Context, list: ConvoyApplyList) {
            java.io.File(context.filesDir, FILE_NAME).writeText(list.toJson().toString(2))
        }
    }
}
