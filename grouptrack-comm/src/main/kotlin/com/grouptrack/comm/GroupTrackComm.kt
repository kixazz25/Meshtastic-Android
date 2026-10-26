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
/** COMMHEALTH-2026-10-11 (additive, Fred 10-11: keeping the link is the comm side's job; GroupTrack reports it).
 *  talking = the radio is really passing traffic (not just "connected"). recovering = the comm side is reconnecting on
 *  its own. attempts = reconnects since the radio last talked. reason -- CODE RULE 1: null = talking, nothing to say;
 *  else NOT_CONNECTED · CONNECTING · RADIO_SILENT · CONNECT_TIMEOUT · RECOVERY_EXHAUSTED · RADIO_UNPAIRED (GroupTrack owns the words).
 *  COMMHEALTH2-2026-10-11: CONNECTING added (a reconnect under way is not "no radio"). */
data class LinkHealth(val talking: Boolean, val recovering: Boolean, val attempts: Int, val reason: String?)
enum class Role { LEADER, MIDDLE, TAIL_GUNNER, RIDER }          // GroupTrack's roles; TAK mapping is Layer 1's

data class RadioEntry(val id: String, val name: String, val paired: Boolean,
                      /** CODE RULE 1: null = the transport cannot measure signal (IP; some scans). */
                      val signalDbm: Int?)

/** id = the transport's id for this device -- the same form as member ids (mesh: the node number as text). */
data class DeviceInfo(val id: String, val name: String,
                      /** CODE RULE 1: null = not reported by this device/transport. */
                      val model: String?, val firmware: String?,
                      /** COMMIDENT-2026-10-09 (additive): the hardware's own id (mesh: the radio's device id), carried into
                       *  ride records. CODE RULE 1: null = not reported by this device/transport. */
                      val deviceId: String?)

