# GroupTrack — Cross-Platform / iPad / PC / KMP Strategy
**Session:** 2026-07-04 (deep planning) · **Status:** architecture arc reconciled; sequencing firm; several decisions still open

> This records the **reasoning arc**, not just conclusions — so the next session starts as a partner carrying the logic forward, not re-onboarding. Read before any iPad/PC/cross-platform planning.

---

## Why this doc exists
Planning sessions must **start by scouring the design corpus** (AllDocs.txt / the 5 session-start xref+nav docs / Drive GroupTrack_docs) for a topic's **full evolution + reasons** before offering a view. Memory summaries are a lossy index; the docs are truth. This file records the reasoning so it isn't lost.

---

## iPad strategy — the evolution (not a new topic; docs had it 4 ways)
The iPad plan evolved four times and was explicitly parked for this session:
1. native port via external dev — Roadmap V9, Mar 25;
2. staged Options A–D incl. WKWebView hybrid — Mar 14 discussion doc;
3. PWA-via-Bluefy-then-Capacitor — May notes;
4. repeatedly flagged "iPad V2.5 vs V3.0, not resolved."

The V3.0 doc line "ATAK removed; no iPad support" is **overturned** by this session — **iPad IS the post-V2.6 target.**

---

## Key collapse — the whole PWA arc was solving a non-problem
Convoy **is** a fork/extension of core Meshtastic's **messaging** (Android = a fork of Meshtastic-Android, Kotlin). The native-vs-PWA-vs-WKWebView arc was all answering "how do we get BLE on iOS?" — a question that **dissolves** once convoy is understood as a fork of the mesh app, not a standalone app.

So iPad = a fork of **Meshtastic-Apple** (Swift/SwiftUI + Core Data + BLE + protobuf messaging; GPL v3; already App-Store-approved; universal incl. iPad; already ships an offline map + full radio config) — same architectural relationship as Android, different language/toolchain. Mac + Xcode = the prerequisite.
**Flag:** Meshtastic-Apple ships native TAK/CoT — reconcile against the roadmap's "TAK as design pattern only" stance.

---

## Config write is offloaded, not owned
The planning side **creates a Meshtastic restore file** (pure file creation, **no BLE** in GroupTrack's code); Meshtastic's own existing "restore config" function applies it to the radio. GroupTrack never does BLE for config. Confirm the exact Meshtastic backup/restore file format + whether Apple's restore accepts the same artifact, when building.

---

## TAK validates the pattern; "the server" is just a port listener
TAK **core** is a deliberate cross-platform **shared layer**; **CoT** is the common **message contract**; transport resolves per platform. On iOS, Apple bans external plugins, so the Meshtastic iOS app spins up a **local server endpoint** (loopback) that iTAK connects to — a **bridge**, not a plugin/fork. Fred's reframe: if messages are IP payloads, "spinning up a server" = nothing more than a **node name + a listening port** handling messages. That loopback socket crosses the iOS app-sandbox boundary where a shared **file** cannot (a timestamped-JSON file proxy works **inside** the Android fork, but two separate iOS apps can't share files — so the bridge needs a socket, not a file). Confirm at build time: iOS background / local-network rules — the app holding the port likely must stay foregrounded (why TAK says keep Meshtastic running as the bridge).

---

## The two candidate iPad-execution patterns (STILL OPEN)
- **(A) Bridge** — convoy is its own native iOS app consuming mesh messages from the separate Meshtastic app over a local port (JSON/IP payloads). Less code; adds a runtime dependency + iOS-lifecycle fragility (the second app must stay alive).
- **(B) Fork** — import Meshtastic's BLE/mesh into the product and extend, exactly as on Android. More work; no runtime dependency; same architecture as Android.

**Trade:** effort (favors A) vs field reliability + Android-sameness (favors B). For an **off-grid safety app**, "bridge dies if iOS backgrounds the neighbor app" is a real mark against A — leans B, but B costs a full native shell. **Not decided.**

---

## Three surfaces, split on connectivity
- **PC = planning-only web app** (routes/waypoints/library + config-file generation; no mesh, no radio, online).
- **Android + iPad = off-grid native convoy execution** (BLE/radio/live mesh; convoy **cannot** be web — it's a disconnected off-grid device).

Planning = the shareable web core (deploys standalone on PC, embeds via WebView in both mobile apps). Convoy = native + mobile-only. The onX "plan on PC, available on any device" model = the V3.0 backend route/ride distribution. **The A/B decision touches only the mobile convoy mesh source — PC is unaffected and can proceed independently.**

---

## Presentation is ~90% shared because it's ALREADY web — SwiftUI rejected
The convoy/planning presentation is already Leaflet/HTML/JS in a WebView (`convoy_map.html` / `grouptrack_map.html`). The leverage lives in the web layer, not in Swift.

**SwiftUI is rejected for presentation** — it does not port to a browser and gives zero reuse of the Android web layer. iPad presentation = the existing web layer in a **WKWebView** behind a native Swift shell; PC = the same web (planning half) in a plain browser; Android = the same web, unchanged. Swift's only job on iPad = the native shell (mesh source + WebView host + JS↔native bridge, the `WKScriptMessageHandler` analog of Android's `JavascriptInterface`). In **both** A and B the iPad app is a native shell hosting the same ~90% web presentation; A vs B only changes the mesh-source module behind the bridge.

