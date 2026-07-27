# GroupTrack — Living Master Checklist — EOD 2026-07-11

Categories: **Completed** / **In-Progress** / **Open**. EOD entry is a strict superset of any earlier update today.

---

## COMPLETED (shipped / verified)
- Tile **segmentation** / load-balancing — shipped (commit e38e43487).
- **Pass 1** MBTiles + WebP — shipped (55ce56239 / ccea5977e).
- Tile **concurrency** speed-up — shipped (18a7054a2).
- **Track-duplication** incident — closed (4ff574ddd). Durable lessons retained: Clear-storage revokes All-Files access; grep raw data before theorizing.
- **V2.5** milestone — shipped 06-24 (c603bc3f0).
- Track services layer / maps-follow-tracks / track-tap panel — done 06-30..07-02.

### Route+ persistence — SAVE side (verified on device, not yet committed)
- Per-point draft save — 33 clean snapped vertices captured in test.
- WIP-save — byte-identical geometry on re-pull.
- routeState written into the map-state JSON.
- Auto-name "Auto Saved In Progress" + no start dialog.
- Discard "Delete in-progress" removes the draft.

---

## IN-PROGRESS
### Route+ persistence — RESTORE side (THE #1 build)
- **Status:** phase-2 build compiled green, installed on test device, **broken on device** (route-add stuck armed — every tap drops a point, toggle dead, phantom launch point). Not shippable. Not committed.
- **Root cause:** phase-2 seeded the armed flag from persisted state + guarded the follow behavior → flag stuck on. Approach being replaced.
- **New plan (converged today): recovery-file dialog on map open.**
  - Trigger: map open + recovery file exists → recovery fires.
  - Flow: dialog → OK → (after map draw completes) load route + arm route-add + position on route's first vertex at zoom 15.
  - State self-corrects (JSON rewritten every draw); resolution (save/discard) is the clearing.
  - Built as **shared components reused by convoy + planner** (extract the planner resume into a common helper).
- **Before next patch — validate:** (1) GPS-center-on-launch blast radius via logcat; (2) state-write fires on route on/off/resolve.
- **Undo when fix lands:** back out the phase-2 armed-flag seed + 3 follow-guards; keep routeState-write + auto-name.

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
- Route+ completed-summary offload happens **at commit time**, not now (task still in-flight).
