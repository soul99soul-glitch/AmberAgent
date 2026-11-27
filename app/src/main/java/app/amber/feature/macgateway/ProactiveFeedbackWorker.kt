package app.amber.feature.macgateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

sealed interface ProactiveFeedbackOutcome {
    data class Success(val applied: Boolean) : ProactiveFeedbackOutcome
    data class RetryableFailure(val reason: String) : ProactiveFeedbackOutcome
    data object PairingMismatch : ProactiveFeedbackOutcome
    data class TerminalFailure(val reason: String) : ProactiveFeedbackOutcome
}

/**
 * Pure, testable execution engine backing [ProactiveFeedbackWorker].
 *
 * Security & durability invariants (§3.7):
 * - Verifies `gatewayId` AND `deviceId` match the active pairing BEFORE reading any token or
 *   contacting the network; mismatches immediately abort and post a local "Action not completed"
 *   notification so an old notification can never send actions with a new pairing's token.
 * - Captures `connectionGeneration` before the call and verifies it before persisting local state.
 * - Treats `ok = true, applied = false` as idempotent success.
 * - Treats HTTP 401/404/403 and certificate mismatch as terminal failures (no retry) and notifies.
 * - Retries transient network / offline / 5xx failures up to [maxRetryAttempts], notifying only
 *   once retries are exhausted.
 */
