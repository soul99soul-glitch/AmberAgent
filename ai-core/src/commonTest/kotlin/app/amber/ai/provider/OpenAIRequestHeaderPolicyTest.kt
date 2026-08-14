package app.amber.ai.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAIRequestHeaderPolicyTest {
    @Test
    fun codingPlanInjectsVersionedOpenCodeUserAgent() {
        val headers = resolveOpenAIRequestHeaders(OpenAIAuthMode.ZHIPU_CODING_PLAN)
        assertEquals(
            listOf(CustomHeader("User-Agent", OpenAICompatUserAgents.OPENCODE)),
            headers,
        )
        assertTrue(OpenAICompatUserAgents.OPENCODE.startsWith("opencode/"))
        assertTrue(OpenAICompatUserAgents.OPENCODE.removePrefix("opencode/").contains('.'))
    }

    @Test
    fun apiKeyModeDoesNotInventAUserAgent() {
        assertTrue(resolveOpenAIRequestHeaders(OpenAIAuthMode.API_KEY).isEmpty())
    }

    @Test
    fun explicitUserAgentWinsOverOpenCodeDefault() {
        val headers = resolveOpenAIRequestHeaders(
            authMode = OpenAIAuthMode.KIMI_CODING_PLAN,
            extraHeaders = listOf(
                CustomHeader("user-agent", OpenAICompatUserAgents.CURSOR),
                CustomHeader("X-Title", "AmberAgent"),
            ),
        )
        assertEquals("User-Agent", headers.single { it.name == "User-Agent" }.name)
        assertEquals(OpenAICompatUserAgents.CURSOR, headers.single { it.name == "User-Agent" }.value)
        assertEquals("AmberAgent", headers.single { it.name == "X-Title" }.value)
    }

    @Test
    fun tokenPlanAuthModeIsBrandScoped() {
        assertEquals(OpenAIAuthMode.ZHIPU_CODING_PLAN, OpenAIBrand.ZHIPU.tokenPlanAuthMode())
        assertEquals(OpenAIAuthMode.KIMI_CODING_PLAN, OpenAIBrand.KIMI.tokenPlanAuthMode())
        assertNull(OpenAIBrand.OPENAI.tokenPlanAuthMode())
        assertTrue(OpenAIAuthMode.MINIMAX_TOKEN_PLAN.isCodingPlan())
        assertEquals(
            "https://open.bigmodel.cn/api/coding/paas/v4",
            OpenAIAuthMode.ZHIPU_CODING_PLAN.fixedBaseUrl(),
        )
    }
}
