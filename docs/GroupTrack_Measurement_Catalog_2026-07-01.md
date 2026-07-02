# GroupTrack — CONVOY MEASUREMENT / DISPLAY CATALOG (2026-07-01)
Complete inventory of every measurement + value shown on HUD / convoy display, its DERIVATION RULE, and where it's used. Purpose: the tick rewrite MUST continue every one of these. Any value missing post-rewrite = a blank/broken field. Mined from ConvoyNode.kt, ConvoyEngine.kt, ConvoyViewModel.kt (07-01). Fred to confirm each rule (mine or redefine).

## 1. PER-CART VALUES (ConvoyNode fields — one set PER cart)
| Value | Type | Derivation rule | Notes / where |
|---|---|---|---|
| nodeId | String | radio `!%08x`.format(num), or `!phone` (device) | identity; drives isMyCart/lead match |
| callsign | String | radio user.long_name → short_name → `!num`; device = Build.MODEL | display name |
| role | String | Lead / Tail / My Cart / Convoy (from assignLeadTail) | HUD role |
| status | enum | computeStatus: age=now−lastSeenMs; ≥LOST_MINUTES→LOST; ≥SIGNAL_DROP_MINUTES→SIGNAL_DROP; else ACTIVE | drives active/lost counts, color |
| isLead / isTail / isMyCart | Bool | isLead=nodeId==lockedLeadNodeId; isTail=tailNodeId(min dist accum) or max convoyPosition; isMyCart=nodeId==myCartId | role flags |
| latitude / longitude | Double | reported GPS (pos.latitude_i*1e-7) or own last-known; NEVER cross-cart substitute (NEW rule) | map position |
| altitude_m | Int | radio pos, or phone GPS alt (×3.28084 to ft for display) | HUD alt |
| speed_mph | Float | **60-SECOND FIXED WINDOW**: distance(window start→now) then ×60 = mph; recomputed when window elapses; else last computed (VM ~913-932). Radio speed if provided. | HUD speed |
| heading_deg | Float | radio ground_track (scaled /100000 %360) or phone GPS bearing | HUD heading, convoy sort |
| battery_pct | Int | radio deviceMetrics.battery_level; device=100 | HUD battery |
| snr_db | Float | radio node.snr | signal quality |
| lastSeenMs | Long | nowMs on build; drives status + lastSeenAgo | freshness |
| convoyPosition | Int | computeSortPositions: project lat/lon onto heading vector (lat*cos+lon*sin), sort desc, 1=front..N=back; LOST appended | order in convoy |
| feetToNodeAhead | Float | haversineMiles(this, node at idx-1) × 5280 | HUD spacing |
| feetToNodeBehind | Float | haversineMiles(this, node at idx+1) × 5280 | HUD spacing |
| milesToLead | Float | haversineMiles(this, lead node) | HUD my-progress (NEEDS correct isMyCart/lead) |
| milesToTail | Float | haversineMiles(this, tail node) | HUD |
| markerColor | String | isLead→#1CF0A0 green; isTail→#FF8C42 orange; isMyCart→#2E75B6 blue; else palette[convoyPosition%16] (brown=#795548 at idx8) | map marker |
| markerSymbol | String | lead=triangle; tail=triangle-stroked; myCart=star; else circle | map marker |
| markerSize | String | lead/tail/myCart=large; else medium | map marker |
| lastSeenAgo | String (computed) | (now−lastSeenMs)/1000 → "Ns"/"Nm" formatted | HUD freshness label |
| cotType / timestampUtc | String | CoT type constant; UTC timestamp | ATAK/export (verify still needed) |

## 2. GROUP-LEVEL VALUES (ConvoyEngine.ConvoyState)
| Value | Type | Derivation rule | Where |
|---|---|---|---|
| lead | ConvoyNode? | firstOrNull{isLead}; null if lockedLeadNodeId not in active | group HUD lead |
| tail | ConvoyNode? | firstOrNull{isTail} (min dist accumulator node) | group HUD tail |
| span_miles | Float | computeSpan = haversineMiles(lead, tail) | group HUD "convoy length" |
| convoyHeading | Float | computeHeading = median heading of ACTIVE nodes moving >3mph; 0 if none | sort + display |
| hasLost | Bool | lostCount > 0 | warning indicator |
| activeCount | Int | count{status==ACTIVE} | group HUD |
| lostCount | Int | count{status==LOST} | group HUD |

