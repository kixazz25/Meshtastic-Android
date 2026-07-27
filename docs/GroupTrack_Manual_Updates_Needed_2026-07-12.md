# GroupTrack — Manual Updates Needed — 2026-07-12

Canonical manual: `app/src/main/assets/grouptrack_manual.html`

**These edits remain STAGED, NOT applied — Route+ persistence has NOT shipped.**
Apply to the canonical manual only when Route+ persistence is committed and verified. Documenting an unshipped feature as live would mislead users.

**⭐ CHANGED 07-12:** Route+ is now **PLANNER-ONLY**. The 07-11 version of this doc said the behavior applies to "both the convoy map and the planner map" — that is **no longer correct**. Convoy is frozen and Route+ is being removed from it. All wording below is **planner-only**.

---

## What already exists in the manual
The manual documents the Route → In Progress flow (starting a route, snapping points along a trail, saving/discarding). The updates below extend that section for **persistence and recovery on the planner map**, once shipped.

## Section to update: Routes → In Progress (planner map)
Add, after the existing in-progress route description:

### Auto-save (behavior change)
- An in-progress route now saves automatically as you place each point. There is no "name your route" prompt when you start — the route is saved under a working name ("Auto Saved In Progress") and you name it when you finish.
- Undo removes the last point and the auto-save updates to match.

### Turning point-drawing on and off
- Placing route points is **opt-in**: you must turn draw ON before taps become route points. When draw is OFF, tapping the map pans and interacts normally without dropping points.
- The map always opens with draw OFF. (Prevents stray taps from adding points while you navigate.)

### If you leave with a route in progress
- Your in-progress route is kept. The next time you open the planner map, GroupTrack detects it and shows an **auto-recovery** prompt.
- You must **resolve** it before continuing — an in-progress route can't be silently lost. You choose one of:
  - **Save as work-in-progress** — keeps it in your in-progress list under a name you choose.
  - **Save as a permanent route** — you must give it a real name (the working "Auto Saved In Progress" name is not accepted).
  - **Discard** — removes the in-progress route.

## Scope note for the writer
- Applies to the **planner map only**. (Route+ has been removed from the convoy map.)
- Do NOT describe route creation/recovery on the convoy map.
- Wording above is user-facing; keep it consistent with the manual's existing Routes section voice.

---

## Reminder (doc-process)
- Verify the canonical file identity before editing (the Windows rename + EOD dedup-skip has carried the wrong manual forward before).
- Do not apply these until Route+ persistence is committed.
- When applied, confirm no residual "convoy map route" wording remains anywhere in the manual.
