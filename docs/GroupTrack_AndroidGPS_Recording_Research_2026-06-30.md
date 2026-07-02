# Research Brief — Android-GPS Track Recording (CORRECTED) — 2026-06-30
**Recorder:** `ConvoyGpsService.kt` · **Orchestrator:** `ConvoyViewModel.kt` · **Lead calc:** `ConvoyEngine.kt`
**Source:** xref (`function_universe_raw`) + AllDocs history. Corrected per Fred: the RECORDED GPX is ALWAYS Android phone GPS; RADIO drives only the on-screen lead-cart line.

## ⭐ THE CORRECTED ARCHITECTURE — two INDEPENDENT flows
1. **GPX RECORDING (the saved file) = ANDROID phone GPS, ALWAYS.**
   `ConvoyViewModel.startRecording()` (~457) → `gpsService.startTrack()` → `ConvoyGpsService.startLocationUpdates()` (~251) → `LocationManager.requestLocationUpdates(GPS_PROVIDER, 2000, 1f)` (AllDocs 4863) → `onGpsUpdate()` (~288) → `writeGpxPoint()` (~393). `stopRecording()` (~471) → `finalizeTrack()` (~214/487). This writes the `<trkpt>`s. It does NOT use radio.
2. **ON-SCREEN LEAD-CART LINE = RADIO, display only.**
   `tick()` (~570) reads radio nodes; `leadIsReporting = leadNode.latitude != 0.0` (~682); builds `ConvoyEngine.LeadTrackSegment` (~767) → `_leadTrackSegments` (~299) → drawn on the map. This is the live convoy line; it is NOT written to the GPX.
3. **THE CROSSOVER (watch this):** `tick()` also reads `gpsService?.lastLat/lastLon` (~819) and `bindGpsService` builds a `LeadTrackSegment` from the service position (~438). So the service's Android position ALSO feeds a display segment. Radio-line vs Android-GPX vs service-position-segment can get tangled here.

**So "radio records perfectly" was a misread:** radio makes the on-screen LINE look right; the SAVED GPX is Android-sourced in every case. The failing thing (empty/truncated GPX) is the Android recording path — which is the ONLY GPX writer. There is effectively ONE GPX source (Android), and it's the one failing.

## ⚠️ WHAT TO VERIFY FIRST (this changes the whole hypothesis set)
**Confirm what "good" meant in each test by inspecting the SAVED GPX, not the screen:**
- The certified "radio recording" today — pull its `<hash>.gpx`, count `<trkpt>` and check for `</trk>`. If it has real points, Android recording CAN work (so the bug is conditional, not total). If that file is actually thin/truncated too, then GPX recording is broadly broken and the screen line fooled us.
- Compare a known-good recorded file vs "mike ride June 5" (truncated). Are the good files from a specific path/condition?