class ProactiveFeedbackExecutor internal constructor(
    private val awaitGatewayReady: suspend () -> Unit,
    private val currentConnection: () -> MacGatewayConnection?,
    private val currentGeneration: () -> Long,
    private val submitFeedback: suspend (
        connection: MacGatewayConnection,
        generation: Long,
        signalId: String,
        action: ProactiveFeedbackAction,
        snoozeMinutes: Int?,
        muteHours: Int?,
    ) -> ProactiveFeedbackResult?,
    private val recordLocalFeedback: suspend (gatewayId: String, deviceId: String, signalId: String, feedbackState: String) -> Unit,
    private val onCompletedCancelNotification: (gatewayId: String, deviceId: String, signalId: String) -> Unit = { _, _, _ -> },
    private val onTerminalIncomplete: (signalId: String, action: ProactiveFeedbackAction) -> Unit = { _, _ -> },
    private val maxRetryAttempts: Int = DEFAULT_MAX_RETRY_ATTEMPTS,
) {
    constructor(
        gatewayRepository: MacGatewayRepository,
        receipts: ProactiveDeliveryReceipts,
        presenter: ProactiveNotificationPresenter,
        maxRetryAttempts: Int = DEFAULT_MAX_RETRY_ATTEMPTS,
    ) : this(
        awaitGatewayReady = { gatewayRepository.awaitReady() },
        currentConnection = { gatewayRepository.state.value.connection },
        currentGeneration = { gatewayRepository.connectionGeneration.value },
        submitFeedback = { connection, generation, signalId, action, snoozeMinutes, muteHours ->
            gatewayRepository.clientForPairing(connection.gatewayId, connection.deviceId, generation)?.proactiveFeedback(
                signalId = signalId,
                action = action.wire,
                snoozeMinutes = snoozeMinutes,
                muteHours = muteHours,
            )
        },
        recordLocalFeedback = receipts::feedback,
        onCompletedCancelNotification = presenter::cancelProactiveNotification,
        onTerminalIncomplete = { signalId, action ->
            presenter.showFeedbackIncompleteNotification(signalId, action)
        },
        maxRetryAttempts = maxRetryAttempts,
    )

    suspend fun execute(
        gatewayId: String?,
        deviceId: String?,
        signalId: String?,
        actionWire: String?,
        runAttemptCount: Int,
    ): ProactiveFeedbackOutcome {
        val cleanGatewayId = gatewayId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ProactiveFeedbackOutcome.TerminalFailure("missing_gateway_id")
        val cleanDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ProactiveFeedbackOutcome.TerminalFailure("missing_device_id")
        val cleanSignalId = signalId?.takeIf { ProactiveContract.isValidSignalId(it) }
            ?: return ProactiveFeedbackOutcome.TerminalFailure("invalid_signal_id")
        val action = ProactiveFeedbackAction.fromWire(actionWire)
            ?.takeIf { it == ProactiveFeedbackAction.SNOOZE || it == ProactiveFeedbackAction.MUTE_TOPIC }
            ?: return ProactiveFeedbackOutcome.TerminalFailure("unsupported_action")

        awaitGatewayReady()
        val generationBefore = currentGeneration()
        val connection = currentConnection()
        if (connection == null ||
            connection.gatewayId != cleanGatewayId ||
            connection.deviceId != cleanDeviceId
        ) {
            onTerminalIncomplete(cleanSignalId, action)
            return ProactiveFeedbackOutcome.PairingMismatch
        }

        val snoozeMinutes = if (action == ProactiveFeedbackAction.SNOOZE) DEFAULT_SNOOZE_MINUTES else null
        val muteHours = if (action == ProactiveFeedbackAction.MUTE_TOPIC) DEFAULT_MUTE_HOURS else null

        val result = try {
            submitFeedback(
                connection,
                generationBefore,
                cleanSignalId,
                action,
                snoozeMinutes,
                muteHours,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MacGatewayException.Unauthorized) {
            onTerminalIncomplete(cleanSignalId, action)
            return ProactiveFeedbackOutcome.TerminalFailure("unauthorized_401")
        } catch (_: MacGatewayException.NotFound) {
            onTerminalIncomplete(cleanSignalId, action)
            return ProactiveFeedbackOutcome.TerminalFailure("not_found_404")
        } catch (_: MacGatewayException.Forbidden) {
            onTerminalIncomplete(cleanSignalId, action)
            return ProactiveFeedbackOutcome.TerminalFailure("forbidden_403")
        } catch (_: MacGatewayException.CertificateMismatch) {
            onTerminalIncomplete(cleanSignalId, action)
            return ProactiveFeedbackOutcome.TerminalFailure("certificate_mismatch")
        } catch (retryable: Throwable) {
            val isTransient = retryable is MacGatewayException.Unreachable ||
                retryable is MacGatewayException.Server ||
                retryable is MacGatewayException.InvalidResponse ||
                retryable is IOException
            if (isTransient && runAttemptCount < maxRetryAttempts) {
                return ProactiveFeedbackOutcome.RetryableFailure(
                    retryable::class.java.simpleName.ifBlank { "transient_error" },
                )
            }
            onTerminalIncomplete(cleanSignalId, action)
            return ProactiveFeedbackOutcome.TerminalFailure(
                if (isTransient) "retry_exhausted" else "unexpected_error",
            )
        }

        if (result == null || currentGeneration() != generationBefore || currentConnection() != connection) {
            onTerminalIncomplete(cleanSignalId, action)
            return ProactiveFeedbackOutcome.PairingMismatch
        }
        if (!result.ok) {
            onTerminalIncomplete(cleanSignalId, action)
            return ProactiveFeedbackOutcome.TerminalFailure("server_rejected")
        }

        recordLocalFeedback(cleanGatewayId, cleanDeviceId, cleanSignalId, action.wire)
        onCompletedCancelNotification(cleanGatewayId, cleanDeviceId, cleanSignalId)
        return ProactiveFeedbackOutcome.Success(applied = result.applied)
    }

    companion object {
        const val DEFAULT_MAX_RETRY_ATTEMPTS = 3
        const val DEFAULT_SNOOZE_MINUTES = 60
        const val DEFAULT_MUTE_HOURS = 168
    }
}

/**
 * WorkManager worker executing proactive notification background feedback (`snooze` / `mute_topic`).
 */
class ProactiveFeedbackWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val executor = get<ProactiveFeedbackExecutor>()
        val outcome = executor.execute(
            gatewayId = inputData.getString(KEY_GATEWAY_ID),
            deviceId = inputData.getString(KEY_DEVICE_ID),
            signalId = inputData.getString(KEY_SIGNAL_ID),
            actionWire = inputData.getString(KEY_ACTION),
            runAttemptCount = runAttemptCount,
        )
        return when (outcome) {
            is ProactiveFeedbackOutcome.Success -> Result.success()
            is ProactiveFeedbackOutcome.RetryableFailure -> {
                Log.w(TAG, "proactive feedback retryable failure (attempt=$runAttemptCount): ${outcome.reason}")
                Result.retry()
            }
            ProactiveFeedbackOutcome.PairingMismatch -> {
                Log.w(TAG, "proactive feedback aborted due to pairing mismatch")
                Result.failure()
            }
            is ProactiveFeedbackOutcome.TerminalFailure -> {
                Log.w(TAG, "proactive feedback terminal failure: ${outcome.reason}")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "ProactiveFeedbackWorker"
        const val KEY_GATEWAY_ID = "gatewayId"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_SIGNAL_ID = "signalId"
        const val KEY_ACTION = "action"
    }
}
