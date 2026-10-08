package app.amber.ai.provider.providers.grok

import app.amber.ai.provider.Model
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokOAuthTest {
    @Test fun authorizationUsesRegisteredPublicClientWithPkce() {
        val url = grokAuthorizationUrl("state-1", "nonce-1", "challenge-1").toHttpUrl()
        assertEquals("https://auth.x.ai/oauth2/authorize", url.toString().substringBefore('?'))
        assertEquals("b1a00492-073a-47ea-816f-4c329264a828", url.queryParameter("client_id"))
        assertEquals("http://127.0.0.1:8787/callback", url.queryParameter("redirect_uri"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("challenge-1", url.queryParameter("code_challenge"))
        assertTrue(url.queryParameter("scope")!!.split(' ').contains("grok-cli:access"))
    }

    @Test fun authorizationPreservesEncodedStateAndNonce() {
        val url = grokAuthorizationUrl("state+ &value", "nonce+ &value", "challenge-_1").toHttpUrl()
        assertEquals("state+ &value", url.queryParameter("state"))
        assertEquals("nonce+ &value", url.queryParameter("nonce"))
    }

    @Test fun statusDistinguishesMissingAndRefreshableExpiredToken() {
        assertEquals(GrokAuthStatusCode.NOT_SIGNED_IN, GrokAuthStatus.from(null, 100L).code)
        val expired = GrokAuthStatus.from(GrokOAuthTokens("access", "refresh", 100L), 100L)
        assertEquals(GrokAuthStatusCode.TOKEN_EXPIRED, expired.code)
        assertTrue(expired.usable)
    }

    @Test fun parserAcceptsOpenAiDataModelsAndSkipsMalformedEntries() {
        val models = parseGrokModels("""{"data":[{"id":" grok-4.5 ","name":"Grok 4.5"},{"id":""},{"name":"missing-id"}]}""")
        assertEquals(1, models.size)
        assertEquals("grok-4.5", models.single().modelId)
        assertEquals("Grok 4.5", models.single().displayName)
    }

    @Test fun parserAcceptsModelsEnvelopeAndFallbackIsNonEmpty() {
        assertEquals("grok-4.6", parseGrokModels("""{"models":[{"model":"grok-4.6"}]}""").single().modelId)
        assertTrue(defaultGrokOAuthModels().isNotEmpty())
    }
}
