package com.itemmanagement.printbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val config = AppConfig(context)
        if (!config.startOnBoot || !config.isPaired) return
        PrintBridgeService.start(context)
    }
}