## 3. MY-CART / RIDE-LEVEL DERIVED VALUES (ConvoyViewModel)
| Value | Type | Derivation rule | Where |
|---|---|---|---|
| distanceMiles | Double | gpsService.totalDistanceMiles (recorder-accumulated; reset 0 on start) | my-cart HUD distance travelled |
| nodeDistanceAccum[id] | Float | per-node miles travelled (accumulated haversine between successive positions) | tail selection (min=tail) + lead 1/4mi trigger |
| avgChannelUtil | Float | channel utilization avg | signal HUD |
| currentIntervalSecs | Int | GPS broadcast interval (default 5s) | settings/HUD |
| rideStartTimeMs | Long | set at startGroupTrack; stale-packet gate | recording |
| convoyHeading / speed windows | — | see above | — |

## 4. HUD MODES (which values shown when)
- `HudMode.GROUP` — group-level: lead/tail/span/active/lost/heading + my-cart summary.
- `HudMode.NODE` — selected-cart detail (onMarkerTapped): that node's full per-cart value set.
- My-cart panel — my isMyCart node values + distanceMiles + progress (milesToLead).

## 5. DERIVATION RULES TO CONFIRM/REDEFINE (Fred)
- **speed_mph:** 60-second fixed window (dist over 60s ×60). Confirm keep, or redefine window length.
- **distanceMiles:** from recorder (gpsService.totalDistanceMiles). In snap2 world — is distance travelled measured on RAW GPS or SNAPPED track? (snapped = smoother/shorter; raw = true odometer). DECISION NEEDED.
- **nodeDistanceAccum:** per-node accumulator — keep (tail parked-radio rationale + lead trigger). Measured on own positions only (NOT substituted) in new model.
- **convoyPosition:** heading-projection sort — keep (drives order + tail fallback).
- **status thresholds:** LOST_MINUTES / SIGNAL_DROP_MINUTES (ConvoyConfig) — confirm values.
- **milesToLead/Tail, feetAhead/Behind:** haversine — keep; REQUIRE correct isMyCart identity (Fix 2) to be meaningful.
- **markerColor:** confirm the role-first + palette scheme stays (green=lead etc).

## 6. RULE FOR THE REWRITE
Every value in sections 1-3 MUST be produced by the new tick (IDLE draws position/marker values; RECORDING adds measurements + progress + distance). Before deleting any calc, confirm its replacement produces the SAME value. Cross-check this catalog against the new tick's outputs field-by-field. NEEDS: ConvoyScreen HUD composables to confirm NO displayed value is missing from this catalog (pull the HUD UI to cross-verify consumption).


---

## 7. MINED DISPLAY FIELDS (from ConvoyScreen.kt — what's ACTUALLY rendered)
Cross-verified against the model values above. Every field here MUST survive the rewrite.

### GroupHud (ConvoyScreen.kt:2128)
| Displayed | Source | Format |
|---|---|---|
| Interval | currentIntervalSecs (slider 2-8s) | "{n}s" |
| SPAN | state.span_miles | "%.1f mi" |
| CH% | avgChannelUtil | "%.0f%%" |
| Carts | state.nodes.size | int |
| Active | state.activeCount | int (green) |
| Lost | state.lostCount | int (red) |
| ▲ Lead | state.lead?.callsign | callsign or "--" (green) |
| ▽ Tail | state.tail?.callsign | callsign or "--" (orange) |

### MyCartHud (ConvoyScreen.kt:2231)
| Displayed | Source | Format |
|---|---|---|
| Title | myCart?.callsign ?: myCartId.takeLast(8) | "My Cart ★ {name}" |
| Heading | myCart.heading_deg | "%.0f°" |
| Battery | myCart.battery_pct | "{n}%" |
| Altitude | myCart.altitude_m | "{n} ft"  ⚠️ labeled ft |
| Speed | myCart.speed_mph | "%.0f mph" |
| ↑↑ To Lead | myCart.milesToLead | "%.1f mi" |
| ↓↓ To Tail | myCart.milesToTail | "%.1f mi" |
| ↑ Gap Ahead | myCart.feetToNodeAhead | "%.0f ft" |
| ↓ Gap Behind | myCart.feetToNodeBehind | "%.0f ft" |
(Fallback: "MY CART not found" if myCart null — happens when isMyCart matches nothing → identity bug surfaces HERE too.)

### NodeDetailHud (ConvoyScreen.kt:2294) — selected cart
| Displayed | Source | Format |
|---|---|---|
| Title | node.callsign | text |
| STATUS | node.status.name | ACTIVE/SIGNAL_DROP/LOST |
| SPD | node.speed_mph | "%.0f mph" |
| BAT | node.battery_pct | "{n}%" |
| POS | node.convoyPosition | "#{n}" |
| HDG | node.heading_deg | "%.0f°" |
| ALT | node.altitude_m | "{n}m"  ⚠️ labeled m |
| SEEN | node.lastSeenAgo | "Ns"/"Nm" |
| actions | SET AS LEAD / REMOVE FROM RIDE | buttons |

