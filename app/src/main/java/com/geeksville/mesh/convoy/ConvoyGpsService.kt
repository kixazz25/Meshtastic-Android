package com.geeksville.mesh.convoy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ConvoyGpsService
 *
 * Foreground service for GPS track recording.
 * Survives screen off, app backgrounded, and Android Doze mode.
 *
 * States: IDLE → RECORDING → PAUSED → RECORDING → STOPPED
 *
 * Notification actions mirror the on-screen REC button:
 *   RECORDING: PAUSE | END
 *   PAUSED:    RESUME | END
 *
 * File naming:
 *   Recording starts with temp file: convoy_track_temp_{timestamp}.kml
 *   On END: file is complete and ready. Caller renames via finalizeTrack().
 *
 * Communication: bound service pattern. ConvoyViewModel binds and calls
 * startTrack / pauseTrack / resumeTrack / stopTrack directly.
 */
class ConvoyGpsService : Service() {

    // ── Binder ───────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): ConvoyGpsService = this@ConvoyGpsService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── State ────────────────────────────────────────────────────────────────
    enum class State { IDLE, RECORDING, PAUSED }
    var state: State = State.IDLE
        private set

    var currentTempFile: File? = null
        private set

    // Callback to ViewModel for location updates (trail rendering on map)
    var onLocationUpdate: ((lat: Double, lon: Double, alt: Double) -> Unit)? = null

