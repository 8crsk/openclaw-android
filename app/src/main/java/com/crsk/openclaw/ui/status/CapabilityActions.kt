package com.crsk.openclaw.ui.status

/** Stable identity for each on-device capability bubble on the Status screen. */
enum class CapabilityId { Gateway, Model, UiAutomation, Shell, Plugins }

/** Pure mapping from a capability's state to the CTA shown when it is off. */
object CapabilityActions {
    /** Short call-to-action label for an off capability, or null when connected. */
    fun ctaLabel(id: CapabilityId, connected: Boolean): String? {
        if (connected) return null
        return when (id) {
            CapabilityId.Gateway -> "Start"
            CapabilityId.Model -> "Add key"
            CapabilityId.UiAutomation -> "Enable"
            CapabilityId.Shell -> "Set up"
            CapabilityId.Plugins -> "Connect"
        }
    }
}
