#!/bin/bash
# recommit_docs_v16.sh
# GroupTrack document management + cross-reference generation
# V16: THE COMMIT IS NOW SCOPED TO docs/ ONLY (`git commit ... -- "$DOCS"`).
#      v14/v15 staged only docs with `git add "$DOCS"` but then ran a BARE
#      `git commit`, which commits the ENTIRE INDEX -- so any file already staged
#      before the run was swept into the "docs:" commit. On 08-11 that silently
#      committed app/src/main/assets/convoy_map.html + grouptrack_map.html. The
#      pathspec makes the commit include ONLY docs/; anything staged elsewhere is
#      left untouched. The staged-change check is scoped the same way.
# V14: Step 3 now ALSO archives stale DATED docs (_YYYY-MM-DD), keeping only the
#      newest date per base name. v13 archived only _v# versioned files, so dated
#      docs (RouteBuilder_..._2026-06-04.html, LivingChecklist_2026-06-04.html, etc.)
#      piled up and had to be cleaned by hand. This closes that gap.
# V13: Step 4 folds HTML docs into GroupTrack_AllDocs.txt (v12 copied .html
#      into docs/ but only docx+md were folded into AllDocs; html text was lost).
# V12: Adds Step 2b — also pull docs from the Google-Drive synced folder
#      (GroupTrack_docs on G:) using the SAME pattern as the Downloads copy.
# V11: Adds Step 1b — navigation_xref.txt (screen routing + button labels).
# V10: Cross-reference moved to LAST STEP — commit/push happens first.
#
# Run from: ~/Meshtastic-Android
# Usage:    bash docs/recommit_docs_v16.sh

cd /c/Users/kixaz/Meshtastic-Android || { echo "ERROR: Not in Meshtastic-Android"; exit 1; }

DOWNLOADS="/c/Users/kixaz/Downloads"
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

echo "=== GROUPTRACK DOCS RECOMMIT v14 ==="
echo "    (cross-reference runs LAST -- after commit)"
echo ""

mkdir -p "$DOCS"
mkdir -p "$ARCHIVE"
mkdir -p "$RADIO_CONFIGS"

# =========================================================================
# Step 1: Fast text generation (function universe + where-used)
# =========================================================================
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

# ------------------------------------------------------------
# Step 1b: Navigation cross-reference (user-manual cookbook) -- fast, defensive
# ------------------------------------------------------------
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
echo "--- Step 2: Copying docs from Downloads ---"

find "$DOWNLOADS" -maxdepth 1 \( -name "GroupTrack*(1)*" -o -name "GroupTrack*(2)*" -o -name "GroupTrack*(3)*" \) -delete 2>/dev/null

for ext in docx pdf txt md html sql json; do
  for f in "$DOWNLOADS"/GroupTrack*."$ext"; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f")
    cp "$f" "$DOCS/$BASENAME"
    echo "  Copied: $BASENAME"
  done
done

