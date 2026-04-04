#!/bin/bash
# recommit_docs.sh
# Copies ALL GroupTrack project documents from Downloads to docs/ and commits.
#
# CAPTURES:
#   - GroupTrack*.docx / *.pdf       — all project documents
#   - DEV_ENVIRONMENT.md             — environment reference
#   - grouptrack*.html               — prototype files
#   - GroupTrack*.txt                — release notes
#   - *.cfg                          — radio config files
#   - recommit_docs.sh               — this script
#
# VERSION RULES:
#   - Files with version numbers (e.g. _v1, _v2) keep only latest in docs/
#   - Older versions moved to docs/archive/
#   - Windows duplicate copies (1)(2)(3) deleted before commit
#
# SESSION START:
#   At the start of every Claude session, paste contents of:
#     docs/DEV_ENVIRONMENT.md
#     docs/OPEN_TASKS.md (if exists)
#   Or fetch raw from GitHub:
#     https://raw.githubusercontent.com/[username]/Meshtastic-Android/feature/convoy-event-ride/docs/DEV_ENVIRONMENT.md
#
# Run from: ~/Meshtastic-Android

cd /c/Users/kixaz/Meshtastic-Android
DOWNLOADS="/c/Users/kixaz/Downloads"
DOCS="docs"
ARCHIVE="docs/archive"
RADIO_CONFIGS="docs/radio_configs"

echo "=== GROUPTRACK DOCS RECOMMIT ==="
echo ""

mkdir -p "$DOCS"
mkdir -p "$ARCHIVE"
mkdir -p "$RADIO_CONFIGS"

# ── Step 1: Delete Windows duplicate copies (1)(2)(3) from Downloads ─────────
echo "--- Removing Windows duplicate copies from Downloads ---"
find "$DOWNLOADS" -maxdepth 1 -name "GroupTrack* (*).docx" -delete
find "$DOWNLOADS" -maxdepth 1 -name "GroupTrack* (*).pdf" -delete
find "$DOWNLOADS" -maxdepth 1 -name "GroupTrack* (*).txt" -delete
find "$DOWNLOADS" -maxdepth 1 -name "grouptrack* (*).html" -delete
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

# ── Step 5: Copy critical environment files ───────────────────────────────────
echo "--- Copying environment files ---"
for f in DEV_ENVIRONMENT.md recommit_docs.sh; do
    if [ -f "$DOWNLOADS/$f" ]; then
        cp "$DOWNLOADS/$f" "$DOCS/$f"
        echo "  + $f"
    elif [ -f "$f" ]; then
        cp "$f" "$DOCS/$f"
        echo "  + $f (from project root)"
    fi
done
# Always keep recommit_docs.sh at project root too
if [ -f "$DOWNLOADS/recommit_docs.sh" ]; then
    cp "$DOWNLOADS/recommit_docs.sh" ./recommit_docs.sh
fi
echo ""

# ── Step 6: Archive older versions ───────────────────────────────────────────
echo "--- Archiving older versions ---"
archive_count=0
for f in "$DOCS"/GroupTrack*_v*.docx \
         "$DOCS"/GroupTrack*_v*.pdf \
         "$DOCS"/GroupTrack*_v*.txt \
         "$DOCS"/grouptrack*_v*.html; do
    [ -f "$f" ] || continue
    basename_f=$(basename "$f")
    ext="${basename_f##*.}"
    base=$(echo "$basename_f" | sed 's/_v[0-9]*\.'$ext'$//')
    version=$(echo "$basename_f" | grep -oE '_v[0-9]+' | grep -oE '[0-9]+')
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
echo "SESSION START INSTRUCTIONS:"
echo "  Paste docs/DEV_ENVIRONMENT.md to Claude at session start"
echo "  Or fetch from GitHub raw URL:"
echo "  https://raw.githubusercontent.com/[username]/Meshtastic-Android/feature/convoy-event-ride/docs/DEV_ENVIRONMENT.md"
echo ""
echo "NAMING RULES:"
echo "  Use _v1 _v2 _v3 suffixes on all living documents"
echo "  Never reuse the same filename for a revision"
echo "  APK: GroupTrack_v2.4_YYYYMMDD_HHMM.apk"
echo "  Release notes: GroupTrack_v2.4_YYYYMMDD_HHMM_ReleaseNotes.txt"
