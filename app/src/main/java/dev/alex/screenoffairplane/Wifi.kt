package dev.alex.screenoffairplane

import android.util.Log

/**
 * Wi-Fi control, independent of airplane mode.
 *
 * `cmd wifi set-wifi-enabled` is permitted for the shell uid (unlike
 * `list-tethered-clients`, which is not), so Wi-Fi can be steered separately
 * from the airplane-mode toggle.
 */
object Wifi {

    enum class State { ON, OFF, UNKNOWN }

    fun get(): State {
        val r = Shell.run("cmd", "wifi", "status")
        val first = r.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            first.startsWith("Wifi is enabled") -> State.ON
            first.startsWith("Wifi is disabled") -> State.OFF
            else -> {
                Log.w(TAG, "unexpected wifi status: '$first' err='${r.stderr}'")
                State.UNKNOWN
            }
        }
    }

    fun set(on: Boolean): Boolean {
        val arg = if (on) "enabled" else "disabled"
        val r = Shell.run("cmd", "wifi", "set-wifi-enabled", arg)
        if (!r.ok) Log.e(TAG, "set-wifi-enabled $arg failed: exit=${r.exitCode} err='${r.stderr}'")
        return r.ok
    }

    private const val TAG = "SoA.Wifi"
}
