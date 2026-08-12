# GroupTrack Manual — Updates Needed (as of 2026-07-09)
**Canonical manual:** `app/src/main/assets/grouptrack_manual.html` (the in-app "?" cookbook). This note lists what the manual needs; it does NOT regenerate the manual (per doc-process: verify the canonical manual, never blindly regenerate — a Windows rename + EOD dedup-skip has carried the wrong manual forward before).

> These are pending edits to fold into the manual when the corresponding features are field-verified. Nothing here is urgent for tomorrow's priority (load-balancing build) — it's the running list so manual updates aren't lost.

## Updates to make (map download section)
1. **Faster downloads (2.6b).** Add a note that map-area downloads now fetch tiles in parallel and are several times faster than earlier builds; large multi-source areas complete in roughly an hour+ rather than many hours. Mention the app retries tiles on temporary server errors.
2. **Full-detail topo.** TOPO/TOPO+ now download their full available zoom (the old internal cap is removed). Keep the existing note that USGS TOPO+ has no imagery above ~z15 (source limitation).
3. **Download requirement reminder** (already emphasized in Quick Start) — off-grid use REQUIRES downloading map areas before losing signal. Ensure the manual's download walkthrough keeps this prominent.

## Updates PENDING feature completion (do NOT add yet — features not built)
- **Even area-segmentation load balancing** — no user-facing manual change needed (it's internal), but if download-time guidance exists, update expected times once the balanced build ships.
- **Clear/delete a downloaded area** — once wired, document how to remove a single downloaded map area (tied to the "replace" toggle in the download-confirm panel).
- **Compression-quality setting** — if the runtime WebP-quality dial ships, document it in map settings.

## Known-issue notes to keep current
- **TOPO+ blank above ~z15** — USGS source limitation (already in release notes + should be in manual's map-source section).
- **SAT labels appearing on topo** — if not fixed before the next manual publish, add a known-issue line; remove once the render-side fix lands.

## Verification reminder (doc-process)
Before any manual publish: confirm `app/src/main/assets/grouptrack_manual.html` is the LATEST (not a stale carried-forward copy — the rename/dedup failure mode). The manual is an in-app ASSET, a separate track from the living master.
