# GroupTrack — STATE + NEXT-SESSION PLAN (2026-07-01 EOD)

## CURRENT STATE (safe, stable)
- **On clean baseline `d719fbc95`** (HEAD). ALL 3 days of spatial/sync/save/import/extension-DB work is COMMITTED and SAFE in this commit. NOT the Play Store version (that's the older `c603bc3f0`). Nothing lost.
- Working tree: CLEAN. (Today's uncommitted my-cart fix + CartDiag were reverted via `git checkout -- ConvoyViewModel.kt`.) Untracked junk only: `d1.db`, `legacy_sample.gpx` (ignore).
- Builds clean. Branch 84 commits ahead of origin (unpushed).

## THE STRATEGIC DECISION (Fred, EOD) — REVERT THE GATE, RESTORE AUTOMATIC REFRESH
The map-independence change `e0182045a` ("per-map MapStateStore + lastMapProcessed gate") converted refresh from AUTOMATIC to MANUAL: the gate (`if lastMapProcessed != "convoy"`) suppresses reseed until a map-switch, so EVERY state-changing caller must now EXPLICITLY fire the `getBounds()->onViewportChanged` redraw round-trip. Some callers got it (toggle 1499-1500, FIT a43f80829); others didn't (panel onDismiss 1470, likely SELECT/EDIT ~1790-1799). This is BLAST RADIUS from a change that wasn't requested and wasn't well-researched — an ongoing manual-redraw tax where new/missed callers silently don't redraw.

**PLAN: restore automatic view refresh (undo/replace the gate), THEN observe:**
- What breakage DISAPPEARS = blast radius the gate change caused (not real bugs).
- What breakage REMAINS = real ghosts to fix (e.g. the track-draw / my-cart race, the pre-existing Android record issue).
This SEPARATES manufactured problems from actual ones — stop chasing symptoms of the gate change.

## HOW TO DO IT (next session, fresh — one step, verified, committed)
1. **See exactly what `e0182045a` changed** (full diff, not grep): `git show e0182045a`. Identify the gate + what refresh looked like BEFORE it.
2. **Decide revert scope:**
   - Option A: `git revert e0182045a` (if it's a clean standalone commit and nothing since depends on MapStateStore structurally). Cleanest.
   - Option B: surgically remove/loosen JUST the gate (`if lastMapProcessed != "convoy"`) so reseed happens on every viewport event (automatic refresh restored), while KEEPING the per-map JSON persistence (that part may be fine). This keeps map-independence's good half (separate JSON per map) and drops the bad half (the suppress-until-switch flag).
   - RECOMMEND Option B if map-independence's JSON-per-map is worth keeping; Option A if the whole change is suspect.
   - CAUTION: map-independence's GOAL was real (convoy vs planning not clobbering). If you remove the gate entirely, verify convoy/planning don't re-clobber. The better end-state: reseed automatically on SAME-map state change, guard only CROSS-map — but that's a redesign; for now, restore automatic refresh and test.
3. **Build, install, cold-launch. OBSERVE and LIST:**
   - Does artifact toggle/select/close redraw now (automatic)? 
   - Do tracks display on open (needs GPS->bbox)? 
   - Does track draw on record? (the original goal)
   - Which previously-broken things now work (blast radius gone) vs still broken (real ghosts)?
4. **Commit the refresh restoration** if it holds. THEN triage the remaining real ghosts one at a time.

## REAL GHOSTS (candidates that are NOT the gate — verify after refresh restored)
- **Track doesn't draw on record / my-cart HOTEL-10:** the my-cart id races node-info readiness at record (line 157 reads stale mirror `_myNodeInfo` vs live `nodeRepository.myNodeInfo`); lead lock freezes at a placeholder that doesn't match the real node. ALSO a genuine SECOND ConvoyViewModel instance runs (CartDiag proved 2 ticks, same PID, Planning NOT open — source still unidentified). ALSO a lead-dropout ~1 min into recording. (Today's 1-line fix helped no-radio case but left these; it was reverted.)
- **Android track RECORDING fails first attempt:** PRE-EXISTING in Play Store version (Fred confirmed). Separate from display. GPS warm-up / first-fix gap in ConvoyGpsService.
- **Track display depends on GPS->bbox:** no GPS on open = no bbox = no tracks. If GPS-on-open is broken, that cascades to all track display. Verify GPS resolves on open first.

## DO NOT REPEAT (today's lessons)
- Don't stack fixes; one change, verified working on-device, committed, BEFORE the next.
- Don't patch symptoms of a bad design (adding manual redraws) — fix the design (restore automatic refresh).
- Look at the actual DIFF of a suspect change before theorizing about it.
- Believe Fred's field observations over the code's apparent logic.
- Baseline d719fbc95 is the safety net; git checkout/reset returns here.

## FILES NEEDED NEXT SESSION
- `MapStateStore.kt` (the gate flag + per-map persist — to decide revert vs surgical).
- ConvoyArtifactsPanel SELECT/EDIT callbacks (~1780-1800 in ConvoyScreen.kt) — which paths miss redraw.
- Already have: ConvoyScreen.kt, ConvoyViewModel.kt, ConvoyEngine.kt, ConvoyGpsService.kt, ConvoyMapViewerScreen.kt.

---

## ⚠️ EXPECTATIONS FOR THE AM TEST (Fred flagged — gate revert is NOT a cure-all)
The gate revert restores automatic REFRESH (changes redraw). It does NOT fix everything:

1. **Track-DISPLAY issue will probably PERSIST.** If tracks don't display because of the GPS->bbox dependency (no GPS on open = no bbox = no track query), the refresh change does NOT touch that. Refresh fixes "changes don't redraw", NOT "tracks don't draw at all." Track-display is a SEPARATE real ghost. Verify GPS-resolves-on-open as its own thing.

2. **Route-build conflict will RESURRECT (known open issue "Route BUG A").** Restoring automatic refresh brings back the redraw that CLOBBERS an in-progress route: artifact popups fire -> redraw -> hides the new route being drawn. The broad gate had been suppressing this. So route building will conflict with artifact popups again. THIS IS EXPECTED, not a surprise — do not route-build in the AM test.

## THE REAL DESIGN FIX (next — resolves the tension)
The tension: automatic redraw is GOOD for artifacts (want it on change/close) but BAD during route-build (clobbers the route). Neither "gate always on" (breaks artifact refresh) nor "gate always off" (breaks route-build) is correct. CORRECT END STATE:
- **Automatic refresh normally** (gate off — tonight's change), PLUS
- **Suppress redraw ONLY while `__routeMode == true`** (narrow, mode-scoped gate during active route drawing).
This is a NARROW intentional suppression, not the broad always-on gate. Fixes artifact refresh AND protects route-build. Implement next session after AM testing confirms the gate revert's effects. (This is the same narrow-gate fix noted earlier for the Leaflet route-mode popup suppression leak.)

## AM TEST CHECKLIST (what to observe)
- Artifact toggle/select/close -> map redraws automatically? (gate revert should fix)
- Tracks display on open? (likely still broken — GPS->bbox; separate ghost)
- Convoy vs planning maps still independent (no clobber)?
- Track draws on record? (my-cart race ghost — separate)
- DON'T test route-build (known re-break; fix is the mode-scoped gate above).

---

## ⭐ WHY THE GATE/SAVE CHANGE EXISTED (Fred's recollection — CRITICAL, preserve this intent)
The save/refresh-suppression was put in to solve a REAL problem: Fred had NO track showing, wanted to SAVE (preserve whatever was recorded), and **touching the screen blew away the chance to see if it was actually recording** — a stray touch triggered a redraw/state-change that WIPED the in-progress recording state before he could confirm capture. So the gate/suppression PROTECTED in-progress recording from touch-triggered redraws.

**So the broad gate was doing (at least) THREE legitimate jobs, plus one bad side effect:**
1. Map-independence (convoy vs planning don't clobber). [keep]
2. Suppress artifact-popup redraw during ROUTE-BUILD (so redraw doesn't hide the route). [keep — Route BUG A]
3. **Protect in-progress RECORDING from touch-triggered redraws wiping it.** [keep — the ORIGINAL reason]
4. BUT broke artifact-refresh-on-close (the bad side effect). [the thing we want fixed]

## ⚠️ RISK FROM TONIGHT'S GATE REVERT — may resurrect #3
Removing the gate restores automatic refresh (fixes #4) but may UN-DO #1, #2, AND #3. So in the AM ALSO watch: **does touching the screen DURING RECORDING still wipe/clobber the recording?** If yes, the revert brought back the original problem. This is a THIRD thing to verify (with route-build and track-display).

## THE CORRECT DESIGN (now clearer) — narrow, purpose-scoped suppression, not a broad gate
Restore automatic refresh AS DEFAULT, and SUPPRESS redraw ONLY in the specific states where redraw causes harm:
- during active ROUTE-BUILD (`__routeMode == true`) — protects the route being drawn.
- during active RECORDING where a touch-redraw would clobber capture — protect the recording state (guard the touch/redraw path while `_trackActive`/recording).
- always read convoy-own JSON so map-independence holds.
This preserves all THREE legitimate protections while fixing the artifact-refresh side effect. The broad "gate everything until map-switch" was too blunt — it protected recording/route by breaking normal refresh. Replace with targeted guards.
IMPLEMENT next session after AM testing shows what the revert actually resurrected.
