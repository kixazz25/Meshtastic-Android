package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.geeksville.mesh.convoy.ConvoySettingsRepository
import com.geeksville.mesh.convoy.SettingsRepoCertOld
import com.geeksville.mesh.convoy.certOldDataStore17b2
import com.grouptrack.shared.settings.GtRideSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * STEP17G2B2-2026-11-12 (2.8 step 17, G2b-2) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the ride settings,
 * OLD (2.7e ConvoySettingsRepository on DataStore) = NEW (GtRideSettings on the settings socket). Request mode "ride17b2".
 *   input    the rider's DataStore "convoy_settings", every key and value -- READ ONLY (certRawSnapshot); the day number
 *   seeded   OLD: a cert DataStore filled with those values; NEW: a cert store filled by fillMissing -- THE ONE-TIME COPY, certified here
 *   ops      the same changes on both (every setter, remove two carts, reinstate one, purge), then every value and the raw cart set
 *   cleared  clearRemovedCarts on both;  empty: both stores emptied -> the defaults;  copy: fillMissing never overwrites a saved key
 * Both cert stores are emptied and the NEW one deleted at the end. Lines: S17C <tab> key <tab> SAME|DIFF|INPUT <tab> value.
 */
object GtCert17b2 {
    private const val TAG = "CertRun"
    fun enc(v: Any?): String = GtCert17b.enc(v)

    fun run(app: Context, w: java.io.Writer) = runBlocking {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0
        fun check(k: String, old: String, new: String) {
            if (old == new) { same++; w.write("S17C\t$k\tSAME\t$new\n") } else { diff++; w.write("S17C\t$k\tDIFF\n  OLD\t$old\n  NEW\t$new\n") }
        }
        val day = java.time.LocalDate.now().toEpochDay()
        val raw = ConvoySettingsRepository(app).certRawSnapshot().toSortedMap()
        w.write("# S17C droid ${android.os.Build.MODEL}: ride settings, DataStore (2.7e) vs GtRideSettings; the rider's DataStore has ${raw.size} keys\n")
        w.write("S17C\tinput-day\tINPUT\t$day\n")
        for ((k, v) in raw) w.write("S17C\tinput|$k\tINPUT\t${enc(v)}\n")

        val o = SettingsRepoCertOld(app); val ods = app.certOldDataStore17b2
        val settings = GtSettingsAndroid(app); val ns = settings.store("gtcert17b2_new")
        val n = GtRideSettings(settings, "gtcert17b2_new") { day }
        suspend fun reads(tag: String) {
            check("$tag|signalDropMinutes", "${o.signalDropMinutes.first()}", "${n.signalDropMinutes()}")
            check("$tag|signalLostMinutes", "${o.signalLostMinutes.first()}", "${n.signalLostMinutes()}")
            check("$tag|offTrackMiles", "${o.offTrackMiles.first()}", "${n.offTrackMiles()}")
            check("$tag|offTrackMilesSaved", "${o.offTrackMilesSaved.first()}", "${n.offTrackMilesSaved()}")
            check("$tag|admissionWindowHours", "${o.admissionWindowHours.first()}", "${n.admissionWindowHours()}")
            check("$tag|removedCartsForToday", "${o.removedCartsForToday.first().toSortedMap()}", "${n.removedCartsForToday().toSortedMap()}")
            val oSet = ods.data.first()[stringSetPreferencesKey("removed_carts")]
            check("$tag|removed_carts(raw)", enc(oSet), enc(ns.getStringSet("removed_carts", null)))
        }
        // seed: OLD from the rider's values; NEW through the one-time copy
        ods.edit { pr -> pr.clear(); for ((k, v) in raw) when (v) {
            is Float -> pr[floatPreferencesKey(k)] = v; is Int -> pr[intPreferencesKey(k)] = v; is Long -> pr[longPreferencesKey(k)] = v
            is Boolean -> pr[booleanPreferencesKey(k)] = v; is String -> pr[stringPreferencesKey(k)] = v
            is Set<*> -> pr[stringSetPreferencesKey(k)] = v.map { it.toString() }.toSet() } }
        ns.edit { clear() }
        check("copy|count", "${raw.size}", "${n.fillMissing(raw)}")
        reads("seeded")
        // the same changes on both
        o.setSignalDropMinutes(3.5f); n.setSignalDropMinutes(3.5f)
        o.setSignalLostMinutes(7f); n.setSignalLostMinutes(7f)
        o.setOffTrackMiles(0.4f); n.setOffTrackMiles(0.4f)
        o.setAdmissionWindowHours(2); n.setAdmissionWindowHours(2)
        o.removeCart("!a1b2c3d4", "Fred"); n.removeCart("!a1b2c3d4", "Fred")
        o.removeCart("!e5f6a7b8", "Lead/Car"); n.removeCart("!e5f6a7b8", "Lead/Car")
        o.reinstateCart("!a1b2c3d4"); n.reinstateCart("!a1b2c3d4")
        o.purgeStaleRemovedCarts(); n.purgeStaleRemovedCarts()
        reads("ops")
        o.clearRemovedCarts(); n.clearRemovedCarts()
        reads("cleared")
        ods.edit { it.clear() }; ns.edit { clear() }
        reads("empty")
        // the copy never overwrites a key already saved in the new store
        n.setSignalDropMinutes(5f)
        val c2 = n.fillMissing(mapOf("signal_drop_minutes" to 9f, "admission_window_hours" to 3))
        check("copy|no-overwrite", "5.0 / 1 copied / 3", "${n.signalDropMinutes()} / $c2 copied / ${n.admissionWindowHours()}")
        ods.edit { it.clear() }; ns.edit { clear() }; app.deleteSharedPreferences("gtcert17b2_new")
        w.write("# S17C done: $same SAME, $diff DIFF in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT ride17b2: $same SAME, $diff DIFF")
    }
}