## HYPOTHESES (re-ranked for "Android is the only GPX source")
### H1 — Cold-start first fix (STRONGEST; fits "fails on FIRST attempt")
AllDocs 6112: `ConvoyViewModel.startPhoneGps()` (~111) was ADDED in 2.6 because `getLastKnownLocation` returned null on cold start (map pin didn't appear) — fixed with an active 2s listener. The RECORDER's `startLocationUpdates` likely still has the ORIGINAL cold-start gap: at record-start no fix has arrived, nothing is written before stop → empty/0-point/truncated. `startPhoneGps` (map) already solved this; the recorder never got the same treatment. **First-attempt-empty is the signature of exactly this.**
- Does `startPhoneGps` (map, WORKS) and `startLocationUpdates` (recorder, FAILS) use the same provider/args? If the map one warms GPS and the recorder one cold-requests GPS_PROVIDER, the recorder starts from null.
- Possible interaction: if `startPhoneGps` is only started "when no radio connected," then WITH a radio the phone GPS may not be warm when recording starts → recorder's cold GPS_PROVIDER yields no early points. (Check the `startPhoneGps` start condition ~111-140.)

### H2 — min-distance 1f + slow provider suppresses early points
`requestLocationUpdates(GPS_PROVIDER, 2000, 1f)` — 1-meter filter + raw GPS_PROVIDER cold latency can drop the first fixes (jitter-in-place or no-fix) so the file opens but no point lands. Consider fused provider and/or minDistance 0 (filter later).

### H3 — foregroundServiceType/location (verify only; likely already fixed)
April bug: `foregroundServiceType=health` + location calls → SecurityException/denied. Reverted to `location` in April (AllDocs 3685/3689). VERIFY no regression: `grep -n 'ConvoyGpsService' AndroidManifest.xml` + the `<service>` block has `foregroundServiceType="location"` and `FOREGROUND_SERVICE_LOCATION` is present. (If regressed, Android recording dies while the radio LINE still draws — matches symptom — so don't skip the check.)

### H4 — permission in Service context vs VM context
`startPhoneGps` runs from the VM/activity; the recorder from the Service. If the runtime location-permission state differs, the service's `requestLocationUpdates` is silently denied. Cheap to rule out with a permission log at `startLocationUpdates`.

## NEXT-SESSION PLAN — INSTRUMENT FIRST (the 06-30 lesson)
1. **Pull code (no changes):**
   - `sed -n '111,145p' …/ConvoyViewModel.kt` — `startPhoneGps` (provider/args/start-condition — the WORKING map path).
   - `sed -n '457,495p' …/ConvoyViewModel.kt` — `startRecording`/`stopRecording`/`finalizeTrack` wiring.
   - `sed -n '145,160p'` + `sed -n '238,320p'` …/ConvoyGpsService.kt — `startTrack`, `useRadioGps`, `onRadioPosition`, `startLocationUpdates`, `onGpsUpdate`.
   - `grep -n 'ConvoyGpsService' AndroidManifest.xml` + the `<service>` block (H3 check).
2. **Verify saved-GPX reality (see "verify first"):** pull the "good" radio-test file + count trkpts/check `</trk>`.
3. **Instrument + one build:** log in `startLocationUpdates` (provider, minTime, minDist, permission granted?), in the `LocationListener.onLocationChanged` (EVERY raw callback), in `onGpsUpdate` (every point + `useRadioGps` + recording state), and in `finalizeTrack` (point count, closing-tags written?). Tag `RecDiag`. Cold-record in Android mode. Read:
   - No `onLocationChanged` at all → provider never fires → H1/H3/H4 (split via the permission/type logs).
   - Callbacks fire, `onGpsUpdate` doesn't → wiring/filter between listener and sink.
   - Points arrive but file truncated → `finalizeTrack`/`closeGpx` not writing `</trkseg></trk></gpx>` on stop.
4. **Fix per the log.** If H1: mirror `startPhoneGps` (warm the provider / immediate first-fix request / don't depend on `getLastKnownLocation`), consider fused provider + minDistance 0. Surface "acquiring GPS…" and never write an empty track. Ensure record-stop finalizes the file.

## GUARD-RAILS
- The radio lead-cart LINE (`tick`→`LeadTrackSegment`) is display-only and works — don't disturb it.
- `onGpsUpdate` also drives the mileage accumulator (AllDocs 1176) — preserve when changing the source path.
- The truncated "mike ride June 5" is consistent with an Android record that got no points and/or wasn't finalized — same root; the fix should close both the empty-track and truncation bugs.

---

## ⭐⭐ TRACE ADDED — where Android GPS is assigned to the lead cart / self node (answers "in what functions")
`getPhoneLocation()` (ConvoyViewModel ~817) is THE bridge from Android GPS into the convoy. It tries THREE sources in priority order:
1. **`gpsService?.lastLat/lastLon`** (~819-820) — the RECORDER SERVICE's Android GPS, set ONLY inside `ConvoyGpsService.onGpsUpdate` (~304), which only fires while the service's `startLocationUpdates` subscription is delivering.
2. **`livePhoneLocation`** (~828) — the value from `startPhoneGps()`'s `phoneLocationListener` (~109/111), the 2-sec listener ADDED IN 2.6 for the map pin.
3. **`LocationManager.getLastKnownLocation`** (~834) — last resort.

`getPhoneLocation()` is called in `readLiveNodes()` at:
- **~849** — builds the SELF/phone node's position (lat/lon/alt×3.28084ft/speed×2.23694mph/bearing).
- **~899** — SUBSTITUTES phone GPS for any RADIO node that has no position (the AllDocs 4848/4890 "if node lat=0.0/lon=0.0, substitute phone GPS" logic).
Then `readLiveNodes` → `ConvoyEngine.assignLeadTail()` (~99) picks the lead → `LeadTrackSegment` draws the on-screen line.

## ⭐⭐ ROOT-CAUSE MECHANISM (this explains "screen fine, GPX empty")
The DISPLAY and the RECORDING use DIFFERENT Android GPS subscriptions:
- **DISPLAY (lead/self node)** can satisfy `getPhoneLocation` from source 2 (`startPhoneGps`/`livePhoneLocation`) or 3 (`getLastKnownLocation`) — so the pin/self-node shows even if the recorder isn't feeding points.
- **GPX RECORDING** depends ONLY on source 1's origin: `ConvoyGpsService.startLocationUpdates` → `onGpsUpdate` → `writeGpxPoint`. If the SERVICE's own subscription doesn't deliver, `gpsService.lastLat/lastLon` stays null, NO `<trkpt>` is written → empty/truncated GPX — WHILE the screen still draws from the ViewModel's separate listener.

**So the bug is specifically in `ConvoyGpsService.startLocationUpdates` (the SERVICE's own subscription), NOT in the phone-GPS-for-display path.** The ViewModel's `startPhoneGps` proves the phone CAN deliver fixes in-process; the service's subscription is the one failing. LIKELY FIXES: (a) have the service subscribe the way `startPhoneGps` does (same provider/warm-up), or (b) feed the recorder from the already-working `livePhoneLocation`/`getPhoneLocation` stream instead of a second independent `LocationManager` subscription in the service — one warm Android GPS source feeding both display and GPX. Decide with Fred: consolidate to ONE phone-GPS subscription vs fix the service's own.

## TOMORROW — pull these to confirm
- `sed -n '817,838p' …/ConvoyViewModel.kt` (getPhoneLocation source priority)
- `sed -n '839,910p' …/ConvoyViewModel.kt` (readLiveNodes — the 849 self-node + 899 radio-substitute injections)
- `sed -n '109,145p' …/ConvoyViewModel.kt` (startPhoneGps / livePhoneLocation — the WORKING listener)
- `sed -n '288,320p' …/ConvoyGpsService.kt` (onGpsUpdate — sets lastLat/lastLon + writes GPX)
- `sed -n '251,287p' …/ConvoyGpsService.kt` (startLocationUpdates — THE failing subscription)
Then instrument onLocationChanged/onGpsUpdate in the SERVICE and confirm no callbacks arrive there while livePhoneLocation IS updating (which would nail the "two subscriptions, service one dead" mechanism).

---

## ⭐⭐⭐ FRED'S FIELD OBSERVATION (06-30 eve) — THE PIN APPEARS BUT DOES NOT MOVE
Fred: the Android device pin shows on map-open but **does not move** → it's not being updated in `tick()` → its lat/lon aren't refreshing → therefore no track points either. **One frozen position source = frozen pin + no tick movement + no GPX.** This is the unifying symptom.

**Confirmed by the docs' buried assumption:**
- AllDocs 4886: "GPS recording — ConvoyGpsService already records phone GPS to GPX **independently**."
- AllDocs 4863: "The phone GPS is **already active when ConvoyGpsService starts (triggered by RECORD)**… `requestLocationUpdates(GPS_PROVIDER,2000,1f)`… so the tick loop gets a fresh fix via `getLastKnownLocation()`."
- The whole Android-position chain was designed ASSUMING the SERVICE's `startLocationUpdates` keeps LocationManager warm, so `getLastKnownLocation()` returns fresh fixes. If that subscription isn't delivering: `gpsService.lastLat/lastLon` never updates (no GPX), and `getLastKnownLocation()` returns only the ONE stale open-time fix (pin appears, never moves), and tick sees no change. **Exactly Fred's symptom.**
- `livePhoneLocation` is `@Volatile` with `phoneLocationListener` (VM 108-109) via `startPhoneGps` (111). If `startPhoneGps` isn't actually running/refreshing (e.g. only started under a condition that isn't met, or never (re)started for this flow), the ONLY live updater is the service subscription — which is the dead one.

**LEADING MECHANISM (test this first):** the Android position is frozen after the initial open-time fix because NOTHING is continuously updating it — either `startPhoneGps`'s listener isn't running/refreshing `livePhoneLocation`, or the code leans on the service's `startLocationUpdates` (not delivering) + a stale `getLastKnownLocation`. Fix = guarantee ONE continuously-live Android GPS updater feeding `getPhoneLocation` (prefer `startPhoneGps`/`livePhoneLocation`, always running while the map/record is active), and have the recorder consume THAT stream instead of depending on its own separate service subscription.

**Fastest confirming probe next session:** instrument `startPhoneGps`'s listener callback (log each `livePhoneLocation` update) AND `ConvoyGpsService.onLocationChanged`. Open the map, watch: if `livePhoneLocation` is NOT ticking (or `startPhoneGps` never logged "started"), that's the frozen source — the pin came from a single `getLastKnownLocation`. If `livePhoneLocation` IS ticking but the pin still doesn't move, the break is downstream in `getPhoneLocation`/`readLiveNodes`/`tick` consuming it.

---

## ⭐⭐⭐⭐ SHARPENED CONCLUSION — tick does NOT write the track; the service's Android LocationListener does
Verified in code:
- `ConvoyGpsService.onGpsUpdate()` (~288) is where BOTH `writeGpxPoint()` (~393) is called AND `lastLat/lastLon` are set (~304-305). This is entirely INSIDE the service.
- TWO things call `onGpsUpdate`: `onRadioPosition` (~245, RADIO — works) and the Android `LocationListener` registered in `startLocationUpdates` (~251, ANDROID — failing).
- `tick()` does NOT write the GPX. `tick`/`readLiveNodes`/`getPhoneLocation` only READ `lastLat/lastLon` for the DISPLAY (self node ~849, radio-substitute ~899).

**Therefore the frozen pin and the missing track are ONE fault with ONE upstream:** the Android `LocationListener` in `ConvoyGpsService.startLocationUpdates` is not firing `onGpsUpdate`. Because it never fires:
- `writeGpxPoint` is never called with Android fixes → empty/truncated GPX.
- `lastLat/lastLon` stay at the single open-time value → `getPhoneLocation` returns stale → tick assigns the same position every cycle → pin frozen, no movement.

**Fred's framing, corrected to the mechanism:** it's not that tick fails to assign the Android a position — it's that the SERVICE's Android LocationListener (the shared upstream feeding BOTH the GPX write and the `lastLat/lastLon` that tick reads) is dead. One fix point.

**THE ONE FUNCTION TO FIX:** `ConvoyGpsService.startLocationUpdates()` (~251-277) — its `LocationListener.onLocationChanged` is not calling `onGpsUpdate` (or the subscription never delivers). Confirm with a single log in `onLocationChanged`. Then fix the subscription (provider/permission/foreground-type/warm-up) OR feed `onGpsUpdate` from the already-live `startPhoneGps` stream. Radio path stays untouched.

---

## ⭐⭐⭐⭐⭐ FRED'S SEQUENCE INSIGHT — the CART-ARRAY assignment (radio vs Android) is the process to check
Fred: this is a SEQUENCE issue. GPS record is always active for Android, but if NO device name/slot is assigned to that Android in tick, its position is never updated. The cart array (cart1/cart2/cart3) is built for all devices; tick updates each device's location in the array. The RADIO-vs-ANDROID assignment of each cart slot is what to check.

**What the xref shows (confirms the concern):**
- `resolveMyCartId()` (~290-291): `val num = nodeRepository.myNodeInfo.value?.myNodeNum` → "my cart" is resolved from the **RADIO node number**. If the position source is Android (no radio / radio isn't the identity), the myCart id may not correspond to any node carrying the phone GPS.
- `readLiveNodes()` builds the node/cart array from TWO origins:
  - RADIO nodes from `nodeMap = nodeRepository.nodeDBbyNum` (~868), position from `pos.latitude_i/longitude_i` (~873-875); `hasPos` gate (~873).
  - PHONE/Android via `getPhoneLocation()` at ~849 (self-node build) and ~899 (substitute phone GPS ONLY when a radio node's `hasPos` is false).
- `isMyCart` (ConvoyNode ~16) must be stamped on the array node that represents THIS device for tick/HUD/lead to track it (`state.nodes.firstOrNull { it.isMyCart }`, ~601).

**THE SEQUENCE BUG (hypothesis to verify):** when the device is Android-sourced, is a cart-array node ever (a) created for the Android device, (b) stamped `isMyCart`, and (c) UPDATED each tick with the MOVING `getPhoneLocation()` value? If the array is built from radio `nodeMap` and the Android device only enters as a one-time self-node or a fallback-substitute that isn't refreshed as `isMyCart`, then tick updates only RADIO-assigned slots → the Android slot is frozen (initial fix only) → no movement, no track. This is consistent with "pin appears, never moves, no track."

**Contrast with why RADIO works:** a radio cart is a real `nodeMap` node whose `pos.latitude_i` updates every mesh position packet → tick re-reads it every cycle → slot moves → (and its GPX writes via `onRadioPosition`→`onGpsUpdate`). The Android device lacks an equivalent per-tick position refresh into its cart slot.

**PULL NEXT SESSION (the cart-assignment sequence):**
- `sed -n '289,296p' …/ConvoyViewModel.kt` — `resolveMyCartId` (radio-keyed — does it handle the Android/no-radio case?).
- `sed -n '839,910p' …/ConvoyViewModel.kt` — `readLiveNodes`: self-node build (~849), radio-node build (~868), phone substitute (~899), and WHERE `isMyCart` is stamped (is the phone/Android node ever marked isMyCart?).
- `sed -n '595,700p' …/ConvoyViewModel.kt` — `tick`: `myCart` handling (~601), `leadIsReporting` (~682); does tick refresh the Android cart slot's position from `getPhoneLocation` every cycle, or only radio slots?
- Cross-check `ConvoySimulation.MY_CART_ID` (the default `_myCartId`, ~289) — is the real device ever swapped in, or does a sim/default id leave the Android device unassigned?

**KEY QUESTION to answer first:** in Android-only mode, WHICH cart-array node is the Android device, is it `isMyCart`, and does tick update its lat/lon from `getPhoneLocation()` every cycle? If the answer is "no node / not isMyCart / not refreshed," that's the sequence bug — fix by ensuring the Android device is assigned a cart slot (isMyCart) whose position tick refreshes from the live phone GPS each cycle. The GPX record depends on the same live phone stream reaching `onGpsUpdate`.

---

## ⭐⭐⭐⭐⭐⭐ DECISIVE FINDING — "assign My Cart to Android" is a ROLLED-BACK feature (GPS-01/GPS-02), not a broken subscription
Where My Cart is assigned radio-vs-Android:
- **RADIO:** `resolveMyCartId()` (~290) → `nodeRepository.myNodeInfo.value?.myNodeNum` → the real radio node → gets `isMyCart`, position from `pos.latitude_i` refreshed every mesh packet in tick. **This path exists and works.**
- **Default:** `_myCartId` starts as `ConvoySimulation.MY_CART_ID = "HOTEL-10"` (~289 / ConvoySimulation 11) — a SIM constant until a real node is resolved.
- **ANDROID / standalone:** the path that assigns My Cart to the Android device with a live phone-GPS position was **BUILT THEN ROLLED BACK** and never re-completed.

**Documented history (AllDocs):**
- Release scope items: **GPS-01** "standalone GPS track recording without radio"; **GPS-02** "standalone mode: assign My Cart as lead, track without radio" (~3264-3265).
- Standalone GPS Mode was an EXPLICIT rollback candidate: "depends on lead cart working correctly; if lead cart is rolled back, these must go too" (~3948).
- The rollback removed exactly: **`getPhoneLocation()`, the `readLiveNodes` phone-node block, the self-heal lead block, SOLO_DEBUG** — "keep `setLeadCart()` and lead lock" (~4033).
- Redesign directive left open: **"Study tick(), compute(), assignLeadTail() line by line. Redesign lead cart to work WITH tick, not against it"** (~4044). The standalone path fought tick — that's WHY it was pulled.

**So the root cause is not a dead LocationListener — it's a MISSING/INCOMPLETE ASSIGNMENT PATH:** in Android/standalone mode there is no (complete) code that (a) creates a cart-array node for the Android device, (b) stamps it `isMyCart`, and (c) refreshes its position from live phone GPS every tick — because that block was rolled back. Radio works because a radio node naturally does all three. This is exactly Fred's sequence insight: no device assigned to the Android in tick → no movement, no track.

**⚠️ NUANCE — partial re-add since the rollback:** the CURRENT xref still shows `getPhoneLocation()` (~817) called at ~849 and ~899, so SOME of the phone path was re-added. So it's not fully absent now — it may be a HALF-RESTORED fragment that doesn't complete the assignment (e.g. computes a phone position but never stamps `isMyCart` on a per-tick-refreshed node, or only substitutes into radio nodes at ~899, never creating a standalone Android cart). LIVE-CODE VERIFICATION REQUIRED — determine exactly how much of GPS-01/02 currently exists and where it stops short.

**PULL NEXT SESSION to map current vs rolled-back state:**
- `sed -n '839,910p' …/ConvoyViewModel.kt` — is there a phone-node CREATION block (Android device → ConvoyNode with `isMyCart=true`)? Or only the ~899 substitute-into-radio-node? Does anything set `isMyCart` on a phone-sourced node?
- `sed -n '595,700p' …/ConvoyViewModel.kt` — does tick REFRESH a phone/Android cart slot's lat/lon each cycle, or only radio slots? Is there a "self-heal lead" remnant?
- `git log --oneline` around `6126417ff` (manual lead cart assignment commit) and any later standalone re-add — to see what was reverted vs restored.
- Confirm `resolveMyCartId` behavior when `myNodeInfo` is null (no radio): does it fall back to a phone/device id, or leave `_myCartId` = "HOTEL-10" (sim) with no matching real node?

**FIX DIRECTION (Fred to decide):** complete GPS-01/02 the RIGHT way — "work WITH tick": in Android/standalone mode, ensure a cart node exists for the device, is stamped `isMyCart`, and has its position refreshed from the live phone GPS EVERY tick (same cadence a radio node gets from mesh packets). The GPX recording (service `onGpsUpdate`→`writeGpxPoint`) should be fed by that same single live phone-GPS stream. Do NOT re-introduce the version that "fought tick." Keep radio path untouched.
