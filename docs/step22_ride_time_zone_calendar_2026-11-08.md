# 2.8 STEP 22 — ride time zone, calendar entry, GPX attachments for non-GroupTrack riders (was 14d)

**Status (Fred 11-08 12:44, 12:46):** moved out of step 14 to STEP 22 of release 2.8, after steps 15–21 (the iPad app working). Nothing of it is built. Build it on the SHARED ride code (com.grouptrack.shared.ride), once, for Android and iPad together. Do not re-decide anything below; it is settled.

## Why it moved, and why nothing is lost
- "Do ride conversion once" (Fred 08:55) is satisfied by the certified port (commit 9657a8ac1) and the switch. 14d is a feature added later on shared code: still written once, reaches both platforms.
- It is a database change (schema version 1 -> 2), kept out of this stretch ("steady the ship").

## Fred's decisions (all final)
1. GroupTrack riders get the ride package; everyone else gets the route GPX and a waypoints GPX as two separate attachments; everyone gets a calendar entry with the meeting waypoint's GPS in the location (08:27–08:55).
2. The ride's time is defined in the ORGANIZER's time zone. It goes out as an exact moment; each receiver's calendar converts it to their own zone. Calendars shift automatically when the phone changes zone (Fred 09:01).
3. Awareness is the key (09:03): every receiver is told the event was scheduled in another zone and has been adjusted.
4. The ride form captures date, time and zone "just like a calendar entry", validated (12:38): date picker, TIME PICKER (no free text), time-zone selector preset to the organizer's current zone and changeable.
5. Calendar entry for GroupTrack receivers: the app opens the phone's calendar pre-filled; the rider taps Save. No calendar permission (my default, 12:38 — Fred did not object).

## Design
### Database (shared GtSchema, new step, schema version 1 -> 2)
- `rides.time_zone TEXT` — IANA name (America/Denver, America/Phoenix, America/Los_Angeles; Navajo Nation = America/Denver).
- New rides store `start_time` as `HH:mm` (24-hour). Old rows keep their text (e.g. "8:00 am") until next edited.
- NO stored moment/UTC column: the moment is always computed from ride_date + start_time + time_zone, so it can never disagree.
- Old rows: time_zone NULL = "read in the organizer's zone" (show as stored, no conversion notice).
- Certify with the schema check: FRESH, UPGRADE from 1, AGAIN identical (CertSchema / CertSchemaMacTest, gt_schemacheck).

### Ride form (ConvoyRideCreateScreen)
- Date picker (as now) + Material3 time picker + "Time zone" selector under it.
- Zone list: US zones; Arizona and "Navajo Nation (observes daylight saving)" listed separately; any IANA zone reachable.
- Label: "Start time (Mountain – your time zone)".
- Cannot save without date, time and zone.
- Editing an old ride: parse "8:00 am", "08:00", "8am", "8:00AM", "13:30"; if unreadable, the picker opens empty and the form asks for the time.
- CODE RULE 1: saveRide/updateRide get the zone as a required parameter.

### Ride package
- `ride.timeZone` added; formatVersion unchanged; older app versions ignore it. Import stores it into time_zone.

### Send (Android Intent; all text generation in shared code)
- ACTION_SEND_MULTIPLE: `.convoy` package + route GPX (route only) + waypoints GPX (trailhead + ride waypoints) + `.ics`.
- Attachments named after the ride, like the .convoy today.
- Invite email keeps today's text, plus a short section for riders without GroupTrack: open the two GPX files in your trail app; add the .ics to your calendar.

### The .ics (RFC 5545, shared)
- VTIMEZONE for the ride zone (Phoenix has no daylight saving); `DTSTART;TZID=<zone>:YYYYMMDDTHHMMSS` — NOT a bare UTC time, so calendars know it was scheduled in another zone.
- DTEND default = start + 4 hours (to confirm with Fred when built).
- UID = rideId@grouptrack; LOCATION = trailhead name + "(lat, lon)" from the same trailhead the package uses (recipe anchor), else the ride's first waypoint; GEO:lat;lon.
- DESCRIPTION, fixed and true for every receiver: "This ride is scheduled for 9:00 AM Mountain Time (UTC−7 on this date). If your calendar is in a different time zone, the time shown has been adjusted to your time zone." — abbreviation and offset worked out FOR THE RIDE DATE; then the leader's notes.
- Escaping and 75-octet line folding. No zone or unreadable time -> all-day entry with the stored time text in the description.

### GroupTrack receiver (import)
- After import: "Add to calendar" -> phone calendar pre-filled (ACTION_INSERT; on iPad EventKit UI), event time zone = ride zone.
- Description computed ON THE RECEIVER'S PHONE: "This ride is scheduled for 9:00 AM Mountain Time (UTC−7). It has been adjusted to your time zone: 11:00 AM Eastern, 2 hours ahead." Nothing when the offsets are equal at the ride moment.
- A notice written at import stays as written; the calendar time itself always moves with the phone.

### Ride panel (live)
- Each time it opens: "Ride destination is 1 hour behind your current time zone (UTC−7 vs your UTC−6)", worked out on the rider's phone from its current zone.

### The rules that keep the difference right (Fred 08:57: the biggest risk is misinforming the zone difference)
1. Work out the difference AT THE RIDE'S DATE AND TIME, never today's. (Today Utah and Arizona are both UTC−7; a July ride is Utah UTC−6 vs Arizona UTC−7.)
2. Store named zones, never fixed offsets.
3. Shared code, kotlinx-datetime (0.7.1 is in libs.versions.toml; add it to grouptrack-shared).

### Fixed test table (each case has a known right answer; Android and iPad must both pass)
- Utah -> Arizona: summer 1 h behind; winter same.
- Utah -> Nevada: 1 h behind, winter and summer.
- Utah -> Navajo Nation: summer same.
- A ride on 2026-11-01 (daylight saving ends) and on 2026-03-08 (starts), including a 02:30 time that does not exist / exists twice (choose and record the rule).
- Same zone; a viewer in America/New_York.
- .ics golden checks: folding, escaping, VTIMEZONE Phoenix vs Denver.

### Manual line (for the ride/invite section when built)
"Times shown are in your phone's current time zone. If you're crossing into Arizona or Nevada, the time will change at the border."

## Open choice (Fred to decide)
- The two GPX attachments alone need no database change and could ship with the switch instead of waiting.

## Not to reuse
- A background build of this (as 14d) was started at 12:41 and STOPPED at 12:44 (Fred deferred it). Any partial files in the scratchpad step14d/ folder are incomplete and must not be used; rebuild from this note.
