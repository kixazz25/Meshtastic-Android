# GroupTrack V2.5 — Living Checklist — 2026-06-10

_Branch feature/convoy-event-ride · Device = Droid 2 24039703201775, release APK · Builds are RELEASE builds (assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease)._

**Supersedes 2026-06-06 (v3).** Built directly on the 06-06 v3 spine (A–G). Today's change: the §C "NEXT" item — planning-map snap-2 + undo — is now DONE. Sections updated below; everything else carried forward from 06-06 v3 intact. Companion deep record = GroupTrack_DecisionLog_APPEND (append-only).

**Sequence (was locked 06-06):** route lifecycle ✔ → convoy-map snap-2 ✔ → ~~planning-map snap-2 + undo (NEXT)~~ **✔ DONE 06-10** → strip diagnostic logs → AAB. **The sequence is now at "strip diagnostic logs → AAB."**

---

## A. Route Lifecycle — DONE & COMMITTED (unchanged from 06-06 v3)
- COMMITTED d2c685926 — Layer 2 UI-first: adaptive Save/Discard, In-Progress picker+delete, unique-name check, hoisted saveCompleted, state resets on New.
- COMMITTED 444218ee2 — Cleanup: arm tap-to-place on New, route name REQUIRED, Save buttons reworded.
- COMMITTED Backfill 2a/2b/2c — real RouteDraftStore + SpatialDbManager.routeNameExists. Drafts JSON at /sdcard/Documents/GroupTrack/route_drafts/<name>.json. Proven on Droid 2.
- Repo was 3 commits ahead of origin (lifecycle); now MORE ahead with today's 3 C-1 commits. Push at a convenient checkpoint.

## B. Snap-2 — CONVOY MAP: DONE & PROVEN (unchanged from 06-06 v3)
- WIN — snap-2 traces route along trail/track geometry on the convoy map (ConvoyScreen.kt). Confirmed three ways (logcat tracedLen growth, on-screen, undo/repost). Fix = live draw runs snapped vertices through buildSegments → swap [lon,lat]→[lat,lon] → drawBuildLine. Both onMapTap handlers patched (544 persistent + 694 factory; fresh sessions run 694). Diagnostic logs retained (ConvoyScreen ~555/716) — strip at AAB.

## C. Snap-2 + Undo — PLANNING MAP: ✔ DONE 2026-06-10 (this was the §C "NEXT")
**ConvoyMapViewerScreen.kt = planning map. Now mirrors convoy: route traces trail geometry on every draw path. Proven on device (Utah/St.George viewport, Trails ON). Committed.**

### C-1 · Snap-2 trace on planning map — DONE
The 06-06 v3 line numbers (498/982/1040/1075) had drifted (the 06-07 `?` patch shifted them). Re-read live against the 06-10 fresh xref; real sites were:
- onMapTap live draw (~490): **already traced** from an earlier session (buildSegments + parseWktLine + [it[1],it[0]] swap). No patch needed. Carries the "S2P tracedLen" diagnostic log.
- rollback redraw (rbPts) — commit **56713ab1e** — proven (rolled-back route redraws traced along the trail).
- resume redraw (rsPts, preserves setRouteMode(true);) — commit **5630fb0b9** — proven.
- undo redraw (onUndo) — commit **6b1628f82** — proven (line stays traced; undo-to-empty clears, no crash).
- **Pattern:** each redraw onClick was SYNCHRONOUS, so withContext(IO) (suspend) had to be wrapped in `scope.launch { ... }` (val scope = rememberCoroutineScope() ~line 107). File is CRLF, webViewRef? syntax (NOT .value).

### C-2 · Undo on planning map — RESOLVED (was the "does not remove segments" mixed-signal)
Re-tested per the 06-06 fork: undo WORKS (new route → undo ✔; resume → undo ✔). The "doesn't remove segments" symptom was NOT an undo bug — it was MAP-SWITCH shared-state contamination: RouteManager is a singleton shared across both maps, so switching maps mid-build carried stale state. That folds into the auto-save-and-terminate-on-map-switch design item (Section F-parked / G). Undo handler itself is correct — do not patch it.

## D. File / build facts (carry from 06-06 v3 + 06-10 confirmations)
- LINE ENDINGS TRAP: ConvoyScreen.kt mixed; ConvoyMapViewerScreen.kt = CRLF. cat -A/grep -U/sed all LIE; only raw Python rb read is reliable. Write patch OLD/NEW with \r\n. webViewRef syntax DIFFERS: ConvoyScreen = webViewRef.value; ConvoyMapViewerScreen = webViewRef?.
- Viewport query keys: queryTrailsByViewport → trail_id,name,geometry; queryTracksByViewport → track_id,name,geometry (WKT). parseWktLine handles LINESTRING/MULTILINESTRING "lon lat".
- Commit with explicit file lists. Build ~11–21 min incremental. Recovery anchor 204405fc7. Fallback AAB versionCode 29320600 on Play Internal.
- SCHEMA (verified live 06-10, for any DB loader): trails INSERT (trail_id,name,geometry,min_lat,max_lat,min_lon,max_lon,created_at,updated_at,geom_hash), created_at==updated_at. geom_hash = SHA-256(wkt UTF-8) lowercase. WKT = "LINESTRING(lon lat,lon lat)" (lon first, comma no-space). notNamed → "Not Named".

