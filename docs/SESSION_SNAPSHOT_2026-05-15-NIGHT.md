# Session Snapshot — 2026-05-15 NIGHT（pre-compact #3）

> 第三份 snapshot，覆盖 EVENING snapshot 后 M1.1.8d 全部完成 + 装机 sanity + 1 个 regression fix 的进度。
> 完整背景见 `docs/AMBERAGENT_HANDOFF_2026-05-15.md`。
> PM / EVENING snapshot 的 §1-7 仍可作 cold-start 背景（§8-10 已过期）。

---

## 1. 你（压缩后的我）必须读完的 6 份文档（按顺序）

1. **本文档** — NIGHT snapshot，最先读
2. `docs/SESSION_SNAPSHOT_2026-05-15-EVENING.md` — EVENING snapshot（仅 §1-8 取 nuance / pre-flight；§9-10 已过期）
3. `docs/SESSION_SNAPSHOT_2026-05-15-PM.md` — PM snapshot（仅看 §1-7 拿原始 nuance）
4. `docs/AMBERAGENT_HANDOFF_2026-05-15.md` — cold-start 全图（§15 滚动更新）
5. `docs/refactor-p1-blueprint.md` — Phase 1 总图
6. `docs/refactor-p1-assistantprefs-poc.md` — 方案 B 设计背景

---

## 2. 当前精确 git 状态（HEAD = `95f35951`）

```
github-private/refactor/p1-godclass
  95f35951  fix(p1): M1.1.8d-3 follow-up — filterNot dummy 等价 settingsFlowRaw 冷流挂起
  d8061e66  refactor(p1): M1.1.8d-3 — 切流 6 个 app-init/service/DI caller，关闭 M1.1.8d
  e576bd0c  refactor(p1): M1.1.8d-2 — 切流 30 个 write caller
  f85fbdac  refactor(p1): M1.1.8d-1 — 切流 15 个剩余 read-only caller
  14009695  docs(snapshot): evening pre-compact #2
  aecaf9b3  refactor(p1): M1.1.8c — 切流 12 leaf caller
  5da3e8b7  refactor(p1): M1.1.8b — atomic RMW + 16 helper tests
  7d5c00f4  refactor(p1): M1.1.8a fix — B1 + W1
  1f51a3da  refactor(p1): M1.1.8a — SettingsAggregator
```

**当前分支**：`refactor/p1-godclass`，HEAD = `95f35951`（filterNot fix 完成 + push），working tree 应仅本 NIGHT snapshot 未 commit。

---

## 3. M1.1.8 安全 Route B 进度总图

```
M1.1.8  god class 真正消灭（vs Route A 保留 facade）
├─ a  写 SettingsAggregator (0 caller)                     ✅ 1f51a3da + 7d5c00f4
├─ b  atomic RMW + 16 helper unit tests                   ✅ 5da3e8b7
├─ c  切 12 个 read-only leaf caller                       ✅ aecaf9b3
├─ d  切 51 个剩余 caller（分 3 commit）                   ✅ f85fbdac + e576bd0c + d8061e66
│     └─ fix: filterNot dummy 等价                         ✅ 95f35951
└─ e  删 class SettingsStore                               ⏳ 下一步（明天 fresh context）
```

M1.1.8d 累计 51 个 caller 切流（15 + 30 + 6） + filterNot fix。
M1.1.8 累计 caller 总数：12 (8c) + 15 + 30 + 6 = **63 个**。

---

## 4. EVENING snapshot 后 → 现在做了什么（精炼时间线）

### M1.1.8d-1（commit f85fbdac）— 15 个 read-only caller

切流文件 15 个，纯 import + 类型引用替换，0 body 改动。RouteActivity.kt 走 sed 路径（避免 Edit 工具的 CRLF 归一化噪声）。Reviewer 6/6 PASS。

### M1.1.8d-2（commit e576bd0c）— 30 个 write caller

包括 7 个 data/agent + data/ai + data/sync + 2 个 UI components + 16 个 UI pages VM + 4 个 web 链。SettingExperimentalWebMountPage.kt 用 FQ 路径（line 104/1062），perl -i 同步处理 FQ + bare。

