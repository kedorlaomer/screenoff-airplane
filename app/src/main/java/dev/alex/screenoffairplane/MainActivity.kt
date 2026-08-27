package dev.alex.screenoffairplane

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 32)
        }
        root.addView(status)

        val guard = CheckBox(this).apply {
            text = "Skip when hotspot clients are connected"
            isChecked = Prefs.hotspotGuard(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setHotspotGuard(this@MainActivity, checked)
            }
        }
        root.addView(guard)

        root.addView(TextView(this).apply {
            text = "Wi-Fi"
            setPadding(0, 32, 0, 8)
        })

        val wifiGroup = RadioGroup(this)
        Prefs.WifiPolicy.entries.forEach { policy ->
            wifiGroup.addView(RadioButton(this).apply {
                id = policy.ordinal + 100
                text = policy.label
                isChecked = Prefs.wifiPolicy(this@MainActivity) == policy
            })
        }
        wifiGroup.setOnCheckedChangeListener { _, id ->
            Prefs.WifiPolicy.entries.getOrNull(id - 100)?.let {
                Prefs.setWifiPolicy(this@MainActivity, it)
            }
        }
        root.addView(wifiGroup)

        root.addView(Button(this).apply {
            text = "Grant Shizuku permission"
            setOnClickListener { ShizukuGate.requestPermission() }
        })

        root.addView(Button(this).apply {
            text = "Start watcher"
            setOnClickListener {
                WatcherService.start(this@MainActivity)
                refresh()
            }
        })

        root.addView(Button(this).apply {
            text = "Stop watcher"
            setOnClickListener {
                WatcherService.stop(this@MainActivity)
                // The service reverts airplane mode on its way down; give it a
                // moment before reading state back.
                status.postDelayed({ refresh() }, 2500)
            }
        })

        root.addView(Button(this).apply {
            text = "Test: toggle airplane mode now"
            setOnClickListener { testToggle() }
        })

        setContentView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).also { root.gravity = Gravity.TOP })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun refresh() {
        thread(isDaemon = true) {
            val shizuku = ShizukuGate.status()
            val air = if (ShizukuGate.ready()) Airplane.get().name else "?"
            val wifi = if (ShizukuGate.ready()) Wifi.get().name else "?"
            val clients = if (ShizukuGate.ready()) {
                val ifaces = Hotspot.tetheredInterfaces()
                if (ifaces.isEmpty()) "hotspot off"
                else "hotspot on (${ifaces.joinToString()}), " +
                    if (Hotspot.hasConnectedClients()) "clients connected" else "no clients"
            } else "?"

            val watcher = if (WatcherService.isRunning(this)) "Watcher: running" else "Watcher: stopped"
            val alarms = if (getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
                "Exact alarms: yes" else "Exact alarms: NO (timing will be approximate)"

            runOnUiThread {
                status.text = buildString {
                    appendLine(watcher)
                    appendLine(shizuku)
                    appendLine(alarms)
                    appendLine()
                    appendLine("Airplane mode: $air")
                    appendLine("Wi-Fi: $wifi")
                    appendLine("Tethering: $clients")
                    appendLine()
                    appendLine("Delay: ${Prefs.delayMinutes(this@MainActivity)} min after screen off")
                }
            }
        }
    }

    private fun testToggle() {
        thread(isDaemon = true) {
            val before = Airplane.get()
            val target = before != Airplane.State.ON
            Airplane.set(target)
            Thread.sleep(2000)
            refresh()
        }
    }
}
