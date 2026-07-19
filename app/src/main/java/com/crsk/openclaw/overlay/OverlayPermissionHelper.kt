package com.crsk.openclaw.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object OverlayPermissionHelper {

    fun hasPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * Build the intent that drops the user on the system "Display over other apps" page
     * scoped to this app. Caller is responsible for `startActivity()` — needs an Activity context
     * for the back stack, but the Intent itself is build-only and pure.
     */
    fun buildGrantIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
}
