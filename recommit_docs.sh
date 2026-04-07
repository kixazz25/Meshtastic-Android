#!/bin/bash
# recommit_docs.sh
# Copies all GroupTrack*.docx from Downloads to docs/ and commits.
# Run this when docs have been accidentally deleted from the repo.

cd /c/Users/kixaz/Meshtastic-Android

DOWNLOADS="/c/Users/kixaz/Downloads"
DOCS="docs"

echo "=== GROUPTRACK DOCS RECOMMIT ==="
echo ""

# Ensure docs directory exists
mkdir -p "$DOCS"

# Copy all GroupTrack docs from Downloads
doc_count=0
for f in "$DOWNLOADS"/GroupTrack*.docx; do
    if [ -f "$f" ]; then
        cp "$f" "$DOCS/"
        echo "✓ $(basename $f)"
        doc_count=$((doc_count + 1))
    fi
done

if [ $doc_count -eq 0 ]; then
    echo "No GroupTrack*.docx files found in Downloads."
    echo "Make sure your docs are in C:/Users/kixaz/Downloads first."
    exit 1
fi

echo ""
echo "$doc_count doc(s) copied to docs/"
echo ""

# Check if anything changed
git add docs/

if git diff --cached --quiet; then
    echo "No changes to commit — docs already up to date in repo."
    exit 0
fi

DATE=$(date +"%Y-%m-%d %H:%M")
git commit -m "docs: recommit GroupTrack documents — $DATE"

if [ $? -eq 0 ]; then
    git push origin feature/convoy-event-ride
    echo ""
    echo "=== DONE — $doc_count docs committed and pushed ==="
else
    echo "ERROR: commit failed"
    exit 1
fi
