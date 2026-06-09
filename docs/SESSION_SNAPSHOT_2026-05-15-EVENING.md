# Session Snapshot — 2026-05-15 EVENING（pre-compact #2）

> 第二份 snapshot，覆盖**自 PM snapshot (`260130a7`) 以来的进度**。
> 完整背景见 `docs/AMBERAGENT_HANDOFF_2026-05-15.md`。
> PM snapshot (`docs/SESSION_SNAPSHOT_2026-05-15-PM.md`) 仍有效作 cold-start 全图基线。

---

## 1. 你（压缩后的我）必须读完的 5 份文档（按顺序）

1. **本文档** — evening snapshot，最先读
2. `docs/SESSION_SNAPSHOT_2026-05-15-PM.md` — 上一份 snapshot（仅看 §1-7 拿 nuance 和 7 个坑；§8-10 已过期）
3. `docs/AMBERAGENT_HANDOFF_2026-05-15.md` — cold-start 全图（含 §15 滚动更新含 M1.1 全部完成总结 + M1.1.8 5 段拆分建议）
4. `docs/refactor-p1-blueprint.md` — Phase 1 总图
5. `docs/refactor-p1-assistantprefs-poc.md` — 方案 B 设计（背景）

---

## 2. 当前精确 git 状态

```
github-private/refactor/p1-godclass  aecaf9b3  refactor(p1): M1.1.8c — 切流 11(实际12) leaf caller
                                     5da3e8b7  refactor(p1): M1.1.8b — atomic RMW + 16 helper tests
                                     7d5c00f4  refactor(p1): M1.1.8a fix — B1 search cleanup + W1 timing
                                     1f51a3da  refactor(p1): M1.1.8a — 引入 SettingsAggregator (0 caller)
                                     a2eee731  docs(handoff): M1.1 全 7 步完成总结 + M1.1.8 拆分建议
                                     a7304025  refactor(p1): M1.1.7 — AssistantPrefs (M1.1 末段)
                                     1536ab17  refactor(p1): M1.1.6 — ExtensionPrefs
                                     8d35add9  refactor(p1): M1.1.5 — ChatPrefs
                                     5e3e4138  refactor(p1): M1.1.4 — ProviderPrefs
                                     ca6598e4  docs(handoff): M1.1.3 追加
                                     772aca5e  refactor(p1): M1.1.3 — AgentPrefs
                                     8f923896  docs(handoff): M1.1.2 追加
                                     a228d8a5  refactor(p1): M1.1.2 — SearchPrefs
                                     260130a7  docs(snapshot): pre-compact PM snapshot ← 上一份 snapshot
```

**当前分支**：`refactor/p1-godclass`，HEAD = `aecaf9b3`（**M1.1.8c 完成 + push**），working tree 应仅本 evening snapshot 未 commit。

---

## 3. M1.1.8 安全 Route B 5 段拆分总图

```
M1.1.8  god class 真正消灭（vs Route A 保留 facade）
├─ a  写 SettingsAggregator (0 caller)                     ✅ 完成 (1f51a3da + 7d5c00f4)
├─ b  atomic RMW + 16 helper unit tests                   ✅ 完成 (5da3e8b7)
├─ c  切 12 个 read-only leaf caller                       ✅ 完成 (aecaf9b3)
├─ d  切剩余 read-only + 25 write caller + supplier        ⏳ 下一步
└─ e  删 class SettingsStore                               ⏳
```

**用户拍板**：选 A 启动 M1.1.8d，**等说"开干"才启动**（snapshot 写成 evening 因为还没开干）。

---

## 4. 这次 PM snapshot → 现在做了什么（精炼时间线）

### M1.1.8a — SettingsAggregator 写完（commit 1f51a3da + 7d5c00f4 修复）

- 新增 `app/.../data/datastore/prefs/SettingsAggregator.kt`（498 行）
- 3 段 map 复刻 SettingsStore reader：
  1. `composeRawSettings` — 7 PrefsData 字段对位映射
  2. `applyBackfillAndSeed` — REMOVED filter / metadata sync / brand re-stamp / image-model seed / DEFAULT_ASSISTANTS inject / routing-qm seed + subscribe / TTS backfill / branding / 2 个 seed flag flip
  3. `applyCrossDomainConsistency` — dedup + filter stale + search reader cleanup
