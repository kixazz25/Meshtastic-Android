#!/usr/bin/env python3
# navxref_2026-09-29.py -- GroupTrack NAVIGATION CROSS-REFERENCE v2 (click tree)
#
# Run from the repo root (C:\Users\kixaz\Meshtastic-Android):
#     python /c/Users/kixaz/Downloads/navxref_2026-09-29.py
# Writes: /c/Users/kixaz/Downloads/navigation_xref_2026-09-30.txt   (read-only scan; changes nothing)
#
# Why v2: the 09-28 xref followed nav ROUTES only. Most 2.7 interaction lives in panels drawn OVER
# the maps and shown by STATE (GRP Awareness, check-in, SELECT CART, Map Features), which a
# route scan cannot see. This version traces every clickable:
#   label -> action -> (state flag -> panel it opens) | (navigate -> screen) | (callback -> parent's action)
# and builds the indented click tree the manual's twisties follow. Each panel carries its source
# file's LAST COMMIT DATE, to compare with the build date on each screenshot (newer code = recapture).
# Heuristic: anything it cannot resolve is listed in section 5, never dropped silently.

import os, re, sys, subprocess, datetime
from collections import defaultdict

ROOTS = ["app/src/main/java/com/geeksville/mesh/convoy",
         "app/src/main/java/com/geeksville/mesh/navigation",
         "app/src/main/java/com/geeksville/mesh/ui/Main.kt"]
OUT = os.path.join(os.path.expanduser("~"), "Downloads", "navigation_xref_2026-09-30.txt")   # C:\\Users\\kixaz\\Downloads on Windows
os.makedirs(os.path.dirname(OUT), exist_ok=True)

if not os.path.isdir("app/src/main/java"):
    sys.exit("ABORT: run this from the repo root (C:\\Users\\kixaz\\Meshtastic-Android). Nothing written.")

def git(*a):
    try: return subprocess.run(["git"]+list(a), capture_output=True, text=True, timeout=30).stdout.strip()
    except Exception: return ""

# ---------- collect files ----------
files = []
for r in ROOTS:
    if os.path.isfile(r): files.append(r)
    elif os.path.isdir(r):
        for dp, _, fs in os.walk(r):
            for f in fs:
                if f.endswith(".kt"): files.append(os.path.join(dp, f).replace("\\", "/"))
files = sorted(set(files))
src = {f: open(f, encoding="utf-8", errors="replace").read() for f in files}
fdate = {f: (git("log", "-1", "--format=%cs", "--", f) or "uncommitted") for f in files}

# ---------- helpers ----------
def match_brace(s, i):
    """s[i] == '{' ; return index of the matching '}' (skips strings, chars, comments)."""
    depth, n = 0, len(s)
    while i < n:
        c = s[i]
        if c == '"':
            if s.startswith('"""', i):
                j = s.find('"""', i + 3); i = (j + 3) if j >= 0 else n; continue
            i += 1
            while i < n and s[i] != '"':
                i += 2 if s[i] == '\\' else 1
        elif c == "'" and i + 2 < n and (s[i+2] == "'" or s[i+1] == '\\'):
            j = s.find("'", i + 2); i = j if j > 0 else i
        elif s.startswith("//", i):
            j = s.find("\n", i); i = j if j >= 0 else n; continue
        elif s.startswith("/*", i):
            j = s.find("*/", i); i = (j + 2) if j >= 0 else n; continue
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return i
        i += 1
    return n - 1

def lineno(s, i): return s.count("\n", 0, i) + 1
def squash(t, n=150):
    t = re.sub(r"\s+", " ", t).strip()
    return t if len(t) <= n else t[:n-1] + "…"

STR = r'"((?:[^"\\\n]|\\.)*)"'
def label_near(body, pos):
    win = body[pos: pos + 1600]
    for pat in (r'\bText\s*\(\s*(?:text\s*=\s*)?' + STR, r'\btext\s*=\s*' + STR,
                r'\blabel\s*=\s*' + STR, r'\btitle\s*=\s*' + STR, STR):
        m = re.search(pat, win)
        if m: return m.group(1).encode().decode("unicode_escape", errors="ignore") if "\\u" in m.group(1) else m.group(1)
    back = body[max(0, pos - 400): pos]
    m = list(re.finditer(STR, back))
    return m[-1].group(1) if m else "?"

# ---------- 1. composable functions ----------
funcs = {}   # name -> dict(file, start, body, line)
for f, s in src.items():
    for m in re.finditer(r'@Composable\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public)\s+)?fun\s+(?:[\w.]+\.)?(\w+)\s*\(', s):
        name = m.group(1)
        # find the body brace after the parameter list
        i, depth = m.end(), 1
        while i < len(s) and depth:
            if s[i] == '(': depth += 1
            elif s[i] == ')': depth -= 1
            i += 1
        b = s.find("{", i)
        if b < 0 or s[i:b].strip().startswith("="): continue
        e = match_brace(s, b)
        if name not in funcs or len(s[b:e]) > len(funcs[name]["body"]):
            funcs[name] = dict(file=f, line=lineno(s, m.start()), body=s[b:e + 1], off=b)