发现并解决：SkillsTools.kt + McpManagementTools.kt 因 ChatService(deferred) 还引用，被迫一起 defer 到 8d-3。

Reviewer 7/7 PASS。

### M1.1.8d-3（commit d8061e66）— 6 个 app-init / service / DI

切流：RikkaHubApp / ChatService / WebServerService / SkillsTools / McpManagementTools / AppModule（supplier lambdas line 542/559 牵动 Board 信号链）。

**做了 1 个语义切换**：5 处 `.settingsFlowRaw.first()` → `.settingsFlow.first()` —— 因为 Aggregator API 已冻结（N10）不能扩 settingsFlowRaw，且 reviewer 给了字段层面等价性证明。Reviewer 8/8 PASS。

**但留下了 regression**（见 §5）。

### filterNot fix（commit 95f35951）

装机 sanity 抓到：
```
W SettingsAggregator: Cannot update dummy settings
I RikkaHubApp: incrementLaunchCount: 0
```

诊断 + 修复：5 处 `.settingsFlow.first()` → `.settingsFlow.filterNot { it.init }.first()`。

修复后再装机：
```
I RikkaHubApp: incrementLaunchCount: 97   ✅ (磁盘 96 + 1)
I WM-WorkerWrapper: Worker result SUCCESS for BoardWorker  ✅
I ChatService: createSession  ✅
```

Reviewer 7/7 PASS（1 个 ChatService import 位置 cosmetic nit）。

---

## 5. M1.1.8d-3 教训（写 reviewer prompt 时要记）

### Reviewer 等价性证明的盲区

8d-3 reviewer 用"site-by-site field-level equivalence"证明了 5 处 `settingsFlowRaw → settingsFlow` 切换安全。但只验证了：
- 哪些字段会被 applyBackfillAndSeed / applyCrossDomainConsistency 修改
- 哪些字段不会

**漏了**：cold flow（`dataStore.data.map{}`）vs hot StateFlow（`MutableStateFlow(dummy())`）的 `.first()` 行为差异。
- cold flow `.first()` = 挂起等第一个真实 emission
- hot StateFlow `.first()` = 立即返回当前 value（可能是 dummy）

→ Aggregator update() 有 dummy guard，写入 dummy 直接被拒，且没抛异常只 Log.w。
→ 用户可见：launchCount 永远 0、webServerEnabled cold start 错位读取。

### M1.1.8e reviewer prompt 必加 check item

```
□ Flow operator semantics check（除字段等价外）：
  - cold flow vs hot StateFlow 的 .first() 行为
  - distinctUntilChanged 跳过的边界条件
  - StateFlow 初始值是否为 dummy / placeholder
  - SharingStarted strategy 的差异
  - .filterNot / .map 链的副作用
```

### 装机 sanity 是底线兜底

5 个 reviewer subagent 都 PASS，但 runtime sanity 直接抓到 bug。
→ **任何包含 settingsFlowRaw 切换 / 跨域 flow 改造 / DI 链路 / 长生命周期 service 的 commit，必须装机至少 5 分钟看 logcat**。
→ 单测 + reviewer 不能替代装机（前者覆盖 pure function / type-check，后者覆盖 invariant + 等价性证明，装机覆盖 runtime 时序 / 并发 / 真实数据）。

---

## 6. SettingsAggregator API（仍冻结 N10）

```kotlin
class SettingsAggregator(
    private val dataStore: DataStore<Preferences>,
    private val uiPrefs: UIPrefs,           // + 6 个其他 Prefs
    scope: AppScope,
) {
    val settingsFlow: StateFlow<Settings>             // backing property 后只读
    suspend fun update(settings: Settings)            // atomic 整体写（带 dummy guard）
    suspend fun update(fn: (Settings) -> Settings)
    suspend fun updateAssistant(assistantId: Uuid)
    suspend fun updateAssistantModel(...)
    suspend fun updateAssistantReasoningLevel(...)
    suspend fun updateAssistantMcpServers(...)
    suspend fun updateAssistantInjections(...)
}
```

