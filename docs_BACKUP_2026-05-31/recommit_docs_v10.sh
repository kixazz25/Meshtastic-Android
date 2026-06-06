#!/bin/bash
# recommit_docs_v10.sh
# GroupTrack document management + cross-reference generation
# V10: Cross-reference moved to LAST STEP — commit/push happens first
#
# CHANGE FROM V9:
#   The heavy field cross-reference (Section 4 caller analysis) now runs
#   AFTER all docs are committed and pushed. This prevents the 10+ minute
#   cross-ref generation from blocking the commit or competing with builds.
#
#   Order: fast steps → commit → push → THEN heavy cross-ref → commit → push
#
# Run from: ~/Meshtastic-Android
# Usage:    bash docs/recommit_docs_v10.sh

cd /c/Users/kixaz/Meshtastic-Android || { echo "ERROR: Not in Meshtastic-Android"; exit 1; }

DOWNLOADS="/c/Users/kixaz/Downloads"
DOCS="docs"
ARCHIVE="docs/archive"
RADIO_CONFIGS="docs/radio_configs"
CONVOY_SRC="app/src/main/java/com/geeksville/mesh/convoy"
MESH_SRC="app/src/main/java/com/geeksville/mesh"
ASSETS_SRC="app/src/main/assets"
MANIFEST="app/src/main/AndroidManifest.xml"
BUILD_GRADLE="app/build.gradle.kts"

CROSSREF="$DOCS/field_crossref_raw.txt"

echo "=== GROUPTRACK DOCS RECOMMIT v10 ==="
echo "    (cross-reference runs LAST — after commit)"
echo ""

mkdir -p "$DOCS"
mkdir -p "$ARCHIVE"
mkdir -p "$RADIO_CONFIGS"

# ══════════════════════════════════════════════════════════════════════════════
# Step 1: Fast text generation (function universe + where-used)
# ══════════════════════════════════════════════════════════════════════════════
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

# ══════════════════════════════════════════════════════════════════════════════
# Step 2: Copy documents from Downloads to docs/
# ══════════════════════════════════════════════════════════════════════════════
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
cp "$DOWNLOADS/recommit_docs_v10.sh" "$DOCS/recommit_docs_v10.sh" 2>/dev/null

# ══════════════════════════════════════════════════════════════════════════════
# Step 3: Archive older versions
# ══════════════════════════════════════════════════════════════════════════════
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

# ══════════════════════════════════════════════════════════════════════════════
# Step 4: Regenerate AllDocs.txt
# ══════════════════════════════════════════════════════════════════════════════
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

ALLDOCS_LINES=$(wc -l < "$ALLDOCS")
echo "  GroupTrack_AllDocs.txt: $ALLDOCS_LINES lines"

# ══════════════════════════════════════════════════════════════════════════════
# Step 5: FIRST COMMIT — everything except heavy cross-reference
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo "--- Step 5: First commit (fast — everything except cross-ref) ---"

git add "$DOCS/" --all
git add app/src/main/assets/ --all 2>/dev/null

CHANGES=$(git diff --cached --stat | tail -1)
if [ -z "$CHANGES" ]; then
    echo "  No changes to commit."
else
    echo "  $CHANGES"
    DATE=$(date +"%Y-%m-%d %H:%M")
    git commit -m "docs: recommit all project documents + cross-reference — $DATE"
    if [ $? -ne 0 ]; then
        echo "ERROR: commit failed"
    else
        git push origin feature/convoy-event-ride
        if [ $? -ne 0 ]; then
            echo "WARNING: push rejected — run: git push origin feature/convoy-event-ride --force-with-lease"
        fi
        echo ""
        echo "  ✅ DOCS COMMITTED AND PUSHED"
        echo "  Cross-reference generation starting now (this is the slow part)..."
        echo ""
    fi
fi

