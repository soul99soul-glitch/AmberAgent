package app.amber.ai.provider.providers.openai

import app.amber.ai.provider.ImageGenerationMode
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.ai.ui.ImageAspectRatio
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * P6-02 — request construction for create vs edit (plan §P6-02 #5/#7):
 * - create keeps the historic `/images/generations` JSON body (no mode /
 *   source fields leak in);
 * - edit builds the `/images/edits` multipart with the source image file +
 *   prompt + model + n + size, and rejects missing / deleted / oversized /
 *   unsupported-format sources before any upload.
 */
class OpenAIImageEditRequestTest {

    private val provider = OpenAIProvider(okhttp3.OkHttpClient(), null)

    private fun setting() = ProviderSetting.OpenAI(
        id = Uuid.random(),
        baseUrl = "https://api.openai.com/v1",
        useResponseApi = true,
        authMode = app.amber.ai.provider.OpenAIAuthMode.API_KEY,
        name = "OpenAI",
    )

    private fun params(
        prompt: String = "add a hat",
        mode: ImageGenerationMode = ImageGenerationMode.EDIT,
        sourceImageUrl: String? = null,
    ) = ImageGenerationParams(
        model = Model(modelId = "gpt-image-1", displayName = "GPT Image"),
        prompt = prompt,
        numOfImages = 1,
        aspectRatio = ImageAspectRatio.LANDSCAPE,
        mode = mode,
        sourceImageUrl = sourceImageUrl,
    )

    @Test
    fun createBodyIsUnchangedJsonWithoutModeOrSourceFields() {
        val body = provider.buildImagesGenerationsBody(params(mode = ImageGenerationMode.CREATE))
        assertTrue(body.contains("\"model\":\"gpt-image-1\""))
        assertTrue(body.contains("\"prompt\":\"add a hat\""))
        assertTrue(body.contains("\"n\":1"))
        assertTrue(body.contains("\"size\":\"1536x1024\""))
        // P6-02 regression guard: edit fields never leak into create requests.
        assertFalse(body.contains("\"mode\""))
        assertFalse(body.contains("source_image_url"))
    }

    @Test
    fun createBodyUsesCustomSizeWhenBothDimensionsProvided() {
        val body = provider.buildImagesGenerationsBody(
            params(mode = ImageGenerationMode.CREATE)
                .copy(customWidth = 512, customHeight = 768)
        )
        assertTrue(body.contains("\"size\":\"512x768\""))
    }

    @Test
    fun customSizeFallsBackToPresetMappingWhenOnlyOneDimensionProvided() {
        val body = provider.buildImagesGenerationsBody(
            params(mode = ImageGenerationMode.CREATE)
                .copy(customWidth = 512)
        )
        // Custom size needs BOTH dimensions; partial input falls back to the
        // aspect-ratio preset (LANDSCAPE here).
        assertTrue(body.contains("\"size\":\"1536x1024\""))
    }

    @Test
    fun editMultipartCarriesSourceFilePromptModelSizeAndCount() {
        val source = File.createTempFile("source", ".png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
            deleteOnExit()
        }
        val body = provider.buildImagesEditMultipart(
            params(sourceImageUrl = "file://${source.absolutePath}")
        )
        assertEquals(MultipartBody.FORM, body.type)

        val parts = body.parts.associate { part ->
            val name = part.headers
                ?.get("Content-Disposition")
                ?.substringAfter("name=\"", "")
                ?.substringBefore("\"")
                ?: ""
            name to part.body
        }
        assertEquals("gpt-image-1", readUtf8(parts.getValue("model")))
        assertEquals("add a hat", readUtf8(parts.getValue("prompt")))
        assertEquals("1", readUtf8(parts.getValue("n")))
        assertEquals("1536x1024", readUtf8(parts.getValue("size")))
        // The uploaded bytes are exactly the source file contents.
        val imageBytes = readBytes(parts.getValue("image"))
        assertEquals(
            "137,80,78,71,1,2,3,4",
            imageBytes.joinToString(",") { b -> (b.toInt() and 0xFF).toString() },
        )
    }

    @Test
    fun editMultipartUsesCustomSizeWhenProvided() {
        val source = File.createTempFile("source", ".png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
            deleteOnExit()
        }
        val body = provider.buildImagesEditMultipart(
            params(sourceImageUrl = "file://${source.absolutePath}")
                .copy(customWidth = 512, customHeight = 768)
        )
        val parts = body.parts.associate { part ->
            val name = part.headers
                ?.get("Content-Disposition")
                ?.substringAfter("name=\"", "")
                ?.substringBefore("\"")
                ?: ""
            name to part.body
        }
        assertEquals("512x768", readUtf8(parts.getValue("size")))
    }

    @Test
    fun editWithoutSourceUrlFails() {
        try {
            provider.buildImagesEditMultipart(params(sourceImageUrl = null))
            fail("expected failure for missing source URL")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("source image URL"))
        }
    }

    @Test
    fun editWithDeletedSourceFails() {
        val gone = File.createTempFile("gone", ".png")
        gone.delete()
        try {
            provider.buildImagesEditMultipart(params(sourceImageUrl = "file://${gone.absolutePath}"))
            fail("expected failure for deleted source")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("not found"))
        }
    }

    @Test
    fun editWithOversizedSourceFails() {
        val big = File.createTempFile("big", ".png").apply {
            writeBytes(ByteArray(OpenAIProvider.IMAGE_EDIT_MAX_SOURCE_BYTES.toInt() + 1))
            deleteOnExit()
        }
        try {
            provider.buildImagesEditMultipart(params(sourceImageUrl = "file://${big.absolutePath}"))
            fail("expected failure for oversized source")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("limit"))
        }
    }

    @Test
    fun editWithUnsupportedFormatFails() {
        val gif = File.createTempFile("anim", ".gif").apply {
            writeBytes(byteArrayOf(0x47, 0x49, 0x46, 1, 2, 3))
            deleteOnExit()
        }
        try {
            provider.buildImagesEditMultipart(params(sourceImageUrl = "file://${gif.absolutePath}"))
            fail("expected failure for unsupported format")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("format"))
        }
    }

    private fun readUtf8(body: okhttp3.RequestBody): String = readBytes(body).decodeToString()

    private fun readBytes(body: okhttp3.RequestBody): ByteArray {
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readByteArray()
    }
}