**API 仍冻结**。M1.1.8e 删 class 时，原 `settingsFlowRaw` 属性可一并删除（外部已 0 caller，本 fix 后）。

---

## 7. 当前 SettingsStore 剩余 reference（M1.1.8e 处理范围）

```
$ grep -rln '\bSettingsStore\b' app/src/main/java/ | sort
```

应仅剩：
- `data/datastore/PreferencesStore.kt` — god class 本体（删类，**但保留**文件）
- `data/datastore/migration/PreferenceStoreV1Migration.kt` — F3 豁免
- `data/datastore/migration/PreferenceStoreV2Migration.kt` — F3 豁免
- `data/datastore/migration/PreferenceStoreV3Migration.kt` — F3 豁免
- `data/datastore/prefs/UIPrefs.kt` / `SearchPrefs.kt` / `AgentPrefs.kt` / `ProviderPrefs.kt` / `ChatPrefs.kt` / `ExtensionPrefs.kt` / `AssistantPrefs.kt` / `SettingsAggregator.kt` — 每个 Prefs 文件内部用 `SettingsStore.XXX_KEY` 常量（preferences keys），需迁移
- `di/DataSourceModule.kt` — DI 注册 `single<SettingsStore>` + Prefs 注册
- `di/AppModule.kt` — 已无 reference（M1.1.8d-3 已切）
- `ui/context/LocalSettings.kt:7` — 仅 error 字符串字面量（cosmetic 清理）

### M1.1.8e 关键决策（明天动手前要想清楚）

1. **Preferences keys 归宿** — `SettingsStore.DYNAMIC_COLOR / THEME_ID / ...` 这 55 个 key 现在仍挂在 `SettingsStore` companion object 上。删 class 时它们去哪？候选：
   - 抽到顶层 `PreferenceKeys` object（迁移最直接）
   - 各自迁移到对应 Prefs 文件（最干净，工作量大）
   - 移到 `data/datastore/PreferencesKeys.kt` 新文件

2. **migration 3 个文件** — 内部用 `SettingsStore.XXX_KEY`。如果 key 抽出来，这些文件也得 import 新位置。

3. **PreferencesStore.kt 内的 extension function** — `getCurrentAssistant`, `findProvider`, `resolveTaskChatModel` 等，都是 Settings 的 extension。这些 fn 不在 class 内（class 外的顶级 fn），删类不影响。但要审计：是否还有应该挂在 class 外的辅助函数被一起删？

4. **del settingsFlowRaw 属性** — class 删了自然消失，但需在 commit message 显式登记（filterNot fix 已让外部 0 caller）。

---

## 8. 关键 nuance（包括 PM/EVENING 的，删除已过期的）

照旧（详见 PM snapshot §7 + EVENING snapshot §8）：
- N1: SettingsStore vs PreferencesStore 命名错位 — Phase 3 才统一
- N2: `assembleNotion`（notion 是 buildType），包名 `me.rerere.amberagent.notion`
- N3: M1.3 ContextPlanner 已存在 — 蓝图 §B 小修留 M1.3
- N4: ~~per-Prefs 端到端单测~~ — 推 M1.8 验收
- N5: ~~atomic RMW 改造债~~ ✅ M1.1.8b 兑现
- N6: 每 milestone 末派 review subagent gate（硬性要求）
- N7: Theme.amberagent 全小写
- N8: 远程 force push / 大规模 delete 需单独授权
- N9: SettingsAggregator caller 已大量存在（不再 0 caller，已过期）
- N10: SettingsAggregator 公开 API 已冻结（仍生效）

**新增 NIGHT nuance**：
- **N11**：Aggregator settingsFlow 是 hot StateFlow，初始 dummy。所有 `.first()` 读取必须 `.filterNot { it.init }.first()` 才等价 settingsFlowRaw 的冷流挂起。新写 caller 这边注意。
- **N12**：Reviewer subagent 等价性证明粒度需明确包含 flow operator 语义（cold/hot、initial value、first 行为、distinctUntilChanged、SharingStarted）。M1.1.8e reviewer prompt 显式加这条。
- **N13**：装机 sanity 是 reviewer + 单测不能替代的最终兜底。任何含 flow 改造 / DI / 长生命周期 service 的 commit 必须装机至少 5 分钟看 logcat。

