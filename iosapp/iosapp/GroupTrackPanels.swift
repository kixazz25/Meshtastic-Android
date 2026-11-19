//  GroupTrackPanels.swift
//  PANELSWIFT-2026-11-19 -- A-I and M on the iPad's glass.
//
//  Same seam as VecMapProbe.swift: a UIViewControllerRepresentable around the
//  UIViewController Kotlin hands back. The difference is what is inside it --
//  GtVecMap with GroupTrackLaunchLayer OVER it, which is the 11-18 note
//  ("so the 17 Compose panels can later sit OVER the map") actually done.
//
//  The status strip is the CERTIFICATION INSTRUMENT here, not decoration. Every
//  launch point whose function is a map-side job it does not have yet reports
//  through onMessage -- zoom, setDisplay, trailsChanged, centre. So the strip
//  shows that a control FIRED even when the map cannot yet answer it, which is
//  the difference between "not wired" and "broken".

import SwiftUI
import UIKit
import Combine   // MemberImportVisibility is on, so @Published must be named.
import GroupTrackShared

final class GroupTrackPanelLog: ObservableObject {
    @Published var lines: [String] = []
    @Published var last: String = "starting..."

    func add(_ name: String, _ body: String) {
        DispatchQueue.main.async {
            // The panels' own reports are what we are watching, so they win the strip.
            if name != "spatial" { self.last = "\(name): \(body)" }
            self.lines.append("\(name): \(body)")
            if self.lines.count > 200 { self.lines.removeFirst(self.lines.count - 200) }
        }
    }
}

struct GroupTrackPanelsView: UIViewControllerRepresentable {
    let log: GroupTrackPanelLog

    func makeUIViewController(context: Context) -> UIViewController {
        // NAME READ FROM THE HEADER'S swift_name ATTRIBUTE, not its @interface name:
        // the header declares @interface GTSGtIosPanelsKt with swift_name("GtIosPanelsKt"),
        // and Swift sees the swift_name because the importer strips the GTS prefix.
        // Precedents: GtIosRootKt and GtVecMap_iosKt are written the same way.
        // Kotlin default parameters do not cross into Swift, so onMessage is passed.
        GtIosPanelsKt.gtGroupTrackController(onMessage: { name, body in
            log.add(name, body)
        })
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct GroupTrackPanelsScreen: View {
    @StateObject private var log = GroupTrackPanelLog()
    @State private var showLog = false
    @State private var hideTabs = true

    var body: some View {
        ZStack(alignment: .topTrailing) {
            GroupTrackPanelsView(log: log)
                .ignoresSafeArea()

            // PANELHUD-2026-11-19: the instrument sits TOP-RIGHT and compact.
            // A full-width strip pinned to .top covered A.MENU, which lives in the
            // upper LEFT -- the HUD must not occupy panel real estate, because the
            // thing being certified is exactly whether the panels have the glass.
            VStack(alignment: .trailing, spacing: 6) {
                HStack(spacing: 6) {
                    // The tab bar sits on the manual's BOTTOM ROW (44 of 87 sub-topics),
                    // so it is hidden by default and summoned when a tab is wanted.
                    Button(action: { hideTabs.toggle() }) {
                        Text(hideTabs ? "TABS" : "HIDE")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .padding(.horizontal, 7).padding(.vertical, 4)
                            .background(.black.opacity(0.72))
                            .foregroundStyle(.white)
                            .clipShape(Capsule())
                    }
                    Button(action: { showLog.toggle() }) {
                        Text(showLog ? "LOG -" : "LOG +")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .padding(.horizontal, 7).padding(.vertical, 4)
                            .background(.black.opacity(0.72))
                            .foregroundStyle(.green)
                            .clipShape(Capsule())
                    }
                }

                if showLog {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(log.last)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(.green)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        ScrollView {
                            VStack(alignment: .leading, spacing: 1) {
                                ForEach(Array(log.lines.suffix(60).enumerated()), id: \.offset) { _, l in
                                    Text(l)
                                        .font(.system(size: 9, design: .monospaced))
                                        .foregroundStyle(.white)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                        }
                        .frame(height: 220)
                    }
                    .padding(6)
                    .frame(width: 430)
                    .background(.black.opacity(0.80))
                }
            }
            .padding(.top, 10)
            .padding(.trailing, 10)
        }
        // iOS 16+; iPadOS 16 is the declared 2.8b floor (TARGETFLOOR).
        .toolbar(hideTabs ? .hidden : .visible, for: .tabBar)
        // PANELHUDFIX-2026-11-19 (Fred 09:38, stranded on the Compose tab with no bar):
        // a TabView keeps its tabs alive, so a hide applied from inside THIS tab can persist
        // after switching away -- and the screen you land on has no TABS button to undo it.
        // Restoring on disappear makes that structurally impossible: the hide can only ever
        // apply while the screen that owns the button is the one on display.
        .onDisappear { hideTabs = false }
    }
}
