# GroupTrack — Handoff / End-of-day recap — 2026-06-11

_Note-to-future-Claude. Read this + `GroupTrack_V25_LivingChecklist_CONSOLIDATED_2026-06-11.md` first next session. Project runs out of Fred's machine (`~/Meshtastic-Android`, branch `feature/convoy-event-ride`, package `com.grouptrack.android`, RELEASE builds). The container does NOT have the repo — Claude gives commands, Fred runs them one at a time and pastes results. The CURRENT doc baseline is this 2026-06-11 set (checklist CONSOLIDATED 06-11 + manual 06-11 + release notes 06-11). Build on Fred's upload, NOT the Drive connector (which lags at 06-04 and can't see the newer docs)._

## FINISHED + COMMITTED today

- **Route-draft picker refresh bug — FIXED + COMMITTED + DEVICE-APPROVED (`03c9e1a56`).** The In-Progress route picker showed a stale "ghost" list (every in-progress route ever created), Delete did nothing, and `route_drafts/` was empty on device yet the picker still showed items. Root cause was a **Compose refresh bug**, not a delete/path bug: `ConvoyScreen` line 205 `emulatedDrafts` was a bare `val` that read the disk once and never re-read; Save (@~1551) and Discard (@~1578) mutated draft files without ticking the refresh key. Fix (3 edits): line 205 → `remember(draftListTick) { RouteDraftStore.listDrafts().map { it.name } }`; Save + Discard each `draftListTick++` (now 3 tick sites — save, discard, picker-delete). Built clean (12m03s), device-approved. (`deleteDraft`/`listDrafts`/`fileFor` were all correct — the empty dir proved delete worked.)

- **V2.5 cleanup batch — COMMITTED + DEVICE-APPROVED (`bcd5f8e31`, 2 files).** Three checklist items:
  - **[8.5]/[8.8]** `android:foregroundServiceType` (connectedDevice) added to SystemForegroundService in AndroidManifest — clears the lint Error-gate (one fix, both items).
  - **[1.7]** route picker: "No in-progress routes yet" empty-state + distinct blank-name vs taken-name hint.
  - **[3.5]** removed the leftover dead QUEUES drag vars (QUEUES was already locked top-right from 06-03).

- **[3.8] Waypoint marker shape — DONE + COMMITTED + DEVICE-CONFIRMED (2 map HTMLs).** Round circles → **teardrop pins**, one shape for all types, so the point marks the exact location. Edit is in the waypoint `L.divIcon` in both `convoy_map.html` + `grouptrack_map.html` (`border-radius:50% 50% 50% 0` + `rotate(-45deg)`, symbol wrapped in a counter-rotated `<span>` to stay upright, `iconAnchor` moved to the bottom tip `[13,26]`). Per-type color/symbol unchanged.

- **[3.9] Track direction arrows — DONE + COMMITTED (3 files) · arrows have a known redraw-timing follow-up.** Direction-of-travel arrows on the **displayed DB tracks** (`trackLayer`), a diagnostic for the multi-track question (self-crossing loop → continuous one-way arrows = one track; conflicting arrows at an overlap = stacked tracks). The `leaflet-polylineDecorator` plugin is **vendored locally** at `app/src/main/assets/leaflet.polylineDecorator.js` (the CDN copy silently failed to load at runtime — `L.Symbol` undefined — so it's bundled; right call for an offline field app). It decorates `trackLayer.getLayers()` (the plugin rejects an `L.geoJSON` group directly), guarded so a missing plugin can never break track display. **Tracks display reliably.**
  - **KNOWN FOLLOW-UP (~20 min next session):** the decorator only redraws on the map's `moveend`, so on first toggle the arrows render late and/or only **one** arrow shows until a pan/zoom nudges the map. Fix by forcing a redraw in `showTracks` after `trackArrows.addTo(map)` — e.g. `map.fire('moveend')` or call the decorator's redraw — so the full repeating series draws instantly. If one-arrow persists after that, secondary suspect is MULTILINESTRING track geometry (ties to [3.7]).
  - Fred's call on the arrow timing: **"no harm no foul, leave it in place, come back and debug further later."** Committed as-is.

- **Also reported complete by Fred today (commit hashes not captured — verify in `git log` next session):** **[3.4]** convoy waypoint-drop now fires · **[4.6]** track-import over-capture verified · **[8.1]** osmdroid tile-cache-scan ANR · **[8.2]** storage-permission startup ANR · **[8.6]** versionCode mechanism. **[8.1] + [8.2] were the two launch-blocking ANRs gating the AAB — both now cleared, a major release unblock.**

## WHERE WE BEGIN TOMORROW

**Next focus = [2h] — Artifact-detail panel + Search.** This is the release-critical remaining UI work; the layout is already approved (mockups, 06-11). Both cosmetic display items ([3.8] + [3.9]) are now done, so the cosmetic queue is clear.

1. **FIRST MOVE (gating step): verify the alias/property data layer actually exists in the schema today** before building any UI on top of it. The detail card depends on the alias/full-data layer (Goal 2 / dedup 2a–2g). If it's there → build. If not → that surfaces the dependency and we shape it first.
2. **Build target** (one composable, 4 type-configs): LEFT panel = Search (type selector Trail/Track/Waypoint/Route + name field + button) above the OFF/ALL/SEL-EDIT row above the results list; result rows clickable → open the detail card. RIGHT panel = the detail card as **two columns**: left rail = the **function list** (enumerate ALL existing functions LIVE from real code at impl time — rename, delete, show/add alias, fit-to-display, plus per-type: Track to-route/survey, Waypoint edit, Route set-trailhead), right = content (type badge + display_name, alias accordion with star=preferred + agency/user/aws chips, full-data card).
3. **ISOLATE + FIT-TO-SCREEN = placeholder/stub now** (Fred's scoping) — it's the most expensive part and overlaps [3.1]/[3.7]; wire a minimal entry point so Search + results + detail content ship unblocked, build real fit-to-extent later on [3.1]'s viewport mechanism.
4. Spec refs: AllDocs ~17499-17517 + 19671-19735.

**Optional quick win if Fred wants it first:** the [3.9] arrow redraw fix (~20 min) — force the redraw in `showTracks`, rebuild, confirm the full repeating arrow series appears instantly on toggle.

## STILL OPEN — pre-AAB gates (after the functional work)
- **[8.7]** About / Attribution screen (GPL / Leaflet / Esri) for Play compliance.
- **[8.9]** First-launch Release-Notes acknowledgment gate.
- Then: strip-logs ✅ / lint ✅ / versionCode ✅ → `bundleGoogleRelease -Pandroid.injected.version.code=NNNNN` (N > Play-live AND > 29320600) → confirm signing (Play vs local APK use different keys). **Release HALTED until Fred says ship.**

## TREE STATE
- HEAD line today: `03c9e1a56` (route-draft fix) → `bcd5f8e31` (cleanup batch), then today's **[3.9]** commit (3 files: 2 map HTMLs + `leaflet.polylineDecorator.js`) and **[3.8]** commit (2 map HTMLs) on top — **grab the exact hashes from `git log` next session** (not captured tonight). The 3.4/4.6/8.1/8.2 "reported complete" commits also need their hashes confirmed in the log.
- Working tree also carries PARKED weekend state — leave it: `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? utah_trails_stgeorge.geojson.bak` (NEVER git-add the `.bak`), `D docs/.tmp.driveupload/10630`. **Commit only explicitly-named files, never `git add .`.**

## TERMINAL / WORKFLOW NOTES (carried)
- Git-Bash replays a stale command prefix onto the next line and mangles literal `!!` → re-type fresh; verify read-only.
- Map HTML assets are **CRLF** → multi-line `python3 -c` anchors ABORT (count 0); use **single-line anchors** (each line unique, CRLF-agnostic). The two map files differ in **script-tag style** (convoy inline `</script>`, planning split-line) — eyeball `<script>`/`</script>` balance before a long asset build.
- **Laptop `curl` fails TLS (exit 35)** — fetch files via browser download or `python3 urllib`, then `cp ~/Downloads/<file> <dest>`. Claude-generated files: deliver via the file panel → Fred downloads → `cp` into place.
- Build ~10–34 min: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`; APK at `app/build/outputs/apk/google/release/app-google-release.apk`; install `adb -s 24039703201775 install -r -d <apk>` (Droid 2 = dev/test `24039703201775`, Droid 1 = field `8624SBCEDF00001789`). No `sqlite3` on device; `pm clear`/uninstall fail on release (reset = reboot + adb kill/start-server).

## DOC HYGIENE
- This EOD set (checklist CONSOLIDATED 06-11, manual 06-11, release notes 06-11) is produced as full updated files from the 06-10 baseline Fred uploaded. Decision Log still owes dated append blocks for the 06-07, 06-10, and 06-11 sessions (append-only) — batch item [9.6].
- Manual now reflects: teardrop waypoint pins (was "being finalized"), track direction arrows, and the snap-to-trail-is-live edits (the 06-10 drafts, applied in place). Release notes carry a "Latest in this build (June 11)" block.