### Distance odometer (ConvoyScreen.kt:1204) — STANDALONE, not a HUD
| Displayed | Source | Format | Notes |
|---|---|---|---|
| Distance | viewModel.distanceMiles | "%.2f" | bottom-right, RED, ONLY when recording. This is the RECORDED ODOMETER (svc.totalDistanceMiles), separate from all HUDs. Reset 0 on stop. |

## 8. BUGS / ISSUES FOUND WHILE MINING (fix-release candidates)
- **ALTITUDE UNIT MISMATCH:** MyCartHud shows `altitude_m` as "ft" (line ~2253); NodeDetailHud shows same `altitude_m` as "m" (line ~2321). Same field, two labels — ONE IS WRONG. `altitude_m` name says meters, but readLiveNodes converts phone alt to ft (×3.28084 at 843-area) for the `!phone` node while radio nodes may store meters. Unit handling is inconsistent — CONFIRM the true unit and fix both labels. Fix-release item.
- **"MY CART not found"** appears when isMyCart matches no node — the identity bug (HOTEL-10 / wrong myCartId) surfaces as this MyCartHud fallback. Fix 2 resolves it.

## 9. TOTAL-DISTANCE FORMULA — NEEDS ConvoyGpsService.kt
distanceMiles = svc.totalDistanceMiles (ViewModel:448 mirrors it; reset 0 at 484). The ACCUMULATION formula (haversine between successive recorded points, summed) lives in ConvoyGpsService.kt — NOT YET UPLOADED. Needed to document the exact odometer formula + to decide (snap2) whether distance is measured on RAW or SNAPPED points.


---