# ══════════════════════════════════════════════════════════════════════════════
# Step 6: HEAVY CROSS-REFERENCE — runs after commit is safe
# ══════════════════════════════════════════════════════════════════════════════
echo "--- Step 6: Generating field-level cross-reference (HEAVY — may take 5-10 min) ---"
echo "    All docs are already committed. This runs in the background."
echo ""

cat > "$CROSSREF" << 'HEADER'
================================================================================
GROUPTRACK — FIELD-LEVEL CROSS-REFERENCE
================================================================================
Auto-generated by recommit_docs_v10.sh
Upload this file to Claude at session start for full codebase context.

LIFECYCLE PHASES:
  INIT    = Activity.onCreate → Hilt DI → ViewModel init{} — NO PERMISSIONS YET
  TICK    = Every 3-5s from startTick() — requires ACCESS_FINE_LOCATION guard
  MAP-LOAD = WebView.onPageFinished — permissions should be granted
  RECORD  = User taps Record → ConvoyGpsService — requires FINE_LOCATION + FOREGROUND_SERVICE
  RADIO   = Radio BLE connect/disconnect — requires BLUETOOTH_SCAN + BLUETOOTH_CONNECT
  USER    = User-initiated button/menu action — varies by action

RULES:
  INIT functions must NOT require permissions, network, or launch activities.
  TICK functions must have permission guard if accessing GPS/Location.
  Never call startActivity() during INIT or TICK.

STATUS KEY:
  [ADDED]      = New code not in stock Meshtastic
  [CHANGED]    = Modified from Meshtastic base
  [REFERENCE]  = Stock Meshtastic, referenced by convoy code
  [REMOVED]    = Was present, now removed (noted for history)

HEADER

echo "Generated: $(date '+%Y-%m-%d %H:%M')" >> "$CROSSREF"
echo "Branch: $(git branch --show-current)" >> "$CROSSREF"
echo "Last commit: $(git log --oneline -1)" >> "$CROSSREF"
echo "" >> "$CROSSREF"

# ── Section 1: Meshtastic Base Layer Modifications ──
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 1: MESHTASTIC BASE LAYER MODIFICATIONS" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- MainActivity.kt ---" >> "$CROSSREF"
echo "[CHANGED] Main entry point. GroupTrack modifications:" >> "$CROSSREF"
grep -n "convoy\|grouptrack\|GroupTrack\|MANAGE_EXTERNAL\|handleTrackFile\|isTrackFile\|TrackImport" \
  "$MESH_SRC/MainActivity.kt" 2>/dev/null | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- ui/Main.kt ---" >> "$CROSSREF"
echo "[CHANGED] Navigation host. GroupTrack modifications:" >> "$CROSSREF"
grep -n "convoy\|Convoy\|resolveLaunchRoute\|isSubscribed\|grouptrack\|GroupTrack" \
  "$MESH_SRC/ui/Main.kt" 2>/dev/null | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- navigation/ConvoyNavigation.kt ---" >> "$CROSSREF"
echo "[ADDED] All convoy navigation routes:" >> "$CROSSREF"
grep -n "composable\|ConvoyRoutes\|resolveLaunchRoute\|isSubscribed" \
  "$MESH_SRC/navigation/ConvoyNavigation.kt" 2>/dev/null | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- app/build.gradle.kts ---" >> "$CROSSREF"
grep -n "grouptrack\|com.grouptrack\|targetSdk\|compileSdk\|versionCode\|versionName\|signingConfig" \
  "$BUILD_GRADLE" 2>/dev/null | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- AndroidManifest.xml ---" >> "$CROSSREF"
grep -n "MANAGE_EXTERNAL\|WRITE_EXTERNAL\|READ_EXTERNAL\|FOREGROUND_SERVICE\|intent-filter\|convoy\|grouptrack\|application/vnd.google-earth\|gpx\|kml" \
  "$MANIFEST" 2>/dev/null | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"

