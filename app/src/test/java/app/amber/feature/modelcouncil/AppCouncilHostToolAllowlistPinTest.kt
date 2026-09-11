package app.amber.feature.modelcouncil

import app.amber.core.ai.tools.createAskUserTool
import app.amber.core.ai.tools.createSearchTools
import app.amber.core.ai.tools.createTimeTool
import app.amber.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ModelCouncil host tool allowlist. [AppCouncilHostToolProvider] is
 * the ONLY place in the council that exposes tools, and it auto-approves
 * everything it executes (`autoApproveTools = true` with no approval UI, no
 * run kernel, no per-run sandbox policy — see its KDoc). The allowlist
 * therefore must not grow silently: a tool added to [AppCouncilHostToolProvider.HOST_TOOL_NAMES]
 * is executed with zero human decision point.
 *
 * These tests fail on ANY change to the allowlist — additions, removals and
 * renames — so widening the host's tool surface is a conscious, reviewed act.
 * `ask_user` is deliberately NOT in the allowlist: it is surfaced for the
 * room's HITL flow and intercepted before execution.
 */
class AppCouncilHostToolAllowlistPinTest {

    @Test
    fun `council host allowlist is pinned to the read-only research trio`() {
        assertEquals(
            "AppCouncilHostToolProvider.HOST_TOOL_NAMES changed; widening the " +
                "auto-approved council host surface is a security decision, not a cleanup.",
            setOf("search_web", "scrape_web", "get_time_info"),
            AppCouncilHostToolProvider.HOST_TOOL_NAMES,
        )
    }

    @Test
    fun `every allowlisted name is actually produced by the host tool factories`() {
        // Mirrors the provider's own assembly (search set + time tool, both
        // filtered through HOST_TOOL_NAMES membership) so a renamed or removed
        // factory tool cannot leave a dead (or silently narrowed) allowlist.
        val produced = buildSet {
            addAll(
                createSearchTools(Settings(), includeWebViewFallbackGuidance = false)
                    .filter { it.name in AppCouncilHostToolProvider.HOST_TOOL_NAMES }
                    .map { it.name },
            )
            val timeTool = createTimeTool()
            if (timeTool.name in AppCouncilHostToolProvider.HOST_TOOL_NAMES) add(timeTool.name)
        }
        assertTrue(
            "allowlist entries not produced by the factories (dead allowlist): " +
                "${(AppCouncilHostToolProvider.HOST_TOOL_NAMES - produced).sorted()}",
            produced.containsAll(AppCouncilHostToolProvider.HOST_TOOL_NAMES),
        )
        assertEquals(AppCouncilHostToolProvider.HOST_TOOL_NAMES, produced)
    }

    @Test
    fun `ask_user is surfaced but never part of the executed allowlist`() {
        assertEquals("ask_user", AppCouncilHostToolProvider.ASK_USER_TOOL_NAME)
        val askUser = createAskUserTool()
        assertEquals(AppCouncilHostToolProvider.ASK_USER_TOOL_NAME, askUser.name)
        assertFalse(
            "ask_user must stay outside HOST_TOOL_NAMES: the provider intercepts it " +
                "into HostToolOutcome.AskUser instead of executing it.",
            askUser.name in AppCouncilHostToolProvider.HOST_TOOL_NAMES,
        )
    }
}
