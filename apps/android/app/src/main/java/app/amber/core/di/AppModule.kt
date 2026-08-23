package app.amber.core.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import app.amber.highlight.Highlighter
import app.amber.agent.AppScope
import app.amber.core.ai.AILoggingManager
import app.amber.core.ai.tools.LocalTools
import app.amber.core.event.AppEventBus
import app.amber.core.utils.EmojiData
import app.amber.core.utils.EmojiUtils
import app.amber.core.utils.JsonInstant
import app.amber.tts.provider.TTSManager
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
        LocalTools(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    // AppScope is a `class AppScope : CoroutineScope`, so it registers under AppScope, not
    // CoroutineScope. Anything asking for a bare CoroutineScope needs this alias (previously
    // it was satisfied by a stray CoroutineScope binding in the novel module).
    single<kotlinx.coroutines.CoroutineScope> { get<AppScope>() }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
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
