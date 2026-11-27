package app.amber.feature.macgateway

import app.amber.agent.data.db.dao.ProactiveCardDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Data repository for Cloud Proactive P1-P3 cards, feedback, and management APIs.
 *
 * Reads persisted proactive cards from Room (`proactive_cards`), resolves remote signal details
 * without touching the main dialogue timeline or cursor, and proxies management mutations through
 * a generation-scoped [MacGatewayClient].
 */
class CloudProactiveRepository internal constructor(
    private val dao: ProactiveCardDao,
    private val gateway: MacGatewayRepository?,
    private val clientProviderOverride: (suspend () -> MacGatewayClient?)?,
    private val connectionProviderOverride: (() -> MacGatewayConnection?)?,
    private val generationProviderOverride: (() -> Long)?,
    private val activeConversationIdProviderOverride: (() -> String?)?,
    private val sendCloudMainMessageOverride: (suspend (conversationId: String, text: String) -> Boolean)?,
    private val receipts: ProactiveDeliveryReceipts = ProactiveDeliveryReceipts(dao, null),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        dao: ProactiveCardDao,
        gateway: MacGatewayRepository,
        receipts: ProactiveDeliveryReceipts,
    ) : this(
        dao = dao,
        gateway = gateway,
        receipts = receipts,
        clientProviderOverride = null,
        connectionProviderOverride = null,
        generationProviderOverride = null,
        activeConversationIdProviderOverride = null,
        sendCloudMainMessageOverride = null,
    )

    fun cards(gatewayId: String, deviceId: String): Flow<List<ProactiveCard>> =
        dao.observeForGateway(gatewayId, deviceId).map { entities ->
            entities.mapNotNull(ProactiveCard::fromEntityOrNull)
        }

    /**
     * Loads a proactive signal detail: local Room cache first, falling back to
     * `GET /v2/proactive/signals/{signalId}`. Returns `null` on invalid ID or 404.
     * Never writes to the dialogue timeline, Room cache, or dialogue cursor.
     */
    suspend fun loadSignal(signalId: String): ProactiveCard? =
        loadSignal(ProactiveDetailRequest(signalId, null, null))

    suspend fun loadSignal(request: ProactiveDetailRequest): ProactiveCard? {
        val trimmed = request.signalId.trim()
        if (!ProactiveContract.isValidSignalId(trimmed)) return null

        val connection = currentConnection() ?: return null
        if (!request.matches(connection)) return null
        val generation = currentGeneration()
        val local = dao.findBySignal(connection.gatewayId, connection.deviceId, trimmed)
            ?.let(ProactiveCard::fromEntityOrNull)
        if (local != null) return local.takeIf { currentGeneration() == generation && currentConnection() == connection }

        val client = resolveClient(connection, generation) ?: return null
        val event = try {
            client.proactiveSignal(trimmed)
        } catch (_: MacGatewayException.NotFound) {
            return null
        } ?: return null

        if (currentGeneration() != generation || currentConnection() != connection || event.proactive?.signalId != trimmed) return null
        return ProactiveCard.fromEventOrNull(
            event = event,
            gatewayId = connection.gatewayId,
            deviceId = connection.deviceId,
            receivedAt = nowMs(),
        )
    }

    /**
     * Submits user feedback for [signalId] and mirrors `feedback_state` locally on success.
     * HTTP 200 with `applied=false` (already applied on server) is treated as idempotent success.
     */
    suspend fun sendFeedback(
        gatewayId: String,
        signalId: String,
        action: ProactiveFeedbackAction,
        deviceId: String,
        snoozeMinutes: Int? = null,
        muteHours: Int? = null,
    ): Boolean {
        val trimmedSignalId = signalId.trim()
        if (!ProactiveContract.isValidSignalId(trimmedSignalId)) return false
        val connection = currentConnection() ?: return false
        if (connection.gatewayId != gatewayId || connection.deviceId != deviceId) return false

        val generationBefore = currentGeneration()
        val client = resolveClient(connection, generationBefore) ?: return false
        val result = try {
            client.proactiveFeedback(
                signalId = trimmedSignalId,
                action = action.wire,
                snoozeMinutes = snoozeMinutes,
                muteHours = muteHours,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (!result.ok) return false
        if (currentGeneration() != generationBefore) return false

        receipts.feedback(gatewayId, deviceId, trimmedSignalId, action.wire)
        return true
    }

    /**
     * Hands a proactive card to the normal cloud main-dialogue flow.
     * Uses `checkPrompt` when present, otherwise `"关于这条主动提醒「{title}」，请帮我处理：\n\n" + text.take(600)`.
     * Only reports `replied` feedback after the cloud main message submission is accepted.
     */
    suspend fun act(
        card: ProactiveCard,
        conversationId: String? = null,
    ): Boolean {
        val targetConversationId = conversationId?.trim()?.takeIf { it.isNotEmpty() }
            ?: currentActiveConversationId()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false
        val generation = currentGeneration()
        val connection = currentConnection() ?: return false
        if (connection.gatewayId != card.gatewayId || connection.deviceId != card.deviceId) return false
        val prompt = card.buildActPrompt()
        if (prompt.isBlank()) return false

        val accepted = try {
            submitCloudMainMessage(targetConversationId, prompt, generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (!accepted) return false

        runCatching {
            sendFeedback(
                gatewayId = card.gatewayId,
                signalId = card.signalId,
                deviceId = card.deviceId,
                action = ProactiveFeedbackAction.REPLIED,
            )
        }
        return true
    }

    suspend fun summary(): CloudProactiveSummary =
        requireClient().proactiveSummary()

    suspend fun unmute(topicKey: String): CloudProactiveRestraintSnapshot =
        requireClient().proactiveUnmute(topicKey)

    suspend fun patchRestraint(patch: CloudProactiveRestraintPatch): CloudProactiveRestraintSnapshot =
        requireClient().proactivePatchRestraint(patch)

    suspend fun setMonitorEnabled(id: String, enabled: Boolean): CloudProactiveMonitorRecord =
        requireClient().proactiveSetMonitorEnabled(id, enabled)

    suspend fun deleteMonitor(id: String) {
        requireClient().proactiveDeleteMonitor(id)
    }

    suspend fun setMonitorDeliveryMode(id: String, mode: String): CloudProactiveMonitorRecord =
        requireClient().proactiveSetMonitorDeliveryMode(id, mode)

    suspend fun createGithubReleaseMonitor(
        owner: String,
        repo: String,
        deliveryMode: String = ProactiveContract.DELIVERY_MODE_IMMEDIATE,
    ): CloudProactiveMonitorRecord =
        requireClient().proactiveCreateGithubReleaseMonitor(owner, repo, deliveryMode)

    suspend fun setWebhookEnabled(id: String, enabled: Boolean): CloudProactiveWebhookRecord =
        requireClient().proactiveSetWebhookEnabled(id, enabled)

    suspend fun deleteWebhook(id: String) {
        requireClient().proactiveDeleteWebhook(id)
    }

    suspend fun cancelWakeup(id: String) {
        requireClient().proactiveCancelWakeup(id)
    }

    private suspend fun submitCloudMainMessage(conversationId: String, text: String, generation: Long): Boolean {
        sendCloudMainMessageOverride?.let { return it(conversationId, text) }
        val repo = gateway ?: return false
        val submission = repo.sendCloudMessage(conversationId = conversationId, text = text, expectedConnectionGeneration = generation)
        return submission.error == null && !submission.eventId.isNullOrBlank()
    }

    private suspend fun currentConnection(): MacGatewayConnection? {
        connectionProviderOverride?.let { return it() }
        val repo = gateway ?: return null
        repo.awaitReady()
        return repo.state.value.connection
    }

    private fun currentGeneration(): Long {
        generationProviderOverride?.let { return it() }
        return gateway?.connectionGeneration?.value ?: 0L
    }

    private suspend fun currentActiveConversationId(): String? {
        activeConversationIdProviderOverride?.let { return it() }
        val repo = gateway ?: return null
        repo.awaitReady()
        return repo.cloudDialogueState.value.activeConversationId
    }

    private suspend fun requireClient(): MacGatewayClient {
        val connection = currentConnection() ?: throw MacGatewayException.Unreachable()
        return resolveClient(connection, currentGeneration()) ?: throw MacGatewayException.Unreachable()
    }

    private suspend fun resolveClient(connection: MacGatewayConnection, generation: Long): MacGatewayClient? {
        clientProviderOverride?.let {
            return it().takeIf { currentGeneration() == generation && currentConnection() == connection }
        }
        return gateway?.clientForPairing(connection.gatewayId, connection.deviceId, generation)
    }
}
