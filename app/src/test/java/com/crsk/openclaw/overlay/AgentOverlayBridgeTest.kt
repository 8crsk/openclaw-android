package com.crsk.openclaw.overlay

import com.crsk.openclaw.data.network.ws.ApprovalKind
import com.crsk.openclaw.data.network.ws.ApprovalRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentOverlayBridgeTest {

    @Test
    fun `initial state is hidden and empty`() = runTest {
        val bridge = AgentOverlayBridge()
        val state = bridge.overlayState.value
        assertFalse(state.visible)
        assertNull(state.currentAction)
        assertTrue(state.recentActions.isEmpty())
        assertNull(state.pendingApproval)
        assertFalse(state.isExpanded)
    }

    @Test
    fun `onAgentStarted makes overlay visible and resets fields`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onToolStarted("stale-tool")
        bridge.setExpanded(true)
        bridge.onAgentStarted()
        val state = bridge.overlayState.value
        assertTrue(state.visible)
        assertNull(state.currentAction)
        assertTrue(state.recentActions.isEmpty())
        assertFalse(state.isExpanded)
    }

    @Test
    fun `onToolStarted sets currentAction and prepends to recentActions`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        bridge.onToolStarted("tap(Wi-Fi)")
        bridge.onToolStarted("scroll(down)")
        val state = bridge.overlayState.value
        assertEquals("scroll(down)", state.currentAction)
        assertEquals(listOf("scroll(down)", "tap(Wi-Fi)"), state.recentActions)
    }

    @Test
    fun `recentActions caps at 5 entries`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        repeat(7) { bridge.onToolStarted("tool-$it") }
        val state = bridge.overlayState.value
        assertEquals(5, state.recentActions.size)
        assertEquals("tool-6", state.recentActions.first())
        assertEquals("tool-2", state.recentActions.last())
    }

    @Test
    fun `onToolFinished clears currentAction but keeps recentActions`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        bridge.onToolStarted("tap(X)")
        bridge.onToolFinished()
        val state = bridge.overlayState.value
        assertNull(state.currentAction)
        assertEquals(listOf("tap(X)"), state.recentActions)
    }

    @Test
    fun `onApprovalRequired sets pendingApproval and forces expansion`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        val req = ApprovalRequest("id-1", ApprovalKind.Exec, "rm cache", "...")
        bridge.onApprovalRequired(req)
        val state = bridge.overlayState.value
        assertEquals(req, state.pendingApproval)
        assertTrue(state.isExpanded)
    }

    @Test
    fun `onApprovalResolved clears pendingApproval`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        bridge.onApprovalRequired(ApprovalRequest("id-1", ApprovalKind.Exec, "s", "d"))
        bridge.onApprovalResolved()
        assertNull(bridge.overlayState.value.pendingApproval)
    }

    @Test
    fun `onAgentDone hides overlay and resets fields`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        bridge.onToolStarted("x")
        bridge.onAgentDone()
        val state = bridge.overlayState.value
        assertFalse(state.visible)
        assertNull(state.currentAction)
        assertTrue(state.recentActions.isEmpty())
    }

    @Test
    fun `onAgentStopped hides overlay`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        bridge.onAgentStopped()
        assertFalse(bridge.overlayState.value.visible)
    }

    @Test
    fun `requestStop emits into stopRequested`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        var received = false
        val collector = backgroundScope.launch {
            bridge.stopRequested.first()
            received = true
        }
        delay(50)
        bridge.requestStop()
        collector.join()
        assertTrue(received)
    }

    @Test
    fun `setCurrentPackage updates badge package`() = runTest {
        val bridge = AgentOverlayBridge()
        bridge.onAgentStarted()
        bridge.setCurrentPackage("com.android.settings")
        assertEquals("com.android.settings", bridge.overlayState.value.currentPackage)
    }
}
