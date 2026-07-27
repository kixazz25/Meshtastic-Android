# GroupTrack — EOD Handoff — 2026-07-11

## Focus of the day
V2.6a **Route+ persistence** — making an in-progress route survive navigation, recomposition, and process death, then recover cleanly on return.

## Where things landed (honest status)
Route+ persistence is **in progress — not committed, not shipped.** The SAVE side is verified working; the RESTORE side is mid-fix, and the current on-device build is broken (details below). A cleaner recovery design was reached this session and is the plan going forward.

## What is verified working (SAVE side)
- **Per-point draft save** — confirmed on device: a test draft captured 33 clean, correctly-snapped vertices (all lineId / segmentIndex / snapped fields correct).
- **WIP-save** — re-pull returns byte-identical geometry with fresh timestamps.
- **routeState now writes** to the panel JSON (the field wired into the map snapshot).
- **Auto-naming** — new routes auto-name to "Auto Saved In Progress" with no start-time dialog.
- **Discard "Delete in-progress"** — calls delete and clears state (works; earlier "didn't delete" was the "Roll back" button, which keeps the draft by design).

## Current build state (broken — do not ship)
- Phase-2 build compiled green (29m45s) and is installed on the sacrificial test device.
- **On device it is broken:** route-add mode is stuck armed at launch, so every map tap/pan/search drops a route point (map unusable), the add-on/off toggle appears dead, and a phantom first point lands at launch.
- **Cause:** the phase-2 approach seeded the armed flag from the persisted state and guarded the map's follow behavior — this combination leaves the flag stuck on. This approach is being replaced (see plan).

## The plan going forward (converged this session)
**Recovery-file dialog, triggered on map open.**

- **Trigger:** any time the map opens and a recovery-file route exists, recovery fires. (Map open = event; recovery file present = condition; recovery flow = action. No seeded flag, no nav-path conditions.)
- **Flow:** dialog — "Recovery file found — press OK to recover your route" — then on OK, *after the map draw completes*: load the recovery route, arm route-add, and position the map on the route's first vertex (center at zoom 15). The user then has the save/discard controls and must resolve.
- **Why it's clean:** the state JSON is rewritten on every draw, so it self-corrects — there is no stale state to manage. Resolving (complete or discard) is itself the write that clears the state. Resolution is the clearing; no separate flag logic needed.
- **Shared components:** the dialog, the load-after-draw-complete resume, and the position-on-first-vertex logic are built as common code that BOTH the convoy map and the planner map call — extracted from the existing planner resume logic, not duplicated.

## Before the next patch — two validations
1. **Blast radius** of the GPS-center-on-launch call — confirm via logcat it fires only on map load/return, not on pan or record.
2. **State-writes-on-draw** — confirm the map-state write (which carries routeState) actually fires on route on/off/resolve, not only on viewport change.

## Undo when the fix goes in
Back out the phase-2 armed-flag seed and the three follow-guards (the stuck-armed cause). Keep the routeState-write wiring and the auto-naming.

## Still open / lower priority
- In-progress picker didn't list a populated draft (an empty one showed) — inspect the list query.
- Broader V2.6 scope unchanged (loadQueue boot-loop hardening, clear-area/delete-area, the three regressions, etc.).

## Next-session pickup
1. Run the two validations (logcat blast-radius; state-write-on-draw).
2. Extract the planner resume into a shared recovery helper.
3. Wire the recovery-file check + dialog + resume into both maps' page-finished path.
4. Build, install on the test device, verify recovery on map-open.