/** A member as heard. Position fields null = that member has sent no fix (a real state on a mesh). */
data class Member(
    val id: String, val callsign: String,
    val staleAtMs: Long?,                            // from the member's last message (CoT stale); null = never sent one
    val latitude: Double?, val longitude: Double?, val altitudeM: Int?,
    val fixTimeMs: Long?, val lastHeardMs: Long,
    val reportedRole: Role?,                         // null = no TAK report from this member yet
    val channelUtilPct: Float?,                      // null = not reported (IP)
    // HEARDSHADOW-2026-10-13 (P5.1a, D1, additive). CODE RULE 1 (both): null = not reported by the transport (IP may not carry them).
    val headingDeg: Float?,                          // course over ground, 0-360
    val batteryPct: Int?,
    // STATSPANEL-2026-10-20 (additive): the radio link of the last report. CODE RULE 1 (all four): null = not reported by the transport
    // (an IP transport carries no radio link quality).
    val snr: Float?, val rssi: Int?, val hopsAway: Int?, val airUtilPct: Float?,
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
    /** POLYLINE-2026-10-20 (additive): another cart's GAP POLYLINE received (its real positions across its dead air, equally spaced in time). */
    data class PolylineReceived(val polyline: GapPolyline, val via: Set<Transport>) : CommEvent()
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

/** COMMIDENT-2026-10-09 KMP GUARDRAIL (design v18 R8): configDoc is the config doc as JSON TEXT (was org.json.JSONObject,
 *  which is Android-only). Changed before anything implements or calls it. */
data class CheckInRequest(val configDoc: String, val rideId: String, val callsign: String, val role: Role)
/** v0.3: one interval PER TRANSPORT + the stationary floor (§R5.3); all managed values, tunable from the field.
 *  CODE RULE 1: wifiIntervalSecs null = this implementation has no Wi-Fi path (bare radio). */
data class ReportingRequest(
    val meshIntervalSecs: Int,
    val wifiIntervalSecs: Int?,
    val stationaryIntervalSecs: Int,
    val method: Method,
    /* COMMREPORT-2026-10-12 (additive): field tuning for a transport that must restart to take the change (mesh: the wait after the
     * reconnect, then the gap before the second reconnect -- GPSCYCLE2). An implementation that does not restart ignores them. */
    val settleWaitSecs: Int = 20,
    val reconnectGapSecs: Int = 4,
) { enum class Method { BROADCAST_TO_RIDE_GROUP } }

/** POLYTEST-2026-10-20 (Fred 10-20): a GAP POLYLINE -- [lats]/[lons] equally spaced in time from [t0Ms], [stepMs] apart (20 points).
 *  senderUid = the sender's radio id; callsign = its rider. */
data class GapPolyline(val senderUid: String, val callsign: String, val t0Ms: Long, val stepMs: Long, val lats: List<Double>, val lons: List<Double>)

/** COMMREPORT-2026-10-12: the steps of a reporting change, for GroupTrack's words. UNCHANGED = already so, nothing written. */
enum class ReportingStep { WRITING, WRITTEN, RESTARTING, RECONNECTING_AGAIN, UNCHANGED, DONE }

// ═════════════════════════════════ THE MODULES (each becomes an API) ═════════════════════════════════

/** A · CommLink — connection. Services A1–A6. */
interface CommLink {
    val link: StateFlow<Link>                                         // A1
    val health: StateFlow<LinkHealth>                                 // A1b -- COMMHEALTH-2026-10-11
    /** v0.3: the box whose Wi-Fi this device is on; null = not on a box's Wi-Fi (no Wi-Fi path — LoRa only). */
    val onBoxWifi: StateFlow<String?>
    suspend fun listRadios(): List<RadioEntry>                        // A2 (empty + NotSupported cap. on IP)
    suspend fun connect(radioId: String): CommResult                  // A3
    suspend fun disconnect(): CommResult                              // A4
    suspend fun forget(radioId: String): CommResult                   // A5 (iPad: partial — system pairing is the rider's)
    suspend fun reconnect(): CommResult                               // A6 — ONE implementation (inventory: was 3)
    /** SLEEPCOMM-2026-10-14 (P5.3, additive): put back the radio that was in use before a DELIBERATE disconnect (sleep). No Bluetooth
     *  check and no pairing: the transport retries by itself once Bluetooth is back. Default: connect(). */
    suspend fun resume(radioId: String): CommResult = connect(radioId)
    /** GRPMODE-2026-10-13 (additive): group awareness on/off. Off = the transport goes quiet -- no link kept, no reconnects; the radio
     *  in use is remembered. On = the remembered radio is used again. */
    suspend fun setActive(active: Boolean): CommResult
    /** COMMNAMES-2026-10-11: the name a radio LAST USED, for a radio in a list: by the address it answers on (radioId), then by its
     *  member id, then by its Bluetooth name's suffix. CODE RULE 1: memberId null = a scanned radio not yet heard;
     *  result null = not known -- the caller shows the radio's own or Bluetooth name. */
    fun radioName(radioId: String, memberId: String?, bluetoothName: String): String?
    /** COMMRADIOS-2026-10-11: the radios this device can use -- PAIRED first, then radios found nearby (new to this device). */
    val radios: StateFlow<List<RadioEntry>>
    /** COMMRADIOS-2026-10-11: the radio this device uses. CODE RULE 1: null = none chosen. */
    val selectedRadioId: StateFlow<String?>
    /** COMMRADIOS-2026-10-11: look for radios nearby (while a radio list is on screen). Failed(PERMISSION_MISSING / BLUETOOTH_OFF). */
    fun startScan(): CommResult
    fun stopScan()
    /** COMMBTOFF-2026-10-11: why radios nearby cannot be looked for, LIVE (follows Bluetooth on/off and the permission).
     *  CODE RULE 1: null = nothing wrong; else BLUETOOTH_OFF / PERMISSION_MISSING -- GroupTrack says so in its words. */
    val scanProblem: StateFlow<String?>
}

/** B · CommIdentity — this device. Service B7. */
interface CommIdentity {
    /** CODE RULE 1: null = the transport has not yet reported it (waiting for the link). */
    suspend fun myDevice(): DeviceInfo?                               // B7
    /** COMMIDENT-2026-10-09 (additive): the same, as it changes (a radio connects, is renamed or swapped). Emits the current
     *  value first. GroupTrack keeps the latest for screens and the tick. CODE RULE 1: null = as myDevice(). */
    val device: Flow<DeviceInfo?>
}

/** C · CommConfig — the config doc says WHAT; the implementation decides HOW. Services C8–C11.
 *  COMMIDENT-2026-10-09: configDoc = the config doc as JSON text (KMP guardrail). */
interface CommConfig {
    suspend fun previewConfig(configDoc: String, callsign: String): Pair<List<ConfigChange>, CommResult>   // C8
    suspend fun applyConfig(configDoc: String, callsign: String): CommResult                                // C9
    suspend fun savedConfigs(): List<SavedConfig>                                                               // C10
    suspend fun compareSaved(a: String, b: String): Pair<List<ConfigChange>, CommResult>                       // C10
    suspend fun restoreSaved(id: String): CommResult                                                            // C10 — returns a result (inventory: was fire-and-forget)
    suspend fun networkLink(): Pair<String?, CommResult>                                                        // C11
    /* COMMCONFIG-2026-10-12 (P4.2, additive): the apply as it runs and when it ends; the ride apply with the title its save is filed
     * under; a radio's own save as a target; what changed on the radio since its last save. */
    /** CODE RULE 1: null = no apply since the app started. */
    val applyReport: StateFlow<ApplyReport?>
    suspend fun applyRide(request: ApplyRequest): CommResult
    /** PRESETPANEL-2026-10-20 (additive): the radio's CURRENT settings with only the channel preset (by name, e.g. MEDIUM_FAST, LONG_FAST)
     *  and the moving reporting interval replaced -- through the same apply as applyRide (plan, write, wait, verify, save),
     *  reported on applyReport. Every radio in a group must share the preset. Default: not supported. */
    suspend fun applyRadioChange(preset: String, movingIntervalSecs: Int): CommResult = CommResult.NotSupported
    suspend fun previewSaved(id: String): Pair<List<ConfigChange>, CommResult>
    suspend fun applySaved(id: String, saveTitle: String): CommResult
    suspend fun driftSinceLastSave(): List<ConfigChange>
    /** RADIONET-2026-10-12 (additive): the network the radio is on, as text (MESH: the primary channel's name; "" = the radio's
     *  own default). GroupTrack decides what it means ("Group..." = set up for GroupTrack).
     *  CODE RULE 1: null = not known -- no radio, or its settings not read yet. */
    suspend fun currentNetwork(): String?
    /* SAVEDCFG-2026-10-12 (P4.3, additive): the saved-configs screen through the API. */
    /** This radio's saves, newest first. Empty = no radio, or nothing saved for it yet. */
    suspend fun savedSetups(): List<SavedSetup>
    /** Compare two configs by id -- a save's id, or [RADIO_NOW] for the radio as it is. Each row says if it is a managed setting
     *  (what an apply would change). The comparison never applies anything. */
    suspend fun compareConfigs(a: String, b: String): Pair<List<ConfigDifference>, CommResult>
    /** CONFIGPDF-2026-10-26 (additive): the values GroupTrack sets on a radio, for a config by id (a save, or [RADIO_NOW]), labelled
     *  by section in the order to set them by hand -- the Archived setups PDF. Default: not supported. */
    suspend fun managedSettings(id: String): Pair<List<ConfigSetting>, CommResult> = emptyList<ConfigSetting>() to CommResult.NotSupported
    /** COMMSAVES-2026-10-14 (P5.4b, additive): what THIS tablet applied to a radio, newest first, from the comm side's own records.
     *  memberId = the radio's id (mesh: node number as text). Empty = nothing recorded. Default: empty. */
    fun appliedHistory(memberId: String): List<AppliedRecord> = emptyList()
    /** COMMSAVES-2026-10-14: any setup saved on this tablet, for any radio (the first-start question -- runs with no radio). Default: false. */
    fun hasAnySavedSetup(): Boolean = false
    companion object { /** SAVEDCFG-2026-10-12: the id that means "the radio as it is right now". */ const val RADIO_NOW = "radio-now" }
}

/** SAVEDCFG-2026-10-12: one save, in words GroupTrack can show as they are. detail = "applied 2026-10-12 11:39 \u00b7 verified \u2713".
 *  CODE RULE 1: rideId null = the GroupTrack default, "as found", or an old save -- none of them belongs to a ride. */
data class SavedSetup(val id: String, val title: String, val detail: String, val savedAtMs: Long, val rideId: String?)
/** COMMSAVES-2026-10-14: one apply recorded on this tablet. title = the config's title as saved ("" = none); appliedAt = local date-time
 *  as recorded (ISO). CODE RULE 1: rideId null = the GroupTrack default, "as found" or an old save -- no ride. */
data class AppliedRecord(val title: String, val rideId: String?, val appliedAt: String)
/** SAVEDCFG-2026-10-12: one difference between two configs; managed = a setting GroupTrack's apply sets. */
data class ConfigDifference(val field: String, val a: String, val b: String, val managed: Boolean)
/** CONFIGPDF-2026-10-26: one value GroupTrack sets -- section ("LoRa"), name as a rider reads it, value as text. */
data class ConfigSetting(val section: String, val name: String, val value: String)

/** COMMCONFIG-2026-10-12: what to apply -- the config doc (the ride file, JSON), the name the radio gets, the title of the config
 *  ("GroupTrack default" or the ride's name), the title its save is filed under, and the ride id (CODE RULE 1: null = the
 *  GroupTrack default, which belongs to no ride). */
data class ApplyRequest(val configDoc: String, val callsign: String, val title: String, val saveTitle: String, val rideId: String?)
/** COMMCONFIG-2026-10-12: one check after an apply -- the setting, what was wanted, what the radio holds. */
data class ConfigCheck(val field: String, val expected: String, val actual: String) { val ok: Boolean get() = expected == actual }
/** COMMCONFIG-2026-10-12: an apply as it runs and when it ends. [log] = the comm side's technical progress lines, shown as they are.
 *  CODE RULE 1: stoppedAt / stopReason null = not stopped (the radio did not come back after a group); savedAs null = nothing
 *  saved (stopped, failed, or still running). */
data class ApplyReport(
    val running: Boolean, val log: List<String>, val checks: List<ConfigCheck>, val verified: Boolean,
    val stoppedAt: String?, val stopReason: String?, val groupsWritten: List<String>, val savedAs: String?,
)

/** D · CommMessaging — TAK out, events in. Services D12–D14. */
interface CommMessaging {
    suspend fun deliver(message: CommMessage): CommResult             // D12 — Layer 1 builds the content, passes it in
    suspend fun deliverText(text: String): CommResult                 // v0.3 — plain text on port 1
    /** POLYTEST-2026-10-20 (additive): the lead's GAP POLYLINE -- its real positions across its dead air, equally spaced in time -- as the
     *  standard CoT user-drawn polyline (u-d-f). Default: not supported. */
    suspend fun deliverPolyline(p: GapPolyline): CommResult = CommResult.NotSupported
    suspend fun setReporting(request: ReportingRequest): CommResult   // D13 — ONE implementation (inventory: was 2)
    /** COMMREPORT-2026-10-12 (additive): the same, telling each step as it happens. Default: no steps told. */
    suspend fun setReporting(request: ReportingRequest, onStep: (ReportingStep) -> Unit): CommResult = setReporting(request)
    /** D14 — the event stream: deduped, normalised. Hot; collectors start at the current state via members(). */
    val events: Flow<CommEvent>
    fun members(): List<Member>                                       // snapshot for a screen opening mid-ride
    /** REPORTNOW-2026-10-13 (P5.2, additive): the moving reporting interval the radio holds NOW, in seconds (read only).
     *  CODE RULE 1: null = not read yet, or the transport cannot tell. Default: null. */
    val currentReportingSecs: Flow<Int?> get() = kotlinx.coroutines.flow.flowOf(null)
    /** PRESETPANEL-2026-10-20 (additive): the channel preset the radio holds NOW, by name (read only).
     *  CODE RULE 1: null = not read yet, or the transport has no preset. Default: null. */
    val currentPreset: Flow<String?> get() = kotlinx.coroutines.flow.flowOf(null)
    /* COMMRECV-2026-10-08 (v0.3, additive; 2.7b step 2b): READ-WHAT-WAS-HEARD calls for GroupTrack's tick, which still runs
     * until cycle 3 replaces it with the events above. memberId = the member's id as the transport knows it (mesh: the
     * radio's node number as text). */
    /** The role a member last reported; null = no report, or a role that is not a ride role (a bare radio). */
    fun reportedRole(memberId: String): Role?
    /** When a member's last TAK report arrived (ms); null = none since the app started. Our own radio's is the
     *  Bluetooth hand-off heartbeat (LINKHB-2026-10-05). */
    fun lastReportFromMs(memberId: String): Long?
    /** COMMNAMES-2026-10-11: the name this member's radio LAST USED (the comm side's name table). CODE RULE 1: null = not known yet
     *  -- the caller falls back to what it has (e.g. the name first heard). */
    fun memberName(memberId: String): String?
    /** MY role, kept with everyone else's (OWNROLE-2026-09-28) -- what reportedRole() answers for my own id. */
    fun recordOwnRole(memberId: String, role: Role)
    /** END: the ride's roles end with the ride (LEADCLEAN-2026-09-29). */
    fun clearRoles()
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
    /** COMMSETTINGS-2026-10-12: the comm-side screen open over GroupTrack now. CODE RULE 1: null = none (GroupTrack's own screens). */
    val openMenu: StateFlow<String?>
    /** COMMSETTINGS-2026-10-12: close a screen opened by open(id) -- GroupTrack's way back. NotSupported if it is not open. */
    fun close(id: String): CommResult
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
