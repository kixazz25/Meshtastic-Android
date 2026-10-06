#!/bin/bash
# site_traffic_2026-10-03_v5.sh — grouptrack.org engagement report (read-only). SITE-2026-10-03 E/F
#   1. DAILY      people on the homepage, and the featured ad that day
#   2. TAGLINES   per ad: shown by auto-advance, vignette taps, seen at the bottom (second chance), How-it-works exits, Google Play taps
#   3. PATHS      the most common journeys: featured ad -> (auto) moves / vignettes tapped -> [bottom: ad] -> How it works / PLAY
# Real browsers only (robots, scanners, Facebook's preview robot left out). One device counted once per day per event.
# No cookies; nothing personal is shown. Run from Git Bash:  bash docs/site_traffic.sh
KEY=~/.ssh/convoy-api-key-2.pem
HOST=ec2-user@34.224.89.217
ssh -i "$KEY" "$HOST" 'sudo sh -c "for f in \$(ls -tr /var/log/httpd/access_log*); do case \$f in *.gz) zcat \$f;; *) cat \$f;; esac; done"' 2>/dev/null \
 | grep "Mozilla" \
 | grep -viE "bot|spider|crawl|curl|python|wget|facebookexternalhit|preview|monitor|uptime|headless" \
 | awk '
   BEGIN {
     T["panel-01"]="Create a ride."
     T["panel-02"]="Open a ride in one tap."
     T["panel-03"]="Check in. Your radio is set."
     T["panel-04"]="Riding together starts with a ride."
     T["panel-05"]="Turn a route into a ride."
     T["panel-06"]="Turn a track into a ride."
     T["panel-07"]="One ride package."
     T["panel-08"]="Tell it where to go. It builds the routes."
     T["panel-09"]="Every map you need, along your route, offline."
     T["panel-10"]="Bring your riding partners with you."
     T["panel-11"]="Five routes in 30 seconds. One tap makes it a ride."
   }
   function arg(s,k,   v){ v=s; if (index(v,k"=")==0) return ""; sub(".*[?&]"k"=","",v); sub(/&.*/,"",v); return v }
   function name(a){ return (a in T)?T[a]:a }
   { split($4,p,":"); day=substr(p[1],2); dev=day" "$1; req=$7 }
   req ~ /^\/(index\.html)?$/ || req ~ /^\/\?/  { if(!((dev) in home)){home[dev]=1; people[day]++; if(!(day in days)){days[day]=1}} }
   req ~ /^\/t\.php\?/ || req ~ /^\/go\.php\?/ {
       d=arg(req,"day"); if (d!="" && !(dev in start)) { start[dev]=d; feat[day" "d]++ }
   }
   req ~ /^\/t\.php\?ev=vig/  { a=arg(req,"ad"); k=dev" vig "a; if(!(k in once)){once[k]=1; vig[a]++}; path[dev]=path[dev]" > "name(a) }
   req ~ /^\/t\.php\?ev=auto/ { a=arg(req,"ad"); k=dev" auto "a; if(!(k in once)){once[k]=1; auto[a]++}; path[dev]=path[dev]" > (auto) "name(a) }
   req ~ /^\/t\.php\?ev=seen2/ { a=arg(req,"ad"); k=dev" seen2 "a; if(!(k in once)){once[k]=1; seen2[a]++}; path[dev]=path[dev]" > [bottom: "name(a)"]" }
   req ~ /^\/t\.php\?ev=how/  { a=arg(req,"ad"); k=dev" how "a; if(!(k in once)){once[k]=1; how[a]++}; path[dev]=path[dev]" > How it works" }
   req ~ /^\/go\.php\?/       { a=arg(req,"ad"); k=dev" play "a; if(!(k in once)){once[k]=1; play[a]++}; path[dev]=path[dev]" > PLAY ("arg(req,"at")")" }
   END {
     print "== 1. DAILY"
     for (d in days) {
       best=""; bn=0; for (k in feat){ split(k,q," "); if(q[1]==d && feat[k]>bn){bn=feat[k]; best=q[2]} }
       printf "%s  people %4d   featured: %s\n", d, people[d], (best!=""?"\""name(best)"\"":"(no taps recorded)") | "sort -t/ -k3,3n -k2,2M -k1,1n | tail -30"
     }
     close("sort -t/ -k3,3n -k2,2M -k1,1n | tail -30")
     print ""; print "== 2. TAGLINES (all days in the logs)"
     printf "  %-55s %6s %8s %7s %7s %6s\n", "tagline", "auto", "vignette", "bottom", "how-it", "PLAY"
     for (a in T) printf "  %-55s %6d %8d %7d %7d %6d\n", "\""T[a]"\"", auto[a]+0, vig[a]+0, seen2[a]+0, how[a]+0, play[a]+0 | "sort -k1,1"
     close("sort -k1,1")
     print ""; print "== 3. TOP PATHS (featured ad first)"
     for (dv in path) { pth="\""name(start[dv])"\""path[dv]; cnt[pth]++ }
     for (pth in cnt) printf "  %4d  %s\n", cnt[pth], pth | "sort -rn | head -15"
     close("sort -rn | head -15")
   }'
