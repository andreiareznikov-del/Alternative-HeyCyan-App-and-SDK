package com.fersaiyan.cyanbridge.w610chatgpt

import org.junit.Assert.*
import org.junit.Test

class EvidencePolicyTest {
    @Test fun dispatchDoesNotConfirmAnything() {
        val dispatched = setOf("CHATGPT_SHARE_STARTED", "INTENT_DISPATCH_RETURNED")
        for (event in listOf("CHATGPT_SHARE_ACCEPTED", "IMAGE_ATTACHMENT_CONFIRMED", "LIVE_SESSION_CONTINUITY_VERIFIED"))
            assertFalse(EvidencePolicy.permits(event, dispatched, ""))
    }
    @Test fun onlyTargetOpenCanConfirmReceipt() {
        assertTrue(EvidencePolicy.permits("CHATGPT_SHARE_ACCEPTED", setOf("CHATGPT_SHARE_STARTED"), "TARGET_OPENED_URI"))
        assertFalse(EvidencePolicy.permits("CHATGPT_SHARE_ACCEPTED", emptySet(), "TARGET_OPENED_URI"))
        assertFalse(EvidencePolicy.permits("CHATGPT_SHARE_ACCEPTED", setOf("CHATGPT_SHARE_STARTED"), "INTENT_RETURNED"))
    }
    @Test fun attachmentRequiresReceiptAndObserver() {
        assertTrue(EvidencePolicy.permits("IMAGE_ATTACHMENT_CONFIRMED", setOf("CHATGPT_SHARE_ACCEPTED"), "USER_OBSERVED_ATTACHMENT"))
        assertFalse(EvidencePolicy.permits("IMAGE_ATTACHMENT_CONFIRMED", emptySet(), "USER_OBSERVED_ATTACHMENT"))
        assertFalse(EvidencePolicy.permits("IMAGE_ATTACHMENT_CONFIRMED", setOf("CHATGPT_SHARE_ACCEPTED"), "TARGET_OPENED_URI"))
    }
    @Test fun liveIsNeverInferredFromAttachment() {
        assertFalse(EvidencePolicy.permits("LIVE_SESSION_CONTINUITY_VERIFIED", setOf("IMAGE_ATTACHMENT_CONFIRMED"), ""))
        assertTrue(EvidencePolicy.permits("LIVE_SESSION_CONTINUITY_VERIFIED", setOf("IMAGE_ATTACHMENT_CONFIRMED"), "USER_VERIFIED_SAME_LIVE"))
    }
}
