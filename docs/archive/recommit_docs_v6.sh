#!/bin/bash
# recommit_docs_v6.sh
# Copies ALL GroupTrack project documents from Downloads to docs/ and commits.
# V6: Restored AllDocs content extraction (lost in v5 transition from v3 Step 7)
#     Generates GroupTrack_AllDocs.txt with full text from every docx.
#
# CAPTURES:
#   - GroupTrack*.docx / *.pdf       — all project documents
#   - GroupTrack*.txt                — release notes
#   - DEV_ENVIRONMENT_v*.md          — environment reference (versioned)
#   - grouptrack*.html               — prototype files
#   - *.cfg                          — radio config files
#   - recommit_docs*.sh              — this script
#   - convoy_api.php                 — live server API file (pulled from EC2)
#
# GENERATES:
#   - where_used_raw.txt             — function call cross-reference
#   - function_universe_raw.txt      — all convoy function declarations
#   - GroupTrack_AllDocs.txt          — full text content of every docx (for Claude)
#
# VERSION RULES:
#   - All living docs use _v1 _v2 _v3 suffixes
#   - Latest version stays in docs/
#   - Older versions move to docs/archive/
#   - Windows duplicate copies (1)(2)(3) deleted before commit
#   - Applies to: .docx .pdf .txt .md .html
#
# Run from: ~/Meshtastic-Android

cd /c/Users/kixaz/Meshtastic-Android
DOWNLOADS="/c/Users/kixaz/Downloads"
DOCS="docs"
ARCHIVE="docs/archive"
RADIO_CONFIGS="docs/radio_configs"
SERVER_DOCS="docs/server"
SSH_KEY="~/.ssh/convoy-api-key-2.pem"
EC2="ec2-user@34.224.89.217"
API_FILE="/var/www/html/convoy_api.php"

echo "=== GROUPTRACK DOCS RECOMMIT v6 ==="
echo ""

mkdir -p "$DOCS"
mkdir -p "$ARCHIVE"
mkdir -p "$RADIO_CONFIGS"
mkdir -p "$SERVER_DOCS"

# ── Step 0: Auto-generate cross-reference documents ───────────────────────────
echo "--- Generating function cross-reference ---"

grep -rn "startRecording\|stopRecording\|finalizeTrack\|installProfileToRadio\|buildProfile\|buildSnapshot\|archiveCurrentRadio\|applyMasterConfig\|scanImportDirectory\|startDownload\|recalcLead\|setLeadCart\|startGroupTrack\|stopGroupTrack\|startTrack\|stopTrack\|pauseTrack\|resumeTrack\|onRadioPosition\|downloadTiles\|isSignedIn\|isSubscribed\|saveUser\|clearSession\|resolveLaunchRoute\|followOrganizer\|unfollowOrganizer\|createRide\|enrollRider\|compute\b\|computeStatus\|computeHeading\|assignLeadTail\|colorSegmentsByNode" \
  app/src/main/java/com/geeksville/mesh/ \
  --include="*.kt" | grep -v "^.*:.*fun \|test" > docs/where_used_raw.txt

grep -rn "^    fun \|^    private fun \|^    suspend fun \|^    override fun \|^fun \|^private fun \|^suspend fun " \
  app/src/main/java/com/geeksville/mesh/convoy/ \
  --include="*.kt" | grep -v "//\|test" | sort > docs/function_universe_raw.txt

echo "  ✓ where_used_raw.txt ($(wc -l < docs/where_used_raw.txt) call sites)"
echo "  ✓ function_universe_raw.txt ($(wc -l < docs/function_universe_raw.txt) functions)"
echo ""

# ── Step 0.5: Pull convoy_api.php from EC2 ────────────────────────────────────
echo "--- Pulling server API file from EC2 ---"
scp -i "$SSH_KEY" "$EC2:$API_FILE" "$SERVER_DOCS/convoy_api.php" 2>/dev/null
if [ $? -eq 0 ]; then
    echo "  ✓ convoy_api.php pulled from EC2"
    # Also stamp a dated snapshot
    STAMP=$(date +"%Y%m%d_%H%M%S")
    cp "$SERVER_DOCS/convoy_api.php" "$SERVER_DOCS/convoy_api_${STAMP}.php"
    # Keep only the 3 most recent snapshots — clean up older ones
    ls -t "$SERVER_DOCS"/convoy_api_*.php 2>/dev/null | tail -n +4 | xargs rm -f 2>/dev/null
    echo "  ✓ snapshot: convoy_api_${STAMP}.php (keeping 3 most recent)"
