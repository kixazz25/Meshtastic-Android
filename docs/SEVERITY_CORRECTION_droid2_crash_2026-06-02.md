# SEVERITY CORRECTION — Droid-2 track crash is HIGH priority (2026-06-02)

Corrects the "LOW severity" framing in ISOLATION_PLAN_droid2_track_crash and the EOD notes. That framing was WRONG.

## Why this is HIGH, not low
The app CRASHES and LOCKS UP / HOSES THE DEVICE on track import. "No data lost" is irrelevant to severity — the failure mode is a hard device lock. For a field convoy tool people depend on off-grid (St George / Bar 10 / backcountry), an app that hangs the phone is a near-worst-case failure. This is a launch-blocking-class stability bug, not a cosmetic post-import glitch.

- Data integrity (67 tracks landed) speaks to DATA SAFETY, not SEVERITY. Both can be true: data is safe AND the bug is severe.
- A device lock in the field = user can't navigate, can't see the convoy, may have to reboot mid-ride. Unacceptable for the product's core use case.

## Priority
- This sits at the level of the other launch-gate stability items (the ANR/osmdroid items in checklist Section G). Treat it as a real bug to root-cause, not a someday-polish item.
- The isolation plan (ISOLATION_PLAN_droid2_track_crash_2026-06-02_AM) is still the right METHOD — isolate the variable, one build cycle, read the logcat. What changes is the disposition: do NOT casually punt it to "come back later." Give it the focused AM session it deserves before route planning, OR consciously decide the order with eyes open — but not because it's "minor." It is not minor.

## Note to self (Claude)
Stop reflexively labeling things "low severity" because data survived. Severity = impact on the user and the product. A crash that hoses the device is high impact regardless of data outcome.
