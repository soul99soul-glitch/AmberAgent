package app.amber.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesStoreTest {
    @Test
    fun defaultAgentPromptUsesToolSearchForHiddenToolDiscovery() {
        // 默认提示词已换新文（旧文移入 PREVIOUS_DEFAULT_AGENT_SOUL_MARKDOWN）：
        // 断言跟随现行 DEFAULT 的 tool_search 引导措辞，意图不变——隐藏工具经
        // tool_search 发现、tools_list 仅目录/调试视图。
        assertTrue(DEFAULT_AGENT_SOUL_MARKDOWN.contains("call tool_search with a concrete intent or exact name"))
        assertTrue(DEFAULT_AGENT_SOUL_MARKDOWN.contains("tools_list is a catalog/debug view"))
        assertFalse(DEFAULT_AGENT_SOUL_MARKDOWN.contains("tools_list(category=\"mcp\")"))
    }
}
