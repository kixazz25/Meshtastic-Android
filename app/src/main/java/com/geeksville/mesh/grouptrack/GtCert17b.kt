package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.ConvoyConfig
import com.geeksville.mesh.convoy.ConvoySessionManager
import com.geeksville.mesh.convoy.SessionCertOld
import com.grouptrack.shared.settings.GtConfig
import com.grouptrack.shared.settings.GtSession

/**
 * STEP17G2B1-2026-11-12 (2.8 step 17, G2b-1) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the settings
 * socket + the session + the shared constants, OLD (2.7e) = NEW (shared). Request mode "session17b".
 *   input   the rider's own store (grouptrack_user), every key and value -- READ ONLY; the iPad seeds its test store from these lines
 *   read    every session answer on the rider's store: OLD ConvoySessionManager = NEW GtSession (no write)
 *   write   both seeded with a COPY of the rider's store (OLD: gtcert17b_old through the generated SessionCertOld; NEW: gtcert17b_new),
 *           the same writes run on both, every key and value compared; then clearSession on both (both must be empty); both copies deleted
 *   config  every ConvoyConfig true constant = GtConfig
 * Lines: S17B <tab> key <tab> SAME|DIFF <tab> value ; footer "# S17B done: N SAME, N DIFF in S s".
 */
object GtCert17b {
    private const val TAG = "CertRun"
    fun enc(v: Any?): String = when (v) {
        null -> "null"; is String -> "s:$v"; is Int -> "i:$v"; is Long -> "l:$v"; is Float -> "f:$v"; is Boolean -> "b:$v"
        is Set<*> -> "set:" + v.map { it.toString() }.sorted().joinToString("\u001f")
        else -> "?:$v"
    }

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0
        fun check(k: String, old: String, new: String) {
            if (old == new) { same++; w.write("S17B\t$k\tSAME\t$new\n") } else { diff++; w.write("S17B\t$k\tDIFF\n  OLD\t$old\n  NEW\t$new\n") }
        }
        val settings = GtSettingsAndroid(app)
        val live = settings.store(GtSession.STORE).all().toSortedMap()
        w.write("# S17B droid ${android.os.Build.MODEL}: session + settings socket + constants; the rider's store has ${live.size} keys\n")
        for ((k, v) in live) w.write("S17B\tinput|$k\tINPUT\t${enc(v)}\n")

        // ---- read: the rider's own store, no writes ----
        val o = ConvoySessionManager; val n = GtSession(settings, GtSession.STORE, System::currentTimeMillis)
        check("read|getUserId", "${o.getUserId(app)}", "${n.getUserId()}")
        check("read|isSignedIn", "${o.isSignedIn(app)}", "${n.isSignedIn()}")
        check("read|isSubscribed", "${o.isSubscribed(app)}", "${n.isSubscribed()}")
        check("read|isSubscriptionExpired", "${o.isSubscriptionExpired(app)}", "${n.isSubscriptionExpired()}")
        check("read|termsAccepted", "${o.termsAccepted(app)}", "${n.termsAccepted()}")
        check("read|privacyAccepted", "${o.privacyAccepted(app)}", "${n.privacyAccepted()}")
        check("read|isOrganizer", "${o.isOrganizer(app)}", "${n.isOrganizer()}")
        check("read|getFirstName", o.getFirstName(app), n.getFirstName())
        check("read|getEmail", o.getEmail(app), n.getEmail())
        check("read|getZipCode", o.getZipCode(app), n.getZipCode())
        check("read|getCity", o.getCity(app), n.getCity())
        check("read|getState", o.getState(app), n.getState())
        check("read|getSearchRadius", "${o.getSearchRadius(app)}", "${n.getSearchRadius()}")
        check("read|route(true)", o.resolveLaunchRoute(app, true).name, n.resolveLaunchRoute(true).name)
        check("read|route(false)", o.resolveLaunchRoute(app, false).name, n.resolveLaunchRoute(false).name)

