package com.geeksville.mesh.convoy

/**
 * ONEFEED-2026-10-19 (Fred 10-19 12:20-12:21): THE ONE ANDROID POSITION FEED -- Google's fused location (as onX), HIGH_ACCURACY, every 3 s.
 * No other GPS request in the app. Listeners:
 *   - my cart (ConvoyViewModel.startPhoneGps): every position -> my cart's CoT message on the board, RECORD on or off;
 *   - the recorder (ConvoyGpsService, RECORD on): records it (accuracy within 30 m, at least 5 m from the last recorded point).
 * Process-wide, not the screen's ViewModel: while RECORD is on the recorder's foreground service keeps the app allowed to receive it with
 * the screen off. The feed starts with the first listener and stops with the last. Google Play only (Fred 10-19 11:33).
 * Evidence for fused (10-19): Droid 1's chip reported "no fix" for minutes with 13 satellites in view; fused kept giving 10-25 m positions
 * and onX (fused: log "FusedLocation ... WorkSource onxmaps.offroad") recorded perfectly.
 */
object TabletPosition {
    fun interface Listener { fun onPosition(loc: android.location.Location) }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private var callback: com.google.android.gms.location.LocationCallback? = null   // CODE RULE 1: null = the feed is not running

    /** The last position the feed gave. CODE RULE 1: null = none yet since the app started. */
    @Volatile var last: android.location.Location? = null
        private set

    // GPSLAMP-2026-10-19 (Fred 10-19 13:50): satellites the chip USES IN ITS FIX (Android's GnssStatus) -- 0 = no lock. Not nullable: 0 when unknown.
    @Volatile private var satsUsed = 0
    @Volatile private var satsAtMs = 0L
    private var gnssCb: android.location.GnssStatus.Callback? = null   // CODE RULE 1: null = not listening (the feed is off)
    /** Satellites in the chip's fix now; 0 when no lock, or no satellite report in the last 5 s. */
    fun satsInFix(): Int = if (System.currentTimeMillis() - satsAtMs < 5_000L) satsUsed else 0

    private fun startGnss(ctx: android.content.Context) {
        if (gnssCb != null) return
        val cb = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                var n = 0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) n++
                satsUsed = n; satsAtMs = System.currentTimeMillis()
            }
            override fun onStopped() { satsUsed = 0 }
        }
        try {
            (ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager)
                .registerGnssStatusCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            gnssCb = cb
        } catch (e: Exception) { android.util.Log.w("TABLETPOS", "GPSLAMP-2026-10-19: satellite status refused: " + e.message) }
    }
    private fun stopGnss(ctx: android.content.Context) {
        val cb = gnssCb ?: return
        try { (ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager).unregisterGnssStatusCallback(cb) } catch (_: Exception) {}
        gnssCb = null; satsUsed = 0
    }

    /** This tablet has a GPS chip (Android lists a GPS provider). Without one, my cart and the recorder use the radio. */
    fun hasGps(ctx: android.content.Context): Boolean = runCatching {
        (ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager)
            .allProviders.contains(android.location.LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)

    /** Add a listener (starts the feed if it is the first). Returns false when the feed could not start (no permission). */
    @Synchronized
    fun add(ctx: android.content.Context, l: Listener): Boolean {
        if (!listeners.contains(l)) listeners.add(l)
        if (callback != null) return true
        val cb = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                for (loc in result.locations) {
                    last = loc
                    for (x in listeners) x.onPosition(loc)
                }
            }
        }
        return try {
            val req = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 3000L).build()   // every 3 s
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx.applicationContext)
                .requestLocationUpdates(req, cb, android.os.Looper.getMainLooper())
            callback = cb
            startGnss(ctx.applicationContext)   // GPSLAMP-2026-10-19
            android.util.Log.i("TABLETPOS", "ONEFEED-2026-10-19: feed started (fused, high accuracy, 3 s) -- listeners " + listeners.size)
            true
        } catch (e: Exception) {
            android.util.Log.e("TABLETPOS", "ONEFEED-2026-10-19: feed refused: " + e.message)
            listeners.remove(l)
            false
        }
    }

    /** Remove a listener (stops the feed when it was the last). */
    @Synchronized
    fun remove(ctx: android.content.Context, l: Listener) {
        listeners.remove(l)
        if (listeners.isNotEmpty()) return
        val cb = callback ?: return
        try {
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx.applicationContext).removeLocationUpdates(cb)
        } catch (_: Exception) {}
        callback = null
        stopGnss(ctx.applicationContext)   // GPSLAMP-2026-10-19
        android.util.Log.i("TABLETPOS", "ONEFEED-2026-10-19: feed stopped (no listeners)")
    }
}
