# GroupTrack — Lead-Cart Track Revision: Demolition + Rebuild Plan
**2026-06-18 · checklist item [2.1] · branch feature/convoy-event-ride**
Authority: `GroupTrack_LeadTrackReplacement_Spec.docx` (May 31) + Fred's 06-18 statement.
Status: SETTLED design, NOT started. Must-ship V2.5.

> This doc has two halves on purpose. **Part 1** is the demolition — identify and
> remove every existing track-recording method and process. **Part 2** is the
> rebuild — the new one-lead / one-track model. They are kept together so each
> removal is traceable to what replaces its job. Do not remove a flow without
> knowing what takes over.

---

## 0 · Why (the problem being solved)

The current convoy lead-cart tracking is a hodgepodge of evolved lead-cart rules
plus position projection. It is unreliable. Concretely:

- The lead cart broadcasts its position over the Meshtastic radio at best every
  ~5 seconds. The code reconstructs the lead's path from that radio data, and
  fills gaps with projection.
- **Phantom carts report in when a rebroadcast is made** — other carts post
  positions that cannot be explained. The result is spurious track segments and
  phantom lines (worst on switchbacks).
- Three parallel track-drawing flows compete to write the line, so with multiple
  carts you get multiple overlapping/contradictory tracks instead of one.

Decision: **gut the existing track methodology and restart**, rather than patch.

---

## PART 1 — DEMOLITION: identify and remove all previous track-recording methods

The goal of Part 1 is a complete, verified inventory of every code path that
records, reconstructs, projects, or draws a *live convoy track*, followed by their
removal. This is distinct from the **displayed DB tracks** (your saved/imported
tracks toggled on via Work-with-Artifacts) — those use `loadTracks` / `trackLayer`
and are NOT in scope here. Be precise about that boundary throughout.

### 1.1 Discovery first (before removing anything)

Removal targets must be *found*, not assumed. JS has no compiler safety net and
Kotlin↔JS calls cross by string, so use multiple lenses:

1. **Regenerate a fresh cross-reference** (`field_crossref_raw.txt` /
   where-used / function-universe) — the stale ones will miss recent drift.
2. **Grep both languages** for the live-track identifiers (list below), in
   `*.kt` AND in the two map HTMLs.
3. **Trace live on device** — a 2-cart field capture (or a controlled replay) to
   confirm which flows actually fire and in what order, so nothing live is removed
   blind.

### 1.2 Known removal targets (the catalog — confirm + extend via discovery)

**The three parallel live-track flows** (the core of the hodgepodge):
- `leadTrackSegments` — the lead cart's reconstructed-from-broadcast segments.
- `gpsTrailSegments` — a live GPS-trail buffer.
- `routeTrailSegments` — route-trail segments folded into the same draw.
- The `trackLeadOnly` filter that selects among them
  (~ConvoyScreen.kt 345-350: `val activeSegments = if (trackLeadOnly) rawSegments
  else (rawSegments + routeTrail)`).

**The live draw path:**
- `drawTrack(...)` as called for the LIVE lead/GPS/route trail (the
  `evaluateJavascript("drawTrack(...)")` calls fed by the segment lists above).
  NOTE: the `drawTrack`/`clearMarkers` "not defined" console warnings are a
  pre-existing load-race and are separate noise — but the `drawTrack` *function
  itself*, as the live-trail renderer, is in scope for removal/replacement.

**The lead-lock / convoy-state engine (ConvoyEngine.kt):**
- `evaluateLeadLock()` — lead selection/lock logic.
- `tick() → compute() → assignLeadTail()` — the per-cycle convoy-state computation
  that assigns lead/tail and drives the trail.
- `lockedLeadNodeId`, `_leadLockedFlag` — lead-lock state.
- **Known defect to retire with it:** tick-oscillation — two tick entries per cycle
  ~170 ms apart, one with the lead set and one without. The rebuild should not
  inherit this; the new lead handling must work cleanly inside (or alongside) tick,
  not fight it.

**Any projection / proxy substitution logic** that synthesizes positions between
broadcasts or substitutes a proxy cart's position for the lead — this is the source
of the phantom check-ins on rebroadcast. Identify all of it in discovery and remove
it; the new model does not project.

