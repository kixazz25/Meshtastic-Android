//  ComposeProbe.swift
//  CMPPIPE-2026-11-18 -- hosts the shared Compose composable inside SwiftUI.
//
//  This is the whole Swift side of the panel path: a UIViewControllerRepresentable around the
//  UIViewController that Kotlin hands back. Every GroupTrack screen that comes over later goes
//  through this same seam, so it is deliberately as thin as it can be.

import SwiftUI
import UIKit
import GroupTrackShared

struct ComposeProbeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Kotlin top-level function in iosMain -> <FileName>Kt on the Swift side.
        GtIosRootKt.gtIpadProbeController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // Compose drives its own state; nothing to push from SwiftUI.
    }
}

struct ComposeProbeScreen: View {
    var body: some View {
        ComposeProbeView()
            .ignoresSafeArea()
    }
}