    var totalDistanceMiles: Double = 0.0
        private set

    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    }

    // ── Internal ─────────────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var kmlWriter: BufferedWriter? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    companion object {
        private const val TAG = "ConvoyGpsService"
        const val CHANNEL_ID = "convoy_gps_recording"
        const val NOTIFICATION_ID = 1001

        // Intent actions for notification buttons
        const val ACTION_PAUSE  = "com.geeksville.mesh.convoy.GPS_PAUSE"
        const val ACTION_RESUME = "com.geeksville.mesh.convoy.GPS_RESUME"
        const val ACTION_END    = "com.geeksville.mesh.convoy.GPS_END"

        fun start(context: Context) {
            val intent = Intent(context, ConvoyGpsService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConvoyGpsService::class.java))
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "ConvoyGpsService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE  -> pauseTrack()
            ACTION_RESUME -> resumeTrack()
            ACTION_END    -> stopTrack()
        }
        // Start as foreground immediately to avoid ANR
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopLocationUpdates()
        closeKml()
        Log.i(TAG, "ConvoyGpsService destroyed")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start a new GPS track recording.
     * Creates temp KML file. GPS updates begin immediately.
     */
    fun startTrack() {
        if (state != State.IDLE) return
        val tempFile = createTempKmlFile()
        currentTempFile = tempFile
        openKmlWriter(tempFile)
        acquireWakeLock()
        startLocationUpdates()
        state = State.RECORDING
        updateNotification()
        Log.i(TAG, "Track recording started: ${tempFile.name}")
    }

    /**
     * Pause recording. GPS updates stop. File stays open.
     */
    fun pauseTrack() {
        if (state != State.RECORDING) return
        stopLocationUpdates()
        releaseWakeLock()
        state = State.PAUSED
        updateNotification()
        Log.i(TAG, "Track recording paused")
    }

    /**
     * Resume recording after pause.
     */
    fun resumeTrack() {
        if (state != State.PAUSED) return
        acquireWakeLock()
        startLocationUpdates()
        state = State.RECORDING
        updateNotification()
        Log.i(TAG, "Track recording resumed")
    }

    /**
     * Stop recording and finalize KML file.
     * Returns the completed temp file — caller is responsible for renaming.
     * After this call, state returns to IDLE.
     */
    fun stopTrack(): File? {
        if (state == State.IDLE) return null
        stopLocationUpdates()
        releaseWakeLock()
        closeKml()
        val file = currentTempFile
        currentTempFile = null
        lastLat = null
        lastLon = null
        totalDistanceMiles = 0.0
        state = State.IDLE
        updateNotification()
        Log.i(TAG, "Track recording stopped. File: ${file?.name}")
        return file
    }

    /**
     * Rename the completed temp file to the user-provided name.
     * Called after stopTrack() once user has entered a name.
     */
    fun finalizeTrack(tempFile: File, trackName: String): File? {
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val safeName = trackName.trim().replace(Regex("[^a-zA-Z0-9_\\- ]"), "").replace(" ", "_")
            val finalName = "${safeName}_${sdf.format(Date())}.kml"
            val finalFile = File(tempFile.parent, finalName)
            val success = tempFile.renameTo(finalFile)
            if (success) {
                Log.i(TAG, "Track finalized: $finalName")
                finalFile
            } else {
                Log.e(TAG, "Failed to rename temp file to $finalName")
                tempFile // Return temp file if rename fails
            }
        } catch (e: Exception) {
            Log.e(TAG, "finalizeTrack error: ${e.message}")
            tempFile
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            val listener = LocationListener { loc ->
                onGpsUpdate(loc)
            }
            locationListener = listener
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                3000L,  // 3 second minimum interval
                5f,     // 5 metre minimum distance
                listener
            )
            Log.i(TAG, "GPS updates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission not granted: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
        } catch (e: Exception) {
            Log.e(TAG, "stopLocationUpdates error: ${e.message}")
        }
        locationListener = null
        locationManager = null
    }

    private fun onGpsUpdate(loc: Location) {
        writeKmlPoint(loc.latitude, loc.longitude, loc.altitude)
        onLocationUpdate?.invoke(loc.latitude, loc.longitude, loc.altitude)
    }

    // ── KML ───────────────────────────────────────────────────────────────────

    private fun createTempKmlFile(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ), "my_tracks"
        )
        if (!dir.exists()) dir.mkdirs()
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return File(dir, "convoy_track_temp_${sdf.format(Date())}.kml")
    }

    private fun openKmlWriter(file: File) {
        try {
            val writer = BufferedWriter(FileWriter(file))
            kmlWriter = writer
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            writer.write("<Document>\n")
            writer.write("<name>Convoy Track</name>\n")
            writer.write("<Placemark><name>Track</name><LineString><coordinates>\n")
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open KML writer: ${e.message}")
        }
    }

    private fun writeKmlPoint(lat: Double, lon: Double, alt: Double) {
        try {
            kmlWriter?.write("$lon,$lat,$alt\n")
            kmlWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "KML write error: ${e.message}")
        }
        // Update trail segments for map display
        val prevLat = lastLat
        val prevLon = lastLon
        lastLat = lat
        lastLon = lon
        if (prevLat != null && prevLon != null) {
            totalDistanceMiles += haversineMiles(prevLat, prevLon, lat, lon)
        }
        // Trail update is handled via onLocationUpdate callback to ViewModel
    }

    private fun closeKml() {
        try {
            kmlWriter?.write("</coordinates></LineString></Placemark>\n")
            kmlWriter?.write("</Document>\n</kml>\n")
            kmlWriter?.flush()
            kmlWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "KML close error: ${e.message}")
        }
        kmlWriter = null
    }

    // ── Wake Lock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ConvoyTracker::GpsRecording"
            ).also {
                it.acquire(4 * 60 * 60 * 1000L) // 4 hour max safety timeout
            }
            Log.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock: ${e.message}")
        }
        wakeLock = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GPS Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Convoy GPS track recording"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pauseIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ConvoyGpsService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val resumeIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ConvoyGpsService::class.java).apply { action = ACTION_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ConvoyGpsService::class.java).apply { action = ACTION_END },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        when (state) {
            State.RECORDING -> {
                builder.setContentTitle("GroupTrack — GPS Recording Active")
                builder.setContentText("Track recording in progress")
                builder.addAction(Notification.Action.Builder(null, "PAUSE", pauseIntent).build())
                builder.addAction(Notification.Action.Builder(null, "END", endIntent).build())
            }
            State.PAUSED -> {
                builder.setContentTitle("GroupTrack — GPS Recording Paused")
                builder.setContentText("Tap RESUME to continue recording")
                builder.addAction(Notification.Action.Builder(null, "RESUME", resumeIntent).build())
                builder.addAction(Notification.Action.Builder(null, "END", endIntent).build())
            }
            State.IDLE -> {
                builder.setContentTitle("GroupTrack")
                builder.setContentText("GPS recording stopped")
            }
        }
        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
        if (state == State.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
