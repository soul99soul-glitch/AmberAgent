# AmberAgent 重构 Handoff — 2026-05-15

> **Cold-start 文档**：任何新 session（AI 或人）应能仅凭这一份文档接手，不需要回顾历史对话。
> 上一份 handoff：`docs/AMBERAGENT_HANDOFF_2026-05-04.md`（已过期，本文档替代）

---

## 0. TL;DR（30 秒读完）

- AmberAgent 是 RikkaHub 的深度 fork，正在做**上下半场**重构计划（plan v2）。今天是 Day 1。
- 完成：**Phase -1（表层品牌清理）+ Phase 0（死代码清理 + 性能 baseline）+ Phase 1 Day 1（调研 + 蓝图 + PoC + M1.1.1 UIPrefs 拆分）**。
- 下一步：**M1.1.2 SearchPrefs**，严格按 M1.1.1 pattern 走。预计 0.5 天。
- 没有 blocker。WIP 分支 `refactor/p1-godclass` 已 push 到 github-private 作备份。

---

## 1. 项目当前状态机

```
project ─┐
         ├── main ─────── github-private/main @ e33a5e08
         │                  Phase -1 + 0 + baseline 全部 ship 完
         │
         └── refactor/p1-godclass ── github-private/refactor/p1-godclass @ 4a675745
                              ↑
                              当前位置 — Phase 1 进行中
                              已完成: 调研 + 蓝图 + PoC + M1.1.1 (UIPrefs)
                              下一步: M1.1.2 SearchPrefs
```

**Phase 1 整体进度**：8 个 milestone 中完成 0.5 个（M1.1.1，UIPrefs 拆出但 SettingsStore 现有读写未删，等 M1.1.8 时统一替换）。预计还要 3-4 周完成 Phase 1。

---

## 2. Git 远程 / 分支 / 命名约定

### Remotes
| 名字 | URL | 用途 |
|---|---|---|
| `github-private` | https://github.com/soul99soul-glitch/AmberAgent.git | 主仓（用户自己的，所有 Phase 工作推这里） |
| `origin` | https://github.com/rikkahub/rikkahub.git | **上游 rikkahub** — 仅作 cherry-pick 安全修复来源，不再自动合并 feature/UI 改动 |

### Branches 清单
| 分支 | 状态 | 用途 |
|---|---|---|
| `main` | active，远程 default branch | Phase 完成后 ff-merge 到这里 |
| `refactor/p1-godclass` | **当前 active**，已 push 远程 | Phase 1 工作分支 |
| `master` | 废弃（落后 origin/master 34 commits，不动） | 留作历史标记 |
| `amberagent/ui-m3-expressive` | local worktree（`rikkahub-claude/`） | 独立 UI 实验，不动 |
| `amberagent/ui-pulse-redesign` | 远程 + local worktree（`rikkahub-pulse/`） | 独立 UI 实验，不动 |
| `amberagent/v0.5-wip` | local，不动 | 历史分支 |

### 分支工作流
- 每个 Phase 一条短分支：`refactor/p-1-rebrand` / `refactor/p-0-cleanup` / `refactor/p1-godclass`（已切完前两个，第三个进行中）
- 完成 Phase 后 `git checkout main && git merge --ff-only <phase-branch>`（单 commit 时 ff，多 commit 时 squash）
- 然后删 phase 分支：`git branch -d <phase-branch>`
- 远程 phase 分支可以 push 作 WIP 备份（如当前 `refactor/p1-godclass`），Phase 完成后远程也删

### Commit 作者
**已设 git config --global**：
```
user.name = Arquiel
user.email = soul99soul@gmail.com
```

### Commit message 习惯
- Subject < 70 字符
- 用 conventional commits prefix：`refactor(p1):` / `refactor(p-0):` / `docs(perf):` / `docs(p1):`
- Body 列具体改动 + 验证结果（构建 / 测试 / 装机）+ 不动的禁区 + 下一步
- 用 HEREDOC 写 message 保留格式（git commit -m "$(cat <<'EOF' ... EOF)"）

---

## 3. 开发环境

### 仓库
```
/Users/arquiel/Downloads/AI/rikkashit/rikkahub
```
包含 git worktree：
- `rikkahub-claude/` — 独立 worktree（amberagent/ui-m3-expressive）
- `rikkahub-pulse/` — 独立 worktree（amberagent/ui-pulse-redesign）

### JDK
**用户机器没装 JDK**（macOS）。Gradle wrapper 自动下载到：
```
/Users/arquiel/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home
```

