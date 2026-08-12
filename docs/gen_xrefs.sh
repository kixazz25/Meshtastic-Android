#!/bin/bash
# gen_xrefs.sh -- ALL FOUR RESEARCH INDEXES. NOTHING ELSE.
#
# No git. No commit. No push. No archiving. No copying from Downloads. Nothing
# that can stall, prompt, delete or rewrite a document.
#
# WHY: the indexes used to be produced only as a side effect of the nightly
# recommit, so anything that stopped that pass -- a hung push, an interrupt --
# left them stale with no warning. Research then runs against a frozen file that
# looks fine.
#
# Run it whenever you need current answers:   bash docs/gen_xrefs.sh
#
# Produces, in docs/:
#   function_universe_raw.txt   every fun/val/var declaration
#   where_used_raw.txt          call sites, DERIVED from the universe above
#   navigation_xref.txt         the user-facing navigation map
#   field_crossref_raw.txt      the heavy field-level cross-reference

cd /c/Users/kixaz/Meshtastic-Android || { echo "ERROR: Not in Meshtastic-Android"; exit 1; }
DOCS="docs"
CONVOY_SRC="app/src/main/java/com/geeksville/mesh/convoy"
MESH_SRC="app/src/main/java/com/geeksville/mesh"
CROSSREF="$DOCS/field_crossref_raw.txt"

echo "================================================================="
echo "GENERATING RESEARCH INDEXES -- $(date '+%Y-%m-%d %H:%M')"
echo "================================================================="
echo ""
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

echo "--- Where-used (derived from the function universe) ---"
# The search set is DERIVED, not hardcoded. It used to be a fixed list of about
# sixty names from the radio and ride era; nothing from the map work was ever
# added, so the index returned 24 KB against a 481 KB universe and could not
# answer a question about anything built in months. It never failed and never
# warned.
grep -oE '\b(fun|val|var) +[a-zA-Z_][a-zA-Z0-9_]*' "$DOCS/function_universe_raw.txt" \
  | awk '{print $2}' \
  | grep -vE '^(it|the|to|is|of|in|if|for|and|or|by|on|at|as)$' \
  | awk 'length($0) > 3' \
  | sort -u > "$DOCS/.wu_names.txt"
NAME_COUNT=$(wc -l < "$DOCS/.wu_names.txt")
echo "  search set: $NAME_COUNT symbols"
sed -e 's/^/\\b/' -e 's/$/\\b/' "$DOCS/.wu_names.txt" > "$DOCS/.wu_patterns.txt"
grep -rnE -f "$DOCS/.wu_patterns.txt" \
  "$MESH_SRC/" \
  --include="*.kt" \
  | grep -v "^.*: *\/\/" \
  | sed 's|app/src/main/java/com/geeksville/mesh/||' \
  > "$DOCS/where_used_raw.txt"
rm -f "$DOCS/.wu_names.txt" "$DOCS/.wu_patterns.txt"
WU_COUNT=$(wc -l < "$DOCS/where_used_raw.txt")
echo "  where_used_raw.txt: $WU_COUNT call sites"

echo "--- Step 1b: Generating navigation map (fast) ---"

NAVXREF="$DOCS/navigation_xref.txt"
NAV_HOST=""
for cand in "$MESH_SRC/navigation/ConvoyNavigation.kt" "$MESH_SRC/ui/Main.kt"; do
  [ -f "$cand" ] && NAV_HOST="$NAV_HOST $cand"
done

