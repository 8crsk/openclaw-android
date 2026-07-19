package com.crsk.openclaw.data.composio

/** A toolkit in Composio's catalog (Gmail, Notion, Linear, etc.). */
data class Toolkit(
    val slug: String,
    val name: String,
    val description: String,
    val logoUrl: String?,
    val categories: List<String>,
    val authType: String, // "OAUTH2" | "API_KEY" | "NO_AUTH"
)

/** A user's connected account for a toolkit. */
data class Connection(
    val toolkitSlug: String,
    val connectionId: String,
    val status: ConnectionStatus,
    val connectedAt: Long,
)

enum class ConnectionStatus {
    INITIATED, // OAuth started, user hasn't completed
    ACTIVE,    // Live and usable
    FAILED,    // OAuth denied or expired
    REVOKED;   // User disconnected (or set INACTIVE on Composio side)

    companion object {
        fun fromString(s: String?): ConnectionStatus = when (s?.uppercase()) {
            "ACTIVE" -> ACTIVE
            // Composio uses "INITIALIZING" while the OAuth tab is open; "INITIATED" and
            // "PENDING" are kept as fallbacks for other API versions.
            "INITIATED", "INITIALIZING", "PENDING" -> INITIATED
            "FAILED", "EXPIRED" -> FAILED
            "REVOKED", "DELETED", "INACTIVE" -> REVOKED
            else -> INITIATED
        }
    }
}

/** Result of initiating a connection — the redirect URL plus the connection id we'll poll on. */
data class ConnectInit(
    val connectionId: String,
    val redirectUrl: String,
)