names = set(funcs)

# ---------- 2. nav routes -> screen composable ----------
routes = {}  # RouteName -> composable
navcb = []   # (screen, its argument text in the nav host)
for f, s in src.items():
    for m in re.finditer(r'composable<(?:\w+\.)*(\w+)>\s*(?:\{[^{]*?->)?\s*\{', s):
        b = s.find("{", m.end() - 1); e = match_brace(s, b); blk = s[b:e]
        for cm in re.finditer(r'\b([A-Z]\w+)\s*\(', blk):
            c = cm.group(1)
            if c in names:
                routes[m.group(1)] = c
                j, depth = cm.end(), 1
                while j < len(blk) and depth:
                    if blk[j] == '(': depth += 1
                    elif blk[j] == ')': depth -= 1
                    j += 1
                navcb.append((c, blk[cm.end(): j]))
                break

# ---------- 3. state flag -> panels it shows ----------
flag_panels = defaultdict(set)
for fn, d in funcs.items():
    body = d["body"]
    for m in re.finditer(r'\bif\s*\(\s*!?\s*([A-Za-z_]\w*)(?:\.value)?\b[^)]*\)\s*\{', body):
        b = body.find("{", m.end() - 1); e = match_brace(body, b)
        for c in re.findall(r'\b([A-Z]\w+)\s*\(', body[b:min(e, b + 2500)]):
            if c in names and c != fn: flag_panels[m.group(1)].add(c); break
    for m in re.finditer(r'AnimatedVisibility\s*\(\s*(?:visible\s*=\s*)?([A-Za-z_]\w*)', body):
        b = body.find("{", m.end()); e = match_brace(body, b) if b > 0 else -1
        if b > 0:
            for c in re.findall(r'\b([A-Z]\w+)\s*\(', body[b:min(e, b + 2500)]):
                if c in names and c != fn: flag_panels[m.group(1)].add(c); break

# ---------- 4. callbacks: (child, param) -> parent's lambda body ----------
cb = defaultdict(list)
for fn, d in funcs.items():
    body = d["body"]
    for m in re.finditer(r'\b([A-Z]\w+)\s*\(', body):
        child = m.group(1)
        if child not in names: continue
        j, depth = m.end(), 1
        while j < len(body) and depth:
            if body[j] == '(': depth += 1
            elif body[j] == ')': depth -= 1
            j += 1
        args = body[m.end(): j]
        for a in re.finditer(r'\b(on\w+)\s*=\s*\{', args):
            bb = args.find("{", a.end() - 1); ee = match_brace(args, bb)
            cb[(child, a.group(1))].append((fn, args[bb + 1: ee]))

for scr, args in navcb:
    for a in re.finditer(r'\b(on\w+)\s*=\s*\{', args):
        bb = args.find("{", a.end() - 1); ee = match_brace(args, bb)
        cb[(scr, a.group(1))].append(("NAV HOST", args[bb + 1: ee]))

# ---------- 5. clickables ----------
CLICK = re.compile(r'(\.clickable\b|\.combinedClickable\b|\bonClick\s*=\s*\{|\bonLongClick\s*=\s*\{|detectTapGestures)')
def action_of(body, pos):
    b = body.find("{", pos)
    if b < 0 or b - pos > 200: return ""
    e = match_brace(body, b); return body[b + 1: e]

def resolve(fn, act, depth=0):
    """What does this action open?  -> list of (kind, target)"""
    out = []
    for m in re.finditer(r'\b([A-Za-z_]\w*)(?:\.value)?\s*=\s*(true|!\s*\1|[A-Za-z_]\w*\s*\.\s*\w+|\w+\s*\()', act):
        flag = m.group(1)
        if flag in flag_panels:
            for p in sorted(flag_panels[flag]): out.append(("opens", p, flag))
    for m in re.finditer(r'navigate\s*\(\s*(?:\w+\.)*(\w+)', act):
        out.append(("goes to", routes.get(m.group(1), m.group(1)), "route " + m.group(1)))
    for m in re.finditer(r'\b(on[A-Z]\w*)\s*(?:\(|\.invoke|\?\.invoke)', act):
        param = m.group(1)
        for parent, lam in cb.get((fn, param), [])[:3]:
            sub = resolve(parent, lam, depth + 1) if depth < 3 else []
            out += [(k, t, f"via {parent}.{param}") for k, t, _ in sub] or [("calls back", parent, param + " → " + squash(lam, 80))]
    for m in re.finditer(r'\b([A-Z]\w+)\s*\(', act):
        if m.group(1) in names and m.group(1) != fn: out.append(("shows", m.group(1), "direct"))
    seen, uniq = set(), []
    for o in out:
        if (o[0], o[1]) not in seen: seen.add((o[0], o[1])); uniq.append(o)
    return uniq