{
  echo "================================================================="
  echo "GROUPTRACK -- NAVIGATION CROSS-REFERENCE (screen routing for user manual)"
  echo "================================================================="
  echo "Generated: $(date '+%Y-%m-%d %H:%M')"
  echo "Nav host file(s) found: $NAV_HOST"
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

# =========================================================================
# Step 2: Copy documents from Downloads to docs/
# =========================================================================
echo ""
echo "--- Step 6: Generating field cross-reference (heavy -- may take minutes) ---"

{
echo "================================================================="
echo "GROUPTRACK -- FIELD-LEVEL CROSS-REFERENCE"
echo "================================================================="
echo "Auto-generated by recommit_docs_v14.sh"
echo "Upload this file to Claude at session start for full codebase context."
echo "Generated: $(date '+%Y-%m-%d %H:%M')"
echo ""
} > "$CROSSREF"

echo "=================================================================" >> "$CROSSREF"
echo "SECTION 1: CONVOY SOURCE FILE INVENTORY" >> "$CROSSREF"
echo "=================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"
for f in "$CONVOY_SRC"/*.kt; do
  [ -f "$f" ] || continue
  LINES=$(wc -l < "$f")
  echo "  $(basename "$f")  ($LINES lines)" >> "$CROSSREF"
done
echo "" >> "$CROSSREF"

echo "=================================================================" >> "$CROSSREF"
echo "SECTION 4: BRIDGE METHOD CALLER ANALYSIS" >> "$CROSSREF"
echo "=================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

BRIDGE_METHODS="setLocalTiles setAutoPan setOfflineMode setMapTypeLabel setPendingDownload drawTrack addMarker drawRoute clearMap setLeadCart colorSegmentsByNode evaluateJavascript"
for m in $BRIDGE_METHODS; do
  echo "--- $m ---" >> "$CROSSREF"
  grep -rn "$m" "$CONVOY_SRC/" --include="*.kt" 2>/dev/null \
    | grep -v "//" | sed "s|$CONVOY_SRC/||;s|^|    |" >> "$CROSSREF"
  echo "" >> "$CROSSREF"
done

echo "=================================================================" >> "$CROSSREF"
echo "SECTION 5: STATE FIELD READ/WRITE MAP" >> "$CROSSREF"
echo "=================================================================" >> "$CROSSREF"
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

echo "=================================================================" >> "$CROSSREF"
echo "SECTION 6: LIFECYCLE PHASE MAP" >> "$CROSSREF"
echo "=================================================================" >> "$CROSSREF"
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

echo "=================================================================" >> "$CROSSREF"
echo "SECTION 7: CODE ISSUES AND WARNINGS" >> "$CROSSREF"
echo "=================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"

grep -rn "TODO\|FIXME\|BUG\|HACK\|WARNING\|ISSUE\|XXX\|WORKAROUND" \
  "$CONVOY_SRC/" --include="*.kt" 2>/dev/null | grep -v "import " \
  | sed "s|$CONVOY_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

grep -rn "TODO\|FIXME\|BUG\|HACK\|WARNING\|ISSUE" \
  "$ASSETS_SRC/convoy_map.html" "$ASSETS_SRC/grouptrack_map.html" 2>/dev/null \
  | sed "s|$ASSETS_SRC/||;s|^|  |" >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "=================================================================" >> "$CROSSREF"
echo "SECTION 8: CURRENT GIT STATE" >> "$CROSSREF"
echo "=================================================================" >> "$CROSSREF"
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

echo "=================================================================" >> "$CROSSREF"
echo "SECTION 9: FEATURE FLAGS AND CONFIG STATE" >> "$CROSSREF"
echo "=================================================================" >> "$CROSSREF"
echo "" >> "$CROSSREF"
grep -n "V3_FEATURES_ENABLED\|PAYWALL_ENABLED\|SOLO_DEBUG\|TILE_SOURCES\|ACTIVE_TILE_SOURCE\|LOCAL_TILE_BASE\|TILE_DIR" \
  "$CONVOY_SRC/ConvoyConfig.kt" 2>/dev/null | sed 's|^|  |' >> "$CROSSREF"
echo "" >> "$CROSSREF"

echo "=================================================================" >> "$CROSSREF"
echo "END OF FIELD CROSS-REFERENCE" >> "$CROSSREF"
echo "=================================================================" >> "$CROSSREF"

XREF_LINES=$(wc -l < "$CROSSREF")
echo "  field_crossref_raw.txt: $XREF_LINES lines"

echo ""
echo "================================================================="
echo "INDEXES COMPLETE -- $(date '+%Y-%m-%d %H:%M')"
echo "================================================================="
# Every index reported with its size and time, so a collapsed or stale one is
# visible at a glance instead of reading as success.
for g in "$DOCS/function_universe_raw.txt" "$DOCS/where_used_raw.txt" \
         "$DOCS/navigation_xref.txt" "$DOCS/field_crossref_raw.txt"; do
  if [ -f "$g" ]; then
    printf "  %-28s %10s bytes   %s\n" "$(basename "$g")" "$(wc -c < "$g")" "$(date -r "$g" '+%m-%d %H:%M')"
  else
    printf "  %-28s %s\n" "$(basename "$g")" "MISSING"
  fi
done
echo ""
[ "$(wc -c < "$DOCS/where_used_raw.txt" 2>/dev/null || echo 0)" -lt 500000 ] && \
  echo "  XX where_used looks collapsed -- expect megabytes." && echo ""
[ "$(wc -l < "$CROSSREF" 2>/dev/null || echo 0)" -lt 500 ] && \
  echo "  XX field_crossref looks collapsed -- expect thousands of lines." && echo ""
exit 0
