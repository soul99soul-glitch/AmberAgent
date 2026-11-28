package app.amber.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.navigation3.runtime.NavKey
import app.amber.core.automation.display.VirtualDisplayRunRegistry
import app.amber.core.automation.display.VirtualDisplayRunNotifier
import app.amber.feature.macgateway.ProactiveNotificationPresenter
import app.amber.feature.macgateway.ProactiveContract
import app.amber.feature.macgateway.ProactiveDeepLinkInbox
import app.amber.feature.runtime.RunTerminal
import app.amber.core.utils.base64Encode
import kotlin.uuid.Uuid

internal const val EXTRA_OPEN_PERSONAL_AGENT = "openPersonalAgent"
private val PROACTIVE_DEEP_LINK_REGEX = Regex("^amber://proactive/([A-Za-z0-9_-]{1,48})$")

/**
 * Strictly parses `amber://proactive/<signalId>`.
 * Rejects any multi-segment path, trailing slash, query string, fragment, userinfo, port,
 * percent-encoded traversal, or illegal `signalId` character outside `^[A-Za-z0-9_-]{1,48}$`.
 */
internal fun proactiveDeepLinkSignalIdFrom(rawUri: String?): String? {
    if (rawUri == null) return null
    val match = PROACTIVE_DEEP_LINK_REGEX.matchEntire(rawUri) ?: return null
    val signalId = match.groupValues[1]
    return signalId.takeIf { ProactiveContract.isValidSignalId(it) }
}

internal fun proactiveDeepLinkSignalIdFrom(uri: Uri?): String? =
    proactiveDeepLinkSignalIdFrom(uri?.toString())

internal fun proactiveDeepLinkSignalIdFrom(intent: Intent?): String? =
    proactiveDeepLinkSignalIdFrom(intent?.data)

/**
 * One-shot consumer for `amber://proactive/<signalId>`: validates, clears `intent.data`,
 * and submits the signal ID to [ProactiveDeepLinkInbox] exactly once.
 */
internal fun consumeProactiveDeepLink(
    intent: Intent?,
    inbox: ProactiveDeepLinkInbox,
    alreadyConsumed: Boolean = false,
): String? {
    if (alreadyConsumed || intent == null) return null
    val signalId = proactiveDeepLinkSignalIdFrom(intent) ?: return null
    intent.data = null
    inbox.submit(signalId, intent.getStringExtra(ProactiveNotificationPresenter.EXTRA_GATEWAY_ID),
        intent.getStringExtra(ProactiveNotificationPresenter.EXTRA_DEVICE_ID))
    return signalId
}

/** Ensures the navigation stack resolves to `[Screen.PersonalAgent]`. */
internal fun applyProactiveDeepLinkToBackStack(backStack: MutableList<NavKey>) {
    backStack.clear()
    backStack.add(Screen.PersonalAgent)
}

private const val ACTION_PHONE_USE_DISPLAY_SETTINGS = "app.amber.action.PHONE_USE_DISPLAY_SETTINGS"

internal fun phoneUseDisplaySettingsIntent(context: Context): Intent =
    Intent(context, RouteActivity::class.java)
        .setAction(ACTION_PHONE_USE_DISPLAY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun phoneUseScreenFromIntent(intent: Intent?): Screen? {
    if (intent?.action == ACTION_PHONE_USE_DISPLAY_SETTINGS) return Screen.SettingPhoneUseDisplay
    if (intent?.action != VirtualDisplayRunNotifier.ACTION_VIEW_VIRTUAL_RUN) return null
    val run = VirtualDisplayRunRegistry.forIntent(intent) ?: return null
    return Screen.PhoneUseLive(runId = run.runId)
}

internal fun openPersonalAgentFromIntent(intent: Intent?): Boolean =
    intent?.getBooleanExtra(EXTRA_OPEN_PERSONAL_AGENT, false) == true

internal fun taskSessionScreenFromIntent(intent: Intent): Screen.Chat? {
    // Shortcut intents carry their own prompt extra and are consumed by
    // DynamicShortcutPublisher.screenFromIntent - never route them twice.
    if (intent.hasExtra(app.amber.feature.tools.DynamicShortcutPublisher.EXTRA_SHORTCUT_KIND)) return null
    val prompt = intent.getStringExtra(RouteActivity.EXTRA_OPEN_CHAT_PROMPT)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return Screen.Chat(
        id = Uuid.random().toString(),
        text = prompt.base64Encode(),
    )
}

/** Parsed identity shared by cold-start and warm notification delivery. */
internal data class NotificationDeepLink(
    val conversationId: String,
    val runId: String?,
)

internal fun notificationDeepLinkFrom(
    conversationId: String?,
    runId: String?,
): NotificationDeepLink? {
    val normalizedConversationId = conversationId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw -> runCatching { Uuid.parse(raw).toString() }.getOrNull() }
        ?: return null
    return NotificationDeepLink(
        conversationId = normalizedConversationId,
        runId = runId?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun notificationDeepLinkFrom(intent: Intent): NotificationDeepLink? =
    notificationDeepLinkFrom(
        conversationId = intent.getStringExtra("conversationId"),
        runId = intent.getStringExtra("runId"),
    )

/** Run ownership/freshness predicate kept pure for the notification canary. */
internal fun notificationRunIsOwned(link: NotificationDeepLink, run: RunTerminal?): Boolean =
    run != null && run.conversationId == link.conversationId && !run.state.isTerminal
