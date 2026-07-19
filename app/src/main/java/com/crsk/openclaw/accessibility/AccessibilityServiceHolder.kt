package com.crsk.openclaw.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class A11yStatus { CONNECTED, DISCONNECTED }

@Singleton
class AccessibilityServiceHolder @Inject constructor() {

    private val _service = MutableStateFlow<PhoneAccessibilityService?>(null)
    private val _status = MutableStateFlow(A11yStatus.DISCONNECTED)
    val status: StateFlow<A11yStatus> = _status.asStateFlow()

    fun attach(service: PhoneAccessibilityService) {
        _service.value = service
        _status.value = A11yStatus.CONNECTED
    }

    fun detach() {
        _service.value = null
        _status.value = A11yStatus.DISCONNECTED
    }

    fun getServiceOrNull(): PhoneAccessibilityService? = _service.value

    fun isConnected(): Boolean = _service.value != null
}