        // ---- write: on COPIES of the rider's store ----
        val so = settings.store("gtcert17b_old"); val sn = settings.store("gtcert17b_new")
        for (s in listOf(so, sn)) s.edit {
            clear()
            for ((k, v) in live) when (v) {
                is String -> putString(k, v); is Int -> putInt(k, v); is Long -> putLong(k, v); is Float -> putFloat(k, v)
                is Boolean -> putBoolean(k, v); is Set<*> -> putStringSet(k, v.map { it.toString() }.toSet())
            }
        }
        val no = SessionCertOld; val nn = GtSession(settings, "gtcert17b_new", System::currentTimeMillis)
        no.setCity(app, "Kanab"); nn.setCity("Kanab")
        no.setState(app, "UT"); nn.setState("UT")
        no.setZipCode(app, "84741"); nn.setZipCode("84741")
        no.setSearchRadius(app, 40); nn.setSearchRadius(40)
        no.saveUser(app, "cert-user-1", "cert-google-1", "cert@example.com", "Cert", "Rider"); nn.saveUser("cert-user-1", "cert-google-1", "cert@example.com", "Cert", "Rider")
        no.acceptTerms(app); nn.acceptTerms()
        no.acceptPrivacy(app); nn.acceptPrivacy()
        no.setOrganizer(app, true); nn.setOrganizer(true)
        no.setSubscriptionExpiry(app, 1893456000000L); nn.setSubscriptionExpiry(1893456000000L)
        val ao = so.all().toSortedMap(); val an = sn.all().toSortedMap()
        for (k in (ao.keys + an.keys).toSortedSet()) check("write|$k", enc(ao[k]), enc(an[k]))
        check("write|route(true)", no.resolveLaunchRoute(app, true).name, nn.resolveLaunchRoute(true).name)
        no.clearSession(app); nn.clearSession()
        check("write|cleared", "${so.all().size}", "${sn.all().size}")
        app.deleteSharedPreferences("gtcert17b_old"); app.deleteSharedPreferences("gtcert17b_new")

        // ---- the shared constants ----
        check("config|MAP_DEFAULT_ZOOM", "${ConvoyConfig.MAP_DEFAULT_ZOOM}", "${GtConfig.MAP_DEFAULT_ZOOM}")
        check("config|LOCAL_TILE_BASE", ConvoyConfig.LOCAL_TILE_BASE, GtConfig.LOCAL_TILE_BASE)
        check("config|MAP_GROUP_ZOOM_PADDING", "${ConvoyConfig.MAP_GROUP_ZOOM_PADDING}", "${GtConfig.MAP_GROUP_ZOOM_PADDING}")
        check("config|MAP_CART_ZOOM", "${ConvoyConfig.MAP_CART_ZOOM}", "${GtConfig.MAP_CART_ZOOM}")
        check("config|MAP_MIN_ZOOM", "${ConvoyConfig.MAP_MIN_ZOOM}", "${GtConfig.MAP_MIN_ZOOM}")
        check("config|BLINK_LOST_MS", "${ConvoyConfig.BLINK_LOST_MS}", "${GtConfig.BLINK_LOST_MS}")
        check("config|BLINK_DROP_MS", "${ConvoyConfig.BLINK_DROP_MS}", "${GtConfig.BLINK_DROP_MS}")
        check("config|MARKER_SIZE_LARGE_DP", "${ConvoyConfig.MARKER_SIZE_LARGE_DP}", "${GtConfig.MARKER_SIZE_LARGE_DP}")
        check("config|MARKER_SIZE_MEDIUM_DP", "${ConvoyConfig.MARKER_SIZE_MEDIUM_DP}", "${GtConfig.MARKER_SIZE_MEDIUM_DP}")
        check("config|API_BASE_URL", ConvoyConfig.API_BASE_URL, GtConfig.API_BASE_URL)
        check("config|V3_FEATURES_ENABLED", "${ConvoyConfig.V3_FEATURES_ENABLED}", "${GtConfig.V3_FEATURES_ENABLED}")
        check("config|PAYWALL_ENABLED", "${ConvoyConfig.PAYWALL_ENABLED}", "${GtConfig.PAYWALL_ENABLED}")
        check("config|IS_STANDALONE_BUILD", "${ConvoyConfig.IS_STANDALONE_BUILD}", "${GtConfig.IS_STANDALONE_BUILD}")
        check("config|DOWNLOAD_ZOOM_MIN", "${ConvoyConfig.DOWNLOAD_ZOOM_MIN}", "${GtConfig.DOWNLOAD_ZOOM_MIN}")

        w.write("# S17B done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT session17b: $same SAME, $diff DIFF")
    }
}
