package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import com.geeksville.mesh.convoy.NoteSection
import com.geeksville.mesh.convoy.NoteStyle   // HOME1B-2026-10-23

/* HOME1-2026-10-23: ConvoyNotesPanel (route narratives: a saved route's, and a work-in-progress draft's), COPIED for ONE MAP with
 * the HELP X standard (was CLOSE). NoteSection / notesFromDraft / notesFromRouteId stay the planner's shared data. The planner keeps its copy. */

private val npBg: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF0F1419 
private val npCard: Color get() = Gt.panel  // STD1-2026-10-24 was 0xFF131A24 
private val npGreen: Color get() = Gt.accent  // STD1-2026-10-24 was 0xFF7BB661 
private val npTxt: Color get() = Gt.text  // STD1-2026-10-24 was 0xFFE6EDF3 
private val npDim: Color get() = Gt.dim  // STD1-2026-10-24 was 0xFF8899AA 
private val npFaint: Color get() = Gt.dim  // STD1-2026-10-24 was 0xFF667788 
private val npMono  = FontFamily.Monospace

@Composable
fun GtNotesPanel(
    title: String,
    subtitle: String? = null,
    sections: List<NoteSection>,
    /** Shown at the foot of every notes panel. Null to omit. */
    footer: String? =
        "\u26a0 GroupTrack knows a trail is there. It does not know how wide it is, " +
        "what the surface is like, or what is on it. Zoom in on the satellite view " +
        "before you commit.",
    onClose: () -> Unit = {},
    /**
     * NOTESACTIONS-2026-09-03: what a rider can DO from here, without leaving.
     *
     * ⭐⭐ Fred, 09-03: *"it was frustrating to demo and have to navigate three
     * screens to show route info."* Reading about a ride and then acting on it
     * were separate journeys; these join them.
     * ⚠ Each is NULL BY DEFAULT and its button only renders when supplied, so
     * the draft caller and any future caller are unaffected -- a WIP route has
     * no recipe to rebuild and nothing saved to download.
     */
    onBuildFromRecipe: (() -> Unit)? = null,
    onRouteDetails: (() -> Unit)? = null,
    onDownloadMaps: (() -> Unit)? = null,
    // RIDEFROMNOTES-2026-09-26 (Fred): CREATE RIDE on a saved route's narrative. Optional for the same reason as its
    // neighbours (CODE RULE 1): the panel also shows narratives that are not saved routes; those callers pass nothing.
    onCreateRide: (() -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    val shown = sections.filter { it.lines.isNotEmpty() || it.pairs.isNotEmpty() }

    Box(Modifier.fillMaxSize().background(npBg)) {
        Column(Modifier.fillMaxSize()) {

            // header stays put while the body scrolls
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 14.dp, 12.dp, 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title.ifBlank { "Details" }, color = npGreen, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GtHelpButton("route.notes")   // HOME1-2026-10-23: the HELP X standard (was CLOSE)
                GtCloseButton(onClose)
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = npDim, fontSize = 12.5.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 6.dp))
            }

            if (shown.isEmpty()) {
                Text("Nothing recorded for this yet.",
                    color = npFaint, fontSize = 12.5.sp,
                    modifier = Modifier.padding(16.dp, 10.dp))
                return@Column
            }

            Column(
                Modifier.weight(1f).verticalScroll(scroll)
                    .padding(16.dp, 8.dp, 16.dp, 22.dp)
            ) {
                shown.forEach { s ->
                    // NOTESTWISTY-2026-09-03: a foldable section. ⚠ Keyed on the
                    // HEADING, so a section that gains or loses one does not
                    // inherit another's open state.
                    var open by remember(s.heading) { mutableStateOf(true) }   // HOME1D-2026-10-23 (Fred 21:40): every section opens OPEN (was !s.collapsed)
                    val canFold = s.collapsed && !s.heading.isNullOrBlank()
                    s.heading?.takeIf { it.isNotBlank() }?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (canFold)
                                Modifier.fillMaxWidth().clickable { open = !open }
                            else Modifier.fillMaxWidth()
                        ) {
                            if (canFold) {
                                Text(if (open) "\u25BE" else "\u25B8", color = npDim,
                                    fontSize = 12.sp)
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(it, color = npFaint, fontSize = 12.sp,
                                fontFamily = npMono, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            // ⭐ The COUNT on a folded section. Without it a
                            // rider has no reason to open one.
                            if (canFold && !open && s.lines.isNotEmpty()) {
                                Text("${s.lines.size}", color = npDim, fontSize = 12.sp,
                                    fontFamily = npMono)
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                    }
                    if (!open) {
                        Spacer(Modifier.height(6.dp))
                        return@forEach
                    }
                    when (s.style) {
                        NoteStyle.STATS ->
                            Surface(shape = RoundedCornerShape(7.dp), color = npCard,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(13.dp, 11.dp)) {
                                    s.pairs.forEach { (k, v) ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                            Text(k, color = npDim, fontSize = 12.sp,
                                                modifier = Modifier.weight(1f))
                                            Text(v, color = npTxt, fontSize = 12.sp,
                                                fontFamily = npMono,
                                                fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        NoteStyle.WARN ->
                            s.lines.forEach {
                                Surface(shape = RoundedCornerShape(6.dp),
                                    color = Gt.panel,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                                    Text(it, color = Gt.warn, fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(11.dp, 10.dp))
                                }
                            }
                        NoteStyle.QUIET ->
                            s.lines.forEach {
                                Text(it, color = npFaint, fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(bottom = 4.dp))
                            }
                        NoteStyle.PLAIN ->
                            s.lines.forEach {
                                Text(it, color = npDim, fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(bottom = 3.dp))
                            }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // NOTESACTIONS-2026-09-03: ⭐ ACT WITHOUT LEAVING. Fred, 09-03:
                // "it was frustrating to demo and have to navigate three screens
                // to show route info." Reading about a ride and doing something
                // about it were separate journeys.
                // ⚠ ABOVE the footer, because the footer is the width warning
                // and that should be the last word on the screen.
                val acts = listOfNotNull(
                    onBuildFromRecipe?.let { "BUILD FROM RECIPE" to it },
                    onRouteDetails?.let { "ROUTE DETAILS" to it },
                    onCreateRide?.let { "CREATE A RIDE" to it },   // RIDEFROMNOTES-2026-09-26; RIDENAME-2026-10-29 (D16)
                    onDownloadMaps?.let { "DOWNLOAD MAPS" to it },
                )
                if (acts.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                        acts.forEach { (label, action) ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Gt.panel,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(bottom = 7.dp)
                                    .clickable { action() }
                            ) {
                                Text(label, color = Gt.accent, fontSize = 12.sp,
                                    fontFamily = npMono, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(13.dp, 11.dp))
                            }
                        }
                    }
                }

                footer?.takeIf { it.isNotBlank() }?.let {
                    Surface(shape = RoundedCornerShape(7.dp), color = npCard,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(it, color = npFaint, fontSize = 12.sp, lineHeight = 17.sp,
                            modifier = Modifier.padding(12.dp, 11.dp))
                    }
                }
            }
        }
    }
}

