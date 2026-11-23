package com.geeksville.mesh.convoy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RIDESCONV-2026-11-23: ANDROID's half of the two ride-screen bridges.
 *
 * The screens themselves are now in commonMain and are the SAME code on both platforms. Everything
 * in here is work the PLATFORM owns -- the system back gesture, the toast surface, the OS geocoder,
 * the share sheet, the preview bitmap, the asset-backed mesh defaults -- and each one is the call
 * the Android screen made before the move, unchanged.
 */
@Composable
fun androidRidesBridge(): RidesBridge {
    val context = LocalContext.current
    return RidesBridge(
        files = GtRidesAndroid.Files(context),
        createBridge = androidRideCreateBridge(),
        backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
        help = { id -> com.geeksville.mesh.grouptrack.GtHelpButton(id) },
        send = { id -> ConvoyRideSend.send(context, id) },
    )
}

@Composable
fun androidRideCreateBridge(): RideCreateBridge {
    val context = LocalContext.current
    return RideCreateBridge(
        files = GtRidesAndroid.Files(context),
        help = { id -> com.geeksville.mesh.grouptrack.GtHelpButton(id) },
        // ConvoySessionManager.getZipCode(context) -- the session store, unchanged.
        storedZipCode = { ConvoySessionManager.getZipCode(context) },
        toast = { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        },
        // ZIPFROMTH-2026-09-24: Android's own geocoder AND its IO dispatch, exactly as the screen
        // had it before the move -- the dispatch moved in here with the call it belongs to.
        zipFor = { lat, lon ->
            withContext(Dispatchers.IO) {
                try {
                    if (!android.location.Geocoder.isPresent()) null
                    else @Suppress("DEPRECATION")
                        android.location.Geocoder(context, java.util.Locale.US)
                            .getFromLocation(lat, lon, 1)?.firstOrNull()?.postalCode
                } catch (e: Exception) { null }
            }
        },
        // RIDEPREVIEW-2026-09-24: the bitmap is Android's, so Android draws it; [fallback] is the
        // shared vignette the screen passes in.
        routePreview = { routeId, fallback ->
            val bmp = remember(routeId) { RidePreview.load(context, routeId) }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = "Route map",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                )
            } else fallback()
        },
        adoptPreview = { routeId, rideId -> RidePreview.adopt(context, routeId, rideId) },
        // ONEDEFAULT-2026-09-26: the asset-backed defaults, read now, as before the move.
        refreshMeshDefaults = { com.grouptrack.core.NetworkGenerator.MeshDefaults.loadFrom(context) },
    )
}
