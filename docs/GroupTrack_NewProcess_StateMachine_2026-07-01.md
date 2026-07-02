# GroupTrack — NEW CONVOY PROCESS: STATE MACHINE & PER-STATE EXECUTION (2026-07-01)
The authoritative process spec for the 2.6 tick/lead/identity/track rewrite. Defines every state and exactly what executes in each. Companion to: GroupTrack_Measurement_Catalog (values/formulas to preserve), GroupTrack_TickLead_RebuildPlan (demolition/build order), GroupTrack_NewTick_Design (flow). Grounded in real code (ConvoyViewModel/Engine/Screen/GpsService, 07-01).

## 0. FOUNDING PRINCIPLE — EACH DEVICE RUNS ITS OWN MODEL INDEPENDENTLY
There is NO central authority. Every device (phone) runs its OWN: tick loop, my-cart identity resolution, ConvoyEngine compute, HUD/measurement calcs, lead track, and GPX recording. A device computes its convoy picture from the node data IT receives (radio mesh + its own GPS).
CONSEQUENCES (these drive the whole design):
- Each device's model must be CLEAN and SELF-CONTAINED — it must NOT corrupt its own model with another device's data. (This is WHY the legacy CART SUB cross-cart substitution + proxy-snap were bugs: a device was overwriting one cart's position with another's inside its OWN model.)
- "My cart" is device-relative: each device identifies ITS OWN node (radio `!%08x` or `!phone`) and computes progress (milesToLead etc.) from ITS OWN perspective.
- The lead TRACK a device draws/records is ITS OWN: provisional-ahead from the lead's broadcast position, authoritative-behind from THIS device's own recording. Two devices recording the same ride each produce their own snapped track.
- No device's tick depends on another device's tick. Broadcasts (radio, 5s) are shared DATA; the processing is per-device.

## 1. TICK = STATE MACHINE (2 tick states), ALWAYS LOOPING @ 2s
Tick LOOP runs continuously at 2-second cadence from convoy-map open (radio broadcasts every 5s; 2s tick avoids ~9s display swings). ONE loop, ONE ConvoyViewModel instance per device (Fix 1: no duplicate hiltViewModel). WHICH processes run each cycle = STATE.