---

## "Does the presentation port?" — it SPLITS (the real cost driver)
What renders **inside** the WebView ports cleanly. Every point where the web layer reaches **out** to the device — file I/O, GPS, radio/mesh, camera, share, persistence, offline-tile reads, native dialogs — is a **bridge call**, re-implemented natively per platform, and many have **no browser equivalent** (which is exactly why PC is planning-only: convoy I/O has no browser home). Porting cost ≈ the **size of that bridge/I/O surface.**

**Next action:** enumerate that surface from the current Android code — every `JavascriptInterface` method + every `evaluateJavascript`/`postMessage` crossing. That inventory = the true iPad build cost **and** which functions can/can't go to PC. It's the blast-radius question the xref docs (where_used / function_universe / navigation_xref) answer.

---

## Target end-state architecture + the honest cost
A **messaging/logic service layer** (mesh processing, convoy logic, state) behind a service interface, plus **logic-free presentation panels** (pure screen I/O; panels call the service for everything). Panel redesigns then carry no logic risk (swappable skins over a stable service). This is Fred's "parameter-driven executable functions" mandate realized as a real **service boundary** — re-validated by the portability goal. Drift from that mandate (logic entangled in panels/bridge; the "tracks means five things" naming pain) is what made the porting question hard to answer.

Serving all three platforms independently means the logic is **rewritten once** into **one** platform-independent implementation — not three copies. Current code is not built this way (logic woven into the Android fork), so getting there is a one-time **extraction** = the same effort as the bridge-surface inventory, seen from the other end. **Open:** service layer local-on-device (needed off-grid) vs network (PC planning) vs both; and the language/runtime of the shared layer.

---

## KMP is the natural target
Logic is already Kotlin. KMP migration is **incremental**, not a rewrite — share networking/data/domain layers first (~70% typical; production teams report 88–95%, e.g. Bitkey/Block 95%), keep the Android UI intact, and `expect`/`actual` **is** literally the "configured API resolves uniquely per platform" idea. So "all logic must be rewritten" softens to "re-homed + refactored into a shared module" — the existing Kotlin is the head start, not a throwaway. KMP does **not** let you fork Meshtastic-Apple (that's Swift); it produces a shared logic module a Swift UI/shell consumes.

**Readiness gauge, two steps:** (1) **mechanical** — install the KMP Android Studio plugin, run its preflight checks (+ Qodana static analysis) against the repo; (2) **architectural** (the real predictor of effort) — a logic-entanglement inventory: how cleanly convoy logic separates from the Android-native + Meshtastic-fork code, read against where_used / function_universe xref docs. No tool substitutes for step 2.

---

## Sequencing decision (firm)
1. Ship **V2.6** on Windows (cleanup + map-download rewrite).
2. Set up the Mac Air and **build/run known-good V2.6 on it first** — prove the environment with a known-good artifact before piling on KMP.
3. Then KMP plugin preflight checks + the entanglement inventory vs xref docs.
4. Then incremental KMP extraction (share domain/logic first).
5. Then iPad.

**Rule: do not introduce KMP onto an unproven Mac — one unknown at a time** (else a break is ambiguous between KMP and Mac setup). Building V2.6 on Mac is the first rung needed anyway (same env for preflight + eventual Xcode).

---

## Next-session deliverables
**Deliverable 1 — Mac environment build-out.** Inventory required tools + install-and-confirm one at a time, sequenced known-good-Android-first → KMP → Apple-later. Rough list (finalize/confirm): Git + Terminal/zsh (macOS is real Unix — the Git-Bash/CRLF-heredoc pain that drove the Python-patch convention may relax on Mac); Android Studio + SDK + Gradle + JDK/JavaVM (version TBD); Python (patch scripts); keystore access (grouptrack-release-key.jks is in iCloud → reachable); clone + build V2.6; **then** the KMP plugin; MySQL (purpose TBD — local dev vs V3.0 RDS-adjacent); Xcode + Apple Developer enrollment (later rung, iPad only).

**Deliverable 2 — consolidated cross-domain tooling/stack inventory** ("tools used on PC, AWS, Google Play, MySQL, bash, JavaVM, etc."). Assemble + confirm from the actual environment + docs, not memory. Domains: PC/local dev (Git, Git Bash, Android Studio, SDK, Gradle, JDK/JavaVM, Python, keystore/signing); Data (MySQL version + where it runs — TBD; GeoPackage/SQLite spatial); AWS / V3.0 backend (EC2, RDS, SES named in roadmap; S3 for tiles; PHP API — confirm from V3 spec); Google Play (AAB build, signing key, Play Console, Google Play Billing; Apple IAP parallel later); runtimes (JavaVM, Kotlin, PHP, JS, Python, later Swift). Feeds the shared-service-layer language decision.

---

## Open items
1. iPad mesh source: A (bridge/port-listener) vs B (fork Meshtastic-Apple).
2. Service layer: local-on-device vs network vs both.
3. Shared-layer language/runtime (Kotlin/KMP is the leading candidate since logic is already Kotlin).
4. Reconcile Meshtastic-Apple's native TAK/CoT vs the roadmap's "TAK as pattern only."
5. Confirm the Meshtastic backup/restore file format + cross-platform acceptance.

---
*Living doc — mirror of `/areas/grouptrack-crossplatform.md` as of 2026-07-04.*
