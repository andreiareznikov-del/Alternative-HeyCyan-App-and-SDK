package com.fersaiyan.cyanbridge.w610chatgpt

/** One capture owner. READY has no transaction ID, so uncorrelated events have a cooldown. */
internal class OperationGate(private val cooldownMs: Long = 3_000) {
    data class Operation(val id: String, val origin: String, var awaitingReady: Boolean = true)
    var active: Operation? = null
        private set
    private var lastCompletedAt = Long.MIN_VALUE / 2

    fun begin(id: String, origin: String, now: Long): Operation? {
        if (active != null || now - lastCompletedAt < cooldownMs) return null
        return Operation(id, origin).also { active = it }
    }

    fun ready(): Operation? = active?.takeIf { it.awaitingReady }?.also { it.awaitingReady = false }

    fun finish(id: String, now: Long) {
        if (active?.id == id) { active = null; lastCompletedAt = now }
    }
}

internal object EvidencePolicy {
    fun permits(event: String, prior: Set<String>, witness: String): Boolean = when (event) {
        "CHATGPT_SHARE_ACCEPTED" -> "CHATGPT_SHARE_STARTED" in prior && witness == "TARGET_OPENED_URI"
        "IMAGE_ATTACHMENT_CONFIRMED" -> "CHATGPT_SHARE_ACCEPTED" in prior && witness == "USER_OBSERVED_ATTACHMENT"
        "LIVE_SESSION_CONTINUITY_VERIFIED" -> "IMAGE_ATTACHMENT_CONFIRMED" in prior && witness == "USER_VERIFIED_SAME_LIVE"
        else -> true
    }
}
