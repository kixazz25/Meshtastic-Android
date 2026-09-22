package com.geeksville.mesh.convoy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * RIDECREATE-2026-09-22 — create a ride. FIRST PASS (shell).
 *
 * TWO ENTRY PATHS, one screen:
 *   - from the ride panel: opens empty, the rider picks a route by name
 *   - from the PLANNER's route detail panel: the route arrives already chosen
 * (CODE RULE 1: routeId is optional because BOTH callers are real -- the ride
 *  panel legitimately opens with no route, the planner legitimately supplies one.)
 *
 * NO MAP HERE. The planner is the map; this screen shows a vignette of the chosen
 * route -- drawn from stored geometry, no tiles, instant and offline.
 *
 * NOT YET: network (waits on the transport work), organization and leader setup
 * (backfilled later), the .convoy file, enrollments.
 */
@Composable
fun ConvoyRideCreateScreen(
    initialRouteId: String? = null,
    onRideCreated: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current

    var rideName by remember { mutableStateOf("") }
    var rideDate by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var zipCode by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }

    var routes by remember { mutableStateOf<List<RouteSummary>>(emptyList()) }
    var routeId by remember { mutableStateOf(initialRouteId ?: "") }
    var routeName by remember { mutableStateOf("") }
    var routePts by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(initialRouteId == null) }
    var status by remember { mutableStateOf("") }

    val me = remember { ConvoyProfileStore.load() }

    LaunchedEffect(Unit) {
        routes = ConvoyRideStore.listRoutes()
        zipCode = ConvoySessionManager.getZipCode(context)
    }
    LaunchedEffect(routeId) {
        if (routeId.isBlank()) { routePts = emptyList(); return@LaunchedEffect }
        routeName = routes.firstOrNull { it.routeId == routeId }?.name ?: routeName
        routePts = ConvoyRideStore.routeGeometry(routeId)
            ?.let { ConvoyRideStore.parseWktLine(it) } ?: emptyList()
        if (rideName.isBlank() && routeName.isNotBlank()) rideName = routeName
    }

    val canSave = rideName.isNotBlank() && rideDate.isNotBlank() && routeId.isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(GroupTrackColors.Navy)) {

        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1628))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A3050))
                .clickable { onBack() }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("\u2190 BACK", color = GroupTrackColors.SkyBlue, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("CREATE A RIDE", color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(thickness = 2.dp, color = GroupTrackColors.SkyBlue)

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .imePadding().navigationBarsPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            RideLabel("LEADER")
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (me == null) "\u26a0 No rider profile on this tablet"
                    else listOf(me.firstName, me.lastName).filter { it.isNotBlank() }
                        .joinToString(" ").ifBlank { me.callsign },
                    color = if (me == null) GroupTrackColors.Amber else Color.White,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Text(me?.callsign ?: "settings \u2192 Your Profile",
                    color = GroupTrackColors.SkyBlue, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            RideLabel("ROUTE")
            if (pickerOpen) {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1628)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (routes.isEmpty()) {
                        Text("No routes yet \u2014 build one in the planner, or convert a track.",
                            color = Color(0xFF445566), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    routes.forEach { r ->
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0F2035))
                            .clickable { routeId = r.routeId; routeName = r.name; pickerOpen = false }
                            .padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.name, color = Color.White, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text("\u203a", color = GroupTrackColors.SkyBlue, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                RideField("Route name", routeName, "Route") { routeName = it }
                RouteVignette(routePts)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1A3050))
                        .clickable { pickerOpen = true }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("CHANGE ROUTE", color = GroupTrackColors.SkyBlue, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Text("${routePts.size} points", color = Color(0xFF445566), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }

            RideLabel("RIDE INFO")
            RideField("Ride name *", rideName, "Sunday Desert Run") { rideName = it }
            RideField("Date *", rideDate, "2026-10-03") { rideDate = it }
            RideField("Rollout time", startTime, "8:00 AM") { startTime = it }
            RideField("Zip code", zipCode, "Ride area zip") { zipCode = it }
            RideField("Description", description, "Meeting point, notes...") { description = it }

            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F2035)).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PUBLIC RIDE", color = Color(0xFFAABBCC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Shared with the community at 3.0", color = Color(0xFF445566), fontSize = 10.sp)
                }
                Switch(checked = isPublic, onCheckedChange = { isPublic = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = GroupTrackColors.SkyBlue,
                        checkedTrackColor = Color(0xFF1A3050),
                        uncheckedThumbColor = Color(0xFF445566),
                        uncheckedTrackColor = Color(0xFF0A1628)))
            }

            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A0A00)).padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("NETWORK", color = GroupTrackColors.Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("\u26a0 comes with the transport work", color = GroupTrackColors.Amber, fontSize = 10.sp)
            }

            if (status.isNotBlank()) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D2010)).padding(12.dp)) {
                    Text(status, color = GroupTrackColors.Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (canSave) Color(0xFF1A3050) else Color(0xFF0A1628))
                .clickable(enabled = canSave) {
                    if (routeName.isNotBlank()) ConvoyRideStore.renameRoute(routeId, routeName)
                    val id = ConvoyRideStore.saveRide(
                        rideName = rideName, rideDate = rideDate, startTime = startTime,
                        description = description, zipCode = zipCode,
                        isPublic = isPublic, routeId = routeId
                    )
                    if (id != null) { status = "\u2713 Ride saved"; onRideCreated(id) }
                    else status = "\u2717 Could not save \u2014 is there a rider profile?"
                }
                .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text("SAVE RIDE",
                    color = if (canSave) GroupTrackColors.SkyBlue else Color(0xFF445566),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The vignette: the chosen route drawn from stored geometry. Confirmation, not a map. */
@Composable
private fun RouteVignette(pts: List<Pair<Double, Double>>) {
    Box(modifier = Modifier.fillMaxWidth().height(150.dp)
        .clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A1628))) {
        if (pts.size < 2) {
            Text("no geometry", color = Color(0xFF334455), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Center))
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val xs = pts.map { it.first }; val ys = pts.map { it.second }
                val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
                val spanX = (maxX - minX).takeIf { it > 1e-9 } ?: 1e-9
                val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1e-9
                val scale = minOf(size.width / spanX, size.height / spanY)
                val offX = (size.width - spanX * scale) / 2
                val offY = (size.height - spanY * scale) / 2
                fun at(p: Pair<Double, Double>) = Offset(
                    (offX + (p.first - minX) * scale).toFloat(),
                    (offY + (maxY - p.second) * scale).toFloat()
                )
                for (i in 0 until pts.size - 1) {
                    drawLine(color = Color(0xFF4AB8E8), start = at(pts[i]), end = at(pts[i + 1]),
                        strokeWidth = 3f, cap = StrokeCap.Round)
                }
                drawCircle(color = Color(0xFF00AA44), radius = 6f, center = at(pts.first()))
                drawCircle(color = Color(0xFFF5A623), radius = 6f, center = at(pts.last()))
            }
        }
    }
}

@Composable
private fun RideLabel(label: String) {
    Text(label, color = GroupTrackColors.SkyBlue, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun RideField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = { Text(placeholder, color = Color(0xFF334455), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = GroupTrackColors.SkyBlue,
            unfocusedBorderColor = Color(0xFF1A3050),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = GroupTrackColors.SkyBlue,
            unfocusedLabelColor = Color(0xFF445566),
            cursorColor = GroupTrackColors.SkyBlue))
}
