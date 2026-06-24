# GroupTrack — User Manual Coverage Worksheet
**2026-06-18 · source of truth: `navigation_xref.txt` (generated 2026-06-18 17:43 from ConvoyNavigation.kt)**

This worksheet is the screen inventory + coverage map for the V2.5 user manual.
It exists so no screen silently drops, and so the manual is completed against the
ACTUAL app navigation (40 nav destinations) rather than from memory.

> **Two-manual split (Fred 06-18):**
> - **Manual 1 — Free Map Product (V2.5):** the standalone app. Complete this one
>   now (when V2.5 is feature-complete). Self-contained; a rider who never uses the
>   online service has a full manual. 3.0 account/cloud screens are IGNORED here
>   (not "coming soon" clutter).
> - **Manual 2 — Online Services (3.0):** sign-in, subscription, ride
>   creation/management, invites, enrollment, dashboard, organizer tools. STUB ONLY
>   now; built when 3.0 is built.

> **Do NOT finalize numbering or capture screenshots until V2.5 is feature-complete.**
> Universal search and the lead-cart rebuild will change several screens
> (Work-with-Artifacts loses the old search box; Convoy Map live-tracking changes;
> SearchByArea is still a "Coming in V2.5" scaffold). Capturing now = re-shooting later.

---

## MANUAL 1 — FREE MAP PRODUCT (V2.5) — screen inventory + coverage