- 5 个 helper API 1:1 mirror SettingsStore（updateAssistant 系列）
- DI 注册（0 caller）
- **3 处 visibility 改 internal**：`withAmberAgentAssistantBranding` / `REMOVED_DEFAULT_TTS_PROVIDER_IDS` / `DEFAULT_TTS_PROVIDERS`（PreferencesStore.kt 1074/1108/1111）
- Reviewer 发现 **B1**：read-only 路径下 search 跨字段 cleanup 不等价（fresh install 会"默认搜索关闭"），**已修**（在 applyCrossDomainConsistency 末尾加 2 个 cleanup + ifEmpty 派生 default）
- Reviewer 顺便建议 **W1**：`settingsFlow.value = settings` 显式同步，**已修**（backing property `_settingsFlow: MutableStateFlow` + `settingsFlow: StateFlow get() = _settingsFlow`，update 内 `_settingsFlow.value = settings` match SettingsStore line 484）

### M1.1.8b — atomic RMW + 16 helper unit tests（commit 5da3e8b7）

- **测试基础设施**：`libs.versions.toml` + `app/build.gradle.kts` 加 `testImplementation(kotlinx-coroutines-test)`（本次未用，为后续端到端测预埋）
- **Constructor refactor** 8 处：UIPrefs/SearchPrefs/AgentPrefs/ProviderPrefs/ChatPrefs/ExtensionPrefs/AssistantPrefs/SettingsAggregator 从 `(context: Context, scope: AppScope)` 改成 `(dataStore: DataStore<Preferences>, scope: AppScope)` 直接注入 — 单测可直接构造，不需 Robolectric/Context
- DI 8 处改为 `Prefs(dataStore = get<Context>().settingsStore, scope = get())`
- **Atomic RMW** 7 处：每个 Prefs.update 改成 `dataStore.edit { p -> readFrom(p) → transform → writeTo(p) }`，readFrom/writeTo 提取 private helper。这是项目长期登记的 "non-atomic RMW 债"兑现（M1.1.1-7 commit message 都登记过）
- **3 helper 改 internal**：composeRawSettings / applyBackfillAndSeed / applyCrossDomainConsistency 让测试可访问
- 新 `SettingsAggregatorHelpersTest.kt` 420 行 / **16 testcase 全 PASS / 82ms** —— 覆盖：
  - composeRawSettings 字段映射 smoke
  - applyBackfillAndSeed 8 个 cleanup 场景（REMOVED filter / image-model seed/skip/dedupe / DEFAULT_ASSISTANTS / routing QM / TTS backfill / branding）
  - applyCrossDomainConsistency 5 个（dedup / search coerceIn / 派生默认 / stale 过滤 / favoriteModels）
  - pipeline empty + idempotent end-to-end
- **现有 352 测试 0 回归**（6 个 baseline failure 与本次无关，pre-existing）

### M1.1.8c — 切 12 read-only leaf caller（commit aecaf9b3）

12 个 caller 从 SettingsStore 切到 SettingsAggregator（仅 import + type ref，body 0 改动）：

1. `data/ai/transformers/OcrTransformer.kt`（inline get<>）
2. `data/ai/transformers/TemplateTransformer.kt`
3. `data/repository/ImageGenerationRepository.kt`
4. `data/memory/extraction/MemoryExtractor.kt`
5. `data/memory/dream/MemoryDreamPlanner.kt`
6. `data/memory/dream/MemoryDreamScheduler.kt`
7. `data/memory/dream/MemoryDreamWorker.kt`（inline get<>）
8. `data/agent/office/radar/DocRadar.kt`
9. `data/agent/tools/ExternalFileTools.kt`
10. `ui/pages/chat/ChatDrawerVM.kt`
11. `ui/pages/history/HistoryVM.kt`
12. `ui/pages/stats/StatsVM.kt`

- **DI 0 改动**（Koin auto-resolve by type）
- 装机 PID 5894 alive，0 ERROR
- 368 测试 6 failed（与 M1.1.8b baseline 完全一致，0 回归）
- Reviewer PASS 0 阻塞

---

