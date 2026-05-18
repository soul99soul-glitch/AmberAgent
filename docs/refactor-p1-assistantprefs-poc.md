# AssistantPrefs 聚合中枢 PoC

> Q5 A — M1.1 拆分前的 24h 试拆 PoC
> 目标：验证 cross-Prefs ID validation 接口设计 + 初始化顺序 + 循环依赖避免
> 不动 PreferencesStore.kt 本体，仅做设计 + 测试草案

## 1. 问题陈述

`PreferencesStore.kt:420-473` 当前的 `.map { settings -> ... }` 阶段对完整 Settings 做了 **6 处跨域 ID 清理 + 7 处同域去重**：

| 跨域清理 | 引用域 | 被引用域 |
|---|---|---|
| `assistants[].mcpServers` | AssistantPrefs | ExtensionPrefs.mcpServers |
| `assistants[].modeInjectionIds` | AssistantPrefs | ExtensionPrefs.modeInjections |
| `assistants[].lorebookIds` | AssistantPrefs | ExtensionPrefs.lorebooks |
| `assistants[].quickMessageIds` | AssistantPrefs | ExtensionPrefs.quickMessages |
| `favoriteModels` | ChatPrefs | ProviderPrefs.providers[].models[] |
| `searchEnabledServiceIds` | (in raw `searchEnabledServiceIds`) | SearchPrefs.searchServices |

| 同域去重 | 所在域 |
|---|---|
| providers / models | ProviderPrefs |
| assistants | AssistantPrefs |
| ttsProviders | ExtensionPrefs |
| modeInjections / lorebooks / quickMessages | ExtensionPrefs |

**目标**：拆分后这套清理 + 去重逻辑必须**行为等价**地继续工作。

## 2. Candidate 方案

### 方案 A — 观察者模式（A 订阅 B 的 Flow）
AssistantPrefs 持有 ExtensionPrefs/ProviderPrefs 引用，内部 `combine` 自己的 flow + 别人的 flow 做清理。

```kotlin
class AssistantPrefs(
    private val extensionPrefs: ExtensionPrefs,
    private val providerPrefs: ProviderPrefs,
) {
    val flow: Flow<List<Assistant>> = combine(
        rawAssistantsFlow,
        extensionPrefs.flow,
        providerPrefs.flow,
    ) { raw, ext, providers ->
        raw.map { it.copy(mcpServers = it.mcpServers.filter { ... }) }
    }
}
```

**缺点**：AssistantPrefs 知道 ExtensionPrefs/ProviderPrefs 太多，边界不干净。多个 Prefs 互相 combine 时容易制造循环依赖（ExtensionPrefs 如果也想检验 AssistantPrefs 用没用它的 ID 就死了）。

### 方案 B — 聚合层做清理
各 Prefs 完全独立，互不知道。在 PreferencesStore（聚合层）combine 各 flow 后做 cross-domain cleanup。

```kotlin
class PreferencesStore(
    val ui: UIPrefs, val search: SearchPrefs, ... val assistant: AssistantPrefs,
    private val scope: CoroutineScope,
) {
    val settingsFlow: StateFlow<Settings> = combine(
        ui.flow, search.flow, agent.flow, extension.flow,
        provider.flow, chat.flow, assistant.flow,
    ) { ui, search, agent, ext, providers, chat, assistants ->
        cleanCrossDomainIds(ui, search, agent, ext, providers, chat, assistants)
    }.distinctUntilChanged()
     .stateIn(scope, SharingStarted.Eagerly, Settings.dummy())
}
```

**优点**：边界干净，各 Prefs 0 互相依赖，cleanup 集中可测试。
**缺点**：聚合层有"god 倾向" — 但只是 cleanup + assembly，不是业务逻辑，比 1131 行原版好太多。

### 方案 C — 事件总线（EventBus / SharedFlow）
ExtensionPrefs 发"modeInjection deleted"事件，AssistantPrefs 订阅做清理。

**缺点**：复杂度高，状态同步时序问题多。Over-engineering。

---

