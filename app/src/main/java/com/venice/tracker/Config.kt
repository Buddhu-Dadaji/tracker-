package com.venice.tracker

object Config {

    // ---------- FILL IN AT STAGE 2 ----------
    const val FIREBASE_URL = "https://REPLACE-ME-default-rtdb.firebaseio.com"
    const val API_SECRET   = "REPLACE-WITH-YOUR-OWN-LONG-WORD"

    // ---------- FILL IN AT STAGE 3 ----------
    const val TG_TOKEN   = "REPLACE_WITH_BOT_TOKEN"
    const val TG_CHAT_ID = "REPLACE_WITH_CHAT_ID"

    // ---------- tunables ----------
    const val DEVICE_ID      = "phone1"
    const val LOC_INTERVAL_MS = 10 * 60 * 1000L
    const val SEGMENT_MS      = 30 * 60 * 1000L
    const val CLOUD_TICK_MS   = 60 * 1000L
    const val MAX_UPLOAD      = 45L * 1024L * 1024L
    const val QUEUE_CAP_MB    = 400L

    const val ALIAS = "com.venice.tracker.MainAlias"
}
