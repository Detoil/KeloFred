package com.kelofred.keloray.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kelofred.keloray.KeloApp
import com.kelofred.keloray.MainActivity
import com.kelofred.keloray.R
import com.kelofred.keloray.core.JsonUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the app process alive (and the CPU awake) while scenes run on the
 * phone in direct mode. The scenes themselves live in the app-wide
 * [SceneRunner]; this service only hosts them, shows the ongoing notification
 * and stops itself once nothing is running.
 */
class SceneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAt = 0L

    private val graph get() = (application as KeloApp).graph

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(emptyList()), type)
        scope.launch {
            graph.runner.running.collectLatest { running ->
                if (running.isEmpty()) {
                    // grace period so a scene that is just being started isn't cut off
                    delay(2_000)
                    if (graph.runner.running.value.isEmpty()) {
                        ServiceCompat.stopForeground(this@SceneService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                } else {
                    holdWakeLock()
                    notify(running.values.toList())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val did = intent.getStringExtra(EXTRA_DID)
                val scene = intent.getStringExtra(EXTRA_SCENE)
                val params = intent.getStringExtra(EXTRA_PARAMS)
                    ?.let { runCatching { JsonUtil.parseObject(it) }.getOrNull() }
                    ?: emptyMap()
                if (did != null && scene != null) {
                    scope.launch {
                        runCatching { graph.runner.start(did, scene, params) }
                            .onFailure { Log.e(TAG, "could not start $scene on $did", it) }
                    }
                }
            }
            ACTION_STOP_ALL -> scope.launch { graph.runner.stopAll() }
            ACTION_KEEPALIVE -> Unit
            // null intent = the system restarted us after killing the process
            ACTION_RESTORE, null -> scope.launch { graph.restoreActiveScenes() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun holdWakeLock() {
        val now = System.currentTimeMillis()
        if (wakeLock?.isHeld == true && now - wakeLockAt < 60 * 60 * 1000L) return
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KeloFred:scenes")
            .also { it.setReferenceCounted(false); wakeLock = it }
        wl.acquire(4 * 60 * 60 * 1000L) // refreshed while scenes keep running
        wakeLockAt = now
    }

    private fun notify(running: List<SceneRunner.Running>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(running))
    }

    private fun buildNotification(running: List<SceneRunner.Running>): Notification {
        val names = graph.deviceNames.value
        val lines = running.map { r -> "${r.scene} · ${names[r.did] ?: r.did}" + (r.lastError?.let { " (retrying)" } ?: "") }
        val title = when (running.size) {
            0 -> getString(R.string.scene_starting)
            1 -> getString(R.string.scene_running_one)
            else -> resources.getQuantityString(R.plurals.scenes_running, running.size, running.size)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SceneService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_SCENES)
            .setSmallIcon(R.drawable.ic_stat_light)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(it) } })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_all), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "SceneService"
        const val CHANNEL_SCENES = "scenes"
        const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.kelofred.keloray.START"
        const val ACTION_STOP_ALL = "com.kelofred.keloray.STOP_ALL"
        const val ACTION_KEEPALIVE = "com.kelofred.keloray.KEEPALIVE"
        const val ACTION_RESTORE = "com.kelofred.keloray.RESTORE"
        private const val EXTRA_DID = "did"
        private const val EXTRA_SCENE = "scene"
        private const val EXTRA_PARAMS = "params"

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SCENES, context.getString(R.string.channel_scenes), NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        private fun launch(context: Context, intent: Intent, what: String) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // Android 12+ refuses background FGS starts outside the allowed cases
                Log.w(TAG, "could not start scene service", e)
                alert(context, context.getString(R.string.scene_blocked, what))
            }
        }

        /** Called by the UI right after it started a scene on the shared runner. */
        fun keepAlive(context: Context) =
            launch(context, Intent(context, SceneService::class.java).setAction(ACTION_KEEPALIVE), "scene")

        fun startScene(context: Context, did: String, scene: String, params: Map<String, Any?>, reason: String) =
            launch(
                context,
                Intent(context, SceneService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_DID, did)
                    .putExtra(EXTRA_SCENE, scene)
                    .putExtra(EXTRA_PARAMS, JsonUtil.stringify(params)),
                reason,
            )

        fun restore(context: Context) =
            launch(context, Intent(context, SceneService::class.java).setAction(ACTION_RESTORE), "scenes")

        fun alert(context: Context, text: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannels(context)
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            NotificationManagerCompat.from(context).notify(
                text.hashCode(),
                NotificationCompat.Builder(context, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_light)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
