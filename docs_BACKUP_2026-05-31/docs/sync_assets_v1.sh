#!/bin/bash
# sync_assets_v1.sh
# GroupTrack Asset Sync — pre-build asset verification
#
# Compares device assets against repo committed versions using MD5 hash.
# Only pulls and commits assets that have actually changed.
# Run before every release build to ensure assets are current.
#
# Usage:
#   bash ~/Meshtastic-Android/docs/sync_assets_v1.sh
#
# Or as part of build:
#   bash ~/Meshtastic-Android/docs/sync_assets_v1.sh && \
#   ./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease 2>&1 | grep -E "^e:|BUILD"

cd /c/Users/kixaz/Meshtastic-Android

DEVICE="8624SBCEDF00001789"
PKG="com.geeksville.mesh"
ASSETS="app/src/main/assets"
CHANGED=0
FAILED=0
TMPDIR="/tmp/grouptrack_asset_sync"

mkdir -p "$TMPDIR"

echo "=== GROUPTRACK ASSET SYNC ==="
echo "  Device:  $DEVICE"
echo "  Package: $PKG"
echo ""

check_and_sync() {
    local filename=$1
    local device_file="files/$filename"
    local repo_file="$ASSETS/$filename"
    local tmp_file="$TMPDIR/$filename"

    # Check device is connected
    if ! adb -s $DEVICE get-state > /dev/null 2>&1; then
        echo "  ERROR: Device $DEVICE not connected"
        FAILED=$((FAILED + 1))
        return
    fi

    # Pull from device to temp
    adb -s $DEVICE shell run-as $PKG cat "$device_file" > "$tmp_file" 2>/dev/null

    # Check pull succeeded and file is not empty
    if [ ! -s "$tmp_file" ]; then
        echo "  SKIP: $filename — not found on device (not yet captured)"
        return
    fi

    # Check repo file exists
    if [ ! -f "$repo_file" ]; then
        cp "$tmp_file" "$repo_file"
        echo "  NEW: $filename — added to assets"
        CHANGED=$((CHANGED + 1))
        return
    fi

    # Compare hashes
    device_hash=$(md5sum "$tmp_file" | cut -d' ' -f1)
    repo_hash=$(md5sum "$repo_file" | cut -d' ' -f1)

    if [ "$device_hash" != "$repo_hash" ]; then
        cp "$tmp_file" "$repo_file"
        echo "  CHANGED: $filename — updated in assets"
        CHANGED=$((CHANGED + 1))
    else
        echo "  current: $filename — no change"
    fi
}

# ── Sync all 3 assets ─────────────────────────────────────────────────────────
check_and_sync "master_config.json"
check_and_sync "convoy_apply_list.json"
check_and_sync "master.cfg"

echo ""

# ── Commit if anything changed ────────────────────────────────────────────────
if [ $CHANGED -gt 0 ]; then
    git add "$ASSETS/master_config.json" \
            "$ASSETS/convoy_apply_list.json" \
            "$ASSETS/master.cfg"
    DATE=$(date +"%Y-%m-%d %H:%M")
    git commit -m "assets: sync $CHANGED changed asset(s) before build — $DATE"
    echo "  $CHANGED asset(s) synced and committed"
elif [ $FAILED -gt 0 ]; then
    echo "  WARNING: $FAILED asset(s) could not be synced — device connected?"
    echo "  Build will use existing repo assets"
else
    echo "  All assets current — no changes"
fi

echo ""
echo "=== ASSET SYNC COMPLETE ==="
echo ""
