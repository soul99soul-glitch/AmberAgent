package app.amber.core.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import app.amber.highlight.Highlighter
import app.amber.agent.AppScope
import app.amber.core.ai.AILoggingManager
import app.amber.core.event.AppEventBus
import app.amber.core.utils.EmojiData
import app.amber.core.utils.EmojiUtils
import app.amber.core.utils.JsonInstant
import android.content.Context
import app.amber.agent.data.db.dao.ProactiveCardDao
import app.amber.core.settings.secret.SecretStore
import app.amber.feature.macgateway.CloudProactiveRepository
import app.amber.feature.macgateway.ConversationStoreCloudDialogueSink
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.macgateway.ProactiveDeliveryReceipts
import app.amber.feature.macgateway.ProactiveDeepLinkInbox
import app.amber.feature.macgateway.ProactiveFeedbackExecutor
import app.amber.feature.macgateway.ProactiveNotificationPresenter
import app.amber.feature.prompts.AgentSoulStore
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        AppEventBus()
    }


    single {
        AppScope()
    }

    // AppScope is a `class AppScope : CoroutineScope`, so it registers under AppScope, not
    // CoroutineScope. Anything asking for a bare CoroutineScope needs this alias (previously
    // it was satisfied by a stray CoroutineScope binding in the novel module).
    single<kotlinx.coroutines.CoroutineScope> { get<AppScope>() }

    single {
        AgentSoulStore(context = get<Context>())
    }

    single {
        ProactiveDeliveryReceipts(
            dao = get<ProactiveCardDao>(),
            pending = get<Context>().getSharedPreferences("pending_proactive_receipts", Context.MODE_PRIVATE),
        )
    }

    single {
        val receipts = get<ProactiveDeliveryReceipts>()
        MacGatewayRepository(
            context = get(),
            appScope = get<AppScope>(),
            timelineSink = ConversationStoreCloudDialogueSink(get()),
            agentSoulStore = get<AgentSoulStore>(),
            secretStore = get<SecretStore>(),
            onProactiveCard = receipts::insertEvent,
        )
    }

    single {
        ProactiveDeepLinkInbox()
    }

    single {
        CloudProactiveRepository(
            dao = get<ProactiveCardDao>(),
            gateway = get<MacGatewayRepository>(),
            receipts = get(),
        )
    }

    single {
        ProactiveNotificationPresenter(
            context = get<Context>(),
            gatewayRepository = get<MacGatewayRepository>(),
            receipts = get<ProactiveDeliveryReceipts>(),
        )
    }

    single {
        ProactiveFeedbackExecutor(
            gatewayRepository = get<MacGatewayRepository>(),
            receipts = get<ProactiveDeliveryReceipts>(),
            presenter = get<ProactiveNotificationPresenter>(),
        )
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.remoteConfig
    }

    single {
        Firebase.analytics
    }

    single {
        AILoggingManager()
    }


}
