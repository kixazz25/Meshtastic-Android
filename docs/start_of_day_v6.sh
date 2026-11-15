#!/bin/bash
# start_of_day_v4.sh -- GroupTrack START OF DAY (SOD-2026-11-15 v4, Fred 10-20 07:26 "start google drive at the beginning of every session",
#   07:28 "leave start of day on the desktop"). Mac. Changes only: starts Google Drive if it is not running; sets the tablets' log size.
#   v3 = v2 that also runs from the DESKTOP (gt_env.sh is read from Drive's commands folder, not from beside the script). v1/v2 never run (retired).
#   v6 (2026-11-15 11:12) = ONE CHANGE: step 6's amend check now ignores files marked _APPLIED /
#     _SUPERSEDED / _DO_NOT_USE. v5's pattern flagged them as owed work the first time it ran, on an
#     amend that had just been applied. Everything else is v5.
#   v5 (2026-11-15, Fred 09:23) = THE DAY'S WORKING COPIES ARE CREATED HERE, and the things that
#     silently never get done are CHECKED here. Fred: "work has to be part of the process or it never
#     gets done. revisiting is a failure waiting to happen on the busiest craziest day which is when
#     you need the detail the most." v4 already proved the shape of the fix -- "a CHECK, not a note.
#     A check cannot go stale; a comment can." v5 applies it to the doc cycle and to the pending list.
#     New step 5: the three interim working copies (handoff, checklist, completed) -- created if absent,
#       left alone if present. Renamed into new VERSIONS at the EOD process.
#     New step 6: PENDING WORK -- unpushed commits, amend files waiting in Downloads, the spike's git
#       state, and any .SUPERSEDED / .VOID doc files that must not reach the recommit.
#     Step 3's tablet time-zone CHECK and everything else is v4, unchanged.
#   v4 (2026-11-15) = ONE CHANGE: step 3 no longer PRINTS a static note about Droid 2 being on Utah time.
#     That note went stale the moment Droid 2 was set to America/New_York (2026-11-14 21:20) -- and a clock
#     skew is exactly what it existed to warn about, so a stale version of it is worse than none. v4 READS
#     both tablets' time zones and warns ONLY IF THEY DIFFER. A check cannot go stale; a comment can.
# Run first thing:   bash ~/Desktop/start_of_day_v4.sh      (a copy also lives in Drive: GroupTrack/commands)
#                    or double-click "Start of Day.command" on the Desktop, which calls this script.
#   0. Google Drive running and My Drive reachable (started and waited for, up to 60 s)
#   1. the daily check (folders, git, commands, inbox, docs, keystore, tools) -- daily_check_v1.sh, unchanged
#   2. the repo: branch, ahead/behind origin, uncommitted files (backups left out)
#   3. each tablet: connected? its clock AND TIME ZONE (warns if the two tablets differ); the log buffer set to 16 MiB and shown (a reboot resets it)
#   4. the inbox: what is waiting for the recommit
#   5. the day's INTERIM working copies (handoff, checklist, completed) -- created if absent
#   6. PENDING WORK -- the things that never get done unless something checks for them
#   7. the Claude session: say "start of day" -- Claude asks for the folders again (folder access lasts one session)
# 0. Google Drive first -- every shared folder (inbox, commands, builds, AllDocs) lives there
if [ "$(uname -s)" = "Darwin" ]; then
  if ! pgrep -xq "Google Drive"; then echo "== 0. Google Drive is not running -- starting it"; open -a "Google Drive"; fi
  MD="$HOME/Library/CloudStorage/GoogleDrive-kixazz25@gmail.com/My Drive"; W=0
  until [ -d "$MD/GroupTrack/inbox" ] || [ $W -ge 60 ]; do sleep 2; W=$((W+2)); done
  if [ -d "$MD/GroupTrack/inbox" ]; then echo "== 0. OK Google Drive running, My Drive reachable${W:+ (waited ${W}s)}"; else echo "== 0. XX My Drive not reachable after 60 s -- open Google Drive and sign in, then run this again"; exit 1; fi