## 3. 推荐方案：**B（聚合层做清理）**

理由：
- AmberAgent 现有架构已经在聚合点（`.map { settings -> ... }`）做这事 — 方案 B 是最小心智差异
- 各 Prefs 0 互相依赖，单测无 mock 依赖
- cleanup 逻辑集中在聚合层一个函数（~50 行），容易 review + 测试
- 不引入新概念（无 EventBus、无观察者协议）

---

## 4. 接口设计草案

### 4.1 单域 Prefs 通用接口

```kotlin
interface DomainPrefs<T> {
    val flow: StateFlow<T>
    suspend fun update(transform: (T) -> T)
}
```

每个域 Prefs 持有自己的 DataStore key 子集，独立 read/write，互不知道其他 Prefs。

### 4.2 各域 Prefs 数据形

```kotlin
data class UIPrefsData(
    val dynamicColor: Boolean = false,
    val themeId: String,
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting,
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
)

data class ExtensionPrefsData(
    val modeInjections: List<PromptInjection.ModeInjection>,
    val lorebooks: List<Lorebook>,
    val quickMessages: List<QuickMessage>,
    val mcpServers: List<McpServerConfig>,
    val ttsProviders: List<TTSProviderSetting>,
    val selectedTTSProviderId: Uuid?,
    val webDavConfig: WebDavConfig?,
    val s3Config: S3Config?,
    val webServerEnabled: Boolean,
    // ... 其他扩展字段
)

data class AssistantPrefsData(
    val assistants: List<Assistant>,
    val assistantId: Uuid,
    val assistantTags: List<Tag>,
)

// ... 其他 5 个域类似
```

### 4.3 聚合层

```kotlin
class PreferencesStore(
    private val context: Context,
    private val scope: CoroutineScope,
) : KoinComponent {
    // 7 个独立 Prefs，各自读自己的 DataStore key
    val ui: UIPrefs by lazy { UIPrefsImpl(context.settingsStore, scope) }
    val search: SearchPrefs by lazy { SearchPrefsImpl(context.settingsStore, scope) }
    val agent: AgentPrefs by lazy { AgentPrefsImpl(context.settingsStore, scope) }
    val extension: ExtensionPrefs by lazy { ExtensionPrefsImpl(context.settingsStore, scope) }
    val provider: ProviderPrefs by lazy { ProviderPrefsImpl(context.settingsStore, scope) }
    val chat: ChatPrefs by lazy { ChatPrefsImpl(context.settingsStore, scope) }
    val assistant: AssistantPrefs by lazy { AssistantPrefsImpl(context.settingsStore, scope) }

    // 兼容层：保留旧 settingsFlow API
    val settingsFlow: StateFlow<Settings> = combine(
        ui.flow, search.flow, agent.flow, extension.flow,
        provider.flow, chat.flow, assistant.flow,
    ) { u, s, a, e, p, c, asst ->
        assembleSettings(u, s, a, e, p, c, asst)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, Settings.dummy())

    // 兼容层：保留旧 update API
    suspend fun update(transform: (Settings) -> Settings) = settingsMutex.withLock {
        val current = settingsFlow.value
        val next = transform(current)
        distributeToDomains(next)
    }

    private val settingsMutex = Mutex()
}

private fun assembleSettings(
    u: UIPrefsData, s: SearchPrefsData, a: AgentPrefsData,
    e: ExtensionPrefsData, p: ProviderPrefsData, c: ChatPrefsData,
    asst: AssistantPrefsData,
): Settings {
    // 计算各域已有的 valid ID 集合
    val validMcpServerIds = e.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = e.modeInjections.map { it.id }.toSet()
    val validLorebookIds = e.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = e.quickMessages.map { it.id }.toSet()
    val allModelIds = p.providers.flatMap { it.models.map(Model::id) }.toSet()
    val validSearchServiceIds = s.searchServices.map { it.id }.toSet()

    return Settings(
        // UI fields
        dynamicColor = u.dynamicColor,
        themeId = u.themeId,
        // ... 各域字段

        // 跨域 cleanup
        favoriteModels = c.favoriteModels.filter { it in allModelIds },
        searchEnabledServiceIds = s.searchEnabledServiceIds.filter { it in validSearchServiceIds },
        assistants = asst.assistants.distinctBy { it.id }.map { it.copy(
            mcpServers = it.mcpServers.filter { it in validMcpServerIds }.toSet(),
            modeInjectionIds = it.modeInjectionIds.filter { it in validModeInjectionIds }.toSet(),
            lorebookIds = it.lorebookIds.filter { it in validLorebookIds }.toSet(),
            quickMessageIds = it.quickMessageIds.filter { it in validQuickMessageIds }.toSet(),
        )},
        // 同域去重已在各 Prefs 自己处理（producer 写入时 distinctBy）
        providers = p.providers,        // ProviderPrefs 已确保 distinct
        modeInjections = e.modeInjections,
        // ...
    )
}

private suspend fun distributeToDomains(next: Settings) = coroutineScope {
    listOf(
        async { ui.update { _ -> UIPrefsData(/* from next */) } },
        async { search.update { _ -> SearchPrefsData(/* from next */) } },
        async { agent.update { _ -> AgentPrefsData(/* from next */) } },
        async { extension.update { _ -> ExtensionPrefsData(/* from next */) } },
        async { provider.update { _ -> ProviderPrefsData(/* from next */) } },
        async { chat.update { _ -> ChatPrefsData(/* from next */) } },
        async { assistant.update { _ -> AssistantPrefsData(/* from next */) } },
    ).awaitAll()
}
```

