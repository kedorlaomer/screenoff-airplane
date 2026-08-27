package dev.alex.screenoffairplane

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the watcher after a reboot.
 *
 * specialUse is not among the FGS types Android 15+ forbids starting from
 * BOOT_COMPLETED, so this is allowed. Note the watcher will still be unable to
 * toggle anything until Shizuku is restarted over adb.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                // Only resume if the watcher was running when the device went
                // down. Stopping it is meant to stay stopped across reboots.
                if (Prefs.enabled(context)) WatcherService.start(context)
        }
    }
}
