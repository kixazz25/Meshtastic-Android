# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-19
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `d75572a1f`

> **06-19 session: backlog recovery + trail-findability problem-framing (design/recovery, no code yet).** Audited the bare `[N.x]` backlog codes against AllDocs — most were DONE or stale. Reshaped the trail-selection items around the PROBLEM each solves (method evolves in discussion; problem is the durable record). Recovered: the pristine cookbook manual is the canonical base (see manual note). Retired the `section`-field spec (protects spatial design).

---

## ⭐ OUTCOME 2026-06-18 — THREE COMMITTED WINS; FIT SOLVED

FIT-selection-retention fixed and committed on both maps; convoy "?" help shipped; pixel/neon track arrows shipped. Universal search fully designed. Lead-cart rebuild recovered.

### ✅ COMMITTED
- **FIT selection retention — `35ccccc4a`** (tested). FIT emulates a manual row-select on the LIVE in-memory vars: on detail-panel dismiss with a fitted (type,id), set that type SELECTED + checked-ids to the fitted id, save, fire normal redraw. FIT=one artifact → clear all four types OFF first, then set fitted SELECTED. Fixes persistence (the `saveConvoyState` clobber) AND panel display together. Both maps (planning uses `savePlanningState()` + `webViewRef`).
- **Convoy "?" help — `60db85131`** (tested). Ported planning's "?" help to convoy; both maps load the same release-notes/manual HTML. Button at CenterEnd.
- **Track arrows pixel+neon — `d75572a1f`** (tested). `repeat:'12%'`→`repeat:80` (fixed 80px, re-spaces on zoom) + `#000000`→`#39FF14`. Both HTMLs.

### Commit chain
`009b158aa` (06-17) → `35ccccc4a` (FIT) → `60db85131` ("?" help) → `d75572a1f` (arrows = HEAD).

---

## 🎯 KNOWN REMAINING V2.5 SCOPE — FINISH THIS LIST

Per Fred 06-18: finish what is known. Must-ship:
1. **Universal search (magnifying-glass FAB)** — designed, build next.
2. **Lead-cart convoy-tracking REBUILD [2.1]** — recovered settled design (below); includes removing ALL previous track-recording methods.
3. **Documentation pass** — manual reconciliation (pristine cookbook is base), release-notes realignment, screen capture, cleanup.
4. **Trail findability** — the connected workflow below.

**Moved back (low priority, internal testing):** [8.7] Play Store attribution / About.

---

## ⛔ LEAD-CART CONVOY-TRACKING REBUILD [2.1] — recovered settled design (MUST-SHIP)

