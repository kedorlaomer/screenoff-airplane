package dev.alex.screenoffairplane

import android.content.Context

object Prefs {
    private const val FILE = "prefs"
    private const val KEY_DELAY_MIN = "delay_minutes"
    private const val KEY_WE_ENABLED = "we_enabled_it"
    private const val KEY_HOTSPOT_GUARD = "hotspot_guard"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WIFI_POLICY = "wifi_policy"
    private const val DEFAULT_DELAY_MIN = 5

    /** What to do about Wi-Fi, which airplane mode always takes down. */
    enum class WifiPolicy {
        /** Let airplane mode drop Wi-Fi and let Android bring it back. */
        RESTORE,

        /** Revert airplane mode on screen-on but leave Wi-Fi off. */
        KEEP_OFF,

        /** Turn Wi-Fi back on while the screen is off (radios off except Wi-Fi). */
        ON_DURING;

        val label: String
            get() = when (this) {
                RESTORE -> "Restore Wi-Fi with airplane mode (default)"
                KEEP_OFF -> "Leave Wi-Fi off after screen-on"
                ON_DURING -> "Keep Wi-Fi on while screen is off"
            }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun delayMinutes(c: Context): Int = prefs(c).getInt(KEY_DELAY_MIN, DEFAULT_DELAY_MIN)
    fun setDelayMinutes(c: Context, v: Int) = prefs(c).edit().putInt(KEY_DELAY_MIN, v).apply()
    fun delayMillis(c: Context): Long = delayMinutes(c) * 60_000L

    /**
     * Whether *this app* turned airplane mode on. Used so that screen-on only
     * reverts our own change and leaves a user-initiated toggle alone.
     */
    fun weEnabledIt(c: Context): Boolean = prefs(c).getBoolean(KEY_WE_ENABLED, false)
    fun setWeEnabledIt(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_WE_ENABLED, v).apply()

    fun hotspotGuard(c: Context): Boolean = prefs(c).getBoolean(KEY_HOTSPOT_GUARD, true)
    fun setHotspotGuard(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_HOTSPOT_GUARD, v).apply()

    /**
     * Whether the user wants the watcher active. Checked on boot so a watcher
     * that was deliberately stopped is not restarted behind the user's back.
     */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_ENABLED, v).apply()

    fun wifiPolicy(c: Context): WifiPolicy =
        runCatching {
            WifiPolicy.valueOf(
                prefs(c).getString(KEY_WIFI_POLICY, null) ?: WifiPolicy.RESTORE.name
            )
        }.getOrDefault(WifiPolicy.RESTORE)

    fun setWifiPolicy(c: Context, v: WifiPolicy) =
        prefs(c).edit().putString(KEY_WIFI_POLICY, v.name).apply()
}
