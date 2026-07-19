package com.crsk.openclaw.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExecAllowlist is the last line of defense between prompt-injected agent output
 * and shell execution at the Shizuku (ADB) UID. These tests pin the deny rules so
 * a future refactor can't silently widen the attack surface.
 */
class ExecAllowlistTest {

    private fun allowed(vararg argv: String) =
        ExecAllowlist.validate(argv.toList()) is ExecAllowlist.Result.Allowed

    private fun denied(vararg argv: String) =
        ExecAllowlist.validate(argv.toList()) is ExecAllowlist.Result.Denied

    // ── Allowed binaries pass ──
    @Test fun allowlisted_binaries_pass() {
        assertTrue(allowed("input", "tap", "500", "500"))
        assertTrue(allowed("dumpsys", "window"))
        assertTrue(allowed("pm", "list", "packages"))
        assertTrue(allowed("screencap", "-p"))
        assertTrue(allowed("am", "start", "-n", "com.android.settings/.Settings"))
    }

    // ── Shell / privilege-escalation binaries rejected ──
    @Test fun shell_and_su_rejected() {
        assertTrue(denied("sh", "-c", "rm -rf /sdcard"))
        assertTrue(denied("su"))
        assertTrue(denied("mount"))
        assertTrue(denied("/system/bin/sh"))
        assertTrue(denied("bash"))
    }

    @Test fun fully_qualified_path_resolves_to_basename() {
        // allowed binary via absolute path still allowed…
        assertTrue(allowed("/system/bin/am", "start", "-n", "com.foo/.Bar"))
        // …but a non-allowlisted one is rejected on basename
        assertTrue(denied("/system/bin/dd", "if=/dev/zero"))
    }

    @Test fun empty_argv_rejected() {
        assertTrue(denied())
    }

    // ── argv-smuggling via whitespace / newlines ──
    @Test fun spaces_and_newlines_in_args_rejected() {
        assertTrue(denied("am", "start\n-n\nevil"))
        assertTrue(denied("input", "text", "hello; rm -rf /"))
        assertTrue(denied("settings", "put global adb_enabled 1"))
    }

    // ── am start to sensitive Settings activities ──
    @Test fun am_start_to_dev_settings_blocked() {
        assertTrue(denied("am", "start", "-n", "com.android.settings/.DevelopmentSettings"))
        assertTrue(denied("am", "start", "-n", "com.android.settings/.DevelopmentSettingsDashboardActivity"))
        assertTrue(denied("am", "start", "-n", "com.android.settings/.AdbWirelessDialog"))
    }

    // ── settings put to protected keys (ADB / dev mode / unknown sources) ──
    @Test fun settings_put_to_protected_keys_blocked() {
        assertTrue(denied("settings", "put", "global", "adb_enabled", "1"))
        assertTrue(denied("settings", "put", "global", "adb_wifi_enabled", "1"))
        assertTrue(denied("settings", "put", "global", "development_settings_enabled", "1"))
        assertTrue(denied("settings", "put", "secure", "install_non_market_apps", "1"))
    }

    @Test fun settings_put_to_benign_keys_allowed() {
        assertTrue(allowed("settings", "put", "system", "screen_brightness", "120"))
        assertTrue(allowed("settings", "get", "global", "adb_enabled"))
    }

    @Test fun denied_result_carries_reason() {
        val r = ExecAllowlist.validate(listOf("su"))
        assertTrue(r is ExecAllowlist.Result.Denied)
        assertEquals(true, (r as ExecAllowlist.Result.Denied).reason.isNotBlank())
    }
}
