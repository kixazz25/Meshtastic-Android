package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * IOSHUD-2026-11-19 (Fred 11-19 11:39 "HUD displays are not wired yet ... last scaffolding piece for map
 * display", 12:23 "added HUD display to this level", 12:26 "we are after look and feel"): the HUD's GPS
 * line, shared. Transcribed from GroupTrackScreen.kt:3672-3726 (gpsChColor, GpsLocationRow,
 * GpsOutlinedText), value for value.
 *
 * ⭐ IT DRAWS HONESTLY WITH NO RADIO, which is why it is worth having before the radio exists. The meter's
 * two numbers come from the mesh -- the report interval and the channel utilisation -- so on the iPad they
 * are 0 and 0f, and the row then reads "-- s" and "0%" in green. THAT IS EXACTLY WHAT ANDROID SHOWS WITH
 * NO RADIO: intervalSecs <= 0 gives "-- s" (GPSMETER-LABEL-2026-09-29) and gpsChColor(0f) is green. So
 * nothing here is a placeholder standing in for Android's behaviour; it IS Android's behaviour for a
 * device with no radio connected.
 *
 * HUDRIDE-2026-11-01 (Fred 18:56-19:31): the HUD is a ride feature only -- checked in (Group) or recording
 * (My device). [showGps] false draws ONLY the light/dark button, at the left: that button is not part of
 * the HUD and stays on this line either way, which is what the original's comment insists on.
 *
 * GRPMODE-2026-10-13: the satellite is at 35 % alpha while group awareness is off.
 * HUDBACK-2026-11-03: the meter carries its own ~70 % dark backing; its numbers are always white with a
 * dark outline (GPSMETER-CLEAR-2026-09-29: an outline, never a tile or a blurred halo).
 */
@Composable
fun GtGpsHudRow(
    showGps: Boolean,
    intervalSecs: Int,
    channelUtil: Float,
    /** GRPMODE-2026-10-13: GroupMode.on -- the satellite greys out when group awareness is off. */
    group: Boolean,
    onOpen: () -> Unit,
    onToggleText: () -> Unit,
) {
    val c = gtGpsChColor(channelUtil)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 4.dp),
    ) {
        if (showGps) {
            // the satellite with "GPS" across it -- tap to open "Sent every XX seconds"
            Box(
                modifier = Modifier.size(54.dp)
                    .alpha(if (group) 1f else 0.35f)
                    .background(Color(0xE6111820), RoundedCornerShape(10.dp))
                    .clickable { onOpen() },
                contentAlignment = Alignment.Center,
            ) {
                Text("🛰", fontSize = 28.sp)
                Text(
                    "GPS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.background(Color(0xDD2E75B6), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp),
                )
            }
            // the meter: the interval, and the channel use in its colour
            Column(
                modifier = Modifier.background(Color(0xB3000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                GtGpsOutlinedText(
                    if (intervalSecs > 0) intervalSecs.toString() + " s" else "-- s",
                    Color.White, 18, FontWeight.Black,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier.size(12.dp)
                            .background(Color(0xFF111111), RoundedCornerShape(3.dp))
                            .padding(1.5.dp)
                            .background(c, RoundedCornerShape(2.dp)),
                    )
                    GtGpsOutlinedText(gtPercent0(channelUtil), c, 18, FontWeight.Black)
                }
            }
        }
        // COSMETICS2-2026-10-06: a half-white / half-black circle = "switch the text light/dark". DRAWN,
        // not a font glyph, so it looks the same on every device -- which is the whole reason it survives
        // a platform move unchanged.
        Box(
            modifier = Modifier.size(40.dp)
                .background(Color(0xE6111820), RoundedCornerShape(8.dp))
                .clickable { onToggleText() },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.size(24.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Color(0xFF888888), CircleShape),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.White))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF111111)))
            }
        }
    }
}

/** GroupTrackScreen.kt:3672, unchanged: red above 40 %, amber above 25 %, green below. */
fun gtGpsChColor(util: Float): Color = when {
    util > 40f -> Color(0xFFFF4444)      // red: a congestion problem
    util > 25f -> Color(0xFFFFAA00)      // yellow: the range to adjust in
    else -> Color(0xFF00CC44)            // green: leave it
}

/** GPSMETER-CLEAR-2026-09-29: a dark stroke behind the coloured glyphs -- no tile, no halo. */
@Composable
fun GtGpsOutlinedText(text: String, color: Color, sizeSp: Int, weight: FontWeight) {
    Box {
        Text(
            text, color = Color(0xFF111111), fontSize = sizeSp.sp, fontWeight = weight,
            style = TextStyle(drawStyle = Stroke(width = 6f, join = StrokeJoin.Round)),
        )
        Text(text, color = color, fontSize = sizeSp.sp, fontWeight = weight)
    }
}

/** Android writes "%.0f%%".format(util). String.format is a JVM extension and is NOT in commonMain, so
 *  the rounding is written out. roundToInt is half-up away from zero, as Java's %.0f is for positives --
 *  and channel utilisation is never negative. */
internal fun gtPercent0(util: Float): String = util.roundToInt().toString() + "%"