---

## 5. 设计验证（4 个关键问题）

### Q1 — 初始化顺序
**问题**：各 Prefs 各自从 DataStore 读数据，谁先 ready？
**答案**：用 `combine` + `SharingStarted.Eagerly` + `Settings.dummy()` 兜底。所有 7 个 flow 至少 emit 1 次后，聚合层才输出第一个 Settings。在此之前订阅者拿到 `Settings.dummy()`。这跟现有 `toMutableStateFlow(scope, Settings.dummy())` 行为一致。✅

### Q2 — 循环依赖
**问题**：AssistantPrefs ↔ ExtensionPrefs 互相引用 ID，会不会循环？
**答案**：方案 B 下**各 Prefs 0 互相依赖**。所有跨域逻辑在聚合层（PreferencesStore）。AssistantPrefs 内部只关心自己的字段，不知道 ExtensionPrefs 存在。✅

### Q3 — Dummy 状态时跨域 ID 清理
**问题**：如果 ExtensionPrefs 还没 ready（emit dummy），AssistantPrefs 已经 ready，聚合层会不会把 Assistant.modeInjectionIds 全清掉？
**答案**：`combine` 等待**所有** flow emit 才合成第一个 Settings —— 没有"半就绪"状态。两个 flow 都 ready 之后才进入 cleanup。最坏情况：所有 Prefs 都用 dummy data，cleanup 把所有 ID 清空（但 dummy Assistant 也没引用任何 ID，所以无害）。✅

但有一个 subtle case：DataStore 首次读时 emit `emptyPreferences()` → 各 Prefs 用 default 值。如果用户实际数据里 Assistant.modeInjectionIds = ["abc"]，而 ExtensionPrefs 的 modeInjections 还没读完（emit default 空列表），cleanup 会把 "abc" 清掉。**这是 bug 风险**。

**缓解**：DataStore 的 emit 顺序是 init → real data，combine 会发一次 dummy Settings 然后立刻发 real Settings。subscriber 收到 dummy 后会立刻被 real 覆盖（distinctUntilChanged 不阻挡，因为不同值）。但**期间持久化 distributeToDomains 不能跑** — 加 Mutex 保证 `update()` 完整原子。

**额外保险**：在 `assembleSettings()` 加一个 "dummy detection"：如果任何 flow 的值是 dummy（用一个 sentinel marker），跳过 cleanup 直接返回 raw merge。Phase 1 实施时观察是否需要。

