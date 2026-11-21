package com.grouptrack.shared.platform

import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

/**
 * BUILDINFO-2026-11-20: the iPad's half of [GtBuildInfo].
 *
 * ⚠ ONE REAL DELTA, named here rather than discovered from a confusing report. Android's
 * android.os.Build.MODEL is the SPECIFIC model ("SM-X200"); UIKit's UIDevice.model is the FAMILY
 * ("iPad"). The precise identifier ("iPad14,5") is only available through uname/sysctl, which is a
 * cinterop call and is not worth adding until a defect actually needs the exact hardware. So an
 * iPad report says less about the device than an Android one does -- that is a known limit of this
 * implementation, not a bug in the report.
 *
 * Both members follow the interface's rule and cannot throw: a missing Info.plist key becomes "?".
 */
class IosBuildInfo : GtBuildInfo {

    override fun appVersion(): String {
        val b = NSBundle.mainBundle
        val short = b.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        val build = b.objectForInfoDictionaryKey("CFBundleVersion") as? String
        return when {
            short != null && build != null -> short + " (" + build + ")"
            short != null -> short
            build != null -> build
            // Not a throw, and not a crash: see GtBuildInfo's note. "?" is the honest answer.
            else -> "?"
        }
    }

    override fun device(): String {
        val d = UIDevice.currentDevice
        // "Apple" stands where Android puts MANUFACTURER, so the two lines read the same shape.
        return "Apple " + d.model + " / " + d.systemName + " " + d.systemVersion
    }
}
