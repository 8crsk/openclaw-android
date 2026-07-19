package com.crsk.openclaw.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.crsk.openclaw.bootstrap.BootstrapManager
import com.crsk.openclaw.data.preferences.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var bootstrapManager: BootstrapManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val shouldStart = runBlocking {
            preferences.autoStartOnBoot.first() && bootstrapManager.isBootstrapped()
        }

        if (shouldStart) {
            GatewayService.start(context)
        }
    }
}
