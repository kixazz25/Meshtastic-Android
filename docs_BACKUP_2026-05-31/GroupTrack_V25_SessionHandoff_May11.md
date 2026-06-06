# GroupTrack V2.5 Session Handoff — May 11, 2026
## Rollback Recommended | Read Before Starting

---

## 1. What Happened

Session goal: Build MapSourceManager.kt to replace hardcoded tile source URLs with JSON-driven lookups (Phase 1A Step 1).

MapSourceManager.kt was created and works correctly — it reads map_sources.json from assets and serves tile URLs for Map Viewer's source buttons. Source switching between SAT/TOPO/TOPO+ was visually confirmed working.

**What went wrong:** ConvoyConfig.kt was modified to make TILE_SOURCES a computed property that depended on MapSourceManager being initialized. ConvoyConfig is shared state — convoy map, tile downloader, and Map Viewer all read from it. MapSourceManager.init() was only called from Map Viewer. This broke convoy map (no tiles on load) and downloads (wrong/null URLs). Multiple build-test-fix cycles followed without resolving cleanly.

**Additional issues found and fixed:**
- onPageFinished timing: Map Viewer needs postDelayed(600) for JS to be ready (same pattern as convoy map)
- try-catch needed around FileInputStream in shouldInterceptRequest (open critical item)
- MANAGE_EXTERNAL_STORAGE ANR on fresh install (pre-existing, backlogged as P1)

---

## 2. Rollback Command

The code is in a mixed state with multiple patches applied and partially reverted. Recommended: roll back to the last clean commit and start fresh.

```bash
git checkout -- app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt
git checkout -- app/src/main/java/com/geeksville/mesh/convoy/ConvoyMapViewerScreen.kt
git checkout -- app/src/main/java/com/geeksville/mesh/convoy/ConvoyTileDownloader.kt
rm app/src/main/java/com/geeksville/mesh/convoy/MapSourceManager.kt
git status
```

This restores all three files to HEAD (c7c5adb42) and removes MapSourceManager.kt. The app returns to the exact state before today's session. map_sources.json stays in assets (committed in c7c5adb42).

**Alternative — keep MapSourceManager.kt as uncommitted file:**
```bash
git checkout -- app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt
git checkout -- app/src/main/java/com/geeksville/mesh/convoy/ConvoyMapViewerScreen.kt
git checkout -- app/src/main/java/com/geeksville/mesh/convoy/ConvoyTileDownloader.kt
git status
```

This leaves MapSourceManager.kt as untracked. The code it contains is good — it just needs to be wired in without touching ConvoyConfig.

---

## 3. Root Cause

**ConvoyConfig is shared state.** Every field in ConvoyConfig is read by convoy map, Map Viewer, tile downloader, and potentially other screens. Changing any field's behavior (from hardcoded to computed, from const to dynamic) breaks all consumers.

Fields that MUST NOT be modified:
- TILE_SOURCES — read by ConvoyScreen.kt onPageFinished, onAreaSelected
- ACTIVE_TILE_SOURCE — read by ConvoyScreen.kt and download code
- ESRI_LABELS_URL — read by ConvoyScreen.kt interceptor and tile downloader
- ESRI_TRANSPORT_URL — read by ConvoyScreen.kt interceptor and tile downloader
- TILE_DIR — read everywhere tile files are accessed
- LOCAL_TILE_BASE — read by convoy map offline logic

**Before modifying ANY ConvoyConfig field, run:**
```bash
grep -rn "FIELD_NAME" app/src/main/java/com/geeksville/mesh/
```

This shows every consumer. If convoy map is in the list, don't touch it.

---

## 4. Correct Isolation Strategy

MapSourceManager is Map Viewer / PlanningMapScreen infrastructure ONLY. It must never flow through ConvoyConfig. The wiring should be:

**Map Viewer reads tile sources:**
- OLD: hardcoded tileSources list in ConvoyMapViewerScreen.kt
- NEW: MapSourceManager.getSlotSources() — called directly, not through ConvoyConfig

**Map Viewer interceptor:**
- Keep hardcoded overlay matching (proven working)
- Add try-catch around FileInputStream (critical item)
- Do NOT use MapSourceManager for interceptor matching (timing issues found)

**Map Viewer onPageFinished:**
- Use postDelayed(600) for JS initialization (matches convoy map pattern)
- Read initial source from MapSourceManager slots, not ConvoyConfig

**Map Viewer downloads:**
- Currently uses ConvoyConfig.TILE_SOURCES and ConvoyConfig.ACTIVE_TILE_SOURCE
- FUTURE: PlanningMapScreen download code reads from MapSourceManager directly
- Do NOT modify ConvoyConfig to serve MapSourceManager data

**Convoy map:**
- Reads ConvoyConfig directly. Always. Never MapSourceManager.
- Zero changes to convoy map during Phase 1.

**Tile downloader:**
- Reads ConvoyConfig.ESRI_TRANSPORT_URL and ESRI_LABELS_URL for SAT overlay downloads
- FUTURE: PlanningMapScreen version of downloader reads from MapSourceManager
- The shared ConvoyTileDownloader.kt keeps hardcoded SAT logic until convoy map is retired

---

## 5. What MapSourceManager.kt Contains (Good Code)

The file works correctly. It:
- Reads map_sources.json from assets (16 sources, 3 default slots)
- Provides getSlotSources() for Map Viewer source buttons
- Provides getSourceByLegacyKey() for lookups
- Provides matchAnyOverlayUrl() for generic interceptor matching (NOT READY — timing issues)
- Has hardcoded fallback if JSON load fails
- Singleton, initialized once via init(context)

The code is sound. The wiring was wrong.

---

## 6. Lessons for Next Session

1. **grep before modifying shared state** — every ConvoyConfig field, every ConvoyViewModel function
2. **One command at a time** — today's cascading patches made the code state hard to track
3. **Build and test after EACH change** — not after stacking multiple patches
4. **Map Viewer download path still goes through ConvoyConfig** — this coupling must be broken when building PlanningMapScreen (new download code that reads MapSourceManager directly)
5. **postDelayed(600) is required** in any WebView onPageFinished that calls evaluateJavascript
6. **try-catch around FileInputStream** in shouldInterceptRequest — still an open critical item
7. **recommit_docs_v9.sh needs fixing** — field crossref was not generated, leaving a gap in the cross-reference data

---

## 7. Starting Next Session

1. Upload this handoff document
2. Decide: rollback to c7c5adb42 (clean) or keep MapSourceManager.kt as untracked
3. Run the grep command on every ConvoyConfig field before touching anything
4. Build MapSourceManager wiring into Map Viewer WITHOUT modifying ConvoyConfig
5. Test on Map Viewer only — convoy map must be visually identical before and after

---

## 8. Open Items

| Item | Priority | Notes |
|------|----------|-------|
| ANR on fresh install | P1 | MANAGE_EXTERNAL_STORAGE blocks main thread. Unacceptable for Play Store. |
| try-catch in shouldInterceptRequest | P1 | Both ConvoyScreen.kt and ConvoyMapViewerScreen.kt |
| recommit_docs_v9.sh broken | P2 | Field crossref not generating. Revert to v6 + apply v9 changes. |
| Google tiles in download cache | INVESTIGATE | Downloaded tiles appeared to be Google, not Esri. May be WebView cache or URL issue. Logging was added but never tested. |
| Download speed regression | INVESTIGATE | 155K tiles worked before, 4200 stalled. May be related to URL issue or Esri server. |

---

*GroupTrack V2.5 | Session Handoff May 11, 2026 | Rollback recommended before continuing*
