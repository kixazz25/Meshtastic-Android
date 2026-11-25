package com.geeksville.mesh.grouptrack

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geeksville.mesh.convoy.MapSourceManager

/**
 * DLDEFAULTS-2026-10-24 (Fred 10:57-11:00): the map-download settings are set in ONE place -- Map > DOWNLOAD MAPS FOR OFFLINE USE >
 * Download settings (which map sources, Replace existing tiles). Every download's panel starts from them and shows them in one line:
 * "Download with: SAT, TOPO - Replace existing tiles: No -- CHANGE". CHANGE opens the choices in place FOR THIS RUN ONLY -- the defaults
 * are never changed from a download panel ("I would not change the defaults, only change this run").
 */
object GtDownloadDefaults {
    private const val PREF = "gt_download_defaults"
    // DLSETTINGS-2026-11-25: the panel's showing state is the shared DlSettingsLauncher (common launch).

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    /** true once the rider has saved settings; until then each panel keeps its own starting sources, and Replace starts at YES
     *  (REPLACEDEFAULT-2026-11-03, Fred 11:10: the vector download is fast enough to take every tile fresh). */
    fun isSet(ctx: Context): Boolean = prefs(ctx).contains("slots")
    /** the saved map sources (slot names). EMPTY = never saved (CODE RULE 1: empty, not null). */
    fun slots(ctx: Context): Set<String> = prefs(ctx).getStringSet("slots", emptySet())?.toSet() ?: emptySet()
    fun replace(ctx: Context): Boolean = GtDlDefaults.replace(GtSettingsAndroid(ctx))   // REPLACEDEFAULT-2026-11-03: Yes until saved -- DLSETTINGS-2026-11-25: the shared rule
    fun save(ctx: Context, slots: Set<String>, replace: Boolean) {
        prefs(ctx).edit().putStringSet("slots", slots).putBoolean("replace", replace).apply()
    }
    /** a download panel's starting sources: the saved ones that exist on this tablet, else the panel's own pre-selection. */
    fun initialSlots(ctx: Context, available: List<String>, preSelected: Set<String>): Set<String> {
        if (!isSet(ctx)) return preSelected
        val s = slots(ctx).filter { it in available }.toSet()
        return if (s.isEmpty()) preSelected else s
    }
    /** a download panel's starting Replace: the saved one, else the panel's own. */
    // REPLACEDEFAULT-2026-11-03: no per-panel fallback any more -- the saved choice, else YES. A panel that must replace ORs its own.
    fun initialReplace(ctx: Context): Boolean = replace(ctx)
    // DLPANEL-2026-11-01 (Fred 07:42): every download is the vector map + its elevation (VECDL) -- no slot names ("SAT") shown
    fun summary(slots: Collection<String>, replace: Boolean): String =
        "Download: the map and its elevation" +
            "  \u00b7  Replace existing tiles: " + (if (replace) "Yes" else "No")
}


/** DLDEFAULTS: one line on a download panel -- the settings it will use -- and CHANGE (this run only). */
@Composable
fun GtDownloadSummary(slots: Collection<String>, replace: Boolean, changeOpen: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(GtDownloadDefaults.summary(slots, replace), color = Gt.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (changeOpen) "DONE" else "CHANGE", color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onToggle() }.padding(horizontal = 8.dp, vertical = 6.dp))
    }
    if (changeOpen) Text("For this download only \u2014 the defaults are set in Map \u203a Download settings.", color = Gt.text, fontSize = 12.sp)
}

/** DLDEFAULTS: Map > DOWNLOAD MAPS FOR OFFLINE USE > Download settings. */
@Composable
fun GtDownloadSettingsPanel() {
    // DLSETTINGS-2026-11-25: Android's HOST for the SHARED GtDownloadSettingsScreen -- the screen is commonMain, this hands it
    // Android's store. save() still writes the legacy "slots" key the two legacy confirm screens read, until their rows.
    if (!DlSettingsLauncher.showing) return
    val ctx = LocalContext.current
    val all = remember { MapSourceManager.getSlotSources() }   // (slot, short label, source id)
    val sel = remember { GtDownloadDefaults.initialSlots(ctx, all.map { it.first }, all.map { it.first }.toSet()) }
    GtDownloadSettingsScreen(
        replace = GtDownloadDefaults.initialReplace(ctx),
        onSave = { rep -> GtDownloadDefaults.save(ctx, sel, rep); DlSettingsLauncher.close() },
        onClose = { DlSettingsLauncher.close() },
    )
}
