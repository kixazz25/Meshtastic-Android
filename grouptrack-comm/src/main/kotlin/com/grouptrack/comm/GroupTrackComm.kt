/*
 * GroupTrack Comm API — v0.3 · COMMAPI3-2026-10-08 · 2.7b cycle 1 step 1 (IN THE REPOSITORY from here on: module
 *   :grouptrack-comm). Extends DRAFT v0.2 (GroupTrackComm_API_DRAFT_2026-10-03_v2.kt, COMMAPI-2026-10-03).
 * v0.3 (design §R5.2 + §R6, Fred 10-07/10-08): CoT names (uid per RIDER, how); the transport(s) each event arrived on;
 *   plain text (port 1); reporting per transport; "on a box's Wi-Fi" in link state; CommMenus (menus behind the API;
 *   the GRP Awareness switch off = no items). The translator is NOT in the contract — it is inside the implementation.
 * v0.2: messages CoT-aligned (CoT is GroupTrack's data standard); staleAtMs on every message (the sender's validity
 *   window — status follows it, the deadline timer is the earliest staleAt).
 * Module:     :grouptrack-comm   (this contract + its plain-data types; depends on NOTHING transport-specific)
 * Implemented by: the app module's mesh implementation (§R6.1 — today's Meshtastic code, called, never replaced;
 *                 the app module holds the Meshtastic view models GroupTrack uses, so the mesh side lives there)
 *                 an IP / Nucleus implementation (later) · the Apple Group Comm plugin (same contract, same tests)
 * STEP 1: nothing implements or calls this yet. Each later step moves one area behind it (§R6.5).
 *
 * GOVERNING RULE (Fred 10-02): every payload and packet between device and services is an OPEN format;
 * the work done on it is hidden inside a process.
 * SEPARATION RULES, applied to every call below:
 *   R1 ONLY DOOR      — GroupTrack (Layer 1) reaches a transport only through this file. No transport type, call,
 *                       import or UI in Layer 1; no `if (mesh)`. Enforced by module dependencies.
 *   R2 PLAIN DATA,    — everything crossing is defined here (or Kotlin primitives / JSON). No shared globals:
 *      NO SHARED STATE  each side owns its state; callsign, role and ride are PASSED IN, never read across.
 *   R3 EXPECT FAILURE — every call can answer Failed(reason) or NotSupported; nothing blocks forever.
 *   ⛔ NO CONVERGENCE FIXES — a fix goes inside an implementation or into this contract (additively), never around it.
 * PROCESSING MODEL (Fred 10-02/10-03): NO circular timed tick. The comm layer hands off DEDUPED, NORMALISED
 * messages; GroupTrack processes each on arrival. Time-based status (stale, lost, "last heard") is DERIVED from
 * lastHeardMs; one next-deadline timer in Layer 1 handles silence.
 * CHANGE POLICY: additive only — new fields optional, new calls answer NotSupported in every implementation.
 */
package com.grouptrack.comm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

// ═════════════════════════════════ 0 · SETUP — the Group riding switch ═════════════════════════════════
// Fred 10-03: the plugin is On/Off in Settings. The install question ("Do you have a radio to set up?") sets the
// first value and runs or skips the initial radio default config as now. The value is part of the DEFAULT CONFIG
// API so EVERY SESSION starts the plugin from it. It is a DEVICE setting — never carried in a ride package, so
// opening someone's ride can never switch a rider's plugin on or off.

enum class CommMethod { MESH, IP }
enum class SetupSource { INSTALL_QUESTION, SETTINGS }

/** The plugin's setup value, read at the start of every session. JSON form (device config, not ride package):
 *  "groupRiding": { "enabled": true, "method": "MESH", "setBy": "INSTALL_QUESTION", "setAtMs": 1790000000000 } */
data class GroupRidingSetup(
    val enabled: Boolean,
    val method: CommMethod,
    val setBy: SetupSource,
    val setAtMs: Long,
)

/** Where the setup value lives. GroupTrack reads it at session start; Settings and the install question write it. */
interface CommSetupStore {
    fun read(): GroupRidingSetup                       // a fresh install with no answer yet reads enabled = false
    fun write(setup: GroupRidingSetup)
}

// ═════════════════════════════════ PLAIN DATA (R2) ═════════════════════════════════

enum class Link { DISCONNECTED, CONNECTING, CONNECTED }
enum class Role { LEADER, MIDDLE, TAIL_GUNNER, RIDER }          // GroupTrack's roles; TAK mapping is Layer 1's

data class RadioEntry(val id: String, val name: String, val paired: Boolean,
                      /** CODE RULE 1: null = the transport cannot measure signal (IP; some scans). */
                      val signalDbm: Int?)

data class DeviceInfo(val id: String, val name: String,
                      /** CODE RULE 1: null = not reported by this device/transport. */
                      val model: String?, val firmware: String?)