**每次跑 gradle 都要 export**：
```bash
export JAVA_HOME="/Users/arquiel/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

### Android SDK + ADB
```
ADB=~/Library/Android/sdk/platform-tools/adb
```

### 测试设备
**Nothing A069**（Phone (3a) 或类似，Snapdragon 7s Gen 4 "volcano"）
- adb device ID: `002803623002594`
- Android 16 (SDK 36) / arm64-v8a / density 480
- **作为 Phase 1+ 的性能 baseline 主设备**（plan 提到"卡顿的低端机"，这台符合）

前一台 OPPO Find N5（PMA110，旗舰，density 640）也连接过，但**不能用作 baseline**（旗舰太顺，看不出卡顿）。如果它再次连上，**忽略**或断开它，确保只 Nothing A069 在连。

### 构建 / 装机命令清单
**关键**：用户的应用 ID 是 `me.rerere.amberagent.notion`（notion **buildType**，不是 flavor — plan v2 原写错），所以 task 名是 `:app:assembleNotion`，**不是** `:app:assembleNotionDebug`。

```bash
# 构建 notion debug APK（实际是 buildType notion，applicationIdSuffix = .notion）
./gradlew :app:assembleNotion

# 单元测试（unit test 只有 debug variant，其他 buildType 没 test source set）
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintNotion

# APK 输出（universal，可装机）
app/build/outputs/apk/notion/app-universal-notion.apk

# 装机命令（保留用户数据）
$ADB install -r app/build/outputs/apk/notion/app-universal-notion.apk

# 验证升级路径（firstInstallTime 应保留，lastUpdateTime 应更新）
$ADB shell dumpsys package me.rerere.amberagent.notion | grep -E 'versionName|lastUpdateTime|firstInstallTime'

# 启动 app
$ADB shell am start -n me.rerere.amberagent.notion/me.rerere.rikkahub.RouteActivity

# 看 app 进程
$ADB shell 'ps -A | grep amberagent'

# 看 crash logcat
$ADB logcat -d -t 50 'AndroidRuntime:E *:S'

