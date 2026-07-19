package com.crsk.openclaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.crsk.openclaw.R
import com.crsk.openclaw.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeartbeatNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heartbeat", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Proactive messages from the agent's heartbeat runs"
            },
        )
    }

    fun notifyMessage(messageId: String, preview: String) {
        ensureChannel()
        val tap = PendingIntent.getActivity(
            context, messageId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_SCROLL_TO, messageId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("4AIs")
            .setContentText(preview.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(400)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(messageId.hashCode(), n)
        }
    }

    companion object {
        const val CHANNEL_ID = "heartbeat_channel"
        const val EXTRA_SCROLL_TO = "scrollToMessageId"
    }
}