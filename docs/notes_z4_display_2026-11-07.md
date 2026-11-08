# 2.8 END-OF-RELEASE ITEM -- map blank at z4 and below (Droid 1), online and offline
Recorded 2026-11-07 17:45 (Fred: "move the z3 z4 retrieve issue to the end of 2.8 release ... just attach notes").

## Symptom
- Droid 1: at map zoom 4 and out, the map goes blank, even online, even over Utah where the z4 tiles ARE stored. z5 and in draw normally.
- Not step 11 (same on the 16:18 build without step 11). Not the missing-tiles bug (REPLACEONLY-2026-11-07 fixed that; maps back 17:45).

## Fred's hypothesis (17:45)
- A restriction on the downloaded maps may be affecting the online maps.

## Evidence so far
- Store copy 11-07 16:21 (VEC.mbtiles, 57,054 tiles): z4 held (2,6) (3,5) (3,6) (4,6); NE tile z4/4/5 not held (before the restore).
- 14:26 log: z4/5/5 and z4/5/6 NOT HELD -> fetched online (118,683 B / 184,346 B) -- the online fill works at z4, yet nothing drew.
- The tile server answers a NOT HELD + not-fetched tile with HTTP 200 and an EMPTY body (VectorMapTest.kt shouldInterceptRequest, X-GT-Held: 0).
  MapLibre keeps that empty tile for the session -> going online later does not re-request it ("remembered blank").
- vec_nodata.js (VECNODATA-2026-10-31): when every tile of a view is X-GT-Held 0 it zooms back / shows "No maps downloaded below this zoom here";
  it stops at target < 3. Candidate for the z4 behaviour -- not yet confirmed.
- VecOnline.get(): 4 s semaphore wait; a busy fetch returns null -> drawn empty ("VECONLINE ... busy" in the log).

## Next look (when picked up)
1. logcat -c; online, zoom z6 -> z4; logcat -d | grep -E "VECMAPDIAG|VECONLINE|ConvoyJS" (page messages are tag ConvoyJS -- VECNODATA / VECWATCH lines).
2. Compare with Droid 2 at the same view.
3. Check whether the empty-200 answer should be a 204/404 or trigger a source reload when the app goes online.
