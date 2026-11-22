package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grouptrack.comm.CommConfig
import com.grouptrack.shared.comm.GtRadioApply
import com.grouptrack.shared.ride.GtRideStore
import com.grouptrack.shared.ride.RideFiles
import kotlinx.coroutines.launch

/**
 * CHECKINSHEET-2026-11-22 (2.8b, B1) -- the PRE-RIDE CHECK-IN panel, shared.
 *
 * TRANSCRIBED from Android's ConvoyCheckIn.kt (CHECKIN-2026-09-27). Same rides list, same filter chips,
 * same callsign field, same four roles in one row, same held-role red window, same show-on-map tick, same
 * no-radio refusal, same radio-against-this-ride lines, same enabled rule on CHECK IN. Android's strings
 * are carried across unchanged -- they are what the rider reads on both platforms.
 *
 * ⭐⭐ THE ONE DELIBERATE DIFFERENCE, decided 11-21 and NOT invented here.
 * Android's two radio-setup branches call `RadioConfigLauncher.open()`: they enter N - RADIO SETUP's screen
 * with `autoRideId`, because that screen is the only place Android keeps the apply code. The shared write
 * service already exists, and [GtRadioApply]'s own header states it serves both chains -- for B1:
 * "that ride, no picking and no preview -- apply, then report back". So this panel calls the SERVICE:
 * [GtRadioApply.targetFromFile] then [GtRadioApply.write] then [GtRadioApply.verified].
 * ⇒ B1 IS THEREFORE NOT BLOCKED ON N. Do not re-open that; it was settled 11-21 14:46-14:54.
 *
 * ⚠ ROLE IS SELECTED, NOT DEFAULTED FROM THE PROFILE. [stated] Fred 11-21: "role is selected not default.
 * that role in profile controls function in the community web site. role can be unique on every ride." So
 * [RideMe] has no defaultRole and none is added: the pre-selection is Android's organizer rule only --
 * the chosen ride's organizer gets "leader", everyone else "rider".
 *
 * ⚠ WHAT THE HOST SUPPLIES, and why each one is a parameter rather than read here (CODE RULE 1):
 *  - [radioOn], [nodeNum]: Android reads GrpAwarenessLauncher.connected / .myNodeNum, which is host state.
 *    Precedent GtRecordButton.kt:49, which already takes the connected flag this way. nodeNum null = no
 *    radio or not known yet, which is exactly what [GtRadioApply.lastAppliedRide] expects.
 *  - [heldBy]: the roles other carts hold, which only the host's group view knows (HELDROLE-2026-09-29).
 *  - [config]: the comm side. Needed twice -- lastAppliedRide reads its applied history, and the write
 *    goes through it.
 *  - [fs]: the rides folder, for reading a ride's config doc BY NAME.
 *  - [onOpenGrpAwareness]: Android's "OPEN GRP AWARENESS" button navigates; navigation is the host's.
 * Everything else comes from [GtRideStore.env] -- today(), profile(), defaultChannel -- so no parameter
 * duplicates something the env already answers.
 *
 * ⚠ ON THE SIMULATOR [radioOn] is always false (no Bluetooth), so the no-radio refusal is what shows and
 * CHECK IN stays disabled. That is CORRECT behaviour, not a defect, and it is also why the two radio
 * branches cannot be exercised until the iPad has a radio -- they are BUILT here, per the 11-19 standard.
 */
object GtCheckIn {
    val showing = mutableStateOf(false)

    /**
     * CHECKINCONNECT-2026-09-28 (Main.kt:645-648): CHK IN with no radio opens GRP Awareness, and once a
     * radio CONNECTS the check-in comes back by itself. Without this the rider is left in GRP Awareness
     * with no way back, which no simulator can show because the link never reaches CONNECTED there.
     * ⚠ THE HOST OWNS THE OTHER HALF: it watches the comm link and, on CONNECTED with this flag set,
     * closes GRP Awareness and sets [showing]. Android does it in Main.kt; the iPad does it beside its
     * other GtIosCommLink watchers. CODE RULE 1: false = a plain check-in, nothing waiting.
     */
    var thenCheckIn: Boolean = false