else
    echo "  ! WARNING: could not reach EC2 — API file not updated"
    echo "    Check VPN/network and SSH key. Continuing without API file."
fi
echo ""

# ── Step 1: Delete Windows duplicate copies from Downloads ────────────────────
echo "--- Removing Windows duplicate copies from Downloads ---"
find "$DOWNLOADS" -maxdepth 1 -name "GroupTrack* (*).docx" -delete
find "$DOWNLOADS" -maxdepth 1 -name "GroupTrack* (*).pdf" -delete
find "$DOWNLOADS" -maxdepth 1 -name "GroupTrack* (*).txt" -delete
find "$DOWNLOADS" -maxdepth 1 -name "grouptrack* (*).html" -delete
find "$DOWNLOADS" -maxdepth 1 -name "DEV_ENVIRONMENT* (*).md" -delete
find "$DOWNLOADS" -maxdepth 1 -name "* (*).cfg" -delete
echo "  Done"
echo ""

# ── Step 2: Copy GroupTrack docs ──────────────────────────────────────────────
echo "--- Copying GroupTrack docs ---"
doc_count=0
for f in "$DOWNLOADS"/GroupTrack*.docx \
         "$DOWNLOADS"/GroupTrack*.pdf \
         "$DOWNLOADS"/GroupTrack*.txt; do
    if [ -f "$f" ]; then
        cp "$f" "$DOCS/"
        echo "  + $(basename $f)"
        doc_count=$((doc_count + 1))
    fi
done
echo "  $doc_count doc(s) copied"
echo ""

# ── Step 3: Copy prototype HTML files ────────────────────────────────────────
echo "--- Copying prototype HTML ---"
html_count=0
for f in "$DOWNLOADS"/grouptrack*.html; do
    if [ -f "$f" ]; then
        cp "$f" "$DOCS/"
        echo "  + $(basename $f)"
        html_count=$((html_count + 1))
    fi
done
echo "  $html_count HTML file(s) copied"
echo ""

