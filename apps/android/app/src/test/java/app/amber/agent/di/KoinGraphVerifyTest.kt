package app.amber.agent.di

import app.amber.core.di.agentInfraModule
import app.amber.core.di.agentRuntimeModule
import app.amber.core.di.appModule
import app.amber.core.di.boardModule
import app.amber.core.di.chatModule
import app.amber.core.di.dataSourceModule
import app.amber.core.di.iCloudModule
import app.amber.core.di.memoryModule
import app.amber.core.di.novelModule
import app.amber.core.di.repositoryModule
import app.amber.core.di.viewModelModule
import app.amber.core.di.webMountModule
import app.amber.core.di.workspaceModule
import org.junit.Test
import kotlin.test.assertTrue
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify

/**
 * Full-graph static verification of every Koin definition loaded at startup.
 *
 * KoinModulesVerifyTest only locks in a curated alias list; it cannot catch a
 * freshly added `get<T>()` without a definition (two of those shipped to a
 * device and crashed at launch: `get<List<ContinueCandidateSource>>` and the
 * missing `ContinueCandidateDismissDAO` binding — unit tests never start the
 * Koin graph, so nothing failed until first ViewModel resolution).
 *
 * `verifyAll` reflects over each factory's constructor dependencies and fails
 * on any type with no definition. Types that are legitimately provided by
 * callers (primitives, collections, framework types passed as parameters
 * rather than resolved from the graph) are whitelisted in [extraTypes] — the
 * list below is the curated result of running this test and classifying each
 * report; DI-owned types must NOT be added here, they get real definitions.
 */
class KoinGraphVerifyTest {

    private val loadedAtStartup: List<Module> = listOf(
        appModule,
        chatModule,
        memoryModule,
        novelModule,
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

    @OptIn(KoinInternalApi::class)
    @Test
    fun `every definition's dependencies resolve in the startup graph`() {
        // Koin 4 的单模块 verify 只看"自身 + includes"，跨模块引用（如 appModule
        // 的 LocalTools 依赖 workspaceModule 的 WorkspaceTools）会被误报缺失。
        // 用一个聚合模块 includes 全部启动模块，让校验在完整图上进行。
        val aggregate = module {
            includes(loadedAtStartup)
        }
        // 防空跑：聚合链里必须真的有定义被校验（当前 13 个启动模块共 300+ 条）。
        val definitionCount = loadedAtStartup.sumOf { it.mappings.size }
        assertTrue(definitionCount > 250, "启动模块定义数异常偏少: $definitionCount")
        // 工厂函数构建的定义（single<T> { Factory.build(get()) }）无法从构造器
        // 推断真实依赖，按定义逐个豁免其构造器参数。
        val injectedParams = injectedParameters(
            definition<app.amber.core.utils.EmojiData>(List::class),
            // FirebaseAnalytics.getInstance() 工厂构建，构造器是混淆内部类，与图无关
            definition<com.google.firebase.analytics.FirebaseAnalytics>(
                com.google.android.gms.internal.measurement.zzez::class,
            ),
            // 仓库根目录 File 由各自工厂（defaultRoot(...)）算出传入，不经图解析
            definition<app.amber.feature.novel.persistence.NovelFileProjectRepository>(
                java.io.File::class,
            ),
            definition<app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository>(
                java.io.File::class,
            ),
            // runtime 在工厂里构造（NovelWorkspaceRuntime(get())）后传入协调器
            definition<app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteCoordinator>(
                app.amber.feature.novel.workspace.NovelWorkspaceRuntime::class,
            ),
            // adapters 在工厂里用 listOf(get(), ...) 字面组装后传入
            definition<app.amber.feature.webmount.core.WebMountManager>(List::class),
            // settingsFlow 取自 get<SettingsAggregator>().settingsFlow 属性表达式
            definition<app.amber.feature.modelcouncil.CouncilRoomManager>(
                kotlinx.coroutines.flow.StateFlow::class,
            ),
            // collectors 在工厂里 listOf(get(), ...) 字面组装后传入
            definition<app.amber.feature.board.aggregator.SignalAggregator>(List::class),
            // QuickJsCellEngine() 在工厂内直接构造后传入
            definition<app.amber.feature.jscell.JsCellRuntime>(
                app.amber.feature.jscell.JsCellEngine::class,
            ),
            // createAndroidSecretStore(context) 工厂构建，backend/cipher 由工厂内部创建
            definition<app.amber.core.settings.secret.SecretStore>(
                app.amber.core.settings.secret.SecretStoreBackend::class,
                javax.crypto.Cipher::class,
                app.amber.core.settings.secret.SecretCipher::class,
            ),
        )
        val extraTypesList = listOf(
                List::class,
                Set::class,
                Map::class,
                Boolean::class,
                Int::class,
                Long::class,
                Float::class,
                Double::class,
                String::class,
                android.content.Context::class,
                android.app.Application::class,
                androidx.lifecycle.SavedStateHandle::class,
                androidx.datastore.core.DataStore::class,
                io.ktor.client.engine.HttpClientEngine::class,
                kotlin.Function0::class,
                kotlin.Function1::class,
                kotlin.Function2::class,
                kotlin.Function3::class,
        )
        aggregate.verify(extraTypesList, injectedParams)
    }
}
