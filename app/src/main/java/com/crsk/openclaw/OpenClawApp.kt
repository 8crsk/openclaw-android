package com.crsk.openclaw

import android.app.Application
import android.os.StrictMode
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenClawApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // VM policy only — catches leaked closables / registrations / activities
            // at lifecycle boundaries, not on the hot path. We intentionally do NOT
            // install a ThreadPolicy: detectDiskReads / detectDiskWrites fire on
            // routine DataStore + SharedPreferences + Hilt init calls (many of
            // which are unavoidable on Main), and penaltyLog's per-violation
            // logging added perceptible latency to typing in the chat composer.
            // Reintroduce a narrowly-scoped ThreadPolicy (e.g. detectNetwork only)
            // when chasing a specific Main-thread regression.
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
    }

    // Composio toolkit logos are served from logos.composio.dev as SVG. Default Coil
    // can only decode raster formats — registering SvgDecoder here lets every
    // AsyncImage in the app load SVG URLs transparently.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()
}
