package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtHttp
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.mapsource.GtMapSpecEnv
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * GTIOSHTTP-2026-11-16: the iPad's two HTTP services.
 *
 * TWO IMPLEMENTATIONS, DELIBERATELY NOT MERGED. GtHttp.get and GtMapSpecEnv.httpGet look alike and are
 * not. Measured against their Android originals 2026-11-16, five behaviours differ, and one shared
 * helper would have erased all five:
 *
 *   GtHttp.get (GtHttpAndroid, HttpURLConnection)      GtMapSpecEnv.httpGet (GtMapSpecAndroid, OkHttp)
 *   - connect 15 s FIXED + read = the parameter        - ONE total callTimeout = the parameter
 *   - sends "User-Agent: GroupTrack/2.5"               - sends no User-Agent
 *   - on non-2xx READS AND LOGS the error body         - logs the code only
 *     (truncated at 2000 chars + " ...[truncated]")
 *   - tag "TrailImporter", 2.7e's own texts            - texts "TileJSON HTTP n" / "TileJSON: msg"
 *   - null only on non-2xx or throw                    - CAN RETURN NULL ON A 200 (body?.string())
 *
 * Callers and their budgets (measured): GtHttp.get at 60_000, and 180_000 for Overpass
 * (GtTrailImport:212/:251/:287); GtMapSpecEnv.httpGet at 6_000, once, for TileJSON (GtMapSpec:117).
 *
 * ================================================================================================
 * WHY THIS BLOCKS, AND THE ONE RULE THAT MAKES BLOCKING SAFE
 * ================================================================================================
 * Both interface members are SYNCHRONOUS (fun get(...): String?, not suspend) and NSURLSession is
 * asynchronous, so the actual must block on a dispatch semaphore. Android tolerates a mistake here
 * because HttpURLConnection throws NetworkOnMainThreadException and forces callers off the main
 * thread. iOS HAS NO SUCH GUARD: a 180 s Overpass call on the main thread freezes the app until the
 * watchdog kills it.
 *
 * So the main-thread check below THROWS rather than hanging -- per SVCFIRST-2026-11-16, a service
 * misused must announce itself instead of degrading into something that reads as a bug.
 *
 * Consequence to wire later, recorded now so it is not discovered: GtMapSpec's TileJSON fetch runs
 * from the map, and the spike's WKURLSchemeHandler is a MAIN-THREAD callback -- GtVecMap.ios.kt's
 * withTemplate fetches TileJSON asynchronously with a callback for exactly this reason. On iOS that
 * call must be moved to a background queue before it reaches httpGet.
 *
 * ================================================================================================
 * RE-ENTRANCY IS A REQUIREMENT, NOT AN ACCIDENT (Fred 2026-11-16 14:09)
 * ================================================================================================
 * "just know the download queue is submitting 4 http tasks at the same time."
 *
 * So fetch() holds NO shared mutable state: every call builds its own NSURLSession, its own request,
 * its own result holder and its own semaphore, and the session is invalidated when the call returns.
 * Four concurrent calls cannot see each other.
 *
 * A single shared NSURLSession was the obvious economy and is REJECTED: one session serialises its
 * own connection pool at HTTPMaximumConnectionsPerHost, which would throttle the queue behind the
 * very object meant to serve it.
 *
 * WIRING CONSTRAINT FOR WHOEVER DRIVES THE QUEUE ON iOS: four blocking calls occupy four threads.
 * Dispatch them on a queue (or dispatcher) at least four wide, or the queue starves itself.
 * ================================================================================================
 */

/** One call's outcome. A class, not captured vars: the completion handler mutates an instance. */
private class HttpOut {
    var code: Long = 0L
    var body: String? = null
    var failure: String? = null      // transport-level failure text, null when the server answered
    var timedOut: Boolean = false
}

/**
 * The MECHANISM, shared; every POLICY decision stays with the caller below.
 *
 * @param requestTimeoutMs  NSURLSession timeoutIntervalForRequest -- "wait for more data", the
 *                          closest analogue of HttpURLConnection's readTimeout.
 * @param resourceTimeoutMs NSURLSession timeoutIntervalForResource -- the whole transfer's budget,
 *                          the analogue of OkHttp's callTimeout.
 * @param waitMs            how long we block before abandoning the wait. A backstop only: the
 *                          session's own timeouts should fire first.
 */
