# GroupTrack — Screenshot Recapture Checklist (pre-release sweep)
_2026-07-11 — capture ONCE before release, after Route+ and open download items are settled._

Markers are embedded in `grouptrack_manual_ANNOTATED_2026-07-11.html` — search the file for `[CAPTURE NEEDED]` (yellow highlighted blocks) to jump to each spot. **18 screens flagged.**

The annotated HTML is byte-identical to the canonical manual except for the added markers — all 76 embedded images and all 68 screens are intact. When you do the sweep, recapture into the canonical file, then delete the marker blocks.

---

## RECAPTURE (behavior changed — 12 screens)

### Route+ persistence — Convoy Map (4)
1. **+ Route — start a route** — the "name your route" dialog is REMOVED; route now auto-names to "Auto Saved In Progress" silently.
2. **Building the route** — per-point auto-save is now active.
3. **Extending & saving the route** — persistence behavior changed.
4. **Save route** — save / discard / recovery options changed.

### Route+ persistence — Planning Map (4)
5. **+ Route — start a route (Planning)** — name dialog removed, auto-names silently.
6. **Building the route (Planning)** — per-point auto-save active.
7. **Extending & saving the route (Planning)** — persistence changed.
8. **Save route (Planning)** — save / discard / recovery changed.

### Download pipeline (still open — capture after it settles) (2)
9. **Download Queues (Convoy)** — download pipeline work still open.
10. **Download Queues (Planning)** — download pipeline work still open.

---

## VERIFY / MAYBE RECAPTURE (confirm against current app — 6 screens)
_Flagged generously per "capture 6-8 and only change 5, no harm." Glance at each; recapture only if it no longer matches._

11. **Tap-artifact popup (Convoy)** — confirm track-tap detail panel still matches.
12. **Tap-artifact popup (Planning)** — confirm track-tap detail panel still matches.
13. **Downloads panel** — confirm vs current download pipeline.
14. **Downloads → Map Controls** — confirm vs current pipeline.
15. **Downloads → Select Tiles / Artifacts** — confirm vs current pipeline.
16. **Downloads → Draw Area** — confirm vs current pipeline.
17. **Downloads → drawing & starting the download** — confirm vs current pipeline.
18. **Downloads → Show Downloads** — confirm vs current pipeline.

---

## NEW screen needed (does not exist in the manual yet — 1)
- **Recovery dialog** — "Recovery file found — press OK to recover your route." This appears on map open when an in-progress route exists. No current screen; add it to BOTH the Convoy and Planning Route sections when Route+ ships. (Not marked in the HTML because there's no existing screen to anchor to.)

---

## Notes
- The two-map structure is confirmed present and correct in the canonical manual (Convoy Map + Planning Map, each with its own Route section; Planning also has Import + Downloads).
- Route+ content itself is NOT written into the manual yet — feature not shipped. These are capture markers only; write the flow text + capture at the pre-release sweep.
- Canonical file identity verified this session: title "GroupTrack V2.5 Manual", 3.37 MB, 76 embedded images, 68 screens.
