# GroupTrack — New Conversation Instructions

_How Fred and Claude work, start to finish, every day. Read this first in any new conversation._

---

## Who / What

- **GroupTrack** = Android convoy/off-road tracking app, a Meshtastic fork. Milestone **V2.5**, ~18 testers.
- **Branch:** `feature/convoy-event-ride`. **App package:** `com.grouptrack.android`.
- **Convoy source:** `app/src/main/java/com/geeksville/mesh/convoy/`.
- **Fred** is a solo dev who runs Claude-generated Python patch scripts + git/adb commands on **Windows / Git Bash**, **one command at a time**. Claude drives the code; Fred runs it and reports back.

---

## THE CONTEXT FILES — what each one is, and when to use it

At session start Fred uploads the files the recommit script produced. **These are not inert attachments — each has a job. Use them, don't just hold them.** This is the reference library; consult the right file at the right moment.

### `GroupTrack_AllDocs.txt` — the historical record (ALWAYS upload)
Every current-state doc concatenated into one text file: the Living Checklist, release notes, handoffs, state-of-play, architecture notes, design decisions — `.docx` + `.md` + **`.html`** (HTML folded in as of recommit v13). **This is the project's memory.** When Fred references a past decision, a feature's history, a design rationale, or "what did we decide about X" — **search AllDocs first.** It answers most "why is it this way" questions without re-deriving.

### `field_crossref_raw.txt` — the deep code map (ALWAYS upload; consult BEFORE any code change)
The heavy cross-reference. Contains: source-file inventory (every convoy `.kt` with line counts); **bridge method caller analysis** (where `drawTrack`, `addMarker`, `evaluateJavascript`, etc. are called); **state field read/write map** (SharedPreferences writers/readers, file I/O, Kotlin↔JS bridge); **lifecycle phase map** (init / tick / map-load / record / radio / user phases); code issues (TODO/FIXME/BUG/WARNING); current git state (branch, last 5 commits, uncommitted changes, stash); feature flags. **This is the impact-analysis file. Read the relevant sections before cutting code so you know the blast radius.** When a change touches a function, grep this for every call site first.

### `where_used_raw.txt` — call sites for key functions (consult before changing a shared function)
A targeted grep of where important functions are invoked across the `mesh` tree (recording, download, GPS, radio, ride, dedup functions, etc.). **Use it to answer "if I change this, what calls it?"** Lighter than field_crossref; quick check for a single function's reach.

### `function_universe_raw.txt` — the function inventory (consult to confirm a function exists / find its home)
Every `fun` / `object` / `class` / `val` / `var` declared in the convoy source, path-stripped. **Use it to confirm whether a function already exists, find where it lives, or check a signature** before writing something new (don't duplicate; don't invent). Also the basis for the dead-code quarantine (zero live refs + no AllDocs mention = orphan candidate).

### `navigation_xref.txt` — the screen/menu map (consult for UI wiring + the user manual)
Screen routing and user-visible labels: nav-host routes (every destination), navigation triggers (`onNavigate*`, `navigate()`), screen composables + exit callbacks, and **user-visible labels** (what the user actually taps — `Text(...)`, titles). **Use it to trace how a screen is reached, what wires to it, and what buttons/labels exist** — essential for both UI bugs and writing the manual cookbook. (This is how we confirmed Import-Trails-by-Area's nav is wired but A3_PROCESSING returns 0, and that Remove-Tiles-from-Area had no route.)

> **Minimum upload:** `field_crossref_raw.txt` + `GroupTrack_AllDocs.txt` gives Claude everything essential. The other three sharpen specific tasks (function lookup, call-site reach, UI/nav + manual).

---

## START OF SESSION

1. **Fred uploads the context files** (above).
2. **Claude reads the KEY DOC first:** the **Living Checklist** (`GroupTrack_V25_LivingChecklist_<date>.html` — newest dated one), inside AllDocs or as its own file. Single source of truth: every feature, every state. **Nothing falls off it.**
3. **Claude reads the day's handoff** if present, then states back the current focus before touching anything.

---

## DURING THE DAY (the work loop)

- **Impact analysis FIRST.** Before any code change, consult `field_crossref_raw.txt` (+ `where_used_raw.txt`, `function_universe_raw.txt`). Know the blast radius before cutting.
- **One change at a time.** Batch related edits, then build once. Commit after every successful build.
- **Patch scripts:** uniquely versioned Python each iteration (e.g. `patch_v25_<thing>_v1.py`). Never heredoc, never reuse a name. Fred runs them from `C:/Users/kixaz/Downloads/`. **CRLF-aware:** `ConvoyScreen.kt` + `ConvoyTrackImportScreen.kt` are CRLF; `ConvoyTrackOps.kt` + `SpatialDbManager.kt` are LF.
- **Install:** always `adb -s <serial> install -r -d <apk>`. **Never** omit `-r -d` (wipes tiles/user data). Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~12–21 min).
- **DB edits on device:** no `sqlite3` on the device — pull → edit copy on PC → `am force-stop` → confirm `pidof` empty → push → reopen. DB pull/push needs `MSYS_NO_PATHCONV=1`.
- **Proven-vs-theorized.** Trust logcat and the DB over the UI. When code-reading can't resolve it, make it observable (add a log line), rebuild, test on device — don't infer from silence.
- **Devices:** Droid 1 `8624SBCEDF00001789` (field-test), Droid 2 `24039703201775` (dev/test).

