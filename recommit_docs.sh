#!/bin/bash
# recommit_docs_v3.sh
# Copies ALL GroupTrack project documents from Downloads to docs/ and commits.
# Also generates GroupTrack_V3_Spec.txt for Claude session context.
#
# Run from: ~/Meshtastic-Android

cd /c/Users/kixaz/Meshtastic-Android
DOWNLOADS="/c/Users/kixaz/Downloads"
DOCS="docs"
ARCHIVE="docs/archive"
RADIO_CONFIGS="docs/radio_configs"

echo "=== GROUPTRACK DOCS RECOMMIT v3 ==="
echo ""

mkdir -p "$DOCS"
mkdir -p "$ARCHIVE"
mkdir -p "$RADIO_CONFIGS"

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

# ── Step 6: Archive older versions ───────────────────────────────────────────
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
            echo "  -> archived: $basename_f"
            archive_count=$((archive_count + 1))
        fi
    done
done
echo "  $archive_count older version(s) archived"
echo ""

# ── Step 7: Generate Claude session spec file ─────────────────────────────────
echo "--- Generating GroupTrack_V3_Spec.txt for Claude ---"
python3 << 'PYEOF'
import os
try:
    import docx
except ImportError:
    os.system('pip install python-docx -q')
    import docx

out = open('C:/Users/kixaz/Downloads/GroupTrack_V3_Spec.txt', 'w', encoding='utf-8')
docs_dir = 'C:/Users/kixaz/Meshtastic-Android/docs'
v3_docs = [f for f in sorted(os.listdir(docs_dir)) if any(x in f for x in [
    'V3','Architecture','RideState','Roadmap_v4','PhaseB',
    'Thursday','Complete_Task','WorkPlan_Apr5'
]) and f.endswith('.docx') and '(' not in f]
for f in v3_docs:
    try:
        doc = docx.Document(os.path.join(docs_dir, f))
        out.write(f'\n\n=== {f} ===\n')
        for p in doc.paragraphs:
            if p.text.strip():
                out.write(p.text + '\n')
        for t in doc.tables:
            for row in t.rows:
                line = ' | '.join(c.text.strip() for c in row.cells if c.text.strip())
                if line:
                    out.write(line + '\n')
    except Exception as e:
        out.write(f'ERROR reading {f}: {e}\n')
out.close()
print('  GroupTrack_V3_Spec.txt written to Downloads')
PYEOF
echo ""

# ── Step 8: Show current active docs ─────────────────────────────────────────
echo "--- Current docs/ ---"
ls -1t "$DOCS"/*.docx "$DOCS"/*.pdf "$DOCS"/*.txt \
        "$DOCS"/*.md "$DOCS"/*.html "$DOCS"/*.sh 2>/dev/null | while read f; do
    echo "  + $(basename $f)"
done
echo ""

# ── Step 9: Commit and push ───────────────────────────────────────────────────
git add docs/ recommit_docs.sh
if git diff --cached --quiet; then
    echo "No changes to commit — docs already up to date."
else
    DATE=$(date +"%Y-%m-%d %H:%M")
    git commit -m "docs: recommit all project documents — $DATE"
    git push origin feature/convoy-event-ride
fi

echo ""
echo "=== DONE ==="
echo ""
echo "SESSION START — upload this file to Claude:"
echo "  C:/Users/kixaz/Downloads/GroupTrack_V3_Spec.txt"
