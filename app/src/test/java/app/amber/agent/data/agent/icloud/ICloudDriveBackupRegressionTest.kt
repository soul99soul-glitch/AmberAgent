package app.amber.feature.icloud

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.tools.ICloudDriveTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ICloudDriveBackupRegressionTest {
    private lateinit var context: Context
    private lateinit var client: HttpClient
    private lateinit var manager: ICloudDriveManager
    private val files = linkedMapOf<String, String>()
    private var uploadedText = ""
    private var wrongReadBack = false
    private var cancelReadBack = false
    private var failTrash = false

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("amberagent_icloud_drive", Context.MODE_PRIVATE).edit().clear().commit()
        CookieManager.getInstance().setCookie("https://www.icloud.com", "X-APPLE-WEBAUTH-TOKEN=test")
        CookieManager.getInstance().setCookie("https://www.icloud.com", "X-APPLE-WEBAUTH-VALIDATE=t=test-upload-token:")
        client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = if (request.body is OutgoingContent.ByteArrayContent) {
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            } else ""
            val response = when {
                path.endsWith("/validate") -> """{"dsInfo":{"dsid":"1"},"webservices":{"drivews":{"url":"https://drive.test"},"docws":{"url":"https://doc.test"}}}"""
                path.endsWith("/requestWebAccessState") -> "{}"
                path.endsWith("/retrieveItemDetailsInFolders") -> {
                    val id = Json.parseToJsonElement(body).jsonArray.first().jsonObject.getValue("drivewsid").jsonPrimitive.content
                    val node = if (id == "FOLDER::com.apple.CloudDocs::root") directoryNode(id, "root", listOf(directoryNode("vault", "Vault", emptyList())))
                    else directoryNode("vault", "Vault", files.map { (name, text) -> fileNode(name, text.length) })
                    buildJsonArray { add(node) }.toString()
                }
                path.endsWith("/upload/web") -> """[{"document_id":"new-upload","url":"https://upload.test/content"}]"""
                request.url.host == "upload.test" -> {
                    val outgoing = request.body as OutgoingContent.WriteChannelContent
                    val channel = ByteChannel()
                    val multipart = coroutineScope {
                        launch(Dispatchers.IO) { try { outgoing.writeTo(channel) } finally { channel.close() } }
                        channel.toInputStream().readBytes().decodeToString()
                    }
                    uploadedText = Regex("AmberAgent iCloud write probe\\n[^\\r\\n]+\\n").find(multipart)?.value ?: error("probe body not found")
                    """{"singleFile":{"fileChecksum":"checksum","wrappingKey":"key","referenceChecksum":"reference","size":${uploadedText.length}}}"""
                }
                path.endsWith("/update/documents") -> {
                    val name = Json.parseToJsonElement(body).jsonObject.getValue("path").jsonObject.getValue("path").jsonPrimitive.content
                    files[name] = uploadedText
                    "{}"
                }
                path.endsWith("/download/by_id") -> {
                    val id = request.url.parameters["document_id"] ?: error("missing doc id")
                    """{"data_token":{"url":"https://download.test/${java.net.URLEncoder.encode(id, "UTF-8")}"}}"""
                }
                request.url.host == "download.test" -> {
                    if (cancelReadBack) throw CancellationException("cancelled readback")
                    val name = java.net.URLDecoder.decode(path.removePrefix("/"), "UTF-8")
                    if (wrongReadBack) "incorrect readback" else files.getValue(name)
                }
                path.endsWith("/moveItemsToTrash") -> {
                    if (failTrash) throw IllegalStateException("trash failure")
                    val id = Json.parseToJsonElement(body).jsonObject.getValue("items").jsonArray.first().jsonObject.getValue("drivewsid").jsonPrimitive.content
                    files.remove(id.removePrefix("file:"))
                    "{}"
                }
                else -> error("unexpected iCloud request ${request.url}")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        manager = ICloudDriveManager(context, ICloudDriveCookieProvider(), ICloudDriveClient(client))
        manager.setEnabled(true)
        manager.setVaultPath("Vault")
    }

    @After
    fun tearDown() { client.close() }

    @Test
    fun writeProbeNeverReplacesTheLegacyProbeNamedFile() = runBlocking {
        files[".amberagent_probe.md"] = "user owned content"
        val state = manager.runWriteProbe()
        assertEquals(ICloudDriveCapability.READ_WRITE, state.capability)
        assertEquals(mapOf(".amberagent_probe.md" to "user owned content"), files)
    }

    @Test
    fun failedProbeReadCleansOnlyTheFileItCreated() = runBlocking {
        files[".amberagent_probe.md"] = "user owned content"
        wrongReadBack = true
        val state = manager.runWriteProbe()
        assertEquals(ICloudDriveCapability.READ_ONLY, state.capability)
        assertEquals(mapOf(".amberagent_probe.md" to "user owned content"), files)
    }

    @Test
    fun cancelledProbeCleansItsOwnFileAndPropagatesCancellation() = runBlocking {
        files[".amberagent_probe.md"] = "user owned content"
        cancelReadBack = true
        val error = runCatching { manager.runWriteProbe() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(mapOf(".amberagent_probe.md" to "user owned content"), files)
    }

    @Test
    fun failedCleanupDoesNotReplaceProbeCancellation() = runBlocking {
        files[".amberagent_probe.md"] = "user owned content"
        cancelReadBack = true
        failTrash = true

        val error = runCatching { manager.runWriteProbe() }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals("cancelled readback", error?.message)
        assertTrue(error?.suppressed.orEmpty().any { it.message.orEmpty().contains("trash failure") })
        assertEquals("user owned content", files[".amberagent_probe.md"])
    }

    @Test
    fun chunkReadReturnsFirstAndFollowingSlicesOfA300KbTextFile() = runBlocking {
        files["large.md"] = "a".repeat(300_000)
        val tool = ICloudDriveTools(manager, AgentToolActivityStore()).getTools().first { it.name == "icloud_read" }
        suspend fun read(start: Int): JsonObject {
            val result = tool.execute(buildJsonObject { put("path", "large.md"); put("start_char", start) })
            return Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        }
        val first = read(0)
        assertEquals("a".repeat(65_536), first.getValue("content").jsonPrimitive.content)
        assertEquals("65536", first.getValue("next_start_char").jsonPrimitive.content)
        val second = read(65_536)
        assertEquals("a".repeat(65_536), second.getValue("content").jsonPrimitive.content)
        assertEquals("131072", second.getValue("next_start_char").jsonPrimitive.content)
    }

    @Test
    fun readStillRejectsFilesOverThe4MbHardLimit() = runBlocking {
        files["oversized.md"] = "a".repeat(4 * 1024 * 1024 + 1)
        val tool = ICloudDriveTools(manager, AgentToolActivityStore()).getTools().first { it.name == "icloud_read" }
        val error = runCatching { tool.execute(buildJsonObject { put("path", "oversized.md") }) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("read limit"))
    }

    private fun directoryNode(id: String, name: String, items: List<JsonObject>): JsonObject = buildJsonObject {
        put("drivewsid", id); put("docwsid", id); put("name", name); put("type", "FOLDER"); put("zone", "com.apple.CloudDocs"); put("etag", "etag")
        put("items", buildJsonArray { items.forEach { add(it) } })
    }
    private fun fileNode(name: String, size: Int): JsonObject = buildJsonObject {
        put("drivewsid", "file:$name"); put("docwsid", name); put("name", name); put("type", "FILE"); put("zone", "com.apple.CloudDocs"); put("etag", "etag"); put("size", size)
    }
}
