# GroupTrack — Cart / Recording / Display Process Rules (2026-06-30)
Governing rules for the recording, my-cart, cart-display, and lead-track processes. Written to stop the Android-standalone track-recording/display bug from being mis-diagnosed. Source: field observation (Fred) + code (xref) + git history.

## THE FOUR INDEPENDENT ANDROID-GPS CONSUMERS (do not conflate)
1. **Map-open centering** — `initDeviceLocation()` in the map HTML + `getLastKnownLocation` (ConvoyMapViewerScreen ~631 / ConvoyScreen ~699). Committed `06406e89a`. INDEPENDENT of lead cart. This is why the pin APPEARS on open (a one-shot last-known fix — it does NOT update).
2. **Display listener** — `ConvoyViewModel.startPhoneGps()` (~111) → `livePhoneLocation` (2-sec listener; added 2.6 for the moving pin).
3. **Cart-assignment / tick read** — `getPhoneLocation()` (~817) prefers `gpsService.lastLat/lastLon`, then `livePhoneLocation`, then `getLastKnownLocation`; consumed in `readLiveNodes` (~849 self-node, ~899 radio-substitute).
4. **Recorder** — `ConvoyGpsService.startLocationUpdates()` (~251) → `onGpsUpdate` (~288) → `writeGpxPoint` (~393). WRITES THE GPX INDEPENDENTLY of display (AllDocs 4886). `tick` does NOT write the GPX.

## RULE 1 — RECORDING AND DISPLAY ARE SEPARATE. A GPX CAN RECORD WITH NOTHING ON SCREEN.
The GPX is written by the recorder service (consumer 4), independent of the map draw. So "no track on screen" does NOT mean "no track recorded." ALWAYS check the file on disk before assuming recording failed. (Field test: record + walk 5 min + inspect `/sdcard/Documents/my_tracks/` newest file for real `<trkpt>` count + `</trk>`.)

## RULE 2 — ONLY THE LEAD CART'S TRACK DRAWS ON SCREEN (default).
`_trackLeadOnly = true` (ViewModel ~91), `_showLeadTrack = true` (~296). ConvoyScreen ~361-363: `if (trackLeadOnly) rawSegments` → only `leadTrackSegments` draw. `leadTrackSegments` is built in tick ONLY for the node `assignLeadTail` picks as lead. **Consequence: if the recording device is not identified as the LEAD cart, its track never draws — regardless of whether the GPX is recording.**

## RULE 3 — CART-ID CONSISTENCY IS THE LINCHPIN.
For an Android/standalone track to DRAW, three identities must resolve to the SAME id:
(a) the recording device, (b) `myCartId` (`resolveMyCartId` ~290 → radio `myNodeInfo.myNodeNum`, else the `HOTEL-10` default), and (c) the lead-cart id from `assignLeadTail`.
RADIO works because a radio node carries ONE consistent id across all three. ANDROID-only breaks when the id resolves inconsistently (radio-keyed resolver with no radio) or falls to the prototype default → the device that's recording is not the id that's drawn as lead → no track on screen.

