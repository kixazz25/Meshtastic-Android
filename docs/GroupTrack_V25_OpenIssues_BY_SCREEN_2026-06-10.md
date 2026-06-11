# GroupTrack V2.5 — Open Issues organized BY SCREEN / MENU — 2026-06-10

_Same complete item set as the consolidated checklist, re-sorted by WHERE each issue lives in the app — so when you're on a screen you see everything that touches it together. Mirrors the manual's spine: Planning Map · Convoy Map · Radio-Write Menu · Work with Artifacts · plus App-Wide / Build-Gate / Off-Screen buckets for items not tied to one screen._
_Each item keeps its consolidated-doc ID in [brackets] so the two docs cross-reference. Status: OPEN / PARTIAL / VERIFY / DONE / DEFERRED._

---

# 1 · PLANNING MAP  (ConvoyMapViewerScreen.kt)

## Map display / viewport
- **OPEN — Persist bbox + shown data, refresh on return** [3.1]. Returning to the Planning Map or switching maps recomputes/blanks instead of restoring the last viewport + what was drawn. Persist the bounding box together with the represented data; restore before the first query. Both HTML maps. (Headline cleanup item. Verify MapStateStore/MapSnapshot fields live.)
- **OPEN — Planning Map blank on return from trail-source screen** [3.6]. Comes back blank after the trail source screen. Likely the same bbox-restore gap.
- **OPEN — z12 hide-features** [3.7]. Min display zoom = 12 for all four types so it doesn't draw everything when zoomed out.
- **OPEN — SpatialDisplayManager consolidation** [3.2]. Planning's processArtifact is the GOOD copy; convoy's inline copies should collapse into it (drift-prevention). Planning side: confirm it stays the canonical path.
- **VERIFY — Planning Map entry modes (SP11)** [11.5]. Planning runs; verify planning/import/trailhead entry modes wired.

## Building a route (Planning Map)
- **DONE — Snap-2 trace on all draw paths** [1.1]. Live/rollback/resume/undo all trace along the trail (C-1: 56713ab1e, 5630fb0b9, 6b1628f82).
- **OPEN — sliceLine whole-trail explosion** [1.2]. Some taps plot the entire trail (~78k chars) instead of the A→B segment; stored segmentIndex computed at a different viewport overshoots. Re-project A/B onto current geometry, slice the run. Shared with convoy. Meatiest route bug.
- **OPEN — Armed gating (Add RED → pan, not place)** [1.3].
- **PARTIAL — Draft in-progress lifecycle** [1.4]. Save-in-progress / resume / graduate / discard-rollback; resume+rollback redraw now trace; graduation routeNameExists re-check still open.
- **OPEN — Auto-save-and-terminate on map switch** [1.5]. Route builder open + switch maps → save AND kill app (resets shared RouteManager singleton; the real fix for the cross-map contamination).
- **PARKED — Snap radius slider (80 m + slider)** [1.6]. Hardcoded 30 m starves snap when zoomed out.
- **OPEN — Route cosmetics** [1.7]. Empty In-Progress picker → "No in-progress routes yet"; "Name required" wrongly says "name is taken".
- **OPEN — One-route-per-session** [1.8].
- **VERIFY — +ROUTE nav wiring on artifacts panel** [1.9].
- **OPEN — Draw / Suggest build methods** [1.11] (placeholders).
- **OPEN — Route magenta z-order under trails/tracks** [1.12].

## Importing trails / tracks (Planning Map)
- **OPEN — Walk-away import 3-type toggle** [4.1]. Tracks/Waypoints/Routes selector; today tracks-only via emptyList bypass. Doubles as a test harness.
- **OPEN — Import waypoints from GPX** [4.2]; **Import routes from GPX** [4.3].
- **OPEN — GPX/KML open-with handler not executing** [4.4].
- **OPEN/VERIFY — Large GPX >32MB backtracking** [4.5]; **streaming geometry over-capture verify** [4.6].
- **OPEN — KEEP/DELETE recap misleading (source GPX auto-deletes first)** [4.7].
- **OPEN — Remove post-import Y/N prompt / old 'Work with Tracks' / node persistence** [4.8].
- **OPEN — trailSourceCount hardcoded 0** [4.9]; **area-import API hangs (timeout/error)** [4.10]; **trail-type filtering on ArcGIS query** [4.11].
- **VERIFY — Import sample data all 3 types** [4.12].
- **OPEN — Track import move into Map Viewer settings** [4.14].

---