clicks = defaultdict(list)   # fn -> [ (line, label, action, targets) ]
for fn, d in funcs.items():
    body = d["body"]; s = src[d["file"]]
    for m in CLICK.finditer(body):
        act = action_of(body, m.start())
        clicks[fn].append(dict(line=lineno(s, d["off"] + m.start()), label=label_near(body, m.start()),
                               act=act, targets=resolve(fn, act)))

# children always drawn by a composable (not behind a flag)
children = defaultdict(list)
for fn, d in funcs.items():
    gated = set()
    for ps in flag_panels.values(): gated |= ps
    for c in re.findall(r'\b([A-Z]\w+)\s*\(', d["body"]):
        if c in names and c != fn and c not in gated and c not in children[fn]: children[fn].append(c)

# ---------- write ----------
now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
head, branch = git("rev-parse", "--short", "HEAD"), git("rev-parse", "--abbrev-ref", "HEAD")
L = []
w = L.append
w("=" * 78); w("GROUPTRACK -- NAVIGATION CROSS-REFERENCE v2 (the click tree, for the user manual)"); w("=" * 78)
w(f"Generated: {now}   branch {branch} @ {head}   files scanned: {len(files)}   composables: {len(funcs)}")
w(f"Clickables: {sum(len(v) for v in clicks.values())}   state-opened panels: {len(set().union(*flag_panels.values())) if flag_panels else 0}   routes: {len(routes)}")
w("Each panel shows [file last commit date]: a screenshot whose build date is OLDER than that date needs a recapture.")
w("")

printed = set()
def tree(fn, ind, path, how=""):
    d = funcs.get(fn)
    if not d: w(ind + f"{fn}  (not in scanned source)"); return
    tag = f"{fn}  [{os.path.basename(d['file'])}:{d['line']} · {fdate[d['file']]}]" + (f"  ← {how}" if how else "")
    if fn in printed: w(ind + tag + "  (expanded above)"); return
    w(ind + tag); printed.add(fn)
    if len(path) > 10: w(ind + "   … depth limit"); return
    for c in clicks.get(fn, []):
        w(ind + f"   ▸ “{c['label']}”  (line {c['line']})")
        for kind, tgt, why in c["targets"]:
            if kind in ("opens", "goes to", "shows") and tgt in funcs and tgt not in path:
                tree(tgt, ind + "      ", path + [tgt], f"{kind} ({why})")
            else:
                w(ind + f"      → {kind} {tgt}  ({why})")
        if not c["targets"]: w(ind + (f"      → does: {squash(c['act'], 110)}" if c['act'].strip() else "      → ⚠ no action found"))
    for ch in children.get(fn, []):
        if ch not in path and (clicks.get(ch) or children.get(ch)):
            tree(ch, ind + "   ", path + [ch], "part of this screen")

w("=== 1. THE CLICK TREE (from each screen downward) ===")
for rname, comp in sorted(routes.items()):
    w(""); w(f"### ROUTE {rname}")
    tree(comp, "", [comp])
orphans = [fn for fn in funcs if fn not in printed and clicks.get(fn)]
if orphans:
    w(""); w("### Panels with clickables not reached from any route (entered some other way — check each)")
    for fn in sorted(orphans): w(""); tree(fn, "", [fn])

w(""); w("=== 2. PANELS AND SCREENS — source file and its last commit date ===")
for fn in sorted(funcs, key=lambda n: (funcs[n]["file"], funcs[n]["line"])):
    if clicks.get(fn):
        d = funcs[fn]; w(f"{fdate[d['file']]}  {fn:<44} {d['file']}:{d['line']}  ({len(clicks[fn])} clickables)")

w(""); w("=== 3. STATE FLAGS → PANELS THEY SHOW ===")
for flag in sorted(flag_panels): w(f"{flag:<34} → {', '.join(sorted(flag_panels[flag]))}")

w(""); w("=== 4. EVERY CLICKABLE (flat) — file:line | panel | label | action ===")
for fn in sorted(clicks, key=lambda n: (funcs[n]["file"], funcs[n]["line"])):
    for c in clicks[fn]:
        w(f"{os.path.basename(funcs[fn]['file'])}:{c['line']} | {fn} | “{c['label']}” | {squash(c['act'], 120)}")

w(""); w("=== 5. UNRESOLVED — clickables with no action found (check each by hand) ===")
n = 0
for fn in sorted(clicks):
    for c in clicks[fn]:
        if not c["targets"] and not c["act"].strip():
            n += 1; w(f"{os.path.basename(funcs[fn]['file'])}:{c['line']} | {fn} | “{c['label']}” | {squash(c['act'], 120)}")
w(f"({n} unresolved)")

open(OUT, "w", encoding="utf-8", errors="replace").write("\n".join(L) + "\n")
print(f"Wrote {OUT}")
print(f"  files {len(files)} · composables {len(funcs)} · clickables {sum(len(v) for v in clicks.values())} · routes {len(routes)} · unresolved {n}")
