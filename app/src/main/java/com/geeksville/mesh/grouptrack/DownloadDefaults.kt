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
    /** the Download settings panel is showing. */
    val open = mutableStateOf(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    /** true once the rider has saved settings; until then each panel keeps its own starting choice (every source, no replace). */
    fun isSet(ctx: Context): Boolean = prefs(ctx).contains("slots")
    /** the saved map sources (slot names). EMPTY = never saved (CODE RULE 1: empty, not null). */
    fun slots(ctx: Context): Set<String> = prefs(ctx).getStringSet("slots", emptySet())?.toSet() ?: emptySet()
    fun replace(ctx: Context): Boolean = prefs(ctx).getBoolean("replace", false)
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
    fun initialReplace(ctx: Context, fallback: Boolean): Boolean = if (isSet(ctx)) replace(ctx) else fallback
    fun summary(slots: Collection<String>, replace: Boolean): String =
        "Download with: " + (if (slots.isEmpty()) "no map source" else slots.sorted().joinToString(", ")) +
            "  \u00b7  Replace existing tiles: " + (if (replace) "Yes" else "No")
}

/** DLDEFAULTS: the GroupTrack check -- a bordered box with a check mark when on, blank when off (the STD1H rule), readable on both maps. */
@Composable
fun GtCheck(checked: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).border(2.dp, Gt.text, RoundedCornerShape(4.dp)).background(Gt.panel, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center) {
        if (checked) Text("\u2713", color = Gt.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
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
    if (!GtDownloadDefaults.open.value) return
    val ctx = LocalContext.current
    val all = remember { MapSourceManager.getSlotSources() }   // (slot, short label, source id)
    var sel by remember { mutableStateOf(GtDownloadDefaults.initialSlots(ctx, all.map { it.first }, all.map { it.first }.toSet())) }
    var rep by remember { mutableStateOf(GtDownloadDefaults.initialReplace(ctx, false)) }
    Dialog(onDismissRequest = { GtDownloadDefaults.open.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).width(360.dp).background(Gt.panel, RoundedCornerShape(14.dp))
                .border(1.dp, Gt.line, RoundedCornerShape(14.dp)).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Download settings", color = Gt.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GtHelpButton("map.dl_settings")
                GtCloseButton({ GtDownloadDefaults.open.value = false })
            }
            Text("Every map download uses these. Each download shows them, and CHANGE there alters that download only.",
                color = Gt.text, fontSize = 13.sp)
            Text("MAP SOURCES", color = Gt.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            all.forEach { (slot, label, _) ->
                val on = slot in sel
                Row(Modifier.fillMaxWidth().clickable { sel = if (on) sel - slot else sel + slot }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GtCheck(on)
                    Text("$slot  \u00b7  $label", color = Gt.text, fontSize = 14.sp)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { rep = !rep }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GtCheck(rep)
                Text("Replace existing tiles", color = Gt.text, fontSize = 14.sp)
            }
            if (sel.isEmpty()) Text("Pick at least one map source.", color = Gt.text, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GtButton("SAVE", { GtDownloadDefaults.save(ctx, sel, rep); GtDownloadDefaults.open.value = false }, enabled = sel.isNotEmpty())
                GtButton("CANCEL", { GtDownloadDefaults.open.value = false })
            }
        }
    }
}
