# GroupTrack — Next Session Plan (2026-07-01)
Baseline: `d719fbc95` (clean, builds). CartDiag instrumentation currently applied to ConvoyViewModel.kt (revert with `git checkout -- app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt`).

## ⭐ RULE #0 (NEW, from 06-30 retro) — SEARCH EXISTING DOCS BEFORE DERIVING ANYTHING
AllDocs holds 247 embedded docs. We repeatedly rederive things already documented because we don't look. FIX: an index now exists — `GroupTrack_AllDocs_INDEX.md` (topic-grouped, line numbers). 
STEP -1 of ANY investigation: grep the INDEX (and `GroupTrack_AllDocs.txt`) for the subsystem FIRST. Only derive from scratch if nothing exists. This is Claude's job to do unprompted, every session — Fred should not have to remember a doc exists.

### Docs that ALREADY cover tonight's tick/lead/cart work (READ THESE FIRST tomorrow):
- **L14059 `GroupTrack_TickEngine_Reference.md`** — the 9-stage tick design (already have as standalone upload). Confirms the lead-null→no-draw mechanism + the rolled-back Stage-2 self-heal.
- **L13086 `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`** — THE SETTLED REWRITE PLAN for this exact code (checklist [2.1], "gut and restart"). Demolition inventory + one-lead/one-track rebuild. Authority: LeadTrackReplacement_Spec.
- **L1875 `GroupTrack_LeadTrackReplacement_Spec.docx`** (May 31) — the spec the rebuild is built on.
- **L1615/1706/1797 `GroupTrack_LeadLock_Problem_Resolution.docx`** (3 versions) — prior lead-lock diagnosis + resolution.
- **L4822 `GroupTrack_StandaloneMode_Spec_v2.docx`** — "device is always a node" single-path standalone design.
- **L133 `GroupTrack_ConvoyTrackPickerFixPlan_v1.docx`** — track picker fix plan.

