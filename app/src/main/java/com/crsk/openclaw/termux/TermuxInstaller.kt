package com.crsk.openclaw.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs bundled APKs (e.g. Shizuku) via FileProvider + system installer intent.
 */
@Singleton
class TermuxInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls()
        else true

    fun openUnknownSourcesSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun isInstalled(pkg: String): Boolean = packageInfo(pkg) != null

    private fun packageInfo(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Copies an asset APK into cacheDir and fires an install intent. Returns the cached file path.
     * Throws if the asset is missing.
     */
    fun launchInstall(assetName: String): File {
        val cacheDir = File(context.cacheDir, "apks").apply { mkdirs() }
        val out = File(cacheDir, assetName)

        // Re-extract if missing or asset newer than cache (cheap: just always overwrite).
        context.assets.open("apks/$assetName").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            out,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return out
    }

    object Apks {
        const val SHIZUKU = "shizuku.apk"
    }

    object Packages {
        const val SHIZUKU = "moe.shizuku.privileged.api"
    }
}
