# GroupTrack V2.5 — Cosmetic / Minimal-Impact Batch — RUN SHEET — 2026-06-10

_Goal: shrink the open-issue list by clearing the 10 cosmetic/minimal items in one batch, BEFORE tonight's functional review + scheduling. Each item = a DISCOVERY step (locate the live anchor — line numbers drift, so never trust a remembered number blind) then the CHANGE. Run one at a time, paste results, I confirm the exact edit before you apply it. All on branch feature/convoy-event-ride, in ~/Meshtastic-Android. RELEASE build to verify. Commit each separately with the suggested message._

_Order = cheapest/safest first. Items 1–5 are zero-behavior-risk. 6–10 touch UI text/placement or docs._

---

## 1 — [8.4] Strip diagnostic logs (3 sites) — pure deletion
**Why:** debug Log.d left in committed code; must go before AAB. No behavior change.
**Discovery:**
```
grep -rn 'tracedLen' app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt app/src/main/java/com/geeksville/mesh/convoy/ConvoyMapViewerScreen.kt
```
Expect ~3 hits: ConvoyScreen ~555 (tracedLen) + ~716 (S2 tracedLen), ConvoyMapViewerScreen ~490 (S2P tracedLen).
**Change:** delete each `Log.d(... tracedLen ...)` line (and any `val ... = ...joinToString...` that exists ONLY to feed the log — paste the lines first so I confirm which are log-only vs load-bearing). File is CRLF — match `\r\n`.
**Commit:** `chore: strip snap-2 diagnostic logs (tracedLen) pre-AAB`
**Verify:** build succeeds; route draw still traces (logs were read-only).

---

## 2 — [6.6] Duplicate AlertDialog import — delete one line
**Why:** ConvoyScreen.kt imports AlertDialog twice (lines ~34 and ~85). Tidy.
**Discovery:**
```
grep -n 'import.*AlertDialog' app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt
```
**Change:** delete the SECOND occurrence (keep the first). Paste both lines so I confirm they're identical (if one is material3 and one is material, that's NOT a dupe — stop).
**Commit:** `chore: remove duplicate AlertDialog import in ConvoyScreen`
**Verify:** build succeeds.

---

## 3 — [6.7] !! / safe-call warning tidy
**Why:** compiler warnings, not errors. Low value but free while we're here.
**Discovery:**
```
./gradlew compileGoogleReleaseKotlin 2>&1 | grep -iE 'warning.*(!!|safe call|unnecessary)' | head -20
```
**Change:** address only the trivially-safe ones I can confirm from the warning text + a 3-line read of each site. SKIP any where removing `!!` changes nullability semantics — those are functional, not cosmetic.
**Commit:** `chore: tidy redundant !!/safe-call warnings`
**Verify:** build succeeds; warning count drops.
**NOTE:** if the warning list is long or ambiguous, DEFER this one — it's the least valuable of the batch and not worth a rabbit hole tonight.

---