### How tomorrow's tasks relate to these docs:
- The 2nd-tick-loop fix (Task 1) is a LIFECYCLE bug NOT covered by these docs (net-new from tonight's CartDiag log) — do the surgical fix to stabilize.
- BUT the DemolitionRebuild doc [2.1] is the SETTLED decision to gut+rebuild this same code. DECISION FOR FRED (rested): does the surgical tick-loop fix ship as a stopgap while [2.1] rewrite is scheduled, or go straight to the DemolitionRebuild plan now? Read L13086 + L14059 first, then decide. Do NOT let Claude pick — Fred decides, having read the existing plan.

## STANDING RULES (enforce every step — from 06-30 retro)
- STEP ZERO before touching ANY function: grep codebase + xref for EVERY definition of it; confirm which is live; kill duplicates BEFORE wiring. No wiring around duplicates.
- I FLAG any deviation from what Fred asks BEFORE writing code — no silent substitutions, no "better solution" without discussion first.
- Fred's process design (step-by-step controls, reuse, external-fn/DB rules) is the spec and outranks clean-code aesthetics. Keep instrumentation Fred asks for.
- ONE command at a time. Grep-verify before builds. Impact analysis (field_crossref/where_used/function_universe) before changes.

## TASK 1 (FIRST) — KILL THE 2ND TICK LOOP (the intermittent cart/track bug)
Root cause (proven by CartDiag log 07-01 03:31–03:33): TWO concurrent tick loops / ViewModel states run at once (paired log lines ~170ms apart, same PID 20219). One is stuck at HOTEL-10/null-lead/orange (never draws); one is correct (lead set, #1CF0A0/LEAD, draws). Map reads whichever → intermittent green/track vs orange/no-track. Cart logic itself is CORRECT (the good instance proves it).
Reference: GroupTrack_TickEngine_Reference.md describes ONE tick loop (tick runs from init{}). Two VM instances ⇒ two ticks.

STEPS (instrument-first):
1. CONFIRM instances: add `System.identityHashCode(this)` to the CartDiag TICK log. Rebuild, run. Two hashes = two VM instances; one hash = one VM ticking twice. (Proof before fix.)
2. FIND source (grep, no changes yet):
   - `grep -n 'viewModelScope.launch\|fun tick\|init {\|init{' …/ConvoyViewModel.kt` — how many tick launches; is tick started in init{} and/or elsewhere?
   - How ConvoyViewModel is obtained: `hiltViewModel()` per-screen in ConvoyScreen / ConvoyMapViewerScreen / nav graph vs a single shared scope. Two screens each creating their own = two instances.
3. FIX (per what 1+2 show):
   - If two VM instances → scope to ONE owner (nav-graph/activity-scoped VM shared across the convoy screens), OR
   - If one VM launching tick twice → single-flight: store the tick Job, cancel before starting a new one.
4. VERIFY: CartDiag pairs collapse to ONE line per tick; one identityHashCode; pin stays green/lead; track draws every run.
NOTE: after this is deterministic, consider restoring the rolled-back Stage-2 "self-heal lead" (recording + 1 node + no lead → assign that node lead) per TickEngine_Reference — makes solo cart always lead. Do this as a SECOND, separate step, only after the loop is single.

## TASK 2 (SECOND) — LEAFLET ARTIFACT REFRESH (redraw no longer processed)
Symptom: toggling artifacts off/on/select/close no longer refreshes the map; only panel EXIT reflects changes. State is correct (exit shows right result) — the live redraw is what's broken.
Cause (Fred): the route-mode popup-suppression change (Route BUG A fix — popup fired during route build → redraw overlaid the route) leaked OUTSIDE route mode and killed the normal artifact-toggle redraw. Changes were meant to be active ONLY during route building (`__routeMode == true`).
STEPS:
1. FIND the change: `git log --oneline -12 -- app/src/main/assets/convoy_map.html`; identify the popup/redraw-suppression commit; `git show <commit> -- convoy_map.html` (or `git diff` if uncommitted — check `git status` first).
2. CHECK the gate: is the suppression/redraw-skip gated on `__routeMode`, or applied ALWAYS? `grep -n '__routeMode\|popup\|redraw\|refresh\|pointer-events' convoy_map.html`.
3. FIX: narrow the gate so suppression applies ONLY while `__routeMode == true`; restore the normal artifact-toggle redraw path outside route mode. Mirror in grouptrack_map.html if the same code exists there (two HTMLs, CRLF, single-line python anchors, match-count guard).
4. VERIFY: in normal (non-route) mode, artifact off/on/select redraws the map live; in route mode, the popup still doesn't overlay the route.
DUP CHECK: confirm there's ONE redraw/refresh function the toggle uses, not two — per STEP ZERO.

## OPEN (not tomorrow unless time): the scheduled cart/lead/tick REWRITE
If Task 1 shows the code is too tangled to fix surgically, the rewrite is grounded in GroupTrack_TickEngine_Reference.md (the 9-stage design) + tonight's dual-instance finding. But try the surgical loop fix FIRST — the reference + log say it's a lifecycle bug, not broken logic.

---

## WORK-WITH-ARTIFACTS — OPEN ITEM (added 07-01): LEAFLET REDRAW NOT PROCESSED
Symptom: in Work-with-Artifacts, toggling an artifact off/on/select/close no longer REDRAWS the map live; only EXITING the panel reflects the change. State is correct (exit shows the right result) — the live redraw call is what's broken.
Cause (Fred): the route-mode popup-suppression change (Route BUG A fix: popup fired during route build → redraw overlaid the route) LEAKED outside route mode and killed the normal artifact-toggle redraw. It was meant to be active ONLY during route building (`__routeMode == true`).
Fix (Task 2, scheduled): narrow the gate so suppression applies only while `__routeMode == true`; restore the normal artifact-toggle redraw path outside route mode. Confirm ONE redraw fn (dup check). Mirror in grouptrack_map.html if same code. (Full steps in Task 2 above.)

## RECORD ISSUE — STATUS: DIAGNOSED + PLANNED (not open-ended)
RESOLVED tonight: recording WORKS (GPX writes real points — walk-test verified). The "no track on screen" is the CART/LEAD DISPLAY bug, root-caused to TWO concurrent tick loops (CartDiag log 07-01: one instance stuck HOTEL-10/null-lead, one correct/LEAD; map reads whichever → intermittent). Cart logic is correct. FIX = Task 1 (collapse to one tick loop). NOTE the settled rewrite alternative L13086 DemolitionRebuild [2.1] — Fred decides surgical-stopgap vs rewrite after reading L13086 + L14059.
