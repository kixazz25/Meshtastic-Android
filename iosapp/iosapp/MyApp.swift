//  MyApp.swift
//  IOSROAD-2026-11-18: the app starts at the BEGINNING -- StartupRoadScreen runs the shared
//  housekeeping road and only then shows the map. Android does the same thing in
//  ConvoyAuthorityGateScreenV2: "one call, every launch, before any map loads."

import SwiftUI

@main struct MyApp: App {
    var body: some Scene {
        WindowGroup {
            StartupRoadScreen()
        }
    }
}
