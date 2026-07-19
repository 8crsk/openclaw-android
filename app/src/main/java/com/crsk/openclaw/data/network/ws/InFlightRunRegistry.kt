package com.crsk.openclaw.data.network.ws

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks runIds currently being streamed by a user-initiated ChatSession.streamChat.
 * HeartbeatChannel uses this to decide whether an event:"agent" frame belongs to
 * a chat reply (skip) or a heartbeat run (claim).
 */
@Singleton
class InFlightRunRegistry @Inject constructor() {
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun register(runId: String) { if (runId.isNotBlank()) active.add(runId) }
    fun unregister(runId: String) { if (runId.isNotBlank()) active.remove(runId) }
    fun isInFlight(runId: String): Boolean = runId.isNotBlank() && active.contains(runId)
}