package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesStoreTest {
    @Test
    fun defaultAgentPromptUsesToolSearchForHiddenToolDiscovery() {
        assertTrue(DEFAULT_AGENT_SOUL_MARKDOWN.contains("call tool_search first"))
        assertTrue(DEFAULT_AGENT_SOUL_MARKDOWN.contains("tools_list is catalog/debug only"))
        assertFalse(DEFAULT_AGENT_SOUL_MARKDOWN.contains("tools_list(category=\"mcp\")"))
    }
}
