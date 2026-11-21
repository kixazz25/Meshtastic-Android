@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.grouptrack.shared.platform

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.storage.GtStorage
import kotlinx.cinterop.useContents
import okio.Path.Companion.toPath
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.drawViewHierarchyInRect

private val log = gtLogger("GtIosCapture")

/** Android keeps its shots in a cache dir; this is the iPad's equivalent, under the storage root. */
private const val SHOT_DIR = "issue_shots"

/** Android's quality (GtIssue.kt:123 / GtRideStatsSend.kt:51): JPEG 85. */
private const val JPEG_QUALITY = 0.85

/**
 * SCREENCAP-2026-11-20: the iPad's half of [GtScreenCapture].
 *
 * ⛔⛔ READ THIS BEFORE TRUSTING AN ATTACHMENT. GroupTrack's map is a WKWebView
 * (GtVecMap.ios.kt). `drawViewHierarchyInRect` renders the view hierarchy of THIS process, and
 * WKWebView draws its content OUT OF PROCESS -- so web content can come back BLANK in this capture.
 * Android's PixelCopy reads the real window surface and has no such problem. The map screenshot is
 * the single attachment this was built for, and it is exactly the one at risk.
 *
 * The right API for web content is `WKWebView.takeSnapshotWithConfiguration(completionHandler:)`,
 * which is ASYNC and hands back its own UIImage. Composing the two -- chrome from the hierarchy, map
 * from the web view -- is a separate function and is deliberately NOT guessed at here.
 *
 * ⚠ So: this writes a JPEG of the screen, verified by compiling and by the file appearing. WHETHER
 * THE MAP IS IN IT is a device question that must be answered on the sideloaded iPad before any
 * issue report is trusted. Said here rather than discovered from a white rectangle in an email.
 */
class IosScreenCapture(private val storage: GtStorage = iosStorage()) : GtScreenCapture {

    private fun dir() = storage.root().toString().toPath() / SHOT_DIR

    override fun capture(fileName: String): String? {
        // keyWindow is deprecated under multiple scenes but is what this app has one of, and it is
        // already how IosArtifactSink reaches the presenting controller.
        val window = UIApplication.sharedApplication.keyWindow
        if (window == null) {
            log.e { "SCREENCAP-2026-11-20: no key window -- nothing to capture for " + fileName }
            return null
        }
        return try {
            val bounds = window.bounds
            val size = bounds.useContents { CGSizeMake(size.width, size.height) }
            // opaque = false, scale = 0.0 -> the device's own scale, as a screenshot should be.
            UIGraphicsBeginImageContextWithOptions(size, false, 0.0)
            try {
                // afterScreenUpdates = true: draw what is on screen NOW, not the previous frame.
                window.drawViewHierarchyInRect(bounds, afterScreenUpdates = true)
                val image = UIGraphicsGetImageFromCurrentImageContext()
                if (image == null) {
                    log.e { "SCREENCAP-2026-11-20: no image from the context for " + fileName }
                    return null
                }
                val data: NSData = UIImageJPEGRepresentation(image, JPEG_QUALITY)
                    ?: run {
                        log.e { "SCREENCAP-2026-11-20: JPEG encoding returned nothing for " + fileName }
                        return null
                    }
                val fs = okio.FileSystem.SYSTEM
                fs.createDirectories(dir())
                val target = dir() / fileName
                if (!data.writeToFile(target.toString(), atomically = true)) {
                    log.e { "SCREENCAP-2026-11-20: could not write " + target }
                    return null
                }
                log.i { "SCREENCAP-2026-11-20: wrote " + target }
                target.toString()
            } finally {
                // ⚠ ALWAYS, even on an early return: an unbalanced image context leaks for the life
                // of the process.
                UIGraphicsEndImageContext()
            }
        } catch (e: Throwable) {
            log.e { "SCREENCAP-2026-11-20: capture(" + fileName + ") failed: " + e.message }
            null
        }
    }

    override fun clear(): Int = try {
        val fs = okio.FileSystem.SYSTEM
        val d = dir()
        if (!fs.exists(d)) 0 else {
            var n = 0
            fs.list(d).forEach { p -> runCatching { fs.delete(p); n++ } }
            log.i { "SCREENCAP-2026-11-20: cleared " + n + " shot(s)" }
            n
        }
    } catch (e: Throwable) {
        log.e { "SCREENCAP-2026-11-20: clear() failed: " + e.message }
        0
    }
}
