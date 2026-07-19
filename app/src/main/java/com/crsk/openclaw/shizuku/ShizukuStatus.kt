package com.crsk.openclaw.shizuku

sealed class ShizukuStatus {
    data object NotInstalled : ShizukuStatus()
    data object NotRunning : ShizukuStatus()
    data object PermissionRequired : ShizukuStatus()
    data object Connecting : ShizukuStatus()
    data class Ready(val version: String) : ShizukuStatus()
    data class Failed(val reason: String) : ShizukuStatus()
}
