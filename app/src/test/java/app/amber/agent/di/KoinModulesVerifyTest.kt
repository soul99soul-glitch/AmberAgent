package app.amber.agent.di

import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.providers.ClaudeProvider
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.ai.provider.providers.openai.StoredResponseApi
import app.amber.core.ai.RunKernel
import app.amber.core.ai.DefaultRunKernel
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
import app.amber.feature.runtime.StoredResponseGateway
import app.amber.feature.subagent.SubAgentRunner
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import kotlin.reflect.KClass
import kotlin.test.assertTrue

/**
 * Verifies that constructor-injected API interfaces have Koin aliases for
 * their concrete owners. A missing `Generator` (now `RunKernel`) alias previously caused chat
 * startup to throw `NoDefinitionFoundException`.
 * Bindings use `single<Interface> { get<Impl>() }`; this test asserts the
 * alias exists for every interface listed in [requiredAliases].
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
        RunKernel::class,
        ConversationAccess::class,
        ChatSessionResolver::class,
        AgentEventStore::class,
        AgentRegistry::class,
        AgentRunner::class,
        SubAgentRunner::class,
        ModelCouncilTextRunner::class,
        StoredResponseApi::class,
        StoredResponseGateway::class,
    )

    private val requiredConcreteBindings: List<KClass<*>> = listOf(
        OpenAIProvider::class,
        GoogleProvider::class,
        ClaudeProvider::class,
        ProviderCatalog::class,
        DefaultRunKernel::class,
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
        val missingConcrete = requiredConcreteBindings.filterNot { it in boundTypes }
        assertTrue(
            actual = missing.isEmpty() && missingConcrete.isEmpty(),
            message = "Missing Koin bindings: aliases=${missing.map { it.qualifiedName }}, " +
                "concrete=${missingConcrete.map { it.qualifiedName }}.",
        )
    }
}
