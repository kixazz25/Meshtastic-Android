package com.grouptrack.shared.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.mapstate.GtBBox   // IOSFRAMEWRITE-2026-11-21
import com.grouptrack.shared.mapstate.GtMapState
import com.grouptrack.shared.spatial.GtSpatial
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.timeIntervalSince1970
import platform.darwin.dispatch_queue_create
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

/**
 * VECSTORE-2026-11-18: the GroupTrack root on iOS, and files inside it.
 * DBPROBE 8/8 proved the app builds Documents/GroupTrack itself, and the seed is a directory
 * copy of Droid 1's GroupTrack root -- so these paths are ANDROID's layout, not the spike's
 * (which put VEC.mbtiles at Documents root).
 */
private fun gtRoot(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull()?.toString() ?: "") + "/GroupTrack"

private fun gtFile(rel: String): String = gtRoot() + "/" + rel

/**
 * VECSTORE-2026-11-18: Droid 1's VEC.mbtiles, read in place -- the same table and raw z/x/y key
 * as MBTilesStore.readTile (tiles(zoom_level, tile_column, tile_row, tile_data), scheme xyz).
 * Read-only, main thread only. Through androidx.sqlite, as the spike concluded; the hand-made
 * cinterop hookup it started with was retired.
 * CODE RULE 1: stmt null = NO STORE (file missing or unreadable), and every read then says
 * "not held" so the server falls through to the internet. That is a real state, not a shortcut.
 */
@OptIn(ExperimentalForeignApi::class)
private class VecStore(val path: String) {
    private val stmt: SQLiteStatement? = if (!NSFileManager.defaultManager.fileExistsAtPath(path)) null else try {
        BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY)
            .prepare("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1")
    } catch (e: Throwable) { null }

    val ok: Boolean get() = stmt != null

    fun read(z: Int, x: Int, y: Int): NSData? {
        val s = stmt ?: return null
        s.reset()
        s.bindLong(1, z.toLong()); s.bindLong(2, x.toLong()); s.bindLong(3, y.toLong())
        if (!s.step()) return null
        val b = s.getBlob(0)
        if (b.isEmpty()) return NSData()
        return b.usePinned { NSData.dataWithBytes(it.addressOf(0), b.size.toULong()) }
    }
}

/**
 * MAPFRAME-2026-11-18: the frame the last session left, from the panel json.
 *
 * The page opens at its own hardcoded default (center [-98.5,39.5] zoom 4, vecmap_main.html:31)
 * and holds no localStorage, so only the host can reposition it. GtMapState reads the panel
 * json in SHARED code; IosFiles and IosImportPaths are already here.
 *
 * mapFrameOk is GroupTrackScreen.kt:3884 verbatim: never 0,0,0,0 (a frame the map never
 * reported), north above south, east above west, and no more than 2 degrees across -- a
 * world-size frame is not a position, it is a map that never settled.
 */
private fun mapFrameOk(s: Double, w: Double, n: Double, e: Double): Boolean =
    !(s == 0.0 && w == 0.0 && n == 0.0 && e == 0.0) && n > s && e > w && (n - s) <= 2.0 && (e - w) <= 2.0

@OptIn(ExperimentalForeignApi::class)
private fun restoreFrame(web: WKWebView, log: (String) -> Unit) {
    val bb = try {
        GtMapState(IosFiles(), IosImportPaths()).readMap(GtMapState.rideKey()).bbox
    } catch (e: Throwable) {
        log("panel json unreadable: " + e.message); return
    }
    if (bb == null) { log("no saved frame in the panel json -- the page keeps its own default"); return }
    if (!mapFrameOk(bb.south, bb.west, bb.north, bb.east)) {
        log("saved frame not usable: " + bb.south + "," + bb.west + "," + bb.north + "," + bb.east); return
    }
    // GroupTrackScreen.kt:1081 verbatim -- fitBounds takes [lats] then [lons]
    web.evaluateJavaScript("fitBounds([" + bb.south + "," + bb.north + "],[" + bb.west + "," + bb.east + "])", null)
    log("restored frame " + bb.south + "," + bb.west + " .. " + bb.north + "," + bb.east)
}

/**
 * VECSTORE-2026-11-18: the page's onViewportChanged -> the SHARED GtSpatial (on its own queue)
 * -> the page's updateX + showX. As SpatialDisplayManager.processViewport on Android.
 * Only the newest viewport is worked; older ones still waiting are skipped.
 *
 * GtSpatial is ALREADY in commonMain here and takes CONNECTIONS, not a path -- and a second one
 * for grouptrack_data.db, which the spike's copy did not have. Connections come from
 * gtOpenDatabase(), the opener DBPROBE proved on device.
 */
