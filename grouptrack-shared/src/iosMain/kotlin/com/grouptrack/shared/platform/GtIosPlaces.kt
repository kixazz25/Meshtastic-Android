package com.grouptrack.shared.platform

import com.geeksville.mesh.convoy.GeofabrikState
import com.geeksville.mesh.grouptrack.GtPlace
import com.grouptrack.shared.log.gtLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLCircularRegion
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLPlacemark
import platform.Foundation.NSError
import kotlin.coroutines.resume

/**
 * IOSPLACES-2026-11-19: the PLACES half of M.Search on the iPad.
 *
 * Transcribed from GroupTrackScreen.kt:2984-2996. Android uses android.location.Geocoder with a
 * RECTANGULAR hint and a limit of 6, then applies the filter Fred asked for on 10-22 at 13:06
 * ("Smithtown exists in a lot of places"): a state outline is a rectangle that overlaps its
 * neighbours, so a returned place must ALSO NAME that state.
 *
 * ⚠ THE HINT DIVERGES, THE FILTER DOES NOT. CLGeocoder takes a CLRegion, and the usable one is
 * CIRCULAR, so the hint here is the state bbox's centre with a radius out to its longer span.
 * The filter below is Android's, unchanged -- and the filter is what actually rejects a place in
 * the wrong state, so the narrowing behaviour is the same even though the hint's shape is not.
 *
 * ⚠ ONLINE ONLY, like Android's. The caller already gates on b.online, and the panel says
 * "Offline -- places are left out" when it is false.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun iosFindPlaces(term: String, st: GeofabrikState?): List<GtPlace> =
    suspendCancellableCoroutine { cont ->
        val log = gtLogger("IosPlaces")

        val handler: (List<*>?, NSError?) -> Unit = { marks, err ->
            val out = try {
                (marks ?: emptyList<Any?>())
                    .mapNotNull { it as? CLPlacemark }
                    // GroupTrackScreen.kt:2992 verbatim in intent: blank adminArea passes (the
                    // geocoder did not say), otherwise the state's name must start with it.
                    .filter { p ->
                        val admin = p.administrativeArea
                        st == null || admin.isNullOrBlank() || st.name.startsWith(admin, ignoreCase = true)
                    }
                    .mapNotNull { p ->
                        val loc = p.location ?: return@mapNotNull null
                        val lat = loc.coordinate.useContents { latitude }
                        val lon = loc.coordinate.useContents { longitude }
                        GtPlace(
                            listOfNotNull(p.name, p.locality, p.administrativeArea)
                                .distinct().joinToString(", "),
                            lat, lon,
                        )
                    }
                    .take(6)   // Android's getFromLocationName(..., 6)
            } catch (e: Throwable) {
                log.e(e) { "IOSPLACES-2026-11-19: placemark mapping failed" }
                emptyList()
            }
            if (err != null) log.i { "IOSPLACES-2026-11-19: geocoder said " + err.localizedDescription }
            log.i { "IOSPLACES-2026-11-19: '" + term + "' -> " + out.size + " place(s)" }
            if (cont.isActive) cont.resume(out)
        }

        val g = CLGeocoder()
        if (st == null) {
            g.geocodeAddressString(term, handler)
        } else {
            val centre = cValue<CLLocationCoordinate2D> {
                latitude = (st.bboxSouth + st.bboxNorth) / 2.0
                longitude = (st.bboxWest + st.bboxEast) / 2.0
            }
            // ~111.32 km per degree of latitude; half the longer span reaches the bbox edge.
            val span = maxOf(st.bboxNorth - st.bboxSouth, st.bboxEast - st.bboxWest)
            // IOSPLACES2-2026-11-19: NAMED arguments are required here. CLCircularRegion exposes
            // two constructors with identical parameter TYPES -- center: and
            // circularRegionWithCenter: -- so a positional call is an overload ambiguity.
            val region = CLCircularRegion(
                center = centre,
                radius = span * 111_320.0 / 2.0,
                identifier = "gtsearch",
            )
            g.geocodeAddressString(term, region, handler)
        }

        cont.invokeOnCancellation { g.cancelGeocode() }
    }
