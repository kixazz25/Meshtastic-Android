//  StartupRoad.swift
//  IOSROAD-2026-11-18 -- the app starts at the beginning.
//
//  Android's startup container is ConvoyAuthorityGateScreenV2, which runs
//  StartupHousekeeping.run(context) on a background thread and does not let the map load
//  until it returns. This is that container for iOS, calling the same shared road through
//  GtIosStartup.
//
//  The readout is not decoration: housekeeping can CLEAR EVERY TRAIL (jobSchemaConverge when
//  the schema marker is missing), and "needsReload" is how the app knows to route to the
//  import picker. Silently swallowing that is how a wiped device looks like an empty one.

import SwiftUI
import Combine
import GroupTrackShared

@MainActor
final class StartupRoadModel: ObservableObject {
    enum Phase { case idle, running, done, failed }

    @Published var phase: Phase = .idle
    @Published var lines: [String] = []
    @Published var needsReload = false

    func start() {
        guard phase == .idle else { return }
        phase = .running
        lines = ["housekeeping..."]

        // BLOCKING AND DELIBERATELY SO -- off the main thread, and the map waits for it.
        Task.detached(priority: .userInitiated) {
            let r = GtIosStartup.shared.run()
            GtIosStartup.shared.everyLaunch()
            await MainActor.run {
                var out: [String] = []
                out.append("ran: \(r.ran)")
                out.append("cleared: \(r.cleared)")
                out.append("needsReload: \(r.needsReload)")
                if let e = r.error { out.append("error: \(e)") }
                self.lines = out
                self.needsReload = r.needsReload
                self.phase = (r.error == nil) ? .done : .failed
            }
        }
    }
}

struct StartupRoadScreen: View {
    @StateObject private var model = StartupRoadModel()

    var body: some View {
        Group {
            switch model.phase {
            case .idle, .running:
                VStack(spacing: 14) {
                    ProgressView()
                    Text("GroupTrack — starting")
                        .font(.headline)
                    Text("housekeeping, before any map loads")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            case .failed:
                VStack(alignment: .leading, spacing: 8) {
                    Text("Startup housekeeping failed")
                        .font(.headline).foregroundStyle(.red)
                    ForEach(Array(model.lines.enumerated()), id: \.offset) { _, l in
                        Text(l).font(.system(size: 12, design: .monospaced))
                    }
                    Text("The map is not shown: the road did not complete.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            case .done:
                AppAfterStartup(startup: model.lines, needsReload: model.needsReload)
            }
        }
        .onAppear { model.start() }
    }
}

/// What the app is once the road has run. The map is the backdrop; the probes stay reachable.
struct AppAfterStartup: View {
    let startup: [String]
    let needsReload: Bool

    var body: some View {
        TabView {
            VecMapScreen()
                .tabItem { Label("Map", systemImage: "map") }
            GroupTrackPanelsScreen()
                .tabItem { Label("GroupTrack", systemImage: "square.3.layers.3d") }
            ContentView()
                .tabItem { Label("Probes", systemImage: "checkmark.seal") }
            ComposeProbeScreen()
                .tabItem { Label("Compose", systemImage: "rectangle.on.rectangle") }
        }
        .overlay(alignment: .bottom) {
            if needsReload {
                // Android routes to the import picker here. Saying so is better than pretending.
                Text("housekeeping says: needs reload (Android routes to the import picker)")
                    .font(.system(size: 11, design: .monospaced))
                    .padding(6)
                    .background(.yellow.opacity(0.85))
                    .padding(.bottom, 54)
            }
        }
    }
}