    /** The rider's choices kept while the radio is set up. CODE RULE 1: null = none kept (a fresh check-in). */
    var pending: Pending? = null

    data class Pending(
        val rideId: String?,
        val callsign: String,
        val role: String,
        val showOnMap: Boolean,
        val error: String,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GtCheckInSheet(
    radioOn: Boolean,
    nodeNum: Int?,
    heldBy: Map<String, String>,
    config: CommConfig,
    fs: RideFiles,
    onDone: (GtRideStore.CheckIn, Boolean) -> Unit,
    onCancel: () -> Unit,
    onOpenGrpAwareness: () -> Unit,
    /** CHECKINHELP-2026-11-22 / SEAMRULE-2026-11-19: HELP needs LocalContext on Android (GtHelp.load),
     *  so the HOST draws it -- GtPanelChrome exposes openHelp(id) as a service, not a composable.
     *  Precedent: GtAlertSettingsPanel's own `help` parameter, same shape, same position in the row.
     *  The manual page is "record.checkin"; the host passes a control that opens it. */
    help: @Composable () -> Unit = {},
) {
    val env = GtRideStore.env
    val me = remember { env.profile() }
    val rides = remember { GtRideStore.openRecentRides() }
    val today = remember { env.today() }
    val todays = rides.filter { it.date == today }
    val roles = listOf("leader" to "Leader", "rider" to "Rider", "middle" to "Middle", "tail_gunner" to "Tail gunner")

    // Android's defaultRole, minus the profile's defaultRole (see the header): the organizer leads.
    fun defaultRole(r: GtRideStore.RideChoice?): String =
        if (r != null && me != null && r.organizerId == me.userId) "leader" else "rider"

    val kept = remember { GtCheckIn.pending.also { GtCheckIn.pending = null } }
    var chosen by remember {
        mutableStateOf(kept?.let { k -> rides.firstOrNull { it.rideId == k.rideId } } ?: todays.singleOrNull())
    }
    var picked by remember { mutableStateOf(kept != null || todays.size == 1) }
    var callsign by remember { mutableStateOf(kept?.callsign ?: me?.callsign ?: "") }
    var role by remember { mutableStateOf(kept?.role ?: defaultRole(chosen)) }
    var showOnMap by remember { mutableStateOf(kept?.showOnMap ?: true) }

    // CHECKINAPPLY-2026-09-28: is the connected radio on THIS ride's network? Compared by NETWORK.
    val last = remember(nodeNum, radioOn) { GtRadioApply.lastAppliedRide(config, nodeNum) }
    val chosenNet = remember(chosen?.rideId) { chosen?.rideId?.let { GtRideStore.rideForEdit(it)?.configId } }
    val lastNet = remember(last) { last?.first?.let { GtRideStore.rideForEdit(it)?.configId } }
    // DEFAULTNET-2026-10-10
    val onDefault = chosenNet == env.defaultChannel
    val needsSetup = chosen != null && radioOn &&
        (if (onDefault) (lastNet != null && lastNet != chosenNet) else (lastNet == null || lastNet != chosenNet))
    // NORIDEDEFAULT-2026-10-10
    val radioOnDefault = last == null || lastNet == env.defaultChannel ||
        (last.first == null && last.second == "GroupTrack default")
    var keepRadioAsIs by remember { mutableStateOf(false) }
    val noRideNeedsDefault = picked && chosen == null && radioOn && !radioOnDefault && !keepRadioAsIs
    val dim = Gt.dim
    val scope = rememberCoroutineScope()

    /**
     * Android's apply-then-check-in, with the SERVICE in place of the screen. On a verified write the
     * check-in goes through; otherwise the choices are kept and the panel reopens carrying Android's own
     * failure wording -- the same contract RadioConfigLauncher.onAutoResult had.
     */
    fun applyThenCheckIn(ride: GtRideStore.RideChoice?, fileName: String, failure: String) {
        val p = GtCheckIn.Pending(ride?.rideId, callsign, role, ride != null && showOnMap, "")
        GtCheckIn.pending = p
        scope.launch {
            val target = GtRadioApply.targetFromFile(
                fs, fileName, p.callsign,
                ride?.name ?: "GroupTrack default",
                ride?.name ?: "GroupTrack default",
                ride?.rideId,
            )
            val result = if (target == null) null else GtRadioApply.write(config, target)
            if (result != null && GtRadioApply.verified(result)) {
                GtCheckIn.pending = null
                GtRideStore.checkIn(ride, p.callsign, p.role)?.let { onDone(it, p.showOnMap) }
            } else {
                GtCheckIn.pending = p.copy(error = failure)
                GtCheckIn.showing.value = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Gt.panel,
        titleContentColor = Gt.text,
        textContentColor = Gt.text,
        tonalElevation = 0.dp,
        title = {   // CHKINSTD-2026-10-22: the standard top bar -- HELP close, top right
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check in", modifier = Modifier.weight(1f))
                help()
                GtCloseButton(onCancel)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Which ride?", fontWeight = FontWeight.Bold)
                var rideFilter by remember { mutableStateOf("") }
                val organizers = remember(rides) {
                    rides.map { it.organizerName }.filter { it.isNotBlank() }.distinct().sorted()
                }
                if (organizers.isNotEmpty()) Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(selected = rideFilter.isEmpty(), onClick = { rideFilter = "" }, label = { Text("All") })
                    organizers.forEach { o ->
                        FilterChip(selected = rideFilter == o, onClick = { rideFilter = o }, label = { Text(o) })
                    }
                }
                val shown = rides.filter { r -> rideFilter.isEmpty() || r.organizerName == rideFilter }
                val todayAt = shown.indexOfFirst { it.date <= today }
                    .let { if (it < 0) (shown.size - 1).coerceAtLeast(0) else it }
                val rideList = rememberLazyListState()
                LaunchedEffect(rideFilter, shown.size) {
                    if (shown.isNotEmpty()) rideList.scrollToItem(todayAt)
                }
                if (shown.isEmpty()) Text(
                    if (rides.isEmpty()) "No rides on this tablet." else "No rides here.",
                    color = dim, fontSize = 12.sp,
                )
                else LazyColumn(state = rideList, modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
                    items(shown.size) { i ->
                        val r = shown[i]
                        FilterChip(
                            selected = picked && chosen?.rideId == r.rideId,
                            onClick = { chosen = r; picked = true; role = defaultRole(r) },
                            label = {
                                Text(
                                    (if (r.date == today) "TODAY \u00b7 " else "") + r.name + " \u00b7 " + r.date +
                                        (if (r.startTime.isNotBlank()) " " + r.startTime else "") +
                                        (if (r.organizerName.isNotBlank()) " \u00b7 " + r.organizerName else ""),
                                )
                            },
                        )
                    }
                }
                FilterChip(
                    selected = picked && chosen == null,
                    onClick = { chosen = null; picked = true },
                    label = { Text("No scheduled ride") },
                )
                if (picked && chosen == null) Text(
                    "A normal recording: no survey and no sharing \u2014 you name the track at the end.",
                    color = dim, fontSize = 12.sp,
                )
                if (picked && chosen == null && radioOn && !radioOnDefault) {   // NORIDEDEFAULT-2026-10-10
                    // CHECKINWARN-2026-11-22 v2: radioOnDefault is `last == null || ...`, so
                    // !radioOnDefault implies last != null and Kotlin smart-casts it here -- the
                    // elvis fallback could never be taken. This is the site the compiler warned about.
                    if (!keepRadioAsIs) Text(
                        "\u26A0 Your radio is set up for " + last.second +
                            " \u2014 it will be set back to the GroupTrack default when you check in.",
                        color = Gt.warn, fontSize = 13.sp,
                    )
                    Row {
                        Checkbox(checked = keepRadioAsIs, onCheckedChange = { keepRadioAsIs = it })
                        Text("Keep my radio as it is", modifier = Modifier.padding(top = 12.dp), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = callsign, onValueChange = { callsign = it.take(39) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Callsign for this ride") },
                )
                if (me != null && callsign.trim() != me.callsign) Text(
                    "For this ride only \u2014 your profile is not changed.", color = dim, fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("Role", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    roles.forEach { (v, l) ->
                        FilterChip(selected = role == v, onClick = { role = v }, label = { Text(l) })
                    }
                }
                // HELDROLE / HELDROLE2-2026-09-29: a held special role goes back to Rider, shown as a red window.
                var heldWarn by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(role, heldBy) {
                    if (heldBy[role] != null) { heldWarn = role; role = "rider" }
                }
                heldWarn?.let { w ->
                    val label = roles.firstOrNull { it.first == w }?.second ?: w
                    AlertDialog(
                        onDismissRequest = { heldWarn = null },
                        containerColor = Gt.bad.copy(alpha = 0.22f),
                        titleContentColor = Gt.text,
                        textContentColor = Gt.text,
                        title = { Text("\u26D4 " + label + " is taken", fontWeight = FontWeight.Black, fontSize = 22.sp) },
                        text = {
                            Text(
                                label + " is already held by " + (heldBy[w] ?: "another cart") + ".\n\n" +
                                    "A Leader, Middle or Tail gunner can reassign it.\n\nChoose another role to check in.",
                                fontSize = 17.sp,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { heldWarn = null }) {
                                Text("CHOOSE ANOTHER ROLE", color = Gt.text, fontWeight = FontWeight.Black)
                            }
                        },
                    )
                }
                if (chosen != null) Row {   // CHECKINMAP-2026-09-28
                    Checkbox(checked = showOnMap, onCheckedChange = { showOnMap = it })
                    Text(
                        "Show this ride's route and trailhead on the ride map",
                        modifier = Modifier.padding(top = 12.dp), fontSize = 13.sp,
                    )
                }
                // CHECKINRADIO-2026-09-28: NO RADIO, NO CHECK-IN -- every check-in, No scheduled ride included.
                if (!radioOn) {
                    Text(
                        "\u26D4 You can't check in without a radio. Connect it in GRP Awareness.",
                        color = Gt.bad, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = {
                        // CHECKINCONNECT-2026-09-28: Android sets GrpAwarenessLauncher.thenCheckIn here, so the
                        // check-in reopens when a radio connects. The flag is this panel's; the watcher is the host's.
                        GtCheckIn.thenCheckIn = true
                        onCancel()
                        onOpenGrpAwareness()
                    }) { Text("OPEN GRP AWARENESS") }
                }
                if (radioOn) chosen?.let { c ->
                    when {
                        needsSetup && last == null -> Text(
                            "\u26A0 Your radio has no GroupTrack OG configuration \u2014 it will be set up for " +
                                c.name + " when you check in.", color = Gt.warn, fontSize = 13.sp,
                        )
                        // CHECKINWARN-2026-11-22 v2: Kotlin does NOT narrow `last` across `when`
                        // arms, so it is NULLABLE here even though the arm above matched
                        // `needsSetup && last == null`. Android writes last!!.second at this line.
                        needsSetup -> Text(
                            "\u26A0 Your radio is set up for " + last!!.second +
                                " \u2014 it will be set up for " + c.name + " when you check in.",
                            color = Gt.warn, fontSize = 13.sp,
                        )
                        else -> Text(
                            "\u2713 Radio ready for this ride",
                            color = Gt.good, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                kept?.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = Gt.bad, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked && callsign.isNotBlank() && radioOn && heldBy[role] == null,
                onClick = {
                    if (noRideNeedsDefault) {
                        // NORIDEDEFAULT-2026-10-10: the radio back to the GroupTrack default FIRST, then check in.
                        onCancel()
                        applyThenCheckIn(
                            null, GtRadioApply.DEFAULT_ASSET,
                            "The radio was not set back to the GroupTrack default \u2014 CHECK IN to try again, " +
                                "or tick Keep my radio as it is.",
                        )
                    } else if (needsSetup) {
                        // CHECKINAPPLY-2026-09-28: set the radio up for this ride FIRST, then check in.
                        val c = chosen
                        if (c != null) {
                            onCancel()
                            applyThenCheckIn(
                                c, c.rideId + ".json",
                                "The radio was not set up for " + c.name + " \u2014 CHECK IN to try again.",
                            )
                        }
                    } else {
                        GtCheckIn.thenCheckIn = false   // CHECKINCONNECT: nothing is waiting once we are in
                        GtRideStore.checkIn(chosen, callsign, role)?.let { onDone(it, chosen != null && showOnMap) }
                    }
                },
            ) { Text("CHECK IN") }
        },
    )
}
