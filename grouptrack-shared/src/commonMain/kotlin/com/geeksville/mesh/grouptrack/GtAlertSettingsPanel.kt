package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grouptrack.shared.settings.GtRideSettings
import kotlin.math.roundToInt

/**
 * IOSSETTINGS-2026-11-19 (Fred 11-19 11:52 "three items to be wired on menu"): A.MENU > menu.settings.
 *
 * ⭐ WHY THIS IS A NEW SHARED PANEL AND NOT A MOVE OF ConvoySettingsScreen. Measured 11-19: that screen
 * is 517 lines, and only about 60 of them are SETTINGS. The rest are five ANDROID subsystems bolted to
 * the same page -- GroupTrackConversion's record, TrailCheck, GtIssue, BuildConfig's release label and
 * GroupMode -- and three of those end in Intent.ACTION_SEND + createChooser, which is the external
 * email/share work Fred moved to ROUND TWO ("that is why [we] operated the external heavy lifting to
 * the second round of the build. email send, file imports and exports, and comm modules"). Moving the
 * screen would have dragged round two's work into round one. So the SETTINGS come across and the TOOLS
 * stay on Android, where they already work.
 *
 * ⭐ IT PERSISTS FOR REAL ON BOTH PLATFORMS. [GtRideSettings] is shared (store "convoy_settings", keys
 * signal_drop_minutes / signal_lost_minutes / off_track_miles / admission_window_hours) and is already
 * certified on the iPad (GtRideSettingsMacTest). Android reaches it through GtSettingsAndroid and the
 * iPad through GtIosSettings on NSUserDefaults, so this panel writes the rider's real settings, not a
 * copy. SEAMRULE-2026-11-19: the HOST acquires the store and passes it in; the panel never opens one.
 *
 * ⚠ THE REDRAW TRAP, avoided rather than rediscovered. FILTERTICK-2026-11-19 cost a build this morning:
 * a panel that WRITES host state and then READS it back does not recompose, because the store is not
 * observable -- exactly what MapKeysPanel.kt:60-66 records in capitals ("KeyColumn took it; KeyRowItem
 * did not; the headings worked and the rows appeared dead"). Here each slider holds its own value in
 * remembered state, seeded from the store once, and writes THROUGH on release. The display is driven by
 * the state, the persistence by the store, and neither waits on the other.
 *
 * ⚠ STD1-2026-10-24: this uses Gt roles. ConvoySettingsScreen uses MaterialTheme's typography and
 * colorScheme, so the two will not look identical until Android converges on this panel. Said here so
 * the difference reads as a known step rather than a defect.
 */
