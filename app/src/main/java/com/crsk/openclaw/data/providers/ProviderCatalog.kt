package com.crsk.openclaw.data.providers

/** One model a provider can serve. `id` is the exact model string sent to the
 *  provider's OpenAI-compatible endpoint (NVIDIA ids contain an org prefix,
 *  e.g. "meta/llama-3.3-70b-instruct" — that's part of the id, not a provider ref). */
data class ProviderModel(
    val id: String,
    val displayName: String,
    val tagline: String,
    val contextWindow: Int,
    val maxTokens: Int,
    val reasoning: Boolean = false,
)

/** How a provider authenticates a plain GET /models validation probe. */
enum class KeyAuthStyle {
    /** `Authorization: Bearer <key>` — NVIDIA, OpenAI, Gemini (OpenAI-compat layer). */
    BEARER,

    /** `x-api-key: <key>` + `anthropic-version` header — Anthropic's native API. */
    ANTHROPIC,
}

/**
 * A bring-your-own-key model provider.
 *
 * All four providers are written into openclaw config as *custom* providers
 * (explicit baseUrl + api: "openai-completions" + explicit models array), never
 * as openclaw's built-in provider plugins. The built-in plugins gate model ids
 * against a static catalog that has drifted from the live APIs (openclaw
 * 2026.5.12 rejects currently-valid NVIDIA models as "Unknown model"); custom
 * providers serve whatever models we declare. That's also why `id` deliberately
 * avoids the built-in plugin names ("nvidia", "openai", "anthropic", "google").
 */
data class AiProvider(
    val id: String,
    val displayName: String,
    /** OpenAI-compatible chat-completions base URL, no trailing slash. */
    val baseUrl: String,
    val keyLabel: String,
    val keyHint: String,
    val keyPattern: Regex,
    /** Where the user creates a key. */
    val consoleUrl: String,
    val freeTier: Boolean,
    val authStyle: KeyAuthStyle = KeyAuthStyle.BEARER,
    val models: List<ProviderModel>,
) {
    val defaultModel: ProviderModel get() = models.first()
}

object ProviderCatalog {

    val NVIDIA = AiProvider(
        id = "nim",
        displayName = "NVIDIA",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        keyLabel = "NVIDIA API key",
        keyHint = "nvapi-…",
        keyPattern = Regex("^nvapi-[A-Za-z0-9_-]{20,}$"),
        consoleUrl = "https://build.nvidia.com",
        freeTier = true,
        models = listOf(
            ProviderModel(
                id = "nvidia/nemotron-3-super-120b-a12b",
                displayName = "Nemotron 3 Super 120B",
                tagline = "Recommended free default. 262k context, strong tool use.",
                contextWindow = 262_144,
                maxTokens = 8_192,
            ),
            ProviderModel(
                id = "meta/llama-3.3-70b-instruct",
                displayName = "Llama 3.3 70B",
                tagline = "Free, fast, solid generalist.",
                contextWindow = 131_072,
                maxTokens = 8_192,
            ),
        ),
    )

    val OPENAI = AiProvider(
        id = "oai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        keyLabel = "OpenAI API key",
        keyHint = "sk-…",
        keyPattern = Regex("^sk-[A-Za-z0-9_-]{20,}$"),
        consoleUrl = "https://platform.openai.com/api-keys",
        freeTier = false,
        models = listOf(
            ProviderModel(
                id = "gpt-5.1",
                displayName = "GPT-5.1",
                tagline = "Frontier reasoning; strongest OpenAI agent brain.",
                contextWindow = 400_000,
                maxTokens = 64_000,
                reasoning = true,
            ),
            ProviderModel(
                id = "gpt-5.1-mini",
                displayName = "GPT-5.1 mini",
                tagline = "Cheaper and faster; good for everyday agent loops.",
                contextWindow = 400_000,
                maxTokens = 64_000,
            ),
        ),
    )

    val ANTHROPIC = AiProvider(
        id = "ant",
        displayName = "Anthropic",
        // Anthropic's OpenAI-SDK-compatible endpoint. Chat completions POST to
        // <baseUrl>/chat/completions and accept Bearer auth; only the /models
        // validation probe needs native x-api-key headers (see KeyAuthStyle).
        baseUrl = "https://api.anthropic.com/v1",
        keyLabel = "Anthropic API key",
        keyHint = "sk-ant-…",
        keyPattern = Regex("^sk-ant-[A-Za-z0-9_-]{20,}$"),
        consoleUrl = "https://console.anthropic.com/settings/keys",
        freeTier = false,
        authStyle = KeyAuthStyle.ANTHROPIC,
        models = listOf(
            ProviderModel(
                id = "claude-sonnet-5",
                displayName = "Claude Sonnet 5",
                tagline = "Excellent tool use and long agent runs.",
                contextWindow = 200_000,
                maxTokens = 64_000,
                reasoning = true,
            ),
            ProviderModel(
                id = "claude-haiku-4-5-20251001",
                displayName = "Claude Haiku 4.5",
                tagline = "Fast and cheap; great phone-agent loop model.",
                contextWindow = 200_000,
                maxTokens = 64_000,
            ),
        ),
    )

    val GEMINI = AiProvider(
        id = "gem",
        displayName = "Google Gemini",
        // Gemini's OpenAI-compat layer (AI Studio key, generous free tier).
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        keyLabel = "Gemini API key",
        keyHint = "AIza…",
        keyPattern = Regex("^AIza[A-Za-z0-9_-]{30,}$"),
        consoleUrl = "https://aistudio.google.com/apikey",
        freeTier = true,
        models = listOf(
            ProviderModel(
                id = "gemini-2.5-flash",
                displayName = "Gemini 2.5 Flash",
                tagline = "Free-tier friendly, fast, 1M context.",
                contextWindow = 1_000_000,
                maxTokens = 65_536,
            ),
            ProviderModel(
                id = "gemini-2.5-pro",
                displayName = "Gemini 2.5 Pro",
                tagline = "Stronger reasoning, 1M context.",
                contextWindow = 1_000_000,
                maxTokens = 65_536,
                reasoning = true,
            ),
        ),
    )

    val all: List<AiProvider> = listOf(NVIDIA, OPENAI, ANTHROPIC, GEMINI)

    /** NVIDIA is the hero path: free keys, no card required. */
    val default: AiProvider = NVIDIA

    fun byId(id: String): AiProvider? = all.firstOrNull { it.id == id }

    fun modelDisplayName(modelId: String): String =
        all.asSequence()
            .flatMap { it.models }
            .firstOrNull { it.id == modelId }
            ?.displayName
            ?: modelId.substringAfterLast('/')
}