@OptIn(ExperimentalForeignApi::class)
private fun fetch(
    url: String,
    userAgent: String?,
    requestTimeoutMs: Int,
    resourceTimeoutMs: Int,
    waitMs: Long,
): HttpOut {
    if (NSThread.isMainThread()) {
        throw IllegalStateException(
            "GTIOSHTTP-2026-11-16: synchronous HTTP called on the MAIN THREAD for " + url +
                " -- GtHttp.get and GtMapSpecEnv.httpGet block; call them from a background queue."
        )
    }
    val out = HttpOut()
    val nsurl = NSURL.URLWithString(url)
    if (nsurl == null) { out.failure = "malformed URL"; return out }

    val req = NSMutableURLRequest.requestWithURL(nsurl)
    if (userAgent != null) req.setValue(userAgent, forHTTPHeaderField = "User-Agent")

    val cfg = NSURLSessionConfiguration.defaultSessionConfiguration()
    cfg.timeoutIntervalForRequest = requestTimeoutMs / 1000.0
    cfg.timeoutIntervalForResource = resourceTimeoutMs / 1000.0
    val session = NSURLSession.sessionWithConfiguration(cfg)

    val sem = dispatch_semaphore_create(0)
    session.dataTaskWithRequest(req) { data: NSData?, response: NSURLResponse?, error: NSError? ->
        out.code = (response as? NSHTTPURLResponse)?.statusCode ?: 0L
        if (error != null) out.failure = error.localizedDescription
        if (data != null) out.body = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        dispatch_semaphore_signal(sem)
    }.resume()

    out.timedOut = dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, waitMs * 1_000_000L)) != 0L
    session.finishTasksAndInvalidate()
    return out
}

/**
 * GtHttpAndroid's behaviour, member for member. Tag "TrailImporter" is 2.7e's own, kept deliberately
 * so trail-import logs read the same on both platforms (the Android file records the same reason).
 */
object IosHttp : GtHttp {
    private val log = gtLogger("TrailImporter")

    override fun get(url: String, readTimeoutMs: Int): String? {
        val r = fetch(
            url = url,
            userAgent = "GroupTrack/2.5",
            requestTimeoutMs = readTimeoutMs,
            // Android's connect timeout is a FIXED 15 s on top of the read budget; NSURLSession has no
            // separate connect timeout, so the total gets the same 15 s of headroom.
            resourceTimeoutMs = readTimeoutMs + 15_000,
            waitMs = (readTimeoutMs + 15_000).toLong(),
        )
        if (r.timedOut || r.failure != null) {
            val why = if (r.timedOut) "timed out" else (r.failure ?: "unknown")
            log.e { "HTTP FAILED [NSError] " + why }
            log.e { "HTTP FAILED for URL: " + url }
            return null
        }
        val code = r.code.toInt()
        if (code !in 200..299) {
            // NSURLSession delivers a non-2xx body in `data`, where Java split it onto errorStream.
            val body = r.body ?: "(no error body)"
            val trimmed = if (body.length > 2000) body.substring(0, 2000) + " ...[truncated]" else body
            log.e { "HTTP " + code + " " + NSHTTPURLResponse.localizedStringForStatusCode(r.code) + " -- server said: " + trimmed }
            log.e { "HTTP " + code + " for URL: " + url }
            return null
        }
        val text = r.body ?: ""
        log.i { "HTTP " + code + " OK, " + text.length + " chars" }
        return text
    }
}

/**
 * GtMapSpecAndroid.Env's behaviour, member for member.
 *
 * readAsset goes through iosBundleAsset -- the ONE bundle reader, shared with the JSON asset socket,
 * so there is a single place where "asset not in the bundle" is decided. Android throws here too
 * (assets.open) and GtMapSpec catches, so the throw is the contract, not a defect.
 */
object IosMapSpecEnv : GtMapSpecEnv {
    private val log = gtLogger("GtIosMapSpec")

    override fun readAsset(name: String): String = iosBundleAsset(name)

    override fun httpGet(url: String, timeoutMs: Int): String? {
        val r = fetch(
            url = url,
            userAgent = null,                 // OkHttp sent none; do not add one
            requestTimeoutMs = timeoutMs,
            resourceTimeoutMs = timeoutMs,    // callTimeout is a TOTAL budget
            waitMs = timeoutMs.toLong(),
        )
        if (r.timedOut || r.failure != null) {
            log.e { "TileJSON: " + (if (r.timedOut) "timed out" else (r.failure ?: "unknown")) }
            return null
        }
        val code = r.code.toInt()
        if (code !in 200..299) {
            log.e { "TileJSON HTTP " + code }
            return null
        }
        // Android returns r.body?.string(), which is nullable EVEN ON A 200. Mirrored: no "" default.
        return r.body
    }
}
