package app.amber.ai.provider

import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3+ 风险拦截（Surface B / ProviderSetting sealed dispatch 完整性）：
 *
 * `ProviderSetting` 是 sealed class，全仓库 ~20 个 Kotlin when-dispatch 点靠
 * 编译器强制 exhaustive（赋值形 when）来保证「新增子类必失败编译」。但有两类
 * 点绕过了编译器保护：
 *
 *   (1) Swift 侧 `if let ... as? ProviderSetting.OpenAI { ... }` 早返回链
 *       —— 见 CouncilRunner.swift:574-587 的 dispatchCouncilStream()，只处理
 *       OpenAI/Claude，Google 落到 onError("暂不支持")。新增第 4 个子类会
 *       静默落到错误分支，编译器无能为力（Swift 不做 sealed exhaustive 检查）。
 *   (2) 任何用 `when (x) { is A -> ..; is B -> .. }` 作为 *语句*（非表达式）
 *       且没 else 的点，Kotlin 也不强制 exhaustive。
 *
 * 本测试用 kotlinx.serialization 生成的 sealed descriptor 读取真实子类集合。
 * 一旦有人新增第 4 个 provider（如独立 Mistral/DeepSeek 子类），descriptor
 * 的 serialName 集合会变化，强制 reviewer 去 Swift 侧补齐 dispatchCouncilStream
 * 等绕过编译器的点。
 */
class ProviderSettingSealedExhaustivenessTest {

    /**
     * 显式枚举全部 sealed 子类。新增 provider 必须把新子类加进这个 list——
     * 这一步就是「强制审查 Swift dispatch 点」的触发点。更新此处时务必同步审查：
     *   - CouncilRunner.dispatchCouncilStream (CouncilRunner.swift:566-588)
     *   - ChatProviderConfiguration (ChatProviderConfiguration.swift:88-100)
     *   - ProviderRegistryStore (ProviderRegistryStore.swift:305-310)
     */
    private val ALL_SEALED_SUBCLASSES: List<ProviderSetting> = listOf(
        ProviderSetting.OpenAI(),
        ProviderSetting.Google(),
        ProviderSetting.Claude(),
    )

    private val EXPECTED_SERIAL_NAMES = setOf("openai", "google", "claude")

    /**
     * 钉死 sealed 子类 serialName 集合。新增子类必须更新此断言。
     * 这是最关键的拦截——它让「新增 provider」这个动作无法静默通过 CI。
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun sealedSubclassSerialNamesAreExactlyExpected() {
        val descriptor = ProviderSetting.serializer().descriptor
        val valueDescriptor = descriptor.getElementDescriptor(1)
        val actualSerialNames = (0 until valueDescriptor.elementsCount)
            .map { valueDescriptor.getElementName(it) }
            .toSet()

        assertEquals(
            EXPECTED_SERIAL_NAMES,
            actualSerialNames,
            "ProviderSetting sealed 子类集合变了（当前 $actualSerialNames）。" +
                "如果新增了 provider，必须：1) 更新此期望；2) 审查所有 Swift 侧 " +
                "`if let x as? ProviderSetting.XXX` 链，特别是 " +
                "CouncilRunner.dispatchCouncilStream (CouncilRunner.swift:566-588)；" +
                "3) 确认 ProviderSetting.Types (ProviderSetting.kt:369-376) 已收录。",
        )
    }

    /**
     * 钉死 Types 注册表与显式子类集合一致。SettingsAggregator / SettingsDefaults 等
     * 多处用 ProviderSetting.Types 做遍历（如品牌回填），若 Types 漏了某子类会导致
     * 该 provider 的 builtIn 默认值不生效。
     */
    @Test
    fun typesRegistryCoversAllSealedSubclasses() {
        val explicitNames = ALL_SEALED_SUBCLASSES.map { it::class.simpleName }.toSet()
        val registeredNames = ProviderSetting.Types.map { it.simpleName }.toSet()
        assertEquals(
            explicitNames,
            registeredNames,
            "ProviderSetting.Types 必须收录全部 sealed 子类。" +
                "缺失: ${explicitNames - registeredNames}，多余: ${registeredNames - explicitNames}",
        )
    }

    /**
     * 每个子类必须能无异常实例化（验证 @Serializable 的必填字段都有默认值）。
     * 这保证了从 JSON 反序列化「缺字段」的旧配置不会 crash——
     * 这是 KMP→Swift 桥另一条隐式调用链：Settings JSON → ProviderSetting → Swift。
     */
    @Test
    fun everySealedSubclassIsInstantiableWithDefaults() {
        ALL_SEALED_SUBCLASSES.forEach { setting ->
            assertTrue(setting.enabled, "${setting::class.simpleName} 默认 enabled 必须为 true")
            assertTrue(setting.models.isEmpty(), "${setting::class.simpleName} 默认 models 必须为空")
        }
    }
}
