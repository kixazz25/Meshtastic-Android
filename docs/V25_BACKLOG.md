# GroupTrack V2.5 Backlog & Architecture Notes

**Audience:** future-Fred (after 4-week absence) and any AI assistant continuing the work.
**Purpose:** capture decisions, investigations, and intent so V2.5 work can resume without re-investigating settled questions.
**Maintained:** add to this doc as decisions get made — do not re-litigate.

---

## V2.4 Track Manager Architecture (built — context for V2.5 extensions)

### Three-file split (ConvoyTrack*.kt)

The track management system is intentionally split across three files for reusability across screens (e.g., Map Viewer can reuse the same dialogs and ops).

**`ConvoyTrackOps.kt`** — pure file logic, no UI, no Compose. Object singleton callable from anywhere.
- `tracksDir()`, `downloadsDir()` — File path helpers
- `listTracks()` — returns GPX/KML in my_tracks/, excludes hidden + .trashed-
- `isInProgress(file)` — true if filename starts with `convoy_track_temp_`
- `renameTrack(file, newBaseName)` — returns sealed `RenameResult` (Success / NameExists / Failed)
- `deleteTrack(file)` — boolean
- `copyToDownloads(file)` — boolean
- `shareTrack(context, file)` — launches Android share sheet via FileProvider
- `formatSize(bytes)` — B/KB/MB display
- `extractEarliestTime(content)` — parses GPX `<time>` and KML `<when>` to epoch ms
- `importTrackFile(sourceFile, onProgress)` — handles single/multi-track split, preserves earliest time
- `fixDateFromContent(file)` — extract earliest time from existing file's content, set as mtime
- `fixDatesForFiles(files, onProgress)` — bulk version

**`ConvoyTrackDialogs.kt`** — reusable Composable dialogs.
- `TrackActionDialog` — action sheet, accepts nullable callbacks per action
- `RenameTrackDialog` — text input with extension preservation
- `DeleteTrackDialog` — confirm dialog

**`ConvoyTrackExportSheet.kt`** — "Work With Tracks" full-screen manager.
Function name preserved (`ConvoyTrackExportSheet`) for caller compatibility.

### Sort modes (4-state cycle)

Tap the sort button to cycle: Date ↓ → Date ↑ → Name ↑ → Name ↓ → loop.

Default: Date ↓ (newest first).

### Filter tabs

- **All** — everything except hidden/.trashed
- **Saved** — `!isInProgress(file)` (excludes convoy_track_temp_*)
- **In-Progress** — only `convoy_track_temp_*` files

### Naming conventions

| Pattern | Source | Example |
|---|---|---|
| User-named (no timestamp) | renamed by user | `Bar10 to St George.gpx` |
| GroupTrack auto (timestamp suffix) | recorded by app | `coral_pink_sand_day_2_20260505_155359.gpx` |
| Convoy temp (in-progress) | active recording | `convoy_track_temp_20260507_102309.gpx` |
| External tool (own format) | Gaia, onX, etc. | `St George to Bar10.gpx` |
| Android trash (hidden) | Android system | `.trashed-1779474820-onXmaps-04_22_26-080643.gpx` |

### Date preservation rule

**Decision:** The earliest `<time>` element in GPX content (or `<when>` in KML) is the file's creation date.

Set on import via `setLastModified(epochMs)`. Falls back to source file mtime if no parseable `<time>` found.

This makes the Date sort show actual recording dates, not import dates.

### FileProvider

- Authority: `${applicationId}.provider`
- Paths file: `app/src/main/res/xml/convoy_file_paths.xml`
- Includes: external-path, external-files-path, files-path, cache-path

---

## V2.5 Backlog — Build Order

### 1. ConvoyDownloadsImportScreen (in-app file picker)

**Intent:** replace the buggy manifest intent flow (flashes grey/black on launch from external file picker) with an in-app browser pointed at Downloads/.

**Architecture (already designed, ready to build):**
- New file: `app/src/main/java/com/geeksville/mesh/convoy/ConvoyDownloadsImportScreen.kt`
- Mirror Work With Tracks pattern: same TrackRow style, same search/sort/filter
- Filter tabs: All | KML | GPX (no in-progress concept, source dir is Downloads)
- Per-file actions: Import to my_tracks (uses `ConvoyTrackOps.importTrackFile`)
- Progress dialog while importing (multi-track GPX = N output files)
- Status summary on completion: "Imported file.gpx → 3 tracks: a.gpx, b.gpx, c.gpx"
- Source file stays in Downloads as backup (no auto-delete)
- Existing GPX collisions: skip with status, no overwrite
- Stay on screen after import for multi-import — user taps Done when finished

