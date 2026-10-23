package app.heartpact.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.heartpact.HeartpactApplication
import app.heartpact.MainActivity
import app.heartpact.R
import app.heartpact.album.AlbumNotices
import app.heartpact.i18n.tr

/**
 * Shares this phone's position while the person has asked to, with a notification that says so and
 * stops it. It runs only after they tap Share in the app (a while-in-use foreground service: no
 * background location permission), sends a fix about every 30 seconds or 25 metres through the
 * couple hub, and never stores a history on the phone.
 */
class LocationShareService : Service() {
    private val container get() = (application as HeartpactApplication).container
    private var listener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.live.stopLocationSharing()
            stop()
            return START_NOT_STICKY
        }
        if (!hasPermission(this)) { stopSelf(); return START_NOT_STICKY }
        val partner = intent?.getStringExtra(EXTRA_PARTNER) ?: tr("your partner")
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(partner), type) }
        if (started.isFailure) { stopSelf(); return START_NOT_STICKY }
        running = true
        container.live.hold(HOLD)
        listen()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun listen() {
        if (listener != null) return
        val manager = getSystemService(LocationManager::class.java)
        val callback = LocationListener { fix: Location -> container.live.updateLocation(fix.latitude, fix.longitude, fix.accuracy.toDouble()) }
        listener = callback
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            else {
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
        }
        providers.forEach { provider ->
            manager.getLastKnownLocation(provider)?.let { callback.onLocationChanged(it) }
            manager.requestLocationUpdates(provider, INTERVAL_MS, MIN_METRES, callback, mainLooper)
        }
    }

    private fun stop() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        running = false
        container.live.release(HOLD)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        running = false
        container.live.release(HOLD)
        super.onDestroy()
    }

    private fun notification(partner: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Location sharing"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("Shown the whole time you share your location.")
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(AlbumNotices.EXTRA_OPEN_TAB, "us").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LocationShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(tr("Sharing your location with {partner}", "partner" to partner))
            .setContentText(tr("Only while you both share. Stop any time."))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, tr("Stop sharing"), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "location"
        private const val NOTIFICATION_ID = 4200
        private const val HOLD = "location"
        private const val ACTION_STOP = "app.heartpact.location.STOP"
        private const val EXTRA_PARTNER = "partner"
        private const val INTERVAL_MS = 30_000L
        private const val MIN_METRES = 25f

        /** Whether this process is sharing right now. */
        @Volatile var running = false
            private set

        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Call from the screen, after the person tapped Share and granted location. */
        fun start(context: Context, partnerName: String) =
            ContextCompat.startForegroundService(context, Intent(context, LocationShareService::class.java).putExtra(EXTRA_PARTNER, partnerName))

        fun stop(context: Context) = context.startService(Intent(context, LocationShareService::class.java).setAction(ACTION_STOP))
    }
}
