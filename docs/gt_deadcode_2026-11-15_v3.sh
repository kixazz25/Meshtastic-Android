#!/bin/bash
# GTDEADCODE-2026-11-15 v3: sweep now wired for ALL THREE suggesters (routes | members | decls),
#   and `suggest members` no longer reports LOCAL variables as members. v2 matched any indented
#   `val`, so MapSourceManager returned arr, c, cols, ctx, dest, existing -- locals inside
#   functions. v3 takes declarations at EXACTLY ONE indent level (4 spaces), the Kotlin convention
#   for an object's own members. HEURISTIC, not a parser: a member indented differently is missed,
#   and stage 2 is what authorises anything regardless.
# ALL FIVE VERIFY KINDS PROVEN 2026-11-15 by their sentinels producing plausible LIVE counts:
#   route ConvoyDashboard=5 · screen ConvoyProfile=3 · type MapSourceManager=68 · fun convoyGraph=3
#   · member MapSourceManager.getSlotSources=11. The `screen` kind independently reproduced the
#   hand audit exactly (ConvoyMapViewer 0 code/12 comment, ConvoyApplyRadio 0/5).
#
# GTDEADCODE-2026-11-15 v2: the dead-code instrument -- SUGGEST (batch) + VERIFY (every item).
# v1 (same day) was correct but UNUSABLY SLOW and had a real bug:
#   - it ran the sentinel TWICE (the second inside a pipe, so its variables never propagated), and
#   - it did one FULL RECURSIVE GREP PER NAME -- 18 names = 18 tree walks over 5 modules.
# v2 does ONE grep pass for every name at once and attributes the hits. v1 is superseded.
#
# Fred 2026-11-15: 11:23 "one suggests functions, the other verifies a specific function" ·
#   11:25 "the suggest batch first, then the verification method on EVERY suggested item" ·
#   11:28 "backed up first then removed and rebuilt ... leave empty functioning placeholders so
#   app flows stay the same ... an empty function that says e.g. raster processing removed" ·
#   11:33 "leave the comment logger where we remove code so we can identify how much was
#   EXECUTING WITHOUT ANY PURPOSE."
#
# WHAT A BARE GREP GETS WRONG (all measured 2026-11-15):
#   1. EXACT FORM -- ConvoyRoutes.ConvoyMapViewer is DEAD while ConvoyMapViewerScreen appears 12x.
#   2. CODE vs COMMENT -- all 18 "live" hits that day were comments about REMOVED screens.
#   3. DECLARATION != REFERENCE -- a thing's own declaration is not a use of it.
#   4. MEMBER LEVEL -- MapSourceManager is retired but holds getSlotSources with live callers.
#   5. A SENTINEL -- an earlier sweep reported "live 0", impossible since the app runs. This tool
#      verifies a known-LIVE name every run and ABORTS if it comes back DEAD.
#
# USAGE
#   gt_deadcode_2026-11-15_v3.sh sweep routes | members <Object> | decls <path> [--report]
#   gt_deadcode_2026-11-15_v3.sh verify <kind> <Name...> | -f <file>
#   gt_deadcode_2026-11-15_v3.sh suggest routes | members <Object> | decls <path>
# KINDS  route | screen | type | fun | member
set -u
REPO="${GT_REPO:-$HOME/Projects/Meshtastic-Android}"
cd "$REPO" || { echo "no repo at $REPO"; exit 1; }
MODS="app core feature grouptrack-shared grouptrack-comm"
EXC='\.bak_|/build/|_patch_backups|/\.git/'
ROUTES_KT="core/navigation/src/main/kotlin/org/meshtastic/core/navigation/Routes.kt"
CONTROL=""; REPORT=""
A=(); while [ $# -gt 0 ]; do case "$1" in
  --control) CONTROL="$2"; shift 2;;
  --report) REPORT="$HOME/Downloads/gt_deadcode_report_$(date '+%Y-%m-%d_%H%M').txt"; shift;;
  *) A+=("$1"); shift;; esac; done
set -- "${A[@]:-}"
MODE="${1:-}"; shift || true
out(){ if [ -n "$REPORT" ]; then printf '%s\n' "$*" | tee -a "$REPORT"; else printf '%s\n' "$*"; fi; }

sentinel_for(){ case "$1" in route) echo ConvoyDashboard;; screen) echo ConvoyProfile;;
  type) echo MapSourceManager;; fun) echo convoyGraph;; member) echo MapSourceManager.getSlotSources;; esac; }