@OptIn(ExperimentalForeignApi::class)
private class SpatialRunner(private val log: (String) -> Unit) {
    /**
     * VIEWFILTER-2026-11-19: WHAT THE FILTER SAYS, read at request time.
     * A lambda, not a snapshot: this runner answers viewports for the life of the screen, and a
     * value captured when the map was built would freeze the filter at that moment. Returns
     * (states, checked ids) -- GtSpatial.viewport's two new arguments.
     */
    var display: () -> Pair<Map<String, Int>, Map<String, Set<String>?>> = { emptyMap<String, Int>() to emptyMap() }
    var web: WKWebView? = null
    private val queue = dispatch_queue_create("gt.spatial", null)
    private var spatial: GtSpatial? = null
    private var opened = false
    private var seq = 0
    // IOSFRAMEWRITE-2026-11-21: the last frame written, so a pan that ends where it began writes nothing.
    private var lastFrame = ""

    fun request(args: String) {
        val v = args.trim().removePrefix("[").removeSuffix("]").split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (v.size < 5) return
        val my = ++seq
        dispatch_async(queue) {
            if (my != seq) return@dispatch_async          // a newer viewport already arrived
            if (!opened) {
                opened = true
                val sp = gtFile("grouptrack_spatial.db")
                val dp = gtFile("grouptrack_data.db")
                spatial = if (!NSFileManager.defaultManager.fileExistsAtPath(sp)) null else try {
                    val data = if (NSFileManager.defaultManager.fileExistsAtPath(dp))
                        try { gtOpenDatabase(dp, true) } catch (e: Throwable) { null } else null
                    GtSpatial(gtOpenDatabase(sp, true), data)
                } catch (e: Throwable) {
                    dispatch_async(dispatch_get_main_queue()) { log("could not open grouptrack_spatial.db: " + e.message) }
                    null
                }
            }
            val sp2 = spatial
            if (sp2 == null) {
                dispatch_async(dispatch_get_main_queue()) { log("NO spatial database in " + gtRoot()) }
                return@dispatch_async
            }
            val t0 = NSDate().timeIntervalSince1970
            val b = try { run { val d = display(); sp2.viewport(v[0], v[1], v[2], v[3], v[4].toInt(), d.first, d.second) } } catch (e: Throwable) {
                dispatch_async(dispatch_get_main_queue()) { log("query failed: " + e.message) }
                return@dispatch_async
            }
            val ms = ((NSDate().timeIntervalSince1970 - t0) * 1000).toInt()
            // IOSFRAMEWRITE-2026-11-21: save the frame the rider is looking at, so the next launch opens here
            // instead of the page default (zoom 4, mid-US) where trails are below TRAILS_MIN_ZOOM.
            // readMap -> copy -> saveMap because saveMap writes the WHOLE snapshot. Only frames
            // mapFrameOk accepts are written, so a wide view never overwrites a usable one.
            run {
                val fn = v[0]; val fs = v[1]; val fe = v[2]; val fw = v[3]
                if (mapFrameOk(fs, fw, fn, fe)) {
                    val key = "" + fs + "," + fw + "," + fn + "," + fe
                    if (key != lastFrame) {
                        lastFrame = key
                        try {
                            val st = GtMapState(IosFiles(), IosImportPaths())
                            val k = GtMapState.rideKey()
                            st.saveMap(k, st.readMap(k).copy(bbox = GtBBox(fs, fw, fn, fe, v[4])))
                        } catch (ex: Throwable) {
                            dispatch_async(dispatch_get_main_queue()) { log("frame save failed: " + ex.message) }
                        }
                    }
                }
            }
            dispatch_async(dispatch_get_main_queue()) {
                val w = web ?: return@dispatch_async
                b.js.forEach { js ->
                    w.evaluateJavaScript(js) { res, _ ->
                        val r = res?.toString() ?: ""
                        if (r.startsWith("ERR")) log("PAGE " + r.take(160))
                    }
                }
                log("app z" + v[4].toInt() + " · " + b.counts.entries.joinToString(" · ") { it.key + " " + it.value } + " · query " + ms + " ms")
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class GtVecServer(private val log: (String) -> Unit) : NSObject(), WKURLSchemeHandlerProtocol {
    private val live = mutableSetOf<Any>()          // tasks not yet stopped by the web view (main thread only)
    private var template: String? = null
    private var templateAsked = false
    private val waiting = mutableListOf<(String?) -> Unit>()
    private var assets = 0; private var stored = 0; private var tiles = 0; private var empty = 0; private var missing = 0

    // VECSTORE-2026-11-18: Droid 1's tile store, at ANDROID's path under the GroupTrack root.
    private val store = VecStore(gtFile("maps/tiles/VEC.mbtiles"))

    /** OFFLINE MODE (Android's STATUS toggle): stored tiles only, even with internet. */
    var offline = false

    private fun status(last: String) =
        log((if (store.ok) "store OPEN" else "NO STORE") + (if (offline) " · INTERNET OFF" else "") +
            " · assets $assets · from store $stored · internet $tiles · empty $empty · missing $missing · $last")

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
        // VECSTORE-2026-11-18: stored first, then the internet -- VecOnline's rule on Android.
        store.read(z, x, y)?.let { d ->
            stored++; status("store z$z/$x/$y ${d.length} B")
            return reply(task, url, 200, "application/x-protobuf", d, "1")
        }
        if (offline) {
            empty++; status("not held z$z/$x/$y (internet off)")
            return reply(task, url, 200, "application/x-protobuf", NSData(), "0")
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
fun GtVecMap(
    modifier: Modifier = Modifier,
    onMessage: (String, String) -> Unit,
    /** MAPWIRE-2026-11-19: the host receives the web view so it can evaluate the SAME JS the
     *  Android host evaluates (zoom, gtSet3D, gtNorthUp, gtSetHeadingUp, setTrailStyles).
     *  REQUIRED, not defaulted (CODE RULE 1): a host that wants no map controls passes {} and
     *  says so at the call site, rather than a default hiding a miswiring. */
    onWeb: (WKWebView) -> Unit,
    /** SEARCHFIT2-2026-11-19: the host receives the TILE SERVER too, because E.Status is one
     *  flag on it -- GtVecServer.offline already means Android's VecOnline.localOnly. REQUIRED
     *  (CODE RULE 1): a host with no STATUS control passes {} and says so. */
    /** OFFLINEFLOW-2026-11-19: E.Status, as a VALUE rather than by handing the host the
     *  server. true = stored tiles only, even with internet -- Android's VecOnline.localOnly.
     *  REQUIRED (CODE RULE 1): false is a legitimate value and the host always knows which. */
    offline: Boolean,
    /** VIEWFILTER-2026-11-19: the display states and checked ids the FILTER holds, read on EVERY
     *  viewport. REQUIRED (CODE RULE 1): a host with no filter passes
     *  `{ emptyMap<String, Int>() to emptyMap() }` and says so at the call site. A default here
     *  would hide exactly the miswiring Fred found at 16:10 -- a filter that changed nothing. */
    displayState: () -> Pair<Map<String, Int>, Map<String, Set<String>?>>,
) {
    // VECSTORE-2026-11-18: onViewportChanged drives the spatial layer, exactly as on Android.
    val runner = remember { SpatialRunner { s -> onMessage("spatial", s) } }
    runner.display = displayState   // VIEWFILTER-2026-11-19: re-bound every recomposition, so it never goes stale
    val messages = remember {
        GtVecMessages { fn, args ->
            onMessage(fn, args)
            if (fn == "onViewportChanged") runner.request(args)
            // MAPFRAME-2026-11-18: reposition once the page exists -- before onMapReady there is
            // no map object to move.
            if (fn == "onMapReady") runner.web?.let { w -> restoreFrame(w) { s -> onMessage("frame", s) } }
        }
    }
    val server = remember { GtVecServer { s -> onMessage("server", s) } }
    // OFFLINEFLOW-2026-11-19: assigned every composition, so the server always carries the
    // host's current answer. GtVecServer.offline is read per tile request, not cached.
    server.offline = offline
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
            runner.web = web   // VECSTORE-2026-11-18
            onWeb(web)         // MAPWIRE-2026-11-19: the host's handle on the page
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
        // MAPWIRE-2026-11-19: the bare Map tab is the CONTROL -- no launch layer, so no host
        // controls and nothing to drive the page with.
        // The bare Map tab is the CONTROL: no launch layer, so no STATUS control either.
        // VIEWFILTER-2026-11-19: the CONTROL passes NO filter, explicitly. This tab has no launch
        // layer, so there is no FILTER to read and every type draws -- which is the point of a
        // control. Said at the call site rather than hidden in a default, per the parameter's own
        // contract (CODE RULE 1).
        GtVecMap(Modifier.fillMaxSize(), onMessage, onWeb = {}, offline = false,
            displayState = { emptyMap<String, Int>() to emptyMap() })
    }