Status legend: COVERED in current 06-18 manual · PARTIAL/needs detail · MISSING · SCAFFOLD/stub (don't document as live yet)

### Section 1 · Getting Started (one section, stepped)
| Step | Screen(s) involved | xref screen | Manual status |
|---|---|---|---|
| 1 Install | first launch, storage-permission dialog ("Storage Access Required"), location-permission dialog | ConvoyScreen | PARTIAL — drafted, [verify] |
| 2 Download trails | Trail Sources (IMPORT FULL SOURCE / IMPORT BY AREA / VALIDATE / IMPORT TRAILS / progress recap) | ConvoyTrailSourceScreen | PARTIAL — drafted from memory; xref shows richer flow, needs detail |
| 3 Map sources | Map Sources (assign SAT/TOPO/TOPO+ slots, API Key dialog, Refresh Tiles) | ConvoyMapSourceScreen | PARTIAL — [verify menu path]; xref confirms reached from Settings -> "Change Map Sources" |
| 4 Trail sources (add/update) | Trail Sources again | ConvoyTrailSourceScreen | PARTIAL — drafted |
| 5 Radio setup (optional) | connect panel + the radio-write sequence | (multiple, see Radio section) | PARTIAL — drafted, points to radio chapter |
| 6 Release upgrades | ? help chooser (Release Notes / Full Manual) + Play Store | ConvoyScreen/MapViewer ? dialog | PARTIAL — added in plan, not yet written into manual |

### Section 2 · Planning Map
| Screen | xref | On-screen functions (from xref labels) | Status |
|---|---|---|---|
| Planning Map (Map Viewer) | ConvoyMapViewerScreen | BACK, QUEUES, SEARCH+MAP collapsible, Search-area field ("press Enter"), New Waypoint dialog (Name optional / Create / Cancel), "Leave Planning Map?" (settings preserved), ? Help & Info (Release Notes / Full Manual / Close), DOWNLOAD QUEUES panel, Start-a-route (New Route / resume) | COVERED landing; PARTIAL — QUEUES + ? + route-start need their own slots/detail |

### Section 3 · Convoy Map
| Screen | xref | On-screen functions | Status |
|---|---|---|---|
| Convoy Map | ConvoyScreen | Storage/Location permission dialogs, Area-Too-Large, Download-Map-Area (mi×mi, tiles/MB, source, DOWNLOAD/CANCEL), Download Error, Save Track (name / SAVE / SKIP), New Waypoint (Name optional / Create / Cancel) | COVERED landing; MISSING — download-area dialog, save-track dialog not documented |
| Download Queues | (ConvoyMapViewerScreen panel) | DOWNLOAD QUEUES / CLOSE / "No downloads in queue" | PARTIAL — slot present, needs detail |

### Section 4 · Work with Artifacts (+ detail panel)
| Screen | xref | On-screen functions | Status |
|---|---|---|---|
| Work with Artifacts | (ConvoyArtifactsPanel / ConvoyScreen) | toggles ON/OFF/SELECTED, search-by-name, SEL/Edit list | COVERED |
| Artifact Detail panel | ArtifactDetailPanel.kt | Type, ALIASES, DETAILS, technical toggle, FIT, CLOSE, Rename dialog, Delete dialog ("cannot be undone") | COVERED FIT + detail; PARTIAL — rename/delete are stubs (Pass-1), note as in-progress |

### Section 5 · Convoy Settings  — MISSING FROM MANUAL — must add
| Screen | xref | On-screen functions | Status |
|---|---|---|---|
| Convoy Settings | ConvoySettingsScreen | Change Map Sources (-> Map Sources), alert thresholds: Signal Drop / Signal Lost / Off Track / Admission Window, Multicolor Track toggle, Lead Cart Only toggle, Track Recording Format (KML/GPX), "No carts removed today" / Reinstate, build-stamp footer | MISSING — NOT in manual. This is the "~20 fields, one screenshot" screen. Note: Lead Cart Only + Multicolor Track tie directly to the lead-cart rebuild [2.1] — wording here changes when [2.1] lands. |

### Section 6 · Convoy Radio-Write  — SEVERELY UNDER-COVERED — it's a multi-screen flow
The manual treats this as one "menu." The xref shows a full sequence:
| Screen | xref | Role |
|---|---|---|
| Field Radio | ConvoyFieldRadioScreen | "Always active — no internet"; APPLY MASTER CONFIG / APPLY RIDE CONFIG / VERIFY CONFIG |
| Apply List | ConvoyApplyListScreen | APPLY RADIO CONFIG; field table (FIELD/CURRENT/NEW VALUE/RULE); LONG NAME; PROCEED TO UPDATE; CAPTURE NEW MASTER |
| Apply Radio | ConvoyApplyRadioScreen | select ride, changes-to-be-applied, new channel + AES-256, PROCEED |
| Master Capture | ConvoyMasterCaptureScreen | CAPTURE MASTER CONFIG; reads LoRa region/preset/hop/TX/freq/channel/PSK from connected radio; Frequency-MHz entry; PROCEED |
| Archive / Restore | ConvoyArchiveRestoreScreen | select archive, "RESTORE WILL OVERWRITE", reboot-after, countdown, DONE/TRY AGAIN |
| Radio Config Steps | ConvoyRadioConfigScreens | Step N of M: Archive -> Device -> LoRa -> Position -> Channel+PSK writes, reboot warnings |
| Reconnect Wait | ConvoyReconnectWaitScreen | BT off/on instructions, countdown, auto-proceed |
| Verify Config | ConvoyVerifyConfigScreen | field-by-field compare, FAILED FIELDS table, per-group pass/total |
| Broadcast | ConvoyBroadcastScreen | (ride broadcast — likely 3.0/online, verify scope) |

**Status: the manual's single Radio-Write section must expand to cover this
sequence, OR explicitly scope it to the user-facing entry points and mark the
internal write-steps as guided/automatic. Decide depth with Fred. Transitional —
slated for 3.0 replacement, but supported and used in V2.5.**

### Scaffolds — do NOT document as live
| Screen | xref | Why |
|---|---|---|
| Search By Area | ConvoySearchByAreaScreen | renders "Coming in V2.5 / REQUIRES V2.5 MAP FUNCTIONS" — stub |
| Route Create (standalone) | ConvoyRouteCreateScreen | "[ Pass 1 scaffold ]" — real route building is the on-map Route+ toolbar, already documented in Work-with-Artifacts |
| Tracks (library) | ConvoyTracksScreen | "Tracks — Phase C" placeholder |

---

## MANUAL 2 — ONLINE SERVICES (3.0) — deferred, stub only

These nav destinations are the account/cloud product. NOT in Manual 1. Listed so
they're inventoried, not lost:

ConvoySignIn, ConvoyTerms, ConvoyPrivacy, ConvoySubscription, ConvoyDashboard,
ConvoyProfile, ConvoyCreateRide, ConvoyCreateEvent, ConvoyMyRides, ConvoyRideDetail,
ConvoyCompletedRides, ConvoyCompletedRideDetail, ConvoyEnrollment, ConvoyInviteSend,
ConvoyMyOrganizers, ConvoyTransferRide, ConvoyEmailGate, ConvoyDownloadRideConfig,
ConvoyExplore, ConvoyBroadcast (broadcast may be online-only — verify).

---

## COMPLETION PLAN (when V2.5 feature-complete)

1. Hold until search + lead-cart land (they change screens).
2. Add the missing Manual 1 sections: Convoy Settings (the ~20-field screen),
   and expand Radio-Write to the real sequence (depth TBD with Fred).
3. Correct the Getting Started [verify] spots against the device (install path,
   Map Sources menu path, trail-source re-import dedupe).
4. Reconcile every Manual 1 section against this worksheet's function lists.
5. THEN number image slots 001...NNN in manual order (three-digit) and produce the
   numbered shot list; capture per the list.
6. Manual 2 (online services) is a separate, later build.
