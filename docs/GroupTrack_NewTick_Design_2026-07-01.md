# GroupTrack — NEW TICK / CONVOY PROCESS DESIGN (replacement, 2026-07-01)
Authoritative design for the rewritten convoy tick/lead/identity/track process. From Fred's new_tick.txt + 07-01 decisions. This is the NEW PROCESS that replaces the current tick/lead/track accumulation (which has cross-cart contamination — see demolition inventory in RebuildPlan). Clean model: "each cart posts its own position; no cross-cart substitution."

## A. APP OPEN → CONVOY MAP LOAD
1. Convoy map opens; **splash screen shows** while the map draws initial GPS (Android device GPS or radio GPS) to POSITION the map.
2. Splash has a DELIBERATE DELAY — stays up until load tasks complete, so the app presents a fully-drawn map, not a half-loaded state.
3. **My-cart id ASSIGNED at open** (event-driven): resolve from radio (`!%08x`) if a node is connected, else `!phone` (device). If a node CONNECTS AFTER my-cart is assigned, the connection UPDATES my-cart id. (Hooks the existing myNodeInfo observer.)
4. **My-cart id LOCKS during recording** (safety): once recording, an inadvertent disconnect/reconnect does NOT reassign identity — prevents mid-ride identity corruption. Unlock on stop → resume event-driven updates. [Fred: confirm keep the lock — added for disconnect safety.]

## B. TICK = STATE MACHINE — 2 STATES (IDLE / RECORDING), 2s CADENCE, ALWAYS LOOPING
Tick LOOP always runs at 2s cadence (from map open). WHICH PROCESSES execute each cycle depends on STATE. Tick is a STATE MACHINE, not "always do everything."

**TWO tick states:**
- **IDLE** (map open, not recording): tick draws CART POSITIONS + positions the map. DISPLAY ONLY. (No measurements, no tracking, no GPX.)
- **RECORDING** (RECORD pressed): tick adds the recording process set —
  - MEASUREMENTS (HUD: my-cart, group, selected-cart, distance travelled, all derived values)
  - LEAD / MY-CART TRACKING (snap2 heal — lead track, replace-with-recorded-points)
  - GPX TRACK RECORDING (write snapped points)

**PAUSE and SLEEPING are NOT tick states — they affect GPX WRITES ONLY.**
During pause/sleep the tick stays in RECORDING state: still drawing, still measuring, still tracking. ONLY the GPX writer suspends. So:
```
TICK STATE (2):   IDLE | RECORDING
GPX WRITER sub-state (only during RECORDING):  writing | paused | sleeping
  paused/sleeping → suspend GPX appends; tick continues drawing/measuring/tracking normally
```
This matches ConvoyGpsService (pauseTrack) + recording-independent-of-display. The existing `RecordingState { IDLE, RECORDING, PAUSED, SLEEPING }` enum: IDLE/RECORDING are the TICK states; PAUSED/SLEEPING are GPX-WRITER sub-states OF recording (do NOT branch the tick on them).

**Timing:** radio broadcasts every 5s; tick 2s avoids ~9s swings. ONE tick loop, ONE ConvoyViewModel instance (Fix 1). RECORD transitions IDLE→RECORDING (switches on the recording process set); stop → RECORDING→IDLE.

## C. RECORD FUNCTION
1. On RECORD press: prompt user to SELECT the lead cart from the node list — UNLESS the node list is 1 cart, then lead is AUTOMATED (solo → that cart is lead, no prompt).
2. Lead id locks for the session. My-cart id locks (B4).
3. Snap2 healing + GPX recording begin (within the always-running tick).

## D. TICK PER-CYCLE WORK (every 2s)
1. **Each cart's position for display** — compute EVERY tick. Each cart posts ITS OWN reported position; if silent, ITS OWN last-known ONLY. **NO cross-cart substitution** (remove legacy CART SUB / furthestLat borrowing / proxy snap — the contamination sources).
2. **Lead cart track (display path):** use the LEAD cart's NEW position as the lead-track display path → SNAP to trails/track info. (Lead's OWN real position — leadActualLat/Lon — never proxy/substituted.)
3. **Snap2 HEAL (during recording):** on each Android device, REPLACE the lead cart's track points with THIS device's last Android data point, snapped to my-cart's path; the lead cart's (provisional) data point is REMOVED. → one continuous track: provisional lead ahead, this-device recorded+snapped behind. RECORDED track IS the snap2 (snapped) result; OFF-TRAIL → record raw (OFF_TRACK_MILES gate).
4. **HUD / measurements — compute EVERY tick:** my-cart values, group values, selected-cart info, plus all derived values (distance travelled, etc.) displayed on screens. (myCartId → isMyCart drives proximity: milesToLead/milesToTail/feetAhead/Behind — must have correct identity, hence A3.)

## E. CLEAN-MODEL INVARIANTS (what makes this correct vs the legacy mess)
- Each cart draws at ITS OWN position or ITS OWN last-known. A cart NEVER wears another cart's coordinates.
- `lastNodePositions[nodeId]` stores ONLY that node's own reported positions — never a substituted neighbor position (removes the 662 cross-write bug).
- Lead track sources from EXACTLY TWO clean places: (1) lead's own real position (provisional ahead), (2) this device's own recording (authoritative behind). Both snapped. No proxy, no cross-cart.
- One instance, one always-on 2s tick, one identity source (event-driven idle / locked recording).

## F. BUILD ORDER (prerequisites before the heal engine)
1. Fix 1 — ONE instance/tick (dedupe hiltViewModel).
2. Fix 2 — event-driven + record-locked identity (observer hook; delete HOTEL-10 default). Fixes HUD + progress reporting.
3. Fix 3 — lead: select-from-list, or automate if 1 cart; reset lock on fresh record; deterministic.
4. Remove contamination — CART SUB substitution (646-652), cross-cart write (662), proxySnapped/proxy-snap, append-only accumulation.
5. Change tick cadence to 2s, ensure always-on.
6. Build the snap2 HEAL engine (editable point-by-point lead track; heal boundary = this device's projected position; snap both halves; recorded = snapped, off-trail raw). CENTERPIECE.
7. HUD/measurements every tick (verify identity-driven proximity correct).
Each step: build + on-device verify + commit (named files). Bank a marker before step 6.

## OPEN CONFIRMATIONS (Fred)
- Keep the my-cart RECORD-LOCK (D/A4)? (added for disconnect safety; not in original notes)
- new_tick.txt "tick starts with record" = heal/record behavior starts at record, tick LOOP always on — confirm.
- Snap threshold + OFF_TRACK_MILES values (from ConvoyConfig) for snap/record gate.
