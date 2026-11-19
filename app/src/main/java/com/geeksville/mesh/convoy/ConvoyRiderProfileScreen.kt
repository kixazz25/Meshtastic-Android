package com.geeksville.mesh.convoy

import androidx.compose.runtime.Composable
import com.geeksville.mesh.grouptrack.GtHelpButton
import com.grouptrack.shared.net.GtRiderProfile

/**
 * PANELMOVE4-2026-11-19: ANDROID'S wiring for the shared [GtRiderProfilePanel]. The panel moved to commonMain because
 * A.MENU's menu.account had no destination on the iPad; this keeps the name and signature every Android call site uses,
 * so nothing on Android changes. SEAMRULE-2026-11-19: the three things that cannot cross the seam are supplied here --
 * the store (ConvoyProfileStore, which maps RiderProfile <-> GtRiderProfile over the shared GtProfileStore) and HELP
 * (GtHelpButton needs LocalContext for GtHelp.load).
 */
@Composable
fun ConvoyRiderProfileScreen(
    onSaved: () -> Unit,
    onCancel: (() -> Unit)? = null
) = GtRiderProfilePanel(
    onSaved = onSaved,
    onCancel = onCancel,
    load = {
        ConvoyProfileStore.load()?.let {
            GtRiderProfile(it.userId, it.firstName, it.lastName, it.callsign, it.email, it.cell,
                it.defaultRole, it.vehicleType, it.team)
        }
    },
    save = { g ->
        ConvoyProfileStore.save(
            RiderProfile(g.userId, g.firstName, g.lastName, g.callsign, g.email, g.cell,
                g.defaultRole, g.vehicleType, g.team)
        )
    },
    help = { GtHelpButton("menu.account") }
)
