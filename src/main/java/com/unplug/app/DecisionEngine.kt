package com.unplug.app

data class Decision(val ring: Boolean, val reason: String)

/**
 * Rules-only engine (MVP). Layer order follows the project document:
 * 1. Safety net (calls/alarms, VIP, repeated contact)  2. User keyword rules.
 * Anything unmatched is held for the digest.
 * Phase B (on-device AI) plugs in as a third step before the final "hold".
 */
object DecisionEngine {
    private const val REPEAT_WINDOW_MS = 5 * 60_000L
    private val lastSeen = mutableMapOf<String, Long>()

    @Synchronized
    fun decide(pkg: String, title: String, text: String, category: String?, rules: List<Rule>): Decision {
        val now = System.currentTimeMillis()
        val key = "$pkg|$title"
        val prev = lastSeen[key]
        lastSeen[key] = now
        lastSeen.entries.removeAll { now - it.value > REPEAT_WINDOW_MS }

        if (category == "call" || category == "alarm") return Decision(true, "Call or alarm")

        val t = title.lowercase()
        rules.filter { it.type == "VIP" }.firstOrNull {
            val v = it.value.lowercase(); t.contains(v) || pkg.lowercase() == v
        }?.let { return Decision(true, "VIP: ${it.value}") }

        if (prev != null && now - prev <= REPEAT_WINDOW_MS)
            return Decision(true, "Same sender twice within 5 minutes")

        val hay = "$title $text".lowercase()
        rules.filter { it.type == "KEYWORD" }.firstOrNull { hay.contains(it.value.lowercase()) }
            ?.let { return Decision(true, "Keyword: ${it.value}") }

        return Decision(false, "No urgent rule matched")
    }
}