# 强杀（baseline 录冷启动前用）
$ADB shell am force-stop me.rerere.amberagent.notion
```

---

## 4. 完成 milestones 时间线

### Phase -1 — 表层独立化（commit 9f479ff4）
> 目标：产品外观完全 AmberAgent，老用户无感

| 改动 | 详情 |
|---|---|
| 6 locale strings.xml | 删孤儿字符串 `rikkahub_provider_description`（RikkahubProvider 类已在 fork 早期被移除，仅剩孤儿 string entry） |
| themes.xml | `Theme.Rikkahub` → `Theme.amberagent`（小写，用户选择） |
| AndroidManifest.xml | 4 处 `@style/Theme.Rikkahub` → `@style/Theme.amberagent` |
| 3 份 README | 全删 upstream attribution: DeepWiki/zread badges、Download 链接、Sponsors (Aihubmix)、Donate (Patreon/爱发电)、Star History、上游 PR 政策、Features 段 |
| **不动**：namespace `me.rerere.rikkahub` / DataStore key / 深链 scheme / shortcuts FQN / `REMOVED_DEFAULT_PROVIDER_IDS` / `search/SearchService.kt` 的 `@SerialName("rikkahub")` |

**结果**：11 文件改，+6/-160 行。assembleNotion ✓，装机 ✓，独立 review subagent PASS。

### Phase 0 — 死代码清理 + 性能 baseline（commits 09c012cd + e33a5e08）
> 目标：消除明确死代码 + 建立性能基线

| 改动 | 详情 |
|---|---|
| 删 3 文件（死代码） | `SponsorAPI.kt` / `RikkaHubAPI.kt` / `Sponsor.kt`（grep 确认 0 调用点） |
| 删 Retrofit | `libs.retrofit*` 依赖 + `Retrofit` / `RikkaHubAPI` / `SponsorAPI` single 注入（DataSourceModule.kt） |
| 替换 Pebble | TemplateTransformer.kt 重写（80→49 行），用 `{{ var }}` 简单 `String.replace`；删 `AssistantTemplateLoader` 类 + `PebbleEngine` single + `PreferencesStore.invalidateAll`；libs.pebble 整删 |
| 保留 | `SPONSOR_ALERT_DISMISSED_AT` DataStore key + `sponsorAlertDismissedAt` Settings 字段（老用户兼容）|
| **plan v2 误判修正**：原说"speech 模块替代 tts"——错的，speech 模块根本不存在，tts/ 仍是活模块；推到 Phase 1 评估 |

**结果**：8 文件改，+22/-141 行 + 3 个删文件 = -300+ 行总净减。**APK 95→92MB（-3MB）**。assembleNotion ✓，装机 ✓，review PASS（3 pre-existing test failures 已确认无关）。

#### Baseline 数据（commit e33a5e08）
设备 Nothing A069，30s perfetto trace，关键数字：

| 指标 | 值 | 评估 |
|---|---|---|
| **冷启动 TTFP** | **3.32 秒** | 中端机偏慢，目标 < 1.5s |
| **APK dex 加载** | **2.35 秒** | 占冷启 70%（92MB 直接代价） |
| **Firebase 全套主线程** | **~480ms** | cls 193 + sessions 180 + Firebase 84 + 其他 |
| **30s 帧率** | 1784 frames, **9.8% jank** | 偏高 |
| **最慢帧** | 1079ms | 启动期首帧 |
| **"切换界面卡"** | 主线程后 27s 0 个 >5ms slice | 卡顿**非 main-thread CPU bound**，疑 Compose 高频重组 / GPU / JIT — Phase A deep dive |

详细报告：`docs/perf-baseline/2026-05-15/cold-start.md`
原始 trace（66MB）**不入 git**（`.gitignore` 加了 `*.pftrace` 排除），需要时重录。

**待补 baseline trace**（按 `docs/perf-baseline/2026-05-15/README.md` 命令模板）：
- Trace 2 滚动 — 需要 50+ 消息对话历史
- Trace 3 流式响应 — 需要配 Provider
- Memory Profile — 需要 Android Studio Profiler

### Phase 1 Day 1（commits 2177ddb4 → 4a675745，在 refactor/p1-godclass）
> 目标：先调研后动手，最小风险拆出 UIPrefs 验证设计

| commit | 内容 |
|---|---|
| 2177ddb4 | `docs(p1): 拆分蓝图 v1` — 7 域 Prefs + 5 组件 + 3 Orchestrator，8 个 milestone（M1.1→M1.8）总图 |
| af587279 | `docs(p1): AssistantPrefs PoC` — 方案 B（聚合层做清理）可行性验证，接口设计 + 4 边界 + 测试草案 |
| 4a675745 | `refactor(p1): M1.1.1 — 引入 UIPrefs 作为并行读取通道` |

#### M1.1.1 UIPrefs 详情
- 新建 `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/UIPrefs.kt`（71 行）
  - `data class UIPrefsData`（6 字段：dynamicColor / themeId / developerMode / displaySetting / launchCount / sponsorAlertDismissedAt）
  - `class UIPrefs(context, scope)` 提供 `flow: StateFlow<UIPrefsData>` + `update(transform): suspend`
  - **复用** `SettingsStore.Companion` 已有的 6 个 PreferencesKey（key 名不变 → 老数据兼容）
- 改 `app/.../data/datastore/PreferencesStore.kt:69` — `private val Context.settingsStore` → `internal`（让 `prefs/` 子目录跨 package 可见，同一 DataStore 实例）
- 改 `app/.../di/DataSourceModule.kt` — 加 `single { UIPrefs(get(), get()) }`
- **未改**：SettingsStore 现有 6 UI 字段读写（line 241-244 + 486-489 + 549-550），settingsFlow API，所有 caller

**结果**：UIPrefs 作为并行读取通道引入，0 caller 改动，0 回归面。assembleNotion 43s ✓，装机 ✓，logcat 无 crash，review subagent PASS（5 个非阻塞 warning）。

---

## 5. Phase 1 路线图（蓝图全图）

详细蓝图：[`docs/refactor-p1-blueprint.md`](refactor-p1-blueprint.md)（224 行）

```
M1.1 — PreferencesStore.kt 拆分 (1-1.5 周)  ← 当前在 M1.1.1 / 7
  ├── M1.1.1 UIPrefs (0.5d)              ✅ DONE @ 4a675745
  ├── M1.1.2 SearchPrefs (0.5d)          ← 下一步
  ├── M1.1.3 AgentPrefs (1d，体量大)
  ├── M1.1.4 ProviderPrefs (1d，被 ChatPrefs 单向依赖)
  ├── M1.1.5 ChatPrefs (1d)
  ├── M1.1.6 ExtensionPrefs (1.5d，与 AssistantPrefs 互引)
  ├── M1.1.7 AssistantPrefs (2d，聚合中枢)
  └── M1.1.8 PreferencesStore 薄包装（聚合层）+ 删原 god class (1d)
                                          ↑ 这里必须兑现 UIPrefs 单测（M1.1.1 留下的债）

M1.2 — Orchestrator 三件套 (3-4d)
  ├── BranchMessageOrchestrator      （最简，不调 GenerationHandler）
  ├── RegenerateMessageOrchestrator
  └── SendMessageOrchestrator

M1.3 — ChatService + GenerationHandler 拆分 (1.5-2 周)
  ├── ConversationStateHolder (LOW)
  ├── MessageTransformPipeline (LOW)
  ├── ContextPlanner (MED)
  ├── StreamingPipeline (MED)
  └── ToolApprovalCoordinator (HIGH)

