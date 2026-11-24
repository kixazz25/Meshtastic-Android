package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.grouptrack.GtCheck
import com.geeksville.mesh.grouptrack.GtScheme
import com.grouptrack.shared.text.GtFmt

/**
 * TRAILTABLE-2026-11-24: MOVED from RouteModule.kt into commonMain, bytes moved by script and
 * not retyped. The four FILTER toggles came with it -- they had one call site each, all here,
 * and are pure logic over the shared TrailFilterState. Their only host-specific act is the
 * redraw, which is now the GtAiFlow.filterChanged seam (Fred 15:26: "it is basically changing
 * the map filter and a new map view request").
 */
private fun gtToggleType(t: String) { com.grouptrack.shared.filter.TrailFilterState.toggleCategory(t); com.geeksville.mesh.grouptrack.GtAiFlow.filterChanged() }

private fun gtToggleUse(label: String) {
        val f = com.grouptrack.shared.filter.TrailFilterState
        val motor = label == "Motorized"
        if (!motor && label != "Non-motorized") return
        val mOn = f.use != "NON-MOTORIZED"; val nOn = f.use != "MOTORIZED"
        val m2 = if (motor) !mOn else mOn; val n2 = if (motor) nOn else !nOn
        if (!m2 && !n2) return   // at least one use stays on
        f.setUse(if (m2 && n2) "ALL" else if (m2) "MOTORIZED" else "NON-MOTORIZED"); com.geeksville.mesh.grouptrack.GtAiFlow.filterChanged()
    }

private fun gtToggleLand(pub: Boolean) {
        val f = com.grouptrack.shared.filter.TrailFilterState
        val p = f.land != "PRIVATE"; val q = f.land != "PUBLIC"
        val p2 = if (pub) !p else p; val q2 = if (pub) q else !q
        if (!p2 && !q2) return   // at least one land type stays on (as FILTER itself)
        f.setLand(if (p2 && q2) "ALL" else if (p2) "PUBLIC" else "PRIVATE"); com.geeksville.mesh.grouptrack.GtAiFlow.filterChanged()
    }

private fun gtToggleRestricted() { com.grouptrack.shared.filter.TrailFilterState.setRestricted(!com.grouptrack.shared.filter.TrailFilterState.restricted); com.geeksville.mesh.grouptrack.GtAiFlow.filterChanged() }

/**
 * PLANTABLE-2026-11-03 (Fred 07:39-07:41, mockup approved): the plan's trail table -- by use, one line per trail type with yes / no and
 * its public / private counts in the search area; GREEN = included in the search, RED = left out. SEE / CHANGE FILTER changes it.
 */
@androidx.compose.runtime.Composable
fun GtTrailTable(t: com.grouptrack.shared.route.GtRoute.SelTable) {
    // PLANCHECK-2026-11-03 (Fred 08:28): a tick box in front of every line -- ticked = included (green numbers), unticked = left out
    //  (red); the box or the line changes FILTER, the map redraws, the numbers recolour.
    val sc = GtScheme.current.value
    val inc = sc.good; val out = sc.bad; val txt = sc.panelText
    fun fmt(n: Int) = GtFmt.d(n)
    val bold = androidx.compose.ui.text.font.FontWeight.Bold
    val end = androidx.compose.ui.text.style.TextAlign.End
    val gap = Modifier.width(8.dp)
    // PLANTOAST-2026-11-03 (Fred 08:30): every tap says what changed in FILTER
    fun said(msg: String) = com.geeksville.mesh.grouptrack.GtRouteLaunch.toast(msg)
    val tf = com.grouptrack.shared.filter.TrailFilterState
    fun landTap(pub: Boolean) {
        val before = tf.land
        gtToggleLand(pub)
        if (tf.land == before) said("At least one land type stays on")
        else said("FILTER changed: " + (if (pub) "Public" else "Private") + " land " +
            (if ((if (pub) tf.land != "PRIVATE" else tf.land != "PUBLIC")) "included" else "left out"))
    }
    fun useTap(u: String) {
        val before = tf.use
        gtToggleUse(u)
        if (tf.use == before) said("At least one use stays on")
        else said("FILTER changed: $u " + (if ((if (u == "Motorized") tf.use != "NON-MOTORIZED" else tf.use != "MOTORIZED")) "included" else "left out"))
    }
    fun typeTap(ty: String) { gtToggleType(ty); said("FILTER changed: $ty " + (if (tf.isOn(ty)) "included" else "left out")) }
    fun rstTap() { gtToggleRestricted(); said("FILTER changed: Restricted " + (if (tf.restricted) "included" else "left out")) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (t.total == 0) { Text("Counting the trails…", color = txt, fontSize = 13.sp); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TRAILS IN THE SEARCH AREA (" + t.area + ")", color = txt, fontSize = 12.sp, fontWeight = bold, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(96.dp).clickable { landTap(true) }) {
                GtCheck(t.publicOn); Spacer(gap); Text("Public", color = txt, fontSize = 12.sp, fontWeight = bold) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(96.dp).clickable { landTap(false) }) {
                GtCheck(t.privateOn); Spacer(gap); Text("Private", color = txt, fontSize = 12.sp, fontWeight = bold) }
        }
        var lastUse = ""
        for (r in t.rows) {
            if (r.use != lastUse) {
                lastUse = r.use
                val u = r.use
                val switchable = u == "Motorized" || u == "Non-motorized"
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(enabled = switchable) { useTap(u) }) {
                    if (switchable) { GtCheck(r.useOn); Spacer(gap) }
                    Text(if (switchable) u else "$u (always searched)", color = txt, fontSize = 13.sp, fontWeight = bold)
                }
            }
            val ty = r.type
            val pubIn = r.useOn && r.typeOn && t.publicOn
            val privIn = r.useOn && r.typeOn && t.privateOn
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).clickable { typeTap(ty) }) {
                Spacer(Modifier.width(24.dp))
                GtCheck(r.typeOn); Spacer(gap)
                Text(r.type, color = txt, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(fmt(r.pub), color = if (pubIn) inc else out, fontSize = 13.sp, fontWeight = bold, textAlign = end, modifier = Modifier.width(96.dp))
                Text(fmt(r.priv), color = if (privIn) inc else out, fontSize = 13.sp, fontWeight = bold, textAlign = end, modifier = Modifier.width(96.dp))
            }
        }
        // RESTRICTEDNEVER-2026-11-03 (Fred 20:18-20:20): restricted is never in a route -- information only, no tick box
        if (t.restricted > 0 || t.pieces > 0) Text("Restricted (never in a route): " + fmt(t.restricted) + " trail" + (if (t.restricted == 1) "" else "s") +
            " \u00b7 " + fmt(t.pieces) + " with restricted pieces (routed on their open parts)", color = out, fontSize = 13.sp, fontWeight = bold,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(fmt(t.searched) + " searched · " + fmt(t.total - t.searched) + " left out", color = txt, fontSize = 13.sp, fontWeight = bold,
            modifier = Modifier.padding(top = 10.dp))
        Text("Green numbers are included, red are left out. Tick a box to include those trails — SEE / CHANGE FILTER for " +
            "everything else.", color = txt, fontSize = 11.sp)
    }
}
