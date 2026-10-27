package com.doctorknepper.oarlock.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.doctorknepper.oarlock.Ark

/** Restarts unfinished downloads after the phone reboots or the app is updated (unless the user paused them). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        if (!ok) return
        if (Ark.engine.hasWork && !Ark.settings.pausedByUser) {
            runCatching { DownloadService.start(context) }
        }
    }
}