## 5. M1.1.8d 启动 — Reviewer pre-flight 5 条建议

🚨 **M1.1.8d 启动前必做（不阻塞但应该处理）**：

### F1 — Caller 数字订正
M1.1.8c commit message 标题说"11 个"，实际 12 个 file。代码正确，仅 doc 错。
**处理**：M1.1.8d commit message 里 acknowledge，不单独修 commit。

### F2 — 扫剩余 read-only caller 优先切（最重要！）
M1.1.8c 切完后 SettingsStore 仍剩约 53 个 caller 文件。其中除 ~25 个 write caller 外，**还有更多 read-only caller**（BoardWorker.kt / PlaceholderTransformer.kt / RikkaHubApp.kt / 部分 service / web route 等）本来该跟 M1.1.8c 一起切。

**M1.1.8d 实施顺序**（强烈推荐拆 3 commit）：
- **M1.1.8d-1**：剩余 read-only caller（grep `\bSettingsStore\b` 全集减去 update/updateAssistant* 的）—— 零风险，机制同 M1.1.8c
- **M1.1.8d-2**：write caller（~25 个）—— 依赖 atomic RMW + 5 helper API mirror（M1.1.8b 已保护）
- **M1.1.8d-3**：AppModule.kt:542/559 inline supplier lambda 切流（**单独 commit**，牵动 Board 信号链 TimeAnchorSignalCollector + SignalAggregator + BoardScheduler）—— 切前后各装机一次对比 logcat

### F3 — PreferenceStoreV1/V2/V3Migration 明确豁免
3 个 migration 文件引用 SettingsStore 是迁移实现细节，**不要切**到 Aggregator。M1.1.8e 删 SettingsStore class 时需保留迁移路径（迁移本身是 SharedPreferencesMigration，不依赖 god class 类本身）。

### F4 — handoff §11 review 模板更新
M1.1.8c 之前的"0-caller 硬检"已与现实矛盾。建议更新为：
```
10. 切流 caller 受控扩散检查：
    - commit message 明确列出切的 caller，review 比对 diff 一致
    - 切的 caller 不依赖 SettingsStore 私有方法
    - 仅用 SettingsAggregator 公开 API (settingsFlow / update / updateAssistant*)
    - SettingsAggregator 自身代码本次 0 改动（git diff 验证）
    - 现有 16 helper test + 352 单测 0 回归
```

### F5 — W1 修复 runtime 兜底验证
M1.1.8d 写 caller 切流后，挑一个 "update → 立刻读 .settingsFlow.value" 的场景跑装机 sanity（M1.1.8a W1 修复已通过 helper test 锁定，但 runtime 场景再覆盖一次）。

---

## 6. SettingsAggregator API（M1.1.8b 已冻结，不许扩）

```kotlin
class SettingsAggregator(
    private val dataStore: DataStore<Preferences>,
    private val uiPrefs: UIPrefs,           // + 6 个其他 Prefs
    scope: AppScope,
) {
    val settingsFlow: StateFlow<Settings>   // backing property 后只读
    suspend fun update(settings: Settings)            // atomic 整体写
    suspend fun update(fn: (Settings) -> Settings)    // 函数式写
    suspend fun updateAssistant(assistantId: Uuid)
    suspend fun updateAssistantModel(...)
    suspend fun updateAssistantReasoningLevel(...)
    suspend fun updateAssistantMcpServers(...)
    suspend fun updateAssistantInjections(...)
}
```

**M1.1.8d-e 不允许扩展新公开方法**，否则破坏等价性兜底。

---

## 7. 环境关键变量（防压缩后忘记）

```bash
# JDK（每次跑 gradle 都要 export）
export JAVA_HOME="/Users/arquiel/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# ADB
ADB=~/Library/Android/sdk/platform-tools/adb

# 设备（**注意**：当前连接的是 OPPO Find N5 PMA110 不是 Nothing A069 — smoke test OK，但 baseline trace 必须 Nothing）
$ADB devices

# 构建 / 装机
./gradlew :app:assembleNotion
$ADB install -r app/build/outputs/apk/notion/app-universal-notion.apk

# 跑单测（cli 用法）
./gradlew :app:testDebugUnitTest --tests "*SettingsAggregatorHelpersTest*"
./gradlew :app:testDebugUnitTest                                # 全量，含 6 个 pre-existing failure
```