fi
ENVF="$(dirname "$0")/gt_env.sh"; [ -f "$ENVF" ] || ENVF="$HOME/Library/CloudStorage/GoogleDrive-kixazz25@gmail.com/My Drive/GroupTrack/commands/gt_env.sh"
source "$ENVF" || exit 1
echo "=================== START OF DAY -- $GT_MACHINE -- $(date '+%Y-%m-%d %H:%M') ==================="
echo; echo "== 1. Daily check"; bash "$GT_CMDS/daily_check_v1.sh"
echo; echo "== 2. Repository"
cd "$GT_REPO" || { echo "  XX repository not found: $GT_REPO"; exit 1; }
git fetch -q 2>/dev/null || echo "  XX git fetch failed (network?)"
echo "  $(git status -sb | head -1)"
U=$(git status --short | grep -v '\.bak_')
if [ -n "$U" ]; then echo "  uncommitted:"; echo "$U" | sed 's/^/    /'; else echo "  OK nothing uncommitted"; fi
echo "  last commit: $(git log --oneline -1)"
echo; echo "== 3. Tablets"
if ! command -v adb >/dev/null; then echo "  XX adb not found"; else
  TZS=""
  for pair in "Droid 1:8624SBCEDF00001789" "Droid 2:24039703201775"; do
    NAME=${pair%%:*}; SER=${pair#*:}
    if [ "$(adb -s "$SER" get-state 2>/dev/null)" = "device" ]; then
      adb -s "$SER" logcat -G 16M >/dev/null 2>&1
      TZ=$(adb -s "$SER" shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')
      echo "  OK $NAME ($SER)  clock $(adb -s "$SER" shell date '+%H:%M' 2>/dev/null | tr -d '\r')  tz ${TZ:-?}  log buffer: $(adb -s "$SER" logcat -g 2>/dev/null | head -1 | sed 's/^.*ring buffer is //')"
      [ -n "$TZ" ] && TZS="$TZS$TZ
"
    else
      echo "  XX $NAME ($SER) not connected -- plug it in, then run this again (the log buffer is not set)"
    fi
  done
  # SOD-2026-11-15 v4: a CHECK, not a note. The tablets must share a time zone or a side-by-side
  # produces differences that read as port defects -- the daily map recipe is named by DAY
  # (grouptrack_maps_<day>.gtmaps) and step 22 is about ride time zones.
  NTZ=$(printf '%s' "$TZS" | grep -c . 2>/dev/null); DTZ=$(printf '%s' "$TZS" | sort -u | grep -c .)
  if [ "${NTZ:-0}" -ge 2 ] && [ "${DTZ:-1}" -gt 1 ]; then
    echo "  XX TIME ZONES DIFFER between the tablets:"; printf '%s' "$TZS" | sort -u | sed 's/^/       /'
    echo "     Fix before any side-by-side -- set it in Settings on the tablet."
  elif [ "${NTZ:-0}" -ge 2 ]; then
    echo "  OK both tablets on the same time zone ($(printf '%s' "$TZS" | sort -u | head -1))"
  fi
fi
echo; echo "== 4. Inbox ($GT_INBOX)"
N=$(find "$GT_INBOX" -maxdepth 1 -type f ! -name '.*' | wc -l | tr -d ' ')
echo "  $N file(s) waiting for the recommit"; find "$GT_INBOX" -maxdepth 1 -type f ! -name '.*' -exec basename {} \; | sort | head -20 | sed 's/^/    /'

# SOD-2026-11-15 v5 step 5: THE DAY'S WORKING COPIES. The doc cycle (Fred 09:21): copy the handoff and
# the living checklist into interims at the start of the day, keep a daily completed list, MOVE items
# between the three all day, and rename the interims into new VERSIONS at the EOD process. Creating them
# here is the difference between a process and an intention.
echo; echo "== 5. The day's interim working copies"
DAY=$(date '+%Y-%m-%d'); DOCS="$GT_REPO/docs"
HI="$DOCS/grouptrack_handoff_interim_$DAY.html"
CI="$DOCS/grouptrack_living_checklist_interim_$DAY.html"
DI="$DOCS/GroupTrack_2.8_completed_interim_$DAY.html"
if [ ! -d "$DOCS" ]; then echo "  XX no docs folder at $DOCS"; else
  if [ -f "$HI" ]; then echo "  OK handoff interim exists  ($(wc -c <"$HI" | tr -d ' ') bytes) -- APPEND to it, do not recreate"
  else
    SRC=$(ls -t "$DOCS"/grouptrack_handoff_*_v*.html 2>/dev/null | head -1)
    if [ -n "$SRC" ]; then cp "$SRC" "$HI"; echo "  created handoff interim from $(basename "$SRC")"
    else echo "  XX no released handoff to copy"; fi
  fi
  if [ -f "$CI" ]; then echo "  OK checklist interim exists ($(wc -c <"$CI" | tr -d ' ') bytes) -- APPEND to it, do not recreate"
  else
    SRC=$(ls -t "$DOCS"/grouptrack_living_checklist_*_v*.html 2>/dev/null | head -1)
    if [ -n "$SRC" ]; then cp "$SRC" "$CI"; echo "  created checklist interim from $(basename "$SRC")"
    else echo "  XX no released checklist to copy"; fi
  fi
  if [ -f "$DI" ]; then echo "  OK completed list exists    ($(wc -c <"$DI" | tr -d ' ') bytes)"
  else
    printf '%s\n' '<!doctype html><html lang="en"><head><meta charset="utf-8">' \
      "<title>GroupTrack — COMPLETED $DAY</title></head><body>" \
      "<h1>COMPLETED $DAY</h1>" \
      '<p>One row per item that closed today, with its EVIDENCE (a measurement, a commit, a device' \
      'observation) and its commit number. Items ARRIVE here by being MOVED out of the handoff interim' \
      'or the checklist interim -- never by being retyped. Becomes the day&rsquo;s completed-activity' \
      'record at the EOD process, so that pruning never loses the fact that something was addressed.</p>' \
      '</body></html>' > "$DI"
    echo "  created the day's completed list (empty)"
  fi
fi

# SOD-2026-11-15 v5 step 6: PENDING WORK. Every item here is something that went un-done in a real
# session because it lived in conversation instead of in a check.
echo; echo "== 6. Pending work"
cd "$GT_REPO" 2>/dev/null || true
AH=$(git rev-list --count @{u}..HEAD 2>/dev/null)
if [ -n "$AH" ] && [ "$AH" -gt 0 ]; then echo "  XX $AH commit(s) NOT PUSHED:"; git log --oneline @{u}..HEAD | sed 's/^/       /'
else echo "  OK nothing unpushed"; fi
# SOD-2026-11-15 v6: the v5 pattern matched amend_*.txt including files already marked
# APPLIED or SUPERSEDED, so it reported owed work on the very first morning it ran. A check that
# cries wolf gets ignored, which is worse than no check (Fred 09:23: work belongs in the process).
A=$(ls -1 "$HOME/Downloads"/amend_*.txt 2>/dev/null | grep -viE '_APPLIED|_SUPERSEDED|_DO_NOT_USE')
if [ -n "$A" ]; then echo "  XX amend message(s) waiting in Downloads -- a commit is owed an amend:"; echo "$A" | sed 's|.*/|       |'
else echo "  OK no amend waiting"; fi
SPK="$HOME/AndroidStudioProjects/GroupTrackSpike"
if [ -d "$SPK" ]; then
  if [ -d "$SPK/.git" ]; then
    SD=$(cd "$SPK" && git status --short | wc -l | tr -d ' ')
    echo "  OK spike under git ($(cd "$SPK" && git log --oneline -1)) -- ${SD} uncommitted"
    [ "$SD" -gt 0 ] && echo "     NOTE the spike is FROZEN as the iPad oracle; changes there want explaining"
  else echo "  XX the SPIKE IS NOT UNDER GIT -- it is the only copy of the working iPad map layer AND the _data/cert certification workspace"; fi
fi
V=$(ls -1 "$GT_REPO/docs"/*SUPERSEDED* "$GT_REPO/docs"/*VOID* 2>/dev/null | wc -l | tr -d ' ')
[ "${V:-0}" -gt 0 ] && echo "  XX ${V} superseded/void doc file(s) in docs/ -- must NOT reach the recommit" || echo "  OK no superseded/void docs"
if command -v xcrun >/dev/null; then
  B=$(xcrun simctl list devices booted 2>/dev/null | grep -c Booted)
  [ "${B:-0}" -eq 0 ] && echo "  -- no booted simulator (iosSimulatorArm64Test HANGS SILENTLY with none; cost 13 min on 11-15)" || echo "  OK ${B} simulator booted"
fi

echo; echo "== 7. Claude: in the chat say \"start of day\" -- folder access (repo, Downloads, Desktop, Drive GroupTrack + GroupTrack_docs) is asked for again each session."
echo "=================== START OF DAY DONE ==================="