# ── Section 2: File Inventory ──
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 2: CONVOY FILE INVENTORY" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "FILE | LINES | FUNCTIONS | PURPOSE" >> "$CROSSREF"
echo "---- | ----- | --------- | -------" >> "$CROSSREF"

for f in "$CONVOY_SRC"/*.kt; do
  [ -f "$f" ] || continue
  FNAME=$(basename "$f")
  LINES=$(wc -l < "$f")
  FUNCS=$(grep -c "fun " "$f")
  PURPOSE=$(grep -m1 "class \|object \|\/\/ " "$f" | head -1 | sed 's|^.*//||;s|^.*class ||;s|^.*object ||' | head -c 60)
  echo "$FNAME | $LINES | $FUNCS | $PURPOSE" >> "$CROSSREF"
done
echo "" >> "$CROSSREF"

# ── Section 3: HTML Assets ──
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 3: HTML MAP ASSETS" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

for htmlfile in "$ASSETS_SRC"/convoy_map.html "$ASSETS_SRC"/grouptrack_map.html; do
  [ -f "$htmlfile" ] || continue
  echo "--- $(basename $htmlfile) ---" >> "$CROSSREF"
  grep -n "function " "$htmlfile" | sed 's|^|  |' >> "$CROSSREF"
  echo "" >> "$CROSSREF"
done

# ── Section 4: Per-File Function Detail (THE HEAVY SECTION) ──
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 4: PER-FILE FUNCTION DETAIL" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

