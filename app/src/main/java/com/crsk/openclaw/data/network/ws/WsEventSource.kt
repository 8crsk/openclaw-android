package com.crsk.openclaw.data.network.ws

import kotlinx.coroutines.flow.SharedFlow

/**
 * Minimal interface over the event side of [WsRpcClient], extracted so that
 * [HeartbeatChannel] and tests can depend on this contract rather than the
 * concrete class.
 */
interface WsEventSource {
    val events: SharedFlow<Frame.Event>
}