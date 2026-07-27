# Convoy Entry/Restore Design Map (before Stage 5 patching)
**2026-06-16 · base commit `fe8a0849a` (Stages 1-3 done: planning on shared fn)**

---

## THE PROBLEM (why convoy entry is broken)
Convoy on entry does GPS `setView` (ConvoyScreen onPageFinished ~656-660) → that move fires onViewportChanged → writes `lastViewport*` = the GPS-centered frame. So:
1. **lastViewport gets GPS-polluted on entry** (the stale-value trap Fred flagged). Anything reading it before a user pan gets a GPS point, not the work frame.
2. **Convoy has NO frame restore** — unlike planning (which does `fitBounds(pmSeed.bbox)` @693), convoy always lands on GPS, never the saved frame.
3. Convoy's JSON saves **no bbox** anyway (Stage 4 deferred it), so there's nothing to restore yet.

## FRED'S KEY INSIGHT (the fix direction)
**Seed `lastViewport*` from the JSON bbox on entry/restore — BEFORE any draw fires.** Then lastViewport is a real frame from the moment of restore, not GPS-polluted. This makes lastViewport trustworthy, which in turn makes saving `BBox(lastViewport*)` safe. The seed + the restore are the same fix.

---

## CONVOY PERSISTENCE MODEL (settled, from memory)
- **SESSION-ONLY.** Cold launch (fresh app start) → GPS fresh (no restore). In-session map-switch back → restore saved frame.
- Mechanism: cold launch DELETES convoy_panel.json (deleteMap on splash — NOT on base, must add). So:
  - **Cold launch:** convoy_panel.json absent/empty → no bbox → GPS setView (current behavior). Correct.
  - **In-session re-entry:** convoy_panel.json has bbox (saved during the session) → restore frame + seed lastViewport. Correct.
- So the SAME onPageFinished logic works for both: "if cmSeed.bbox != null → restore + seed; else → GPS." The deleteMap-on-cold-launch is what makes bbox absent on cold launch. **deleteMap is the gate between the two behaviors.**

## THE TWO WEBVIEW PATHS (the drift to unify)
Convoy has TWO WebView setups, each with its own onViewportChanged:
- **(A) REUSE path** — ConvoyScreen ~533 `val existing = viewModel.persistentWebView` → onVC@~569 → calls `processViewport` ✅ (already on shared fn, 573 fixed). Used on LATER entries (persistent WebView exists).
- **(B) CREATE path** — ConvoyScreen ~606 `WebView(ctx).apply{}` → onVC@~735 → INLINE draw ~730-815 (the old copy). Used on FIRST entry (no persistent WebView yet); stored to persistentWebView @~882.
- onPageFinished @626 is in the CREATE path (the WebView being built).

**Unify:** delete the inline draw (730-815); make path B's onViewportChanged call `processViewport` JSON-fed like path A. Then both paths draw identically through the shared fn.

---

## THE DESIGN — convoy entry/restore, step by step

### On entry (onPageFinished, both paths should behave the same):
```
1. Page loads (map HTML ready, JS draw fns exist).
2. Read cmSeed = readMap("convoy")   [already done @186 at composable top]
3. IF cmSeed.bbox != null  (in-session re-entry — saved frame exists):
     a. SEED lastViewport* = cmSeed.bbox.south/west/north/east   ← Fred's seed, BEFORE draw
     b. fitBounds(cmSeed.bbox)                                     ← restore the frame visually
     c. drawPersistedState("convoy", webView, context)            ← deterministic artifact restore
   ELSE  (cold launch — convoy_panel.json was deleted on splash):
     a. GPS setView (current behavior @656-660)                   ← fresh GPS
     b. (the GPS move fires onViewportChanged → draws via processViewport;
         lastViewport gets the GPS frame, which is correct for a fresh start)
```

### Why seed lastViewport in the restore branch (3a):
- The fitBounds(cmSeed.bbox) will eventually fire onViewportChanged and update lastViewport — BUT there's a window before that where lastViewport is stale/0.0. Seeding it explicitly (3a) closes that window: lastViewport is correct immediately, so any save firing before the fitBounds-moveend gets the right frame.
- Also, drawPersistedState (3c) uses the JSON bbox directly (not lastViewport), so the artifact restore is correct regardless. The seed is belt-and-suspenders for OTHER lastViewport readers (the save, route-snap queries @542).

### On save (saveConvoyState):
- After the seed design, `BBox(lastViewportSouth, ...)` is SAFE to save — lastViewport is seeded-from-JSON on restore and updated-by-pan during use, never GPS-polluted (because cold launch has no bbox → no restore → GPS is correct there; re-entry seeds the real frame).
- So Stage 4's bbox save folds back in here, AFTER the seed is in place.

### deleteMap (cold-launch clear) — the session-only gate:
- Add `MapStateStore.deleteMap("convoy")` at app splash/cold-launch (NOT on map entry — only true cold start).
- Effect: cold launch → convoy_panel.json gone → cmSeed.bbox null → GPS branch. In-session → file present → restore branch.
- ⚠️ deleteMap doesn't exist in MapStateStore yet — add `fun deleteMap(mapKey) { fileFor(mapKey).delete() }`.
- ⚠️ Must fire on COLD LAUNCH only, not every convoy entry (else re-entry wouldn't restore). Splash/Application onCreate, gated to once-per-process.

---

## STAGE 5 BUILD ORDER (small tested steps)
1. **Clobber fix** (Stage 4a — rowsFor from CheckedIds, no bbox yet). Build, JSON-pull test: all types persist on panel switch. [SAFE, isolated]
2. **Unify WebView paths:** delete inline draw 730-815; path B onVC calls processViewport JSON-fed. Build, test: convoy draws same on first entry and re-entry (no more order-dependent divergence).
3. **Entry restore + lastViewport seed:** onPageFinished → if cmSeed.bbox: seed lastViewport + fitBounds + drawPersistedState; else GPS. Build, test: re-entry restores frame+artifacts.
4. **Save bbox:** now safe — saveConvoyState saves BBox(lastViewport*). Build, JSON-pull test: bbox is real (matches work frame, not GPS point).
5. **deleteMap on cold launch:** add deleteMap + splash call. Test: cold launch → GPS; in-session re-entry → restore.
6. Commit convoy unified.

## OPEN QUESTIONS for Stage 5
- **Q-A:** Where exactly is the cold-launch hook? (Splash screen / Application.onCreate / a once-per-process flag.) Need to find convoy's app-entry point.
- **Q-B:** Does the GPS setView @656 also fire when restoring (we want it SKIPPED when bbox exists)? The if/else in onPageFinished handles it — confirm the GPS block is in the else.
- **Q-C:** The two onViewportChanged (569 + 735) — after unifying, is there ONE WebView or genuinely two instances? If persistentWebView is reused, only one survives; confirm we're not maintaining two.
- **Q-D:** route-snap queries @542-543 read lastViewport directly — the seed helps them too, but verify they're correct post-seed (separate from persistence but same variable).
