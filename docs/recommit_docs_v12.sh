#!/bin/bash
# recommit_docs_v12.sh
# GroupTrack document management + cross-reference generation
# V12: Adds Step 2b — also pull docs from the Google-Drive synced folder
#      (GroupTrack_docs on G:) using the SAME pattern as the Downloads copy.
#      Claude writes current-state docs into GroupTrack_docs; they sync to G:;
#      this step archives them into git docs/ alongside the Downloads copy.
#      Defensive: skips cleanly if the G: path is not mounted; cannot block run.
# V11: Adds Step 1b — navigation_xref.txt (screen routing + button labels)
#      for the user-manual cookbook. Fast grep block; cannot block the run.
# V10: Cross-reference moved to LAST STEP — commit/push happens first
#
# CHANGE FROM V9:
#   The heavy field cross-reference (Section 4 caller analysis) now runs
#   AFTER all docs are committed and pushed. This prevents the 10+ minute
#   cross-ref generation from blocking the commit or competing with builds.
#
#   Order: fast steps -> commit -> push -> THEN heavy cross-ref -> commit -> push
#
# Run from: ~/Meshtastic-Android
# Usage:    bash docs/recommit_docs_v12.sh

cd /c/Users/kixaz/Meshtastic-Android || { echo "ERROR: Not in Meshtastic-Android"; exit 1; }

DOWNLOADS="/c/Users/kixaz/Downloads"
# V12: Google-Drive synced working-docs folder (Claude writes here; syncs to G:).
# ADJUST THIS ONE LINE if your Drive-for-Desktop mount differs. Common shapes:
#   /g/My Drive/GroupTrack_docs   or   /g/GroupTrack_docs
GDOCS="/g/My Drive/GroupTrack_docs"
DOCS="docs"
ARCHIVE="docs/archive"
RADIO_CONFIGS="docs/radio_configs"
CONVOY_SRC="app/src/main/java/com/geeksville/mesh/convoy"
MESH_SRC="app/src/main/java/com/geeksville/mesh"
ASSETS_SRC="app/src/main/assets"
MANIFEST="app/src/main/AndroidManifest.xml"
BUILD_GRADLE="app/build.gradle.kts"

CROSSREF="$DOCS/field_crossref_raw.txt"

echo "=== GROUPTRACK DOCS RECOMMIT v12 ==="
echo "    (cross-reference runs LAST -- after commit)"
echo ""

mkdir -p "$DOCS"
mkdir -p "$ARCHIVE"
mkdir -p "$RADIO_CONFIGS"

# ==============================================================================
# Step 1: Fast text generation (function universe + where-used)
# ==============================================================================
echo "--- Step 1: Generating function universe (fast) ---"

grep -rn "fun \|object \|class \|val \|var " \
  "$CONVOY_SRC/" \
  --include="*.kt" \
  | grep -v "\/\/" \
  | grep -v "test" \
  | sed 's|app/src/main/java/com/geeksville/mesh/convoy/||' \
  > "$DOCS/function_universe_raw.txt"

FN_COUNT=$(wc -l < "$DOCS/function_universe_raw.txt")
echo "  function_universe_raw.txt: $FN_COUNT entries"

echo "--- Step 1: Generating where-used (fast) ---"

grep -rn "startRecording\|stopRecording\|finalizeTrack\|installProfileToRadio\|buildProfile\|buildSnapshot\|archiveCurrentRadio\|applyMasterConfig\|scanImportDirectory\|startDownload\|recalcLead\|startGroupTrack\|stopGroupTrack\|startTrack\|stopTrack\|pauseTrack\|resumeTrack\|onRadioPosition\|downloadTiles\|isSignedIn\|isSubscribed\|saveUser\|clearSession\|resolveLaunchRoute\|followOrganizer\|unfollowOrganizer\|createRide\|enrollRider\|compute\b\|computeStatus\|computeHeading\|assignLeadTail\|colorSegmentsByNode\|setLeadCart\|deleteTempTrack\|convoyListTracks\|loadTrackOnMap\|handleTrackFileImport\|startPhoneGps\|stopPhoneGps\|getPhoneLocation\|readLiveNodes\|tick\b\|startTick\|bindGpsService\|setLocalTiles\|setAutoPan\|setOfflineMode\|setMapTypeLabel\|setPendingDownload\|cancelDownload\|calculateTiles\|downloadTile\b\|tilePath\|registerUser\|downloadRide\|createInvite\|onGpsUpdate\|startLocationUpdates\|stopLocationUpdates\|writeGpxPoint\|migrateTiles\|getUserId\|reconnectRadio\|setGpsInterval\|removeNode" \
  "$MESH_SRC/" \
  --include="*.kt" \
  | grep -v "^.*: *\/\/" \
  | sed 's|app/src/main/java/com/geeksville/mesh/||' \
  > "$DOCS/where_used_raw.txt"