> Settled V2.5 design that fell off the checklist. Authority: `GroupTrack_LeadTrackReplacement_Spec.docx` (May 31). NOT done. Full plan: `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.

**Problem:** current lead-cart tracking is a hodgepodge of evolved rules + position projection. Unreliable — phantom carts report in on rebroadcast. Gut and restart.

**Rebuild — one lead cart, one lead track:**
1. Track ONLY the lead cart, from its broadcasts (not projection).
2. Snap-2, 100-yard radius: snap the lead's broadcast onto known trail/track geometry within 100 yd.
3. Every other cart shows at current position only — not tracked (kills phantom carts).
4. Each cart tracks its own progress; overtaking the lead's positions, it removes the lead's path from its own map and replaces with its own per-second device GPS.

**Net:** one continuous trail from the lead that improves in accuracy in the wake. **Rationale:** lead broadcasts at best every 5s (radio limit) → coarse trail ahead; each cart records 1s GPS → refined trail behind ("rear-view mirror"). Trail you drive over is always better-known than trail ahead.

**Open (confirm before building):** overtaking-cart wake replacement — unconditional, or snap-gated (off-trail cart beyond threshold doesn't overwrite)?

**Code targets to gut:** 3 parallel flows (`leadTrackSegments`/`gpsTrailSegments`/`routeTrailSegments`), live `drawTrack` path (~ConvoyScreen 345-350), `trackLeadOnly`, ConvoyEngine lead-lock/tick (`evaluateLeadLock()`, `tick()→compute()→assignLeadTail()`, `lockedLeadNodeId`, `_leadLockedFlag`; known tick-oscillation). Replacement = one growing lead polyline gated on `lockedLeadNodeId`; `pushTrackToMap` net-new. Discovery first: fresh `field_crossref_raw.txt`, trace live, 2-cart capture.

> Don't confuse this lead-cart snap-2 (lead broadcast → known trail geometry, 100yd) with ROUTE snap-2 (route drawing follows trail geometry between vertices). Different features.

> AllDocs also has Phase-C "auto-election / two-tier lead assignment" (lead identity logic, removed before V2.4, preserved in git; design `GroupTrack_LeadLock_AutoAssign_Design.md`) — relevant to lead identity within this rebuild; reconcile when building.

---

## ⛔ UNIVERSAL SEARCH ([2h.1]) — designed 06-18, build next

Design: `GroupTrack_UnifiedSearch_DESIGN_2026-06-18.md` + mockup. (Supersedes the 06-16 handoff design portion.)

- **Architecture:** self-contained shared `UnifiedSearch.kt`, param `mapContext` ("convoy"/"planning") + webView + context + `onOpenDetail(type,id)`. Lift `ResultsList`/`ArtifactResult` (maybe `SearchBlock`) out of `ConvoyArtifactsPanel.kt`. Wiring + deletion, not new search logic.
- **Flow:** magnifying-glass beacon (draggable, resets per session) → bar with 5 chips one line (Area·Track·Route·Trail·Waypoint) + text → Enter executes+closes → results list → tap result closes list → Area = setView+showSearchCenter; artifact = `ArtifactDetailPanel` (FIT). Dup names #1/#2.
- **Convoy gains Area mode.** All 5 modes both maps.
- **Engines kept:** `searchByName`→name-sequence (convoy ~1356/planning ~870); `Geocoder.getFromLocationName`→setView+showSearchCenter (planning ~365-388; convoy needs the geocode call added); `ArtifactDetailPanel`. Both HTMLs already have setView+showSearchCenter — no HTML change for search.
- **Remove launch points:** (1) convoy dead `locationSearch*` @268-270; (2) planning area-search field+geocode ~345-405 + `searchText`; (3) artifact-search box (`SearchBlock`+`ResultsList` in `ConvoyArtifactsPanel`) — keep toggles/+ROUTE/import; lift `ResultsList`.
- **Build incrementally:** UnifiedSearch.kt + convoy mount (old stays) → test → planning mount → test → remove 3 old launch points → test both. Commit each green.

---

## 🎯 TRAIL FINDABILITY — selecting the right trail among hundreds of same-named segments (recovered/clarified 06-19)

> **Framed by the PROBLEM each solves. Method evolves in discussion — the problem is the durable record.** One connected workflow, not loose ideas.

**CORE PROBLEM.** Real trail data has huge same-name density — "Jordan River Trail" alone is **314 distinct segments** (verified; each a unique geometry, NOT a dupe — dedup via `UNIQUE(geom_hash)` works correctly). A rider selecting the trail they want faces hundreds of identically-named entries and a map of indistinguishable lines, and can't reliably (a) tell segments apart, (b) reach all of them, or (c) know whether they're seeing all of them.

- **Blank/featureless startup map — riders see nothing and don't know why.** On launch / wide zoom, the artifact limit is so exhausted nothing renders — looks broken with no explanation. NEEDED: **below z11, the map must say "zoom in to see artifact info"** so an empty-looking map is self-explaining. *(Method: state-driven message off viewport zoom/count; evolving.)*

- **Silent truncation at the display cap — riders miss segments without knowing.** At cap 200, ~36% of Jordan River's 314 segments silently drop from the SEL/Edit list. NEEDED: (1) **raise cap 200→400**; (2) at the cap, message **"Maximum artifacts reached for this map area. Zoom in to ensure you have all artifacts that belong on the map."** No silent truncation. *(Settled spec exists — AllDocs ~19843, state-driven off existing viewport count, no new query; this refines to 400 + wording. Real long-term fix = paging [11.1]; 400 is the now-fix.)*

- **"Why does this trail appear so many times?" — asked at the SELECT LIST.** Same-named segments look like duplicates; riders don't trust the list. NEEDED: on the **select-list row**, surface the **unique geom-hash** as a plain "this is a unique trail segment, period" indicator. Hash is already on the row — just show it. **DECISION (06-19, supersedes AllDocs `section`-field spec ~19829-19834):** do NOT add a `section`/numbering field — it compromises the spatial data design (identity is `UNIQUE(geom_hash)`, mirrored to AWS in 2.6). Showing the existing hash answers the question with zero schema change. The "assign section at import via trigger" plan is RETIRED; the schema-cost-vs-benefit mismatch is exactly why it was never built.

- **Can't tell map lines apart — no trail name positioners on the map.** A dense viewport is a wall of indistinguishable lines. NEEDED: **trail name positioners/labels on the map** (needed even at 200). *(The one piece with real cost — RECOVERY 06-19: trail labels are NOT drawn today at all; net-new Leaflet labeling in BOTH HTMLs, on the hot draw path, known perf concern at hundreds of labels. Needs its own design pass. Other items here are bounded/low-risk.)*

- **The detail panel IS the disambiguation+selection tool — and must be the ONE universal detail function.** With 400 trails/segments in play, the user picks the right one by opening its detail then FIT. PRINCIPLE: ONE shared `ArtifactDetailPanel` (owns FIT), reached identically from every entry point — select-list row-tap, search result, map artifact-tap/popup. Not per-caller variants. The "map-popup → detail" item is just another entrance to this same panel. *(Open: replace on-map popup entirely with tap→detail, vs keep popup as launcher→detail. Decide in design.)*

- **Detail-panel content enhancements (schema-safe, bounded to `ArtifactDetailPanel`).** Surface what a planner needs on the card: **trail type via CartoCode** (color the panel + bold type footer — per-trail, honest, no trail-line redraw); **trail/segment length** (`distance_miles` may already be a stored property — verify; else derive from geometry at open); the **unique-segment hash indicator** (above). All read-only, no schema change. *(CartoCode line coloring on the MAP — recoloring trail lines by type — is a separate, heavier 2.6 question, PARKED for the 2.6 discussion. Only the detail-panel color/footer is in scope here.)*

---

## ⏳ DEFERRED — separate items, do NOT combine

- **Map-popup / artifact-tap → detail** — tap an artifact on the map → the universal `ArtifactDetailPanel` (owns FIT). JS click handler on the artifact in BOTH HTMLs + `@JavascriptInterface` bridge to open detail with (type,id), passing mapKey + webView. *(This is the "another entrance to the universal detail panel" from Trail Findability.)*
- **Shared-JS consolidation** — dedupe `setView`/`showSearchCenter` out of the two HTMLs into a shared local JS file (precedent: both include `leaflet.polylineDecorator.js`). **Blast-radius caution:** grep can't bound — `setView` grep conflates wrapper vs native `map.setView([...])` vs Kotlin `evaluateJavascript("setView(...)")`. No JS compiler net. Regen xref first, grep both languages, device-test. Not an end-of-day job.
- **Detail-extraction cleanup** — `ArtifactListPanel` still embeds its own detail for the sel/edit row-tap; route that to the shared `ArtifactDetailPanel` too. Implement `ConvoyArtifactOps` Pass-1 log stubs (rename/delete/toRoute/toTrack/upload/download/changeType/editPoints/addAlias/setTrailhead); `fit()` is the only real op today.

---

## OTHER OPEN (backlog / 2.6) — RECOVERED & TRIAGED 06-19

> Audited against Fred's recall + AllDocs 06-19. Bare `[N.x]` codes resolved to real status. Most of the old list was DONE or stale.

**Still genuinely open:**
- **[6.2] Remove leftover geojson asset + JS-injection code.** Distributed trail content was cut from 27MB to 2 trails to shrink the distro, but the geojson asset and the JS-injection code that loads it were never removed — dead weight still in the build. NEEDED: remove both. *(Also the root of the stale CartoCode legend in the manual — connected.)*
- **[3.3] Queue panel — restore upload placeholder + add upload/download activity selector.** The AWS-upload placeholder was removed; the button is Upload/Download but there's no way to say which activity. NEEDED: put the upload placeholder back, add a **selector at the top of the panel** for upload vs download. *(Queue backend — hold/resume/cancel — is done; this is the panel UI.)*
- **Map Manager Phase C — tile-transfer redesign (POST-V2.5).** Building/reserving map areas hammers public tile servers. INTENT: external-map sourcing + reviewing/batching downloads through an AWS server so areas build/reserve without (or with minimal) public-server load, via a faster transfer method (server-side bundle assembly; mbtiles/PMTiles). *(The April-1 "Map Manager — Complete Specification" in AllDocs is the SCREEN spec, partly superseded by the shipped Convoy/Planning split + Work-with-Artifacts. The AWS tile-bundle/faster-transfer piece is NOT yet a written spec in AllDocs — Fred checking Downloads. Esri dev account: 2M free tiles/mo; key config deferred until transfer method framed.)*
- **Blank trail-name in FIT's JSON row** — id correct, selection id-based, so it works; name writes `""` (trails get names, tracks don't). Cosmetic, parked.
- **[1.2] sliceLine whole-trail — VERIFY OBSOLETE.** Trail-source/type/route-capture area was rewritten; unsure this still applies. Do NOT carry as active — confirm dead (quick xref check on `sliceLine` callers) and remove, or re-scope if it surfaces.

**DONE (recovered 06-19 — removed from open):**
- [4.x] import trails from external sources — DONE.
- [10.1] BLE — DONE. The timeout fix: pause recording + disconnect device cleanly before BT times out (previously required a device power-cycle to reconnect); driving away triggers reconnect.
- Gaia/onX standalone parity — DONE (distance, speed, waypoint adding, etc.).

**Fold into Work with Artifacts (not standalone):**
- Track Display Selector (filter tabs). Track importer.

**Still backlog (unchanged):** [11.1] paging (real fix behind the artifact cap); Map Manager screen items not yet realized. *(05-07 backlog.)*

**Tree cleanup:** remove stray `.bak_*` (06-18 patches), `ConvoyScreen.kt.bak_move`, `utah_trails_stgeorge.geojson.bak`; never commit `grouptrack_spatial.db` (117MB).

**DEFERRED (later releases):** GeoPackage national-trail architecture, V3.0, paywall.

---

## ✅ DONE (removed from open)
- Bundle config (versionCode/signing) ✓
- [1.5] auto-save + terminate on map-switch (= persistence) ✓
- FIT (`35ccccc4a`) ✓ · convoy "?" help (`60db85131`) ✓ · [3.9a] arrows (`d75572a1f`) ✓
- Search → detail separation (`009b158aa`, 06-17) ✓

## ❌ CUT FROM SCOPE (Fred 06-18)
- [3.1b] Planning GPS-recenter button.
- convoy_map.html `drawTrack`/`clearMarkers` "not defined" — harmless JS load-race noise; displayed DB tracks use `loadTracks`/`trackLayer` (work); erroring `drawTrack` is the live lead trail. Not a real bug.

---

## DESIGN CONTEXT (carried forward)
- **Two draw paths BY DESIGN.** (A) `drawPersistedState` = saved/restore from JSON. (B) onViewportChanged = in-memory live vars, preserving selections across zoom/pan. New actions (FIT) update the LIVE side via the existing select mechanism.
- **Map-purpose model:** Convoy = live/location (session-only). Planning = deliberate/identity (persisted).
- **Reusability principle:** own behavior in the SHARED component; callers pass DATA not BEHAVIOR (`mapContext` routes it). convoy↔planning duplication is the pain → shared components cure it (search = shared `UnifiedSearch.kt`; FIT joins the existing select mechanism; ONE universal `ArtifactDetailPanel`).
- **Boy-scout cleanup:** clean up what you touch, only when you can see the blast radius — Kotlin (grep+compiler+xref) is bounded; JS (grep only, no compiler, silent runtime failure) is not — be conservative, device-test.
- **XREFS = blast-radius RECOVERY (not discovery).** Before a known change, consult the xrefs to recover the full dependency set (callers, linked functions, shared fields) so the edit preserves what it must and breaks nothing unaware. They're born of necessity because the codebase exceeds what's holdable in head / boundable by grep. Same tool, both directions: code change ("what will I break?") and docs ("what must I cover?" — navigation_xref drives the cookbook). The recurring failure is forgetting what they provide, not lacking them — consult first, before reaching past them.

---

## PROCESS NOTE — settled designs keep getting lost
Settled designs fall off because each EOD doc is rewritten fresh and dormant items aren't re-typed. Mitigation is two-layer: this checklist + the memory spine carry open items (append, don't drop); the doc folder holds full specs. Settled designs go on the list the same day. **Before declaring any task "doesn't exist," search the record first** (memory + conversation + AllDocs) — Fred should never get "doesn't exist" then find it in Downloads.

---

## TREE STATE
- **Committed HEAD `d75572a1f`** (arrows). Chain: `009b158aa` → `35ccccc4a` (FIT) → `60db85131` ("?" help) → `d75572a1f` (arrows).
- Parked (never git-add): `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? *.geojson.bak`, `?? *.bak_*`, `?? ConvoyScreen.kt.bak_move`, `?? grouptrack_spatial.db`. Commit only named files. Never `git add .`.

## DEVICE / BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~14–42 min)
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s 8624SBCEDF00001789 install -r -d <apk>` (Droid 1 = `8624SBCEDF00001789` field/real-GPS · Droid 2 = `24039703201775` dev)
- JSON pull: `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json` (or `planning_panel.json`)
- Logcat: `adb -s 8624SBCEDF00001789 logcat -d -s <tags> | tail -N` (`-c` clears). LIVE logcat BLOCKS — use `-d` dump.
- **LINE ENDINGS:** .kt files MIXED CRLF/LF even within one file. Patches detect newline at runtime + count==1 guard.
- Patch flow: Claude files → present_files → Fred downloads to `/c/Users/kixaz/Downloads/` → `python3 <name>.py`.
- Revert one file: `git checkout <hash> -- <file>` — run `git diff <hash> -- <file>` FIRST. NO sqlite3 on device.

