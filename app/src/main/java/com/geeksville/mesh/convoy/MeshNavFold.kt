package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * MeshNavFold — is the Meshtastic navigation rail folded away?
 * MESHFOLD-2026-09-04.
 *
 * ⭐⭐ THE FIRST CUT ALONG THE 2.7 LINE. Fred, 09-04: this is *"the start of
 * separating the mesh product from the core GroupTrack, and the shape of what
 * the plugin delivers."* Whatever hides behind the strip is roughly what the
 * plugin will own — and drawing that boundary in the UI first means living with
 * it before committing to it in code.
 *
 * ⛔ THE IMMEDIATE PROBLEM: a rider with no radio opens the app and sees a
 * navigation rail full of Meshtastic destinations that mean nothing to them.
 * The Quick Start's first job was going to be explaining that away.
 *
 * ⚠ STORED BESIDE THE DATABASES, not in SharedPreferences — same reasoning as
 * the schema marker: prefs are wiped by "clear data" while the rider's own
 * files survive, and losing this would unfold the rail under someone who chose
 * to fold it.
 */
object MeshNavFold {

    private const val TAG = "MeshNavFold"
    private const val FILE = "mesh_nav_folded"

    private fun file(): File = File(SpatialDbManager.dbDir(), FILE)

    /**
     * ⚠ DEFAULTS TO FALSE — the rail shows until a rider folds it. A new
     * install must not hide navigation nobody asked to hide; the Meshtastic
     * rider would have no idea it existed.
     */
    fun isFolded(ctx: Context): Boolean = try {
        file().exists()
    } catch (e: Exception) {
        Log.w(TAG, "isFolded: ${e.message}")
        false
    }

    /** ⚠ Presence IS the flag — no contents to parse and nothing to corrupt. */
    fun setFolded(ctx: Context, folded: Boolean) {
        try {
            val f = file()
            if (folded) {
                f.parentFile?.mkdirs()
                f.writeText("folded")
            } else {
                f.delete()
            }
            Log.i(TAG, "meshtastic nav " + if (folded) "folded" else "unfolded")
        } catch (e: Exception) {
            Log.w(TAG, "setFolded: ${e.message}")
        }
    }
}

/**
 * MESHFOLD-2026-09-04: the fold control, as its own composable.
 *
 * ⭐ IT LIVES HERE, NOT IN Main.kt, ON PURPOSE. Main.kt is UPSTREAM Meshtastic
 * code and every line changed there is a rebase conflict later. This keeps that
 * file's diff to four lines — and when 2.7 lifts mesh out as a plugin, the
 * boundary is already drawn around this file rather than tangled through
 * someone else's.
 *
 * ⚠ Two states, one composable. Folded: a strip reading OPEN MESHTASTIC.
 * Unfolded: a small chevron at the top of the rail to fold it again.
 * ⛔ NEITHER STATE CAN HIDE ITS OWN WAY BACK. A control that removes the route
 * to itself is a trap, and folded navigation with no visible handle is exactly
 * that.
 */
@Composable
fun MeshFoldControl(
    folded: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folded) {
        // ⭐ "OPEN MESHTASTIC" — Fred, 09-04, correcting "OPEN MESH": once IP
        // transport arrives, "mesh" describes that too. Meshtastic names the
        // actual thing and stays correct.
        // ⚠ Vertically centred, clear of the map controls that live in the top
        // corners.
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .width(24.dp)
                .height(210.dp)
                .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .background(Color(0xEE141B23))
                .border(1.dp, Color(0xFF2F3945),
                    RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .clickable { onToggle(false) }
        ) {
            Text(
                "\u25B8  OPEN MESHTASTIC",
                color = Color(0xFF8FD0FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                // ⚠ Rotated 90°, so it reads top-to-bottom down the strip.
                modifier = Modifier.rotate(90f).width(200.dp)
            )
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .width(30.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xEE141B23))
                .border(1.dp, Color(0xFF2F3945), RoundedCornerShape(4.dp))
                .clickable { onToggle(true) }
                .padding(1.dp)
        ) {
            Text("\u25C2", color = Color(0xFF8FD0FF), fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}
