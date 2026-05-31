# GroupTrack — Documentation Index

**Entry point.** A work session reads this first to know the whole doc landscape. Every living doc listed with purpose + freshness.

_Last updated: 2026-05-31_

---

## Where docs live (the two-home model that stops drift)

- **Google Drive `GroupTrack_docs/` = the WORKING copy.** Claude can read AND write these in-session via the Drive connector. This is what gets maintained live — no re-upload, no regeneration. Survives missed days because the current version is always in Drive.
- **Git repo `docs/` = the DURABLE archive.** recommit11 commits nightly to GitHub for version history.
- **Sync:** keep the Drive folder synced to the Windows machine (Drive desktop app), so recommit11 picks up the latest and commits it. Drive is where edits happen; git is the audit trail.

Markdown (.md) is the source of truth — diffable, greppable, Claude-readable. A readable Google Doc export sits beside each for browser reading. `.docx`/PDF are on-demand exports, never the master.

**Never** keep the only copy of a checklist/handoff/spec in a chat session or Downloads — that is the loss mechanism this kills.

---

## Manual
- `grouptrack_manual.html` — Living manual. 3-section spine: **Online Model** (V3.0) · **Convoy Map** (live) · **Planning Map** (live). Canonical functions authored once; shared map functions render in both map sections. Search across all sections. In-app `?` target via `?help=<anchor>` / `?section=<sec>` / `GroupTrackManual.open()`. Help anchors: work-with-artifacts, downloads, map-overview.

## Checklists
- `v25_master_checklist.md` — THE active V2.5 spine. DONE/PARTIAL/OPEN/VERIFY/FLAW, sourced. Primary tracker.
- `v30_stub_inventory.md` — 29 SP stubs (SP01–SP29), deferred.
- `open_items_expanded.md` — deep detail behind open items.

## Handoffs (dated, append-only)
- `handoff_YYYY-MM-DD.md` — end-of-session state: shipped, git state, next agenda, reminders.

## Release Notes
- `v2.5_release_notes.md` — field-test notes (What's New + Known Issues).
- `daily_doc_update_YYYY-MM-DD.md` — daily completed-function capture → release bullets + cookbook record.

## Specs
- `lead_track_replacement.md` — replace 3-flow lead-track pipeline; removal discovery anchored to field_crossref. After routes.
- `help_system_bridge.md` — wiring the in-app `?` to the HTML manual (Kotlin↔WebView). To write.

## Cross-Reference (xref) — upload-at-session-start set
- `field_crossref_raw.txt` (authoritative, [W]/[R] + file:line), `where_used_raw.txt`, `function_universe_raw.txt`, `navigation_xref.txt`. Regenerate via recommit_docs_v11.sh.

---

## Session start (the new, low-friction version)
1. Tell Claude: "read my GroupTrack_docs in Drive." Claude pulls INDEX + checklist + latest handoff directly — no upload.
2. Paste: `git log --oneline -5` · `git status` · branch.
3. State today's goal.
Claude edits the Drive docs in place as work proceeds; recommit11 archives them to git nightly.