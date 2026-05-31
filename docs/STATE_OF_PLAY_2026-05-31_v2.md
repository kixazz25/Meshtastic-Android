# WORKING AGREEMENT + STATE OF PLAY — read FIRST every session (set 2026-05-31)

## THE WORKING AGREEMENT (the method — honor this, do not re-litigate)
Manual sharing at the session boundaries. NO auto-sync. Drive-as-bridge was abandoned 2026-05-31 (two-account collision; uphill PC→Claude never worked). Lower-tech on purpose, because it works.

1. **Protect investments — never reference-and-restart.** Build on existing docs/methods/decisions. Claude must NOT regenerate from scratch. If Claude starts rebuilding something already made, that is the error — stop and use the existing one.
2. **Checklist + handoff EVERY session.** User retrieves and provides them at start. Claude reads them FIRST, before proposing anything.
3. **Xref docs = scope analysis, every time.** Before proposing/touching a change, Claude works from field_crossref_raw.txt (authoritative), where_used_raw.txt, function_universe_raw.txt. No guessing scope when xref answers it.
4. **End-of-session ritual — non-negotiable.** TWO parts:
   (a) **Recap together.** At session end, user and Claude walk the NEW items together. For each new item, ensure the handoff captures ENOUGH DETAIL TO FOCUS THE PROBLEM — beyond a single sentence. Not full executable depth (that's conversation), but enough that next session the item reads as a focused problem, not a cryptic one-liner we have to reconstruct. The recap is where each new item is promoted from one-liner → focused-problem, together, while fresh. User catches what Claude under-captured; Claude catches what got decided but unwritten.
   (b) **Manual + Release Notes.** Claude MUST ASK for the current User Manual and Release Notes; user retrieves them; we update them while work is fresh. Burden is on Claude to ask. If Claude forgets, user reminds — but Claude owns asking.
5. **PRESERVE CAPTURED DETAIL — never lose ground.** Every update keeps the detail ALREADY CAPTURED in the current-state docs. Claude reads the EXISTING version before changing it, and edits surgically (add the new, correct the changed, mark the resolved) — never regenerates from memory, which silently drops prior detail. If Claude is about to produce a SHORTER version of something that was longer, that is the alarm: stop, it is a regression. Superseded items are MARKED superseded WITH THE REASON, never deleted. NOTE: this rule is about not eroding what's recorded — it is NOT a promise that each checklist item carries enough detail to execute. Executable detail is built in CONVERSATION (see Detail/Domain note below), not pre-loaded into items.

## WHERE DETAIL COMES FROM (corrected 2026-05-31)
- **Executable detail = conversation.** The detail to actually PERFORM a task is built between user and Claude when we work it. A checklist line is a pointer ("this needs doing"), not a full spec. Claude does NOT carry the burden of each item being self-sufficient.
- **BUT each item should be FOCUSED beyond a sentence** (via the end-of-day recap, rule 4a) — enough to grip the problem next session, even though full depth comes from conversation. The handoff isn't miraculous; it gets us reframing from the CORRECT place with the RIGHT reference docs, not from scratch. That's the win, for both sides.
- **Planning / roadmap / design / scope docs = USER'S DOMAIN.** These are the user's. When a BIG ISSUE is being framed, the user RETRIEVES the relevant planning/roadmap/design doc, we REFERENCE it together, and we ADJUST it in conversation. They come into play at framing time — not maintained silently by Claude.

## THE REAL ENEMY
Losing details in the heat of battle — mid-session, deep in a patch, when something gets decided/discovered and there's no time to file it. The system exists so capturing a detail in that moment is cheap and automatic (Claude writes it to the shared folder as we go) and rule 5 keeps it from eroding. Capture cheaply in the moment, preserve relentlessly, start oriented next time.

## RESPONSIBILITY SPLIT (set 2026-05-31)
- **Claude owns CURRENT STATE:** writes/updates/retrieves the current-state docs in GroupTrack_docs (checklist, handoff, release notes, manual, catalog, this file). Originate from Claude (uphill sync doesn't work). Claude keeps them current and does not erode captured detail (rule 5).
- **User owns HISTORY + PLANNING DOMAIN:** the 231-file repo archive, AND the planning/roadmap/design/scope docs. User surfaces the right one when a gap or a big-issue framing needs it; we reference and adjust together.

## SESSION START (paste/provide these)
- checklist (v25_master_checklist.md) + latest handoff + xref .txt set. Claude reads before acting.
- git log --oneline -5 · git status · branch.

## WHERE THINGS LIVE (confirmed)
- Repo `~/Meshtastic-Android/docs/` = 231 files = authoritative source. recommit11 commits nightly. Backup: docs_BACKUP_2026-05-31.
- Drive `GroupTrack_docs/` = files CLAUDE creates (readable both ways: Claude writes → appears on user G:\). Holds: this file, CATALOG.md, v25_master_checklist.md, handoff_2026-05-31.md, INDEX.md, v2.5_release_notes.md, daily_doc_update.
- Drive uphill (user PC → Claude) DOES NOT WORK. Don't rely on it.

## HOUSEKEEPING — next session
- Consolidate the STATE_OF_PLAY files in the folder into this single current one (delete the earlier ids once confirmed this one is complete). Claude owns this cleanup.

## OPEN QUESTION — DESIGN / SCOPE / ROADMAP DOCS (user's domain; revisit at framing time)
These are the user's planning docs, surfaced when a big issue is framed. Pending housekeeping when we get to them:
- Which single roadmap is current (date-confirm: Product_Roadmap_V9 vs others).
- Which consolidated design is current (ConsolidatedDesign_May17_v2 likely).
- Scope analysis = the xref docs (that IS the scope tool going forward).

## ⚠️ FIND NEXT SESSION — V3 PICKUP DOC
User believes a **Version 3 pickup doc** exists and hopes it's still accessible. Candidates (per CATALOG §7):
- `GroupTrack_V3_PickupGuide_v1.docx` ← most likely THIS one
- also: V3_CompleteImplementation_FINAL_v2, V3_StubbedProcesses_FINAL_v2, V3_Complete_Task_List, V3_Implementation_Plan, V3_ProcessSpec_v1
ACTION: user opens `~/Meshtastic-Android/docs/GroupTrack_V3_PickupGuide_v1.docx` to confirm. It IS in the repo (231-file set) — not lost.

## THE ACTUAL WORK (queued, untouched — sequenced today)
1. Cosmetic/quick-win pass: Tier 1 (5 cosmetic, one patch) + Tier 2 (import/track-source cluster) + z12 separate. Hold `!!`/safe-call tidy separate.
2. Dupe-creation roadblocks: import (name,geometry) existence-check + post-import dedupe pass + DB UNIQUE constraint + source_id + null-naming.
3. Route creation: point-to-point SNAP-2 (trails+tracks). Tester-chosen; do NOT revert to freehand.

## TODAY'S HONEST RESULT
Zero code changes. But: sequenced all the work, built CATALOG.md (index of every doc), established the working agreement above (5 rules incl. detail-preservation + end-of-day together-recap), set the responsibility split, clarified detail comes from conversation while planning/roadmap docs are the user's domain, corrected the V2.5 Release Notes (3 new functions in, fixed known-issue retired), and proved the auto-sync bridge is dead. The scaffolding that makes next sessions fast.