**Wiring:**
- New nav route: `ConvoyRoutes.ConvoyImportFromDownloads`
- Wire `onImportFromDownloads` callback in `Main.kt:500` (currently empty)
- Update `ConvoySubMenu.kt` if menu label changes
- Refactor `MainActivity.handleTrackFileImport` to call `ConvoyTrackOps.importTrackFile` (eliminates code duplication)

**Out of scope:** subdirectory browsing in Downloads (flatten everything to one list)

### 2. Multi-Select Mode

**Intent:** bulk operations on selected tracks. Decided in V2.4 to defer because basic Work With Tracks must ship first.

**Design (agreed):**
- Long-press a track to enter multi-select mode (or "Select" toggle button)
- Checkboxes appear on each row
- "Select All" / "Deselect All" button
- Bottom toolbar with bulk actions: Delete / Share / Move to Downloads / Fix Creation Date
- Tap any row in multi-select toggles its checkbox (no action sheet)
- Cancel/Done button exits multi-select mode

**Excluded from multi-select:**
- Rename — each file needs unique input
- Open on Map — only makes sense for one file at a time

**ConvoyTrackOps additions needed:**
- `deleteTracksBatch(files, onProgress)` — bulk delete
- `shareTracksBatch(context, files)` — multi-attach share intent
- `copyTracksToDownloadsBatch(files, onProgress)` — bulk copy
- `fixDatesForFiles(files, onProgress)` — already exists from V2.4

### 3. Eliminate `_YYYYMMDD_HHMMSS` suffix from auto-named tracks

**Intent:** GroupTrack-recorded tracks should have meaningful names by default (e.g., based on starting location or trail name) instead of timestamp suffixes.

**Approach:**
- Use ConvoyGpsService initial GPS fix to lookup location name (Geocoder)
- Suggest filename based on nearest place + date
- User confirms or edits at recording start (or at recording stop)

**Affects:**
- ConvoyGpsService.kt — recording filename generation
- ConvoyConfig.kt — naming pattern config

### 4. Esri Basemap Service Migration

**Background investigated in V2.4 session:**

Esri legacy ArcGIS Online services (`services.arcgisonline.com/ArcGIS/.../World_Imagery|World_Transportation|World_Boundaries_and_Places`) are in "mature support" — sunsetting per Feb 2026 announcement.

**Observed behavior:**
- Live online: Transportation overlay loads, Boundaries_and_Places sparse/not loading
- Cached offline: both overlays render with full content
- Theory: Esri throttles online presentation layer to push migration; data layer (downloads) still serves rich content.

**Replacement service:**
- New endpoint: `ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?token={KEY}`
- Requires Esri Developer API key (free tier: 2,000,000 tiles/month)
- License question for V2.5: token distribution in installed apps — verify TOS allows app-embedded tokens, or each user needs own developer account

**Code touchpoints:**
- `ConvoyConfig.kt` TILE_SOURCES — change SAT URL
- `ConvoyConfig.kt` ESRI_TRANSPORT_URL, ESRI_LABELS_URL — change to ibasemaps endpoints
- `ConvoyTileDownloader.kt` — add token parameter to URLs
- `ConvoyScreen.kt` interceptor — match new URL patterns
- `convoy_map.html` — update tileLayer URLs

**Decision deferred:** stay on legacy until forced. Cached tiles continue working indefinitely. Migration when first user reports completely broken live tiles.

### 5. Google Maps SDK Native Integration (alternative to Esri migration)

**Background:**
- Google Maps SDK for Android = unlimited free for Android apps
- BUT requires native MapView replacing current Leaflet/WebView implementation
- Significant rewrite: tile rendering, markers, overlays, polylines all need native MapView APIs
- Better long-term performance than WebView

