# GroupTrack — Manual Updates Needed — 2026-07-11

Canonical manual: `app/src/main/assets/grouptrack_manual.html`
(This session's verified copy: `GroupTrack_manual_2026-07-11.html`, title "GroupTrack V2.5 Manual".)

**These edits are staged, not yet applied — Route+ persistence has NOT shipped.**
Apply them to the canonical manual only when Route+ persistence is committed and verified. Documenting an unshipped feature as live would mislead users.

---

## What already exists in the manual
The manual already documents the Route → In Progress flow (starting a route, snapping points along a trail, saving/discarding). The updates below extend that section for **persistence and recovery**, once shipped.

## Section to update: Routes → In Progress
Add, after the existing in-progress route description:

### Auto-save (behavior change)
- An in-progress route now saves automatically as you place each point. There is no "name your route" prompt when you start — the route is saved under a working name ("Auto Saved In Progress") and you name it when you finish.
- Undo removes the last point and the auto-save updates to match.

### If you leave with a route in progress
- Your in-progress route is kept. The next time you open the map, GroupTrack shows a **"Recovery file found"** prompt.
- Press **OK** to bring your route back: the map returns to your route and re-opens route editing so you can keep going, finish, or discard it.
- You will be asked to **save or discard** before doing anything else — an in-progress route can't be silently lost.

### Finishing a route
- **Save as a permanent route** — you must give it a real name (the working "Auto Saved In Progress" name is not accepted).
- **Save as work-in-progress** — keeps it in your in-progress list under a name you choose.
- **Discard** — "Delete in-progress" removes it. ("Roll back" keeps the draft and returns to your last saved state.)

## Scope note for the writer
- Applies to **both** the convoy map and the planner map (same behavior on both).
- Wording above is user-facing; keep it consistent with the manual's existing Routes section voice.

---

## Reminder (doc-process)
- Verify the canonical file identity before editing (the Windows rename + EOD dedup-skip has carried the wrong manual forward before).
- Do not apply these until Route+ persistence is committed.