for f in "$DOWNLOADS"/*.cfg; do
  [ -f "$f" ] || continue
  BASENAME=$(basename "$f")
  cp "$f" "$RADIO_CONFIGS/$BASENAME"
  echo "  Config: $BASENAME"
done

cp "$DOWNLOADS/recommit_docs_v16.sh" "$DOCS/recommit_docs_v16.sh" 2>/dev/null

# =========================================================================
# Step 2b: ALSO copy documents from the Google-Drive synced folder (G:)
# =========================================================================
echo ""
if [ -d "$GDOCS" ]; then
  echo "--- Step 2b: Copying docs from Google Drive ($GDOCS) ---"

  find "$GDOCS" -maxdepth 1 \( -name "*(1)*" -o -name "*(2)*" -o -name "*(3)*" -o -name "*(4)*" -o -name "*(5)*" -o -name "*(6)*" -o -name "*(7)*" -o -name "*(8)*" -o -name "*(9)*" -o -name "*(1[0-9])*" \) -delete 2>/dev/null

  for ext in docx pdf txt md html sql json; do
    for f in "$GDOCS"/GroupTrack*."$ext"; do
      [ -f "$f" ] || continue
      BASENAME=$(basename "$f")
      cp "$f" "$DOCS/$BASENAME"
      echo "  Copied (Drive): $BASENAME"
    done
  done

  for pat in "handoff_"* "STATE_OF_PLAY"* "CATALOG"* "INDEX"* "v25_"* "v2.5_"* "RouteBuilder"* "NEW_CONVERSATION"*; do
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

# =========================================================================
# Step 3: Archive older versions
#   3a: _v# versioned files (keep highest version)
#   3b: V14 NEW -- _YYYY-MM-DD dated files (keep newest date per base name)
# =========================================================================
echo ""
echo "--- Step 3: Archiving older versions ---"

# 3a -- versioned (_v1, _v2, ...) : keep highest version, archive the rest
for ext in docx pdf txt md html sql; do
  for f in "$DOCS"/*_v[0-9]*."$ext"; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f" ".$ext")
    BASE=$(echo "$BASENAME" | sed 's|_v[0-9]*$||')
    LATEST=$(ls -1 "$DOCS"/${BASE}_v*."$ext" 2>/dev/null | sort -V | tail -1)
    for older in "$DOCS"/${BASE}_v*."$ext"; do
      [ "$older" = "$LATEST" ] && continue
      mv "$older" "$ARCHIVE/" 2>/dev/null && echo "  Archived (ver): $(basename $older)"
    done
  done
done

# 3b -- V14 NEW: dated (_YYYY-MM-DD) : keep newest date per base name, archive the rest
# Matches an 8-char ISO date anchored before the extension: _2026-06-04.html etc.
for ext in docx pdf txt md html sql; do
  # collect distinct base names that carry a date stamp
  for f in "$DOCS"/*_20[0-9][0-9]-[0-1][0-9]-[0-3][0-9]."$ext"; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f" ".$ext")
    # strip the trailing _YYYY-MM-DD to get the stable base name
    BASE=$(echo "$BASENAME" | sed -E 's|_20[0-9]{2}-[0-1][0-9]-[0-3][0-9]$||')
    # newest date sorts last lexicographically (ISO dates sort correctly)
    LATEST=$(ls -1 "$DOCS"/${BASE}_20[0-9][0-9]-[0-1][0-9]-[0-3][0-9]."$ext" 2>/dev/null | sort | tail -1)
    for older in "$DOCS"/${BASE}_20[0-9][0-9]-[0-1][0-9]-[0-3][0-9]."$ext"; do
      [ -f "$older" ] || continue
      [ "$older" = "$LATEST" ] && continue
      mv "$older" "$ARCHIVE/" 2>/dev/null && echo "  Archived (date): $(basename $older)"
    done
  done
done

# =========================================================================
# =========================================================================
# Step 3b [ALLDOCS-PREFIX-FIX-2026-07-26]: collect docs whose filenames do NOT
# begin with the literal "GroupTrack" prefix.
#
# WHY THIS EXISTS
#   Steps 2/3 copy into $DOCS with the glob:
#       "$DOWNLOADS"/GroupTrack*."$ext"
#   and BASH GLOBS ARE CASE-SENSITIVE. Files named grouptrack_* (lowercase),
#   or SCOPING_* / BLUEPRINT_* / COMPLETED_TASK_* / workplan_*, match none of
#   them -- so they never reached $DOCS, and Step 4's HTML folding loop (added
#   in V13, and correct) had nothing to fold. Every HTML doc produced since
#   ~2026-07-14 has therefore been absent from AllDocs.
#
#   This block is ADDITIVE: it copies the missed files in BEFORE Step 4 runs,
#   so the existing loops are untouched and the backlog is picked up too.
# =========================================================================
echo ""
echo "--- Step 3b: Collecting docs by extension [ALLDOCS-BY-EXTENSION-2026-07-27] ---"

# rev2 (Fred, 07-27): collect by EXTENSION, not by name prefix.
#   "suffix extensions are correct. grouptrack prefix is what i'm questioning.
#    json should be omitted."
# A prefix whitelist is the original bug in a new coat: "GroupTrack*" starved
# AllDocs for two weeks, and any fixed prefix list starves it again the first
# time a doc is named outside it. json dropped -- config/state, not docs.
# (.py/.sh were never collected, so patch scripts stay out as before.)
DOC_EXTS="docx pdf txt md html sql"

# ── GUARD 1 [ALLDOCS-GUARDRAILS-2026-07-27]: SECRETS / PERSONAL ────────────
# A HARD gate, matched case-insensitively on the filename before any copy.
# On 07-27 an unguarded sweep committed ngrok_recovery_codes.txt, MySQL
# connection details and a personal PDF into a PUSHED repo (151fffb79).
# Skips are LOGGED so a near-miss is visible rather than silent.
SECRET_PATTERNS="ngrok mysql credential password passwd secret token apikey api_key recovery regform private_key keystore .pem .jks .p12 .env .ppk id_rsa"

# ── GUARD 2: RECENCY WINDOW ───────────────────────────────────────────────
# Collect only what changed recently. No name dependency (a prefix whitelist
# is what starved AllDocs for two weeks), but a floor so the sweep cannot drag
# in years of accumulated Downloads and stall on Drive sync.
# 45 days covers the backlog missing since ~2026-06-10. ONCE THAT BACKLOG IS
# IN AND COMMITTED, drop this to ~14 -- older files will already be tracked.
DOC_MAX_AGE_DAYS=45

# ⚠ EXCLUDE THE SCRIPT'S OWN OUTPUTS. Steps 1/1b GENERATE these into $DOCS.
# Step 3b runs after them, so copying a stale Downloads copy back would CLOBBER
# the fresh one -- handing the next session a stale xref while it believes the
# xref is current. Exactly the failure class this fix exists to end.
SELF_OUTPUTS="GroupTrack_AllDocs.txt field_crossref_raw.txt function_universe_raw.txt where_used_raw.txt navigation_xref.txt"

# The manual is ~3.6 MB, so real docs pass; a stray data dump does not.
MAX_DOC_BYTES=10485760

shopt -s nullglob
COPIED_3B=0
SKIPPED_3B=0

for dir in "$DOWNLOADS" "$GDOCS"; do
  [ -n "$dir" ] || continue
  [ -d "$dir" ] || continue
  for ext in $DOC_EXTS; do
    for f in "$dir"/*."$ext"; do
      [ -f "$f" ] || continue
      BN=$(basename "$f")

      # Browser duplicate markers -- "name (1).html"
      case "$BN" in *"("*")"*) continue ;; esac

      # GUARD 1: secrets / personal. Case-insensitive, logged when it fires.
      BN_LC=$(printf '%s' "$BN" | tr '[:upper:]' '[:lower:]')
      SECRET=0
      for sp in $SECRET_PATTERNS; do
        case "$BN_LC" in *"$sp"*) SECRET=1; break ;; esac
      done
      if [ "$SECRET" -eq 1 ]; then
        echo "  SKIP (secret/personal): $BN"
        SKIPPED_3B=$((SKIPPED_3B+1)); continue
      fi

      # GUARD 2: recency. Older than the window means it is either already
      # tracked or deliberately archived -- either way, not new material.
      if [ -n "$(find "$f" -maxdepth 0 -mtime +$DOC_MAX_AGE_DAYS 2>/dev/null)" ]; then
        SKIPPED_3B=$((SKIPPED_3B+1)); continue
      fi

      # The script's own generated files
      SKIP=0
      for so in $SELF_OUTPUTS; do
        [ "$BN" = "$so" ] && SKIP=1
      done
      if [ "$SKIP" -eq 1 ]; then
        SKIPPED_3B=$((SKIPPED_3B+1)); continue
      fi

      # Implausibly large for a document
      SZ=$(stat -c%s "$f" 2>/dev/null || echo 0)
      if [ "$SZ" -gt "$MAX_DOC_BYTES" ]; then
        echo "  skip (${SZ} bytes, over cap): $BN"
        SKIPPED_3B=$((SKIPPED_3B+1)); continue
      fi

      # Copy when absent, or when the source is newer than what is in $DOCS.
      if [ ! -f "$DOCS/$BN" ] || [ "$f" -nt "$DOCS/$BN" ]; then
        cp -f "$f" "$DOCS/" 2>/dev/null && COPIED_3B=$((COPIED_3B+1))
      fi
    done
  done
done

shopt -u nullglob
echo "  Step 3b copied/updated: $COPIED_3B file(s), skipped: $SKIPPED_3B"

# =========================================================================
# Step 4: Regenerate AllDocs.txt
# =========================================================================
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

# V13: ALSO fold in the HTML current-state docs (release notes, living checklist,
# manual) so AllDocs is complete. Prefer pandoc; fall back to a crude tag-strip.
for f in "$DOCS"/*.html; do
  [ -f "$f" ] || continue
  BASENAME=$(basename "$f")
  echo "=== $BASENAME ===" >> "$ALLDOCS"
  if command -v pandoc &>/dev/null; then
    pandoc "$f" -t plain --wrap=none 2>/dev/null >> "$ALLDOCS"
  else
    sed -e 's/<script[^>]*>.*<\/script>//gI' \
        -e 's/<style[^>]*>.*<\/style>//gI' \
        -e 's/<[^>]*>//g' "$f" \
      | sed '/^[[:space:]]*$/d' >> "$ALLDOCS"
  fi
  echo "" >> "$ALLDOCS"
  echo "" >> "$ALLDOCS"
done

AD_LINES=$(wc -l < "$ALLDOCS")
echo "  GroupTrack_AllDocs.txt: $AD_LINES lines"

# =========================================================================
# RECOMMIT-V15-2026-08-12: THE CROSS-REFERENCE IS GENERATED **BEFORE** THE
# COMMIT, NOT AFTER IT.
#
# In v14 the heavy cross-reference ran as step 6, AFTER the step 5 commit and
# push. When that push stalled the script never reached it, so
# field_crossref_raw.txt silently kept its old timestamp while every other
# index refreshed -- and the line count it prints came from the STALE file, so
# it read as success every single run.
#
# Generating it first means it is local work with nothing ahead of it that can
# hang, and it lands in the SAME commit as everything else. The old second
# commit is gone; there is nothing left for it to commit.
# =========================================================================

# =========================================================================
# Step 4b: Heavy field cross-reference (runs BEFORE the commit)
# =========================================================================
echo ""
echo "--- Step 4b: Generating field cross-reference (heavy -- may take minutes) ---"

{
echo "================================================================="
echo "GROUPTRACK -- FIELD-LEVEL CROSS-REFERENCE"
echo "================================================================="
echo "Auto-generated by recommit_docs_v16.sh"
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

# RECOMMIT-V15-2026-08-12: fail LOUDLY. The old script printed this same line
# count from whatever file happened to be on disk, so a cross-reference that
# had not regenerated for days still reported a healthy number.
if [ ! -s "$CROSSREF" ]; then
  echo ""
  echo "  XX CROSS-REFERENCE IS EMPTY OR MISSING -- $CROSSREF"
  echo ""
elif [ "$XREF_LINES" -lt 500 ]; then
  echo ""
  echo "  XX CROSS-REFERENCE LOOKS COLLAPSED: $XREF_LINES lines (expect thousands)."
  echo ""
fi

# =========================================================================
# Step 5: COMMIT -- all docs INCLUDING the cross-reference
# =========================================================================
echo ""
echo "--- Step 5: Commit (docs, xrefs, cross-reference) ---"

git add "$DOCS"

# RECOMMIT-V16-2026-08-13: scope BOTH the change-check and the commit to "$DOCS".
#   The bug (v14/v15): `git add "$DOCS"` staged only docs, but a bare `git commit`
#   commits the ENTIRE INDEX -- so anything already staged before this run (e.g. a
#   source asset left staged by a bisect) was folded into the "docs:" commit. On
#   08-11 that silently committed app/src/main/assets/convoy_map.html + grouptrack_map.html
#   under a "docs:" label. The `-- "$DOCS"` pathspec makes the commit include ONLY
#   docs/, leaving anything staged elsewhere untouched. The diff-check is scoped the
#   same way so the "no changes" guard reflects docs, not the whole index.
CHANGES=$(git diff --cached --stat -- "$DOCS" | tail -1)
if [ -z "$CHANGES" ]; then
    echo "  No doc changes to commit."
else
    echo "  $CHANGES"
    DATE1=$(date +"%Y-%m-%d %H:%M")
    git commit -m "docs: recommit docs + fast xrefs -- $DATE1" -- "$DOCS"
    git push origin feature/convoy-event-ride
    if [ $? -ne 0 ]; then
        echo "WARNING: push rejected -- run: git push origin feature/convoy-event-ride --force-with-lease"
    fi
fi


# RECOMMIT-V15-2026-08-12: a completion marker. Without one, a run that dies
# part-way looks identical to a run that finished.
echo ""
echo "================================================================="
echo "RECOMMIT V16 COMPLETE -- $(date '+%Y-%m-%d %H:%M')"
echo "================================================================="
for g in "$DOCS/field_crossref_raw.txt" "$DOCS/where_used_raw.txt" \
         "$DOCS/function_universe_raw.txt" "$DOCS/navigation_xref.txt"; do
  if [ -f "$g" ]; then
    echo "  $(basename "$g")  $(wc -c < "$g") bytes  $(date -r "$g" '+%m-%d %H:%M')"
  else
    echo "  $(basename "$g")  MISSING"
  fi
done
echo ""