## EOD DOCS — status
- Release notes → 06-18 (`grouptrack_release_notes_2026-06-18.html`): FIT, convoy "?" help, arrows.
- User manual (4-section variant) → 06-18 — SUPERSEDED; the canonical manual is the pristine cookbook below.
- This checklist → 06-19.
- Lead-cart demolition+rebuild plan → `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.
- **MANUAL — pristine cookbook is the CANONICAL BASE (recovered 06-18 PM).** Canonical = `app/src/main/assets/grouptrack_manual.html` (modified 06-07, never git-committed = untouched by the same-name overwrites that destroyed the dated copies). Full screen-by-screen cookbook: 41 screen cards covering all 40 nav destinations, Reached-from / What-you-do / Leads-to format, 3.0-vs-V2.5 marking, search box, `[screenshot to be added]` slots, feature-status appendix. Drive copy: `grouptrack_manual_PRISTINE_BASE_recovered_2026-06-18.html`. **RULE: edit THIS in place — never rebuild from a thinner version.** The 4-section "06-17/06-18" variants are the DRIFTED branch; do not merge. The navigation_xref was purpose-built to drive this cookbook — matched pair; consult the xref to keep it honest against real nav.
- **Manual reconciliation needed (next manual pass, edits-in-place):** update the **CartoCode legend** (stale since JS-injected trail sources dropped — see [6.2]); fold in features newer than 06-07 (FIT, convoy "?" help, pixel arrows, snap-2 routes, persistence) as edits to existing cards; optionally re-order rider-first; capture into the existing `[screenshot to be added]` slots.
- **Still to do (documentation pass):** the manual reconciliation above; cookbook screen-capture (Fred captures via scrcpy/adb into existing slots; Claude annotates via PIL); broader doc organize/cleanup.
