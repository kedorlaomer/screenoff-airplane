package dev.alex.screenoffairplane

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * Foreground service that watches screen state.
 *
 * ACTION_SCREEN_ON/OFF cannot be declared in the manifest — they are only
 * delivered to receivers registered at runtime — so a live process is required.
 */
class WatcherService : Service() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // An exception escaping onReceive takes the whole process down, and
            // START_STICKY then restarts it looking healthy while having done
            // nothing. Contain failures here instead.
            runCatching {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                }
            }.onFailure { Log.e(TAG, "handling ${intent.action} failed", it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Watching screen state"))

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        Log.i(TAG, "watcher started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TIMER_FIRED -> onTimerFired()
            ACTION_STOP -> {
                stopWatching()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * Clean shutdown: cancel the pending alarm, put the radios back if we were
     * the ones that turned them off, then stop.
     *
     * Reverting matters — a force-stop skips this path entirely and would leave
     * airplane mode on with nothing left running to undo it.
     */
    private fun stopWatching() {
        Log.i(TAG, "stopping watcher")
        getSystemService(AlarmManager::class.java).cancel(alarmIntent())

        if (Prefs.weEnabledIt(this)) {
            updateNotification("Stopping — restoring radios")
            // Revert synchronously so the process is not killed mid-shell-call.
            val ok = Airplane.set(false)
            Prefs.setWeEnabledIt(this, false)
            Log.i(TAG, "reverted airplane mode on stop: $ok")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- screen transitions ---------------------------------------------------

    private fun onScreenOff() {
        Log.i(TAG, "screen off; arming ${Prefs.delayMinutes(this)}min timer")
        val triggerAt = SystemClock.elapsedRealtime() + Prefs.delayMillis(this)
        val am = getSystemService(AlarmManager::class.java)

        // Both variants pass ...AllowWhileIdle because Doze is precisely the
        // state being waited through; a plain setExact or a Handler would be
        // deferred to an unpredictable time.
        //
        // canScheduleExactAlarms must be checked first: without the permission
        // setExactAndAllowWhileIdle throws SecurityException, which killed the
        // receiver and left no alarm scheduled at all. Inexact still fires,
        // just with OS-chosen slack, so degrade rather than fail.
        val exact = am.canScheduleExactAlarms()
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarmIntent()
                )
            } else {
                Log.w(TAG, "no exact-alarm permission; falling back to inexact")
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarmIntent()
                )
            }
        } catch (e: SecurityException) {
            // Belt and braces: OEM builds have been known to deny this even
            // when canScheduleExactAlarms() returns true.
            Log.e(TAG, "exact alarm refused; falling back to inexact", e)
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarmIntent()
            )
        }

        val suffix = if (exact) "" else " (approx)"
        updateNotification(
            "Screen off — airplane mode in ${Prefs.delayMinutes(this)} min$suffix"
        )
    }

    private fun onScreenOn() {
        getSystemService(AlarmManager::class.java).cancel(alarmIntent())

        // Only revert what we turned on. If the user enabled airplane mode
        // themselves we must not silently switch their radios back on.
        if (Prefs.weEnabledIt(this)) {
            Log.i(TAG, "screen on; reverting airplane mode")
            updateNotification("Screen on — restoring radios")
            worker {
                val ok = Airplane.set(false)
                Prefs.setWeEnabledIt(this, false)
                updateNotification(
                    if (ok) "Watching screen state" else "Failed to restore radios — check Shizuku"
                )
            }
        } else {
            updateNotification("Watching screen state")
        }
    }

    private fun onTimerFired() {
        worker {
            if (!ShizukuGate.ready()) {
                Log.w(TAG, "timer fired but Shizuku is not available; doing nothing")
                updateNotification("Shizuku not running — cannot toggle")
                return@worker
            }

            if (Prefs.hotspotGuard(this) && Hotspot.hasConnectedClients()) {
                Log.i(TAG, "hotspot has clients; skipping airplane mode")
                updateNotification("Skipped — hotspot clients connected")
                return@worker
            }

            if (Airplane.get() == Airplane.State.ON) {
                Log.i(TAG, "airplane mode already on; not touching it")
                return@worker
            }

            val ok = Airplane.set(true)
            Prefs.setWeEnabledIt(this, ok)
            Log.i(TAG, "airplane mode enabled: $ok")
            updateNotification(if (ok) "Airplane mode on (screen off)" else "Failed to enable — check Shizuku")
        }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(this, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Shell calls block; never run them on the main thread. */
    private fun worker(block: () -> Unit) = thread(isDaemon = true) {
        runCatching(block).onFailure { Log.e(TAG, "worker failed", it) }
    }

    // --- notification ---------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen watcher",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Ongoing notification required for the background service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, WatcherService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen-off airplane mode")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stopIntent
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_TIMER_FIRED = "dev.alex.screenoffairplane.TIMER_FIRED"
        const val ACTION_STOP = "dev.alex.screenoffairplane.STOP"
        private const val CHANNEL_ID = "watcher"
        private const val NOTIF_ID = 1
        private const val TAG = "SoA.Watcher"

        fun start(context: Context) {
            val intent = Intent(context, WatcherService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Prefs.setEnabled(context, true)
        }

        fun stop(context: Context) {
            // Recorded before the stop request so a restart-on-boot does not
            // resurrect a watcher the user deliberately switched off.
            Prefs.setEnabled(context, false)
            val intent = Intent(context, WatcherService::class.java).apply {
                action = ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun isRunning(context: Context): Boolean = Prefs.enabled(context)
    }
}