---

## END OF DAY (the recap ritual) — THE IMPORTANT PART

At the end of each day, **recap what happened and agree on a summary before closing.** The standard: enough detail to **focus on and describe each problem or feature** next session — _not_ enough to write the software, but enough to grip it again cold. Three buckets:

1. **Finished** — what got committed (with commit hashes).
2. **Researched** — what was diagnosed but not fixed, with the approach captured so it resumes fast.
3. **Added** — new items/features that surfaced.

Then update the docs in this order:

### A. The Living Checklist (KEY DOC) — always
- Update it **in place** with today's status changes. Tracks **every feature, every state** — DONE / PARTIAL / OPEN / VERIFY / FLAW — each with a source line. **Nothing falls off the list.**
- Status is never guessed; where unconfirmed, mark **VERIFY**.
- Post a **new dated version** to the shared Drive folder. Claude only ever **adds** dated files (connector is create-only). Recommit retires the old version and folds the content into AllDocs.

### B. Release Notes — when there's something testers should see
- Lives in **HTML** (ships with the product). New dated version to the Drive folder.

### C. The Manual — ONLY when a function is "put to bed"
- The manual is **HTML**, lives in the repo (`~/Meshtastic-Android/docs/grouptrack_manual.html`), ships with the product.
- **Update a function's manual entry only once that function is settled** — never mid-flight, or it gets rewritten five times as the feature changes. Routes don't exist yet → nothing to write there yet.

---

## THE DOC PIPELINE (how the files move, and how they get regenerated)

- **Claude writes** current-state docs (Living Checklist, release notes) into the **shared Drive folder** `GroupTrack_docs` (id `1oSW2Bd2LxxpzXIo4gjsJjDBaiX6WWHnr`). These sync to Fred's `G:` drive.
- **Fred runs `recommit_docs_v13.sh`** from `~/Meshtastic-Android` (`bash docs/recommit_docs_v13.sh`). That script regenerates the very context files described above:
  1. Generates the fast xrefs → `function_universe_raw.txt`, `where_used_raw.txt`, `navigation_xref.txt`.
  2. Copies docs from Downloads **and** the Google-Drive folder into `docs/`.
  3. Archives older `_v#` versions.
  4. **Regenerates `GroupTrack_AllDocs.txt`** — folds in `.docx`, `.md`, **and `.html`** (the HTML fold-in is the v13 fix; v12 dropped HTML text from AllDocs).
  5. Commits + pushes docs, then runs the heavy **`field_crossref_raw.txt`**, commits + pushes again.
- **Next session, Fred re-uploads** `field_crossref_raw.txt` + `GroupTrack_AllDocs.txt` (and optionally the other three) → Claude has full current context. **The loop closes: the docs Claude writes today become the AllDocs Claude reads tomorrow.**

> Naming note: dated docs end in `_<date>` (e.g. `_2026-06-02`), which does **not** match the script's `_v#` archive pattern — so dated docs accumulate one-per-day in `docs/` rather than self-retiring. Harmless (all fold into AllDocs); revisit only if the buildup bothers us.

---

## STANDING RULES (always apply)

- `V3_FEATURES_ENABLED` — **never commit as `true`.**
- **Keystore** `grouptrack-release-key.jks`, alias `grouptrack`; `keystore.properties` at repo root — **never commit.**
- Play Store and local APK use **different signing keys** — uninstall one before installing the other.
- **Asset pull:** `run-as cat` redirect only; never `adb pull` internal storage. Prod pkg `com.geeksville.mesh.google`, debug `com.geeksville.mesh.google.debug`.
- **Grep-verify before building.** Eyeball every diff (`git --no-pager diff`) before the build.
- No hardcoded behavior differences between use cases — confirm screens present user-editable defaults regardless of the calling process.

---

## SEQUENCE (current, as of 2026-06-02)

1. **Convoy parity** (must reach planning-map level BEFORE routes): QUEUES (point convoy at the working `ConvoyDownloadPanel` + plain `.clickable`) → waypoint drop (add `console.log` in convoy_map.html contextmenu, rebuild, watch logcat).
2. **AAB** (`bundleGoogleRelease`, bump versionCode) → install both Droids. Pre-AAB: verify the new streaming parser geometry is correct, not over-capturing.
3. **Route planning** (dedicated day, after parity). **Decision is settled: snap-2 now.** Freehand = rejected origin (testers feel drawing tracks is their value — "John Henry syndrome"). Future vision (not now): draw points to visit → planner generates ~5 candidate routes with key POIs.
4. **2.6:** AWS MySQL model refresh — mirror the local v3 spatial schema structurally (local↔AWS UNIQUE-key identity is correctness-critical). Local DB is reconciled/proven; this is stage 2 of 3.
5. **3.0:** built over the refreshed AWS model — cloud sync, import-tracks-by-area, Core API.

---

## METHODOLOGY THAT WORKS

Proven-vs-theorized (failure in logcat vs guess at cause). Trust the DB/logcat over the UI. One change at a time. Follow the symptom's shape. When reading code can't resolve it, make it observable and test on device. Keep captured detail — edit the checklist surgically, never regenerate it shorter.
