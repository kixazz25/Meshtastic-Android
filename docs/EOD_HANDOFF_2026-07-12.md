# GroupTrack — EOD Handoff — 2026-07-12

## Focus of the day
Continued V2.6a **Route+ persistence / convoy bbox-restore** — but the day became a **direction change**: rolled back three days of uncommitted convoy viewport work, froze the convoy map, and moved Route+ to **planner-only**.

## Headline (read this first)
**The convoy map is FROZEN. Route+ is planner-only going forward.** Three days of convoy viewport/restore work was rolled back (it was never committed). Nothing shipped today; this was a rollback-and-replan session. The build is green on the rolled-back baseline + kept planner Route+.

## What happened today (honest narrative)
1. Chased a convoy reopen bug ("map opens in a new location / exit-vs-reopen frame not identical"). Two theories were pursued and **both were wrong** (fitBounds-recompute; artifact-dismiss clobber).
2. The **real root cause** was found in the JS: `convoy_map.html:163` — `setView()` calls `reportViewport()`, so **every programmatic map move re-saves itself**. Restore re-saves; the Kotlin-side guard could never catch a save that originates in JS. (z18 was the maxNativeZoom clamp ceiling caught in the loop.)
3. On the reverted, known-good 07-11 baseline, the **bbox-padding cumulative zoom-out** (trail shrinks / map area grows every reopen) **still reproduces** — proving it is a **pre-existing** convoy flaw, present before any of the three days' work.
4. Decision: **stop patching a system with no documentation or coherent mental model.** Freeze convoy. Roll back. Keep Route+ on the planner where it works.

## What was rolled back / kept
- **Rolled back** (`git checkout -- convoy_map.html ConvoyScreen.kt`): all convoy viewport/zoom work — viewport-save patch, zoom-persistence + isRestoring guard, the artifact-dismiss one-liner, the z12 diagnostic. All of it was **uncommitted**, so rollback was clean; last commit is 825f7babd (07-11, docs-only).
- **Kept** (still uncommitted, working planner Route+ + shared deps): `ConvoyMapViewerScreen.kt` (planner — routeState-in-save, onNewRoute auto-name, onUndo per-point save), `MapStateStore.kt` (RouteState field; also an inert zoom field left over — harmless), `RouteDraftStore.kt`.
- **Build:** `assembleGoogleRelease` — **BUILD SUCCESSFUL**. Reverted convoy + kept planner compile clean against the shared MapStateStore signature.

## Still verified working (SAVE side — unchanged from 07-11)
Per-point draft save (33 clean snapped vertices), WIP-save (byte-identical re-pull), routeState writes to panel JSON, auto-name "Auto Saved In Progress", Discard "Delete in-progress".

## The plan going forward — PLANNER ONLY
Recovery is **simpler** now: Route+ is planner-only, so any auto-recovery JSON can only come from the planner — no cross-map source ambiguity, single writer/reader.

**Recovery model = 3-way resolution forced at launch-detection.** When an auto-recovery JSON exists at launch: dialog "auto-recovery route detected" → user must resolve one of three ways:
1. **Save as in-progress** (keep as WIP)
2. **Save as completed** (graduate to a permanent route — real name required)
3. **Discard** (delete the auto-recovery JSON)

This is the same resolution set the earlier exit-gates model used; only the trigger point differs (launch vs exit). Confirm exit-gates vs dialog-on-load as the chosen model at next session start — they were alternatives and this revives dialog-on-load.

## Next-session checklist (all planner unless noted)
1. **Remove Route+ from the convoy artifact panel** — the only convoy touch, and it is a *removal* of an entry point (not editing convoy's restore/save logic). Respects the freeze.
2. **Planner: New Route drops the name prompt** — creates the auto-recovery JSON instead (no start dialog).
3. **Planner: Save-in-progress renames** the auto-recovery JSON to the user's chosen name.
4. **Planner: check auto-recovery JSON existence on load.**
5. **Planner: if found → dialog → 3-way resolution** (in-progress / completed / discard).
6. **Fix the padding issue on the planner** — same cumulative bbox-padding-on-restore drift. ⚠ **SHARED-FUNCTION CHECK FIRST:** trace whether the padding/restore path is shared with convoy. If separable → fix planner's copy. If truly shared → the fix also touches convoy (would fix convoy's drift as a side effect) → needs explicit sign-off before proceeding, since convoy is frozen. Fix direction: don't re-pad an already-padded saved bbox; silent-setView if the planner JS self-saves like convoy's.
7. **Fix the draw yes/no toggle on the planner** — point-registration guard: a tap becomes a route vertex ONLY when draw is ON. Launch = draw OFF (taps pan); user explicitly arms; only then do taps register. Draw-mode = explicit `DrawMode {OFF, ON}` enum, program-set OFF at load, never persisted, one source of truth driving switch + guard + JS bridge. **This exists only in Route+ → planner-only, no convoy/shared risk.**

## Known pre-existing convoy bug (documented, NOT fixing — frozen)
Convoy reopen = **cumulative zoom-out**: restore reads saved bbox → pads it → saves the padded bbox → next reopen pads that → frame grows each cycle. Reproduces on the 07-11 baseline (pre-existing). Left as-is. Real fix if convoy is ever revived: silent setView (no reportViewport) for restore + don't re-pad an already-padded bbox. **Prerequisite for ANY convoy change = document the layered process (Kotlin ↔ JS bridge ↔ Leaflet ↔ self-saving setView ↔ shared MapStateStore) first.**

## Doc-process status
- **Release notes (HTML asset):** NOT updated with Route+ (still unshipped). Only the date stamp carried forward as superset — no new user-facing work shipped today.
- **Manual (HTML asset):** Route+ persistence section stays **unapplied** (unshipped). The staged manual-updates doc corrected: recovery is **planner-only**, not "both maps."
- **Living master:** updated (this session) to reflect freeze + planner-only pivot + rollback.

## Still open / backlog (unchanged)
In-progress picker not listing a populated draft; loadQueue boot-loop immunity; saveQueue atomic-write hardening; clear-area/delete-area; the three no-radio regressions; lead-cart re-engineer; git push blocked by 3 large .db in history; Esri tile-cost strategy; V2.6 release delayed ~4 weeks.