M1.4 — Tools 4 god 文件 (1 周)
M1.5 — ModelCouncil + AppModule 拆 (3-4d)
M1.6 — Repository 解耦 (3-4d)
M1.7 — tts/ 模块去留评估 (1d) ← 用户决策 B: 改名 me.rerere.tts → me.rerere.amberagent.speech，移到 :speech
M1.8 — Phase 1 验收（assembleNotion + 单测 + review gate + 装机 + Perfetto 对比 baseline）
```

### 用户已拍板的决策（蓝图 + PoC review 时定的）

| Q | 用户选 | 含义 |
|---|---|---|
| 整体方向 | OK | 8 milestone 顺序 + 3-4 周接受 |
| M1.1 vs M1.2 谁先 | C | 保持蓝图顺序 M1.1 → M1.2 → M1.3 |
| tts/ 处理 | B | M1.7 改名 + 移到 :speech 模块 |
| 测试投入 | A | 5 新组件 + 3 Orchestrator 100% 单测 |
| AssistantPrefs PoC | A | 先做 24h PoC（已完成，方案 B 可行） |
| M1.1.1 目录 | B | `app/.../data/datastore/prefs/`（子目录） |
| M1.1.1 接口 | B | 单 class，不分 interface |
| M1.1.1 方法迁不迁 | B | 不迁，PreferencesStore 现有方法留着委托 |

---

## 6. M1.1.2 起步指南（**新 session 接手最重要的一节**）

### 6.1 SearchPrefs 字段清单

10 个字段（按 PreferencesStore.kt 字段顺序）：

| Settings 字段 | DataStore key | 类型 | 默认值 | 行号(PreferencesStore.kt) |
|---|---|---|---|---|
| searchServices | SEARCH_SERVICES | stringPreferencesKey | `[SearchServiceOptions.DEFAULT]` (单元素 list) | 120 |
| searchCommonOptions | SEARCH_COMMON | stringPreferencesKey | `SearchCommonOptions()` | 121 |
| searchServiceSelected | SEARCH_SELECTED | intPreferencesKey | `0` | 122 |
| searchEnabledServiceIds | SEARCH_ENABLED_SERVICE_IDS | stringPreferencesKey | `searchServices.take(1).map { it.id }` | 123 |
| searchBuiltinDuckDuckGoEnabled | SEARCH_BUILTIN_DUCKDUCKGO_ENABLED | booleanPreferencesKey | `true` | 124 |
| searchBuiltinBingEnabled | SEARCH_BUILTIN_BING_ENABLED | booleanPreferencesKey | `true` | 125 |
| searchBuiltinJinaEnabled | SEARCH_BUILTIN_JINA_ENABLED | booleanPreferencesKey | `true` | 126 |
| searchBuiltinWikipediaEnabled | SEARCH_BUILTIN_WIKIPEDIA_ENABLED | booleanPreferencesKey | `true` | 127 |
| searchBuiltinHackerNewsEnabled | SEARCH_BUILTIN_HACKERNEWS_ENABLED | booleanPreferencesKey | `true` | 128 |
| searchGoogleWebViewFallbackEnabled | SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED | booleanPreferencesKey | `true` | 129 |

读路径：`PreferencesStore.kt:245-256`（部分字段有 distinct/filter，注意细节）
写路径：`PreferencesStore.kt:514-531`（注意 line 516-525 的 `searchServiceSelected coerceIn` 和 `searchEnabledServiceIds filter` 逻辑 — 这些是 cleanup，留聚合层做 M1.1.8 处理）

⚠️ **特别注意 line 245-250 的 `searchServices`/`searchServiceSelected`/`searchEnabledServiceIds` 的 build 逻辑** — 它们有跨字段依赖（serviceSelected 索引依赖 searchServices 列表长度，enabledServiceIds 依赖 searchServices ID 集合）。**M1.1.2 拆 SearchPrefs 时这些 cleanup 留 SettingsStore 原 .map 阶段不动**，只 mirror 读 raw 数据。M1.1.8 聚合层时再决定 cleanup 怎么搬。

### 6.2 严格按 M1.1.1 pattern

1. 新建 `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/SearchPrefs.kt`
   - `data class SearchPrefsData(...)` — 10 字段
   - `class SearchPrefs(context: Context, scope: AppScope)` — flow + update
   - 复用 `SettingsStore.Companion.SEARCH_*` 9 个 key（已有）
2. `DataSourceModule.kt` 加 `single { SearchPrefs(get(), get()) }`
3. **不动**：SettingsStore 现有 search 字段读写、所有 caller、其他 6 个 Prefs 字段域
4. 构建 `:app:assembleNotion` 必须通过
5. 装机：`adb install -r ... && 验证 firstInstallTime 保留`
6. commit on `refactor/p1-godclass` 分支：
   ```
   refactor(p1): M1.1.2 — SearchPrefs 并行读取通道
   ```
7. **派 review subagent**（M1.1.1 review prompt 模板见这份 handoff 第 11 节）
8. review PASS 后 push: `git push github-private refactor/p1-godclass`

### 6.3 估时 0.5 天，单 session 可完

---

## 7. 必须知道的坑（plan v2 的误判 + 项目历史遗留）

### 坑 1：`speech` 模块不存在
plan v2 写"tts/ 已被 speech 替代"，**错的**。顶层模块清单：
```
ai/ app/ common/ document/ highlight/ locale-tui/ search/ tts/ web/ web-ui/
```
没有 `speech/`。tts/ 仍是活模块，被 5+ 文件直接依赖（AppModule / PreferencesStore / TTS.kt hook / TTSController.kt UI / SettingTTSPage.kt / TTSProviderConfigure.kt）。

**已修 plan**：`/Users/arquiel/.claude/plans/plan-users-arquiel-downloads-ai-rikkash-splendid-cake.md` 里 Phase 0 第 1 项 "删除 tts/" 已改成"plan v2 错记，推到 Phase 1 评估"。

**M1.7 用户决策 B**：改名 `me.rerere.tts` → `me.rerere.amberagent.speech` + 移到 `:speech` 模块。

### 坑 2：task name 错位
plan v2 原写 `assembleNotionDebug` / `testNotionDebugUnitTest` / `lintNotionDebug`，**错的**。`notion` 是 buildType（不是 flavor），所以：
- `assembleNotion` ✓
- `testDebugUnitTest` ✓（unit test 只有 debug variant，其他 buildType 没 test source set）
- `lintNotion` ✓

**已修 plan** 8 处错误的 task name。

### 坑 3：文件名/类名错位 — SettingsStore vs PreferencesStore
- 文件名：`app/.../data/datastore/PreferencesStore.kt`（1131 行）
- 类名：`SettingsStore`（line 79）
- 我所有调研报告/蓝图/PoC 都说"拆 PreferencesStore 1131 行"指代它，实际类名是 SettingsStore。
- **Phase 3 才统一名字**，Phase 1 不动这个名字（接受错位）。

### 坑 4：plan v2 三处错误对应的修正都已经 commit 到 plan 文件
plan 文件不在 git tracked，是 `/Users/arquiel/.claude/plans/`，用户私有 work plan。修过的内容下次开 session 时会自动读到。

### 坑 5：APK 体积 1MB 抖动
- Phase -1/0 时 95→92MB（-3MB，删 Retrofit + Pebble 真实减少）
- M1.1.1 后 93MB（vs 92MB +1MB）— **不是 regression**，是 R8/Kotlin compiler 同源不同 build 的非确定性，几十行 Kotlin + Koin 注册实际仅几 KB 字节。Review subagent W3 confirmed。

### 坑 6：M1.1.1 留了一个技术债，M1.1.8 必须兑现
- UIPrefs.kt 没有 unit test（commit message 写明推到 M1.1.8）
- UIPrefs.update 是 RMW（先 read flow.value，再 transform，再 edit）— **非 atomic**。当前现状跟 SettingsStore.update(settings) 一致（不是新引入的问题），但 M1.1.8 聚合层引入时**应改成 `dataStore.edit { p -> val ui = read(p); val next = transform(ui); write(p, next) }` 真正的 atomic RMW**。
- M1.1.8 的 acceptance criteria 必须包含：「UIPrefs/SearchPrefs/AgentPrefs/ProviderPrefs/ChatPrefs/ExtensionPrefs/AssistantPrefs 7 个全部有单测」+「update 是 atomic RMW」+「聚合层包装 + 删原 god class」。

### 坑 7：Pebble 语法 narrowing
Phase 0 用简单 `{{ var }}` Kotlin replace 替代了 Pebble。**如果用户曾在 messageTemplate 里写过 `{% if %}` / `{% for %}` / `| filter`**，会渲染成字面文本（power user 影响，普通用户无感）。Phase 0 review subagent 提到，未加 release note。

### 坑 8：Theme 命名小写
`Theme.amberagent`（全小写 amberagent）—— **不是** `Theme.AmberAgent`。这是用户选择（前面对话拍板 "Theme.amberagent"）。Android 惯例是 PascalCase，但用户偏好 lowercase 跟 scheme `amberagent` 一致。后续保持 lowercase。

---

## 8. 设计文档索引

git tracked 的关键文档：

| 文件 | 内容 | 何时读 |
|---|---|---|
| **`docs/AMBERAGENT_HANDOFF_2026-05-15.md`** | **本文档（你现在读的）** | 任何接手时第一份 |
| `docs/refactor-p1-blueprint.md` | Phase 1 完整拆分蓝图（8 milestone） | Phase 1 任何工作前 |
| `docs/refactor-p1-assistantprefs-poc.md` | 方案 B（聚合层做清理）PoC 设计 | M1.1.7 / M1.1.8 拆 AssistantPrefs 时 |
| `docs/perf-baseline/2026-05-15/README.md` | Perfetto trace 录制 step-by-step（3 个 trace） | 要跑 baseline trace 时 |
| `docs/perf-baseline/2026-05-15/cold-start.md` | 已完成的 cold-start baseline 数据 + 分析 | 比较 Phase 1 末 / Phase 2 末性能时 |

**老 handoff**（已过期，仅作历史参考）：
- `docs/AMBERAGENT_HANDOFF_2026-05-04.md` — Pre-Phase -1 状态
- `docs/CODEX_HANDOFF_*.md` / `docs/CLAUDE_UI_HANDOFF_*.md` — UI 重设计相关，跟当前重构计划无关

**用户私有 plan**（不入 git）：
- `/Users/arquiel/.claude/plans/plan-users-arquiel-downloads-ai-rikkash-splendid-cake.md` — Plan v2 上下半场框架（已修正 task name + tts/ 误判）

**用户 memory**（不入 git，自动加载）：
- `/Users/arquiel/.claude/projects/-Users-arquiel-Downloads-AI-rikkashit/memory/MEMORY.md` 索引
- `build_install_notion_flavor.md` — assembleNotion + 装机命令（早期就对，不要再错记）
- `process_phase_review.md` — 每个 milestone 末派 review subagent gate（**硬性要求**，不能跳）

---

## 9. 5 个 CRITICAL 风险点（蓝图 + PoC 提的，必看）

### R1 — AssistantPrefs 聚合中枢（M1.1.7）
Assistant 引用 modeInjectionIds / lorebookIds / mcpServers / quickMessageIds / chatModelId **跨 5 个 Prefs**。拆分时 ID validation 复杂。**已通过 PoC 验证方案 B 可行**：各 Prefs 独立，聚合层做 cleanup。但 M1.1.7 实施时仍是 Phase 1 最复杂任务。

### R2 — V3Migration 跨域
`PreferenceStoreV3Migration` 把 QuickMessages 从 `Assistant.quickMessages`（旧字段）提取到全局 `QUICK_MESSAGES`，并为各消息生成 UUID。拆分时这个 migration 跨 AssistantPrefs + ExtensionPrefs，需要 synchronized initialization。M1.1.6 + M1.1.7 拆时小心。

### R3 — Image Model Seeding 标志
`SEEDED_IMAGE_MODELS_V1` 驱动 per-load backfill（gpt-image-2 / nano-banana-2）。如果跟 ProviderPrefs 解绑了，新 ProviderPrefs 读取时找不到标志，会重复 seed。M1.1.4 ProviderPrefs 拆分时**必须**把 SEEDED_IMAGE_MODELS_V1 跟 providers 一起搬。

### R4 — 取消传播脆弱（Phase 1 M1.3）
ChatService 手握 Job，调用 `session.setJob()`，但 GenerationHandler **无 cancel 权限**。如果 GenerationHandler 内部漏 ensureActive，cancellation 不会立即中止。M1.3 拆 ToolApprovalCoordinator + StreamingPipeline 时**必须显式 `ensureActive()` 检查**。

### R5 — 工具执行并发竞态（M1.3 ToolApprovalCoordinator）
`pendingTools.isEmpty()` 检查与 `executeBatch` 之间**非原子**。M1.3 实现 ToolApprovalCoordinator 时应加 Mutex 保护。

---

## 10. 当前 open questions / 待用户决策

(写本文档时无新 open question — 上面的 Q1-Q5 + Q1-3 微决策都已答完)

可能的将来 push back（按蓝图节奏会出现）：

- **Phase 1 中**：M1.7 tts/ 改名实施细节（Room 数据库 entity 路径变化要不要 migration？）
- **Phase 1 末**：是否要发版 AmberAgent 1.0 stable，dogfood 2-4 周后才启动 Phase 2？plan v2 说这是"稳定锚点"，但 1 人开发可能想跳过节省时间
- **下半场启动前**：Phase A 重新 profile 后，决定 Phase B 候选模块（SSE / PDF / Memory / Markdown 4 个候选，按 Phase A 数据排序）

---

## 11. Review subagent gate prompt 模板（每个 milestone 末派一次）

**用户硬性要求**：每个 milestone 末必须派独立 review subagent，gate 不过不进下一步。

模板（M1.1.X 替换为具体 milestone 号 / 字段域名）：

```
独立 review M1.1.X（XxxPrefs 引入）的代码改动。这是 AmberAgent Phase 1 god class 拆分的第 X 步。

