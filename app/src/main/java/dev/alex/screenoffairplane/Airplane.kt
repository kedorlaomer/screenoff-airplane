package dev.alex.screenoffairplane

import android.util.Log

/**
 * Airplane mode via `cmd connectivity airplane-mode`, which runs inside
 * ConnectivityService and therefore both writes the setting *and* sends the
 * ACTION_AIRPLANE_MODE_CHANGED broadcast that actually powers the radios down.
 *
 * Writing Settings.Global.AIRPLANE_MODE_ON directly does not work: the
 * broadcast is protected, so telephony/wifi never react and only the UI toggle
 * changes. The `cmd` path needs NETWORK_SETTINGS, which the shell uid holds.
 */
object Airplane {

    enum class State { ON, OFF, UNKNOWN }

    fun get(): State {
        val r = Shell.run("cmd", "connectivity", "airplane-mode")
        return when (r.stdout.trim()) {
            "enabled" -> State.ON
            "disabled" -> State.OFF
            else -> {
                Log.w(TAG, "unexpected airplane-mode output: '${r.stdout}' err='${r.stderr}'")
                State.UNKNOWN
            }
        }
    }

    fun set(on: Boolean): Boolean {
        val arg = if (on) "enable" else "disable"
        val r = Shell.run("cmd", "connectivity", "airplane-mode", arg)
        if (!r.ok) {
            Log.e(TAG, "airplane-mode $arg failed: exit=${r.exitCode} err='${r.stderr}'")
        }
        return r.ok
    }

    private const val TAG = "SoA.Airplane"
}
