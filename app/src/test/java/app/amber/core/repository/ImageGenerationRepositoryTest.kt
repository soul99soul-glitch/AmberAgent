package app.amber.core.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.ImageGenerationMode
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ImageModelGateway
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.ImageAspectRatio
import app.amber.ai.ui.ImageGenerationItem
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.agent.data.files.CasTestFixtures
import app.amber.core.ai.tools.createImageGenTool
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.prompts.AgentPromptConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

/**
 * P6-02 — edit loop validation (plan §P6-02 #7, JVM-executable part):
 * - create path unchanged (params stay CREATE / no source);
 * - edit request construction carries the source reference;
 * - deleted source fails with a distinguishable error;
 * - unsupported provider fails honestly (no fake support);
 * - oversized / unsupported-format / out-of-conversation sources are
 *   rejected before any provider call;
 * - the generate_image tool schema exposes edit params only when the
 *   provider declares edit capability, and mode=edit routes to the
 *   repository with the resolved source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageGenerationRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var filesManager: FilesManager
    private lateinit var providerCatalog: ProviderCatalog
    private lateinit var fakeProvider: FakeImageProvider
    private lateinit var repository: ImageGenerationRepository
    private lateinit var imageModel: Model

    @Before
    fun setUp() = runTest {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val appScope = AppScope()
        filesManager = FilesManager(context, FilesRepository(db.managedFileDao()), appScope)
        val testRoot = File(context.cacheDir, "img-gen-${System.nanoTime()}").apply { mkdirs() }
        settingsStore = CasTestFixtures.settingsAggregator(context, testRoot)

        imageModel = Model(
            modelId = "gpt-image-test",
            displayName = "GPT Image Test",
            type = ModelType.IMAGE,
        )
        val openAiSetting = ProviderSetting.OpenAI(models = listOf(imageModel))
        settingsStore.update(
            Settings(
                providers = listOf(openAiSetting),
                imageGenerationModelId = imageModel.id,
            )
        )

        fakeProvider = FakeImageProvider()
        val httpClient = okhttp3.OkHttpClient()
        providerCatalog = ProviderCatalog(
            openAIProvider = app.amber.ai.provider.providers.OpenAIProvider(httpClient, context),
            googleProvider = app.amber.ai.provider.providers.GoogleProvider(httpClient, context),
            claudeProvider = app.amber.ai.provider.providers.ClaudeProvider(httpClient, context),
            openAITextGateway = fakeProvider,
            openAIImageGateway = fakeProvider,
        )

        repository = ImageGenerationRepository(
            settingsStore = settingsStore,
            providerCatalog = providerCatalog,
            filesManager = filesManager,
            promptConfigRepository = AgentPromptConfigRepository(context),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun conversationId(): Uuid = Uuid.random()

    // ---- repository: create ----

    @Test
    fun createPassesCreateParamsAndWritesFiles() = runTest {
        val result = repository.generateForConversation(
            modelId = imageModel.id,
            prompt = "a cat",
            aspectRatio = ImageAspectRatio.SQUARE,
            numOfImages = 1,
            conversationId = conversationId(),
        )
        assertTrue(result.isSuccess)
        val received = fakeProvider.received.single()
        assertEquals(ImageGenerationMode.CREATE, received.mode)
        assertNull(received.sourceImageUrl)
        val saved = result.getOrThrow().single()
        assertTrue(saved.file.exists())
    }

    // ---- repository: edit ----

    @Test
    fun editPassesEditParamsWithSourceReference() = runTest {
        val conversationId = conversationId()
        val source = File(filesManager.getChatImagesDir(conversationId), "source.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        }
        val result = repository.generateForConversation(
            modelId = imageModel.id,
            prompt = "make it night",
            aspectRatio = ImageAspectRatio.LANDSCAPE,
            numOfImages = 1,
            conversationId = conversationId,
            mode = ImageGenerationMode.EDIT,
            sourceImageUrl = "file://${source.absolutePath}",
        )
        assertTrue(result.isSuccess)
        val received = fakeProvider.received.single()
        assertEquals(ImageGenerationMode.EDIT, received.mode)
        assertEquals("file://${source.absolutePath}", received.sourceImageUrl)
        assertTrue(result.getOrThrow().single().file.exists())
    }

    @Test
    fun editWithDeletedSourceFails() = runTest {
        val conversationId = conversationId()
        val gone = File(filesManager.getChatImagesDir(conversationId), "gone.png")
        gone.parentFile?.mkdirs()
        gone.writeBytes(byteArrayOf(1, 2, 3))
        gone.delete()
        val result = repository.generateForConversation(
            modelId = imageModel.id,
            prompt = "make it night",
            aspectRatio = ImageAspectRatio.SQUARE,
            numOfImages = 1,
            conversationId = conversationId,
            mode = ImageGenerationMode.EDIT,
            sourceImageUrl = "file://${gone.absolutePath}",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("not found"))
        assertTrue(fakeProvider.received.isEmpty())
    }

    @Test
    fun editWithoutSourceUrlFails() = runTest {
        val result = repository.generateForConversation(
            modelId = imageModel.id,
            prompt = "make it night",
            aspectRatio = ImageAspectRatio.SQUARE,
            numOfImages = 1,
            conversationId = conversationId(),
            mode = ImageGenerationMode.EDIT,
            sourceImageUrl = null,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("source image URL"))
    }

    @Test
    fun editWithUnsupportedProviderFailsHonestly() = runTest {
        fakeProvider.editSupported = false
        val conversationId = conversationId()
        val source = File(filesManager.getChatImagesDir(conversationId), "source.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        }
        val result = repository.generateForConversation(
            modelId = imageModel.id,
            prompt = "make it night",
            aspectRatio = ImageAspectRatio.SQUARE,
            numOfImages = 1,
            conversationId = conversationId,
            mode = ImageGenerationMode.EDIT,
            sourceImageUrl = "file://${source.absolutePath}",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("does not support editing"))
        assertTrue(fakeProvider.received.isEmpty())
    }

    @Test
    fun editWithOversizedSourceFails() = runTest {
        val conversationId = conversationId()
        val source = File(filesManager.getChatImagesDir(conversationId), "big.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray((5 * 1024 * 1024) + 1))
        }
        val result = repository.generateForConversation(
            modelId = imageModel.id,
            prompt = "make it night",
            aspectRatio = ImageAspectRatio.SQUARE,
            numOfImages = 1,
            conversationId = conversationId,
            mode = ImageGenerationMode.EDIT,
            sourceImageUrl = "file://${source.absolutePath}",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("too large"))
        assertTrue(fakeProvider.received.isEmpty())
    }

    @Test
    fun editWithUnsupportedFormatFails() = runTest {
        val conversationId = conversationId()
        val source = File(filesManager.getChatImagesDir(conversationId), "anim.gif").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x47, 0x49, 0x46, 1, 2, 3))
        }
        val result = repository.generateForConversation(
            modelId = imageModel.id,
            prompt = "make it night",
            aspectRatio = ImageAspectRatio.SQUARE,
            numOfImages = 1,
            conversationId = conversationId,
            mode = ImageGenerationMode.EDIT,
            sourceImageUrl = "file://${source.absolutePath}",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("format"))
        assertTrue(fakeProvider.received.isEmpty())
    }

    @Test
    fun editWithSourceOutsideConversationFails() = runTest {
        // A valid png placed outside the conversation's chat_images dir must
        // be rejected (controlled reference — no arbitrary file reads).
        val outside = File(context.cacheDir, "outside.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val result = repository.generateForConversation(
            modelId = imageModel.id,
            prompt = "make it night",
            aspectRatio = ImageAspectRatio.SQUARE,
            numOfImages = 1,
            conversationId = conversationId(),
            mode = ImageGenerationMode.EDIT,
            sourceImageUrl = "file://${outside.absolutePath}",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("outside"))
        assertTrue(fakeProvider.received.isEmpty())
    }

    // ---- tool: schema + execute wiring ----

    @Test
    fun toolSchemaExposesEditParamsOnlyWhenCapable() = runTest {
        val conversationId = conversationId()
        fakeProvider.editSupported = true
        val capableTool = createImageGenTool(conversationId, settingsStore, repository)
        val capableProperties = (capableTool.parameters() as InputSchema.Obj).properties
        assertTrue(capableProperties.containsKey("mode"))
        assertTrue(capableProperties.containsKey("source_image_url"))
        assertEquals(listOf("prompt"), (capableTool.parameters() as InputSchema.Obj).required)

        fakeProvider.editSupported = false
        val createOnlyTool = createImageGenTool(conversationId, settingsStore, repository)
        val createOnlyProperties = (createOnlyTool.parameters() as InputSchema.Obj).properties
        assertFalse(createOnlyProperties.containsKey("mode"))
        assertFalse(createOnlyProperties.containsKey("source_image_url"))
        assertEquals(listOf("prompt"), (createOnlyTool.parameters() as InputSchema.Obj).required)
    }

    @Test
    fun toolCreateArgsStayCreate() = runTest {
        val tool = createImageGenTool(conversationId(), settingsStore, repository)
        val parts = tool.execute(
            buildJsonObject {
                put("prompt", "a dog")
                put("aspect_ratio", "1:1")
                put("count", 1)
            }
        )
        assertTrue(parts.isNotEmpty())
        assertEquals(ImageGenerationMode.CREATE, fakeProvider.received.single().mode)
        assertNull(fakeProvider.received.single().sourceImageUrl)
    }

    @Test
    fun toolEditArgsResolveSourceFromResolver() = runTest {
        val conversationId = conversationId()
        val source = File(filesManager.getChatImagesDir(conversationId), "source.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        }
        val tool = createImageGenTool(
            conversationId = conversationId,
            settingsStore = settingsStore,
            imageGenerationRepository = repository,
            sourceImageResolver = { "file://${source.absolutePath}" },
        )
        val parts = tool.execute(
            buildJsonObject {
                put("prompt", "make it night")
                put("mode", "edit")
            }
        )
        assertTrue(parts.isNotEmpty())
        val received = fakeProvider.received.single()
        assertEquals(ImageGenerationMode.EDIT, received.mode)
        assertEquals("file://${source.absolutePath}", received.sourceImageUrl)
        // The result image parts record the source reference.
        val imageParts = parts.filterIsInstance<app.amber.ai.ui.UIMessagePart.Image>()
        assertEquals(1, imageParts.size)
        val modeMeta = imageParts.single().metadata?.get("mode") as? kotlinx.serialization.json.JsonPrimitive
        assertEquals("EDIT", modeMeta?.contentOrNull)
        assertTrue(imageParts.single().metadata.toString().contains("edit_source_url"))
    }

    /** Fake image provider recording every received params object. */
    private class FakeImageProvider(
        var editSupported: Boolean = true,
    ) : TextModelGateway<ProviderSetting.OpenAI>, ImageModelGateway<ProviderSetting.OpenAI> {
        val received = mutableListOf<ImageGenerationParams>()

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun complete(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("not used")

        override suspend fun stream(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = error("not used")

        override suspend fun generateImage(
            providerSetting: ProviderSetting.OpenAI,
            params: ImageGenerationParams,
        ): ImageGenerationResult {
            received += params
            return ImageGenerationResult(
                items = listOf(
                    ImageGenerationItem(
                        data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
                        mimeType = "image/png",
                    )
                )
            )
        }

        override fun supportsImageEdit(providerSetting: ProviderSetting.OpenAI): Boolean = editSupported
    }
}
