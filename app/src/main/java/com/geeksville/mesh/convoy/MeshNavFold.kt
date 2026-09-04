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
     * MESHBTN-2026-09-04: ⭐⭐ COMPOSE STATE, NOT A PLAIN FIELD.
     *
     * Both Main.kt (the rail) and the map screens (the Mesh button that brings
     * it back) read this, so it cannot be local to either.
     *
     * ⛔ AND IT IS mutableStateOf ON PURPOSE. TrailFilterState is a plain
     * singleton, and every composable reading it has to take a `tick`
     * parameter or Compose skips the recomposition — that cost EIGHT BUILDS on
     * 09-02, with taps registering and the screen never moving. Compose
     * observes this one directly and no counter is needed anywhere.
     *
     * ⚠ Backed by the file, so it survives a restart. The state is the truth
     * in memory; the file is the truth across launches.
     */
    private val _folded = androidx.compose.runtime.mutableStateOf(false)
    private var loaded = false

    /** Read this from a composable — Compose recomposes when it changes. */
    val folded: Boolean get() = _folded.value

    /**
     * ⚠ DEFAULTS TO FALSE — the rail shows until a rider folds it. A new
     * install must not hide navigation nobody asked to hide; the Meshtastic
     * rider would have no idea it existed.
     */
    fun isFolded(ctx: Context): Boolean {
        if (!loaded) {
            _folded.value = try {
                file().exists()
            } catch (e: Exception) {
                Log.w(TAG, "isFolded: ${e.message}")
                false
            }
            loaded = true
        }
        return _folded.value
    }

    /** ⚠ Presence IS the flag — no contents to parse and nothing to corrupt. */
    fun setFolded(ctx: Context, folded: Boolean) {
        // ⭐ The in-memory state moves FIRST, so every composable reading it
        // recomposes immediately rather than waiting on a file write.
        _folded.value = folded
        loaded = true
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

// MESHBTN-2026-09-04: ⛔ MeshFoldControl IS GONE. The folded strip on the left
// edge rendered inside the scaffold's content and the map WebView drew over it,
// so a rider who folded the rail had no visible way back. Fred: "how do I bring
// it back... looked good with it missing."
// ⭐ Replaced by a "Mesh" launcher in the RIGHT-HAND COLUMN of each map, with
// Map Features, Map Keys, Search and Help — where every other GroupTrack
// control already lives, where it cannot be covered, and where a rider is
// already looking. It shows only while the rail is folded.
