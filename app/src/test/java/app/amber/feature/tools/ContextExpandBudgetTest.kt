package app.amber.feature.tools

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.core.context.CompactSummaryNormalizer
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.repository.LightConversationEntity
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.runtime.DurableRuntimeTestBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2: `conversation_expand` had no total budget and never reported `stale`, so expanding a compact
 * could return the whole original transcript and a dead compact looked like live history.
 */
class ContextExpandBudgetTest : DurableRuntimeTestBase() {

    private fun repository(): ConversationRepository {
        val conversations = database.conversationDao()
        val summaries = object : ConversationDAO by conversations {
            override suspend fun getConversationSummaryById(id: String): LightConversationEntity? {
                val row = conversations.getConversationById(id) ?: return null
                return LightConversationEntity(
                    id = row.id,
                    assistantId = row.assistantId,
                    title = row.title,
                    isPinned = row.isPinned,
                    createAt = row.createAt,
                    updateAt = row.updateAt,
                    messageCount = database.messageNodeDao().countNodesOfConversation(id),
                    lastMessagePreview = "",
                )
            }
        }
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), AppScope())
        return ConversationRepository(
            summaries, database.messageNodeDao(), database.messageStatsDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database),
        )
    }

    private fun engine(repo: ConversationRepository): ConversationContextEngine =
        ConversationContextEngine(
            providerCatalog = app.amber.ai.provider.ProviderCatalog(
                openAIProvider = app.amber.ai.provider.providers.OpenAIProvider(okhttp3.OkHttpClient(), context),
                googleProvider = app.amber.ai.provider.providers.GoogleProvider(okhttp3.OkHttpClient(), context),
                claudeProvider = app.amber.ai.provider.providers.ClaudeProvider(okhttp3.OkHttpClient(), context),
            ),
            json = Json { ignoreUnknownKeys = true },
            contextRepository = ConversationContextRepository(
                compactDAO = database.conversationCompactDao(),
                eventDAO = database.conversationContextEventDao(),
                conversationRepository = repo,
            ),
            appScope = AppScope(),
            capabilitySnapshotBuilder = app.amber.core.context.AgentCapabilitySnapshotBuilder(),
            promptConfigRepository = AgentPromptConfigRepository(context),
            context = context,
        )

    private fun tools(
        engine: ConversationContextEngine,
        conversation: Conversation,
    ) = ConversationContextTools(
        contextEngine = engine,
        conversationProvider = { conversation },
        settingsProvider = { app.amber.core.settings.Settings() },
        modelProvider = { null },
    ).tools()

    private suspend fun expand(
        conversation: Conversation,
        engine: ConversationContextEngine,
        sourceId: String,
        radius: Int = 2,
        maxChars: Int? = null,
    ): JsonObject {
        val input = buildJsonObject {
            put("source_id", sourceId)
            put("radius", radius)
            maxChars?.let { put("max_chars", it) }
        }
        val tool = tools(engine, conversation).single { it.name == "conversation_expand" }
        val output = tool.execute(input).filterIsInstance<UIMessagePart.Text>().single().text
        return Json.parseToJsonElement(output).jsonObject
    }

    @Test
    fun compactExpansionIsCappedAndReportsTruncation() = runBlocking {
        val repo = repository()
        val messages = (1..20).map { i ->
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("m$i-${"x".repeat(900)}")))
        }
        val conversation = Conversation(assistantId = AMBER_AGENT_ID, messageNodes = messages.map(MessageNode::of))
        repo.insertConversation(conversation)
        val persisted = repo.getConversationById(conversation.id)!!

        val summary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
            summary = "compacted",
            sourceMessageIds = persisted.currentMessages.map { it.id.toString() },
            createdAt = 1_000L,
        )
        database.conversationCompactDao().insert(
            app.amber.agent.data.db.entity.ConversationCompactEntity(
                id = "compact-1",
                conversationId = conversation.id.toString(),
                summary = summary,
                level = 1,
                sourceStartIndex = 0,
                sourceEndIndex = persisted.currentMessages.lastIndex,
                sourceMessageIdsJson = Json.encodeToString(
                    persisted.currentMessages.map { it.id.toString() },
                ),
                tokenEstimate = 100,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                status = "completed",
            )
        )

        val expanded = expand(conversation, engine(repo), "compact-1", maxChars = 3_000)
        assertEquals("ok", expanded["status"]?.jsonPrimitive?.content)
        assertTrue("compact expansion must report truncation", expanded["truncated"]?.jsonPrimitive?.booleanOrNull == true)
        val returned = expanded.getValue("messages").jsonArray.size
        assertTrue("must not return all 20 source messages, got $returned", returned < 20)
        val used = expanded["budget_chars"]?.jsonPrimitive?.intOrNull ?: -1
        assertTrue("returned text must stay within the budget, got $used", used in 1..3_000)
        assertFalse("a live compact is not stale", expanded["stale"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test
    fun expansionOfADeadCompactReportsStale() = runBlocking {
        val repo = repository()
        val conversation = Conversation(
            assistantId = AMBER_AGENT_ID,
            messageNodes = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("live"))))
                .map(MessageNode::of),
        )
        repo.insertConversation(conversation)

        val summary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
            summary = "compacted",
            sourceMessageIds = listOf("gone-1", "gone-2"),
            createdAt = 1_000L,
        )
        database.conversationCompactDao().insert(
            app.amber.agent.data.db.entity.ConversationCompactEntity(
                id = "compact-dead",
                conversationId = conversation.id.toString(),
                summary = summary,
                level = 1,
                sourceStartIndex = 0,
                sourceEndIndex = 1,
                sourceMessageIdsJson = """["gone-1","gone-2"]""",
                tokenEstimate = 20,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                status = "completed",
            )
        )

        val expanded = expand(conversation, engine(repo), "compact-dead")
        assertEquals("ok", expanded["status"]?.jsonPrimitive?.content)
        assertTrue(
            "a compact whose sources are all gone must be marked stale",
            expanded["stale"]?.jsonPrimitive?.booleanOrNull == true,
        )
        assertEquals(0, expanded.getValue("messages").jsonArray.size)
    }

    @Test
    fun messageExpansionKeepsItsExistingRadiusWindow() = runBlocking {
        val repo = repository()
        val messages = (1..6).map { i ->
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("msg-$i")))
        }
        val conversation = Conversation(assistantId = AMBER_AGENT_ID, messageNodes = messages.map(MessageNode::of))
        repo.insertConversation(conversation)
        val persisted = repo.getConversationById(conversation.id)!!

        val expanded = expand(
            conversation = conversation,
            engine = engine(repo),
            sourceId = persisted.currentMessages[2].id.toString(),
            radius = 1,
        )
        val texts = expanded.getValue("messages").jsonArray.map {
            it.jsonObject.getValue("text").jsonPrimitive.content
        }
        assertEquals(listOf("msg-2", "msg-3", "msg-4"), texts)
        assertFalse(expanded["truncated"]?.jsonPrimitive?.booleanOrNull == true)
        assertFalse(expanded["stale"]?.jsonPrimitive?.booleanOrNull == true)
    }
}
