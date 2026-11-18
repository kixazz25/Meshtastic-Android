/*
 * CMPPIPE-2026-11-18 -- the iOS entry point for shared Compose UI.
 *
 * ComposeUIViewController wraps a composable in a UIViewController, which is the ONLY way
 * Compose content reaches a SwiftUI/UIKit host. Swift sees this as:
 *
 *     GtIosRootKt.gtIpadProbeController()
 *
 * returning a UIViewController -- so no Kotlin default parameters are involved (they do not
 * cross into Swift) and nothing here can throw an NSException Swift cannot catch.
 */
package com.grouptrack.shared.ui

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

// CMPPLISTSKIP-2026-11-18: enforceStrictPlistSanityCheck = false.
//
// CMP refuses to start unless Info.plist carries CADisableMinimumFrameDurationOnPhone=true.
// We tried to add it as INFOPLIST_KEY_CADisableMinimumFrameDurationOnPhone and the build
// SUCCEEDED while the key never reached the bundle -- Xcode only passes through
// INFOPLIST_KEY_* names on its own allowlist, and drops the rest without a word.
//
// This disables the CHECK, not the requirement. The real fix is a genuine Info.plist on the
// target, and without it a real iPad with ProMotion can be capped at 60Hz. PROBE CODE ONLY
// (CODE RULE 3, no marker): it goes when the Info.plist lands.
fun gtIpadProbeController(): UIViewController =
    ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {
        GtIpadProbeScreen()
    }