---

## 9. 接下来 immediate 步骤（压缩后立即做）

```
1. 读完本 snapshot
2. 跑 git log --oneline -3 验证 HEAD = 95f35951 + d8061e66 + e576bd0c
3. 报告状态：
   "M1.1.8d 完成 + filterNot fix 完成（95f35951）。M1.1.8 仅剩 M1.1.8e (删类)。
    建议先审计 §7 的 M1.1.8e 关键决策再开干。"
4. 等用户说 "开干 M1.1.8e" 或类似确认
5. M1.1.8e 启动前：先做 §7 的 4 个关键决策，可能需要 push back 让用户拍板
6. 反复装机（参考 N13）
```

---

## 10. Sanity check（压缩后第一件事跑）

```bash
cd /Users/arquiel/Downloads/AI/rikkashit/rikkahub
git log --oneline -5
# 期望：
#   <本 snapshot commit> docs(snapshot): night pre-compact #3
#   95f35951 fix(p1): M1.1.8d-3 follow-up — filterNot dummy 等价
#   d8061e66 refactor(p1): M1.1.8d-3 — 切流 6 个 app-init/service/DI caller
#   e576bd0c refactor(p1): M1.1.8d-2 — 切流 30 个 write caller
#   f85fbdac refactor(p1): M1.1.8d-1 — 切流 15 个剩余 read-only caller

git status --short
# 期望：空

# 关键文件存在性
ls docs/SESSION_SNAPSHOT_2026-05-15-NIGHT.md \
   docs/SESSION_SNAPSHOT_2026-05-15-EVENING.md \
   docs/SESSION_SNAPSHOT_2026-05-15-PM.md \
   docs/AMBERAGENT_HANDOFF_2026-05-15.md \
   app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/SettingsAggregator.kt

# 验证 SettingsStore 应用代码 caller 已清
grep -rln '\bSettingsStore\b' app/src/main/java/ | grep -v -E '(PreferencesStore\.kt|migration/PreferenceStoreV|datastore/prefs/|di/DataSourceModule|LocalSettings\.kt)' | wc -l
# 期望：0（应用代码 caller 全部已切到 SettingsAggregator）

# 单测 baseline
export JAVA_HOME="/Users/arquiel/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest --console=plain 2>&1 | grep 'tests completed'
# 期望：368 tests completed, 6 failed（baseline 一致）
```

---

## 11. 装机 sanity 数据（M1.1.8d 完成 + filterNot fix 后）

OPPO Find N5 PMA110 (Android 16+)，notion buildType (`me.rerere.amberagent.notion`)，apk 96.6 MB。

**关键 log（PID 7235）**：
```
05-16 00:31:46.196  I ChatService: createSession                          ✅ ChatService 创建会话路径通
05-16 00:31:47.101  I RikkaHubApp: incrementLaunchCount: 97               ✅ filterNot fix 起效，磁盘 96→97
05-16 00:32:19.028  D WM-WorkerWrapper: Starting work for BoardWorker     ✅ AppModule:542/559 supplier lambda 喂入
05-16 00:32:19.505  I WM-WorkerWrapper: Worker result SUCCESS             ✅ Board 信号链端到端 0 异常
```

**0 我方 crash / FATAL**。仅有 Firebase 占位 API key warning（pre-existing）+ OPPO 系统侧 noise（无关）。

---

## 12. 如果发现 git state 跟 §2 不一致

- 用户在 GitHub 上又改了什么 → `git fetch github-private && git pull --ff-only github-private refactor/p1-godclass`
- working tree 不 clean → `git status` 看是不是手工改

**不要**自动 reset/discard，先 push back 问用户。

---

**snapshot 时间**：2026-05-16 00:35，M1.1.8d 完成 + filterNot fix push 后、M1.1.8e 开干前
**有效期**：直到 M1.1.8e 启动（或 24h 过期，以先到为准）