### Q4 — `update(Settings -> Settings)` 回写原子性
**问题**：旧 API 接受完整 Settings transform，新版要分解到 7 个 domain.update() 调用。如果中间一个失败，state 不一致。
**答案**：`coroutineScope { async / awaitAll }` + `Mutex` 保证：
- Mutex 保证一次只有一个 update 跑
- coroutineScope 保证所有 async 完成或抛异常
- 任一 prefs.update 失败 → 全部抛出 → 调用者重试

旧 API 行为：DataStore.edit 内部是事务，单文件原子。新 API 7 个 DataStore.edit 不是单事务 —— 但实际场景每个 Prefs 用同一个 DataStore (`settingsStore`)，只是 key 不同。Android DataStore.edit 对**整个 DataStore 实例**做事务。所以 7 个 prefs.update 并发跑时是 7 个并发事务，DataStore 内部会串行化。这等价于"7 次小事务"而不是"1 次大事务"。

**影响**：中间 power loss 可能导致部分字段写入。但实际场景 update 调用频率低（用户点保存按钮），power loss 概率 ~0。可接受。

如果对原子性有更严要求，可改成：单一 `settingsStore.edit { prefs -> 7 个 key 一起写 }`。但这需要在聚合层重构 DataStore.edit 调用，复杂度增加，**Phase 1 不做**。

---

## 6. 测试草案（M1.1 拆完后必跑）

```kotlin
class CrossDomainCleanupTest {
    @Test fun `assistant mcpServers 引用不存在的 id 应被清理`()
    @Test fun `favoriteModels 引用不存在的 model id 应被清理`()
    @Test fun `searchEnabledServiceIds 引用不存在的 service id 应被清理`()
    @Test fun `所有 Prefs 都 ready 后才输出 valid settings`()
    @Test fun `update full settings 会原子分发到所有 domain`()
    @Test fun `单域 update 不影响其他域的 flow`()
}

class V3MigrationTest {
    @Test fun `quickMessages 从 Assistant 提取到全局后 assistantPrefs 和 extensionPrefs 协作正确`()
}
```

特别要测的边界：
- 初始化竞态（DataStore 第一次 emit）
- 7 个 Prefs 并发 update（mutex 保护）
- 同域去重（distinctBy）
- 跨域 cleanup（filter not in valid set）

---

## 7. 结论

✅ **方案 B（聚合层做清理）可行**。

接口设计草案验证：
- 边界干净，0 循环依赖 ✓
- 初始化顺序由 `combine` + `dummy` 自然处理 ✓
- 原子回写有 Mutex 保护 ✓
- 现有 `.map { settings -> ... }` 逻辑一对一迁到 `assembleSettings()` ✓

**风险残留**：DataStore 首次 emit 时跨域 ID 可能被错误清空。Phase 1 实施时观察，必要时加 sentinel marker。

**推荐**：按蓝图 M1.1.1 → M1.1.7 顺序执行，**M1.1.6 ExtensionPrefs 和 M1.1.7 AssistantPrefs 完成后**，再写聚合层（M1.1.8）。每步先跑现有 testDebugUnitTest 确保不破坏 PreferencesStore 现有行为，再 squash。

---

## 8. 下一步

PoC 结论：**接口设计可行，可以开 M1.1.1**。

按蓝图 M1.1 顺序：
- M1.1.1 UIPrefs (0 依赖，最先做练手) — 估时 0.5 天
- M1.1.2 SearchPrefs — 0.5 天
- M1.1.3 AgentPrefs — 1 天（体量大）
- M1.1.4 ProviderPrefs — 1 天（被 ChatPrefs 依赖，先做）
- M1.1.5 ChatPrefs — 1 天
- M1.1.6 ExtensionPrefs — 1.5 天
- M1.1.7 AssistantPrefs — 2 天（聚合中枢最复杂）
- M1.1.8 PreferencesStore 薄包装（聚合层）+ 删原 god class — 1 天

**M1.1 合计 8.5 天**（蓝图估 1-1.5 周，吻合）。