suggest_routes(){
  [ -f "$ROUTES_KT" ] || { echo "no $ROUTES_KT" >&2; return 1; }
  awk '/object ConvoyRoutes[[:space:]]*\{/{f=1;next} f&&/^\}/{exit} f' "$ROUTES_KT" \
    | grep -oE '(data object|object|data class)[[:space:]]+[A-Za-z0-9_]+' | awk '{print $NF}' | sort -u > /tmp/.gtd_decl
  grep -rhoE 'gtComposable<ConvoyRoutes\.[A-Za-z0-9_]+>' --include='*.kt' app 2>/dev/null \
    | grep -vE "$EXC" | sed -E 's/.*ConvoyRoutes\.([A-Za-z0-9_]+)>/\1/' | sort -u > /tmp/.gtd_reg
  comm -23 /tmp/.gtd_decl /tmp/.gtd_reg
}
suggest_members(){ local f; f=$(grep -rlE "(object|class)[[:space:]]+$1\\b" --include='*.kt' $MODS 2>/dev/null | grep -vE "$EXC" | head -1)
  [ -n "$f" ] || { echo "no declaration found for $1" >&2; return 1; }
  echo "  (declared in $f)" >&2
  # v3: EXACTLY one indent level -- an object's own members. Locals sit at 8+ spaces.
  grep -oE '^    (fun|val|var)[[:space:]]+[A-Za-z0-9_]+' "$f" | awk '{print $NF}' | sort -u | sed "s|^|$1.|"; }
suggest_decls(){ grep -rhoE '^[[:space:]]*(object|class|interface|enum class|data class)[[:space:]]+[A-Za-z0-9_]+' "$1" 2>/dev/null | awk '{print $NF}' | sort -u; }

# ---- ONE PASS over the tree for every name, then attribute -------------------------------
# $1 kind  $2 newline-separated names  -> /tmp/.gtd_res lines: NAME|CODE|COMMENT|sites
verify_batch(){
  local kind="$1" names="$2" alt n P
  case "$kind" in
    route)  alt=$(printf '%s\n' "$names" | paste -sd'|' -); P="ConvoyRoutes\\.($alt)\\b";;
    screen) alt=$(printf '%s\n' "$names" | paste -sd'|' -); P="\\b($alt)Screen\\b";;
    fun)    alt=$(printf '%s\n' "$names" | paste -sd'|' -); P="\\b($alt)[[:space:]]*\\(";;
    *)      alt=$(printf '%s\n' "$names" | sed 's/\./\\./g' | paste -sd'|' -); P="\\b($alt)\\b";;
  esac
  : > /tmp/.gtd_hits
  grep -rnoE "$P" --include='*.kt' --include='*.java' --include='*.xml' --include='*.html' --include='*.js' \
    $MODS 2>/dev/null | grep -vE "$EXC" > /tmp/.gtd_hits || true
  [ "$kind" = route ] && { grep -v "$ROUTES_KT" /tmp/.gtd_hits > /tmp/.gtd_h2 || true; mv /tmp/.gtd_h2 /tmp/.gtd_hits; }
  : > /tmp/.gtd_res
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    local base=${n##*.} code=0 comm=0 sites="" h f ln line trimmed before
    # hits whose matched text belongs to THIS name
    case "$kind" in
      route)  h=$(grep -E "ConvoyRoutes\\.${n}\$" /tmp/.gtd_hits || true);;
      screen) h=$(grep -E ":${n}Screen\$" /tmp/.gtd_hits || true);;
      fun)    h=$(grep -E ":${n}[[:space:]]*\\($" /tmp/.gtd_hits || true);;
      *)      h=$(grep -E ":$(printf '%s' "$n" | sed 's/\./\\./g')\$" /tmp/.gtd_hits || true);;
    esac
    while IFS= read -r hit; do
      [ -n "$hit" ] || continue
      f=${hit%%:*}; ln=$(printf '%s' "${hit#*:}" | cut -d: -f1)
      line=$(sed -n "${ln}p" "$f" 2>/dev/null)
      # a declaration is not a reference
      printf '%s' "$line" | grep -qE "(object|class|interface|enum class|data class|fun|val|var)[[:space:]]+${base}\\b" && continue
      trimmed=$(printf '%s' "$line" | sed 's/^[[:space:]]*//')
      case "$trimmed" in '//'*|'*'*|'/*'*) comm=$((comm+1)); continue;; esac
      before=$(printf '%s' "$line" | awk -v b="$base" 'BEGIN{FS=""} {i=index($0,b); print substr($0,1,i-1)}')
      case "$before" in *'//'*) comm=$((comm+1)); continue;; esac
      code=$((code+1)); sites="$sites;$f:$ln"
    done <<< "$h"
    printf '%s|%s|%s|%s\n' "$n" "$code" "$comm" "$sites" >> /tmp/.gtd_res
  done <<< "$names"
}

