//  MyApp.swift
//  CMPPIPE-2026-11-18 -- two tabs so the Compose pipe can be tried WITHOUT disturbing the
//  probes that already pass on device (GEOMPROBE 3/3, STARTASSERT 19/19, DBPROBE 8/8).
//  ContentView is untouched.

import SwiftUI

@main struct MyApp: App {
    var body: some Scene {
        WindowGroup {
            TabView {
                // VECMAPWIRE-2026-11-18: the map opens FIRST -- it is what tonight is for,
                // and a headless screenshot then lands on it.
                VecMapScreen()
                    .tabItem { Label("Map", systemImage: "map") }
                ContentView()
                    .tabItem { Label("Probes", systemImage: "checkmark.seal") }
                ComposeProbeScreen()
                    .tabItem { Label("Compose", systemImage: "rectangle.on.rectangle") }
            }
        }
    }
}
