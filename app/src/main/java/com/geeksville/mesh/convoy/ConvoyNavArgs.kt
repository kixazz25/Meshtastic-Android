package com.geeksville.mesh.convoy

// Lightweight holder for nav arguments that cannot be passed via type-safe routes.
// Set before navigating, read in composable.
object ConvoyNavArgs {
    var completedRidesTab: String = "HISTORY"
    var completedRideId: String = ""
}