case "$MODE" in
  suggest) case "${1:-}" in routes) suggest_routes;; members) suggest_members "${2:?Object}";;
             decls) suggest_decls "${2:?path}";; *) echo "suggest: routes|members <Object>|decls <path>" >&2; exit 2;; esac;;
  verify|sweep)
    KIND="${1:-}"; shift || true
    if [ "$MODE" = sweep ]; then
      case "$KIND" in
        routes|route) NAMES=$(suggest_routes); KIND=route; SRC="ConvoyRoutes declared-but-unregistered";;
        members)      NAMES=$(suggest_members "${1:?sweep members <Object>}"); KIND=member; SRC="members of ${1}";;
        decls)        NAMES=$(suggest_decls   "${1:?sweep decls <path>}");     KIND=type;   SRC="top-level declarations in ${1}";;
        *) echo "sweep supports: routes | members <Object> | decls <path>" >&2; exit 2;; esac
      out "=== SWEEP $KIND -- stage 1 ($SRC) suggested $(printf '%s\n' "$NAMES" | grep -c .) candidate(s) ==="

    elif [ "${1:-}" = "-f" ]; then NAMES=$(grep -v '^[[:space:]]*$' "$2" | sed 's/[[:space:]]*$//')
    else NAMES=$(printf '%s\n' "$@" | grep -v '^$'); fi
    [ -n "${NAMES:-}" ] || { out "  nothing to verify"; exit 0; }
    SENT="${CONTROL:-$(sentinel_for "$KIND")}"
    out "=== gt_deadcode v3  $MODE $KIND  $(date '+%Y-%m-%d %H:%M')  $REPO ==="
    ALL=$(printf '%s\n%s\n' "$NAMES" "$SENT" | grep -v '^$' | sort -u)
    verify_batch "$KIND" "$ALL"
    SL=$(grep "^${SENT}|" /tmp/.gtd_res | cut -d'|' -f2)
    out "  SENTINEL $SENT: code=$SL"
    if [ "${SL:-0}" -eq 0 ]; then
      out ""; out "  *** ABORT: the sentinel reported DEAD. The '$KIND' pattern is wrong, or the"
      out "      sentinel is. A verifier that cannot produce a LIVE result is an echo."
      out "      NO RESULTS BELOW WOULD BE TRUSTWORTHY. Pass --control <known-live-name>."; exit 1
    fi
    out ""; out "$(printf '%-34s %6s %8s  %s' "NAME ($KIND)" "CODE" "COMMENT" "VERDICT")"
    out "-------------------------------------------------------------------------------"
    D=0; L=0
    while IFS= read -r n; do
      [ "$n" = "$SENT" ] && continue
      r=$(grep "^${n}|" /tmp/.gtd_res); c=$(echo "$r" | cut -d'|' -f2); m=$(echo "$r" | cut -d'|' -f3); s=$(echo "$r" | cut -d'|' -f4)
      if [ "$c" -gt 0 ]; then V="*** LIVE -- referenced in code"; L=$((L+1))
      elif [ "$m" -gt 0 ]; then V="DEAD -- $m comment(s) only"; D=$((D+1))
      else V="DEAD -- no reference at all"; D=$((D+1)); fi
      out "$(printf '%-34s %6s %8s  %s' "$n" "$c" "$m" "$V")"
      [ "$c" -gt 0 ] && printf '%s' "$s" | tr ';' '\n' | grep -v '^$' | sort -u | head -6 | while IFS= read -r x; do out "        $x"; done
    done <<< "$NAMES"
    out "-------------------------------------------------------------------------------"
    out "  DEAD $D   LIVE $L   of $((D+L))     sentinel $SENT verified LIVE ($SL code refs)"
    out "  Stage 1 suggests. Stage 2 (this) authorises. Never the other way round."
    out "  DEAD-with-comments-only is safe to remove; the comments are history and stay."
    [ -n "$REPORT" ] && echo "  report: $REPORT"
    ;;
  *) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 1;;
esac
