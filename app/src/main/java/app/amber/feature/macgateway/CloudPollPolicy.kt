package app.amber.feature.macgateway

const val CLOUD_POLL_FOREGROUND_ACTIVE_MS = 1_000L
const val CLOUD_POLL_FOREGROUND_IDLE_MS = 5_000L
const val CLOUD_POLL_BACKGROUND_ACTIVE_MS = 10_000L

/**
 * Pure polling backoff policy for Cloud Dialogue `/v2/dialogue/events`.
 *
 * - Foreground with an active task or pending submission: 1s
 * - Foreground idle: 5s
 * - Background with an active task or pending submission: 10s
 * - Background idle (no active task and no pending submission): `null` (stop polling)
 */
fun cloudPollDelayMs(
    foreground: Boolean,
    hasActiveTask: Boolean,
    hasPendingSubmission: Boolean,
): Long? = when {
    foreground && (hasActiveTask || hasPendingSubmission) -> CLOUD_POLL_FOREGROUND_ACTIVE_MS
    foreground -> CLOUD_POLL_FOREGROUND_IDLE_MS
    hasActiveTask || hasPendingSubmission -> CLOUD_POLL_BACKGROUND_ACTIVE_MS
    else -> null
}
