# GroupTrack — Manual & Release-Notes Revision Instructions
*Written 2026-06-19. Lives alongside the Living Checklist + Handoff. Purpose: stop the manual from being lost or rebuilt-from-memory ever again.*

## THE FILES (where they live, which is canonical)

**USER MANUAL**
- **CANONICAL BASE (never overwrite):** `grouptrack_manual_PRISTINE_BASE_recovered_2026-06-18.html` in Drive GroupTrack_docs (Drive id `1ibNIPW0Nb7bcZh1gmIPAfh9ZZnuMaxPU`). Recovered from `app/src/main/assets/grouptrack_manual.html` (working-tree file, last modified 2026-06-07, never git-committed → untouched by the same-name overwrites that destroyed the older dated copies).
- **DATED WORKING COPY (edit this):** `grouptrack_manual_WORKING_2026-06-19.html` in Drive (id `1kjIUdyexqnH1Y9ZyZckRN992I4MXqaGW`) — a verbatim copy of the pristine base, carried forward 2026-06-19. **Edit the dated working copy; keep the PRISTINE_BASE untouched as the recovery point.**
- **SHIPS IN-APP as:** `app/src/main/assets/grouptrack_manual.html` (bundled in the AAB; the "?" help button opens it). The final edited manual gets written back to this path before the AAB cut.
- **DRIFTED — do NOT merge:** the 4-section "06-17/06-18" manual variants (`GroupTrack_V25_UserManual_2026-06-18.html`, `..._2026-06-18b_restructured.html`). These are a thinner drifted branch. Ignore them; do not merge them into the cookbook.

**RELEASE NOTES**
- **CURRENT:** `grouptrack_release_notes_2026-06-18.html` in Drive (id `1B_Cxb7Z06lM7_LEnm5fZZftrSTOmpVKj`).
- **DATED WORKING COPY (edit this):** `grouptrack_release_notes_WORKING_2026-06-19.html` in Drive (id `1rieti1DmQjINZBxLLIE2avpWzE6U8Q8T`) — carried forward 2026-06-19.
- **SHIPS IN-APP as:** `app/src/main/assets/grouptrack_release_notes.html` (bundled in the AAB; "?" help opens it).

## THE RULE THAT PREVENTS LOSS
1. **Never rebuild from memory.** Always start from the current dated working copy (carried forward from the prior version). If unsure which is current, the PRISTINE_BASE manual is the safe floor — it is the verified 06-07 cookbook.
2. **Edit in place; carry forward dated.** Each session: pull the latest working copy → edit surgically → save as a new dated working copy. Never overwrite a dated file with the same name (that is what destroyed the old copies). Keep PRISTINE_BASE permanently untouched.
3. **Two homes, always.** Every revision saved to BOTH Drive (GroupTrack_docs) AND downloaded by Fred to G: drive. recommit pulls into repo `docs/`. The in-app asset (`app/src/main/assets/...`) is the third home and the one that ships.
4. **The manual is a COOKBOOK, edit-in-place.** Structure = 41 screen cards, all 40 nav destinations, Reached-from / What-you-do / Leads-to format, 3.0-vs-V2.5 marking, `[screenshot to be added]` slots. The navigation_xref is its matched pair — consult it to keep the cookbook honest against real nav.

## MANUAL — what to revise (do at the END of V2.5 feature work, before the AAB cut, when screens are final)
Edit the dated working copy in place:
1. **Rewrite the "Work with Artifacts" section around ICON NAVIGATION.** It currently describes "panel bar + accordion." After the FAB work it becomes: tap the artifacts FAB (right-edge icon column: search · artifacts · help) → panel opens EXPANDED → the accordion-collapse control CLOSES it. The always-on WWA bar is gone.
2. **Add the universal search FAB** — magnifying-glass FAB on BOTH maps, 5 modes (Area · Track · Route · Trail · Waypoint), result → detail. (Replaces the old per-map search descriptions.)
3. **Add the ONE artifact detail panel + the Carto Type field** — one universal detail panel reached from search FAB, select/edit list row, and map artifact-tap. Carto Type shows the translated type text (OHV / Road-Concurrent, Hiking-Only, etc., or "Unspecified") colored by carto color / cyan.
4. **Document map artifact-tap → detail** (tap an artifact on the map opens the detail panel).
5. **Update the CartoCode legend** — currently stale (the JS-injected trail sources were dropped, [6.2]). Reconcile to the real carto color mapping.
6. **Fold in features newer than 06-07** — FIT, convoy "?" help, pixel-spaced neon track arrows, snap-2 routes, viewport persistence — as edits to the existing screen cards (not a rebuild).
7. **Keep 3.0 account/cloud screens marked "Coming in Release 3.0"** (collapsed/oriented, not removed).
8. **Screen captures last** — Claude builds a capture-companion manual (embedded mkdir + per-slot adb screencap commands; Claude owns slot filenames); Fred navigates each screen + runs each command; Fred zips + uploads; Claude inserts images into the `[screenshot to be added]` slots + annotates via PIL. Claude CANNOT run adb. Capture once, when all screens are final.

## RELEASE NOTES — what to revise
Edit the dated working copy in place. Add for V2.5:
- Universal search (both maps), one unified artifact detail panel with Carto Type, map artifact-tap → detail, Work-with-Artifacts icon navigation, track survey collection (rate + share), settable transfer concurrency (default 4 / max 6), FIT, "?" help, pixel-spaced neon track arrows, viewport persistence, snap-2 routes.
- Realign so the in-app notes match exactly what the AAB ships (if the lead-track rewrite defers to 2.6, do NOT mention it in the 2.5 notes).
- Bundle in the AAB.

## SEQUENCING
Documentation is done LAST among V2.5 work — AFTER all feature screens are final (so captures and the WWA-icon-nav rewrite are done once, correctly) and BEFORE the AAB cut (the manual + release notes ship inside the AAB). See the Living Checklist RELEASE STRATEGY section.
