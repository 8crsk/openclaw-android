package com.crsk.openclaw.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppForegroundState @Inject constructor() {
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { _foreground.value = true }
            override fun onStop(owner: LifecycleOwner) { _foreground.value = false }
        })
    }
}