仓库: /Users/arquiel/Downloads/AI/rikkashit/rikkahub
分支: refactor/p1-godclass
待 review commit: HEAD（subject `refactor(p1): M1.1.X — 引入 XxxPrefs`）

Phase 1 上下文（必读）:
- 目标: 拆 SettingsStore (在 PreferencesStore.kt 1131 行) → 7 个域 Prefs
- 蓝图: docs/refactor-p1-blueprint.md
- PoC: docs/refactor-p1-assistantprefs-poc.md（方案 B 聚合层做清理）
- M1.1.X 范围: 仅引入 XxxPrefs 作为**并行读取通道**，SettingsStore 现有读写**完全不动**，所有 caller 0 改动

用户决策（B B B）:
- 目录: prefs/ 子目录
- 单 class 不分 interface
- 不迁现有方法

你的工作:
1. 字段映射正确性（XxxPrefs N 个字段 ↔ SettingsStore.Companion 的 PreferencesKey 一对一）
2. 默认值与原版一致
3. SettingsStore 现有读写未被破坏（grep 验证）
4. UIPrefs/XxxPrefs/SettingsStore 共享同一 DataStore 实例（非 second instance）
5. visibility 改动安全（如有）
6. AppScope/Koin 注入正确
7. 蓝图约束遵循（单文件 < 500 行 / 单测延期到 M1.1.8 等）
8. 未触及禁区（namespace / DataStore key 名 / 类名 SettingsStore / 其他未拆字段）
9. 构建/装机验证（assembleNotion / firstInstallTime 保留）

