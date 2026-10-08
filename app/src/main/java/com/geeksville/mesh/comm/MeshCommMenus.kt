package com.geeksville.mesh.comm

import android.content.Context
import com.grouptrack.comm.CommMenuItem
import com.grouptrack.comm.CommMenus
import com.grouptrack.comm.CommResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * COMMMENUS-2026-10-08 (2.7b cycle 1, design v16 §R6.2) -- the MESH implementation of CommMenus: the native Meshtastic
 * menus and screens, UNCHANGED, opened over GroupTrack by request. GroupTrack asks by id; it never names a Meshtastic
 * screen. Services (connect, apply ...) are a different thing -- these are Meshtastic's own menus as they are.
 * First item: "mesh-menu" = the Meshtastic left-hand rail (today's unfold, MESHFOLD-2026-09-04). It ENDS as it does today:
 * the rail's own "Hide Mesh" folds it and returns to the ride map -- how menus end (and what event they report) is
 * on the planning agenda.
 */
class MeshCommMenus @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : CommMenus {

    override fun items(): List<CommMenuItem> = listOf(
        CommMenuItem(id = MESH_MENU, label = "Meshtastic menu", group = "mesh"),
    )

    override fun open(id: String): CommResult = when (id) {
        MESH_MENU -> {
            // The fold state still lives in convoy/MeshNavFold (it also holds GroupTrack's launch-document marker);
            // it moves to the comm side when GroupTrack moves into its own module (step 6).
            com.geeksville.mesh.convoy.MeshNavFold.setFolded(ctx, false)
            CommResult.Ok
        }
        else -> CommResult.NotSupported
    }

    companion object { const val MESH_MENU = "mesh-menu" }
}
