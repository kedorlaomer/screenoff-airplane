package dev.alex.screenoffairplane

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired when the screen has been off for the configured delay. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, WatcherService::class.java).apply {
            action = WatcherService.ACTION_TIMER_FIRED
        }
        context.startForegroundService(svc)
    }
}
