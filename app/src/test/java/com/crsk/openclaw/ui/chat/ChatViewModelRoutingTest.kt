package com.crsk.openclaw.ui.chat

import com.crsk.openclaw.data.providers.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ChatViewModel.selectProvider — pure routing logic only.
 *
 * BYO-key era: the user picks a provider + model in Settings; selectProvider
 * resolves that selection against ProviderCatalog and falls back to safe
 * defaults so the returned pair always exists in openclaw's written config.
 */
class ChatViewModelRoutingTest {

    @Test fun `valid selection passes through unchanged`() {
        val (provider, model) = selectProvider("gem", "gemini-2.5-flash")
        assertEquals("gem", provider)
        assertEquals("gemini-2.5-flash", model)
    }

    @Test fun `every catalog model routes to its own provider`() {
        for (p in ProviderCatalog.all) {
            for (m in p.models) {
                val (provider, model) = selectProvider(p.id, m.id)
                assertEquals(p.id, provider)
                assertEquals(m.id, model)
            }
        }
    }

    @Test fun `unknown provider falls back to catalog default`() {
        val (provider, model) = selectProvider("pro", "nova-lite")
        assertEquals(ProviderCatalog.default.id, provider)
        assertEquals(ProviderCatalog.default.defaultModel.id, model)
    }

    @Test fun `model from a different provider falls back to the selected provider's default`() {
        // e.g. user switched provider in Settings but the stored model id still
        // belongs to the previous provider.
        val (provider, model) = selectProvider("oai", "gemini-2.5-flash")
        assertEquals("oai", provider)
        assertEquals(ProviderCatalog.OPENAI.defaultModel.id, model)
    }

    @Test fun `blank selection lands on the free NVIDIA default`() {
        val (provider, model) = selectProvider("", "")
        assertEquals("nim", provider)
        assertEquals("nvidia/nemotron-3-super-120b-a12b", model)
    }
}
