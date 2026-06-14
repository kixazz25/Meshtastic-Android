# GroupTrack — Handoff / End-of-day recap — 2026-06-12

_Note-to-future-Claude. Read this + `GroupTrack_V25_LivingChecklist_CONSOLIDATED_2026-06-12.md` first next session. Project runs out of Fred's machine (`~/Meshtastic-Android`, branch `feature/convoy-event-ride`, package `com.grouptrack.android`, RELEASE builds). The container does NOT have the repo — Claude gives commands, Fred runs them one at a time and pastes results. CURRENT doc baseline = this 2026-06-12 set. Build on Fred's UPLOAD, not the Drive connector (which lags ~a week)._

## ⚡ START HERE NEXT SESSION — finish [2h] (the cram is half-applied)

**Today's headline: [2h] SEARCH is DONE, committed, and device-proven. The DETAIL CARD is half-built — 2 of 4 patches applied but NOT yet built.** Tomorrow = apply pieces 3 + 4, build once, device-test.

### State of the [2h] cram (read carefully — this is mid-surgery)
- **SEARCH LAYER — COMMITTED `4f7abbbb7`, DEVICE-PROVEN.** Search-by-name across Trails/Tracks/Waypoints/Routes; type **dropdown** + **keyboard-Enter** (no FIND button); returns proper rows on device (tested: dropdown→Trail→`bluff`→Enter). This is the clean **rollback anchor** if the cram goes sideways.
- **cram1 — APPLIED, UNBUILT.** `SpatialDbManager.kt` (LF, 58758→62471b). Added 4 detail-card DB readers before `addAlias`:
  - `getArtifactDetail(type,id)` → **spatial** row (the full-data card source), drops the geometry blob, coerces name `"null"`/blank → `"Not Named"`.
  - `getAliasesFor(type,id)` → **data-DB** `artifact_aliases`, preferred-first. ⚠️ data-DB handle is **`extensionDb`** (NOT dataDb); spatial is `spatialDb`.
  - `setPreferredAlias(type,id,aliasId)` → star: set is_preferred=1, clear siblings in one txn (no db constraint).
  - `deleteAlias(aliasId)` → remove one alias (UI enforces min-one guard).
  - SQL **proven** against pulled DBs first (test_detail_readers_2026-06-12_v2.py).
- **cram2 — APPLIED, UNBUILT.** `ArtifactListPanel.kt` (CRLF, 15175→21958b). Added reader/alias callbacks to the signature (`onLoadDetail, onLoadAliases, onAddAlias, onStarAlias, onDeleteAlias, onFit` — all defaulted null) + replaced the detail AlertDialog's text-block with the **two-column card**: LEFT rail = existing actions (rename/delete/share/export/change-type, per-type-gated) + **FIT** (stub, logs "FIT not yet wired"); RIGHT = type badge + **alias accordion** (★ star, source chip, × delete min-one-guard, + ADD ALIAS) + curated **full-data card** (spatial fields, geom_hash truncated, geometry hidden, empty-safe). Panel calls the readers **directly** (Fred chose main-thread; reads are tiny).

### ⏭️ REMAINING — piece 3 + piece 4 (do BOTH before building; cram2 won't compile alone)
**Piece 3 — wire both `ArtifactListPanel(...)` call sites** (ConvoyScreen.kt:1651 CONVOY, ConvoyMapViewerScreen.kt:1221 PLANNING):
1. Pass `onLoadDetail = SpatialDbManager::getArtifactDetail` and `onLoadAliases = SpatialDbManager::getAliasesFor` to **both** maps.
2. PLANNING **also** wires `onAddAlias` (→ ConvoyArtifactOps.addAlias), `onStarAlias` (→ setPreferredAlias), `onDeleteAlias` (→ deleteAlias). CONVOY wires **none** of those — read-only by design (live-ride map). **Preserve this asymmetry.**
3. Turn `onResultClick` (currently `Log.i("GT-SEARCH", …)` stub on both) into **open the panel to the clicked artifact**. Two small prereqs:
   - Add `initialDetailId: String? = null` param to `ArtifactListPanel` that seeds `detailArtifactId` (so a search hit opens straight to its detail card — one tap, not two).
   - **Widen `onResultClick`** from `(type,id,geomHash)` to also carry **name** — the result row already has the name from `ArtifactResult`, so onResultClick can build `artifactList = listOf(mapOf("id" to id, "name" to name, "type" to type))` + set `activeListType` + `initialDetailId` with **no DB round-trip**. (ConvoyArtifactsPanel sig is `(String,String,String)` today.)
