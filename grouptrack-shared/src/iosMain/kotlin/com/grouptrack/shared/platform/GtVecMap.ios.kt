package com.grouptrack.shared.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithURL
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIViewController
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/*
 * VECMAPIOS-2026-11-18 -- transcribed from GroupTrackSpike/_orig/GtVecMap.ios.kt.step2d
 * (VECIPAD-2026-11-06, spike step 2c/2d). The iPad side of VecMapServer plus the Android
 * JavascriptInterface. 11-15 decision: TRANSCRIBE, DO NOT REPAIR.
 *
 *   gtvec://local/a/<file>     -> <bundle>/vecmap/<file>     (the 2.7e viewer, shipped by VECOPAQUE)
 *   gtvec://local/t/z/x/y.pbf  -> OpenFreeMap over the internet  (the VEC store returns with the seed)
 *
 * WKWebView cannot take over https://, so the page's https://gtvec.local/ is rewritten to
 * gtvec://local/ as files are served -- THE PAGE IS NOT EDITED.
 *
 * The SHIM gives the page a window.Android whose every method posts "name<0x01>[args]".
 * C28-26319 B3: all 14 names ARRIVE; only onViewportChanged currently drives anything, and
 * the catch-all Proxy makes an unhandled name indistinguishable from a handled one at the
 * page -- dispatch gaps are SILENT BY CONSTRUCTION. That is wiring, not feasibility.
 */
private const val SCHEME = "gtvec"
private const val OLD_BASE = "https://gtvec.local/"
private const val NEW_BASE = "gtvec://local/"
private const val TILEJSON = "https://tiles.openfreemap.org/planet"

private val SHIM = """
(function () {
  function post(s) { try { window.webkit.messageHandlers.gt.postMessage(s); } catch (e) {} }
  window.Android = new Proxy({}, { get: function (t, n) {
    if (typeof n !== 'string' || n === 'then' || n === 'toJSON') return undefined;
    return function () { post(n + '\u0001' + JSON.stringify(Array.prototype.slice.call(arguments))); };
  } });
  var o = console.log.bind(console);
  console.log = function () { post('console\u0001' + Array.prototype.slice.call(arguments).map(String).join(' ')); o.apply(null, arguments); };
  window.addEventListener('error', function (e) { post('jserror\u0001' + e.message + ' @' + e.filename + ':' + e.lineno); });
  post('shim\u0001ready');
})();
"""

private class GtVecMessages(private val onMessage: (String, String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        val text = didReceiveScriptMessage.body.toString()
        val i = text.indexOf('\u0001')
        if (i < 0) onMessage("?", text) else onMessage(text.substring(0, i), text.substring(i + 1))
    }
}

/**
 * VECMAPIOS-2026-11-18 divergence 1: the viewer is read from the APP BUNDLE.
 * VECOPAQUE-2026-11-18 ships app/src/main/assets/vecmap as an opaque folder, so it lands at
 * <bundle>/vecmap/<rel> with its directory structure intact -- the same shape Android serves
 * from assets/vecmap/. Verified in the built app: 69 files, including
 * vecmap/fonts/Noto Sans Italic/0-255.pbf.
 */
