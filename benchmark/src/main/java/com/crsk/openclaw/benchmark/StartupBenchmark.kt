package com.crsk.openclaw.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold and warm startup benchmarks for 4AIs.
 *
 * Run with:
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest
 *
 * Results land in benchmark/build/outputs/connected_android_test_additional_output/.
 * The JSON file is what to commit / diff over time; the trace files are for Perfetto.
 *
 * The app launches into the auth or setup wizard on a fresh install; that's fine — startup
 * is measured from process fork to first frame, before any of our screens render content
 * that depends on the gateway being up.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCold() = startup(StartupMode.COLD)

    @Test
    fun startupWarm() = startup(StartupMode.WARM)

    private fun startup(mode: StartupMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 5,
        startupMode = mode,
        setupBlock = { pressHome() },
        measureBlock = {
            startActivityAndWait()
        },
    )

    private companion object {
        const val TARGET_PACKAGE = "com.crsk.openclaw"
    }
}
