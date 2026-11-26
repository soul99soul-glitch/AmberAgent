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
import app.amber.feature.macgateway.CloudDialogueCoordinator
import app.amber.feature.macgateway.CloudProactiveRepository
import app.amber.feature.macgateway.ConversationStoreCloudDialogueSink
import app.amber.feature.macgateway.MacGatewayRepository
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
        val proactiveCardDao = get<ProactiveCardDao>()
        CloudDialogueCoordinator.setDefaultProactiveCardStore { entity ->
            proactiveCardDao.insert(entity)
        }
        MacGatewayRepository(
            context = get(),
            appScope = get<AppScope>(),
            timelineSink = ConversationStoreCloudDialogueSink(get()),
            agentSoulStore = get<AgentSoulStore>(),
            secretStore = get<SecretStore>(),
        ).also { repo ->
            CloudDialogueCoordinator.setDefaultConnectionGenerationProvider {
                repo.connectionGeneration.value
            }
        }
    }

    single {
        ProactiveDeepLinkInbox()
    }

    single {
        CloudProactiveRepository(
            dao = get<ProactiveCardDao>(),
            gateway = get<MacGatewayRepository>(),
            secretStore = get<SecretStore>(),
        )
    }

    single {
        ProactiveNotificationPresenter(
            context = get<Context>(),
            gatewayRepository = get<MacGatewayRepository>(),
            proactiveRepository = get<CloudProactiveRepository>(),
        )
    }

    single {
        ProactiveFeedbackExecutor(
            gatewayRepository = get<MacGatewayRepository>(),
            proactiveCardDao = get<ProactiveCardDao>(),
            secretStore = get<SecretStore>(),
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
