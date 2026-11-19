//  VecMapProbe.swift
//  VECMAPWIRE-2026-11-18 -- the 2.7e vector map page, hosted on the iPad.
//
//  Same seam as ComposeProbe.swift: a UIViewControllerRepresentable around the
//  UIViewController Kotlin hands back. The map is a WKWebView inside Compose, so
//  the 17 Compose panels can later sit OVER it rather than beside it.
//
//  The status strip is not decoration. The server reports what it served and from
//  where, exactly as the Nov 6 spike did; without it a blank map has no cause.

import SwiftUI
import UIKit
import Combine   // VECMAPCOMBINE-2026-11-18: MemberImportVisibility is on, so @Published
                 // does not arrive transitively through SwiftUI -- it must be named.
import GroupTrackShared

final class VecMapLog: ObservableObject {
    @Published var lines: [String] = []
    @Published var last: String = "starting..."

    func add(_ name: String, _ body: String) {
        DispatchQueue.main.async {
            if name == "server" { self.last = body }
            self.lines.append("\(name): \(body)")
            if self.lines.count > 200 { self.lines.removeFirst(self.lines.count - 200) }
        }
    }
}

struct VecMapView: UIViewControllerRepresentable {
    let log: VecMapLog

    func makeUIViewController(context: Context) -> UIViewController {
        // VECMAPNAME-2026-11-18: GtVecMap.ios.kt generates GtVecMap_iosKt (the dot becomes
        // an underscore), NOT GtVecMapIosKt. Read from the generated header, not assumed.
        GtVecMap_iosKt.gtVecMapController(onMessage: { name, body in
            log.add(name, body)
        })
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct VecMapScreen: View {
    @StateObject private var log = VecMapLog()
    @State private var showLog = false

    var body: some View {
        ZStack(alignment: .top) {
            VecMapView(log: log)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                Text(log.last)
                    .font(.system(size: 11, design: .monospaced))
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 8).padding(.vertical, 5)
                    .background(.black.opacity(0.72))
                    .foregroundStyle(.green)
                    .onTapGesture { showLog.toggle() }

                if showLog {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 1) {
                            ForEach(Array(log.lines.suffix(60).enumerated()), id: \.offset) { _, l in
                                Text(l)
                                    .font(.system(size: 9, design: .monospaced))
                                    .foregroundStyle(.white)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }.padding(6)
                    }
                    .frame(maxHeight: 260)
                    .background(.black.opacity(0.78))
                }
            }
        }
    }
}