/** A member as heard. Position fields null = that member has sent no fix (a real state on a mesh). */
data class Member(
    val id: String, val callsign: String,
    val staleAtMs: Long?,                            // from the member's last message (CoT stale); null = never sent one
    val latitude: Double?, val longitude: Double?, val altitudeM: Int?,
    val fixTimeMs: Long?, val lastHeardMs: Long,
    val reportedRole: Role?,                         // null = no TAK report from this member yet
    val channelUtilPct: Float?,                      // null = not reported (IP)
)

/** CoT IS THE DATA STANDARD (Fred 10-03). Messages are CoT-shaped: uid, type, time/start/stale, point with accuracy,
 *  contact, group + role, track, status. GroupTrack-only concepts (ride id, check-in, END) travel in a CoT DETAIL
 *  EXTENSION (other TAK apps ignore it). Wire format is the transport's: mesh = Meshtastic's compact TAK packet;
 *  IP = TAK protocol (CoT protobuf/XML). The comm layer converts both ways (its normalising job).
 *  To confirm in research: the CoT schema (MITRE / TAK protocol docs) and which fields the mesh TAK packet carries —
 *  a field the mesh cannot carry is filled by the comm layer (e.g. staleAt from the reporting frequency) or left null. */
data class CotPoint(val latitude: Double, val longitude: Double,
                    /** CODE RULE 1 (all three): null = not reported by the sender. */
                    val haeM: Double?, val ceM: Double?, val leM: Double?)
data class CotTrack(val speedMps: Double, val courseDeg: Double)
data class GroupTrackDetail(val rideId: String, val kind: Kind) { enum class Kind { REPORT, ROLE_CHANGE, CHECK_IN, END } }

data class CommMessage(
    val uid: String,                 // CoT uid — ONE stable id per RIDER (the GroupTrack user id, not the radio's): Natak's
                                     //   bridge keys its rate limit and loop guard on it (v0.3)
    val type: String,                // CoT type, e.g. "a-f-G-U-C" (friendly ground unit)
    val how: String,                 // CoT how, e.g. "m-g" (machine, GPS) (v0.3)
    val timeMs: Long,                // CoT time — when sent
    val startMs: Long,               // CoT start — valid from
    val staleAtMs: Long,             // CoT stale — valid until = OUR BOUNDARY (§R5.3; filled from the reporting interval when the wire has none)
    val point: CotPoint?,            // null = no fix in this message (e.g. a pure role change)
    val callsign: String,            // CoT contact
    val role: Role,                  // CoT __group role (mapped: LEADER→Team Lead, MIDDLE→RTO, TAIL_GUNNER→Forward Observer, RIDER→HQ)
    val team: String,                // CoT __group name (2.7a: "Cyan")
    val track: CotTrack?,            // null = not reported
    val batteryPct: Int?,            // CoT status battery; null = not reported
    val groupTrack: GroupTrackDetail?, // the detail extension; null = a plain TAK event from another TAK app
)

/** Everything the comm layer hands up — already DEDUPED (each message once) and NORMALISED (a mesh position packet
 *  and a TAK report arrive as the same Report). GroupTrack processes each on arrival — no polling. */
/** v0.3: which path(s) delivered an event — wifi, lora or both (duplicates collapse into one event naming both). */
enum class Transport { LORA, WIFI }

sealed class CommEvent {
    data class Received(val message: CommMessage, val via: Set<Transport>) : CommEvent()
    /** v0.3: plain text (Meshtastic port 1) — merges into the radio's message store, reaches plain radios. */
    data class TextReceived(val fromUid: String, val text: String, val timeMs: Long, val via: Set<Transport>) : CommEvent()
    data class MemberHeard(val member: Member) : CommEvent()        // radio-level presence/position (no TAK)
    data class LinkChanged(val link: Link) : CommEvent()
}

sealed class CommResult {
    object Ok : CommResult()
    data class Applied(val verified: Boolean, val failedChecks: List<String>) : CommResult()
    data class HandedOff(val note: String) : CommResult()
    data class Failed(val reason: String) : CommResult()
    object NotSupported : CommResult()
}

data class ConfigChange(val field: String, val from: String, val to: String)
data class SavedConfig(val id: String, val label: String, val savedAtMs: Long)

data class Capabilities(
    val listRadios: Boolean, val pair: Boolean, val forget: Boolean,
    val previewConfig: Boolean, val verifyConfig: Boolean, val savedConfigs: Boolean,
    val reportingFrequency: Boolean, val networkLink: Boolean,
    val deliverTak: Boolean, val receiveTak: Boolean,
)

data class CheckInRequest(val configDoc: JSONObject, val rideId: String, val callsign: String, val role: Role)
/** v0.3: one interval PER TRANSPORT + the stationary floor (§R5.3); all managed values, tunable from the field.
 *  CODE RULE 1: wifiIntervalSecs null = this implementation has no Wi-Fi path (bare radio). */
