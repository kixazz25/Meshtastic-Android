#!/bin/bash
# GTIOSLONG9-2026-11-15: the nine iosTest classes the 60s sweep capped. ONE gradle invocation,
# NINE filters, NO timeout -- all nine are long because of input size, not because they are stuck
# (measured: ~600 MB DB snapshots, a 543 MB copy, or 70 MB of import fixtures).
# Progress signal is NOT gradle's event stream (it stays empty until the task ends). It is the
# files appearing in $SPIKE/_data/cert -- watch those, or ask Claude to read them.
set -u
cd "$HOME/Projects/Meshtastic-Android" || exit 1
L="$HOME/Downloads/gt_ios_cert_long9_2026-11-15.log"
P=com.grouptrack.shared.cert
BOOTED=$(xcrun simctl list devices booted | grep -c Booted)
[ "$BOOTED" -eq 0 ] && { echo "ABORT: no booted simulator -- the task would hang silently."; exit 1; }
echo "== nine long certs, uncapped, one invocation. Started $(date '+%H:%M:%S'). DO NOT KILL."
echo "== log: $L"
./gradlew :grouptrack-shared:iosSimulatorArm64Test --rerun --continue --console=plain \
  --tests "$P.CertMacTest"              --tests "$P.CertMapMacTest" \
  --tests "$P.GtCert15bMacTest"         --tests "$P.GtCertGeomMacTest" \
  --tests "$P.GtCertImport15eMacTest"   --tests "$P.GtCertOsm15gMacTest" \
  --tests "$P.GtCertRider15cMacTest"    --tests "$P.GtCertTrack15dMacTest" \
  --tests "$P.GtCertTrail15fMacTest" > "$L" 2>&1
echo "== gradle exit=$?  finished $(date '+%H:%M:%S')"
R=grouptrack-shared/build/test-results/iosSimulatorArm64Test
echo "== per-class result:"
for f in "$R"/*.xml; do
  [ -e "$f" ] || continue
  n=$(basename "$f" .xml | sed "s/TEST-iosSimulatorArm64Test\.$P\.//")
  printf "   %-30s %s\n" "$n" "$(grep -ohE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "$f" | head -1)"
done
echo "== full log: $L   (Claude can read the XMLs and failure text directly)"