### TICK STATE A — IDLE (map open, not recording)
Purpose: position the map + show live convoy. Display only.
EXECUTES each 2s tick:
- readLiveNodes → build node list (this device's own GPS as `!phone`, or radio mesh nodes). Each node at ITS OWN reported position or ITS OWN last-known (NO cross-cart substitution).
- Identity: `_myCartId` maintained by EVENT (connect→`!%08x`, no-node→`!phone`) — see §3. (Not recomputed in tick; tick just reads it.)
- ConvoyEngine.compute(nodes, myCartId, ...) → positions, roles (lead null until record), convoyPosition sort, proximity, span, counts.
- Draw cart markers (color/symbol/size per node).
- Update GROUP HUD display values (span, active, lost, carts, lead/tail callsign) + MY-CART HUD (identity, heading, battery, altitude, speed) as data allows.
DOES NOT: accumulate lead track, heal, record GPX, or run the odometer.

### TICK STATE B — RECORDING (RECORD pressed)
Purpose: everything IDLE does PLUS measurements, lead/my-cart snap2 tracking, and GPX recording.
EXECUTES each 2s tick (all of IDLE, plus):
- MEASUREMENTS/HUD (full): all per-cart, group, my-cart, selected-cart values + derived (distanceMiles odometer, milesToLead/Tail, gaps, speed windows). Per the Measurement Catalog.
- LEAD / MY-CART SNAP2 TRACKING:
  - Provisional AHEAD: lead's OWN broadcast position (leadActualLat/Lon — never substituted), snapped to trail/track geometry.
  - Heal BEHIND: replace provisional points behind THIS device's projected position with THIS device's OWN recorded (snapped) points; delete the superseded provisional points. One continuous track.
  - Snap gate: on-trail → snap; off-trail (OFF_TRACK_MILES) → raw.
- GPX RECORDING: this device's points written (snapped = the recorded track, per decision) via ConvoyGpsService.onGpsUpdate → accumulate odometer + write trkpt. (Snap2 injection point: snap before distance-accum+write.)
IDENTITY LOCKED here (see §3): disconnect during RECORDING does not reassign my-cart.

## 2. GPX-WRITER SUB-STATES (only meaningful during RECORDING) — NOT tick states
PAUSE and SLEEPING affect GPX WRITES ONLY. Tick stays in RECORDING (keeps drawing, measuring, tracking, updating HUD + odometer display). Only the writer suspends.
- WRITING: append points (snapped) to GPX; accumulate odometer.
- PAUSED (user): suspend GPX appends. Tick continues full RECORDING processing. (ConvoyScreen: PAUSE↔RESUME.)
- SLEEPING (auto): no movement > SLEEP_THRESHOLD (10 min, MOVE_THRESHOLD_FEET=50 gate) → auto-pause writes, show ASLEEP; wake on movement/user. Tick continues.
Existing enum `RecordingState { IDLE, RECORDING, PAUSED, SLEEPING }` maps: IDLE/RECORDING = TICK states; PAUSED/SLEEPING = WRITER sub-states of RECORDING. (UI already shows odometer for state != IDLE, consistent.)

## 3. IDENTITY LIFECYCLE (event-driven idle, locked recording) — per device
- APP OPEN / IDLE: resolve `_myCartId` on CONNECTION EVENTS via the myNodeInfo observer — connect → `!%08x`.format(myNodeNum); no node → `!phone`. Set once per event, stable. (NOT per-tick; delete HOTEL-10 default.)
- RECORD press → LOCK: freeze `_myCartId` for the session (this is the startGroupTrack set-point, now a lock). Also lock lead.
- DURING RECORDING: connection/disconnect events do NOT change `_myCartId` (guard: `if (!_trackActive.value)` in the observer). An inadvertent BLE drop / supervision timeout cannot reassign identity mid-track.
- STOP → UNLOCK: resume event-driven idle resolution.
WHY it matters: `myCartId`→`isMyCart` drives progress reporting (milesToLead/Tail, gaps) AND the radio-GPS position feed AND the HUD label. Correct pre-record identity = correct progress the whole session.

## 4. LEAD LIFECYCLE — per device
- IDLE: no lead (lead null; nothing drawn as lead track).
- RECORD press: if node list == 1 → automate (that node is lead). If > 1 → prompt user to select lead from node list. Lock lead for session. (Reset any stale lock on fresh record.)
- Optional in-tick self-heal (rolled-back Stage-2): recording + 1 node + no lead → assign that node lead (safety for solo).
- Lead's OWN broadcast position (leadActualLat/Lon) feeds provisional-ahead track. NEVER proxy/substituted.

## 5. TRANSITIONS
```
        app opens convoy map
                │
                ▼
        ┌──────────────┐   RECORD (select/auto lead, lock identity)   ┌───────────────┐
        │  IDLE (tick) │ ───────────────────────────────────────────▶ │ RECORDING(tick)│
        │ draw+HUD     │ ◀─────────────────────────────────────────── │ +meas+heal+GPX │
        └──────────────┘                 STOP (unlock)                 └───────┬───────┘
                                                                                │ writer sub-states
                                                                   PAUSE ◀──────┼─────▶ SLEEP(auto)
                                                                   (writes suspend; tick unaffected)
```

## 6. WHAT EXECUTES WHERE — quick matrix
| Process | IDLE | RECORDING | PAUSED/SLEEPING |
|---|---|---|---|
| readLiveNodes + own-position model | ✅ | ✅ | ✅ (tick continues) |
| identity (event-driven) | ✅ resolve | 🔒 locked | 🔒 locked |
| ConvoyEngine compute (positions/roles/sort/proximity) | ✅ | ✅ | ✅ |
| draw markers | ✅ | ✅ | ✅ |
| group + my-cart HUD | ✅ (as data allows) | ✅ full | ✅ full |
| measurements/derived (odometer, milesToLead, gaps) | ❌ | ✅ | ✅ (display continues) |
| lead snap2 track (provisional+heal) | ❌ | ✅ | ✅ (tracking continues) |
| GPX write (trkpt) + odometer accumulate | ❌ | ✅ | ⏸ writes suspended |

## 7. CLEAN-MODEL INVARIANTS (per device)
1. Each cart drawn at its OWN reported position or its OWN last-known — never another cart's. `lastNodePositions[id]` holds ONLY that node's own positions.
2. Lead track sources from exactly TWO clean places: lead's own broadcast (provisional-ahead) + THIS device's own recording (authoritative-behind). No proxy, no cross-cart.
3. One ViewModel, one always-on 2s tick, one identity source (event/idle, locked/recording), per device.
4. Recorded track = snapped (on-trail) / raw (off-trail); odometer + GPX from the same point stream.
5. This device never depends on another device's tick; broadcasts are shared data, processing is local.

## 8. OPEN DECISIONS (Fred, before/at build)
- Snap2 distance: measure odometer on RAW or SNAPPED points? (injection at ConvoyGpsService.onGpsUpdate before line 309.)
- Speed: keep source-split (radio 60s window / phone instant ×2.23694) or unify?
- Keep in-tick lead self-heal (§4) — recommended for solo safety.
- Confirm identity RECORD-LOCK (§3) — recommended for disconnect safety.
