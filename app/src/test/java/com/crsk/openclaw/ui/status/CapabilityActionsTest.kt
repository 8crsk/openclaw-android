package com.crsk.openclaw.ui.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityActionsTest {

    @Test
    fun `connected capability has no CTA`() {
        CapabilityId.values().forEach { id ->
            assertNull(CapabilityActions.ctaLabel(id, connected = true))
        }
    }

    @Test
    fun `each off capability has a distinct CTA label`() {
        assertEquals("Start", CapabilityActions.ctaLabel(CapabilityId.Gateway, connected = false))
        assertEquals("Add key", CapabilityActions.ctaLabel(CapabilityId.Model, connected = false))
        assertEquals("Enable", CapabilityActions.ctaLabel(CapabilityId.UiAutomation, connected = false))
        assertEquals("Set up", CapabilityActions.ctaLabel(CapabilityId.Shell, connected = false))
        assertEquals("Connect", CapabilityActions.ctaLabel(CapabilityId.Plugins, connected = false))
    }
}
