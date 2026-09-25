package com.geeksville.mesh.convoy

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile

/*
 * RADIOWRITER-2026-09-25 (GroupTrack 2.7) -- the new radio configurator, part 2: the SEQUENCED WRITER.
 *
 * Writes a ConfigPlan (part 1) in Nathan's order, each group followed by the proven wait:
 *   group 1a  owner (the rider's callsign)
 *   group 1b  BEGIN settings -> lora -> device -> position -> COMMIT   (one save, at most one restart)
 *   group 2   channel 0: name, key, position precision
 *   then      retrieve ALL values (fresh after the last reconnect) -> verify the managed fields
 * A group with nothing to change is skipped. Every call is awaited, in order (the view model's fire-and-forget
 * wrappers do not guarantee order -- the writer does not use them).
 *
 * THE WAIT after each group = the old ConvoyReconnectWaitScreen's proven pattern with Nathan's settle:
 *   settle 15 s (Nathan: covers the radio's delayed restart) -> FORCED Bluetooth cycle (disconnect, 3 s,
 *   reconnect to the saved radio -- as the old wait's auto cycle) -> wait until the state is EXACTLY
 *   ConnectionState.Connected (never a text match: "Disconnected" contains "connected") -> 1.5 s.
 *   A fresh Connected means the app re-downloaded the radio's config (Stage 1 complete), so the verify's
 *   retrieve reflects what the radio actually holds.
 * Not back within the timeout -> STOP, report the group; nothing further is written.
 * Whether a group made the radio restart by itself is observed during the settle and logged, so the bench
 * learns firmware 2.6.11's real behaviour.
 *
 * RadioOps is the only link to the radio; the Bluetooth adapter (RadioController / view model) is part 4.
 */

/** Everything the writer needs from the radio connection. */
interface RadioOps {
    val connection: StateFlow<ConnectionState>
    suspend fun setOwner(longName: String)
    suspend fun beginEdit()
    suspend fun commitEdit()
    suspend fun writeConfig(config: Config)
    suspend fun writeChannel(channel: Channel)
    /** Drop the app's Bluetooth link (the old wait: setDeviceAddress("n")). */
    suspend fun disconnect()
    /** Reconnect to the saved radio (the old wait: setDeviceAddress(savedAddress)). */
    suspend fun reconnect()
    /** All values, as the app holds them after the latest (re)connect -- a DeviceProfile. */
    suspend fun retrieve(): DeviceProfile
}

data class WriterTiming(
    val settleMs: Long = 15_000, // Nathan's settle
    val dropWaitMs: Long = 10_000, // after disconnect(): wait for the link to actually drop
    val cycleGapMs: Long = 3_000, // disconnect -> reconnect gap (old wait: 3 s)
    val reconnectTimeoutMs: Long = 60_000, // old wait: 60 s
    val afterConnectMs: Long = 1_500, // old wait: 1.5 s before proceeding
    val pollMs: Long = 1_000,
)

sealed class WriteResult {
    /** Written and retrieved; [checks] says field by field whether the radio holds the target. */
    data class Done(val after: DeviceProfile, val checks: List<FieldCheck>, val groupsWritten: List<String>) : WriteResult() {
        val verified: Boolean get() = checks.all { it.ok }
    }
    /** Stopped: [group] did not come back. Groups before it were written; none after. */
    data class Stopped(val group: String, val reason: String, val groupsWritten: List<String>) : WriteResult()
}

class RadioConfigWriter(
    private val ops: RadioOps,
    private val log: (String) -> Unit,
    private val timing: WriterTiming = WriterTiming(),
) {
    suspend fun apply(plan: ConfigPlan, target: ManagedValues): WriteResult {
        val written = mutableListOf<String>()
        if (plan.isEmpty) log("RADIOWRITER: nothing to change -- verify only")

        plan.ownerLongName?.let { name ->
            log("RADIOWRITER: group 1a -- owner '$name'")
            ops.setOwner(name)
            written += "1a owner"
            if (!waitBack("1a owner")) return WriteResult.Stopped("1a owner", "radio did not come back", written)
        }

        if (plan.lora != null || plan.device != null || plan.position != null) {
            val parts = listOfNotNull(plan.lora?.let { "lora" }, plan.device?.let { "device" }, plan.position?.let { "position" })
            log("RADIOWRITER: group 1b -- begin, ${parts.joinToString(" + ")}, commit")
            ops.beginEdit()
            plan.lora?.let { ops.writeConfig(Config(lora = it)) }
            plan.device?.let { ops.writeConfig(Config(device = it)) }
            plan.position?.let { ops.writeConfig(Config(position = it)) }
            ops.commitEdit()
            written += "1b " + parts.joinToString("+")
            if (!waitBack("1b settings")) return WriteResult.Stopped("1b settings", "radio did not come back", written)
        }

        plan.channel?.let { ch ->
            log("RADIOWRITER: group 2 -- channel '${ch.settings?.name}' (key, precision)")
            ops.writeChannel(ch)
            written += "2 channel"
            if (!waitBack("2 channel")) return WriteResult.Stopped("2 channel", "radio did not come back", written)
        }

        if (ops.connection.value != ConnectionState.Connected) {
            log("RADIOWRITER: not connected before the retrieve -- waiting")
            if (!waitConnected()) return WriteResult.Stopped("retrieve", "radio not connected", written)
        }
        val after = ops.retrieve()
        val checks = RadioConfigurator.verify(after, target)
        log("RADIOWRITER: verify ${checks.count { it.ok }}/${checks.size}" +
            checks.filter { !it.ok }.joinToString("") { " | FAIL ${it.field}: expected ${it.expected}, got ${it.actual}" })
        return WriteResult.Done(after, checks, written)
    }

    /** Nathan's settle + the old wait's forced Bluetooth cycle + an EXACT Connected. */
    private suspend fun waitBack(group: String): Boolean {
        val t0 = System.currentTimeMillis()
        var droppedAfter: Long? = null
        var waited = 0L
        while (waited < timing.settleMs) {
            if (droppedAfter == null && ops.connection.value != ConnectionState.Connected) droppedAfter = waited
            delay(timing.pollMs); waited += timing.pollMs
        }
        log("RADIOWRITER: $group -- settle ${timing.settleMs / 1000}s done; radio restarted by itself: " +
            (droppedAfter?.let { "yes, after ~${it / 1000}s" } ?: "no"))
        log("RADIOWRITER: $group -- forced Bluetooth cycle")
        ops.disconnect()
        withTimeoutOrNull(timing.dropWaitMs) { ops.connection.first { it != ConnectionState.Connected } }
        delay(timing.cycleGapMs)
        ops.reconnect()
        val back = waitConnected()
        log("RADIOWRITER: $group -- " + if (back) "back (connected) after ${(System.currentTimeMillis() - t0) / 1000}s" else "NOT back")
        if (back) delay(timing.afterConnectMs)
        return back
    }

    private suspend fun waitConnected(): Boolean =
        withTimeoutOrNull(timing.reconnectTimeoutMs) { ops.connection.first { it == ConnectionState.Connected } } != null
}