输出 ## 通过项 / ## 警告 / ## 阻塞项 / ## Verdict (PASS/FAIL)
精炼，路径+行号。
```

---

## 12. 接手 step-by-step（下次新 session 第一件事）

### A. 同步状态
```bash
cd /Users/arquiel/Downloads/AI/rikkashit/rikkahub
git fetch github-private
git checkout refactor/p1-godclass
git pull github-private refactor/p1-godclass
git log --oneline -10
# 应该看到 HEAD = 4a675745 refactor(p1): M1.1.1 — 引入 UIPrefs ...
```

### B. 读关键文档（按优先级）
1. **本文档 (`docs/AMBERAGENT_HANDOFF_2026-05-15.md`)** — 全图
2. `docs/refactor-p1-blueprint.md` — Phase 1 完整规划
3. `docs/refactor-p1-assistantprefs-poc.md` — 最复杂任务的设计（提前看，M1.1.7 用）
4. 上次 M1.1.1 commit diff：`git show 4a675745`

### C. 装环境
```bash
export JAVA_HOME="/Users/arquiel/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB devices  # 应看到 002803623002594 device (Nothing A069)
```

### D. 跑一次基线确认环境正常
```bash
./gradlew :app:assembleNotion --console=plain | tail -10
# 期望: BUILD SUCCESSFUL，APK 在 app/build/outputs/apk/notion/app-universal-notion.apk
```

### E. 起步 M1.1.2 SearchPrefs
看本文档 §6（"M1.1.2 起步指南"）。0.5 天估时。

### F. milestone 完成时
1. commit on refactor/p1-godclass
2. **派 review subagent**（模板见 §11）
3. review PASS 后 `git push github-private refactor/p1-godclass` 作 WIP 备份
4. M1.1 全部 7+1 个 sub-milestone 完成后，整体 ff-merge 到 main + push + 删 phase 分支

---

## 13. 常用命令备查

```bash
# 看 phase 进度
git log --oneline main..refactor/p1-godclass

