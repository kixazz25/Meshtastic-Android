package com.grouptrack.shared.platform

import com.grouptrack.shared.help.GtHelpAssets
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle

/**
 * CERTVALUES-2026-11-16: ONE bundle-asset reader for the iPad.
 *
 * Android reads every asset the same way -- ctx.assets.open(name).bufferedReader().use { readText() } --
 * through GtImportPlatform.readAsset, GtHelpAssets.text and GtMapSpecEnv.readAsset. Three interfaces,
 * one behaviour. This is that behaviour once, so the iPad cannot drift between them the way it would
 * with three hand-written copies (the V3 rule: anything compared is produced identically).
 *
 * ⛔ IT MUST THROW WHEN THE ASSET IS ABSENT, exactly as assets.open does, because callers depend on the
 * throw rather than on a null:
 *   GtHelpLinks.load wraps it in runCatching and falls back to an EMPTY link table
 *   GtMapSpec.spec() catches and returns null ("the catch stays in GtMapSpec, not here")
 *   GtHelpAssets' own header: "⚠ text must THROW when the asset is missing"
 * Returning "" instead would turn every one of those into a silent empty-but-successful read.
 */
internal fun iosBundleAsset(name: String): String {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot + 1) else ""
    val p = NSBundle.mainBundle.pathForResource(stem, ext)
        ?: throw IllegalStateException("CERTVALUES-2026-11-16: asset not in the bundle: " + name)
    return FileSystem.SYSTEM.read(p.toPath()) { readUtf8() }
}

/**
 * IOSHELP-2026-11-19: the same lookup, returning the FILE PATH instead of the contents.
 * ⚠ WHY A SECOND READER AND NOT iosBundleAsset(): grouptrack_manual.html is 10.8 MB of base64-embedded
 * screenshots. Reading that into a Kotlin String and handing it to WKWebView.loadHTMLString would copy
 * it twice through memory for no reason; loadFileURL reads it off disk. The three documents are the only
 * assets large enough for the difference to matter.
 * CODE RULE 1: null = not in the bundle, which is a real state -- the documents are staged by
 * gt_iosres_2026-11-19_v2.py and a build made before that script ran will not have them. The caller says
 * so on screen rather than throwing, because a missing document is not a reason to lose the map.
 */
internal fun iosBundleAssetPath(name: String): String? {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot + 1) else ""
    return NSBundle.mainBundle.pathForResource(stem, ext)
}

/** GtHelp.AndroidAssets' one member, on the bundle. */
class IosHelpAssets : GtHelpAssets {
    override fun text(name: String): String = iosBundleAsset(name)
}
