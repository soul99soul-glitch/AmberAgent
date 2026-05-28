package me.rerere.rikkahub.di

import app.amber.feature.office.FeishuOfficeEnhancementManager
import me.rerere.rikkahub.data.agent.tools.ExternalFileTools
import me.rerere.rikkahub.data.agent.tools.FeishuOfficeTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceArtifactTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceTools
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.data.context.AgentCapabilitySnapshotBuilder
import me.rerere.rikkahub.data.context.ConversationContextEngine
import me.rerere.rikkahub.data.context.ConversationContextRepository
import me.rerere.rikkahub.data.font.SlidesFontRepository
import org.koin.dsl.module

/**
 * Workspace + conversation-context + Feishu Office domain Koin module.
 *
 * Bundles:
 *  - Workspace lifecycle (WorkspaceManager / WorkspaceTools /
 *    WorkspaceArtifactTools / SlidesFontRepository) — the per-conversation
 *    sandbox + slide composition support.
 *  - Conversation context engine (ConversationContextRepository /
 *    AgentCapabilitySnapshotBuilder / ConversationContextEngine) — the
 *    M1.3.3 "ContextPlanner" layer (already in main, only DI wired here).
 *  - Feishu Office enhancement (FeishuOfficeEnhancementManager /
 *    FeishuOfficeTools) — feishu-doc agent surface.
 *  - Generic external file tools (ExternalFileTools).
 *
 * Extracted from AppModule in M1.5 continuation.
 */
val workspaceModule = module {
    single { WorkspaceManager(get()) }

    single { WorkspaceTools(get(), get()) }

    single { FeishuOfficeEnhancementManager(get(), get(), get(), get(), get()) }

    single { FeishuOfficeTools(get(), get(), get()) }

    single { ConversationContextRepository(get(), get(), get()) }

    single { AgentCapabilitySnapshotBuilder(get()) }

    single { ConversationContextEngine(get(), get(), get(), get(), get(), get()) }

    single { WorkspaceArtifactTools(get(), get(), get()) }

    single { SlidesFontRepository(context = get(), client = get(), json = get()) }

    single { ExternalFileTools(get(), get(), get()) }
}