WU_COUNT=$(wc -l < "$DOCS/where_used_raw.txt")
echo "  where_used_raw.txt: $WU_COUNT call sites"

# ----------------------------------------------
# Step 1b: Navigation cross-reference (user-manual cookbook) -- fast, defensive
# ----------------------------------------------
echo "--- Step 1b: Generating navigation map (fast) ---"

NAVXREF="$DOCS/navigation_xref.txt"
NAV_HOST=""
for cand in "$MESH_SRC/navigation/ConvoyNavigation.kt" "$MESH_SRC/ui/Main.kt"; do
  [ -f "$cand" ] && NAV_HOST="$NAV_HOST $cand"
done

{
  echo "================================================================================"
  echo "GROUPTRACK -- NAVIGATION CROSS-REFERENCE (screen routing for user manual)"
  echo "================================================================================"
  echo "Generated: $(date '+%Y-%m-%d %H:%M')"
  echo "Nav host file(s) found:$NAV_HOST"
  echo ""

  echo "=== A. NAV HOST ROUTES (every destination) ==="
  for hf in $NAV_HOST; do
    echo "--- $(basename "$hf") ---"
    grep -n "composable(\|route =\|ConvoyRoutes\|sealed class\|object .* :\|data class .* :\|navigate(\|currentScreen\|when *(" \
      "$hf" 2>/dev/null
    echo ""
  done

  echo "=== C. NAVIGATION TRIGGERS (onNavigate* params + navigate() calls) ==="
  grep -rn "onNavigate[A-Za-z]*\|navController.navigate\|navigate(\|onOpen[A-Z][A-Za-z]*\|onLaunch\b" \
    "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
    | grep -v "//" \
    | sed "s|$CONVOY_SRC/||"
  echo ""

  echo "=== D. SCREEN COMPOSABLES + EXIT CALLBACKS ==="
  for f in "$CONVOY_SRC"/*Screen*.kt; do
    [ -f "$f" ] || continue
    echo "--- $(basename "$f") ---"
    grep -n "fun .*Screen(\|onNavigate[A-Za-z]*\|onDismiss\|onBack\|onAccept\|onDecline\|onLaunch" "$f" 2>/dev/null | head -30
  done
  echo ""

  echo "=== E. USER-VISIBLE LABELS (what the user taps -- Text/label/title) ==="
  for f in "$CONVOY_SRC"/*Screen*.kt "$CONVOY_SRC"/*Panel*.kt; do
    [ -f "$f" ] || continue
    LABELS=$(grep -n 'Text("\|Text( *"\|label *= *"\|text *= *"\|title *= *"' "$f" 2>/dev/null | head -50)
    [ -z "$LABELS" ] && continue
    echo "--- $(basename "$f") ---"
    echo "$LABELS"
  done
} > "$NAVXREF" 2>/dev/null

NAV_COUNT=$(wc -l < "$NAVXREF" 2>/dev/null || echo 0)
echo "  navigation_xref.txt: $NAV_COUNT lines"

# ==============================================================================
# Step 2: Copy documents from Downloads to docs/
# ==============================================================================
echo ""
echo "--- Step 2: Copying docs from Downloads ---"

# Delete Windows duplicate copies first
find "$DOWNLOADS" -maxdepth 1 \( -name "GroupTrack*(1)*" -o -name "GroupTrack*(2)*" -o -name "GroupTrack*(3)*" \) -delete 2>/dev/null

# Copy GroupTrack documents
for ext in docx pdf txt md html sql json; do
  for f in "$DOWNLOADS"/GroupTrack*."$ext"; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f")
    cp "$f" "$DOCS/$BASENAME"
    echo "  Copied: $BASENAME"
  done
done

# Copy radio configs
for f in "$DOWNLOADS"/*.cfg; do
  [ -f "$f" ] || continue
  BASENAME=$(basename "$f")
  cp "$f" "$RADIO_CONFIGS/$BASENAME"
  echo "  Config: $BASENAME"
done

# Copy this script
cp "$DOWNLOADS/recommit_docs_v12.sh" "$DOCS/recommit_docs_v12.sh" 2>/dev/null

# ==============================================================================
# Step 2b: ALSO copy documents from the Google-Drive synced folder (G:)
#   Same pattern as Step 2 above, just a different source dir.
#   Defensive: if the G: path is not mounted, skip silently -- never block.
# ==============================================================================
echo ""
if [ -d "$GDOCS" ]; then
  echo "--- Step 2b: Copying docs from Google Drive ($GDOCS) ---"

  # Delete Windows duplicate copies first (same guard as Downloads)
  find "$GDOCS" -maxdepth 1 \( -name "*(1)*" -o -name "*(2)*" -o -name "*(3)*" -o -name "*(4)*" -o -name "*(5)*" -o -name "*(6)*" -o -name "*(7)*" -o -name "*(8)*" -o -name "*(9)*" -o -name "*(1[0-9])*" \) -delete 2>/dev/null

  # Copy GroupTrack-named documents
  for ext in docx pdf txt md html sql json; do
    for f in "$GDOCS"/GroupTrack*."$ext"; do
      [ -f "$f" ] || continue
      BASENAME=$(basename "$f")
      cp "$f" "$DOCS/$BASENAME"
      echo "  Copied (Drive): $BASENAME"
    done
  done

  # The working folder also holds non-GroupTrack-prefixed current-state docs
  # (handoff_*, STATE_OF_PLAY*, CATALOG*, INDEX*, v25_*, v2.5_*). Copy those too.
  for pat in "handoff_"* "STATE_OF_PLAY"* "CATALOG"* "INDEX"* "v25_"* "v2.5_"*; do
    for f in "$GDOCS"/$pat; do
      [ -f "$f" ] || continue
      case "$f" in
        *.docx|*.pdf|*.txt|*.md|*.html|*.sql|*.json) ;;
        *) continue ;;
      esac
      BASENAME=$(basename "$f")
      cp "$f" "$DOCS/$BASENAME"
      echo "  Copied (Drive): $BASENAME"
    done
  done
else
  echo "--- Step 2b: SKIP -- Google Drive folder not found at $GDOCS ---"
  echo "    (adjust GDOCS at top of script if your Drive mount path differs)"
fi

# ==============================================================================
# Step 3: Archive older versions
# ==============================================================================
echo ""
echo "--- Step 3: Archiving older versions ---"

for ext in docx pdf txt md html sql; do
  for f in "$DOCS"/*_v[0-9]*."$ext"; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f" ".$ext")
    BASE=$(echo "$BASENAME" | sed 's|_v[0-9]*$||')
    LATEST=$(ls -1 "$DOCS"/${BASE}_v*."$ext" 2>/dev/null | sort -V | tail -1)
    for older in "$DOCS"/${BASE}_v*."$ext"; do
      [ "$older" = "$LATEST" ] && continue
      mv "$older" "$ARCHIVE/" 2>/dev/null && echo "  Archived: $(basename $older)"
    done
  done
done

# ==============================================================================
# Step 4: Regenerate AllDocs.txt
# ==============================================================================
echo ""
echo "--- Step 4: Regenerating GroupTrack_AllDocs.txt ---"

ALLDOCS="$DOCS/GroupTrack_AllDocs.txt"
> "$ALLDOCS"

for f in "$DOCS"/*.docx; do
  [ -f "$f" ] || continue
  BASENAME=$(basename "$f")
  echo "=== $BASENAME ===" >> "$ALLDOCS"
  if command -v pandoc &>/dev/null; then
    pandoc "$f" -t plain --wrap=none 2>/dev/null >> "$ALLDOCS"
  else
    python3 -c "
from docx import Document
doc = Document('$f')
for p in doc.paragraphs:
    print(p.text)
for t in doc.tables:
    for row in t.rows:
        print(' | '.join(c.text.strip() for c in row.cells))
" 2>/dev/null >> "$ALLDOCS"
  fi
  echo "" >> "$ALLDOCS"
  echo "" >> "$ALLDOCS"
done

# Also fold in the markdown current-state docs so AllDocs is complete
for f in "$DOCS"/*.md; do
  [ -f "$f" ] || continue
  BASENAME=$(basename "$f")
  echo "=== $BASENAME ===" >> "$ALLDOCS"
  cat "$f" >> "$ALLDOCS"
  echo "" >> "$ALLDOCS"
  echo "" >> "$ALLDOCS"
done

AD_LINES=$(wc -l < "$ALLDOCS")
echo "  GroupTrack_AllDocs.txt: $AD_LINES lines"

# ==============================================================================
# Step 5: FIRST COMMIT -- all docs except the heavy cross-reference
# ==============================================================================
echo ""
echo "--- Step 5: First commit (docs, fast xrefs) ---"

git add "$DOCS"

CHANGES=$(git diff --cached --stat | tail -1)
if [ -z "$CHANGES" ]; then
    echo "  No doc changes to commit."
else
    echo "  $CHANGES"
    DATE1=$(date +"%Y-%m-%d %H:%M")
    git commit -m "docs: recommit docs + fast xrefs -- $DATE1"
    git push origin feature/convoy-event-ride
    if [ $? -ne 0 ]; then
        echo "WARNING: push rejected -- run: git push origin feature/convoy-event-ride --force-with-lease"
    fi
fi

# ==============================================================================
# Step 6: Heavy field cross-reference (runs LAST)
# ==============================================================================
echo ""
echo "--- Step 6: Generating field cross-reference (heavy -- may take minutes) ---"

{
echo "================================================================================"
echo "GROUPTRACK -- FIELD-LEVEL CROSS-REFERENCE"
echo "================================================================================"
echo "Auto-generated by recommit_docs_v12.sh"
echo "Upload this file to Claude at session start for full codebase context."
echo "Generated: $(date '+%Y-%m-%d %H:%M')"
echo ""
} > "$CROSSREF"

# ---- Section 1-3: Inventory ----
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 1: CONVOY SOURCE FILE INVENTORY" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"
for f in "$CONVOY_SRC"/*.kt; do
  [ -f "$f" ] || continue
  LINES=$(wc -l < "$f")
  echo "  $(basename "$f")  ($LINES lines)" >> "$CROSSREF"
done
echo "" >> "$CROSSREF"

# ---- Section 4: Field caller analysis (the heavy part) ----
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 4: BRIDGE METHOD CALLER ANALYSIS" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

BRIDGE_METHODS="setLocalTiles setAutoPan setOfflineMode setMapTypeLabel setPendingDownload drawTrack addMarker drawRoute clearMap setLeadCart colorSegmentsByNode evaluateJavascript"
for m in $BRIDGE_METHODS; do
  echo "--- $m ---" >> "$CROSSREF"
  grep -rn "$m" "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
    | grep -v "//" | sed "s|$CONVOY_SRC/||;s|^|    |" >> "$CROSSREF"
  echo "" >> "$CROSSREF"
done

# ---- Section 5: State field read/write map ----
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 5: STATE FIELD READ/WRITE MAP" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- 5C: SharedPreferences ---" >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "  WRITERS:" >> "$CROSSREF"
grep -rn "putString\|putBoolean\|putInt\|putLong\|\.edit\b\|\.save\b" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | grep -v "import\|//" \
  | sed "s|$CONVOY_SRC/||;s|^|    [W] |" >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "  READERS:" >> "$CROSSREF"
grep -rn "getString\|getBoolean\|getInt\|getLong\|preferences\[" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | grep -v "import\|//" \
  | sed "s|$CONVOY_SRC/||;s|^|    [R] |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- 5D: File I/O ---" >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "  FILE WRITERS:" >> "$CROSSREF"
grep -rn "FileOutputStream\|BufferedWriter\|\.write(\|\.renameTo\|\.mkdirs\|\.createNewFile\|copyTo\|writeText\|appendText" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | grep -v "import\|//" \
  | sed "s|$CONVOY_SRC/||;s|^|    [W] |" >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "  FILE READERS:" >> "$CROSSREF"
grep -rn "FileInputStream\|BufferedReader\|\.readText\|\.listFiles\|\.exists()\|\.readLines\|\.readBytes\|openInputStream\|contentResolver" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | grep -v "import\|//" \
  | sed "s|$CONVOY_SRC/||;s|^|    [R] |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- 5E: Kotlin -> JavaScript Bridge ---" >> "$CROSSREF"
echo "" >> "$CROSSREF"
grep -rn "evaluateJavascript\|loadUrl.*javascript:" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
  | sed "s|$CONVOY_SRC/||;s|^|    [W->JS] |" >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "  JavaScript -> Kotlin callbacks:" >> "$CROSSREF"
grep -rn "addJavascriptInterface\|@JavascriptInterface\|postMessage\|ConvoyBridge\|WebAppInterface" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
  | sed "s|$CONVOY_SRC/||;s|^|    [JS->KT] |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

# ---- Section 6: Lifecycle Phase Map ----
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 6: LIFECYCLE PHASE MAP" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- INIT PHASE ---" >> "$CROSSREF"
grep -rn "init {\|init{\|override fun onCreate\|resolveLaunchRoute\|scanImportDirectory\|startTick\|admissionWindowHours\|myNodeInfo.collect" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- TICK PHASE ---" >> "$CROSSREF"
grep -rn "fun tick\|readLiveNodes\|startPhoneGps\|getPhoneLocation\|stopPhoneGps\|ConvoyEngine.compute\|convoyLog\|resolveMyCartId\|_convoyState.value" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- MAP-LOAD PHASE ---" >> "$CROSSREF"
grep -rn "onPageFinished\|setTileUrl\|setLocalTiles\|setAutoPan\|setOfflineMode\|setMapTypeLabel\|evaluateJavascript\|loadUrl" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- RECORD PHASE ---" >> "$CROSSREF"
grep -rn "startGroupTrack\|stopGroupTrack\|startRecording\|stopRecording\|pauseRecording\|resumeRecording\|finalizeTrack\|bindGpsService\|startTrack\|stopTrack\|pauseTrack\|resumeTrack\|startLocationUpdates\|stopLocationUpdates\|onGpsUpdate\|writeGpxPoint" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- RADIO PHASE ---" >> "$CROSSREF"
grep -rn "installProfileToRadio\|reconnectRadio\|buildProfile\|buildSnapshot\|archiveCurrentRadio\|setGpsInterval\|removeNode\|meshService\|setConfig\|channelViewModel" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- USER PHASE ---" >> "$CROSSREF"
grep -rn "setPendingDownload\|startDownload\|cancelDownload\|setLeadCart\|deleteTempTrack\|convoyListTracks\|loadTrackOnMap\|handleTrackFileImport\|saveUser\|clearSession\|createRide\|enrollRider\|followOrganizer\|unfollowOrganizer\|registerUser\|downloadRide\|createInvite" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

# ---- Section 7: Code Issues ----
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 7: CODE ISSUES AND WARNINGS" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

grep -rn "TODO\|FIXME\|BUG\|HACK\|WARNING\|ISSUE\|XXX\|WORKAROUND" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | grep -v "import " \
  | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

grep -rn "TODO\|FIXME\|BUG\|HACK\|WARNING\|ISSUE" \
  "$ASSETS_SRC/convoy_map.html" "$ASSETS_SRC/grouptrack_map.html" 2>/dev/null \
  | sed "s|$ASSETS_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

# ---- Section 8: Git State ----
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 8: CURRENT GIT STATE" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "Branch: $(git branch --show-current)" >> "$CROSSREF"
echo "Last 5 commits:" >> "$CROSSREF"
git log --oneline -5 | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "Uncommitted changes:" >> "$CROSSREF"
git status --short | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "Stash list:" >> "$CROSSREF"
git stash list 2>/dev/null | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"

# ---- Section 9: Feature Flags ----
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 9: FEATURE FLAGS AND CONFIG STATE" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"
grep -n "V3_FEATURES_ENABLED\|PAYWALL_ENABLED\|SOLO_DEBUG\|TILE_SOURCES\|ACTIVE_TILE_SOURCE\|LOCAL_TILE_BASE\|TILE_DIR" \
  "$CONVOY_SRC/ConvoyConfig.kt" 2>/dev/null | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "================================================================================" >> "$CROSSREF"
echo "END OF FIELD CROSS-REFERENCE" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"

XREF_LINES=$(wc -l < "$CROSSREF")
echo "  field_crossref_raw.txt: $XREF_LINES lines"
echo ""

# ==============================================================================
# Step 7: SECOND COMMIT -- just the cross-reference update
# ==============================================================================
echo "--- Step 7: Second commit (cross-reference only) ---"

git add "$DOCS/field_crossref_raw.txt"

CHANGES2=$(git diff --cached --stat | tail -1)
if [ -z "$CHANGES2" ]; then
    echo "  Cross-reference unchanged."
else
    echo "  $CHANGES2"
    DATE2=$(date +"%Y-%m-%d %H:%M")
    git commit -m "docs: update field cross-reference -- $DATE2"
    git push origin feature/convoy-event-ride
    if [ $? -ne 0 ]; then
        echo "WARNING: push rejected -- run: git push origin feature/convoy-event-ride --force-with-lease"
    fi
fi

echo ""
echo "=== DONE ==="
echo ""
echo "  Active docs:       $DOCS/"
echo "  Archived:          $ARCHIVE/"
echo "  Radio configs:     $RADIO_CONFIGS/"
echo ""
echo "  UPLOAD THESE FILES TO CLAUDE AT SESSION START:"
echo "    1. docs/field_crossref_raw.txt"
echo "    2. docs/GroupTrack_AllDocs.txt"
echo "    3. docs/function_universe_raw.txt  (optional)"
echo "    4. docs/where_used_raw.txt  (optional)"
echo "    5. docs/navigation_xref.txt  (for user-manual cookbook)"
echo ""
echo "  MINIMUM: Upload #1 and #2. That gives Claude everything."
echo ""