# 2 · CONVOY MAP  (ConvoyScreen.kt)

## Map display / viewport
- **OPEN — Persist bbox + shown data, refresh** [3.1] (same as Planning; convoy side).
- **OPEN — SpatialDisplayManager: convoy's 3 inline copies → one processArtifact** [3.2]. Convoy interface #1 (~476/494) + #2 (~647/622): historically update without show; #1 routesRaw filter bug; #2 no DS_SELECTED filter. May-31 mirrored the filter but kept copies. Collapse + inject shared JS. Confirm show*() now present everywhere.
- **OPEN — SELECTED clear-on-leave alignment (convoy onSetState)** [3.10].
- **OPEN — z12 hide-features** [3.7] (convoy side).

## Live tracking / lead track
- **OPEN — Lead-track redesign: 3 flows → 1 writer** [2.1]. Replace routeTrailSegments + gpsTrailSegments + lead-only filter (per-node colored) with a single lead-position polyline + drawTrack, gated on lockedLeadNodeId; pushTrackToMap is net-new. Removes the multi-writer phantom-line class of bug on switchbacks. Discovery first: anchor removal targets in field_crossref, trace live, 2-cart capture. (Spec: LeadTrackReplacement, May 31.)
- **OPEN — Lead-track smoothing (snap broadcast points to trail)** [2.2]. Decide V2.5 vs 2.6 (needs field testing). Keep "every segment draws, always."

## Waypoints (Convoy Map)
- **OPEN — Long-press waypoint drop doesn't fire on convoy** [3.4]. Whole pipeline exists (convoy_map.html:286 contextmenu → onMapLongPress → pendingWaypoint → dialog → insertWaypoint) but no result. Diagnose event interception. Must fire only on empty map, not node markers [also master §F].

## Download QUEUES (Convoy Map)
- **OPEN — Convoy QUEUES button DEAD** [3.3]. Port planning's working showDownloadPanel → ConvoyDownloadPanel; convoy's queuesOpen → ConvoyQueuesPanel is the dead/lesser path. Resolve tap collision (drop custom drag → .clickable). Don't cover NET/LOCAL; watch double-accordion.

## Help on Convoy Map
- **OPEN — Convoy `?` misplaced** [3.5]. Shipped inside if(convoyState.hasLost) so only shows on contact-lost. Move beside QUEUES (ConvoyScreen.kt:1359), always-visible. Planning `?` is fine.

## Building a route on the Convoy Map
- **DONE — Convoy snap-2 trace** [1.1] (sites 544 + 694).
- Shared route bugs apply here too: **sliceLine explosion** [1.2], **armed gating** [1.3], **auto-save-on-switch** [1.5], **radius** [1.6], **z-order** [1.12] — same code, both maps.

## Convoy markers / lines (cosmetic/display)
- **OPEN — Waypoint marker shape decision** [3.8] (triangle vs round pin).
- **OPEN — Direction arrows on track/trail lines** [3.9].

---

# 3 · CONVOY RADIO-WRITE MENU  (Apply / Archive / Restore)
- No open ISSUES recorded against this menu for V2.5 (Apply Convoy Config / Archive / Restore are COMMITTED and working per the manual + handoffs). Listed so the screen isn't a blind spot during the walk-through. (If field testing surfaces a radio-write defect, it lands here.)

---

# 4 · WORK WITH ARTIFACTS  (panel, both maps)

## Display toggles (ON / OFF / SELECTED)
- **OPEN — SpatialDisplayManager one-process** [3.2] (this panel drives the toggles; same consolidation).
- **OPEN — Settings filter table (CartoCode / motorized / type)** [11.3].

## Select / Edit list (per type)
- **OPEN — Artifact list caps at 200 (paging)** [11.1].
- **OPEN — Trail/Route DETAIL via SELECT/Edit list (not map-tap)** [11.2].

## Routes display + Creating a Route (early access)
- Route creation items mirror the Planning/Convoy build lists [1.x]. Manual "Creating a Route" section needs the snap-now-live edit (GroupTrack_Manual_CreatingARoute_update_2026-06-10.md).
- **OPEN — Route maintenance screen (rename / edit geometry / delete)** [1.10].

## Track / Trail / Waypoint maintenance
- **VERIFY — Track maintenance screen on device** [11.4].
- **OPEN — Trail maintenance (title click)** [11.4].
- **OPEN/VERIFY — Waypoint maintenance dedicated screen** [11.4] (rename/delete/changeType exist).
- **OPEN — Track Display Selector: All/Saved/In-Progress tabs + rename in-progress** [4.13] (UI only; backend ready).
- **VERIFY — Created waypoints survive force-stop/reopen** [7.8].

