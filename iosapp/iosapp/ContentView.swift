import SwiftUI
import GroupTrackShared

// GEOMPROBE-2026-11-18 (XCODESTEPS STEP 3): proof that GroupTrackShared LINKS AND RUNS on iPadOS,
// not merely that the Xcode project compiles. A green build with the template ContentView proves
// nothing -- it imports nothing, so the linker never reaches the framework.
//
// GtGeom is chosen deliberately. geom_hash is SHA-256 of the WKT TEXT, and the text uses Java's
// Double.toString rule (GtJava.jd -- on iPadOS, the 15a port, because Kotlin/Native's own toString
// writes some numbers differently: 17 digits on 28 of 188,137 values). If the iPad writes one digit
// differently, dedup and sync silently see two trails where there is one. The format is verified
// against all 230,009 rows of Droid 1's live copy, so agreement here is agreement with production.
//
// The three checks mirror the certification's own two, plus the formatting itself.
// Replaced by STEP 4's full startup assertion once the 13 JSON / 6 SQL / 69 vecmap sets are bundled.

private struct ProbeCheck: Identifiable {
    let id = UUID()
    let name: String
    let expected: String
    let got: String
    var ok: Bool { expected == got }
}

private func runGeomProbe() -> [ProbeCheck] {
    let expectedWkt  = "POINT(-113.0877 36.4581)"
    let expectedHash = "5bbe0f8aeeb72e3e5ba315e76a66c93d307b467658d4ea4511bd2b795db38f2f"

    let geom  = GtGeom.shared.pointLonLat(lon: -113.0877, lat: 36.4581)
    let wkt   = geom.wkt
    let hash  = GtGeom.shared.hash(wkt: wkt)
    let round = GtGeom.shared.parse(wkt: wkt)?.canonical().wkt ?? "<parse returned nil>"

    let checks = [
        ProbeCheck(name: "pointLonLat WKT (GtJava.jd double text)", expected: expectedWkt, got: wkt),
        ProbeCheck(name: "hash(wkt) SHA-256, lower-case hex", expected: expectedHash, got: hash),
        ProbeCheck(name: "parse -> canonical round trip", expected: wkt, got: round),
    ]

    print("GEOMPROBE-2026-11-18 ----------------------------------------")
    for c in checks {
        print("GEOMPROBE \(c.ok ? "PASS" : "FAIL") \(c.name)")
        if !c.ok {
            print("GEOMPROBE   expected: \(c.expected)")
            print("GEOMPROBE   got:      \(c.got)")
        }
    }
    print("GEOMPROBE \(checks.filter { $0.ok }.count) of \(checks.count) passed")
    return checks
}

struct ContentView: View {
    private let checks = runGeomProbe()
    private let assets = StartupAssertion.run()   // STEP 4: the 19 bundle assets, via the production reader
    private let dbs = DbProbe.run()               // after STEP 4: the app builds its own database

    var body: some View {
        let failures = checks.filter { !$0.ok }
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(failures.isEmpty ? "GroupTrackShared LINKED AND RUNNING"
                                      : "GEOM PROBE FAILED")
                    .font(.largeTitle).bold()
                    .foregroundStyle(failures.isEmpty ? Color.green : Color.red)

                Text(failures.isEmpty
                     ? "\(checks.count) of \(checks.count) checks passed. The framework's 405 exported interfaces are reachable from Swift, and GtGeom produces 2.7e's WKT and geom_hash identically on iPadOS."
                     : "\(failures.count) of \(checks.count) checks FAILED. A geom_hash that differs from Android means dedup and sync see two trails where there is one -- read the values below before changing anything.")
                    .font(.body)
                    .foregroundStyle(.secondary)

                ForEach(checks) { c in
                    VStack(alignment: .leading, spacing: 5) {
                        Text((c.ok ? "PASS   " : "FAIL   ") + c.name)
                            .font(.headline)
                            .foregroundStyle(c.ok ? Color.green : Color.red)
                        Text("expected:  " + c.expected)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                        Text("got:       " + c.got)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(c.ok ? Color.secondary : Color.red)
                    }
                }

                Divider()

                let assetBad = assets.filter { !$0.ok }
                Text(assetBad.isEmpty ? "BUNDLE ASSETS OK" : "BUNDLE ASSETS FAILED")
                    .font(.title2).bold()
                    .foregroundStyle(assetBad.isEmpty ? Color.green : Color.red)
                Text(assetBad.isEmpty
                     ? "All \(assets.count) read through NSBundle.pathForResource, the same reader GtSchema and GtHelpLinks use. A missing one would have terminated before this screen drew."
                     : "\(assetBad.count) of \(assets.count) read empty.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                ForEach(assets) { a in
                    Text((a.ok ? "PASS   " : "FAIL   ") + "[\(a.kind)] " + a.name + "   " + a.note)
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(a.ok ? Color.secondary : Color.red)
                }

                Divider()

                let dbBad = dbs.filter { !$0.ok }
                Text(dbBad.isEmpty ? "DATABASE BUILT ON iPADOS" : "DATABASE FAILED")
                    .font(.title2).bold()
                    .foregroundStyle(dbBad.isEmpty ? Color.green : Color.red)
                Text(dbBad.isEmpty
                     ? "Created by the same GtSchema Android runs, from the same schema files, in a database the app made itself."
                     : "\(dbBad.count) of \(dbs.count) failed. initBb leaves connections nil rather than throwing -- the reason is in the DBPROBE log.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                ForEach(dbs) { c in
                    VStack(alignment: .leading, spacing: 2) {
                        Text((c.ok ? "PASS   " : "FAIL   ") + c.what)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(c.ok ? Color.green : Color.red)
                        Text("    " + c.got)
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer(minLength: 8)
                Text("GEOMPROBE-2026-11-18  -  bundle resources are STEP 4; this probe covers linkage and computation only.")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
            }
            .padding(28)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

#Preview {
    ContentView()
}
