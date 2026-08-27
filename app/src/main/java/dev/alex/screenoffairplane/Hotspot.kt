package dev.alex.screenoffairplane

import android.util.Log

/**
 * Hotspot guard.
 *
 * Turning on airplane mode tears down the softAP, which disconnects every
 * tethered client. When someone is actually using the hotspot we skip the
 * toggle rather than cutting them off.
 *
 * `cmd wifi list-tethered-clients` would be the obvious source but it is denied
 * even to the shell uid (SecurityException: "Uid 2000 does not have access"),
 * so client presence is read from the tethered interface's neighbour table.
 */
object Hotspot {

    /** Interfaces currently tethered, e.g. [wlan1]. Empty when hotspot is off. */
    fun tetheredInterfaces(): List<String> {
        val r = Shell.run("dumpsys", "tethering")
        if (!r.ok) {
            Log.w(TAG, "dumpsys tethering failed: ${r.stderr}")
            return emptyList()
        }
        // The "Tether state:" block lists one line per downstream:
        //     wlan1 - TetheredState - lastError = 0
        return r.stdout.lineSequence()
            .dropWhile { !it.contains("Tether state:") }
            .drop(1)
            .takeWhile { it.startsWith("    ") || it.startsWith("\t") }
            .mapNotNull { line ->
                val t = line.trim()
                if (!t.contains("TetheredState")) return@mapNotNull null
                t.substringBefore(" -").trim().takeIf { it.isNotEmpty() }
            }
            .toList()
    }

    /**
     * True when at least one client is connected to a tethered interface.
     *
     * Only REACHABLE/STALE/DELAY/PROBE neighbours count. FAILED and INCOMPLETE
     * entries are stale ARP noise from clients that have already gone away.
     */
    fun hasConnectedClients(): Boolean {
        val ifaces = tetheredInterfaces()
        if (ifaces.isEmpty()) return false

        for (iface in ifaces) {
            val r = Shell.run("ip", "neigh", "show", "dev", iface)
            if (!r.ok) {
                // Fail safe: if we cannot tell, assume someone is connected so
                // we never silently cut off a client.
                Log.w(TAG, "ip neigh on $iface failed: ${r.stderr}; assuming clients present")
                return true
            }
            val live = r.stdout.lineSequence()
                .filter { it.isNotBlank() }
                .filter { line -> LIVE_STATES.any { line.contains(it) } }
                // One physical client shows up as several entries (v4 + v6), so
                // dedupe on MAC to make the log meaningful.
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    val i = parts.indexOf("lladdr")
                    if (i >= 0 && i + 1 < parts.size) parts[i + 1] else null
                }
                .toSet()

            if (live.isNotEmpty()) {
                Log.i(TAG, "hotspot $iface has ${live.size} client(s): $live")
                return true
            }
        }
        return false
    }

    private val LIVE_STATES = listOf("REACHABLE", "STALE", "DELAY", "PROBE")
    private const val TAG = "SoA.Hotspot"
}