---

# 5 · APP-WIDE / UNDER-THE-HOOD  (not one screen)

## Database / dedup (every insert path)
- **OPEN — Add-core bypass: route insertTrack/Waypoint/Route through resolveByGeom** [5.1]. Only trails funnel through the core; the other three do inline INSERT OR IGNORE → no alias-on-rename; snap-2 routes won't dedup until migrated.
- **VERIFY — geom_hash normalization (round coords before hashing)** [5.2].
- **OPEN — NULL names → 'Not Named'/'Unnamed @lat,lon' consistently** [5.3].
- **OPEN — source_id column on trails** [5.4].
- **OPEN — init() vs applyMigrationIfNeeded schema_version conflict** [5.5].
- **OPEN — beginDedupSession type-scope (perf)** [5.6].
- **VERIFY — regenerate-not-migrate hardening + golden-v2 loop; keep tester "update, don't uninstall" callout** [5.7].
- **OPEN — AWS MySQL structural mirror of v3 schema (sync correctness)** [5.8].

## Cleanup / dead-code (codebase-wide, verify-first)
- **METHOD — remove link AND function together, reversibly, area by area** [6.1].
- **OPEN — geojson functional removal: rewire ConvoyScreen.kt:1706 to DB-viewport, then git rm asset** [6.2].
- **OPEN — MAP SETTINGS submenu quarantine** [6.3]; **METHOD_SELECT / B1_DRAW_AREA remnants** [6.4]; **scanDownloadsForGpx / showImportList orphans** [6.5]; **duplicate AlertDialog import** [6.6]; **!!/safe-call tidy** [6.7]; **2.5→2.6 quarantine inventory** [6.8]; **GroupTrack-maps-submenu elimination** [6.9].

## Standalone / solo-rider polish
- **OPEN** — 1-sec GPS option [7.1]; odometer [7.2]; speed warmup [7.3]; track stats summary [7.4]; track survey on stop [7.5]; solo onboarding [7.6]; standalone marketing copy [7.7].

## Downstream / ride wiring
- **OPEN — SP13 Route→ride-creation wiring** [11.6]; **SP14 Trailhead selection integration** [11.7].

---

# 6 · BUILD / RELEASE GATE  (before AAB + Play submit)
- **OPEN — Strip diagnostic logs** [8.4] (ConvoyScreen ~555/716 + planning S2P ~490).
- **OPEN — SpecifyForegroundServiceType lint** [8.5].
- **OPEN — versionCode bump + signing confirm** [8.6].
- **OPEN — ANR #2 osmdroid tile-cache scan (launch-blocking)** [8.1]; **ANR #1 storage-permission startup** [8.2]; **VERIFY ANR Type 2** [8.3].
- **OPEN — About/Attribution screen** [8.7]; **lintVital ServiceKeepAlive** [8.8].
- **OPEN — First-launch Release-Notes acknowledgment gate** [8.9].

---

# 7 · DOCS / WEBSITE  (finish-the-product)
- **OPEN — Cookbook manual with device screenshots, published online** [9.1] (screenshot AFTER screens settle; edit 06-05 manual in place).
- **OPEN — Release notes finalized + online** [9.2].
- **OPEN — Website V2.5 deploy (index.html + notes + PDFs; retire 'selections carry' known-issue)** [9.3].
- **OPEN — In-app `?` → HTML manual bridge** [9.4]; **first-launch notes / online-manual open-item** [9.5].
- **OPEN — Decision Log: append 06-07 + 06-10 blocks** [9.6].

---

# 8 · HARDWARE / FIELD  (not code)
- **OPEN — BLE budget-device supervision timeout → tester compatibility list** [10.1].

---

# DEFERRED (NOT V2.5)
- National Trail Model / GeoPackage architecture · V3.0 Stub Inventory SP01–SP29 · Map Manager Phase C · V3 subscription/paywall/ride-engine/AWS-collective. (Captured, not serviced for 2.5.)

---

_Cross-references the consolidated doc (GroupTrack_V25_LivingChecklist_CONSOLIDATED_2026-06-10.md) via the [bracketed] IDs. Same items, two views: that doc groups by subsystem; this one by screen/menu so a walk-through sees each screen's issues together. The Radio-Write menu (Section 3) currently carries no open issues — kept visible so it's not a walk-through blind spot._