## 10. ⭐ ALTITUDE — FIELD NAME LIES, CALC IS TRUTH (Fred 07-01 — why we mine calcs from xref, not field names)
The field is named `altitude_m` (implies METERS) but the DISPLAYED value was CORRECTED to FEET via a conversion formula. Confirmed in xref:
- **ConvoyViewModel.kt:852** (readLiveNodes, phone-node branch): `val alt = ((loc?.altitude ?: 0.0) * 3.28084).toInt()` — Android GPS altitude (meters) × 3.28084 = FEET, stored into `altitude_m`. So for the `!phone` node, `altitude_m` actually holds FEET.
- MyCartHud labels it "ft" → CORRECT (for phone node). NodeDetailHud labels it "m" → WRONG (it's feet after conversion).

**⚠️ DEEPER OPEN QUESTION (needs ConvoyViewModel.kt re-upload to confirm):** does the RADIO-node branch of readLiveNodes also convert to feet, or store raw meters? If radio nodes store METERS while the phone node stores FEET in the SAME `altitude_m` field, the field holds INCONSISTENT UNITS by source — a real bug, not just a label mismatch. MUST verify the radio-node altitude assignment. Only calc-mining (not the field name) reveals this.

**LESSON / RULE FOR THE CATALOG:** the field NAME is unreliable (`altitude_m` holds feet). The DERIVATION CALC is the truth. Mine every raw-field→display conversion from the xref (like ×3.28084) — those conversion formulas ARE the real derivation rules and MUST be preserved in the rewrite. A field-name-based catalog would document altitude WRONG. This is why Fred wants calcs mined from field xref.

TODO (calc-mining pass): find every unit/format conversion applied between raw field and display (m→ft, mi→ft ×5280, mph scaling, heading /100000 %360, lat/lon *1e-7, etc.) and record each as the field's true derivation rule. Re-upload ConvoyViewModel.kt to complete the altitude radio-vs-phone check.


---

## 11. ✅ MINED CONVERSION FORMULAS (the TRUE derivation rules — from ConvoyViewModel.kt readLiveNodes)
Every raw-field→display conversion, mined from code. THESE are what the rewrite must preserve (field names are unreliable).

| Field | Radio node | Phone (!phone) node | Notes |
|---|---|---|---|
| latitude | pos.latitude_i × 1e-7 | loc.latitude (raw) | radio stores int×1e7 |
| longitude | pos.longitude_i × 1e-7 | loc.longitude (raw) | " |
| **altitude_m** | pos.altitude × 3.28084 → **FEET** | loc.altitude × 3.28084 → **FEET** | ⭐ BOTH convert to FEET. Field named `_m` but ALWAYS holds FEET. NodeDetailHud "m" label is WRONG; MyCartHud "ft" is right. NO units-by-source bug (both feet). |
| **speed_mph** | 60-SEC FIXED WINDOW: distMiles(window)×60 = mph; holds last computed until window completes | loc.speed × 2.23694 (m/s→mph, INSTANT) | ⭐ DIFFERENT derivation by source: radio=60s smoothed window, phone=instant GPS speed. Both mph. |
| **heading_deg** | (ground_track / 100000) % 360, normalized +360%360 | loc.bearing (raw deg) | radio scales /100000 |
| battery_pct | deviceMetrics.battery_level ?: 0 | 100 (hardcoded for phone) | |
| snr_db | node.snr (raw) | 0 (phone n/a) | |
| lastSeenMs | node.lastHeard × 1000 (s→ms) | nowMs | drives status/lastSeenAgo |
| callsign | user.long_name→short_name→`!num` | Build.MODEL | |
| nodeId | `!%08x`.format(node.num) | "!phone" | |

### Display-layer conversions (ConvoyEngine / HUD format):
| Value | Formula | Where |
|---|---|---|
| feetToNodeAhead/Behind | haversineMiles(...) × 5280 (mi→ft) | ConvoyEngine computeProximity |
| milesToLead/Tail | haversineMiles(...) (mi) | " |
| span_miles | haversineMiles(lead,tail) | computeSpan |
| convoyPosition | project lat*cos(hdg)+lon*sin(hdg), sort desc | computeSortPositions |
| distanceMiles (odometer) | svc.totalDistanceMiles (accumulated in ConvoyGpsService — haversine between recorded points; NEEDS ConvoyGpsService.kt for exact formula) | odometer display |
| lastSeenAgo | (now−lastSeenMs)/1000 → "Ns"/"Nm" | ConvoyNode computed |

### RULE PRESERVED FOR REWRITE:
- Altitude: keep ×3.28084 both branches (feet). FIX the NodeDetailHud "m"→"ft" label (fix-release, 1 word).
- Speed: preserve BOTH derivations — radio 60s-window (smoothed), phone instant×2.23694. (Rewrite decision: unify or keep source-specific? Radio has no instant speed, so likely keep split.)
- Heading: radio /100000 %360; phone raw bearing.
- lat/lon: radio ×1e-7; phone raw.
- All haversine-based distances unchanged.

## STILL NEEDS (to close the catalog 100%):
- **ConvoyGpsService.kt** — exact totalDistanceMiles accumulation formula (odometer) + the recording write path + snap decision point (raw vs snapped distance).


---

## 12. ✅ ODOMETER FORMULA — totalDistanceMiles (from ConvoyGpsService.kt onGpsUpdate) — CATALOG NOW COMPLETE
`distanceMiles` (the on-record odometer, ConvoyScreen:1204) = ViewModel mirrors `svc.totalDistanceMiles` (VM:448), reset 0 on stop (VM:484). The accumulation (ConvoyGpsService.kt):
- **onGpsUpdate(lat,lon,alt)** (288) — called per GPS fix.
- **Distance accum (309):** `totalDistanceMiles += haversineMiles(prevLat, prevLon, lat, lon)` — haversine between successive points, summed. Accumulates on EVERY point ("regardless of export format", comment 306). Reset to 0.0 at start (203).
- **MOVE_THRESHOLD_FEET = 50** (90, 292) — gates SLEEP detection only (no move 10 min → sleep/pause via SLEEP_THRESHOLD_MS). Does NOT gate distance accumulation.
- **GPX write (393):** `writeGpxPoint` → `<trkpt lat lon><ele>alt</ele><time>ts</time>` — writes the SAME raw point.

### ⭐ SNAP2 INJECTION POINT (critical for the rewrite):
`onGpsUpdate` is where BOTH distance-accumulation (309) AND GPX-write (311-315) happen, on the RAW GPS point. This is the ONE place the snap2 decision lands:
- **Today:** raw point → accumulate raw distance + write raw GPX.
- **Snap2 model (recorded track IS snapped):** snap the point to trail BEFORE line 309, so BOTH distance and GPX use the snapped point. → recorded GPX = snapped; odometer = snapped-path distance.
- **Fred's flagged decision (distance raw vs snapped)** is decided EXACTLY HERE: snap before 309 → distance on snapped path (smoother/slightly shorter); snap only the write → distance stays raw (true odometer), GPX snapped. Pick one deliberately.
- OFF-TRAIL: gate with OFF_TRACK_MILES — on-trail snap, off-trail raw (both distance and write).

## ✅ CATALOG STATUS: DERIVATION-COMPLETE
All per-cart, group, derived values + all display fields + all conversion formulas + the odometer formula are now mined and documented from real code. This is the estimation-ready measurement reference. The rewrite must reproduce every value herein. The only remaining rewrite DECISIONS (not missing data) are flagged: snap2 raw-vs-snapped distance; speed derivation unify-vs-split; and the fix-release bug (NodeDetailHud altitude "m"→"ft").