## E. AAB milestone — now the active gate (§C is cleared)
- GATE was "after planning undo AND snap-2 trace." **Both are now DONE.** So AAB is unblocked except for the strip + lint.
- STRIP diagnostic logs first: ConvoyScreen tracedLen (~555) + S2 tracedLen (~716) + the planning "S2P tracedLen" Log.d (ConvoyMapViewerScreen onMapTap ~490).
- Then: bump versionCode (Droid builds seen to 29320598; fallback was 29320600), confirm signing, fix SpecifyForegroundServiceType in AndroidManifest, bundleGoogleRelease, docs in sync, upload.
- PRE-AAB carry-over: verify streaming-parser geometry not OVER-capturing.
- NOTE: Release is effectively HALTED until Fred says ship. AAB can be banked as a drawer build when he's ready.

## C-extra · NOT-yet-done route features (after snap, which is now done)
- OPEN — sliceLine WHOLE-TRAIL EXPLOSION: on some taps the whole underlying trail plots from the last point instead of just the A→B segment (logcat tracedLen 1808→79861 on single taps). Diagnosed: sliceLine/buildSegments trust stored segmentIndex computed at a DIFFERENT viewport → overshoots. Fix = re-project A and B onto current geometry, slice contiguous run. Device retest across pan/zoom. NOT a one-liner. **This is the meatiest remaining route bug.**
- OPEN — Armed gating (Add RED → tap PANS not places).
- OPEN — Draft-JSON in-progress lifecycle polish; Draw/Suggest methods.

## F. Parked (captured, not active — carry from 06-06 v3)
- PARK — Snap radius slider (80 m default + in-app slider). Fell back to hardcoded 30 m. Do deliberately, after snap lands (it has). 30 m starves snap when zoomed out.
- PARK — Cosmetic: empty In-Progress picker shows only "Cancel"; "Name required" hint reuses the "name is taken" message.
- PARK — One-route-per-session; plan-exit via maps-submenu elimination; graduation routeNameExists re-check; dead-code burn-down; NULL trail names → 'Not Named'.
- PARK — AUTO-SAVE-AND-TERMINATE on map-switch with route builder active (resets the shared RouteManager singleton — this is the real fix for the C-2 contamination). Own change w/ device test.
- PARK — Tuning after snap (turns cut corners / wobble — trace fix likely resolves most for free).

## G. Other open (2.5 backlog / 2.6+ — carry from 06-06 v3)
- Walk-away import 3-type toggle (Tracks/Waypoints/Routes; today bypasses wpt/route via emptyList).
- Add-core bypass: insert{Track,Waypoint,Route}ToDb bypass resolveByGeom; migrate through add-core.
- UX: KEEP/DELETE recap buttons misleading (source GPX auto-deletes before dialog).
- DB dedup P1 3339839f4 + P2 eaf8508c1 (trails only).
- Verify streaming-parse geometry not over-capturing (St George → Bar 10 point counts).
- BLE budget-device supervision timeout (hardware; device compat list).
- beginDedupSession type-scope for track-only imports.

## H. NEW 2026-06-10 — items that surfaced today

### H-1 · NH trail data (Fred relocating to NH ~6 months)
- OPEN — Add NH trails via the SOURCE-DOCUMENT path (NH GRANIT as the 8th trail_sources.json entry), NOT manual DB-load (Fred's decision). Drop-in because the importer uses one standard ArcGIS query for all 8 existing sources. BLOCKER: verify the FeatureServer /query URL + confirm field names from a BROWSER (curl blocked on Fred's machine). We already know the schema from the 90MB download (trailname/trailsys/objectid). Full story + ready-to-paste JSON: GroupTrack_NH_TrailImport_task_2026-06-10.md + TRAIL_SOURCE_NH_2026-06-10.md. Parked alternative: load_nh_trails_to_db_2026-06-10_v1.py (byte-matched manual loader).

### H-2 · Convoy `?` help button placement (from the 06-07 session)
- OPEN — convoy `?` shipped INSIDE `if (convoyState.hasLost ...)` so it only shows when a contact is lost. Move it beside the QUEUES button (ConvoyScreen.kt:1359). Planning `?` is correct/always-visible. Small patch.

### H-3 · Docs baked into builds (from 06-07)
- DONE (assets) — all-functions user manual grouptrack_manual.html (28,050 bytes) + release notes with tester upgrade-hazard callout + geojson shrunk to 3KB. Manual still has 37 "[screenshot to be added]" placeholders. Manual "Creating a Route" section needs the snap-now-live edit (GroupTrack_Manual_CreatingARoute_update_2026-06-10.md).
