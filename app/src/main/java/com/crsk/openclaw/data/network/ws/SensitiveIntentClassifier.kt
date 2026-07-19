package com.crsk.openclaw.data.network.ws

import org.json.JSONObject

/** Severity of an agent action, lowest -> highest. */
enum class RiskLevel { Low, Elevated, High }

/** Result of classifying an approval payload. [category] is null when [level] is Low. */
data class RiskAssessment(val level: RiskLevel, val category: String?)

/**
 * Decides how sensitive an agent action is. Keyword-based on the JSON payload openclaw
 * sends with `exec.approval.requested` / `plugin.approval.requested`. False positives are
 * fine (one extra confirmation); false negatives are not (silent money movement).
 *
 * Per-action consent record for the load-bearing categories the user (and DPDP §6 /
 * GDPR Art. 7 informed-consent) cares most about: money, messages, account deletion,
 * package install/uninstall, dialing arbitrary numbers.
 */
object SensitiveIntentClassifier {

    private data class Rule(val category: String, val level: RiskLevel, val pattern: Regex)

    private val RULES = listOf(
        // Money / payments. Catches UPI, wallets, bank app intents, payment SDKs.
        Rule("Money", RiskLevel.High, Regex("""\b(pay|payment|paytm|gpay|phonepe|upi|bhim|razorpay|stripe|transfer|wallet|bank|venmo|cashapp|zelle)\b""", RegexOption.IGNORE_CASE)),
        // Destructive: uninstall, delete-account, factory reset, wipe.
        Rule("Destructive", RiskLevel.High, Regex("""\b(uninstall|delete\s*account|factory\s*reset|wipe|clear\s*data|remove\s*account)\b""", RegexOption.IGNORE_CASE)),
        // Calls — dialing arbitrary numbers is high-risk (premium-rate, social-engineering).
        Rule("Call", RiskLevel.High, Regex("""\b(dial|call.*number|tel:|callto:)\b""", RegexOption.IGNORE_CASE)),
        // Sending messages outward — distinct from reading. Sending on the user's behalf needs consent.
        Rule("Messaging", RiskLevel.Elevated, Regex("""\b(send|sendmsg|sendmessage|sendtext|sms|whatsapp.*send|reply.*send|compose.*send)\b""", RegexOption.IGNORE_CASE)),
        // Package install — sideloading anything is a privilege handoff.
        Rule("Install", RiskLevel.Elevated, Regex("""\b(install|installer|sideload|adb\s*install|pm\s+install)\b""", RegexOption.IGNORE_CASE)),
    )

    /** Highest matching level wins; that level's first matching rule supplies the category. */
    fun classify(payload: JSONObject): RiskAssessment {
        val haystack = payload.toString()
        val matches = RULES.filter { it.pattern.containsMatchIn(haystack) }
        val top = matches.maxByOrNull { it.level.ordinal }
            ?: return RiskAssessment(RiskLevel.Low, null)
        return RiskAssessment(top.level, top.category)
    }

    /** Back-compat: any non-Low classification is "sensitive" -> always prompt. */
    fun isSensitive(payload: JSONObject): Boolean = classify(payload).level != RiskLevel.Low
}
