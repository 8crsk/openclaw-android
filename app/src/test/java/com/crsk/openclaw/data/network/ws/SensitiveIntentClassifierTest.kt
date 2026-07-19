package com.crsk.openclaw.data.network.ws

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveIntentClassifierTest {

    private fun payload(vararg pairs: Pair<String, String>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `money intent is High risk`() {
        val r = SensitiveIntentClassifier.classify(payload("command" to "open phonepe and pay 500"))
        assertEquals(RiskLevel.High, r.level)
        assertEquals("Money", r.category)
    }

    @Test
    fun `destructive intent is High risk`() {
        val r = SensitiveIntentClassifier.classify(payload("summary" to "uninstall the app"))
        assertEquals(RiskLevel.High, r.level)
        assertEquals("Destructive", r.category)
    }

    @Test
    fun `messaging intent is Elevated risk`() {
        val r = SensitiveIntentClassifier.classify(payload("summary" to "send a whatsapp message to mom"))
        assertEquals(RiskLevel.Elevated, r.level)
        assertEquals("Messaging", r.category)
    }

    @Test
    fun `benign intent is Low risk with no category`() {
        val r = SensitiveIntentClassifier.classify(payload("summary" to "read the current screen"))
        assertEquals(RiskLevel.Low, r.level)
        assertEquals(null, r.category)
    }

    @Test
    fun `High wins over Elevated when both present`() {
        // "send" (Elevated/Messaging) + "pay" (High/Money) -> High
        val r = SensitiveIntentClassifier.classify(payload("summary" to "send payment via upi"))
        assertEquals(RiskLevel.High, r.level)
    }

    @Test
    fun `isSensitive stays true for any non-Low and false for Low`() {
        assertTrue(SensitiveIntentClassifier.isSensitive(payload("summary" to "transfer money")))
        assertTrue(SensitiveIntentClassifier.isSensitive(payload("summary" to "send sms")))
        assertFalse(SensitiveIntentClassifier.isSensitive(payload("summary" to "scroll down")))
    }
}