private fun bundleVecFile(rel: String): String? {
    val root = NSBundle.mainBundle.resourcePath ?: return null
    return "$root/vecmap/$rel"
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class GtVecServer(private val log: (String) -> Unit) : NSObject(), WKURLSchemeHandlerProtocol {
    private val live = mutableSetOf<Any>()          // tasks not yet stopped by the web view (main thread only)
    private var template: String? = null
    private var templateAsked = false
    private val waiting = mutableListOf<(String?) -> Unit>()
    private var assets = 0; private var tiles = 0; private var empty = 0; private var missing = 0

    // VECMAPIOS-2026-11-18 divergence 2: no store in v1. The oracle's VecStore returns null for
    // every read when Documents/VEC.mbtiles is absent, which is an unseeded iPad exactly. The
    // store returns with the seed step, on androidx.sqlite.
    private fun status(last: String) =
        log("NO STORE (v1) · assets $assets · internet $tiles · empty $empty · missing $missing · $last")

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val task = startURLSchemeTask
        live.add(task)
        val url = task.request.URL ?: return
        val path = url.path ?: ""
        when {
            path.startsWith("/a/") -> asset(task, url, path.removePrefix("/a/"))
            path.startsWith("/t/") -> tile(task, url, path)
            else -> reply(task, url, 404, "text/plain", null)
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        live.remove(stopURLSchemeTask)
    }

    private fun reply(task: WKURLSchemeTaskProtocol, url: NSURL, code: Long, mime: String, data: NSData?, held: String? = null) {
        if (!live.contains(task)) return            // the page gave up on it -- answering now would crash
        live.remove(task)
        val h = mutableMapOf<Any?, Any?>("Content-Type" to mime, "Access-Control-Allow-Origin" to "*",
            "Access-Control-Expose-Headers" to "X-GT-Held")
        if (held != null) h["X-GT-Held"] = held
        task.didReceiveResponse(NSHTTPURLResponse(uRL = url, statusCode = code, HTTPVersion = "HTTP/1.1", headerFields = h))
        if (data != null) task.didReceiveData(data)
        task.didFinish()
    }

    private fun asset(task: WKURLSchemeTaskProtocol, url: NSURL, rel: String) {
        if (rel.contains("..")) return reply(task, url, 404, "text/plain", null)
        val file = bundleVecFile(rel)
        var data = if (file == null) null else NSData.dataWithContentsOfFile(file)
        if (data == null) {
            missing++; status("MISSING $rel")
            // as VecMapServer: a font range we do not bundle is served EMPTY, never 404
            // (MapLibre drops the tile otherwise)
            return if (rel.startsWith("fonts")) reply(task, url, 200, "application/x-protobuf", NSData())
            else reply(task, url, 404, "text/plain", null)
        }
        if (rel.endsWith(".html") || rel.endsWith(".js") || rel.endsWith(".json")) {
            val s = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            if (s != null && s.contains(OLD_BASE))
                data = NSString.create(string = s.replace(OLD_BASE, NEW_BASE)).dataUsingEncoding(NSUTF8StringEncoding)
        }
        assets++; status("served $rel")
        reply(task, url, 200, mimeOf(rel), data)
    }

    private fun tile(task: WKURLSchemeTaskProtocol, url: NSURL, path: String) {
        val seg = path.removePrefix("/t/").removeSuffix(".pbf").split("/")
        val z = seg.getOrNull(0)?.toIntOrNull(); val x = seg.getOrNull(1)?.toIntOrNull(); val y = seg.getOrNull(2)?.toIntOrNull()
        if (z == null || x == null || y == null || z > 14) {
            empty++; return reply(task, url, 200, "application/x-protobuf", NSData(), "0")
        }
        withTemplate { t ->
            val src = t?.let { NSURL.URLWithString(it.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")) }
            if (src == null) {
                empty++; status("no tile address (TileJSON failed)")
                reply(task, url, 200, "application/x-protobuf", NSData(), "0")
            } else NSURLSession.sharedSession.dataTaskWithURL(src) { d, r, _ ->
                val code = (r as? NSHTTPURLResponse)?.statusCode ?: 0L
                dispatch_async(dispatch_get_main_queue()) {
                    if (d != null && code == 200L) { tiles++; status("tile z$z/$x/$y ${d.length} B"); reply(task, url, 200, "application/x-protobuf", d, "1") }
                    else { empty++; status("tile z$z/$x/$y HTTP $code"); reply(task, url, 200, "application/x-protobuf", NSData(), "0") }
                }
            }.resume()
        }
    }

    /** The tile address comes from OpenFreeMap's TileJSON (it names the current planet build), fetched once -- as VectorTiles.tileTemplate. */
    private fun withTemplate(then: (String?) -> Unit) {
        template?.let { return then(it) }
        waiting.add(then)
        if (templateAsked) return
        templateAsked = true
        NSURLSession.sharedSession.dataTaskWithURL(NSURL.URLWithString(TILEJSON)!!) { d, _, _ ->
            val s = d?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() } ?: ""
            val t = Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(s)?.groupValues?.get(1)
            dispatch_async(dispatch_get_main_queue()) {
                template = t; if (t == null) templateAsked = false
                status(if (t != null) "TileJSON ok" else "TileJSON FAILED")
                val w = waiting.toList(); waiting.clear(); w.forEach { it(t) }
            }
        }.resume()
    }

    private fun mimeOf(p: String) = when {
        p.endsWith(".html") -> "text/html"
        p.endsWith(".js") -> "application/javascript"
        p.endsWith(".css") -> "text/css"
        p.endsWith(".json") -> "application/json"
        p.endsWith(".pbf") -> "application/x-protobuf"
        p.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }
}

/** The 2.7e vector map page, in a WKWebView, inside Compose -- which is how the 17 Compose
 *  panels will later sit OVER the map rather than beside it. */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun GtVecMap(modifier: Modifier = Modifier, onMessage: (String, String) -> Unit) {
    val messages = remember { GtVecMessages(onMessage) }
    val server = remember { GtVecServer { s -> onMessage("server", s) } }
    UIKitView(
        factory = {
            val config = WKWebViewConfiguration()
            config.setURLSchemeHandler(server, forURLScheme = SCHEME)
            config.userContentController.addScriptMessageHandler(messages, "gt")
            config.userContentController.addUserScript(
                WKUserScript(source = SHIM,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true))
            val web = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)
            web.inspectable = true
            web.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString(NEW_BASE + "a/vecmap_main.html")!!))
            web
        },
        modifier = modifier,
    )
}

/**
 * Swift entry point: GtVecMap_iosKt.gtVecMapController(onMessage:) -- VECMAPNAME-2026-11-18:
 * a file named X.ios.kt generates X_iosKt, not XIosKt (cf. GtRx_iosKt in the same header).
 * CMPPLISTSKIP-2026-11-18: enforceStrictPlistSanityCheck stays false until a real Info.plist
 * carries CADisableMinimumFrameDurationOnPhone.
 */
fun gtVecMapController(onMessage: (String, String) -> Unit): UIViewController =
    ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {
        GtVecMap(Modifier.fillMaxSize(), onMessage)
    }
