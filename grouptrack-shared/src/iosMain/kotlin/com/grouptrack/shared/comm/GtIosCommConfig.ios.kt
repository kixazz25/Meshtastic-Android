/*
 * Copyright (c) 2026 GroupTrack
 */
package com.grouptrack.shared.comm

import com.grouptrack.comm.ApplyReport
import com.grouptrack.comm.ApplyRequest
import com.grouptrack.comm.CommConfig
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.ConfigCheck
import com.grouptrack.comm.ConfigChange
import com.grouptrack.comm.ConfigDifference
import com.grouptrack.comm.SavedConfig
import com.grouptrack.comm.SavedSetup
import com.grouptrack.shared.log.gtLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.RadioClient

/**
 * IOSCONFIG-2026-11-20 -- the iPad's [CommConfig]: the RADIO SETTINGS, over the SDK's AdminApi.
 *
 * REAL here, all the way to the radio: [applyRadioChange] (the LoRa preset and the moving reporting
 * interval, written then re-read and checked), [currentNetwork], and a live [applyReport].
 *
 * NOT here, and said so rather than stubbed: everything that needs GroupTrack's CONFIG-DOC format or
 * RadioBackups' saved-setups store, neither of which exists on iOS yet. Those return
 * [CommResult.NotSupported] or an empty list, both of which are legitimate values in this contract
 * ("Empty = no radio, or nothing saved for it yet"), so a panel asking gets a straight answer.
 *
 * @param clientOf the connected radio, from [GtIosCommLink]. Null = no radio -- always null on the
 *   simulator, which has no Bluetooth. Every function below treats that as NOT_CONNECTED rather than
 *   pretending, because an apply that silently did nothing is the worst outcome for a rider.
 */
class GtIosCommConfig(private val clientOf: () -> RadioClient?) : CommConfig {

    private val log = gtLogger("GtIosCommConfig")

    private val _report = MutableStateFlow<ApplyReport?>(null)
    /** CODE RULE 1: null = no apply since the app started. */
    override val applyReport: StateFlow<ApplyReport?> = _report.asStateFlow()

    // ── the one apply that is real ──────────────────────────────────────────────────────────────────────

    /**
     * PRESETPANEL-2026-10-20: the radio's current settings with ONLY the channel preset and the moving
     * reporting interval replaced. Every radio in a group must share the preset, which is why this is one
     * call rather than two panels.
     *
     * Plan, write, verify. The read-modify-write is EXPLICIT: the current section is fetched from the radio,
     * only the two managed fields are changed on it, and it goes back whole. ⛔ Do NOT replace this with the
     * SDK's setLoraConfig / setPositionConfig helpers -- their `initial` is an EMPTY section, so they would
     * send a config with every other field at its proto default and reset the radio's region. The re-read
     * afterwards is what makes this a verify and not a hope.
     */
    override suspend fun applyRadioChange(preset: String, movingIntervalSecs: Int): CommResult {
        val c = clientOf() ?: return notConnected("applyRadioChange")
        val lines = mutableListOf<String>()
        fun step(line: String) { lines.add(line); publish(running = true, lines = lines, checks = emptyList(), verified = false) }

        val wanted = runCatching { Config.LoRaConfig.ModemPreset.valueOf(preset) }.getOrNull()
            ?: return CommResult.Failed("UNKNOWN_PRESET:" + preset)

        step("PRESETPANEL: preset " + preset + ", moving every " + movingIntervalSecs + " s")

        // IOSCONFIG-2026-11-20 v2 -- READ, MODIFY, WRITE, done EXPLICITLY and deliberately NOT through the
        // SDK's setLoraConfig / setPositionConfig helpers. Their `initial` is Config.X.Builder().build(),
        // a FRESH EMPTY SECTION, so the block's result is what gets sent: using them to set two fields would
        // reset every other field in that section to its proto default -- LoRa `region` among them, and an
        // unset region means the radio will not transmit legally. So the current section is read FROM THE
        // RADIO and only the two managed fields are changed on it.
        val loraNow = c.admin.getConfig(AdminMessage.ConfigType.LORA_CONFIG)
        if (loraNow !is AdminResult.Success) return stopped(lines, "lora.read", loraNow.toString())
        val loraCur = loraNow.value.lora ?: return stopped(lines, "lora.read", "the radio returned no LoRa section")
        step("read the radio's LoRa section (region " + loraCur.region.name + ")")
        val a = c.admin.setConfig(
            Config.Builder().also { w ->
                w.lora = loraCur.newBuilder().also { b -> b.modem_preset = wanted; b.use_preset = true }.build()
            }.build(),
        )
        if (a !is AdminResult.Success) return stopped(lines, "lora.write", a.toString())
        step("wrote LoRa preset")

        val posNow = c.admin.getConfig(AdminMessage.ConfigType.POSITION_CONFIG)
        if (posNow !is AdminResult.Success) return stopped(lines, "position.read", posNow.toString())
        val posCur = posNow.value.position ?: return stopped(lines, "position.read", "the radio returned no position section")
        step("read the radio's position section")
        val b = c.admin.setConfig(
            Config.Builder().also { w ->
                w.position = posCur.newBuilder().also { b -> b.broadcast_smart_minimum_interval_secs = movingIntervalSecs }.build()
            }.build(),
        )
        if (b !is AdminResult.Success) return stopped(lines, "position.write", b.toString())
        step("wrote the moving interval")

        // VERIFY -- read both sections back and compare what the radio holds with what was asked for.
        val checks = mutableListOf<ConfigCheck>()
        val lora = c.admin.getConfig(AdminMessage.ConfigType.LORA_CONFIG)
        if (lora is AdminResult.Success) {
            checks.add(ConfigCheck("lora.modem_preset", preset, lora.value.lora?.modem_preset?.name ?: "<none>"))
            checks.add(ConfigCheck("lora.use_preset", "true", (lora.value.lora?.use_preset ?: false).toString()))
        } else checks.add(ConfigCheck("lora.modem_preset", preset, "<not read: " + lora + ">"))
        val pos = c.admin.getConfig(AdminMessage.ConfigType.POSITION_CONFIG)
        if (pos is AdminResult.Success) {
            checks.add(ConfigCheck(
                "position.broadcast_smart_minimum_interval_secs",
                movingIntervalSecs.toString(),
                (pos.value.position?.broadcast_smart_minimum_interval_secs ?: 0).toString(),
            ))
        } else checks.add(ConfigCheck("position.broadcast_smart_minimum_interval_secs", movingIntervalSecs.toString(), "<not read: " + pos + ">"))

        val verified = checks.all { it.ok }
        lines.add(if (verified) "verified" else "VERIFY FAILED -- the radio does not hold what was written")
        publish(running = false, lines = lines, checks = checks, verified = verified)
        log.i { "IOSCONFIG-2026-11-20 applyRadioChange verified=" + verified }
        // CommResult.Applied carries exactly this distinction: it was applied, and whether it verified.
        return CommResult.Applied(verified = verified, failedChecks = checks.filter { !it.ok }.map { it.field })
    }