**Per-node colored multi-segment track rendering** — the old model colored segments
per node (each cart's color along the segments it last passed). The new model is one
trail, so the per-node segment-coloring machinery for the *live* track is removed.

### 1.3 Removal method

- Remove in a sequence that keeps the app buildable and the device usable between
  steps (don't leave the convoy map non-compiling across a long build).
- Kotlin removals: lean on the compiler + the fresh xref to bound each cut.
- JS removals: grep-bounded only, so device-test after each — confirm the live
  track stops drawing from the old path and nothing else (displayed DB tracks,
  node markers, route lines) regresses.
- Keep displayed-DB-track rendering (`loadTracks` / `trackLayer`) intact — out of
  scope, must not be touched.

### 1.4 Part 1 done-when

- The three parallel flows, the live `drawTrack` path, the lead-lock/tick lead
  machinery, and all projection/proxy logic are removed.
- The convoy map builds and runs; node markers and displayed DB tracks still work.
- No live convoy track is drawn at all yet (clean slate for Part 2).
- The removal is committed as its own green step before Part 2 begins.

---

## PART 2 — REBUILD: one lead cart, one lead track

### 2.1 Principle

**One lead cart. One lead track.** Everything else is a position, not a track.

### 2.2 The model

1. **Track only the lead cart**, built from its broadcasts (not projection).
2. **Snap-2, 100-yard radius:** snap the lead's broadcast position onto known
   trail/track geometry whenever it is within 100 yards of a known trail/track.
   This corrects broadcast/GPS scatter onto real trail lines. (Distinct from the
   route-builder snap-2, which snaps route-drawing vertices to trail geometry.)
3. **Every other cart appears at its current position only — it is NOT tracked.**
   A live present-position marker, no per-cart track line, no projected path. This
   removes the phantom-cart problem at its root.
4. **Each cart individually tracks its own progress** and, as it overtakes the
   lead cart's positions, **removes the lead cart's path from its own map and
   replaces it with its own device GPS**, recorded every second (the same GPS
   stream that drives follow-now and GPX recording).

### 2.3 Net result + rationale (the accuracy gradient)

The result is **one continuous trail originating from the lead that improves in
accuracy as carts cover the ground in the lead's wake.**

- The lead broadcasts at best every 5 seconds (a radio constraint), so the trail
  *ahead* of you is inherently coarse — 5-second gaps, snapped/interpolated. That
  is the best you can know about ground not yet driven.
- Every cart records its own GPS every second, so the trail *behind* you — the
  "rear-view mirror" — is progressively refined to 1-second truth as wheels
  physically cover the ground.
- Accuracy gradient: coarse 5-second lead broadcast at the front → refined
  1-second GPS truth at the rear (verified by carts driving it). The trail you
  drive *over* is always better-known than the trail *ahead* — exactly right for
  a convoy. The model never claims more precision ahead than the 5-second radio
  allows, and continuously upgrades the trail behind as real wheels cover it.

### 2.4 Implementation shape

- One growing **lead-position polyline**, gated on `lockedLeadNodeId` (the lead
  identity is kept; the old multi-flow reconstruction is not).
- `pushTrackToMap` is net-new (0 references today) — the single entry point that
  draws/updates the one trail.
- Each cart owns its own wake: as it passes the lead's recorded positions, its
  per-second GPS replaces those positions on its map.

### 2.5 OPEN DECISION — confirm before building

When an overtaking cart replaces the lead's path with its own GPS, is the
replacement:
- **(a) Unconditional** — each cart always owns its own track behind it, replacing
  the lead segment regardless; or
- **(b) Snap-gated** — an off-trail cart beyond a threshold does NOT overwrite, so
  a wild detour off the trail does not corrupt the shared composite trail.

An earlier composite-concept note included an off-trail guard, which argues for (b).
Confirm against the May-31 spec / with Fred before implementing.

### 2.6 Part 2 done-when

- Only the lead cart produces a track; all other carts are position markers only.
- The lead trail snaps to known trails within 100 yards.
- Each cart's per-second GPS replaces the lead's path behind it; the composite is
  one continuous trail that is visibly truer toward the rear.
- No phantom carts on rebroadcast.
- Field-tested with ≥2 carts; committed.

---

## 3 · Sequencing

1. Discovery (1.1) — fresh xref, dual-language grep, 2-cart capture.
2. Demolition (Part 1) — remove old flows; build + device-prove; commit.
3. Rebuild (Part 2) — new lead-trail model; build + 2-cart field-prove; commit.
4. Update release notes + manual (the live-track behavior changes for testers).

Sequenced after the universal-search build and the routes/planning cleanup, per
the broader V2.5 order — but it is must-ship V2.5, not deferred.

---

## 4 · Scope boundary reminders

- **In scope:** the live convoy track (lead/GPS/route segment flows, projection,
  per-node live coloring, lead-lock/tick lead machinery).
- **Out of scope (do not touch):** displayed/imported DB tracks
  (`loadTracks` / `trackLayer`), node position markers, route lines, the
  route-builder snap-2.
