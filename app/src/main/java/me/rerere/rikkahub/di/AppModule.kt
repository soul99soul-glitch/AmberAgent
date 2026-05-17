package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementManager
import me.rerere.rikkahub.data.agent.tools.ExternalFileTools
import me.rerere.rikkahub.data.agent.tools.FeishuOfficeTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceArtifactTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceTools
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.data.context.AgentCapabilitySnapshotBuilder
import me.rerere.rikkahub.data.context.ConversationContextEngine
import me.rerere.rikkahub.data.context.ConversationContextRepository
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.font.SlidesFontRepository
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.tts.provider.TTSManager
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
        WorkspaceManager(get())
    }

    single {
        WorkspaceTools(get(), get())
    }


    single {
        FeishuOfficeEnhancementManager(get(), get(), get(), get(), get())
    }

    single {
        FeishuOfficeTools(get(), get(), get())
    }

    single {
        ConversationContextRepository(get(), get(), get())
    }

    single {
        AgentCapabilitySnapshotBuilder(get())
    }

    single {
        ConversationContextEngine(get(), get(), get(), get(), get())
    }

    single {
        WorkspaceArtifactTools(get(), get(), get())
    }

    single {
        SlidesFontRepository(context = get(), client = get(), json = get())
    }

    single {
        ExternalFileTools(get(), get(), get())
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        AppScope()
    }

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
