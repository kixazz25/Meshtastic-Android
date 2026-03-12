#!/bin/bash
# convoy_commit_master.sh — P-003
# Pulls master config + apply list from phone Downloads,
# copies to app/src/main/assets/, commits and pushes to repo.
#
# Place in: C:\Users\kixaz\Meshtastic-Android\
# Usage: bash convoy_commit_master.sh

export PATH=$PATH:/c/Users/kixaz/AppData/Local/Android/Sdk/platform-tools

cd /c/Users/kixaz/Meshtastic-Android

ASSETS="app/src/main/assets"
PHONE_MASTER="/sdcard/Download/master_config.json"
PHONE_APPLY="/sdcard/Download/convoy_apply_list.json"

echo "=== CONVOY MASTER CONFIG COMMIT ==="
echo ""

# Check device connected
DEVICE=$(adb devices | grep -v "List" | grep "device" | awk '{print $1}' | head -1)
if [ -z "$DEVICE" ]; then
    echo "ERROR: No device connected. Connect phone via USB and enable USB debugging."
    exit 1
fi
echo "Device: $DEVICE"
echo ""

# Pull master_config.json
echo "Pulling master_config.json from phone..."
adb -s "$DEVICE" shell "cat /sdcard/Download/master_config.json" > "$ASSETS/master_config.json"
if [ $? -ne 0 ] || [ ! -s "$ASSETS/master_config.json" ]; then
    echo "ERROR: Could not pull master_config.json"
    echo "Run capture on the phone first."
    exit 1
fi
echo "✓ master_config.json pulled"

# Pull convoy_apply_list.json
echo "Pulling convoy_apply_list.json from phone..."
adb -s "$DEVICE" shell "cat /sdcard/Download/convoy_apply_list.json" > "$ASSETS/convoy_apply_list.json"
if [ $? -ne 0 ] || [ ! -s "$ASSETS/convoy_apply_list.json" ]; then
    echo "ERROR: Could not pull convoy_apply_list.json"
    exit 1
fi
echo "✓ convoy_apply_list.json pulled"

echo ""
echo "=== COMMITTING TO REPO ==="

git add "$ASSETS/master_config.json" "$ASSETS/convoy_apply_list.json"

DATE=$(date +"%Y-%m-%d")
git commit -m "Update master radio config + apply list — captured $DATE"

if [ $? -ne 0 ]; then
    echo "ERROR: git commit failed"
    exit 1
fi

git push origin feature/convoy-event-ride

if [ $? -eq 0 ]; then
    echo ""
    echo "=== DONE ==="
    echo "Master config + apply list committed and pushed."
    echo "Next build will bundle both files automatically."
else
    echo "ERROR: git push failed — check your connection and credentials"
fi