---

## 8. PM snapshot 仍有效的 nuance（必记）

照旧（详见 PM snapshot §7）：
- N1: SettingsStore vs PreferencesStore 命名错位 — Phase 3 才统一
- N2: 用 `assembleNotion`（不是 `assembleNotionDebug`，notion 是 buildType）
- N3: M1.3 ContextPlanner 已存在 — 蓝图 §B 小修留 M1.3
- N4: ~~M1.1.1 UIPrefs 单测债~~ ✅ **已通过 M1.1.8b helper test 间接覆盖**；per-Prefs 端到端单测推 M1.8 验收
- N5: ~~atomic RMW 改造债~~ ✅ **M1.1.8b 已兑现 7 处**
- N6: 每 milestone 末派 review subagent gate（硬性要求，本次 8a/8a-fix/8b/8c 全部派了）
- N7: Theme.amberagent 全小写
- N8: 远程操作（force push / 大规模 delete）需单独授权（普通 push 已在 push）

**新增 nuance**：
- **N9**：M1.1.8c 起 SettingsAggregator caller list 不再 0 — 自动反转 handoff §11 第 10 项硬检规则（详见 §5 F4）
- **N10**：M1.1.8b 后 SettingsAggregator 公开 API 已**冻结**（8 个方法 + 1 个 settingsFlow），M1.1.8d-e 不许扩

---

## 9. 接下来 immediate 步骤（压缩后立即做）

```
1. 读完本 snapshot
2. 跑 git log --oneline -3 验证 HEAD = aecaf9b3 (或本 snapshot commit hash)
3. 报告状态：
   "M1.1.8c 完成（aecaf9b3 + 上一个 snapshot commit）。等用户说开干 M1.1.8d。
    推荐拆 3 个 commit (read-only / write caller / supplier)。"
4. 等用户说"开干 M1.1.8d" 或类似确认
5. 启动 M1.1.8d-1：先 grep 找剩余 read-only caller，按 M1.1.8c 同款 mechanic 批量切
```

---

## 10. Sanity check（压缩后第一件事跑）

```bash
cd /Users/arquiel/Downloads/AI/rikkashit/rikkahub
git log --oneline -5
# 期望：
#   <本 snapshot commit> docs(snapshot): evening pre-compact #2
#   aecaf9b3 refactor(p1): M1.1.8c — 切流 11(实际12) leaf caller
#   5da3e8b7 refactor(p1): M1.1.8b — atomic RMW + 16 helper tests
#   7d5c00f4 refactor(p1): M1.1.8a fix — B1+W1
#   1f51a3da refactor(p1): M1.1.8a — SettingsAggregator

git status --short
# 期望：空

# 关键文件存在性
ls docs/SESSION_SNAPSHOT_2026-05-15-EVENING.md \
   docs/SESSION_SNAPSHOT_2026-05-15-PM.md \
   docs/AMBERAGENT_HANDOFF_2026-05-15.md \
   app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/SettingsAggregator.kt \
   app/src/test/java/me/rerere/rikkahub/data/datastore/prefs/SettingsAggregatorHelpersTest.kt

# 现有测试 baseline 检查（应得 368 tests / 6 failed，与 M1.1.8b/c 一致）
export JAVA_HOME="/Users/arquiel/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests "*SettingsAggregatorHelpersTest*" --console=plain | tail -5
# 期望：BUILD SUCCESSFUL，16 tests 通过
```

---

## 11. 如果发现 git state 跟 §2 不一致

可能场景：
- 用户在 GitHub 上又改了什么 → `git fetch github-private && git pull --ff-only github-private refactor/p1-godclass`
- 远程 phase 分支被别处更新 → 看 `git log github-private/refactor/p1-godclass`
- working tree 不 clean → `git status` 看是不是用户手工改

**不要**自动 reset/discard，先 push back 问用户。

---

**snapshot 时间**：2026-05-15 EVENING，M1.1.8c 完成 + push 后、M1.1.8d 开干前
**有效期**：直到 M1.1.8d-1 完成（或 24h 过期，以先到为准）
