package me.rerere.rikkahub.data.agent.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAccessGrantStoreTest {
    @Test
    fun grantAllowsOnlyScopedSessions() {
        val store = SessionAccessGrantStore()
        val grant = store.create(
            sessionIds = listOf("session-a", "session-b"),
            maxChars = 4_000,
            purpose = "summarize history",
            sourceConversationId = "parent",
        )

        val allowed = store.validate(grant.grantId, "session-a", 1_000)
        val denied = store.validate(grant.grantId, "session-c", 1_000)

        assertTrue(allowed is SessionAccessGrantStore.GrantValidation.Allowed)
        assertTrue(denied is SessionAccessGrantStore.GrantValidation.Denied)
    }

    @Test
    fun grantCharacterBudgetIsConsumed() {
        val store = SessionAccessGrantStore()
        val grant = store.create(
            sessionIds = listOf("session-a"),
            maxChars = 2_000,
            purpose = "summarize history",
            sourceConversationId = "parent",
        )

        store.recordUse(grant.grantId, 1_500)
        val allowed = store.validate(grant.grantId, "session-a", 1_000)

        assertTrue(allowed is SessionAccessGrantStore.GrantValidation.Allowed)
        assertEquals(500, (allowed as SessionAccessGrantStore.GrantValidation.Allowed).allowedChars)
    }

    @Test
    fun expiredGrantIsRejected() {
        val store = SessionAccessGrantStore()
        val grant = store.create(
            sessionIds = listOf("session-a"),
            maxChars = 2_000,
            purpose = "summarize history",
            sourceConversationId = "parent",
            ttlMs = 1L,
        )

        Thread.sleep(5)
        val result = store.validate(grant.grantId, "session-a", 1_000)

        assertTrue(result is SessionAccessGrantStore.GrantValidation.Denied)
    }
}