TOTAL_FILES=$(ls -1 "$CONVOY_SRC"/*.kt 2>/dev/null | wc -l)
FILE_NUM=0

for f in "$CONVOY_SRC"/*.kt; do
  [ -f "$f" ] || continue
  FNAME=$(basename "$f")
  TOTAL_LINES=$(wc -l < "$f")
  FILE_NUM=$((FILE_NUM + 1))
  echo "  [$FILE_NUM/$TOTAL_FILES] Processing $FNAME..."

  echo "========================================" >> "$CROSSREF"
  echo "FILE: $FNAME" >> "$CROSSREF"
  echo "========================================" >> "$CROSSREF"

  grep -n "fun " "$f" | cut -d: -f1 > /tmp/gt_funclines.txt

  grep -n "fun " "$f" | while IFS= read -r line; do
    LINENUM=$(echo "$line" | cut -d: -f1)
    FUNCLINE=$(echo "$line" | cut -d: -f2- | sed 's|^[[:space:]]*||')
    FUNCNAME=$(echo "$FUNCLINE" | sed 's|.*fun \([a-zA-Z_][a-zA-Z0-9_]*\).*|\1|')

    echo "" >> "$CROSSREF"
    echo "  [$LINENUM] $FUNCLINE" >> "$CROSSREF"

    if [ ${#FUNCNAME} -gt 2 ]; then
      CALLERS=$(grep -rn "\b${FUNCNAME}\b" "$MESH_SRC/" --include="*.kt" 2>/dev/null \
        | grep -v "fun ${FUNCNAME}" \
        | grep -v "\/\/ " \
        | grep -v "$FNAME:$LINENUM:" \
        | sed "s|$MESH_SRC/||" \
        | head -15)
      if [ -n "$CALLERS" ]; then
        echo "    CALLED BY:" >> "$CROSSREF"
        echo "$CALLERS" | sed 's|^|      |' >> "$CROSSREF"
      fi
    fi

    NEXT_FUN=$(awk "NR>$LINENUM" /tmp/gt_funclines.txt | head -1)
    if [ -n "$NEXT_FUN" ]; then
      END_LINE=$((NEXT_FUN - 1))
    else
      END_LINE=$((LINENUM + 80))
      [ $END_LINE -gt $TOTAL_LINES ] && END_LINE=$TOTAL_LINES
    fi

    WORK_FIELDS=$(sed -n "$((LINENUM+1)),${END_LINE}p" "$f" \
      | grep -n "val \|var " \
      | grep -v "private \|override \|MutableState\|StateFlow\|companion\|const " \
      | head -12)
    if [ -n "$WORK_FIELDS" ]; then
      echo "    WORK FIELDS (local computed values):" >> "$CROSSREF"
      echo "$WORK_FIELDS" | while IFS= read -r wf; do
        WF_OFFSET=$(echo "$wf" | cut -d: -f1)
        WF_TEXT=$(echo "$wf" | cut -d: -f2- | sed 's|^[[:space:]]*||')
        ACTUAL_LINE=$((LINENUM + WF_OFFSET))
        echo "      [$ACTUAL_LINE] $WF_TEXT" >> "$CROSSREF"
      done
    fi

    CALLS_OUT=$(sed -n "$((LINENUM+1)),${END_LINE}p" "$f" \
      | grep -o '[a-zA-Z_][a-zA-Z0-9_]*([^)]*)\|[a-zA-Z_][a-zA-Z0-9_]*\.[a-zA-Z_][a-zA-Z0-9_]*(' \
      | sed 's|(.*||' \
      | grep -v "val\|var\|fun\|if\|when\|for\|while\|return\|else\|true\|false\|null\|String\|Int\|Long\|Boolean\|Float\|Double\|List\|Map\|Set" \
      | sort -u \
      | head -15)
    if [ -n "$CALLS_OUT" ]; then
      echo "    CALLS OUT:" >> "$CROSSREF"
      echo "$CALLS_OUT" | sed 's|^|      → |' >> "$CROSSREF"
    fi

  done

  echo "" >> "$CROSSREF"
done

rm -f /tmp/gt_funclines.txt

# ── Section 5: Field-Level Data Flow ──
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 5: FIELD-LEVEL DATA FLOW — WRITERS vs READERS" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- 5A: StateFlow and MutableState Fields ---" >> "$CROSSREF"
echo "" >> "$CROSSREF"

grep -n "MutableStateFlow\|MutableState\|StateFlow\|private val _\|private var _" \
  "$CONVOY_SRC/ConvoyViewModel.kt" 2>/dev/null | while IFS= read -r decl; do
  LINENUM=$(echo "$decl" | cut -d: -f1)
  DECLTEXT=$(echo "$decl" | cut -d: -f2- | sed 's|^[[:space:]]*||')
  FIELDNAME=$(echo "$DECLTEXT" | grep -o '_[a-zA-Z][a-zA-Z0-9]*' | head -1)
  PUBNAME=$(echo "$FIELDNAME" | sed 's|^_||')
  [ -z "$FIELDNAME" ] && continue

  echo "  FIELD: $FIELDNAME (public: $PUBNAME)" >> "$CROSSREF"
  echo "  DECLARED: ConvoyViewModel.kt:$LINENUM" >> "$CROSSREF"
  echo "  $DECLTEXT" >> "$CROSSREF"
  echo "" >> "$CROSSREF"

  echo "    WRITERS (functions that CHANGE this field):" >> "$CROSSREF"
  grep -rn "${FIELDNAME}.*=\|${FIELDNAME}\.value\s*=\|${FIELDNAME}\.emit\|${FIELDNAME}\.update\|${FIELDNAME}\.tryEmit" \
    "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
    | grep -v "MutableStateFlow\|MutableState\|private val\|private var" \
    | sed "s|$CONVOY_SRC/||" | head -10 | sed 's|^|      [W] |' >> "$CROSSREF"

  echo "    READERS (functions that USE this field):" >> "$CROSSREF"
  grep -rn "\b${PUBNAME}\b\|${FIELDNAME}\.value[^=]\|${FIELDNAME}\.value$\|collectAsState.*${PUBNAME}\|collect.*${FIELDNAME}" \
    "$MESH_SRC/" --include="*.kt" 2>/dev/null \
    | grep -v "MutableStateFlow\|MutableState\|private val\|private var\|${FIELDNAME}.*=.*MutableState\|${FIELDNAME}\.value\s*=" \
    | grep -v "fun ${PUBNAME}" \
    | sed "s|$MESH_SRC/||" | head -15 | sed 's|^|      [R] |' >> "$CROSSREF"

  echo "" >> "$CROSSREF"
done

echo "--- 5B: StateFlow Fields in Other Files ---" >> "$CROSSREF"
echo "" >> "$CROSSREF"

for f in "$CONVOY_SRC"/ConvoyGpsService.kt "$CONVOY_SRC"/ConvoySettingsViewModel.kt "$CONVOY_SRC"/ConvoySessionManager.kt "$CONVOY_SRC"/ConvoyConfig.kt; do
  [ -f "$f" ] || continue
  FNAME=$(basename "$f")
  FIELDS=$(grep -n "MutableStateFlow\|MutableState\|private val _\|private var _\|var .*=.*mutableStateOf\|const val\|var .*=" "$f" \
    | grep -v "import\|//\|fun \|return\|if \|val [a-z].*=.*\." | head -20)
  [ -z "$FIELDS" ] && continue

  echo "  FILE: $FNAME" >> "$CROSSREF"
  echo "$FIELDS" | while IFS= read -r line; do
    LINENUM=$(echo "$line" | cut -d: -f1)
    TEXT=$(echo "$line" | cut -d: -f2- | sed 's|^[[:space:]]*||')
    FIELDNAME=$(echo "$TEXT" | grep -o '_[a-zA-Z][a-zA-Z0-9]*\|[A-Z_][A-Z_0-9]*\b' | head -1)
    [ -z "$FIELDNAME" ] && FIELDNAME=$(echo "$TEXT" | grep -o 'var [a-zA-Z]*\|val [a-zA-Z]*' | head -1 | awk '{print $2}')
    echo "    [$LINENUM] $TEXT" >> "$CROSSREF"
    if [ -n "$FIELDNAME" ] && [ ${#FIELDNAME} -gt 2 ]; then
      WRITERS=$(grep -rn "${FIELDNAME}.*=" "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
        | grep -v "MutableStateFlow\|private val\|private var\|import\|const val" \
        | grep -v "$FNAME:$LINENUM:" | sed "s|$CONVOY_SRC/||" | head -5)
      [ -n "$WRITERS" ] && echo "$WRITERS" | sed 's|^|        [W] |' >> "$CROSSREF"
      READERS=$(grep -rn "\b${FIELDNAME}\b" "$MESH_SRC/" --include="*.kt" 2>/dev/null \
        | grep -v "${FIELDNAME}.*=\|MutableStateFlow\|private val\|private var\|import\|const val\|fun ${FIELDNAME}" \
        | sed "s|$MESH_SRC/||" | head -5)
      [ -n "$READERS" ] && echo "$READERS" | sed 's|^|        [R] |' >> "$CROSSREF"
    fi
  done
  echo "" >> "$CROSSREF"
done

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

echo "--- 5E: Kotlin → JavaScript Bridge ---" >> "$CROSSREF"
echo "" >> "$CROSSREF"
grep -rn "evaluateJavascript\|loadUrl.*javascript:" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
  | sed "s|$CONVOY_SRC/||;s|^|    [W→JS] |" >> "$CROSSREF"
echo "" >> "$CROSSREF"
echo "  JavaScript → Kotlin callbacks:" >> "$CROSSREF"
grep -rn "addJavascriptInterface\|@JavascriptInterface\|postMessage\|ConvoyBridge\|WebAppInterface" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
  | sed "s|$CONVOY_SRC/||;s|^|    [JS→KT] |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

# ── Section 6: Lifecycle Phase Map ──
echo "================================================================================" >> "$CROSSREF"
echo "SECTION 6: LIFECYCLE PHASE MAP" >> "$CROSSREF"
echo "================================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- INIT PHASE ---" >> "$CROSSREF"
grep -rn "init {\\|init{\\|override fun onCreate\\|resolveLaunchRoute\\|scanImportDirectory\\|startTick\\|admissionWindowHours\\|myNodeInfo.collect" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- TICK PHASE ---" >> "$CROSSREF"
grep -rn "fun tick\\|readLiveNodes\\|startPhoneGps\\|getPhoneLocation\\|stopPhoneGps\\|ConvoyEngine.compute\\|convoyLog\\|resolveMyCartId\\|_convoyState.value" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- MAP-LOAD PHASE ---" >> "$CROSSREF"
grep -rn "onPageFinished\\|setTileUrl\\|setLocalTiles\\|setAutoPan\\|setOfflineMode\\|setMapTypeLabel\\|evaluateJavascript\\|loadUrl" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- RECORD PHASE ---" >> "$CROSSREF"
grep -rn "startGroupTrack\\|stopGroupTrack\\|startRecording\\|stopRecording\\|pauseRecording\\|resumeRecording\\|finalizeTrack\\|bindGpsService\\|startTrack\\|stopTrack\\|pauseTrack\\|resumeTrack\\|startLocationUpdates\\|stopLocationUpdates\\|onGpsUpdate\\|writeGpxPoint" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- RADIO PHASE ---" >> "$CROSSREF"
grep -rn "installProfileToRadio\\|reconnectRadio\\|buildProfile\\|buildSnapshot\\|archiveCurrentRadio\\|setGpsInterval\\|removeNode\\|meshService\\|setConfig\\|channelViewModel" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "--- USER PHASE ---" >> "$CROSSREF"
grep -rn "setPendingDownload\\|startDownload\\|cancelDownload\\|setLeadCart\\|deleteTempTrack\\|convoyListTracks\\|loadTrackOnMap\\|handleTrackFileImport\\|saveUser\\|clearSession\\|createRide\\|enrollRider\\|followOrganizer\\|unfollowOrganizer\\|registerUser\\|downloadRide\\|createInvite" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

# ── Section 7: Code Issues ──
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

# ── Section 8: Git State ──
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

# ── Section 9: Feature Flags ──
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

# ══════════════════════════════════════════════════════════════════════════════
# Step 7: SECOND COMMIT — just the cross-reference update
# ══════════════════════════════════════════════════════════════════════════════
echo "--- Step 7: Second commit (cross-reference only) ---"

git add "$DOCS/field_crossref_raw.txt"

CHANGES2=$(git diff --cached --stat | tail -1)
if [ -z "$CHANGES2" ]; then
    echo "  Cross-reference unchanged."
else
    echo "  $CHANGES2"
    DATE2=$(date +"%Y-%m-%d %H:%M")
    git commit -m "docs: update field cross-reference — $DATE2"
    git push origin feature/convoy-event-ride
    if [ $? -ne 0 ]; then
        echo "WARNING: push rejected — run: git push origin feature/convoy-event-ride --force-with-lease"
    fi
fi

echo ""
echo "=== DONE ==="
echo ""
echo "  Active docs:       $DOCS/"
echo "  Archived:          $ARCHIVE/"
echo "  Radio configs:     $RADIO_CONFIGS/"
echo ""
echo "  ┌─────────────────────────────────────────────────────────────────┐"
echo "  │  UPLOAD THESE FILES TO CLAUDE AT SESSION START:                 │"
echo "  │                                                                 │"
echo "  │  1. docs/field_crossref_raw.txt                                 │"
echo "  │  2. docs/GroupTrack_AllDocs.txt                                  │"
echo "  │  3. docs/function_universe_raw.txt  (optional)                   │"
echo "  │  4. docs/where_used_raw.txt  (optional)                          │"
echo "  │                                                                 │"
echo "  │  MINIMUM: Upload #1 and #2. That gives Claude everything.        │"
echo "  └─────────────────────────────────────────────────────────────────┘"
echo ""
