package dev.alex.screenoffairplane

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku availability and permission.
 *
 * On a non-rooted device the Shizuku server is started via adb and dies on
 * every reboot, so this can go from ready to not-ready at any time. Callers
 * must re-check rather than caching the result.
 */
object ShizukuGate {

    const val REQUEST_CODE = 4242

    fun ready(): Boolean = binderAlive() && hasPermission()

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        Log.w(TAG, "pingBinder failed", t)
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() = try {
        Shizuku.requestPermission(REQUEST_CODE)
    } catch (t: Throwable) {
        Log.e(TAG, "requestPermission failed", t)
    }

    fun status(): String = when {
        !binderAlive() -> "Shizuku is not running — start it via adb"
        !hasPermission() -> "Shizuku is running but permission was not granted"
        else -> "Shizuku ready (uid ${runCatching { Shizuku.getUid() }.getOrDefault(-1)})"
    }

    private const val TAG = "SoA.Shizuku"
}
