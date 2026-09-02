package com.geeksville.mesh.convoy

import android.content.Context
import android.location.Geocoder
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UnifiedSearch -- ONE draggable magnifying-glass search FAB, shared by both maps.
 *
 * Self-contained: owns the FAB, the search bar (5 chips on one line + text field),
 * Return-to-execute, the results list, and per-mode routing. Routes to EXISTING
 * engines (searchByName/assignNameSequence for artifacts; Geocoder for Area) -- it
 * adds no new search logic.
 *
 * This component does NOT save/restore any map-state JSON. Artifact selection is
 * handed off to the caller via onOpenDetail (which opens the universal
 * ArtifactDetailPanel, where FIT lives). Area mode recenters the given webView.
 *
 * @param mapContext "convoy" | "planning" -- carried for future per-map behavior;
 *                   today both maps want the identical search (guardrail: do NOT
 *                   start adding per-map flags -- if you need to, the abstraction
 *                   is wrong).
 * @param webView    the map this instance controls (caller passes the unwrapped
 *                   WebView; convoy unwraps its MutableState, planning passes the
 *                   plain WebView? directly).
 * @param context    for Geocoder + SpatialDbManager.init.
 * @param onOpenDetail (type, id) -> open the artifact's detail card. Supplied per
 *                   screen so each map opens detail its own way.
 * @param stackDown  false (default, convoy): FAB at bottom, bar/results grow UP.
 *                   true (planning): FAB at top, bar/results grow DOWN -- for a
 *                   top-anchored FAB sitting beneath the "?".
 * @param modifier   placement of the FAB column (caller positions it; e.g. above
 *                   or below the "?").
 */
