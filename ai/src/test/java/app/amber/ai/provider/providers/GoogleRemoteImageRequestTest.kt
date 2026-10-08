package app.amber.ai.provider.providers

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import app.amber.ai.util.ImageEncodingException
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** Uses the public provider complete/stream routes and injected HTTP seam, with no network. */
class GoogleRemoteImageRequestTest {
    private val remoteUrl = "HTTPS://images.test/input.png?signature=whole-url"
    private val imageBytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfX8AAAAASUVORK5CYII=")

    private suspend fun assertRequest(streaming: Boolean) {
        val uploaded = AtomicReference<JsonObject>()
        val fetched = mutableListOf<String>()
        val completion = """{"candidates":[{"content":{"role":"model","parts":[{"text":"seen"}]},"finishReason":"STOP"}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val imageFetch = request.url.host == "images.test"
            val body = if (imageFetch) {
                fetched.add(request.url.toString())
                imageBytes.toResponseBody("image/png".toMediaType())
            } else {
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                uploaded.set(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
                if (streaming) "data: $completion\n\n".toResponseBody("text/event-stream".toMediaType())
                else completion.toResponseBody("application/json".toMediaType())
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body).build()
        }.build()
        val provider = GoogleProvider(client)
        val setting = ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "test-key")
        val params = TextGenerationParams(Model(modelId = "gemini-test"))
        val messages = listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image(remoteUrl))))
        if (streaming) {
            assertTrue(provider.stream(setting, messages, params).toList().isNotEmpty())
        } else {
            assertEquals("seen", provider.complete(setting, messages, params)
                .choices.single().message!!.toText())
        }
        assertEquals(listOf(remoteUrl.replaceFirst("HTTPS://", "https://")), fetched)
        val part = uploaded.get().getValue("contents").jsonArray.single().jsonObject
            .getValue("parts").jsonArray.single().jsonObject
        val data = part.getValue("inlineData").jsonObject
        assertEquals("image/png", data.getValue("mimeType").jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(imageBytes), data.getValue("data").jsonPrimitive.content)
        assertEquals("Caller-owned message must retain original URL", remoteUrl,
            (messages.single().parts.single() as UIMessagePart.Image).url)
    }

    @Test fun completeFetchesRemoteImageBeforeEncodingRequest() = runBlocking { assertRequest(false) }
    @Test fun streamFetchesRemoteImageBeforeEncodingRequest() = runBlocking { assertRequest(true) }

    @Test fun failedImageDownloadPreventsGenerationRatherThanDroppingAttachment() = runBlocking {
        var apiRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != "images.test") apiRequests++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404)
                .message("Not Found").body("missing".toResponseBody("text/plain".toMediaType())).build()
        }.build()
        try {
            GoogleProvider(client).complete(
                ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "test-key"),
                listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(remoteUrl)))),
                TextGenerationParams(Model(modelId = "gemini-test")),
            )
            fail("Failed image download must surface a real error")
        } catch (_: ImageEncodingException) {
            assertEquals(0, apiRequests)
        }
    }

    @Test fun oversizedImagePreventsGeneration() = runBlocking {
        var apiRequests = 0
        val bytes = ByteArray(5 * 1024 * 1024 + 1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != "images.test") apiRequests++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        try {
            GoogleProvider(client).complete(
                ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "test-key"),
                listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(remoteUrl)))),
                TextGenerationParams(Model(modelId = "gemini-test")),
            )
            fail("Oversized remote image must surface a real error")
        } catch (_: ImageEncodingException) {
            assertEquals(0, apiRequests)
        }
    }
}
