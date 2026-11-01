package app.amber.core.service

import android.util.Log
import app.amber.agent.BuildConfig
import app.amber.core.files.FilesManager
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ConversationStore"
private const val GENERATION_CHECKPOINT_INTERVAL_MS = 10_000L
private const val INITIAL_TIMELINE_NODE_COUNT = 80
private const val TIMELINE_PREFETCH_BATCH_SIZE = 40

/** Loaded conversation windows and the durable rules that keep them consistent. */
class ConversationStore(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsAggregator,
    private val appScope: CoroutineScope,
    private val filesManager: FilesManager,
    private val pendingMessageQueue: PendingMessageQueue,
    private val errorStore: ChatErrorStore,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) : ConversationAccess {
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)
    private val generationCheckpointAt = ConcurrentHashMap<Uuid, Long>()
    private val timelineLoadMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private val conversationInitMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private val deletedConversationIds = ConcurrentHashMap.newKeySet<Uuid>()
    private val deletedConversationRestoreEpochs = ConcurrentHashMap<Uuid, Long>()

    private val restoreRegistration = restoreWriteGate?.addRestoreLifecycleListener(
        onStarted = {
            sessions.values.forEach { it.getJob()?.cancel() }
        },
        onFinished = { succeeded, dataCommitted ->
            if (succeeded || dataCommitted) {
                sessions.values.forEach { it.invalidateAfterRestore() }
                generationCheckpointAt.clear()
                deletedConversationIds.clear()
                deletedConversationRestoreEpochs.clear()
            } else {
                deletedConversationRestoreEpochs.clear()
            }
        },
    )

    internal fun sessionOrNull(conversationId: Uuid): ConversationSession? = sessions[conversationId]
    internal fun loadedSessions(): List<ConversationSession> = sessions.values.toList()
    internal fun loadedSessionIds(): Set<Uuid> = sessions.keys.toSet()

    fun cleanup() {
        restoreRegistration?.close()
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        timelineLoadMutexes.clear()
        conversationInitMutexes.clear()
    }

    override fun addError(error: Throwable, conversationId: Uuid?, title: String?) =
        errorStore.addError(error, conversationId, title)

    internal fun session(conversationId: Uuid): ConversationSession =
        sessions.computeIfAbsent(conversationId) { id ->
            val created = ConversationSession(
                id = id,
                initial = Conversation.ofId(id = id, assistantId = AMBER_AGENT_ID),
                pendingUserMessages = pendingMessageQueue.flow(id),
                scope = appScope,
                onIdle = ::removeSession,
            )
            _sessionsVersion.update { version -> version + 1 }
            Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            created
        }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (!sessions.remove(conversationId, session)) return

        timelineLoadMutexes.remove(conversationId)
        conversationInitMutexes.remove(conversationId)
        pendingMessageQueue.forgetSession(conversationId)
        session.cleanup()
        _sessionsVersion.update { version -> version + 1 }
        Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
    }

    override fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return session(conversationId).state
    }

    override fun getConversationFlowOrNull(conversationId: Uuid): StateFlow<Conversation>? {
        return sessions[conversationId]?.state
    }

    fun getTimelineLoadStateFlow(conversationId: Uuid): StateFlow<ConversationTimelineLoadState> {
        return session(conversationId).timelineLoadState
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return session(conversationId).processingStatus
    }

    fun getPendingUserMessagesFlow(conversationId: Uuid): StateFlow<List<PendingUserMessage>> {
        return session(conversationId).pendingUserMessages
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> =
        _sessionsVersion.flatMapLatest {
            val snapshot = sessions.values.toList()
            if (snapshot.isEmpty()) return@flatMapLatest flowOf(emptyMap())

            combine(
                snapshot.map { session ->
                    session.generationJob.map { job -> session.id to job }
                }
            ) { entries ->
                buildMap {
                    entries.forEach { (id, job) ->
                        if (job != null) put(id, job)
                    }
                }
            }
        }

    // ---- 初始化对话 ----

    suspend fun ensureInitialized(conversationId: Uuid) {
        val session = session(conversationId) // 确保 session 存在
        // 按 conversationId single-flight：新会话首发时 UI init 与发送路径可能并发进入，
        // 较晚返回的空状态会覆盖已写入的首条消息。
        val mutex = conversationInitMutexes.computeIfAbsent(conversationId) { Mutex() }
        mutex.withLock {
            if (session.timelineLoadState.value.initialized) {
                return@withLock
            }

            val window = conversationRepo.getConversationTailById(conversationId, INITIAL_TIMELINE_NODE_COUNT)
            if (window != null) {
                updateConversation(conversationId, window.conversation)
                session.setTimelineLoadState(
                    ConversationTimelineLoadState(
                        initialized = true,
                        totalNodeCount = window.totalNodeCount,
                        loadedNodeCount = window.conversation.messageNodes.size,
                        oldestLoadedIndex = window.oldestLoadedIndex,
                        isFullyLoaded = window.oldestLoadedIndex == 0,
                        prefetchingOlder = false,
                    )
                )
            } else {
                // 新建对话, 并添加预设消息
                val currentSettings = settingsStore.settingsFlow.filterNot { it.init }.first()
                val newConversation = Conversation.ofId(
                    id = conversationId,
                    assistantId = AMBER_AGENT_ID,
                    newConversation = true
                ).updateCurrentMessages(currentSettings.presetMessages)
                updateConversation(conversationId, newConversation)
                session.setTimelineLoadState(
                    ConversationTimelineLoadState(
                        initialized = true,
                        totalNodeCount = newConversation.messageNodes.size,
                        loadedNodeCount = newConversation.messageNodes.size,
                        oldestLoadedIndex = 0,
                        isFullyLoaded = true,
                        prefetchingOlder = false,
                    )
                )
            }
        }
    }

    internal suspend fun ensureFullConversationLoaded(conversationId: Uuid): Conversation {
        val session = session(conversationId)
        val loadState = session.timelineLoadState.value
        if (!loadState.initialized) {
            val fullConversation = conversationRepo.getConversationById(conversationId)
            if (fullConversation != null) {
                updateConversation(conversationId, fullConversation)
                session.setTimelineLoadState(
                    ConversationTimelineLoadState(
                        initialized = true,
                        totalNodeCount = fullConversation.messageNodes.size,
                        loadedNodeCount = fullConversation.messageNodes.size,
                        oldestLoadedIndex = 0,
                        isFullyLoaded = true,
                        prefetchingOlder = false,
                    )
                )
                return fullConversation
            }
            ensureInitialized(conversationId)
            return session.state.value
        }
        if (loadState.isFullyLoaded && loadState.oldestLoadedIndex == 0) {
            return session.state.value
        }

        while (!session.timelineLoadState.value.isFullyLoaded || session.timelineLoadState.value.oldestLoadedIndex > 0) {
            val loaded = loadOlderTimelineBatch(conversationId, TIMELINE_PREFETCH_BATCH_SIZE)
            if (!loaded) break
        }
        return session.state.value
    }

    suspend fun ensureConversationTimelineLoaded(conversationId: Uuid): Conversation {
        return ensureFullConversationLoaded(conversationId)
    }

    suspend fun loadOlderTimelinePage(conversationId: Uuid): Boolean {
        return loadOlderTimelineBatch(conversationId, TIMELINE_PREFETCH_BATCH_SIZE)
    }

    internal suspend fun readOlderTimelinePageForPrewarm(conversationId: Uuid): List<MessageNode> {
        val loadState = sessions[conversationId]?.timelineLoadState?.value ?: return emptyList()
        if (!loadState.initialized || loadState.isFullyLoaded || loadState.oldestLoadedIndex <= 0) {
            return emptyList()
        }
        val offset = (loadState.oldestLoadedIndex - TIMELINE_PREFETCH_BATCH_SIZE).coerceAtLeast(0)
        return conversationRepo.getConversationNodePage(
            conversationId = conversationId,
            offset = offset,
            limit = loadState.oldestLoadedIndex - offset,
        )
    }

    private suspend fun loadOlderTimelineBatch(conversationId: Uuid, batchSize: Int): Boolean {
        val mutex = timelineLoadMutexes.computeIfAbsent(conversationId) { Mutex() }
        return mutex.withLock {
            val session = sessions[conversationId] ?: return@withLock false
            val loadState = session.timelineLoadState.value
            if (!loadState.initialized) {
                session.setTimelineLoadState(loadState.copy(prefetchingOlder = false))
                return@withLock false
            }
            if (loadState.oldestLoadedIndex <= 0) {
                session.setTimelineLoadState(loadState.copy(prefetchingOlder = false, isFullyLoaded = true))
                return@withLock false
            }

            val nextOffset = (loadState.oldestLoadedIndex - batchSize).coerceAtLeast(0)
            val nextLimit = loadState.oldestLoadedIndex - nextOffset
            session.setTimelineLoadState(loadState.copy(prefetchingOlder = true))

            val olderNodes = try {
                conversationRepo.getConversationNodePage(
                    conversationId = conversationId,
                    offset = nextOffset,
                    limit = nextLimit,
                )
            } catch (error: Throwable) {
                // A cancelled caller (e.g. a UI effect restarting) must not leave the flag set:
                // the timeline stops auto-loading older pages while prefetchingOlder is true.
                session.setTimelineLoadState(session.timelineLoadState.value.copy(prefetchingOlder = false))
                throw error
            }
            var mergedNodeCount = session.state.value.messageNodes.size
            session.state.update { latestConversation ->
                val existingNodeIds = latestConversation.messageNodes.mapTo(mutableSetOf()) { it.id }
                val mergedNodes = olderNodes.filterNot { it.id in existingNodeIds } + latestConversation.messageNodes
                mergedNodeCount = mergedNodes.size
                latestConversation.copy(messageNodes = mergedNodes)
            }

            val isFullyLoaded = nextOffset == 0 || olderNodes.isEmpty()
            session.setTimelineLoadState(
                loadState.copy(
                    initialized = true,
                    loadedNodeCount = mergedNodeCount,
                    oldestLoadedIndex = nextOffset,
                    isFullyLoaded = isFullyLoaded,
                    prefetchingOlder = false,
                )
            )
            !isFullyLoaded
        }
    }

    internal suspend fun loadFullConversationForGeneration(conversationId: Uuid): Conversation {
        val windowConversation = getConversationFlow(conversationId).value
        val loadState = session(conversationId).timelineLoadState.value
        if (loadState.initialized && loadState.isFullyLoaded && loadState.oldestLoadedIndex == 0) {
            return windowConversation
        }
        val fullConversation = conversationRepo.getConversationById(conversationId) ?: return windowConversation
        return mergeConversationWindowIntoFull(
            fullConversation = fullConversation,
            windowConversation = windowConversation,
        )
    }

    internal fun replaceSessionWithFullConversation(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        val session = session(conversationId)
        updateConversation(conversationId, conversation, checkDeletedFiles = false)
        session.setTimelineLoadState(
            ConversationTimelineLoadState(
                initialized = true,
                totalNodeCount = conversation.messageNodes.size,
                loadedNodeCount = conversation.messageNodes.size,
                oldestLoadedIndex = 0,
                isFullyLoaded = true,
                prefetchingOlder = false,
            )
        )
    }

    internal suspend fun appendUserNode(conversationId: Uuid, userNode: MessageNode) {
        val currentConversation = session(conversationId).state.value
        val newConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes + userNode,
        )
        updateConversation(conversationId, newConversation)
        val perfEnabled = BuildConfig.DEBUG && runCatching { Log.isLoggable("AmberChatPerf", Log.DEBUG) }.getOrDefault(false)
        val persistStartedAt = if (perfEnabled) System.nanoTime() else 0L
        persistConversationWindow(conversationId, newConversation, indexFts = true)
        if (perfEnabled) {
            Log.d("AmberChatPerf", "appendUserMessage.persist elapsedMs=${(System.nanoTime() - persistStartedAt) / 1_000_000.0}")
        }
    }

    suspend fun appendCloudDialogueNode(
        conversationId: Uuid,
        node: MessageNode,
        titleHint: String? = null,
    ) {
        ensureInitialized(conversationId)
        val currentConversation = session(conversationId).state.value
        val resolvedTitle = if (currentConversation.title.isBlank() && !titleHint.isNullOrBlank()) {
            titleHint.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(48).orEmpty()
        } else {
            currentConversation.title
        }
        val newConversation = currentConversation.copy(
            title = resolvedTitle,
            messageNodes = currentConversation.messageNodes + node,
            updateAt = Instant.now(),
        )
        updateConversation(conversationId, newConversation)
        persistConversationWindow(conversationId, newConversation, indexFts = true)
    }

    internal suspend fun checkpointConversation(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ): Boolean {
        val now = System.currentTimeMillis()
        val last = generationCheckpointAt[conversationId] ?: 0L
        if (!force && now - last < GENERATION_CHECKPOINT_INTERVAL_MS) return false
        generationCheckpointAt[conversationId] = now
        val startedAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        return runCatching {
            persistConversationWindow(
                conversationId = conversationId,
                conversation = conversation,
                indexFts = force,
            )
            if (BuildConfig.DEBUG) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                Log.d(
                    "AmberChatPerf",
                    "checkpointConversation force=$force nodes=${conversation.messageNodes.size} " +
                        "elapsedMs=${String.format(Locale.US, "%.2f", elapsedMs)}",
                )
            }
            true
        }.onFailure { error ->
            Log.w(TAG, "checkpointConversation failed for $conversationId", error)
        }.getOrDefault(false)
    }

    internal suspend fun persistConversationWindow(
        conversationId: Uuid,
        conversation: Conversation,
        indexFts: Boolean,
    ) {
        withConversationWrite {
            persistConversationWindowInternal(conversationId, conversation, indexFts)
        }
    }

    private suspend fun persistConversationWindowInternal(
        conversationId: Uuid,
        conversation: Conversation,
        indexFts: Boolean,
    ) {
        if (conversationId in deletedConversationIds) return
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return
        }
        if (!exists) {
            conversationRepo.insertConversation(conversation)
            return
        }
        val loadState = session(conversationId).timelineLoadState.value
        if (!loadState.initialized) {
            saveConversationInternal(conversationId, conversation)
            return
        }
        // A window checkpoint may have queued before a metadata command. It owns
        // message content; the current session owns the latest title and suggestions.
        val current = getConversationFlow(conversationId).value
        val checkpoint = conversation.copy(
            title = current.title,
            chatSuggestions = current.chatSuggestions,
            updateAt = maxOf(conversation.updateAt, current.updateAt),
        )
        conversationRepo.upsertConversationWindow(
            conversation = checkpoint,
            firstNodeIndex = loadState.oldestLoadedIndex,
            indexFts = indexFts,
        )
    }

    private suspend fun <T> withConversationWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) {
            block()
        } else {
            gate.withCurrentWriterOrCancel(block)
        }
    }

    override fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
        checkDeletedFiles: Boolean,
    ) {
        if (conversation.id != conversationId) return
        val session = session(conversationId)
        // This API only projects the conversation into the in-memory session.
        // Physical attachment cleanup belongs to saveConversationInternal,
        // where the suspend caller's restore epoch is available. Keeping the
        // old compatibility flag avoids breaking ConversationAccess callers,
        // while preventing a synchronous update from scheduling cleanup under
        // a newer epoch after an older generation resumes.
        session.state.value = conversation
        session.refreshTimelineNodeCount()
    }

    override fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val session = session(conversationId)
        session.state.update { current ->
            update(current).takeIf { it.id == conversationId } ?: current
        }
        session.refreshTimelineNodeCount()
    }

    private fun checkFilesDelete(
        newConversation: Conversation,
        oldConversation: Conversation,
        expectedRestoreEpoch: Long? = null,
    ) {
        val retainedFiles = newConversation.files.toHashSet()
        val removedFiles = oldConversation.files.filterNot(retainedFiles::contains)
        if (removedFiles.isEmpty()) return

        filesManager.deleteChatFiles(removedFiles, expectedRestoreEpoch)
        Log.w(TAG, "checkFilesDelete: $removedFiles")
    }

    override suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        withConversationWrite {
            saveConversationInternal(conversationId, conversation)
        }
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) =
        updateMetadata(conversationId, title = title)

    suspend fun updateSuggestions(conversationId: Uuid, suggestions: List<String>) =
        updateMetadata(conversationId, suggestions = suggestions)

    private suspend fun updateMetadata(conversationId: Uuid, title: String? = null, suggestions: List<String>? = null) {
        withConversationWrite {
            if (conversationId in deletedConversationIds) return@withConversationWrite
            val updatedAt = Instant.now()
            conversationRepo.updateConversationMetadata(
                conversationId, title = title, chatSuggestions = suggestions, updateAt = updatedAt,
            )
            // Streaming can publish a newer tail while Room commits metadata.
            // Patch the latest projection instead of restoring a pre-write snapshot.
            updateConversationState(conversationId) { current ->
                current.copy(
                    title = title ?: current.title,
                    chatSuggestions = suggestions ?: current.chatSuggestions,
                    updateAt = maxOf(current.updateAt, updatedAt),
                )
            }
        }
    }

    suspend fun togglePinnedStatus(conversationId: Uuid) {
        withConversationWrite {
            conversationRepo.togglePinStatus(conversationId)
        }
    }

    private suspend fun saveConversationInternal(conversationId: Uuid, conversation: Conversation) {
        if (conversationId in deletedConversationIds) return
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val loadState = session(conversationId).timelineLoadState.value
        val updatedConversation = if (exists && (!loadState.initialized || !loadState.isFullyLoaded || loadState.oldestLoadedIndex > 0)) {
            mergeConversationWindowIntoFull(
                fullConversation = ensureFullConversationLoaded(conversationId),
                windowConversation = conversation,
            )
        } else {
            conversation.copy()
        }
        val expectedRestoreEpoch = captureRestoreEpoch()
        val previousConversation = getConversationFlow(conversationId).value
        updateConversation(conversationId, updatedConversation, checkDeletedFiles = false)
        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
        // Removing old attachments is only safe after the new references commit.
        checkFilesDelete(updatedConversation, previousConversation, expectedRestoreEpoch)
    }

    private fun mergeConversationWindowIntoFull(
        fullConversation: Conversation,
        windowConversation: Conversation,
    ): Conversation {
        val windowNodesById = windowConversation.messageNodes.associateBy { it.id }
        val mergedNodes = fullConversation.messageNodes
            .map { node -> windowNodesById[node.id] ?: node }
            .toMutableList()
        val fullNodeIds = fullConversation.messageNodes.mapTo(mutableSetOf()) { it.id }
        windowConversation.messageNodes
            .filterNot { it.id in fullNodeIds }
            .forEach { mergedNodes.add(it) }

        return fullConversation.copy(
            assistantId = windowConversation.assistantId,
            title = windowConversation.title,
            chatSuggestions = windowConversation.chatSuggestions,
            isPinned = windowConversation.isPinned,
            autoApproveToolCalls = windowConversation.autoApproveToolCalls,
            updateAt = windowConversation.updateAt,
            messageNodes = mergedNodes,
        )
    }

    fun markConversationRestored(conversationId: Uuid) {
        deletedConversationIds.remove(conversationId)
        deletedConversationRestoreEpochs.remove(conversationId)
    }

    suspend fun purgeDeletedConversation(conversation: Conversation) {
        val invocationEpoch = captureRestoreEpoch()
        // A successful/partial restore clears the tombstone. A late snackbar
        // callback must therefore be a no-op rather than treating its missing
        // epoch as a new user write and deleting restored attachments.
        if (conversation.id !in deletedConversationIds) return
        val expectedRestoreEpoch = deletedConversationRestoreEpochs[conversation.id] ?: invocationEpoch
        withRestoreEpoch(expectedRestoreEpoch) {
            conversationRepo.cleanupDeletedConversation(
                conversation = conversation,
                expectedRestoreEpoch = expectedRestoreEpoch,
            )
        }
        if (expectedRestoreEpoch == null) {
            deletedConversationRestoreEpochs.remove(conversation.id)
        } else {
            deletedConversationRestoreEpochs.remove(conversation.id, expectedRestoreEpoch)
        }
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): StateFlow<Job?> = session(conversationId).generationJob
    fun addConversationReference(conversationId: Uuid): Int = session(conversationId).acquire()
    fun removeConversationReference(conversationId: Uuid): Int? = sessions[conversationId]?.release()

    internal fun clearCheckpoint(conversationId: Uuid) {
        generationCheckpointAt.remove(conversationId)
    }

    internal suspend fun persistFinalWindow(
        conversationId: Uuid,
        checkpointed: Boolean,
        checkpointConversation: Conversation,
        checkpointLoadState: ConversationTimelineLoadState,
    ): Conversation = withConversationWrite {
        val finalConversation = getConversationFlow(conversationId).value
        if (!checkpointed || finalConversation !== checkpointConversation ||
            session(conversationId).timelineLoadState.value != checkpointLoadState
        ) {
            persistConversationWindowInternal(conversationId, finalConversation, indexFts = true)
        }
        finalConversation
    }

    internal suspend fun conversationForGeneration(conversationId: Uuid): Conversation {
        val perfEnabled = BuildConfig.DEBUG && runCatching { Log.isLoggable("AmberChatPerf", Log.DEBUG) }.getOrDefault(false)
        val startedAt = if (perfEnabled) System.nanoTime() else 0L
        val full = loadFullConversationForGeneration(conversationId)
        if (perfEnabled) {
            Log.d("AmberChatPerf", "conversationForGeneration.load nodes=${full.messageNodes.size} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000.0}")
        }
        val sanitized = sanitizeInvalidMessagesForGeneration(full)
        if (sanitized != full) {
            withConversationWrite {
                conversationRepo.updateConversation(sanitized)
                replaceSessionWithFullConversation(conversationId, sanitized)
            }
        }
        return sanitized
    }

    internal fun markDeleted(conversationId: Uuid, expectedRestoreEpoch: Long?) {
        deletedConversationIds.add(conversationId)
        expectedRestoreEpoch?.let { deletedConversationRestoreEpochs[conversationId] = it }
    }

    internal suspend fun deletePersisted(conversation: Conversation, deferCleanup: Boolean, expectedRestoreEpoch: Long?) {
        withRestoreEpoch(expectedRestoreEpoch) {
            conversationRepo.deleteConversation(conversation, deferCleanup = deferCleanup)
        }
        if (!deferCleanup) deletedConversationRestoreEpochs.remove(conversation.id)
    }

    internal suspend fun rollbackDeletionIfPresent(conversationId: Uuid) {
        if (conversationRepo.existsConversationById(conversationId)) markConversationRestored(conversationId)
    }

    internal suspend fun existsConversation(conversationId: Uuid): Boolean =
        conversationRepo.existsConversationById(conversationId)

    internal suspend fun listConversations(): List<Conversation> = conversationRepo.getConversations().first()

    internal suspend fun deleteAllPersisted(conversationIds: List<Uuid>, expectedRestoreEpoch: Long?) {
        withRestoreEpoch(expectedRestoreEpoch) { conversationRepo.deleteAllConversations() }
        conversationIds.forEach(deletedConversationRestoreEpochs::remove)
    }

    private suspend fun captureRestoreEpoch(): Long? {
        val gate = restoreWriteGate ?: return null
        return coroutineContext[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
    }

    private suspend fun <T> withRestoreEpoch(expectedRestoreEpoch: Long?, block: suspend () -> T): T {
        val gate = restoreWriteGate ?: return block()
        val epoch = expectedRestoreEpoch ?: coroutineContext[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
        return withContext(SyncRestoreWriteEpoch(epoch)) { block() }
    }
}
