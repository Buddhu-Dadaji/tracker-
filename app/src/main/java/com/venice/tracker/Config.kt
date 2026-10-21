package com.venice.tracker

object Config {

    // ---------- FILL IN AT STAGE 2 ----------
    const val FIREBASE_URL = "https://tracker-9c0ec-default-rtdb.asia-southeast1.firebasedatabase.app"
    const val API_SECRET   = "white-elephant-9348"

    // ---------- FILL IN AT STAGE 3 ----------
    const val TG_TOKEN   = "8824318917:AAFV2KcQrI50PlpffdvUeUh3BL81EX_tKhw"
    const val TG_CHAT_ID = "1069207755"

    // ---------- tunables ----------
    const val DEVICE_ID      = "phone1"
    const val LOC_INTERVAL_MS = 10 * 60 * 1000L
    const val SEGMENT_MS      = 30 * 60 * 1000L
    const val CLOUD_TICK_MS   = 60 * 1000L
    const val MAX_UPLOAD      = 45L * 1024L * 1024L
    const val QUEUE_CAP_MB    = 400L

    const val ALIAS = "com.venice.tracker.MainAlias"
}