@Composable
fun GtAlertSettingsPanel(
    /** The rider's real store. Android: GtSettingsAndroid; the iPad: GtIosSettings. */
    rs: GtRideSettings,
    /** GRPMODE2-2026-10-13: ConvoyViewModel's flow on Android. */
    showLeadTrack: Boolean,
    onShowLeadTrack: (Boolean) -> Unit,
    /** GroupMode.on. The ALERT THRESHOLDS hide while it is off (ConvoySettingsScreen:338); the seven rows
     *  above them do NOT -- GRPMODE-2026-10-13: "group awareness -- any time". */
    group: Boolean,
    onGroup: (Boolean) -> Unit,
    /** GtIssue.sendsEmailState -- ISSUE-2026-10-24: email to support, or saved on this device. */
    issueEmail: Boolean,
    onIssueEmail: (Boolean) -> Unit,
    onRemoveTrailData: () -> Unit,
    onCheckTrail: () -> Unit,
    onProfile: () -> Unit,
    onMapCredits: () -> Unit,
    onClose: () -> Unit,
    /** SEAMRULE-2026-11-19: HELP needs LocalContext on Android (GtHelp.load), so the host supplies it. */
    help: @Composable () -> Unit = {},
) {
    val drop = remember { mutableFloatStateOf(rs.signalDropMinutes()) }
    val lost = remember { mutableFloatStateOf(rs.signalLostMinutes()) }
    val off = remember { mutableFloatStateOf(rs.offTrackMiles()) }
    val adm = remember { mutableIntStateOf(rs.admissionWindowHours()) }

    Surface(color = Gt.panel, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                help()
                GtCloseButton(onClose)   // CLOSEX-2026-10-22
            }
            Text(
                "SETTINGS", color = Gt.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // ⛔ CORRECTED 11-19 12:38 (Fred: "setting hides [the] group items and it should in dro[i]d
            // mode but it does not sho[w] the seven items that should display"). The first build of this
            // panel carried ONLY the group-gated thresholds, so in My device mode it showed a sentence and
            // nothing else. Android's settings screen has SEVEN rows ABOVE the gate that show in BOTH
            // modes, and they are the ones a solo rider actually needs. Labels and supporting text below
            // are ConvoySettingsScreen's, word for word.
            // CONVREC-2026-11-20 (Fred 20:43): "this function is an old function for android public
            // storage[,] it no longer exists. it can be removed from iPad implementation. iPad is
            // implemented at current level[,] no conversion is necessary. this display conversion results
            // android only."
            // REMOVED, not reworded to "Android only": the row said "not on the iPad yet", and "yet"
            // promises work that will never come -- CONVERSION-2026-09-12 copies GroupTrack out of SHARED
            // STORAGE through a SAF TREE GRANT because MANAGE_EXTERNAL_STORAGE is leaving the manifest.
            // Shared storage, SAF and that permission are all Android; an iPad app is sandboxed from birth
            // and builds its own container (DBPROBE-2026-11-18), so there is no conversion to record.
            // Android keeps its own row in ConvoySettingsScreen -- the function is Android's, not missing.
            // The Storage label stays: "Remove all trail data" sits under it.
            GtSectionLabel("Storage")
            GtSettingRow(
                "Remove all trail data",
                "Every trail and its details, so trails can be imported again clean. " +
                    "Tracks, routes, waypoints and maps stay.",
                onRemoveTrailData,
            )
            Spacer(Modifier.height(8.dp))

            GtSectionLabel("Trail data")
            GtSettingRow(
                "Check a trail",
                "Why a trail does or does not show on the map \u2014 a report you can email",
                onCheckTrail,
            )
            Spacer(Modifier.height(8.dp))

            GtSectionLabel("Your Profile")
            GtSettingRow(
                "Edit rider profile",
                "Callsign, email, vehicle, team colour -- stays on this tablet",
                onProfile,
            )
            Spacer(Modifier.height(8.dp))

            // SRCCREDITS-2026-11-13 / CORRCREDITS-2026-11-13: the row is RETITLED, not greyed. The screen
            // is a credits page -- its source picker is unreachable and its migration never runs -- and the
            // credits must stay reachable (OpenMapTiles' licence). The word doing the damage was "Change":
            // the old row promised a choice the screen does not offer.
            GtSectionLabel("Map source credits")
            GtSettingRow(
                "Map source credits",
                "Where the map comes from, and the credit each source requires \u2014 one vector map, " +
                    "nothing to choose",
                onMapCredits,
            )
            Spacer(Modifier.height(8.dp))

            // ISSUE-2026-10-24 (Fred 11:03): where ISSUE reports go.
            GtSectionLabel("Issue reports")
            GtSwitchRow(
                "Send issue reports by email",
                if (issueEmail) "On \u2014 ISSUE opens your email app"
                else "Off \u2014 ISSUE saves the report on this device",
                issueEmail, onIssueEmail,
            )
            HorizontalDivider(color = Gt.line)

            // GRPMODE-2026-10-13 (Fred): group awareness -- ANY TIME. Off = this device only, no radio calls.
            GtSectionLabel("Group awareness")
            GtSwitchRow(
                "Group awareness with a Meshtastic radio",
                if (group) "On \u2014 GRP Awareness and the group HUD are active"
                else "Off \u2014 this device only. Turn on only when your mesh radio is with you.",
                group, onGroup,
            )
            HorizontalDivider(color = Gt.line)
            Spacer(Modifier.height(8.dp))

            // GRPMODE2-2026-10-13: the group settings -- shown ONLY while group awareness is on, because
            // they act on OTHER carts.
            if (group) {
                GtSectionLabel("Alert Thresholds")
                GtAlertSlider(
                    "Signal Drop", "Minutes without a report before a cart turns yellow",
                    drop.floatValue.roundToInt().toString() + " min",
                    drop.floatValue, 1f..10f, 8,
                ) { v -> drop.floatValue = v; rs.setSignalDropMinutes(v) }
                HorizontalDivider(color = Gt.line)
                GtAlertSlider(
                    "Signal Lost", "Minutes without a report before a cart is considered lost (red)",
                    lost.floatValue.roundToInt().toString() + " min",
                    lost.floatValue, 5f..30f, 4,
                ) { v -> lost.floatValue = v; rs.setSignalLostMinutes(v) }
                HorizontalDivider(color = Gt.line)
                GtAlertSlider(
                    "Off Track", "Miles from the lead's track before the off-track alert fires",
                    gtOneDecimal(off.floatValue) + " mi",
                    off.floatValue, 0.1f..2f, 18,
                ) { v -> off.floatValue = v; rs.setOffTrackMiles(v) }

                Spacer(Modifier.height(8.dp))
                GtSectionLabel("Node Filter")
                GtAlertSlider(
                    "Admission Window", "A radio must have been heard within this time to appear on the map",
                    adm.intValue.toString() + " hr",
                    adm.intValue.toFloat(), 1f..12f, 10,
                ) { v -> adm.intValue = v.roundToInt(); rs.setAdmissionWindowHours(v.roundToInt()) }

                Spacer(Modifier.height(8.dp))
                GtSectionLabel("Lead track")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show lead track", color = Gt.text, fontSize = 15.sp)
                        Text(
                            if (showLeadTrack) "The lead cart's track is drawn on the ride map"
                            else "The lead cart's track is hidden",
                            color = Gt.dim, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = showLeadTrack, onCheckedChange = onShowLeadTrack)
                }
            } else {
                // The thresholds and the lead track act on OTHER carts, so there is nothing to set here in
                // My device mode -- but the seven rows above are all shown, which is the point of 12:38.
                Text(
                    "The alert thresholds and the lead track apply to a GROUP ride. " +
                        "Turn on group awareness above to set them.",
                    color = Gt.dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GtSectionLabel(title: String) {
    Text(
        title, color = Gt.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** ConvoySettingsScreen's AlertSlider, transcribed. The local `latest` is its `localValue`: the store is
 *  written on RELEASE, not on every pixel of the drag. */
@Composable
private fun GtAlertSlider(
    title: String,
    description: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: (Float) -> Unit,
) {
    val latest = remember { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Gt.text, fontSize = 15.sp)
            Text(valueLabel, color = Gt.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Text(description, color = Gt.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Slider(
            value = value,
            onValueChange = { latest.floatValue = it },
            onValueChangeFinished = { onValueChangeFinished(latest.floatValue) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** "%.1f" without java.util.Formatter -- "%.1f".format(x) is a JVM extension and is not in commonMain. */
internal fun gtOneDecimal(x: Float): String {
    val t = (x * 10f).roundToInt()
    return (t / 10).toString() + "." + (if (t < 0) -t % 10 else t % 10).toString()
}

/** ConvoySettingsScreen's ListItem(headlineContent, supportingContent) as a tappable row. Material3's
 *  ListItem exists in CMP, but it carries its own colour scheme; this draws in Gt roles (STD1-2026-10-24). */
@Composable
private fun GtSettingRow(title: String, supporting: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, color = Gt.text, fontSize = 15.sp)
        Text(supporting, color = Gt.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The same row with a Switch on the right -- the shape both of Android's setting switches use. */
@Composable
private fun GtSwitchRow(title: String, supporting: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Gt.text, fontSize = 15.sp)
            Text(supporting, color = Gt.dim, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