## 4 — [8.5] SpecifyForegroundServiceType lint — one manifest attribute
**Why:** androidx.work SystemForegroundService needs android:foregroundServiceType. Lint-only, build succeeds, but fix before Play submission.
**Discovery:**
```
grep -n 'SystemForegroundService\|foregroundServiceType\|<service' app/src/main/AndroidManifest.xml
```
**Change:** add `android:foregroundServiceType="location"` (confirm the type — GroupTrack's FGS is location tracking) to the work-manager service entry. Paste the <service> block so I match it exactly.
**Commit:** `fix: specify foregroundServiceType on work service (lint)`
**Verify:** build; lint no longer flags SpecifyForegroundServiceType.

---

## 5 — [8.6] versionCode bump — one number
**Why:** each AAB needs a higher versionCode (monotonic). versionName cosmetic.
**Discovery:**
```
grep -n 'versionCode\|versionName' app/build.gradle
```
**Change:** bump versionCode by 1 from current (last seen 29320598; fallback used 29320600). Confirm current value, then +1. Leave versionName unless you want to mark 2.5.
**Commit:** `chore: bump versionCode for next build`
**Verify:** build picks up new code (or pass -Pandroid.injected.version.code at bundle time).

---

## 6 — [3.5] Convoy contact-lost `?` placement — move one working block
**Why:** the convoy `?` help icon shipped INSIDE `if (convoyState.hasLost ...)` so it only shows on contact-lost. Move it to always-visible, beside QUEUES. (This is the CONTACT-LOST `?`, the genuinely-cosmetic one — NOT the manual-panel `?`, which is functional [9.4].)
**Discovery:**
```
grep -n 'hasLost\|QUEUES\|"?"\|help' app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt | head -30
sed -n '1355,1375p' app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt
```
Anchor target: the locked top-right Surface, Text("QUEUES") ~1368, QUEUES row ~1359.
**Change:** move the `?` composable out of the `if (convoyState.hasLost)` block to sit beside the QUEUES button in the always-visible top-right Surface, matching its style. Paste the current `?` block + the QUEUES Surface so I write the exact move (this one's a real cut/paste in CRLF — I'll match line endings).
**Commit:** `fix: convoy help ? always visible beside QUEUES (was contact-lost only)`
**Verify on device:** `?` shows on convoy map without a lost contact; tapping still opens help.

---

## 7 — [1.7] Route-builder cosmetic text — two strings
**Why:** (a) empty In-Progress picker shows only "Cancel" → add "No in-progress routes yet"; (b) blank-name hint wrongly reads "name is taken" (reuses routeNameTaken flag) → distinct "Name required" message.
**Discovery:**
```
grep -rn 'in-progress\|In Progress\|name is taken\|routeNameTaken\|Name required\|Cancel' app/src/main/java/com/geeksville/mesh/convoy/ConvoyMapViewerScreen.kt app/src/main/java/com/geeksville/mesh/convoy/RouteManager.kt
```
**Change:** (a) add an empty-state line to the In-Progress picker dialog; (b) split the hint so blank-name and taken-name show different text. Paste the dialog + hint blocks; I write the exact edits. Both maps if the dialog is duplicated (check ConvoyScreen too).
**Commit:** `fix: route picker empty-state + correct name-required vs name-taken hint`
**Verify on device:** open picker with no drafts → see empty-state text; try to save blank name → "Name required"; try duplicate name → "name is taken".

---

## 8 — [8.8] lintVital ServiceKeepAlive tidy
**Why:** lintVital warning. Free cleanup near the manifest work in item 4.
**Discovery:**
```
./gradlew lintVitalGoogleRelease 2>&1 | grep -iA3 'ServiceKeepAlive\|KeepAlive' | head -20
```
**Change:** apply the lint's suggested fix once I see the exact message + site. If it's non-trivial, DEFER (it's vital-lint, may block AAB — but only fix it correctly, not hastily).
**Commit:** `chore: resolve lintVital ServiceKeepAlive`
**Verify:** lintVitalGoogleRelease passes.

---

## 9 — [7.7] Standalone marketing copy (splash / about) — text
**Why:** position the app as standalone-friendly. Text only.
**Discovery:**
```
grep -rn 'about\|About\|splash\|Splash\|version\|tagline' app/src/main/java/com/geeksville/mesh/ | grep -i 'screen\|about\|splash' | head -20
```
**Change:** update/add the standalone-friendly line on the about/splash screen. DECISION NEEDED FROM FRED: the exact copy. I'll draft 2–3 options tonight; this one may slip to the review since it needs your wording, not just a code edit.
**Commit:** `content: standalone-friendly copy on about/splash`
**NOTE:** lowest-urgency; fine to defer to the content discussion.

---

## 10 — [9.6] Decision Log append (06-07 + 06-10) — doc, not code
**Why:** the 06-07 and 06-10 sessions were never written into a dated Decision Log block. Append-only: new dated block on top, prior blocks verbatim.
**Change:** I'll draft the two dated blocks (06-07: manual/release-notes baked, geojson shrunk, convoy `?` applied-but-misplaced; 06-10: C-1 planning snap-2 mirror done + 3 commits, NH decision, consolidated checklist built) and you append them to GroupTrack_DecisionLog_APPEND_2026-06-06.html (→ save as ..._2026-06-10.html, since it's append-only and dated). No device, no build.
**Verify:** the log carries an unbroken dated history through 06-10.

---

## BATCH NOTES
- **Items 1, 2, 4, 5, 10** are the safest (deletion / one-attr / one-number / doc) — clear these first for fast shrink with near-zero risk.
- **Items 3, 8** (warning/lint tidies) — only do them if the warnings are clean and obvious; DEFER either if it turns into a rabbit hole. Not worth blocking the batch.
- **Items 6, 7** touch UI — real edits, device-verify, but small and well-anchored.
- **Item 9** needs your copy wording — likely slips to tonight's discussion.
- After the batch: **list shrinks by ~8 items** (1,2,4,5,6,7,10 cleared + maybe 3,8), leaving the functional clusters for tonight's review + scheduling.
- One build covers items 1–8 (all in the app); build once at the end of the code items, not after each, to save time — but commit each separately so they're individually revertible. (Item 5 versionCode + item 4 manifest want to be in the build you test.)
