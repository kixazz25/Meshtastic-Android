#!/bin/bash
# recommit_docs_v3.sh
# Copies ALL GroupTrack project documents from Downloads to docs/ and commits.
#
# CAPTURES:
#   - GroupTrack*.docx / *.pdf       — all project documents
#   - GroupTrack*.txt                — release notes
#   - DEV_ENVIRONMENT_v*.md          — environment reference (versioned)
#   - grouptrack*.html               — prototype files
#   - *.cfg                          — radio config files
#   - recommit_docs*.sh              — this script
#
# VERSION RULES:
#   - All living docs use _v1 _v2 _v3 suffixes
#   - Latest version stays in docs/
#   - Older versions move to docs/archive/
#   - Windows duplicate copies (1)(2)(3) deleted before commit
#   - Applies to: .docx .pdf .txt .md .html
#
# SESSION START:
#   Fetch from GitHub raw URL:
#   https://raw.githubusercontent.com/[username]/Meshtastic-Android/feature/convoy-event-ride/docs/DEV_ENVIRONMENT_v2.md
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
# Keep latest recommit script at project root too
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

# ── Step 7: Show current active docs ─────────────────────────────────────────
echo "--- Current docs/ ---"
ls -1t "$DOCS"/*.docx "$DOCS"/*.pdf "$DOCS"/*.txt \
        "$DOCS"/*.md "$DOCS"/*.html "$DOCS"/*.sh 2>/dev/null | while read f; do
    echo "  ✓ $(basename $f)"
done
echo ""
echo "--- Radio configs ---"
ls -1t "$RADIO_CONFIGS"/*.cfg 2>/dev/null | while read f; do
    echo "  ✓ $(basename $f)"
done
echo ""

# ── Step 8: Commit and push ───────────────────────────────────────────────────
git add docs/ recommit_docs.sh
if git diff --cached --quiet; then
    echo "No changes to commit — docs already up to date."
    exit 0
fi

DATE=$(date +"%Y-%m-%d %H:%M")
git commit -m "docs: recommit all project documents — $DATE"
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
echo "  Active docs:    docs/"
echo "  Archived:       docs/archive/"
echo "  Radio configs:  docs/radio_configs/"
echo ""
echo "SESSION START — paste to Claude:"
echo "  cat docs/DEV_ENVIRONMENT_v2.md"
echo ""
echo "NAMING RULES:"
echo "  All living docs: _v1 _v2 _v3 suffix"
echo "  APK: GroupTrack_v2.4_YYYYMMDD_HHMM.apk"
echo "  Release notes: GroupTrack_v2.4_YYYYMMDD_HHMM_ReleaseNotes.txt"
echo "  Patch scripts: fix_something_v1.py — unique name every time"
