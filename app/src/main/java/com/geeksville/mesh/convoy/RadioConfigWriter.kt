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
    /** RADIODEFAULTS-2026-10-06: a module setting (the serial module), inside a begin/commit like the config groups. */
    suspend fun writeModuleConfig(config: org.meshtastic.proto.ModuleConfig)
    /** Drop the app's Bluetooth link (the old wait: setDeviceAddress("n")). */
    suspend fun disconnect()
    /** Reconnect to the saved radio (the old wait: setDeviceAddress(savedAddress)). */
    suspend fun reconnect()
    /** All values, as the app holds them after the latest (re)connect -- a DeviceProfile. */
    suspend fun retrieve(): DeviceProfile
    /** OWNNODE-2026-09-26: the node number of the app's OWN radio as the app currently holds it (its own entry in the node
     *  list, sent only in the connect exchange). CODE RULE 1: null is the real state being waited for -- the app has not
     *  (yet) received its own radio's record -- not a shortcut. */
    fun ownNodeNum(): Int?
}

data class WriterTiming(
    val settleMs: Long = 15_000, // Nathan's settle
    val dropWaitMs: Long = 10_000, // after disconnect(): wait for the link to actually drop
    val cycleGapMs: Long = 3_000, // GAP3-2026-09-26 (Fred): 3 s is enough -- the reconnect then WAITS for Connected; the final clean cycle (FINALCYCLE) handles the radio's last restart
    val ownNodeTimeoutMs: Long = 30_000, // OWNNODE: how long to wait for the app's own-radio record after the last reconnect
    val finalWaitMs: Long = 30_000, // FINALCYCLE-2026-09-26 (Fred): wait after the last group, then ALWAYS one clean reconnect
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
        val ownAtStart = ops.ownNodeNum()   // OWNNODE-2026-09-26: the radio we started with
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

        // RADIODEFAULTS-2026-10-06 (Fred): GROUP 3 -- the serial module (a module setting), before Bluetooth (which stays last).
        plan.serial?.let { s ->
            log("RADIOWRITER: group 3 -- serial enabled=${s.enabled}")
            ops.beginEdit()
            ops.writeModuleConfig(org.meshtastic.proto.ModuleConfig(serial = s))
            ops.commitEdit()
            written += "3 serial"
            if (!waitBack("3 serial")) return WriteResult.Stopped("3 serial", "radio did not come back", written)
        }

        // NOPIN-2026-10-04 (Fred): GROUP 4, LAST -- Bluetooth pairing NO_PIN. Changing the pairing mode can break THIS tablet's
        // stored pairing once (10-04: link up, no data, only forget + re-pair helped), so it goes after everything else, and if
        // the radio does not come back the rider is told exactly what to do. The next check-in finds NO_PIN and skips this.
        plan.bluetooth?.let { bt ->
            log("RADIOWRITER: group 4 -- bluetooth enabled=${bt.enabled} pairing=${bt.mode} (last)")
            ops.beginEdit()
            ops.writeConfig(Config(bluetooth = bt))
            ops.commitEdit()
            written += "4 bluetooth"
            if (!waitBack("4 bluetooth")) return WriteResult.Stopped("4 bluetooth",
                "the radio's Bluetooth pairing changed to No PIN -- forget the radio in Bluetooth settings, pair it again, then check in again", written)
        }

        if (ops.connection.value != ConnectionState.Connected) {
            log("RADIOWRITER: not connected before the retrieve -- waiting")
            if (!waitConnected()) return WriteResult.Stopped("retrieve", "radio not connected", written)
        }
        // FINALCYCLE-2026-09-26 (Fred): after the last write the radio may restart AGAIN while the app still believes it is
        // connected (Android is slow to notice the dead link). Never trust that: wait 30 s, then ALWAYS one clean
        // disconnect/reconnect -- exactly what Fred did by hand -- before the own-radio check and the verify.
        if (written.isNotEmpty()) {
            log("RADIOWRITER: final wait ${timing.finalWaitMs / 1000}s, then a clean reconnect")
            delay(timing.finalWaitMs)
            if (!cycle("final reconnect")) return WriteResult.Stopped("final reconnect", "radio did not come back", written)
        }
        // OWNNODE-2026-09-26 (Fred): "Connected" is not enough -- after a reconnect into a restarting radio the app kept
        // seeing the mesh but lost its OWN radio (only a manual reconnect brought it back). Wait for the app's own-radio
        // record; if it does not come, one more Bluetooth cycle (what Fred did by hand); only then stop, plainly.
        if (written.isNotEmpty() && !waitOwnNode(ownAtStart)) {
            log("RADIOWRITER: own radio record not received -- one more Bluetooth cycle")
            if (!cycle("own-radio record") || !waitOwnNode(ownAtStart)) {
                return WriteResult.Stopped("reconnect", "the app did not receive its own radio's record -- disconnect and reconnect the radio", written)
            }
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

    /** OWNNODE-2026-09-26: waits until the app holds its own radio's record (the same radio as at the start, when known). */
    private suspend fun waitOwnNode(expected: Int?): Boolean {
        val t0 = System.currentTimeMillis()
        val ok = withTimeoutOrNull(timing.ownNodeTimeoutMs) {
            while (true) {
                val n = ops.ownNodeNum()
                if (n != null && (expected == null || n == expected)) break
                delay(timing.pollMs)
            }
            true
        } ?: false
        log("RADIOWRITER: own radio record " + (if (ok) "present after ${(System.currentTimeMillis() - t0) / 1000}s" else "NOT received within ${timing.ownNodeTimeoutMs / 1000}s"))
        return ok
    }

    /** OWNNODE-2026-09-26: one forced Bluetooth cycle on its own (no write, no settle). */
    private suspend fun cycle(label: String): Boolean {
        log("RADIOWRITER: $label -- extra Bluetooth cycle")
        ops.disconnect()
        withTimeoutOrNull(timing.dropWaitMs) { ops.connection.first { it != ConnectionState.Connected } }
        delay(timing.cycleGapMs)
        ops.reconnect()
        val back = waitConnected()
        if (back) delay(timing.afterConnectMs)
        log("RADIOWRITER: $label -- " + if (back) "back (connected)" else "NOT back")
        return back
    }

    private suspend fun waitConnected(): Boolean =
        withTimeoutOrNull(timing.reconnectTimeoutMs) { ops.connection.first { it == ConnectionState.Connected } } != null
}