data class ReportingRequest(
    val meshIntervalSecs: Int,
    val wifiIntervalSecs: Int?,
    val stationaryIntervalSecs: Int,
    val method: Method,
) { enum class Method { BROADCAST_TO_RIDE_GROUP } }

// ═════════════════════════════════ THE MODULES (each becomes an API) ═════════════════════════════════

/** A · CommLink — connection. Services A1–A6. */
interface CommLink {
    val link: StateFlow<Link>                                         // A1
    /** v0.3: the box whose Wi-Fi this device is on; null = not on a box's Wi-Fi (no Wi-Fi path — LoRa only). */
    val onBoxWifi: StateFlow<String?>
    suspend fun listRadios(): List<RadioEntry>                        // A2 (empty + NotSupported cap. on IP)
    suspend fun connect(radioId: String): CommResult                  // A3
    suspend fun disconnect(): CommResult                              // A4
    suspend fun forget(radioId: String): CommResult                   // A5 (iPad: partial — system pairing is the rider's)
    suspend fun reconnect(): CommResult                               // A6 — ONE implementation (inventory: was 3)
}

/** B · CommIdentity — this device. Service B7. */
interface CommIdentity {
    /** CODE RULE 1: null = the transport has not yet reported it (waiting for the link). */
    suspend fun myDevice(): DeviceInfo?                               // B7
}

/** C · CommConfig — the config doc says WHAT; the implementation decides HOW. Services C8–C11. */
interface CommConfig {
    suspend fun previewConfig(configDoc: JSONObject, callsign: String): Pair<List<ConfigChange>, CommResult>   // C8
    suspend fun applyConfig(configDoc: JSONObject, callsign: String): CommResult                                // C9
    suspend fun savedConfigs(): List<SavedConfig>                                                               // C10
    suspend fun compareSaved(a: String, b: String): Pair<List<ConfigChange>, CommResult>                       // C10
    suspend fun restoreSaved(id: String): CommResult                                                            // C10 — returns a result (inventory: was fire-and-forget)
    suspend fun networkLink(): Pair<String?, CommResult>                                                        // C11
}

/** D · CommMessaging — TAK out, events in. Services D12–D14. */
interface CommMessaging {
    suspend fun deliver(message: CommMessage): CommResult             // D12 — Layer 1 builds the content, passes it in
    suspend fun deliverText(text: String): CommResult                 // v0.3 — plain text on port 1
    suspend fun setReporting(request: ReportingRequest): CommResult   // D13 — ONE implementation (inventory: was 2)
    /** D14 — the event stream: deduped, normalised. Hot; collectors start at the current state via members(). */
    val events: Flow<CommEvent>
    fun members(): List<Member>                                       // snapshot for a screen opening mid-ride
}

/** E · CommSession — joining and leaving a ride's network. Services E15–E16. */
interface CommSession {
    suspend fun checkIn(request: CheckInRequest): CommResult          // E15 = C9 + start reporting
    suspend fun endRide(rideId: String): CommResult                   // E16 = stop reports + clear roles
}

/** G · CommMenus — the comm side's screens, opened through the API (v0.3, §R6.2). GroupTrack's menus list these items
 *  and open them by id; GroupTrack never names a radio screen. The GRP Awareness switch off → items() is empty. */
data class CommMenuItem(val id: String, val label: String, val group: String)
interface CommMenus {
    fun items(): List<CommMenuItem>
    fun open(id: String): CommResult                                  // NotSupported for an unknown id
}

/** F · the plugin as a whole: capabilities + lifecycle. Service F17. */
interface GroupCommPlugin : CommLink, CommIdentity, CommConfig, CommMessaging, CommSession, CommMenus {
    val method: CommMethod
    fun capabilities(): Capabilities                                  // F17
    /** Started at session start when GroupRidingSetup.enabled, or when Settings turns it On; stopped when Off. */
    suspend fun start(setup: GroupRidingSetup): CommResult
    suspend fun stop(): CommResult
}

// ═════════════════════ LAYER 1 SIDE (GroupTrack's own — shown here for the processing model; moves to :grouptrack at cycle 3) ═════════════════════

/** GroupTrack's group state is a pure function of events + time. No tick: apply(event) on arrival;
 *  status(now) derived from each member's staleAtMs (CoT-native); nextDeadlineMs = the earliest staleAtMs. */
interface GroupState {
    fun apply(event: CommEvent)
    fun status(nowMs: Long): List<CartStatus>                         // stale / lost derived from lastHeardMs
    fun nextDeadlineMs(nowMs: Long): Long?                            // null = nothing pending; one timer, not a loop
}
data class CartStatus(val member: Member, val state: State) { enum class State { LIVE, STALE, LOST, REMOVED } }
