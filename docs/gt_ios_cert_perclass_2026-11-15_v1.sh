#!/bin/bash
# GTIOSPERCLASS-2026-11-15: run each grouptrack-shared iosTest class ON ITS OWN with a hard
# timeout, so a HANG is a recorded result rather than a dead terminal. A batch run cannot do
# this: a hang emits no events, so it cannot be attributed to a class.
# macOS has no GNU `timeout`; perl's alarm is used instead (exit 142 = SIGALRM = HANG).
set -u
REPO="$HOME/Projects/Meshtastic-Android"
cd "$REPO" || { echo "no repo at $REPO"; exit 1; }
LIMIT=${LIMIT:-60}
REPORT="$HOME/Downloads/gt_ios_cert_perclass_report_2026-11-15.txt"
DIR="grouptrack-shared/src/iosTest/kotlin/com/grouptrack/shared/cert"

BOOTED=$(xcrun simctl list devices booted | grep -c Booted)
{ echo "GTIOSPERCLASS 2026-11-15  limit=${LIMIT}s  booted simulators=$BOOTED"; echo; } | tee "$REPORT"
[ "$BOOTED" -eq 0 ] && { echo "ABORT: no booted simulator -- the task would hang silently." | tee -a "$REPORT"; exit 1; }

CLASSES=$(ls "$DIR"/*.kt 2>/dev/null | xargs -n1 basename | sed 's/\.kt$//' | sort)
TOTAL=$(echo "$CLASSES" | wc -l | tr -d ' ')
P=0; F=0; H=0; n=0
for c in $CLASSES; do
  n=$((n+1))
  printf "[%2d/%s] %-34s " "$n" "$TOTAL" "$c" | tee -a "$REPORT"
  OUT=$(perl -e 'alarm shift; exec @ARGV' "$LIMIT" \
        ./gradlew :grouptrack-shared:iosSimulatorArm64Test --rerun \
        --tests "com.grouptrack.shared.cert.$c" --console=plain -q 2>&1)
  rc=$?
  if [ $rc -eq 142 ] || [ $rc -eq 14 ]; then
    H=$((H+1)); echo "*** HANG (${LIMIT}s) ***" | tee -a "$REPORT"
  elif [ $rc -eq 0 ]; then
    P=$((P+1)); echo "PASS" | tee -a "$REPORT"
  else
    F=$((F+1)); echo "FAIL rc=$rc" | tee -a "$REPORT"
    echo "$OUT" | grep -aE 'FAILED|AssertionError|Exception|IllegalState|error:|No tests found' \
      | head -5 | sed 's/^/           /' | tee -a "$REPORT"
  fi
done
{ echo; echo "PASS $P   FAIL $F   HANG $H   of $TOTAL"; echo "report: $REPORT"; } | tee -a "$REPORT"