## RULE 4 — `HOTEL-10` (or any prototype cart) APPEARING = REAL CART ASSIGNMENT FAILED.
`MY_CART_ID = "HOTEL-10"` (ConvoySimulation ~11) is a LEFTOVER from an old prototype that assigned 10 carts with fixed positions for early display validation. It is a sim/default, not real behavior. **If the old prototype cart NAME appears, it means real cart assignment fell through** (`resolveMyCartId` didn't resolve a real node). It's a SYMPTOM — look upstream at why assignment failed; do NOT treat the constant as the cause or "fix" it.

## RULE 5 — THE PIN-ON-OPEN THAT DOESN'T MOVE IS CONSUMER 1, NOT THE TRACKER.
The device pin at map-open comes from map-centering's one-shot `getLastKnownLocation` (consumer 1). Its NOT moving does not by itself indicate the recorder is broken — it indicates nothing is CONTINUOUSLY updating that display position (consumers 2/3). Recording (consumer 4) is separate again. Diagnose each consumer independently.

## RULE 6 — STANDALONE/ANDROID CART ASSIGNMENT ("assign My Cart as lead, track without radio") IS SCHEDULED ENHANCEMENT WORK (GPS-01/GPS-02), PARTIALLY ROLLED BACK.
It WORKED at one point (Fred), is BROKEN now, and is scheduled as an enhancement. History: manual lead-cart assignment committed `6126417ff`; standalone-GPS mode (`getPhoneLocation`, phone-node block, self-heal lead) was ROLLED BACK when it "fought tick" (AllDocs 4033), with the directive "redesign lead cart to work WITH tick, not against it" (4044). Some of it (`getPhoneLocation`) is re-added; completeness unverified. FIX DIRECTION: in Android/standalone mode ensure the device gets a cart node stamped `isMyCart`, assigned as lead, whose position tick REFRESHES from live phone GPS every cycle (same cadence a radio node gets from mesh packets) — and consistent-id per Rule 3. Do NOT reintroduce the version that fought tick.

## GIT FINDING (06-30) — the cart code barely changed since pre-6/3; the MAP DISPLAY changed heavily.
Baseline `c0ad6b509` (last commit before 6/3) → HEAD:
- `ConvoyViewModel.kt`: +29/−2 — ONLY `finalizeTrack` (CREATE→DB insert) + a `heading_deg` scaling fix. **`resolveMyCartId`/`readLiveNodes`/`tick`/`getPhoneLocation`/`isMyCart` UNCHANGED.**
- `ConvoyGpsService.kt`: 4 lines — filename format only (GPX-only, no timestamp). Harmless.
- `ConvoyScreen.kt`: +937 — but cart/lead rendering essentially untouched (changes are search/artifacts).
- `convoy_map.html`: ~597 lines churned — a TRAIL/WAYPOINT/ROUTE/track DISPLAY-function rewrite (many `loadTrails`/`loadSpatialTracks`/`showTracks`/`hideTracks`/`toggleTracks` etc. removed/rescoped to `window.*`, route-mode added).
CONCLUSION: cart-assignment + recording logic are ~unchanged since the working era; the heavy change is in the HTML DISPLAY layer. This points at Rule 1/2 — recording likely still works; the DRAW regressed (or the lead-id/consistency never resolves for Android). CONFIRM with the walk-test (file on disk) + then diff the HTML track-display functions specifically.

## NEXT STEPS
1. WALK-TEST (empirical Case A vs B): record + walk 5 min + `ls -lt /sdcard/Documents/my_tracks/` + pull newest + count `<trkpt>`/check `</trk>`. File-with-points + no-draw = DISPLAY bug (Rules 1/2/3).
2. If display bug: verify lead-cart identity for the Android device in tick — is it `isMyCart`, is it the lead, does its id match the recorder's? (Rule 3.) Check for `HOTEL-10` (Rule 4).
3. Diff the HTML track-display functions pre-6/3 vs now (which draw functions were renamed/rescoped; does the Kotlin side still call the old names?).
4. Fix per GPS-01/02 "work WITH tick" (Rule 6), keep radio path untouched.

---

## ⭐ 06-30 RESOLVED: RECORDING IS FINE — THE BUG IS CART/LEAD ASSIGNMENT + DRAW
Field-proof: "droid track test" (2b9cf332…gpx) recorded INDOORS at midnight = well-formed GPX, REAL coords (42.801, -71.278 = Windham NH), proper `</trkseg></trk></gpx>` close, 2 points 38s apart. Two points is EXPECTED indoors (GPS_PROVIDER weak, no sky view) — NOT a recorder bug. **Recording works. Drop all recorder/subscription/foreground-type theories.**
**THE BUG:** using/substituting the Android device name for the cart + lead cart, so the Android device is never the LEAD whose track draws (Rule 2/3). Isolate the ASSIGNMENT → LEAD → TICK → DRAW chain and instrument it.

## 🎯 NEXT SESSION — INSTRUMENT THE CART/LEAD/DRAW CHAIN (add debug, watch values)
Add temporary debug logging (tag `CartDiag`) at each stage; run in Android mode; read the flow to see where the Android device drops out. THE CHAIN + WHAT TO LOG:

1. **`resolveMyCartId()`** (ConvoyViewModel ~290) — LOG: returned id, is `myNodeInfo` null?, did it fall to `HOTEL-10`? (Rule 4: HOTEL-10 = assignment already failed.)
2. **`readLiveNodes()`** (~839) — LOG per call: node count; for EACH node its id + `isMyCart` + lat/lon + status; specifically whether a PHONE/Android node is built (~849 self-node) or only radio-substitute (~899); WHICH node (if any) has `isMyCart=true` and does its id == `myCartId`.
3. **`ConvoyEngine.assignLeadTail()`** (~99): `active = nodes.filter { status==ACTIVE }` (~104); `leadNode = lockedLeadNodeId ?: computed` (~107). LOG: active count, `lockedLeadNodeId`, the chosen lead id — and IS the lead id == the Android/myCart id? (If the Android node isn't ACTIVE, or lead is locked to a stale radio id, Android never leads.)
4. **`tick()`** (~570) → `leadTrackSegments` build (~767): LOG lead id, did the lead's position ADVANCE vs last tick, segment count appended.
5. **DRAW gate** — `trackLeadOnly` default true (~91) → ConvoyScreen ~361-363 draws only lead segments. LOG (or reason): if `leadTrackSegments` is empty or the lead isn't the recording device, nothing draws.

**WHAT THE LOG WILL PINPOINT (one of):**
- Android node never built in readLiveNodes → fix the phone-node creation (GPS-01/02 completion, Rule 6).
- Built but `isMyCart=false` / id ≠ myCartId → fix `resolveMyCartId` for the no-radio case (Rule 3/4).
- isMyCart but `status != ACTIVE` → fix status so the phone node is eligible for lead.
- ACTIVE but not chosen lead (locked to stale radio id) → fix lead lock/selection for standalone.
- Chosen lead but position not advancing in tick → fix the per-tick phone-GPS refresh into the node (Rule 6, "work WITH tick").

**ISOLATE THESE FILES/FUNCTIONS ONLY:** ConvoyViewModel `resolveMyCartId`/`readLiveNodes`/`tick` + ConvoyEngine `assignLeadTail`/`compute`. Radio path stays untouched. Add `CartDiag` logs, ONE build, run Android-mode, read the chain, then fix the single stage that drops the Android device.

---

## 🎯🎯 XREF-PINPOINTED — THE NAME/ID MISMATCH LINES (tick issue: pin frozen + no draw)
Fred's call: pin not moving + track not drawing = tick not updating the Android position = a NAME/ID MISMATCH between the lead-cart id and the id tracked in tick. The xref localizes it to these exact lines:

- **ConvoyViewModel ~681:** `val leadNode = state.nodes.firstOrNull { it.nodeId == lockedLeadNodeId }` — tick finds the lead by `nodeId == lockedLeadNodeId`. If no node's id equals `lockedLeadNodeId`, `leadNode == null`.
- **~682:** `val leadIsReporting = leadNode != null && leadNode.latitude != 0.0` — null lead ⇒ not reporting ⇒ no position update, no segment ⇒ frozen pin + no track.
- **~609:** `val trackFrom = currentLeadNodeId ?: "NONE"` — the TRACK DRAWS from **`currentLeadNodeId`**, a SEPARATE variable from `lockedLeadNodeId` (681). **TWO lead-id variables — if they diverge, the lead found ≠ the id the track draws from.** PRIME SUSPECT.
- **~601:** `val myCart = state.nodes.firstOrNull { it.isMyCart }` — myCart found by the `isMyCart` FLAG (a third identity path — flag vs id).
- **~611:** `nodes.firstOrNull { it.nodeId == id }?.callsign ?: id.takeLast(4)` — falls back to `id.takeLast(4)` when no node matches (a mismatch produces a name from the raw id, not a real cart).
- **~194 `setLeadCart(nodeId)` / ~199** — where `lockedLeadNodeId` originates; ~199 also does `nodeId == nodeId` callsign lookup.
- **~289-291** — `_myCartId` default `HOTEL-10`; `resolveMyCartId` → `myNodeInfo.myNodeNum` (radio `!%08x` format).
- **~849-868 (MISSING PIECE — pull live):** the phone/self node is positioned from `getPhoneLocation()` (~849) but WHAT nodeId is assigned to that Android node (between ~854-868) is NOT in the xref. **This is the id to see.** Radio nodes get `!%08x` (~868 `nodeMap.values`). If the phone node's id ≠ the `!%08x`/`HOTEL-10`/resolver id, then `== lockedLeadNodeId` (681) NEVER matches → frozen.

### THE MISMATCH HYPOTHESIS (two candidates, both testable in one run):
1. **`lockedLeadNodeId` ≠ `currentLeadNodeId`** (681 vs 609) — lead lookup uses one, track-draw uses the other; they're out of sync. Find where each is set and whether tick keeps them equal.
2. **Phone-node nodeId ≠ lead/myCart id** — the Android node built in readLiveNodes gets an id string that never equals `lockedLeadNodeId`/`myCartId` (format mismatch: phone id vs `!%08x` radio vs `HOTEL-10` default).

### DEBUG TO ADD (tag `CartDiag`, log every tick):
`lockedLeadNodeId`, `currentLeadNodeId`, `myCartId`, the phone node's `nodeId` (from readLiveNodes), whether ANY node matched at 681 (`leadNode==null?`), and the `isMyCart` node's id. One build, Android mode, read: the two ids that SHOULD be equal but aren't = the mismatch. Fix = make the phone node's id and the lead/myCart id resolve to the SAME string (Rule 3), and keep `lockedLeadNodeId`/`currentLeadNodeId` in sync.

### PULL NEXT SESSION (exact):
- `sed -n '843,868p' …/ConvoyViewModel.kt` — the phone node's nodeId assignment (the missing piece).
- `sed -n '605,700p' …/ConvoyViewModel.kt` — `currentLeadNodeId` (609) vs `lockedLeadNodeId` (681), where each is set, the lead-track build.
- `sed -n '194,205p' …/ConvoyViewModel.kt` — `setLeadCart` (sets lockedLeadNodeId).
- `sed -n '289,296p' …/ConvoyViewModel.kt` — resolveMyCartId + HOTEL-10 default.
- `grep -n 'currentLeadNodeId' …/ConvoyViewModel.kt` — every place it's assigned/read (is it ever set for the Android/standalone case?).

---

## 🎯🎯🎯 ROOT CAUSE FOUND (documented + xref-confirmed) — nodeId FORMAT COLLISION: "!phone" vs "!%08x" vs HOTEL-10
The phone/standalone device node is built with **`nodeId = "!phone"`** (fixed literal) + `callsign = Build.MODEL` (AllDocs 4892-4893). Radio nodes get **`nodeId = "!%08x".format(node.num)`** (ConvoyViewModel ~872). These are DIFFERENT identity formats. The lead-cart id can be set to ANY of several formats depending on path:

**Every `setLeadCart` caller and the id format it writes (from where_used/field_crossref):**
- ConvoyViewModel ~164: `setLeadCart(nodes[0].nodeId)` — single node's real id (radio `!%08x` OR `!phone`).
- ConvoyViewModel ~165/166: `setLeadCart(_myCartId.value)` — **`HOTEL-10`** default or radio-derived `myCartId`.
- ConvoyScreen ~1046: `setLeadCart(node.nodeId)` — RADIO `!%08x`.
- ConvoyScreen ~1105: `setLeadCart(soloNode?.nodeId ?: "!phone")` — the SOLO/phone path → **`!phone`** (Fred's "last change moves the phone name to lead cart id").
- ConvoyScreen ~2104: `setLeadCart(n.nodeId)` — RADIO `!%08x`.

**THE FREEZE MECHANISM (tick ~681):** `leadNode = state.nodes.firstOrNull { it.nodeId == lockedLeadNodeId }`. The phone node's id is `"!phone"`. If `lockedLeadNodeId` was set to a radio `!%08x` id or `HOTEL-10` (any non-`!phone` path fired), the `== lockedLeadNodeId` test NEVER matches the phone node → `leadNode == null` → `leadIsReporting=false` (~682) → position not updated, no segment → **pin frozen + no track drawn.** EXACTLY the symptom.

**DOCUMENTED PROOF this is a known, recurring bug:**
- AllDocs 4687: "Standalone GPS Mode BROKEN — Device node created as `!phone` but GPS position not updating. Likely… **node ID mismatch between lead assignment and tick loop.**"
- AllDocs 1111: "The lead is locked to a radio node name (**`!phone` is a different identity**). When autopause disconnects the radio, lead goes null, trail accumulation skips, **track freezes.**"

**ANSWER to Fred's question "does anything move the radio value to lead cart?":** YES — ConvoyScreen 1046 & 2104 (`node.nodeId` radio `!%08x`), and ConvoyViewModel 164 (single radio node) / 165-166 (`myCartId` → HOTEL-10 or radio). So the lead-cart id and the phone node's `!phone` id disagree whenever any radio/default path set the lead.

## 🛠️ THE FIX DIRECTION THE DOCS ALREADY PRESCRIBE (the rewrite)
AllDocs 4838-4842: **"The problem is not that we need a special standalone mode. The device does not appear as a node unless a radio is connected. Fix that ONE thing and the entire existing flow works. The Android device should ALWAYS appear as a node in convoyState, whether or not a radio is connected — it has a name (Build.MODEL), GPS, battery. It IS a cart."**
Design (AllDocs 4889-4899): in `readLiveNodes()`, if nodeRepository is empty, create ONE ConvoyNode: `nodeId="!phone"`, `callsign=Build.MODEL`, lat/lon/alt/speed from phone GPS. Return it as the sole node.
**FIX = make the identity consistent end-to-end:** the phone node's `nodeId` (`!phone`), `myCartId`, and `lockedLeadNodeId`/`currentLeadNodeId` must ALL resolve to `!phone` when the device is standalone. Then tick's `== lockedLeadNodeId` matches, lead reports, position updates each tick from phone GPS, track draws. Keep radio path (`!%08x`) untouched; the two identities must never be cross-assigned.

## 📋 THE REWRITE TASK (scheduled) — cart/lead/tick cycle, single-path
Per AllDocs "single-path design: device is always a node." Rewrite so:
1. `readLiveNodes` ALWAYS includes the device as a `!phone` node (radio-present OR not), positioned from live phone GPS, refreshed every tick.
2. Lead assignment uses `!phone` for the device consistently — never let a radio `!%08x` or `HOTEL-10` land in the lead id when the device is the intended lead.
3. `lockedLeadNodeId` / `currentLeadNodeId` kept in sync (they diverge today — 681 vs 609).
4. tick updates the lead node's position every cycle (work WITH tick — the rolled-back version fought it).
5. Draw follows automatically (trackLeadOnly → lead segments).
DEBUG FIRST (tag `CartDiag`): log `lockedLeadNodeId`, `currentLeadNodeId`, `myCartId`, phone node `nodeId`, and `leadNode==null?` each tick — SHOW the `!phone` vs `!%08x`/`HOTEL-10` mismatch live before rewriting.

---

## 🎯🎯🎯🎯 FRED'S CATCH — there is NO auto "no radio → lead = !phone", AND the !phone NODE may never be built
Auto-assign (ConvoyViewModel ~164-166):
- `nodes.size == 1 -> setLeadCart(nodes[0].nodeId)` — only works IF a `!phone` node is IN `nodes`.
- `nodes.isEmpty() -> setLeadCart(_myCartId.value)` — no radio → lead = **`_myCartId.value` = `HOTEL-10`**, NOT `!phone`. ← THE HOLE.
- `else -> setLeadCart(_myCartId.value)`.
The ONLY `setLeadCart("!phone")` is ConvoyScreen ~1105 (`soloNode?.nodeId ?: "!phone"`) — a UI/tap fallback, NOT the auto no-radio path.

**AND the !phone NODE is apparently NOT BUILT:** `readLiveNodes` (~839): `nodeMap = nodeRepository.nodeDBbyNum` (radio only, ~840); `getPhoneLocation()` reads phone lat/lon/alt/spd/hdg (~849-854); then `allNodes = nodeMap.values.mapNotNull {...}` (~868) — built PURELY from radio `nodeMap`. **grep for `ConvoyNode(` construction finds ONLY the data-class definition — NO `ConvoyNode(nodeId="!phone", …)` build call.** So the phone GPS is READ but never assembled into a standalone `!phone` node. With no radio: `nodeMap` empty → `allNodes` empty → `nodes.isEmpty()` → lead = `HOTEL-10` → tick `nodeId == lockedLeadNodeId` (~681) matches nothing → frozen. (Matches the rollback: AllDocs 4033 "remove readLiveNodes phone node block" — only `getPhoneLocation` fragments remain; the CREATION block is gone.)

**So the chain fails at the SOURCE: the device is never a node.** This is precisely AllDocs 4838: "the device does not appear as a node unless a radio is connected — fix that ONE thing and the entire existing flow works."

**VERIFY LIVE (the xref skips 855-867):** `sed -n '839,868p' …/ConvoyViewModel.kt` — confirm whether ANY `ConvoyNode(nodeId="!phone", …, isMyCart=true)` is constructed and added when `nodeMap` is empty, or if 849-854's phone values go only to the ~899 radio-substitute / nowhere.

**THE FIX (minimal, per AllDocs 4889):** in `readLiveNodes`, build and include a device node when appropriate — `ConvoyNode(nodeId="!phone", callsign=Build.MODEL, isMyCart=true, lat/lon/alt/spd/hdg from getPhoneLocation)`. Then: `size==1 → setLeadCart("!phone")` naturally; OR change the `nodes.isEmpty()` branch to build/assign `!phone` instead of `_myCartId.value`. Either way the device becomes a real node whose id (`!phone`) is what lead + tick both use → position updates each tick → track draws. Keep radio path (`!%08x`) separate.

---

## 🎨 06-30 eve — CURRENT CODE IS ALMOST RIGHT; the bug is INTERMITTENT (race), and CART COLOR is the tell
CURRENT HEAD code (verified via `git show HEAD:...ConvoyViewModel.kt`):
- ~156-158: `_myCartId.value = if (myNum != null) "!%08x".format(myNum) else "!phone"` — identity resolves to `!phone` with no radio. CORRECT (the earlier "HOTEL-10 default" concern does NOT apply to current code).
- ~159-166: after `readLiveNodes`, `if (lockedLeadNodeId == null) { size==1 -> setLeadCart(nodes[0].nodeId); isEmpty -> setLeadCart(_myCartId.value); else -> setLeadCart(_myCartId.value) }`. So the `!phone` node (the 1 node) → lead = `!phone`. CORRECT — IF this runs at the right time.
- ~848-858: the `!phone` node IS constructed (`nodeId="!phone"`, callsign=Build.MODEL, lat/lon from getPhoneLocation, `lastSeenMs=nowMs`, `status=ACTIVE`) and early-`return listOf(...)`. So identity + node + lead are all consistent ON PAPER. The `!phone` build survived the rollback and is present.

**So the bug is NOT a missing assignment — it's INTERMITTENT ("no rhyme or reason", Fred).** Field facts:
- Android pin at map-load (no radio) = map-open centering (getLastKnownLocation, consumer 1) — separate from the cart. My-cart pin is NOT set until REC is pressed.
- **BROWN my-cart pin → track does NOT draw. GREEN my-cart pin → track DRAWS.** Color = cart status/position.

**CART COLOR IS THE DIAGNOSTIC (decoded):**
- AllDocs 5273: **`#8B4513` BROWN = "Mixed/unknown"** status.
- AllDocs 14080-14081 + ConvoyEngine.computeStatus (~62-64): status from `age = nowMs - node.lastSeenMs`; `age >= LOST_MINUTES → LOST/unknown`. GREEN = fresh/ACTIVE.
- The `!phone` node is built with `lastSeenMs = nowMs` → SHOULD be green every tick — **IF it is rebuilt/refreshed each tick.** If the node stops being refreshed (lead locked to a stale id, or node not rebuilt), `lastSeenMs` ages → BROWN → and the SAME staleness makes `leadIsReporting` (~682) false → NO track draw. **Brown pin + no track = the `!phone` node went stale (not refreshed each tick). Green + draw = it is refreshed.** Color and draw share ONE root: is the `!phone` lead node refreshed with `lastSeenMs=nowMs` every tick?

**THE INTERMITTENCY = A RACE AT REC TIME** (commit `eed497680` "identity locked at RECORD time"):
- The lead-assign is gated `if (lockedLeadNodeId == null)`. If `lockedLeadNodeId` is ALREADY non-null at REC (stale from a prior session / locked before the `!phone` node had a valid GPS fix), the block SKIPS → lead stays a stale id → tick `nodeId == lockedLeadNodeId` (~681) never matches the fresh `!phone` node → node not refreshed as lead → ages → BROWN + no draw.
- When REC fires WITH the `!phone` node already built and `lockedLeadNodeId==null`, lead locks to `!phone` cleanly → GREEN + draws.
- That timing dependency = "intermittent, no rhyme or reason."

## GIT — SURGICAL TRAIL (pinpointed)
`git log -S'"!phone"' -- ConvoyViewModel.kt`:
- `6583f30c3` feat: V2.4 map viewer + **standalone GPS mode** (ADDED !phone).
- `1ce2482c3` V2.4 My Cart name.
- `56abf947a` fix: **remove phone GPS from tick — prevents ANR** (REMOVED it).
- `efa1b677e` fix: **restore phone GPS with permission guards — phone-only mode works, no ANR** (RE-RESTORED — this is upstream of HEAD, IN the branch).
- ~11 commits after efa1b677e touched ConvoyViewModel; prime suspect **`eed497680` "FT-02/03 identity locked at RECORD time"** (the lead-lock timing) and `8121b5a6f` "lead track proxy fix". Baseline `c0ad6b509`→HEAD only changed finalizeTrack + heading (cart code stable since), so the identity-lock behavior came in at/before `eed497680`.

## NEXT SESSION — SURGICAL, not a rewrite (the code is present; fix the RACE)
1. PULL: `git show HEAD:...ConvoyViewModel.kt | sed -n '835,849p'` (what guards the `!phone` early-return — is it `if nodeMap.isEmpty()`? does the node rebuild every tick?) and `sed -n '194,205p'` (`setLeadCart` — does it also set `currentLeadNodeId`?) and `grep -n 'lockedLeadNodeId' ...` (every set/reset — WHERE does it get reset to null? is it reset on a fresh REC?).
2. DIFF the suspect: `git show eed497680 -- ConvoyViewModel.kt` (what "identity locked at RECORD time" changed — did it stop resetting `lockedLeadNodeId`?). Also `git show 8121b5a6f`.
3. INSTRUMENT (tag `CartDiag`): each tick log `lockedLeadNodeId`, `currentLeadNodeId`, `myCartId`, the `!phone` node's `lastSeenMs`/status, and `leadNode==null?`. Reproduce brown vs green and READ which value is stale/mismatched.
4. LIKELY FIX (surgical): on REC (or map open with no radio), RESET `lockedLeadNodeId=null` (or explicitly `setLeadCart("!phone")`) so the fresh `!phone` node becomes lead every session — and/or ensure the `!phone` node is rebuilt with `lastSeenMs=nowMs` every tick so it stays green/ACTIVE. Keep radio path (`!%08x`) untouched.

---

## 🎨✅ CORRECTION — CART COLOR IS QUEUE-POSITION (convoyPosition), NOT staleness (Fred was right)
`ConvoyNode.markerColor` (ConvoyNode ~33) is driven by a **`palette = listOf(...)`** indexed by the node's **`convoyPosition`** (its slot in the convoy). NOT a staleness/lastSeen color. Brown `#8B4513` = "Mixed/unknown" (AllDocs 5273) = the FALLBACK when convoyPosition is not cleanly assigned.

**How convoyPosition is assigned (AllDocs 14085-14094 — the tick compute tree):**
- `computeHeading(nodes)` = median heading of ACTIVE nodes moving **> 3mph** → convoy direction.
- `computeSortPositions(nodes, heading)` = projects each node's lat/lon onto the heading vector, sorts front→back, **assigns `convoyPosition: 1=front … N=back`; LOST nodes appended at end.**
- `assignLeadTail(sorted, lockedLeadNodeId, …)` then picks lead. Legacy lead rule (AllDocs 524/535): `leadNode = active.minByOrNull { it.convoyPosition }` → **lead = lowest convoyPosition = front.**

**SO THE REAL MECHANISM (matches Fred's queue-position read + the intermittency):**
- Color = convoyPosition (palette). GREEN = a valid front/queue slot; BROWN = "Mixed/unknown" = convoyPosition NOT cleanly assigned.
- For a SINGLE standalone Android cart: `computeHeading` needs movement > 3mph; with one node standing still / weak fix, there's no stable heading → `computeSortPositions` can't cleanly project/assign → node falls to unknown position → **BROWN → not cleanly front/lead → NO track.**
- When the device IS moving > 3mph with a clean fix → heading computes → node projects to position 1 → real palette color (GREEN) → becomes lead → **track draws.**
- **THIS is the "intermittent, no rhyme or reason":** it depends on whether, at/after REC, the lone device has enough movement + valid GPS for computeHeading/computeSortPositions to assign `convoyPosition = 1`. (Brown/no-track when stationary or weak-fix; green/track when moving with good fix.)

**REVISED ROOT CAUSE:** identity (`!phone`) is correct; the failure is that a SOLO cart doesn't get a stable `convoyPosition` because computeHeading/computeSortPositions assume a MULTI-node convoy with a computable heading. A lone cart should be `convoyPosition=1`/lead UNCONDITIONALLY, but the projection logic doesn't guarantee it → unknown position → brown → not lead → no draw.

**SURGICAL FIX DIRECTION:** in the position/lead compute, special-case the single-node (standalone) scenario: if there is exactly one node (or only the `!phone` node with no radio), force `convoyPosition = 1` and lead = that node, bypassing computeHeading/computeSortPositions (which need multi-node movement). Then color = front (green), lead = the device, track draws — regardless of movement/heading. Keep the multi-node radio path (heading projection) untouched.

**PULL NEXT SESSION to confirm:**
- `ConvoyNode.kt` ~33-46 — the `markerColor` palette + the brown/unknown fallback condition (what convoyPosition value → brown?).
- `ConvoyEngine` `computeSortPositions`/`computeHeading` — how a single node (or no heading) is handled; does a lone node get convoyPosition=1 or fall to unknown?
- `assignLeadTail` (~99-112) — does `active.minByOrNull { convoyPosition }` pick the `!phone` node when it's the only one, and is it flagged `isLead`?
- INSTRUMENT (`CartDiag`): log the `!phone` node's `convoyPosition`, `markerColor`, `isLead`, and computeHeading result each tick; reproduce brown (stationary) vs green (moving) to confirm the heading/position dependency.

---

## ⭐ RULE CORRECTION (current) — LEAD IS ASSIGNED AT RECORD-PRESS, not derived per-tick by convoyPosition
Fred (current rule, supersedes legacy AllDocs 524/535 `minByOrNull{convoyPosition}`):
- **Lead is assigned WHEN RECORD IS PRESSED** (locked at REC — commit `eed497680` "identity locked at RECORD time").
- **>1 cart:** lead is **SELECTED FROM A LIST** of carts (the ConvoyScreen dialog → `setLeadCart(node.nodeId)` at ~1046/2104).
- **1 cart:** that cart is **ASSUMED the lead** automatically (`nodes.size == 1 -> setLeadCart(nodes[0].nodeId)`).
This means the old "lead recomputed every tick by lowest convoyPosition / needs heading+movement" concern is SUPERSEDED — a solo cart is assumed lead at REC unconditionally. convoyPosition/markerColor is still the COLOR source (Fred), but lead is NOT gated on it anymore.

**SO THE INTERMITTENCY IS A REC-TIME STATE RACE (refined):** at the instant RECORD is pressed, three things must hold for the solo Android cart to become lead and draw:
1. `readLiveNodes()` returns EXACTLY 1 node = the built `!phone` node (requires `getPhoneLocation()` to have a valid fix so the node is real, not lat/lon 0.0).
2. `lockedLeadNodeId == null` so the assign block runs (if it's stale non-null from a prior session, the whole `if` SKIPS → lead never set to this session's cart → brown/no-draw).
3. The assigned lead id == the `!phone` node's id so tick `nodeId == lockedLeadNodeId` (~681) matches.

FAILURE MODES that produce the intermittent brown/no-track:
- REC pressed before phone GPS warm → `readLiveNodes` returns 0 nodes → `nodes.isEmpty()` branch → `setLeadCart(_myCartId.value)`; even if that's `!phone`, there's no `!phone` NODE yet → tick can't match → brown/no-draw until (if) the node appears.
- `lockedLeadNodeId` stale non-null at REC → assign skipped → lead is last session's id → never matches this cart.
- Clean case: phone warm + 1 node + lockedLeadNodeId null at REC → lead = `!phone`, node present → green/draws. ← the "sometimes it works."

**SURGICAL FIX (revised to the REC-time rule):**
- On RECORD press (standalone/no-radio), RESET `lockedLeadNodeId = null` first, THEN assign, so a stale lock never blocks this session.
- Ensure the `!phone` node EXISTS at REC (guarantee getPhoneLocation returns the last-known fix so the node is built with a real position, not 0.0) — or defer the lead-lock until the first valid `!phone` node appears.
- Confirm the 1-cart branch assigns the NODE's id (`nodes[0].nodeId` = `!phone`), not `_myCartId.value`, when a node exists.
Keep the >1-cart dialog path and radio `!%08x` path untouched.

**PULL/CONFIRM NEXT SESSION:**
- `grep -n 'lockedLeadNodeId' …/ConvoyViewModel.kt` — is it EVER reset to null on a fresh RECORD? (If not → stale-lock skip is the intermittency.)
- `git show eed497680 -- …/ConvoyViewModel.kt` — what "identity locked at RECORD time" changed re: when lockedLeadNodeId is set/reset.
- The RECORD-press handler (where startRecording/setLeadCart fire) — order of: reset lock → readLiveNodes → assign.
- INSTRUMENT (`CartDiag`) at REC: log node count, the `!phone` node present?/its lat-lon, `lockedLeadNodeId` before+after, chosen lead id. Reproduce brown vs green to see which failure mode fires.

---

## ⭐ CONSTRAINT (Fred) — PHONE GPS IS WARM AT MAP-OPEN (we center on it) → "REC before warm" is NOT the failure mode
Map-open centers on the device position, so the phone GPS fix is available well before RECORD. Therefore the `!phone` node CAN be built with a real position at REC (getPhoneLocation falls through to `getLastKnownLocation`, which is warm from the centering). This ELIMINATES the "GPS not warm → 0 nodes at REC" failure mode.

**⇒ THE INTERMITTENCY IS ALMOST CERTAINLY THE STALE `lockedLeadNodeId` LOCK.** Leading theory now:
- GPS warm + `!phone` node builds fine at REC, BUT
- `lockedLeadNodeId` is already NON-NULL (left set from a prior session or a prior REC), so `if (lockedLeadNodeId == null)` (ConvoyViewModel ~160) SKIPS the re-assign, and lead stays pointed at a STALE id that no current node matches → tick `nodeId == lockedLeadNodeId` (~681) fails → node not treated as lead → brown / no track.
- Intermittent "no rhyme or reason" = depends on what `lockedLeadNodeId` was left as (null → works/green; stale non-null → fails/brown).

**PRIMARY FIX (surgical, high-confidence):** on RECORD press (at least in standalone/no-radio), RESET `lockedLeadNodeId = null` BEFORE the assign block, so a fresh REC always re-locks lead to the current session's cart. (For >1 cart, the dialog still selects.) This directly addresses the stale-lock skip. Verify `getPhoneLocation` still returns the warm fix so the `!phone` node is present when the assign runs.

**ONE CONFIRMING PROBE:** `grep -n 'lockedLeadNodeId' …/ConvoyViewModel.kt` — find every set/reset. If there is NO reset-to-null on a fresh RECORD/start, the stale-lock is the bug. Then `git show eed497680` to see whether "identity locked at RECORD time" introduced the persistent lock without a per-session reset.

---

## 🎯🎯🎯🎯🎯 STRONGEST THEORY (Fred) — GREEN vs BROWN = TWO POSITIONS; device slotted as cart-2 not lead → no track
Fred's reframe: green and brown are TWO DIFFERENT convoy POSITIONS — green = LEAD (position 1), brown = CART-2 (`#8B4513` "Mixed/unknown"/second slot). Brown means the device was placed as a NON-LEAD cart → since only the lead's track draws, no track. Root = the lead-cart id doesn't match the device's cart id.

**CONFIRMED: TWO separate lead-id variables exist and drive different things:**
- `lockedLeadNodeId` (ConvoyViewModel ~87) → passed to `ConvoyEngine.assignLeadTail(sorted, lockedLeadNodeId, …)` (~43/107) → decides which node is `isLead` → drives convoyPosition/`markerColor` (GREEN=lead/pos1 vs BROWN=non-lead).
- `currentLeadNodeId` (ConvoyViewModel ~308) → used at ~609 as `trackFrom` → **the TRACK-DRAW source.**
- tick lead lookup at ~681 uses `lockedLeadNodeId` (`nodes.firstOrNull { it.nodeId == lockedLeadNodeId }`).

**THE MECHANISM (matches brown-cart-2 + no-track exactly):** color/position is computed from `lockedLeadNodeId` (via assignLeadTail); the track draws from `currentLeadNodeId`. If these two (and the device's `!phone` id) DIVERGE:
- device positioned as non-lead → BROWN, AND
- track draws from a `currentLeadNodeId` pointing elsewhere/nowhere → NO track.
When all three agree on `!phone` → device = lead = GREEN (pos 1) → track draws. The divergence being intermittent = the "no rhyme or reason."

**PRIME SUSPECT:** `setLeadCart` (~194) sets `lockedLeadNodeId` but may NOT set `currentLeadNodeId` (or vice-versa), so they drift. The commit `eed497680` "identity locked at RECORD time" likely touched one path and not the other.

**CONFIRMING PULLS NEXT SESSION:**
- `sed -n '194,210p' …/ConvoyViewModel.kt` — does `setLeadCart` set BOTH `lockedLeadNodeId` AND `currentLeadNodeId`? (If only one → they drift → brown/no-track.)
- `grep -n 'currentLeadNodeId' …/ConvoyViewModel.kt` — every set/read; is it ever set to the `!phone`/device id at REC?
- `grep -n 'lockedLeadNodeId' …/ConvoyViewModel.kt` — every set/read/reset.
- `sed -n '38,46p' …/ConvoyNode.kt` — the `markerColor` palette: confirm index/position → color (green=pos1, brown=unknown/pos2), proving green=lead / brown=cart2.
- INSTRUMENT (`CartDiag`) each tick: log `lockedLeadNodeId`, `currentLeadNodeId`, the device node's id + its `convoyPosition` + `markerColor` + `isLead`, and `trackFrom`. Reproduce brown vs green → SHOW the two ids diverging.

**LIKELY SURGICAL FIX:** make `setLeadCart` set BOTH `lockedLeadNodeId` and `currentLeadNodeId` to the same id (and to the device's `!phone` id in the solo case), so position/color, tick lookup, and track-draw all agree. Keep radio/dialog path consistent (both vars set together everywhere).

---

## 🎨✅✅ CONFIRMED COLOR MAP (from ConvoyNode.markerColor, live code) — proves the id-mismatch
`markerColor` is assigned ROLE-FIRST, then position palette:
- `if (isLead) return "#1CF0A0"` → **GREEN = LEAD CART**
- `if (isTail) return "#FF8C42"` → ORANGE = tail
- `if (isMyCart) return "#2E75B6"` → BLUE = my cart (non-lead)
- else → `palette[convoyPosition % 16]`: 0 pink `#E91E63`, 1 purple `#9C27B0`, 2 indigo `#3F51B5`, 3 cyan `#00BCD4`, 4 green `#4CAF50`, 5 lime `#CDDC39`, 6 orange `#FF9800`, 7 deep-orange `#FF5722`, **8 BROWN `#795548`**, 9 blue-grey `#607D8B`, 10 `#F06292`, 11 `#CE93D8`, 12 `#90CAF9`, 13 `#80DEEA`, 14 `#A5D6A7`, 15 `#FFF176`.
Symbols: lead=triangle, tail=triangle-stroked, myCart=star, else=circle. Size: lead/tail/myCart=large, else=medium.

**THE SMOKING GUN — BROWN (`#795548`) = palette index 8 = a GENERIC convoy member: isLead FALSE, isTail FALSE, isMyCart FALSE.** So when the device pin is BROWN it means NONE of its role flags are set — its `nodeId` matched NEITHER `lockedLeadNodeId` (→ no isLead) NOR `myCartId` (→ no isMyCart). Both flags fail for the SAME reason: **id mismatch.** (And it lands on pos 8 specifically → convoyPosition ended up 8/24/… i.e. an uninitialized/garbage position, another sign it wasn't slotted.)

- GREEN `#1CF0A0` = node.id == lockedLeadNodeId → isLead=true → LEAD → track draws.
- BROWN `#795548` = node.id == neither lead nor myCart → generic palette pos 8 → NOT lead → NO track. (If it were merely "myCart but not lead" it would be BLUE `#2E75B6`, not brown — so brown means it isn't even recognized as myCart. Full identity miss.)

**⇒ CONFIRMS Fred's theory:** tick is not matching the device's cartid to the lead-cart id. Match → green/lead/track. Miss → brown/generic/no-track. The intermittency = whether the device node's `nodeId` equals `lockedLeadNodeId`/`myCartId` at that tick.

**NOTE for the fix:** expected-good solo state should be GREEN (isLead) — NOT blue. If you ever see BLUE (`#2E75B6`), that's isMyCart-but-not-lead (id matched myCartId but not lockedLeadNodeId) — a DIFFERENT partial state worth distinguishing in the CartDiag log. Brown = matched nothing.

**INSTRUMENT (CartDiag) — now with color meaning:** each tick log the device node's `nodeId`, `myCartId`, `lockedLeadNodeId`, `isLead`, `isMyCart`, `convoyPosition`, `markerColor`. Brown → confirm nodeId ≠ lockedLeadNodeId AND ≠ myCartId; blue → nodeId == myCartId but ≠ lockedLeadNodeId; green → nodeId == lockedLeadNodeId. Read which id fails to match and where it's set. Fix = ensure the device node's nodeId, myCartId, and lockedLeadNodeId are the SAME string (`!phone` solo) so isLead is set → green → track draws.

---

## 🚨🚨 CARTDIAG LOG RESULT (07-01 03:31, instrumented build on d719fbc95) — ROOT CAUSE = TWO TICK LOOPS / TWO VIEWMODEL STATES RUNNING AT ONCE
NOT an id-format mismatch. The log shows tick lines arriving in PAIRS ~170ms apart every 5s = two concurrent tick loops with DIFFERENT state:

- **Instance A (the `.27x`/`.28x` timestamps):** ALWAYS `lockedLead=null currentLead=null myCart=HOTEL-10 leadFound=false stateIds=!23dec46a(#FF8C42/MINE)`. Never runs the identity/lead-assign block — stuck on the `HOTEL-10` prototype default, lead never set, node orange(#FF8C42=tail)/MINE. This instance NEVER draws.
- **Instance B (the `.44x`/`.45x` timestamps):** after `setLeadCart nodeId=!23dec46a matchInState=true` (39.545) + `START myCart=!23dec46a lockedLead=!23dec46a nodeIds=` (39.555), progresses correctly: `lockedLead=!23dec46a currentLead=!23dec46a myCart=!23dec46a leadFound=true stateIds=!23dec46a(#1CF0A0/LEAD/MINE)` = GREEN/LEAD/found. This instance DRAWS.

**So the intermittency = which instance's state the map reads.** RECORD updates ONE instance (B → green/lead/draws); the OTHER (A) stays default (HOTEL-10/null-lead/orange/no-draw). Two copies of ConvoyViewModel (or two tick coroutines) are alive simultaneously and fighting over the same map.

**Key corrections to earlier theories:**
- `#FF8C42` = ORANGE (tail role), NOT brown. The stuck node is flagged isTail, not palette-pos-8. (Brown/green two-position theory was directionally right — wrong-role vs right-role — but the mechanism is dual-state, not convoyPosition.)
- The device HAD A RADIO this run: id `!23dec46a` is a real `!%08x`, not `!phone`. Identity/lock worked fine in instance B. So the id logic is NOT broken.
- `START ... nodeIds=` was empty in instance B at track-start (readLiveNodes returned 0 that call) yet lead still locked to the radio id — timing, but it recovered.
- Instance A never ran the identity block at all → permanent HOTEL-10 + null lead. (HOTEL-10 here = that instance's assignment never fired — consistent with Rule 4: HOTEL-10 = real assignment failed. Here it failed because that instance is the orphaned/duplicate one.)

**REVISED ROOT CAUSE:** a LIFECYCLE bug — two ViewModel/tick instances running concurrently. Likely: ViewModel recreated (config change / navigation / nav-graph scoping) without the prior tick coroutine cancelled, OR two `viewModelScope.launch` tick loops started. `eed497680` "identity locked at RECORD time" explains why only the RECORD-updated instance gets identity while the orphan stays default. This is why "everything looks right in the code" (it IS right) but behavior is intermittent — TWO copies run and the map reads whichever.

**THIS IS FIXABLE WITHOUT THE FULL REWRITE.** The cart/lead logic is correct (instance B proves it goes green/lead/found). The bug is duplicate instances. Fix = ensure ONE ViewModel + ONE tick loop.

## NEXT SESSION — confirm + kill the duplicate (surgical)
1. Find where the tick loop is launched: `grep -n 'viewModelScope.launch\|fun tick\|while (true)\|delay(' …/ConvoyViewModel.kt` — is tick started in more than one place / restarted without cancel?
2. Find ViewModel scoping: how is ConvoyViewModel obtained in ConvoyScreen / ConvoyMapViewerScreen / nav graph — `hiltViewModel()` per-screen vs activity-scoped? Two screens each getting their own instance = two ticks.
3. Confirm two instances: add `System.identityHashCode(this)` to the CartDiag TICK log → two distinct hashes = two instances (proof). (Instrument-first: prove it before fixing.)
4. Fix: single shared ViewModel scope (activity/nav-graph scoped, not per-composable), and/or cancel any prior tick job before starting a new one (store the Job, cancel in a single-flight guard). Keep the CartDiag log until the pair collapses to a single line per tick.

---

## 📗 FOUND: GroupTrack_TickEngine_Reference.md — the authoritative tick-engine overview (use for the rewrite)
This existing doc (was embedded in AllDocs ~14060; now a standalone upload) is the tick-cycle spec Fred knew existed. It documents the intended single-tick design end to end: 9 stages (GET NODES → SELF-HEAL LEAD → CONVOY ENGINE COMPUTE {status/heading/sortPositions/assignLeadTail/span/proximity} → FEED RADIO GPS → DEBUG → TRAIL ACCUM {lead-only vs multi} → OFF-TRACK → COLOR → HUD), the key session vars, convoy-order projection math, the file map, and the "lead dropout" problem+fix. USE THIS as the design baseline for the cart/lead/tick rewrite (do not re-derive).

**IT CONFIRMS tonight's mechanism — "THE LEAD DROPOUT PROBLEM":** `assignLeadTail` finds the node matching `lockedLeadNodeId`; if that node isn't in the active list → `state.lead = NULL` → tick trail block (stage 6, lead-only) is ENTIRELY SKIPPED → track freezes. Same failure our CartDiag log showed (leadFound=false → no draw).

**IT NAMES THE INTENDED FIX we found was rolled back — Stage 2 "SELF-HEAL LEAD ASSIGNMENT":** *"If recording + 1 node + no lead → auto-assign that node as lead."* The tick loop was DESIGNED to self-heal the solo-cart-no-lead case every tick. Per AllDocs 4033 the self-heal block was ROLLED BACK — which is very likely why the stuck instance never recovers (no per-tick self-heal to reassign lead to the lone `!phone` node). RESTORING a correct self-heal (work WITH tick) is a documented, intended fix, not a new invention.

**WHAT THE REFERENCE DOES NOT COVER (tonight's NET-NEW finding):** the reference describes ONE tick loop. Our CartDiag log proved TWO concurrent tick loops / two ViewModel states running at once (paired log lines ~170ms apart, same PID; one stuck at HOTEL-10/null-lead, one correct at !phone|radio/LEAD). That DUAL-INSTANCE lifecycle bug is separate from and on top of the reference's single-tick design. So the two problems to fix:
  A) LIFECYCLE: collapse to ONE ViewModel + ONE tick loop (confirm via System.identityHashCode in the tick log; fix scope: shared nav/activity-scoped VM and/or single-flight tick Job with cancel).
  B) DESIGN: restore Stage-2 self-heal (recording + 1 node + no lead → assign that node lead) so a solo cart is always lead, per the reference — "work WITH tick."
Fixing A alone may make behavior deterministic; fixing B ensures the solo cart is correctly lead. Do A first (it's the intermittency), then B (the standalone-lead correctness). Both are grounded in the reference + tonight's log — surgical, not a from-scratch rewrite.

## FILE MAP (from the reference, for the rewrite scope)
- ConvoyViewModel.kt (~877): tick(), trail accumulation, state.
- ConvoyEngine.kt (~178): PURE compute — status/heading/sorting/proximity (no state; safe).
- ConvoyGpsService.kt (~480): GPS recording, GPX write (certified working; leave).
- ConvoyScreen.kt (~2000): UI, map display, drawTrack().
- ConvoyConfig.kt (~60): constants/thresholds/flags.
