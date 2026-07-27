# GroupTrack — Living Master Checklist — EOD 2026-07-12

Categories: **Completed** / **In-Progress** / **Open**. EOD entry is a strict superset of any earlier update today and of 2026-07-11.

**⭐ MAJOR DIRECTION CHANGE TODAY:** convoy map **FROZEN**; Route+ moved to **planner-only**; three days of uncommitted convoy viewport work **rolled back**. Nothing shipped — rollback-and-replan session. Build green on the rolled-back baseline + kept planner Route+.

---

## COMPLETED (shipped / verified)
- Tile **segmentation** / load-balancing — shipped (commit e38e43487).
- **Pass 1** MBTiles + WebP — shipped (55ce56239 / ccea5977e).
- Tile **concurrency** speed-up — shipped (18a7054a2).
- **Track-duplication** incident — closed (4ff574ddd). Durable lessons: Clear-storage revokes All-Files access; grep raw data before theorizing.
- **V2.5** milestone — shipped 06-24 (c603bc3f0).
- Track services layer / maps-follow-tracks / track-tap panel — done 06-30..07-02.

### Route+ persistence — SAVE side (verified on device, not yet committed)
- Per-point draft save — 33 clean snapped vertices captured in test.
- WIP-save — byte-identical geometry on re-pull.
- routeState written into the map-state JSON.
- Auto-name "Auto Saved In Progress" + no start dialog.
- Discard "Delete in-progress" removes the draft.

### 07-12 rollback (clean return to known-good)
- Reverted `convoy_map.html` + `ConvoyScreen.kt` to 07-11 (`git checkout`). All convoy viewport work was uncommitted; rollback clean.
- Kept working planner Route+ + shared deps (`ConvoyMapViewerScreen.kt`, `MapStateStore.kt`, `RouteDraftStore.kt`) — still uncommitted.
- `assembleGoogleRelease` — **BUILD SUCCESSFUL** on this baseline.

---

## IN-PROGRESS
### Route+ persistence — RESTORE side (THE #1 build) — NOW PLANNER-ONLY
- **Status:** convoy Route+/restore work ABANDONED and rolled back. Route+ lives on the **planner** only. The planner already has the SAVE machinery (auto-name, per-point save, RouteDraftStore).
- **Recovery model (converged 07-12): 3-way resolution forced at launch-detection.**
  - Planner-only source → no cross-map ambiguity; single writer/reader.
  - On launch, if auto-recovery JSON exists → dialog "auto-recovery route detected" → resolve one of: **(1) save as in-progress**, **(2) save as completed** (real name required), **(3) discard**.
  - Same resolution set as the exit-gates model; only the trigger differs (launch vs exit). Confirm exit-gates vs dialog-on-load at next session (they were alternatives).
- **Next-session checklist (planner unless noted):**
  1. Remove Route+ from the **convoy artifact panel** (the only convoy touch — a removal of an entry point, respects the freeze).
  2. Planner New Route drops the name prompt → creates the auto-recovery JSON.
  3. Planner Save-in-progress renames the auto-recovery JSON.
  4. Planner checks auto-recovery JSON existence on load.
  5. Planner: if found → dialog → 3-way resolution.
  6. **Fix planner padding** (cumulative bbox-padding-on-restore) — ⚠ trace SHARED-vs-separate first; if shared with convoy, needs explicit sign-off (would touch frozen convoy).
  7. **Fix planner draw yes/no toggle** (point-registration guard; taps register only when draw ON; launch = draw OFF). Route+-only → planner-scoped, no convoy risk.

---

## ⛔ FROZEN — CONVOY MAP (do not touch)
- **Decision 07-12:** convoy map is frozen. No further convoy map edits. The layered process (Kotlin ↔ JS bridge ↔ Leaflet ↔ self-saving setView ↔ shared MapStateStore) has no documentation and no coherent mental model; 3 days of changes rippled unpredictably.
- **Known pre-existing bug (NOT fixing):** convoy reopen = cumulative zoom-out (trail shrinks / map area grows). Restore pads the saved bbox and re-saves the padded frame; each reopen pads again. Confirmed on the 07-11 baseline → pre-existing, not introduced by the recent work.
- **Root cause on record:** `convoy_map.html:163` `setView()` calls `reportViewport()` → every programmatic move re-saves → restore re-saves itself; the Kotlin guard can't catch a JS-origin save. z18 = maxNativeZoom clamp caught in the loop.
- **Prerequisite for ANY convoy change:** document the layered process first. Real fix if ever revived: silent setView for restore + don't re-pad an already-padded bbox.

---

## OPEN (not started / backlog)
- In-progress picker not listing a populated draft — inspect list query.
- loadQueue **boot-loop immunity** (poison queue file boot-loops the app; top tile-storage priority).
- saveQueue atomic-write hardening.
- Clear-area / delete-area (bbox-surgical; gated on queue hardening).
- The three no-radio regressions.
- Lead-cart re-engineer; tuning rig.
- git push blocked by 3 large .db in history (needs filter-repo / BFG).
- Esri tile-cost 4-phase strategy (hinges on one Esri sales conversation).
- V2.6 release delayed ~4 weeks; scope expanded to full V2.6-complete.

---

## Notes
- Canonical manual: `app/src/main/assets/grouptrack_manual.html` — always verify it's the right file before EOD (Windows rename + dedup-skip has carried the wrong manual forward before).
- Route+ completed-summary offload happens **at commit time**, not now (task still in-flight, now planner-only).
- The kept planner Route+ + shared-store changes are **still uncommitted** — consider banking this clean baseline as a commit next session.
