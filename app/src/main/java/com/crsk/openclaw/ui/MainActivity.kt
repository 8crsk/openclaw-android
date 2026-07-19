package com.crsk.openclaw.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.service.HeartbeatNotifier
import com.crsk.openclaw.ui.navigation.AppNavigation
import com.crsk.openclaw.ui.theme.OpenClawTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferences: AppPreferences

    companion object {
        val scrollRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        intent?.getStringExtra(HeartbeatNotifier.EXTRA_SCROLL_TO)?.let {
            scrollRequests.tryEmit(it)
        }

        setContent {
            // Use null to distinguish "not yet loaded" from "loaded as false".
            // Without this, DataStore's async nature causes isSetupComplete to
            // flash false for one frame before the real value arrives, which
            // briefly renders the setup screen on every cold start.
            // remember the mapped flow so we don't re-subscribe every recomposition.
            val isSetupFlow = remember { preferences.isSetupComplete.map<Boolean, Boolean?> { it } }
            val isSetupComplete by isSetupFlow.collectAsState(initial = null)

            OpenClawTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Hold a blank screen until DataStore resolves. This prevents the
                    // setup screen from flashing on cold start.
                    if (isSetupComplete != null) {
                        AppNavigation(isSetupComplete = isSetupComplete ?: false)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(HeartbeatNotifier.EXTRA_SCROLL_TO)?.let {
            scrollRequests.tryEmit(it)
        }
    }
}