**Comparison:**
| Approach | Cost | Effort | Coverage off-road |
|---|---|---|---|
| Stay Esri legacy | $0 | None | Degrading slowly |
| Migrate Esri ibasemaps | $0 free tier, then paid | Low (URL swap + token) | Same as legacy |
| Switch to Google Maps SDK | $0 unlimited (native) | High (rewrite WebView → MapView) | Excellent |
| Switch to OpenStreetMap | $0 | Medium | Poor (off-road missing) |

**Recommendation noted in session:** if rewriting anyway, Google Maps SDK gives unlimited free + better performance + solves Esri deprecation simultaneously.

### 6. Cache Versioning + "Refresh Tiles" Option

**Intent:** track which URL/version the cache was downloaded from, allow user to redownload areas with current URL.

**Reason:** during V2.4 session, discovered that old SAT cache (downloaded with hybrid URL) doubled labels because the SAT URL was later changed to pure imagery + overlays. Old cache had labels baked in + new overlays = double.

**Design:**
- Add metadata file per region: source URL, timestamp, version
- "Refresh Region" action in maps UI
- Optional: detect mismatched version and offer auto-refresh

### 7. Tile Interceptor Logging

**Current:** interceptor only logs `convoy://tiles/` URLs, not the label fetches (Transportation/Boundaries).
**Intent:** add log statements for all intercepted URLs to make debug easier.
**Effort:** trivial — add `Log.d` to existing interceptor branches.

### 8. Cleanup Automation for Orphan Tracks

**Strict rule (Fred's standing decision):** NEVER auto-delete user files. Cleanup is human-initiated only.

**V2.5 implementation:**
- "In-Progress" filter in Work With Tracks already isolates `convoy_track_temp_*` files
- User can review and delete manually
- Optional: add "Delete All In-Progress" button (with confirm + count + size totals)
- Never run on app start, never on schedule

### 9. Auto-Clear Launcher Cache on Install

**Background:** V2.4 session discovered duplicate launcher icon issue, fixed by `pm clear com.android.launcher3`.

**Intent:** include this clear in the install instructions for testers, or potentially script it.
**Effort:** documentation update, potentially a post-install hook.

### 10. Pure Android GPS Speed Calculation

**Current:** 60-second window for speed calculation.
**Intent:** drop the 60-second window, use Android Location's built-in `getSpeed()` instead.
**Reason:** 60-second window adds latency to displayed speed; native speed updates faster.
**Affects:** ConvoyGpsService.kt speed calculation logic.

---

## Standing Architecture Rules (do not change without strong reason)

### Reusability principle

When adding a new track-related feature, ask: "Does Map Viewer or another screen also need this?" If yes, add the function to `ConvoyTrackOps` (logic) and/or `ConvoyTrackDialogs` (UI), not directly in the calling screen.

### No auto-delete

NEVER programmatically delete user files. All deletes require explicit user action with confirmation dialog.

### Date preservation

Earliest `<time>` from content = file's mtime. Already implemented in `ConvoyTrackOps.importTrackFile`.

### Function naming compatibility

When refactoring, preserve public function names where callers exist. Example: `ConvoyTrackExportSheet` was rewritten to be a full track manager, but kept its old name to avoid breaking caller.

### File organization

- `convoy/ConvoyTrack*.kt` for track features (Ops, Dialogs, ExportSheet, future ImportScreen)
- Pure logic stays separate from Compose UI
- Reusable Composables go in their own file, not embedded in screens

---

## Discovery Log (lessons from V2.4 to remember)

### WebView quirks
- WebView's internal HTTP cache can serve different content than the interceptor — leads to online/offline rendering differences
- `--no-watch-fs` Gradle flag prevents native file watcher crashes on Windows under load

### Esri service degradation
- Legacy World_Boundaries_and_Places returns sparser content live than what the downloader captured
- Theory: presentation layer throttling to push migration, data layer unchanged
- Cache becomes more valuable than live access over time

### File picker flash issue
- Manifest intent filter for KML/GPX shows grey → black → return flash on activity transition
- Cannot fix from app side — Android system behavior
- Workaround: in-app file browser (V2.5 ConvoyDownloadsImportScreen)

### GPX timestamp formats
- Most use ISO 8601 with Z suffix: `2020-05-15T14:30:00Z`
- Some use offset format: `2020-05-15T14:30:00-06:00`
- Both handled by Instant.parse and OffsetDateTime.parse fallback in `extractEarliestTime`

---

*Last updated: V2.4 ship preparation, May 7 2026*