# ── Step 4: Copy radio config files ──────────────────────────────────────────
echo "--- Copying radio configs ---"
cfg_count=0
for f in "$DOWNLOADS"/*.cfg; do
    if [ -f "$f" ]; then
        cp "$f" "$RADIO_CONFIGS/"
        echo "  + $(basename $f)"
        cfg_count=$((cfg_count + 1))
    fi
done
echo "  $cfg_count config(s) copied"
echo ""

# ── Step 5: Copy versioned environment and script files ──────────────────────
echo "--- Copying environment files ---"
for f in "$DOWNLOADS"/DEV_ENVIRONMENT_v*.md \
         "$DOWNLOADS"/recommit_docs_v*.sh; do
    if [ -f "$f" ]; then
        cp "$f" "$DOCS/"
        echo "  + $(basename $f)"
    fi
done
latest_recommit=$(ls -t "$DOWNLOADS"/recommit_docs_v*.sh 2>/dev/null | head -1)
if [ -f "$latest_recommit" ]; then
    cp "$latest_recommit" ./recommit_docs.sh
    echo "  + recommit_docs.sh (project root updated)"
fi
echo ""

# ── Step 6: Archive older versions of all versioned files ────────────────────
echo "--- Archiving older versions ---"
archive_count=0

for ext in docx pdf txt md html sh; do
    for f in "$DOCS"/*_v*.$ext; do
        [ -f "$f" ] || continue
        basename_f=$(basename "$f")
        base=$(echo "$basename_f" | sed 's/_v[0-9]*\.'$ext'$//')
        version=$(echo "$basename_f" | grep -oE '_v[0-9]+' | grep -oE '[0-9]+')
        [ -z "$version" ] && continue
        latest_version=$version
        for other in "$DOCS"/${base}_v*.$ext; do
            [ -f "$other" ] || continue
            other_ver=$(echo "$(basename $other)" | grep -oE '_v[0-9]+' | grep -oE '[0-9]+')
            if [ "$other_ver" -gt "$latest_version" ] 2>/dev/null; then
                latest_version=$other_ver
            fi
        done
        if [ "$version" -lt "$latest_version" ] 2>/dev/null; then
            mv "$DOCS/$basename_f" "$ARCHIVE/$basename_f"
            echo "  -> archived: $basename_f (v$version superseded by v$latest_version)"
            archive_count=$((archive_count + 1))
        fi
    done
done

echo "  $archive_count older version(s) archived"
echo ""

# ── Step 7: Extract all docx content into GroupTrack_AllDocs.txt ──────────────
echo "--- Extracting docx content for Claude sessions ---"
python3 << 'PYEOF'
import os
try:
    import docx
except ImportError:
    os.system('pip install python-docx -q')
    import docx

docs_dir = 'docs'
out_path = os.path.join(docs_dir, 'GroupTrack_AllDocs.txt')
out = open(out_path, 'w', encoding='utf-8')
doc_count = 0

for f in sorted(os.listdir(docs_dir)):
    if f.endswith('.docx') and '(' not in f:
        out.write(f'=== {f} ===\n')
        try:
            d = docx.Document(os.path.join(docs_dir, f))
            for p in d.paragraphs:
                if p.text.strip():
                    out.write(p.text + '\n')
            for t in d.tables:
                for row in t.rows:
                    line = ' | '.join(c.text.strip() for c in row.cells if c.text.strip())
                    if line:
                        out.write(line + '\n')
        except Exception as e:
            out.write(f'ERROR reading {f}: {e}\n')
        out.write('\n')
        doc_count += 1

out.close()
size = os.path.getsize(out_path)
print(f'  ✓ GroupTrack_AllDocs.txt ({doc_count} docs, {size:,} bytes)')
PYEOF

# Also copy to Downloads for easy upload to Claude
cp "$DOCS/GroupTrack_AllDocs.txt" "$DOWNLOADS/GroupTrack_AllDocs.txt"
echo "  ✓ Copied to Downloads for Claude session upload"
echo ""

# ── Step 8: Show current active docs ─────────────────────────────────────────
echo "--- Current docs/ ---"
ls -1t "$DOCS"/*.docx "$DOCS"/*.pdf "$DOCS"/*.txt \
        "$DOCS"/*.md "$DOCS"/*.html "$DOCS"/*.sh 2>/dev/null | while read f; do
    echo "  ✓ $(basename $f)"
done
echo ""
echo "--- Server files ---"
ls -1t "$SERVER_DOCS"/*.php 2>/dev/null | while read f; do
    echo "  ✓ $(basename $f)"
done
echo ""
echo "--- Cross-reference data ---"
echo "  ✓ where_used_raw.txt"
echo "  ✓ function_universe_raw.txt"
echo "  ✓ GroupTrack_AllDocs.txt (full docx content)"
echo ""
echo "--- Radio configs ---"
ls -1t "$RADIO_CONFIGS"/*.cfg 2>/dev/null | while read f; do
    echo "  ✓ $(basename $f)"
done
echo ""

# ── Step 9: Commit and push ───────────────────────────────────────────────────
git add docs/ recommit_docs.sh
if git diff --cached --quiet; then
    echo "No changes to commit — docs already up to date."
    exit 0
fi

DATE=$(date +"%Y-%m-%d %H:%M")
git commit -m "docs: recommit all project documents + cross-reference — $DATE"
if [ $? -ne 0 ]; then
    echo "ERROR: commit failed"
    exit 1
fi

git push origin feature/convoy-event-ride
if [ $? -ne 0 ]; then
    echo ""
    echo "ERROR: push rejected — run:"
    echo "  git push origin feature/convoy-event-ride --force-with-lease"
    exit 1
fi

echo ""
echo "=== DONE ==="
echo "  Active docs:       docs/"
echo "  Server API:        docs/server/convoy_api.php"
echo "  Archived:          docs/archive/"
echo "  Radio configs:     docs/radio_configs/"
echo "  Cross-ref data:    docs/where_used_raw.txt"
echo "                     docs/function_universe_raw.txt"
echo "  Full doc content:  docs/GroupTrack_AllDocs.txt"
echo ""
echo "SESSION START — upload these to Claude:"
echo "  1. docs/DEV_ENVIRONMENT_v3.md"
echo "  2. docs/GroupTrack_AllDocs.txt"
echo "  3. docs/where_used_raw.txt"
echo "  4. docs/function_universe_raw.txt"
