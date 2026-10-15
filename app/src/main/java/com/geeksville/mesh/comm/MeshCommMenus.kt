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
        CommMenuItem(id = NODES, label = "Nodes", group = "mesh"),   // NODESMENU-2026-10-15 (Fred): replaces the Meshtastic side menu
        CommMenuItem(id = RADIO_SETTINGS, label = "Radio settings", group = "mesh"),   // COMMSETTINGS-2026-10-12
    )

    override val openMenu: kotlinx.coroutines.flow.StateFlow<String?> = MeshScreens.open   // COMMSETTINGS-2026-10-12

    override fun close(id: String): CommResult =   // COMMSETTINGS-2026-10-12
        if (id == RADIO_SETTINGS && MeshScreens.open.value == RADIO_SETTINGS) { MeshScreens.request(MeshScreens.CLOSE); CommResult.Ok }
        else if (id == NODES && MeshScreens.open.value == NODES) { MeshScreens.request(MeshScreens.CLOSE_NODES); CommResult.Ok }   // NODESMENU-2026-10-15
        else CommResult.NotSupported

    override fun open(id: String): CommResult = when (id) {
        MESH_MENU -> {
            // The fold state still lives in convoy/MeshNavFold (it also holds GroupTrack's launch-document marker);
            // it moves to the comm side when GroupTrack moves into its own module (step 6).
            com.geeksville.mesh.convoy.MeshNavFold.setFolded(ctx, false)
            CommResult.Ok
        }
        RADIO_SETTINGS -> {   // COMMSETTINGS-2026-10-12: Meshtastic's radio settings page, as it is -- opened by the shell
            MeshScreens.open.value = RADIO_SETTINGS
            MeshScreens.request(MeshScreens.OPEN_SETTINGS)
            CommResult.Ok
        }
        NODES -> {   // NODESMENU-2026-10-15: Meshtastic's node list, as it is -- opened by the shell, the way RADIO_SETTINGS is
            MeshScreens.open.value = NODES
            MeshScreens.request(MeshScreens.OPEN_NODES)
            CommResult.Ok
        }
        else -> CommResult.NotSupported
    }

    companion object { const val MESH_MENU = "mesh-menu"; const val RADIO_SETTINGS = "radio-settings"; const val NODES = "nodes" }
}

/**
 * COMMSETTINGS-2026-10-12 -- the comm side's screen requests, carried out by the MESHTASTIC SHELL (its nav host in Main.kt today; it moves
 * out with the shell at step 6). One app-wide holder: the comm side posts a request, the shell navigates, and the shell reports
 * when the requested page has been left (any way -- BACK TO MAP or Android's back button), which clears [open].
 */
object MeshScreens {
    const val OPEN_SETTINGS = "open-settings"
    const val CLOSE = "close"
    const val OPEN_NODES = "open-nodes"     // NODESMENU-2026-10-15
    const val CLOSE_NODES = "close-nodes"   // NODESMENU-2026-10-15
    /** NODESMENU-2026-10-15: the screen Nodes was opened from -- BACK TO MAP pops back to it. CODE RULE 1: null = Nodes not open. */
    @Volatile var baseId: Int? = null
    private val _requests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requests: kotlinx.coroutines.flow.SharedFlow<String> = _requests
    /** The comm-side screen open now. CODE RULE 1: null = none. */
    val open = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @Volatile private var reached = false
    fun request(r: String) { _requests.tryEmit(r) }
    /** The shell reports whether Meshtastic's Settings page is showing. Leaving it after a requested open clears [open]. */
    fun onSettingsPage(showing: Boolean) {
        if (open.value != MeshCommMenus.RADIO_SETTINGS) { reached = false; return }
        if (showing) reached = true
        else if (reached) { reached = false; open.value = null; android.util.Log.i("CommMenus", "COMMSETTINGS-2026-10-12 radio settings left") }
    }
    /** NODESMENU-2026-10-15: the twin of onSettingsPage for Nodes -- leaving it (BACK TO MAP or Android's back) clears [open]. */
    @Volatile private var reachedNodes = false
    fun onNodesPage(showing: Boolean) {
        if (open.value != MeshCommMenus.NODES) { reachedNodes = false; return }
        if (showing) reachedNodes = true
        else if (reachedNodes) { reachedNodes = false; open.value = null; baseId = null; android.util.Log.i("CommMenus", "NODESMENU-2026-10-15 nodes left") }
    }
}
