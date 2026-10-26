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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.heartpact.HeartpactApplication
import app.heartpact.MainActivity
import app.heartpact.R
import app.heartpact.album.AlbumNotices
import app.heartpact.data.ApiResult
import app.heartpact.data.SharingState
import app.heartpact.data.SharingStatus
import app.heartpact.i18n.tr
import app.heartpact.ui.map.LocationMath
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Shares this phone's position while the person has asked to, with a notification that says so and
 * stops it. It runs only after they tap Share in the app (a while-in-use foreground service: no
 * background location permission), sends a fix about every 30 seconds or 25 metres through the
 * couple hub, and never stores a history on the phone.
 *
 * Starting is an explicit call to the server. After that a heartbeat every few minutes keeps the
 * share alive while the phone is still (the server lets a quiet share lapse), and its answer keeps
 * this phone in step: paused shows "Paused" and turns location off until the pause ends, and off
 * (stopped or signed out on another phone, signed out everywhere) ends the service. The hub saying so (a stop
 * elsewhere, or a fix answered with "off") makes it check at once.
 */
class LocationShareService : Service() {
    private val container get() = (application as HeartpactApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    /** Listens on the hub for this person's share being stopped on another phone. */
    private var watch: Job? = null
    /** Wakes the loop early: the app paused, resumed or asked to start again. */
    private val nudge = Channel<Boolean>(Channel.CONFLATED)
    private var listener: LocationListener? = null
    private var fused: LocationCallback? = null
    private var partner = ""
    private var pausedUntil: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // The server hears it even offline: retried until it lands, and its lease ends a quiet share meanwhile.
                if (running && intent.getBooleanExtra(EXTRA_TELL_SERVER, true)) LocationStop.request(container)
                stop()
                return START_NOT_STICKY
            }
            // Swiped away (Android 14+ allows it even for a foreground service): put it back while sharing.
            ACTION_REPOST -> { if (running) post(); return START_NOT_STICKY }
            ACTION_REFRESH -> { if (running) nudge.trySend(false); return START_NOT_STICKY }
        }
        if (!hasPermission(this) || !canNotify(this)) { if (!running) stopSelf(); return START_NOT_STICKY }
        partner = intent?.getStringExtra(EXTRA_PARTNER) ?: tr("your partner")
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
        if (started.isFailure) { stopSelf(); return START_NOT_STICKY }
        running = true
        container.live.hold(HOLD)
        // Tapped Share again while running (to resume from a pause): start again.
        if (loop?.isActive == true) nudge.trySend(true) else loop = scope.launch { keepSharing() }
        if (watch?.isActive != true) watch = scope.launch { watchElsewhere() }
        return START_NOT_STICKY
    }

    /**
     * Sharing stopped on another of this person's phones (they signed out or tapped Stop there, or signed out
     * everywhere): the hub says their share is off, live or in answer to a fix. Check with the server now rather
     * than at the next heartbeat; its "off" ends this service and its notification.
     */
    private suspend fun watchElsewhere() {
        val me = container.session.load()?.user?.id ?: return
        container.live.events.collect { event ->
            if (event.name != "LocationSharingChanged") return@collect
            val state = runCatching { container.json.decodeFromString(SharingState.serializer(), event.json) }.getOrNull()
            if (LocationMath.saysMyShareIsOff(state, me)) nudge.trySend(false)
        }
    }

    private suspend fun keepSharing() {
        var started = false
        while (true) {
            val result = if (!started) container.location.start() else container.location.heartbeat()
            val wait = when (result) {
                is ApiResult.Ok -> {
                    started = true
                    val me = result.value.me
                    when (me.status) {
                        // Stopped somewhere else: on another phone, the web app, a password reset.
                        SharingStatus.Off -> { stop(); return }
                        SharingStatus.Paused -> { paused(me.pausedUntilUtc); LocationMath.nextBeatMs(me.pausedUntilUtc, Instant.now(), HEARTBEAT_MS) }
                        SharingStatus.Sharing -> { sharing(); HEARTBEAT_MS }
                    }
                }
                // Refused for good (no partner any more, signed out): nothing to share with.
                is ApiResult.Failure -> if (result.status in 400..499 && result.status != 408 && result.status != 429) { stop(); return } else RETRY_MS
                ApiResult.Offline -> RETRY_MS
            }
            withTimeoutOrNull(wait) { nudge.receive() }?.let { again -> if (again) started = false }
        }
    }

    private fun sharing() {
        if (pausedUntil != null) { pausedUntil = null; post() }
        listen()
    }

    /** No location at all while paused: the server would drop the fixes anyway. */
    private fun paused(until: String?) {
        unlisten()
        if (pausedUntil != until) { pausedUntil = until; post() }
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun listen() {
        if (listener != null || fused != null || !hasPermission(this)) return
        val send = { fix: Location -> container.live.updateLocation(fix.latitude, fix.longitude, fix.accuracy.toDouble(), fix.time) }
        // A last known fix can be hours old: only a recent one is sent, and always with its own time.
        val recent = { fix: Location -> LocationMath.isRecentFix(fix.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos()) }
        val manager = getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            val callback = LocationListener { send(it) }
            listener = callback
            manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)?.takeIf(recent)?.let(send)
            manager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, INTERVAL_MS, MIN_METRES, callback, mainLooper)
        } else if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
            // Older phones: Play services' fused provider at balanced accuracy, so GPS isn't held on.
            val client = LocationServices.getFusedLocationProviderClient(this)
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) { result.lastLocation?.let(send) }
            }
            fused = callback
            client.lastLocation.addOnSuccessListener { fix -> fix?.takeIf(recent)?.let(send) }
            @Suppress("DEPRECATION")
            val request = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY)
                .setInterval(INTERVAL_MS)
                .setSmallestDisplacement(MIN_METRES)
            client.requestLocationUpdates(request, callback, mainLooper)
        } else {
            // No Play services: one provider, the network's when it is on, so fixes aren't sent twice.
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { manager.isProviderEnabled(it) } ?: return
            val callback = LocationListener { send(it) }
            listener = callback
            manager.getLastKnownLocation(provider)?.takeIf(recent)?.let(send)
            manager.requestLocationUpdates(provider, INTERVAL_MS, MIN_METRES, callback, mainLooper)
        }
    }

    private fun unlisten() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        fused?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        fused = null
    }

    private fun stop() {
        unlisten()
        loop?.cancel()
        loop = null
        watch?.cancel()
        watch = null
        running = false
        container.live.release(HOLD)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unlisten()
        scope.cancel()
        running = false
        container.live.release(HOLD)
        super.onDestroy()
    }

    private fun post() = runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Location sharing"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("Shown the whole time you share your location.")
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(AlbumNotices.EXTRA_OPEN_TAB, "us").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LocationShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val repost = PendingIntent.getService(this, 2, Intent(this, LocationShareService::class.java).setAction(ACTION_REPOST), PendingIntent.FLAG_IMMUTABLE)
        val until = LocationMath.pausedUntil(pausedUntil)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(if (until != null) tr("Location paused until {time}", "time" to until) else tr("Sharing your location with {partner}", "partner" to partner))
            .setContentText(if (until != null) tr("Neither of you sees the other until then. Stop any time.") else tr("Only while you both share. Stop any time."))
            .setOngoing(true)
            .setContentIntent(open)
            .setDeleteIntent(repost)
            .addAction(0, tr("Stop sharing"), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "location"
        private const val NOTIFICATION_ID = 4200
        private const val HOLD = "location"
        private const val ACTION_STOP = "app.heartpact.location.STOP"
        private const val ACTION_REPOST = "app.heartpact.location.REPOST"
        private const val ACTION_REFRESH = "app.heartpact.location.REFRESH"
        private const val EXTRA_PARTNER = "partner"
        private const val EXTRA_TELL_SERVER = "tellServer"
        private const val INTERVAL_MS = 30_000L
        private const val MIN_METRES = 25f
        /** Well inside the server's 10-minute lease, so one lost beat doesn't end the share. */
        private const val HEARTBEAT_MS = 4 * 60_000L
        private const val RETRY_MS = 30_000L

        /** Whether this process is sharing right now. */
        @Volatile var running = false
            private set

        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Sharing is visible or it doesn't happen: without its notification (denied, or the channel turned off) it never starts. */
        fun canNotify(context: Context): Boolean {
            val notifications = NotificationManagerCompat.from(context)
            if (!notifications.areNotificationsEnabled()) return false
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
            return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        /** Call from the screen, after the person tapped Share and granted location and notifications. */
        fun start(context: Context, partnerName: String) =
            ContextCompat.startForegroundService(context, Intent(context, LocationShareService::class.java).putExtra(EXTRA_PARTNER, partnerName))

        /** Stops sharing on this phone. [tellServer] is false when the caller tells the server itself, or can't (signed out). */
        fun stop(context: Context, tellServer: Boolean = true) =
            context.startService(Intent(context, LocationShareService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_TELL_SERVER, tellServer))

        /** The app paused or resumed: check with the server now instead of at the next heartbeat. */
        fun refresh(context: Context) {
            if (running) context.startService(Intent(context, LocationShareService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