    /** RADIONET-2026-10-12: the network the radio is on = the primary channel's name, as Android reads it. */
    override suspend fun currentNetwork(): String? {
        val c = clientOf() ?: return null
        val r = c.admin.listChannels()
        if (r !is AdminResult.Success) {
            log.i { "RADIONET-2026-10-12: network not known yet (" + r + ")" }
            return null
        }
        // CODE RULE 1: null = not known (no radio, or settings not read). "" is a real answer -- the radio's
        // own default channel -- and GroupTrack decides what the name means, so it is passed through as is.
        return r.value.firstOrNull()?.settings?.name
    }

    // ── the saved-setups half: not on iOS yet, and said so ──────────────────────────────────────────────
    //
    // These need GroupTrack's config-doc format and RadioBackups' file store. Android has both; iOS has
    // neither, and inventing a second format here is exactly the parallel helper Fred ruled out on 11-18.

    override suspend fun previewConfig(configDoc: String, callsign: String): Pair<List<ConfigChange>, CommResult> =
        emptyList<ConfigChange>() to CommResult.NotSupported

    override suspend fun applyConfig(configDoc: String, callsign: String): CommResult = CommResult.NotSupported

    override suspend fun applyRide(request: ApplyRequest): CommResult = CommResult.NotSupported

    override suspend fun applySaved(id: String, saveTitle: String): CommResult = CommResult.NotSupported

    override suspend fun previewSaved(id: String): Pair<List<ConfigChange>, CommResult> =
        emptyList<ConfigChange>() to CommResult.NotSupported

    override suspend fun restoreSaved(id: String): CommResult = CommResult.NotSupported

    override suspend fun compareSaved(a: String, b: String): Pair<List<ConfigChange>, CommResult> =
        emptyList<ConfigChange>() to CommResult.NotSupported

    override suspend fun compareConfigs(a: String, b: String): Pair<List<ConfigDifference>, CommResult> =
        emptyList<ConfigDifference>() to CommResult.NotSupported

    /** "Empty = no radio, or nothing saved for it yet" -- the contract's own words, so this is accurate. */
    override suspend fun savedConfigs(): List<SavedConfig> = emptyList()

    override suspend fun savedSetups(): List<SavedSetup> = emptyList()

    /** Drift is measured against the last SAVE; with no saves there is nothing to measure against. */
    override suspend fun driftSinceLastSave(): List<ConfigChange> = emptyList()

    /** C11: the Wi-Fi/IP link to the radio. LoRa only on the iPad today (see CommLink.onBoxWifi). */
    override suspend fun networkLink(): Pair<String?, CommResult> = null to CommResult.NotSupported

    // ── internals ───────────────────────────────────────────────────────────────────────────────────────

    private fun notConnected(what: String): CommResult {
        log.i { "IOSCONFIG-2026-11-20 " + what + ": no radio connected" }
        publish(running = false, lines = listOf("no radio connected"), checks = emptyList(), verified = false, stopReason = "NOT_CONNECTED")
        return CommResult.Failed("NOT_CONNECTED")
    }

    private fun stopped(lines: MutableList<String>, at: String, reason: String): CommResult {
        lines.add("stopped at " + at + ": " + reason)
        publish(running = false, lines = lines, checks = emptyList(), verified = false, stoppedAt = at, stopReason = reason)
        log.e { "IOSCONFIG-2026-11-20 apply stopped at " + at + ": " + reason }
        return CommResult.Failed(reason)
    }

    private fun publish(
        running: Boolean,
        lines: List<String>,
        checks: List<ConfigCheck>,
        verified: Boolean,
        stoppedAt: String? = null,
        stopReason: String? = null,
    ) {
        _report.value = ApplyReport(
            running = running,
            log = lines.toList(),
            checks = checks,
            verified = verified,
            stoppedAt = stoppedAt,
            stopReason = stopReason,
            // No group writes and no save: the iPad writes the two settings directly and has no saved-setups
            // store, so claiming either would be false. CODE RULE 1: savedAs null = nothing saved.
            groupsWritten = emptyList(),
            savedAs = null,
        )
    }
}
