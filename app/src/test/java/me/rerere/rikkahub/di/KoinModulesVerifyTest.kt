package me.rerere.rikkahub.di

import app.amber.core.ai.Generator
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRegistry
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.di.agentInfraModule
import app.amber.core.di.agentRuntimeModule
import app.amber.core.di.appModule
import app.amber.core.di.boardModule
import app.amber.core.di.chatModule
import app.amber.core.di.dataSourceModule
import app.amber.core.di.iCloudModule
import app.amber.core.di.memoryModule
import app.amber.core.di.repositoryModule
import app.amber.core.di.viewModelModule
import app.amber.core.di.webMountModule
import app.amber.core.di.workspaceModule
import app.amber.core.service.ConversationAccess
import app.amber.feature.chat.impl.ChatSessionResolver
import app.amber.feature.modelcouncil.ModelCouncilTextRunner
import app.amber.feature.subagent.SubAgentRunner
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import kotlin.reflect.KClass
import kotlin.test.assertTrue

/**
 * Catches the bug class Codex review surfaced at HEAD 26081410:
 * `class GenerationSubAgentRunner(generationHandler: Generator)`
 * constructor-injected the `Generator` interface, but Koin only
 * registered the concrete `GenerationHandler` — opening any chat page
 * threw `NoDefinitionFoundException`.
 *
 * Phase D cascade lifted several interfaces into api modules
 * (`core/ai/generation/api`, `feature/chat/api`, `feature/subagent`,
 * `feature/modelcouncil`, …), so every concrete `single { Impl(...) }`
 * with consumers asking for the interface needs an alias
 * `single<Interface> { get<Impl>() }`. This test asserts the alias
 * exists for every interface listed in [requiredAliases].
 *
 * NOTE: We tried `Module.verify()` first. It catches these alias gaps
 * (it found three — AgentEventStore, SubAgentRunner,
 * ModelCouncilTextRunner — which this branch's preceding commits
 * already fixed). But verify also reflects on factory-body bindings'
 * ctor params and false-positives on `List<Adapter>` / Boolean /
 * Google-internal types, requiring a sprawling `injectedParameters`
 * config. This targeted form has zero false-positives and is enough
 * to lock in the regression.
 */
class KoinModulesVerifyTest {

    private val loadedAtStartup: List<Module> = listOf(
        appModule,
        chatModule,
        memoryModule,
        iCloudModule,
        webMountModule,
        agentRuntimeModule,
        agentInfraModule,
        boardModule,
        workspaceModule,
        viewModelModule,
        dataSourceModule,
        repositoryModule,
    )

    /**
     * Interfaces that:
     *   - live in a core/feature api module,
     *   - are constructor-injected by at least one Koin singleton,
     *   - have a concrete impl bound via `single { Impl(...) }` in this
     *     app's modules.
     * Each must be aliased so consumers resolving by interface type work.
     */
    private val requiredAliases: List<KClass<*>> = listOf(
        Generator::class,
        ConversationAccess::class,
        ChatSessionResolver::class,
        AgentEventStore::class,
        AgentRegistry::class,
        AgentRunner::class,
        SubAgentRunner::class,
        ModelCouncilTextRunner::class,
    )

    @OptIn(KoinInternalApi::class)
    @Test
    fun `every critical interface has a Koin alias binding`() {
        val boundTypes: Set<KClass<*>> = loadedAtStartup
            .flatMap { it.mappings.values }
            .flatMap { factory ->
                listOf(factory.beanDefinition.primaryType) + factory.beanDefinition.secondaryTypes
            }
            .toSet()

        val missing = requiredAliases.filterNot { it in boundTypes }
        assertTrue(
            actual = missing.isEmpty(),
            message = "Missing Koin alias bindings: ${missing.map { it.qualifiedName }}. " +
                "Add `single<I> { get<Impl>() }` in the module that registers each Impl.",
        )
    }
}
