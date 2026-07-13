# GroupTrack — Manual Updates Needed — 2026-07-13

Canonical manual: `app/src/main/assets/grouptrack_manual.html`
(Last verified copy: title "GroupTrack V2.5 Manual", 3.37 MB, 76 embedded images, 68 screens. Annotated copy with capture markers: `grouptrack_manual_ANNOTATED_2026-07-12.html`.)

**The canonical manual is HELD to V2.6 end** per the doc workflow (add install/quick-start + staged Route+ updates + 18-screen capture sweep together at the end). This file stages every pending manual edit to fold in at that time. **Supersedes the 07-11/07-12 manual-updates notes — this is a strict superset.**

**Apply-when-shipped rule:** these edits describe Route+ persistence / recovery / naming behavior. Only fold them into the canonical manual once the corresponding features are committed AND shipping in the release build. As of 07-13 the recovery notice popup + save-name are COMMITTED (a5a33dbfa) but not yet Play-Store-shipped — do not publish manual text as live until the release goes out.

---

## What already exists in the manual
The manual already documents the Route -> In Progress flow (starting a route, snapping points along a trail, saving/discarding) on BOTH the Convoy Map and the Planning Map, each with its own Route section. The updates below extend those sections for persistence, recovery, and required naming.

---

## Section to update: Routes -> In Progress (carried from 07-12, unchanged)

### Auto-save (behavior change)
- An in-progress route now saves automatically as you place each point. There is no "name your route" prompt when you start — the route is saved under a working name ("Auto Saved In Progress") and you name it when you finish.
- Undo removes the last point and the auto-save updates to match.

### If you leave with a route in progress (UPDATED 07-13 — see recovery change below)
- Your in-progress route is kept. See the **Route recovery** section below for the current (07-13) recovery behavior, which supersedes the earlier "map returns to your route and re-opens route editing" wording — recovery is now a **notice that directs you to resume manually** (auto-resume is planned but not yet shipped).

### Finishing a route (UPDATED 07-13 — see required naming below)
- **Save as a permanent/completed route** — you must give it a real name (the working "Auto Saved In Progress" name is not accepted). Needs 2+ points.
- **Save as work-in-progress** — keeps it in your in-progress list under a name you choose.
- **Discard** — "Delete in-progress" removes it. ("Roll back" keeps the draft and returns to your last saved state.)

## Scope note for the writer
- Applies to **both** the Convoy Map and the Planning Map (same behavior on both), EXCEPT recovery, which currently lives on the **Planning Map** only (see below).
- Keep wording consistent with the manual's existing Routes section voice.

---

## NEW staged updates (2026-07-13)

### Route recovery (Planning Map) — CURRENT behavior (supersedes the 07-12 "Recovery file found -> returns to your route" wording)
- **Behavior to document:** if the app is closed unexpectedly (crash / force-close / battery) while a route is in progress, the next time you open the **Planning map** GroupTrack shows a recovery notice: *"A route was left open from your last session. To resume it, tap +ROUTE and choose In Progress."*
- **User action:** tap **OK**, then **+ROUTE -> In Progress**, and select the auto-saved route to resume it.
- The notice appears only after an uncontrolled exit — a normal route close/save does not trigger it.
- **Note for the writer:** the 07-12 capture-checklist "Recovery dialog" screen should be captured to match THIS text (notice + manual-resume instruction), on the **Planning** section. Auto-resume (map returns straight into the armed route) is planned but not yet shipped; do not document it as live until it ships.

### Saving a route — naming is now required
- **Behavior to document:** when you save a route (Save panel), a **route name is required**. The field is blank for an auto-saved (recovery) route; type a name before saving.
- **Save as completed route** — saves the finished route (needs 2+ points) under the name you enter.
- **Save as in progress** — keeps it as a named draft you can resume later.
- You cannot save with a blank name or the default "Auto Saved In Progress" name — the app prompts you to enter one.

---

## Capture checklist reconciliation (from CAPTURE_CHECKLIST_2026-07-12)
The 18-screen sweep still applies. Two items shift with today's work:
- **Route "Save route" screens (Convoy #4, Planning #8):** now include the required **name field** — recapture to show it.
- **NEW "Recovery dialog" screen:** capture the Planning-map recovery **notice** (with the "tap +ROUTE and choose In Progress" text), not an auto-recovery-into-route screen. Add to the Planning Route section.
- Remaining 16 markers unchanged; still search the annotated HTML for [CAPTURE NEEDED].

---

## Reminders (doc-process)
- Verify canonical file identity before editing (the Windows rename + EOD dedup-skip has carried the wrong manual forward before).
- Confirm size / image-count integrity after any annotation step (two near-misses corrupting the 3.37MB annotated manual were caught by those checks).
- Do not publish Route+ / recovery / naming manual text as live until the features ship in the release build.
