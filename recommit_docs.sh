#!/bin/bash
# recommit_docs.sh
# Copies all GroupTrack*.docx from Downloads to docs/ and commits.
# VERSION RULES:
#   - Files with version numbers (e.g. _v1, _v2) are copied newest-first
#   - Older versions of the same base document are moved to docs/archive/
#   - Pure duplicates with (1)(2)(3) suffixes are deleted before commit
# Run from ~/Meshtastic-Android

cd /c/Users/kixaz/Meshtastic-Android
DOWNLOADS="/c/Users/kixaz/Downloads"
DOCS="docs"
ARCHIVE="docs/archive"

echo "=== GROUPTRACK DOCS RECOMMIT ==="
echo ""

mkdir -p "$DOCS"
mkdir -p "$ARCHIVE"

# ── Step 1: Delete Windows duplicate copies (1)(2)(3) from Downloads ────────
echo "--- Removing Windows duplicate copies from Downloads ---"
find "$DOWNLOADS" -maxdepth 1 -name "GroupTrack* (*).docx" -delete
find "$DOWNLOADS" -maxdepth 1 -name "GroupTrack* (*).pdf" -delete
echo "  Done"
echo ""

# ── Step 2: Copy all GroupTrack docs from Downloads ──────────────────────────
echo "--- Copying docs from Downloads ---"
doc_count=0
for f in "$DOWNLOADS"/GroupTrack*.docx "$DOWNLOADS"/GroupTrack*.pdf; do
    if [ -f "$f" ]; then
        cp "$f" "$DOCS/"
        echo "  + $(basename $f)"
        doc_count=$((doc_count + 1))
    fi
done

if [ $doc_count -eq 0 ]; then
    echo "  No GroupTrack docs found in Downloads."
else
    echo "  $doc_count doc(s) copied"
fi
echo ""

# ── Step 3: Archive older versions — keep only latest version of each doc ───
echo "--- Archiving older versions ---"
archive_count=0

# Get all versioned docs (those with _v followed by a number)
for f in "$DOCS"/GroupTrack*_v*.docx "$DOCS"/GroupTrack*_v*.pdf; do
    [ -f "$f" ] || continue
    basename_f=$(basename "$f")
    
    # Extract base name (strip _vN suffix and extension)
    # e.g. GroupTrack_TaskList_Apr3_v2.docx -> GroupTrack_TaskList_Apr3
    base=$(echo "$basename_f" | sed 's/_v[0-9]*\.\(docx\|pdf\)$//')
    ext=$(echo "$basename_f" | grep -oE '\.(docx|pdf)$')
    version=$(echo "$basename_f" | grep -oE '_v[0-9]+' | grep -oE '[0-9]+')
    
    # Find all versions of this base doc
    latest_version=$version
    for other in "$DOCS"/${base}_v*.${ext#.}; do
        [ -f "$other" ] || continue
        other_ver=$(echo "$(basename $other)" | grep -oE '_v[0-9]+' | grep -oE '[0-9]+')
        if [ "$other_ver" -gt "$latest_version" ] 2>/dev/null; then
            latest_version=$other_ver
        fi
    done
    
    # If this is not the latest version, archive it
    if [ "$version" -lt "$latest_version" ] 2>/dev/null; then
        mv "$DOCS/$basename_f" "$ARCHIVE/$basename_f"
        echo "  -> archived: $basename_f (v$version superseded by v$latest_version)"
        archive_count=$((archive_count + 1))
    fi
done

echo "  $archive_count older version(s) archived"
echo ""

# ── Step 4: Show current active docs ─────────────────────────────────────────
echo "--- Current active docs/ ---"
ls -1t "$DOCS"/*.docx "$DOCS"/*.pdf "$DOCS"/*.png 2>/dev/null | while read f; do
    echo "  ✓ $(basename $f)"
done
echo ""

# ── Step 5: Commit ────────────────────────────────────────────────────────────
git add docs/
if git diff --cached --quiet; then
    echo "No changes to commit — docs already up to date."
    exit 0
fi

DATE=$(date +"%Y-%m-%d %H:%M")
git commit -m "docs: recommit GroupTrack documents — $DATE"
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
echo "=== DONE — docs committed and pushed ==="
echo "  Active docs: docs/"
echo "  Archived:    docs/archive/"
echo ""
echo "NAMING REMINDER: Use _v1, _v2, _v3 suffixes on all living documents."
echo "  Example: GroupTrack_TaskList_Apr3_v2.docx"
echo "  Never reuse the same filename for a revision."