@Composable
fun UnifiedSearch(
    mapContext: String,
    webView: WebView?,
    context: Context,
    onOpenDetail: (String, String) -> Unit,
    stackDown: Boolean = false,
    /* ⚠ DEFAULT FALSE. UnifiedSearch is shared — the convoy map uses it too,
     * and only the planner asks for this. */
    startOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    val mono = FontFamily.Monospace
    val panelBg = Color(0xEE131820)
    val fieldBg = Color(0xFF0D1520)
    val txtB = Color(0xFFCCDDEE)
    val txtD = Color(0xFF7A8DA0)
    val cBlue = Color(0xFF4DA6FF)

    // Chip definitions: label -> (mode-key, color). "area" is the geocode mode;
    // the four artifact modes carry the lowercase TABLE name searchByName expects.
    val chips = listOf(
        "Area" to ("area" to Color(0xFF4DA6FF)),
        "Track" to ("tracks" to Color(0xFF4DA6FF)),
        "Route" to ("routes" to Color(0xFFBC8CFF)),
        "Trail" to ("trails" to Color(0xFF1CF0A0)),
        "Waypoint" to ("waypoints" to Color(0xFFD29922))
    )

    /* SEARCHOPEN-2026-08-29: startOpen SEEDS this, it does not force it.
     *
     * ⛔ Holding the bar open for a whole step means a rider who closes it
     * watches it reopen with no way to dismiss it — the same fault as the Map
     * Features relaunch.
     *
     * ⚠ remember(startOpen), not remember(): a plain remember takes its value
     * ONCE, so a rider reaching the step after this composable already exists
     * would never see it open. Keying on the flag re-seeds when it flips.
     */
    var barOpen by remember(startOpen) { mutableStateOf(startOpen) }
    var selMode by remember { mutableStateOf("area") }   // default = Area (design)
    var term by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<ArtifactResult>()) }
    var showResults by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        if (term.isBlank()) return
        if (selMode == "area") {
            // -- AREA: geocode -> setView + showSearchCenter --
            scope.launch {
                val addrs = withContext(Dispatchers.IO) {
                    try { Geocoder(context).getFromLocationName(term, 5) } catch (e: Exception) { null }
                }
                if (addrs.isNullOrEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        "Place not found -- try adding a state (e.g. \"Zion UT\")",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val a = addrs[0]
                webView?.evaluateJavascript("setView(${a.latitude}, ${a.longitude}, 13)", null)
                webView?.evaluateJavascript(
                    "try{showSearchCenter(${a.latitude}, ${a.longitude})}catch(e){}", null
                )
                // [AREA FIX 2026-06-23] Seed lastViewport* to the NEW frame so the draw
                // queries the searched area, not the stale pre-search bbox. setView needs
                // the map to settle before getBounds is valid -> post ~550ms (mirrors the
                // HTML reportViewport 450ms timer + margin). Same round-trip every other
                // reposition in ConvoyScreen uses.
                webView?.postDelayed({
                    webView?.evaluateJavascript(
                        "try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}",
                        null
                    )
                }, 550)
                results = emptyList(); showResults = false
                barOpen = false
            }
        } else {
            // -- ARTIFACT: searchByName -> assignNameSequence -> results list --
            scope.launch {
                val raw = withContext(Dispatchers.IO) {
                    SpatialDbManager.init(context)
                    SpatialDbManager.searchByName(selMode, term)
                }
                results = assignNameSequence(raw)
                showResults = true
                barOpen = false   // Return closes the bar; the results list shows by the FAB
            }
        }
    }

    // -- The search bar (chips + text field) --
    @Composable
    fun SearchBar() {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = panelBg,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // SEARCHFIT-2026-09-02: a CLOSE. ⛔ The FAB that used to dismiss
                // this is gone (icons were dropped for words), so the bar had no
                // exit at all. Fred, 09-01: "it is insane trying to get back to
                // the desktop once we have things open."
                // ⚠ Boxed, because three times this week a close control existed
                // and was invisible -- Map Keys, Map Features and Route+ all had
                // one too small and too dim to read as a button.
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.width(26.dp).height(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1B2027))
                            .border(1.dp, Color(0xFF47505A), RoundedCornerShape(4.dp))
                            .clickable { barOpen = false; showResults = false }
                    ) {
                        Text("\u2715", color = Color(0xFFE6EDF3), fontSize = 13.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 5 chips on ONE line
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    chips.forEach { (label, pair) ->
                        val mode = pair.first
                        val col = pair.second
                        val on = selMode == mode
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = if (on) col.copy(alpha = 0.25f) else fieldBg,
                            modifier = Modifier.weight(1f).clickable { selMode = mode }
                        ) {
                            Text(
                                label,
                                color = if (on) col else txtD,
                                fontSize = 9.sp, fontFamily = mono,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 1.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // text field -- Return executes and closes the bar
                Surface(shape = RoundedCornerShape(3.dp), color = fieldBg) {
                    BasicTextField(
                        value = term,
                        onValueChange = { term = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = txtB, fontSize = 12.sp, fontFamily = mono
                        ),
                        cursorBrush = SolidColor(cBlue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                                if (term.isEmpty()) {
                                    Text(
                                        if (selMode == "area") "place name, then Enter"
                                        else "search name, then Enter",
                                        color = txtD, fontSize = 12.sp, fontFamily = mono
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
            }
        }
    }

    // -- Results list (shown after an artifact search; row-tap closes it) --
    @Composable
    fun ResultsBox() {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = panelBg,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)
                .heightIn(min = 36.dp, max = 240.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${results.size} result${if (results.size == 1) "" else "s"}",
                        color = txtD, fontSize = 8.sp, fontFamily = mono,
                        modifier = Modifier.weight(1f)
                    )
                    Text("CLOSE", color = cBlue, fontSize = 9.sp, fontFamily = mono,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { showResults = false }
                            .padding(horizontal = 4.dp, vertical = 2.dp))
                }
                if (results.isEmpty()) {
                    Text("no matches", color = txtD, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.padding(8.dp))
                } else {
                    if (results.size >= 200) {
                        Text("showing first 200 -- refine", color = Color(0xFFD29922),
                            fontSize = 8.sp, fontFamily = mono,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    results.forEach { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    showResults = false
                                    // SEARCHFIT-2026-09-02: ⭐ FRAME IT, THEN
                                    // open the card. Fred, 09-02: "we select the
                                    // returned item from the list and then hit
                                    // fit from the popup menu. Silly." You
                                    // searched for it -- you want to see it.
                                    // ⚠ No padding, per NOPAD-2026-08-04: this
                                    // is the only other Kotlin caller of
                                    // fitBounds and it must not reintroduce the
                                    // compounding pad that bug removed.
                                    val s = r.minLat; val n = r.maxLat
                                    val w = r.minLon; val e = r.maxLon
                                    if (s != null && n != null &&
                                        w != null && e != null) {
                                        webView?.evaluateJavascript(
                                            "fitBounds([$s,$n],[$w,$e])", null)
                                    }
                                    val cap = r.type.replaceFirstChar { it.uppercase() }
                                    onOpenDetail(cap, r.id)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(r.name, color = txtB, fontSize = 10.sp, fontFamily = mono,
                                modifier = Modifier.weight(1f))
                            Text("#${r.seq}", color = cBlue, fontSize = 9.sp, fontFamily = mono,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text(r.id.take(8), color = txtD, fontSize = 8.sp, fontFamily = mono)
                        }
                    }
                }
            }
        }
    }

    // -- The magnifying-glass FAB (toggles the bar) --
    @Composable
    fun Fab() {
        Surface(
            onClick = {
                barOpen = !barOpen
                if (barOpen) showResults = false
            },
            // PLAINCTRL2-2026-08-17: the container, not just the label. This Surface still
            // pinned the control to a 40dp circle after the glyph became a word, so the word
            // was clipped to its first two letters. Transparent + unsized, matching the
            // hamburger.
            shape = RoundedCornerShape(4.dp),
            color = Color.Transparent,
            contentColor = Color(0xFFFF00FF)
        ) {
            Box(contentAlignment = Alignment.Center) {
                // PLAINCTRL-2026-08-17: words, not a glyph -- see the hamburger note in
                // ConvoyScreen. Shared component, so this changes BOTH maps.
                Text(
                    "Search",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = androidx.compose.ui.graphics.Color.White,
                            offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                            blurRadius = 6f
                        )
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
        }
    }

    // SEARCHLEFT-2026-09-02: the bar and results shift LEFT; the word does not.
    // Fred, 09-02: the launcher word and the panel were one component, so moving
    // the panel moved the word out of the column with Map Features and Help --
    // and leaving it in place put the words inside the open panel.
    val panelShift = Modifier.offset(x = (-120).dp)
    Box(modifier = modifier) {
        Column(horizontalAlignment = Alignment.End) {
            if (stackDown) {
                // PLANNING: FAB on top, bar/results grow downward.
                Fab()
                if (barOpen) { Spacer(Modifier.height(6.dp)); Box(panelShift) { SearchBar() } }
                if (showResults) { Spacer(Modifier.height(6.dp)); Box(panelShift) { ResultsBox() } }
            } else {
                // CONVOY (default): bar/results above, FAB at the bottom.
                if (barOpen) { Box(panelShift) { SearchBar() }; Spacer(Modifier.height(6.dp)) }
                if (showResults) { Box(panelShift) { ResultsBox() }; Spacer(Modifier.height(6.dp)) }
                Fab()
            }
        }
    }
}