# Phase 完成时 ff-merge + push + 清理
git checkout main && git merge --ff-only refactor/p1-godclass && git push github-private main && git branch -d refactor/p1-godclass && git push github-private :refactor/p1-godclass

# 重录 baseline trace（Phase 1 末做，跟 5/15 数据对比）
$ADB shell am force-stop me.rerere.amberagent.notion
cat <<'CFG' | $ADB shell 'perfetto --txt -c - -o /data/misc/perfetto-traces/cold-start.pftrace'
buffers: { size_kb: 131072 }
duration_ms: 30000
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      atrace_categories: "view"
      atrace_categories: "wm"
      atrace_categories: "am"
      atrace_categories: "gfx"
      atrace_categories: "input"
      atrace_categories: "binder_driver"
      atrace_apps: "me.rerere.amberagent.notion"
    }
  }
}
data_sources { config { name: "android.surfaceflinger.frame" } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
CFG
# 操作 app（点图标启动 + 切换页面 30 秒）
$ADB pull /data/misc/perfetto-traces/cold-start.pftrace docs/perf-baseline/$(date +%Y-%m-%d)/cold-start.pftrace
# 分析（trace_processor 已装在 ~/.local/bin/）
~/.local/bin/trace_processor query docs/perf-baseline/.../cold-start.pftrace "<SQL>"

# 跑测试
./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -20
# 3 个 pre-existing failures 可忽略:
# - GenerativeUiPlannerTest.directDrawingRequestsDisableToolDetours
# - GenerativeUiPlannerTest.toolMediatedVisualRequestsWarnAgainstProgressWidgets
# - DefaultProvidersTest.default providers are curated and deletable

# 看远程状态
gh repo view soul99soul-glitch/AmberAgent --json defaultBranchRef
git branch -vv
```

---

## 14. 如果状态不一致（紧急恢复）

如果发现远程 / 本地分支不一致或者环境有问题：

1. **远程 main 是真相**：`github-private/main @ e33a5e08`，所有 Phase -1/0/baseline 都在这里
2. **远程 refactor/p1-godclass 是 Phase 1 WIP**：`github-private/refactor/p1-godclass @ 4a675745`
3. **本地恢复**：
   ```bash
   git fetch github-private
   git checkout main && git reset --hard github-private/main
   git checkout refactor/p1-godclass 2>/dev/null || git checkout -b refactor/p1-godclass github-private/refactor/p1-godclass
   git reset --hard github-private/refactor/p1-godclass
   ```

---

## 15. 状态变更追加（rolling updates，从 Day 1 收尾后增量记录）

### 2026-05-15 Day 1 后期 — main 推进 21 commit + phase 分支 rebase

**触发**：用户在 GitHub 上做了"小改动"实际是 21 commit / +3486-950 行 / 版本号 1.9.0 / 350。涉及 chat / context / board / radar 多个功能区。

**rebase 行动**：
- main: `e33a5e08` → `f4d03954`（fast-forward 拉下 21 commit）
- refactor/p1-godclass: 4 个 phase commit rebase 到新 main 之上，**0 冲突**
  - 旧 HEAD: `3d9a9d84` (Day 1 收尾)
  - 新 HEAD: `18bcb40b`（rebase 后，远程已 force-with-lease push）
- 4 个 phase commit 全部 byte-精确保留（UIPrefs.kt / PreferencesStore.kt:69 / DataSourceModule.kt）
- `:app:assembleNotion` BUILD SUCCESSFUL 1m 6s ✓
- 独立 review subagent PASS

### 21 commit 对 Phase 1 进度的影响

| god class | 21-commit diff | M1.1 影响 |
|---|---|---|
| **PreferencesStore.kt** | 0 行 | ✅ 完全不受影响，M1.1 蓝图 100% 仍适用 |
| GenerationHandler.kt | 0 行 | ✅ |
| 4 个 god Tools (Local/SystemAccess/FeishuOffice/WebMountPrimitive) | 0 行 | ✅ |
| ModelCouncilManager.kt | 0 行 | ✅ |
| **ChatService.kt** | 62 行 | ⚠️ 行数：2261 → **2303**（+42 净）。M1.3 调研数据小漂移 |
| AppModule.kt | 27+/16- 净 +11 | ⚠️ 行数：664 → **663**（-1）。M1.5 影响轻微 |
| **ConversationContextEngine.kt** | **+171 行** | ⚠️ 大改动（streaming summary + post-compact ring drift 等），M1.3 ContextPlanner 拆分要重新调研 |
| AppDatabase.kt | +13 行（加 entity + version 25 + AutoMigration 24→25）| ✅ 跟 DataStore preferences 独立两套存储，不影响 UIPrefs/SettingsStore |
| DataSourceModule.kt | +8 行（加 docSubscriptionDao + docChangeLogDao single） | ✅ rebase 已合并，跟 UIPrefs single 无重叠 |

### ⭐ M1.3 ContextPlanner 重大发现 — 蓝图 §B 需小修

**蓝图 §B 列 "ContextPlanner" 为 M1.3 待新抽的 5 组件之一，但实际上：**

- `app/src/main/java/me/rerere/rikkahub/data/context/ConversationContextPlanner.kt` 已经存在（**231 行 object**）
- 同目录还有：
  - `ConversationContextEngine.kt`（**493 行**，21 commit 后）
  - `ContextFootprintEstimator.kt`（21 commit +132 行）

**现有 `ConversationContextPlanner` object 职责**：
- ✓ Token 估算
- ✓ Compaction plan
- ✗ 系统提示构建 — 仍在 ContextEngine 里
- ✗ Memory recall 注入 — 仍在 ContextEngine 里

**M1.3 调整建议**（开 M1.3 时必须先做）：
1. 花 1-2h 重新调研 `data/context/` 目录现状（用 Explore subagent）
2. 把蓝图 §B 中 "ContextPlanner" 改写为 **"扩展现有 ConversationContextPlanner object"** 而不是 "新抽"
3. 评估 ContextEngine 是否要彻底拆，或者只把"系统提示构建" + "Memory recall 注入"从 ContextEngine 迁到 ContextPlanner
4. 把 ContextEngine 21 commit 加的 +171 行（streaming summary / post-compact ring drift / CJK token weight / fingerprint cap）作为 **characterization test 基线** — M1.3 拆分时不能误改这些近期 fix

### 当前最新 HEAD 状态（接续 §1 项目状态机）

```
github-private/main                  f4d03954  fix(chat): single boundary marker
                                    (21 commit ahead of e33a5e08)
github-private/refactor/p1-godclass  18bcb40b  docs(handoff): 完整接手文档 (rebase 后)
                                    de7eb8e3  refactor(p1): M1.1.1 — UIPrefs
                                    a4cd9af3  docs(p1): AssistantPrefs PoC
                                    90292404  docs(p1): 拆分蓝图 v1
                                    f4d03954  ← (新基底，main HEAD)
```

### M1.1.2 起步 (§6) **依然有效，不受 21 commit 影响**

`SearchPrefs` 字段、SettingsStore 读写路径、PreferencesStore.kt 行号 — 全部未变。可以按 §6 指引直接开 M1.1.2。

---

**文档版本**：2026-05-15 Day 1 收尾 + 后期 rebase 追加
**下次更新**：开 M1.3 前必须完成 `data/context/` 重新调研后小修 §5 / §6 蓝图引用