4. **Same-line search layout** (Fred's 06-12 ask): the type dropdown should sit on the **same row** as the name field (currently stacked, dropdown above). Small SearchBlock layout tweak in ConvoyArtifactsPanel.kt — fold in here.

**Piece 4 — delete the orphan** `ConvoyArtifactDetailPanel.kt` (the never-wired Pass-1 scaffold — zero call sites; the real detail surface is ArtifactListPanel).

**Then:** one build (`./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`), install Droid 2, device-test: search → click a result → detail card opens (left rail actions, right alias accordion + full-data). Test a **Trail** (only trails have alias data + carto_code/source_id today). Then commit the cram, and **[2h] + [11.2] close**.

**Cram patch files** (in /mnt/user-data/outputs, re-deliverable): `cram1_detail_readers_2026-06-12.py`, `cram2_detail_card_2026-06-12.py`, `test_detail_readers_2026-06-12_v2.py`.

## KEY DISCOVERIES TODAY (correct earlier wrong calls)
- **The live detail surface is `ArtifactListPanel.kt`** (hosted ConvoyScreen:1651 + ConvoyMapViewerScreen:1221), NOT the `ConvoyArtifactDetailPanel` scaffold. An earlier call this week said "no parallel detail panel" — that was wrong (grepped the scaffold's name). Fred insisted there was a working detail panel; he was right.
- **Spatial DB controls everything except aliases.** The full-data card reads the **spatial** per-type row (tracks/trails/waypoints/routes). The data-DB `*_properties` tables are NOT the click-detail source — and for tracks/waypoints/routes they're **empty** anyway (only `trail_properties` populated, 49065 rows). Aliases are the **only** thing the card reads from the data DB.
- **Data-DB handle is `extensionDb`** (the file calls grouptrack_data.db the "extension" DB). Caught this before the build — would've failed to compile as `dataDb`.
- **Some trail names are the literal string `"null"`** in the DB (not SQL NULL) — the reader coerces to "Not Named".

## SHIP-CUTOFF (Fred's directive, captured today)
Ship a cutoff that **functions and does not kick the can** on 2.5 items — as complete as possible, then screens/manual. NOT "all 40 items"; NOT "defer 2.5 to 3.0." Draw the line through **tester-visible functional bugs**; everything below (won't-hit / plumbing / cleanup) → release-notes known-issues or 2.6.
- **HARD pre-AAB gates = only [8.7] About/Attribution + [8.9] first-launch release-notes gate**, then bundle (versionCode -P flag, signing). Everything else is either done or non-gating.
- **Manual + release notes are NOT in the AAB** — they ship with the **website** ([9.3]). Screenshots ([9.1]/[9.4]) come AFTER features settle ("don't screenshot a screen about to change"). So the docs track is **last**, after [2h] and the other functional work freeze the screens.
- Tester-visible functional shortcomings to weigh for the cutoff line: [1.2] sliceLine whole-trail explosion (meatiest), [3.1] blank-on-return + lost selections, [3.7] clump-at-low-zoom + z12 limit, [3.3] dead convoy QUEUES, [1.3] armed-gating, [1.12] route z-order, [1.6] snap radius.

## TREE STATE
- HEAD = **`4f7abbbb7`** ([2h] search layer) on top of `03c9e1a56` → `bcd5f8e31` → C-1 → [3.9]+[3.8].
- Working tree carries **cram1 + cram2 applied-uncommitted** (SpatialDbManager.kt, ArtifactListPanel.kt) — these are the in-flight detail card; they build green only after pieces 3+4.
- Plus PARKED weekend state — leave it: `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? utah_trails_stgeorge.geojson.bak` (NEVER git-add the `.bak`), `D docs/.tmp.driveupload/10630`. **Commit only explicitly-named files, never `git add .`.**

## TERMINAL / WORKFLOW NOTES (carried)
- Line endings: `SpatialDbManager.kt` + `ConvoyArtifactsPanel.kt` = **LF**; `ConvoyScreen.kt` + `ConvoyMapViewerScreen.kt` + `ArtifactListPanel.kt` + map HTML = **CRLF**; manifest LF. `cat -A` / check before patching.
- Single-line `python3 -c` anchors with match-count guard (write only if count==1 else ABORT). Git-Bash mangles `!!`; verify by byte size, not `git diff`.
- **Confirm live field/handle names before writing patch code** (caught `extensionDb`-not-`dataDb` this way).
- Build ~10–34 min: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`; APK at `app/build/outputs/apk/google/release/app-google-release.apk`; install `adb -s 24039703201775 install -r -d <apk>` (Droid 2 = dev/test `24039703201775`, Droid 1 = field `8624SBCEDF00001789`). Laptop `curl` fails TLS — Claude-generated files via the file panel → Fred downloads → `cp` into place. Remote /sdcard adb needs `MSYS_NO_PATHCONV=1`.

## DOC HYGIENE
- This EOD set (handoff 06-12, checklist CONSOLIDATED 06-12, manual 06-12, release notes 06-12) produced as full updated files from the 06-11 baseline. **Checklist/manual/release-notes changes tonight are STATE-ONLY** — they reflect that search shipped + the detail card is in flight; they do NOT yet mark [2h]/[11.2] DONE (the card isn't built). Mark those complete next session after the cram builds + device-tests.
- Decision Log still owes dated append blocks for 06-07, 06-10, 06-11, 06-12 sessions — batch item [9.6].
- EOD memory restructure (split the 32KB grouptrack-v25.md into topic files + detail docs) still pending — do it in an EOD pass, not mid-build.
