# Session Snapshot — 2026-05-15 PM（pre-compact）

> 这份文档是**"刚才那一刻的精确状态"快照**，作为压缩后 self-handoff 用。
> 完整背景见 `docs/AMBERAGENT_HANDOFF_2026-05-15.md`（570+ 行 cold-start 全图）。
> 本 snapshot 仅覆盖**handoff 之后发生的事**和**当前对话中悬而未定的事**。

---

## 1. 你（压缩后的我）必须读完的 4 份文档

按顺序：

1. **本文档** — snapshot，最先读
2. `docs/AMBERAGENT_HANDOFF_2026-05-15.md` — cold-start 全图（含 §15 rebase 后更新）
3. `docs/refactor-p1-blueprint.md` — Phase 1 总图（M1.1→M1.8）
4. `docs/refactor-p1-assistantprefs-poc.md` — 方案 B 设计（M1.1.7 用，现在先了解）

---

## 2. 当前精确 git 状态

```
github-private/main                  f4d03954  (user 今天 push 的 21 commit，1.9.0 / 350)

github-private/refactor/p1-godclass  b25867e7  docs(handoff): 追加 — rebase + M1.3 发现
                                     18bcb40b  docs(handoff): 完整接手文档 — Day 1 收尾（rebase 后）
                                     de7eb8e3  refactor(p1): M1.1.1 — UIPrefs 并行读取通道
                                     a4cd9af3  docs(p1): AssistantPrefs 聚合中枢 PoC
                                     90292404  docs(p1): 拆分蓝图 v1
                                     f4d03954  ← 共同祖先（最新 main HEAD）
```

**当前所在分支**：`refactor/p1-godclass`，HEAD = `b25867e7`，**已 push 远程，clean**。

**当前 working tree**：应该 clean（snapshot 文档 commit 后）。`git status --short` 验证。

---

## 3. 上下文：当前 session 做了什么（按时序）

### Day 1（昨天 + 今天早上）
顺序做完三个 Phase 工作（已 ship 到 main）：

| 时段 | 完成 | commit |
|---|---|---|
| Day 1 早段 | Phase -1 表层品牌清理 | `9f479ff4` 已 in main |
| Day 1 中段 | Phase 0 死代码 + Perfetto baseline | `09c012cd` + `e33a5e08` 已 in main |
| Day 1 后段 | Phase 1 调研 + 蓝图 + PoC + M1.1.1 UIPrefs | 4 commit on phase 分支 |
| Day 1 收尾 | 写完整 handoff doc + push 到 phase 分支作 WIP 备份 | `3d9a9d84` (旧 HEAD，pre-rebase) |

### Day 1 后期 — user 在 GitHub 上做了 21 commit（"小改动"实际是大功能推进）

main 从 `e33a5e08` → `f4d03954`，含：
- 5 个 `refactor(radar)` R0-R4（Doc Radar 整合）
- 6 个 chat/context fix（compact summary streaming / post-compact ring drift / CJK token weight）
- 3 个 board fix
- 1.9.0 / 350 版本 bump
- 改动累计 +3486 / -950 行，34 文件

### 今天 PM 我做了

1. ✅ Pull main 到本地（fast-forward 21 commit）
2. ✅ Rebase phase 分支到新 main 之上（4 commit，0 冲突）
3. ✅ 构建 `:app:assembleNotion` BUILD SUCCESSFUL 1m 6s
4. ✅ 派 review subagent verify rebase（PASS，发现 M1.3 ContextPlanner 已存在的重要 nuance）
5. ✅ Force-with-lease push（`3d9a9d84 → 18bcb40b`）
6. ✅ 更新 handoff doc §15 追加 rebase 记录 + M1.3 发现，commit + push（`b25867e7`）
7. **当前位置**：用户已选 **A**（继续做 M1.1.2 SearchPrefs），但**还没说"开干"**

---

## 4. 用户最新立场（你必须知道的对话状态）

### 用户已经拍板的（仍生效）
- Phase 1 整体方向 OK，3-4 周接受
- M1 sub-milestone 按蓝图顺序走（C: M1.1 → M1.2 → M1.3 → ...）
- tts/ 处理：M1.7 改名 `me.rerere.tts` → `me.rerere.amberagent.speech`（**Q3 B**）
- 测试投入：5 新组件 + 3 Orchestrator 100% 单测（**Q4 A**）
- AssistantPrefs PoC：已完成，方案 B 可行（**Q5 A**）
- M1.1.1 微决策（B B B）：prefs/ 子目录 / 单 class / 不迁现有方法

### 用户**最后回复**的内容
我（之前）列了 4 个候选：A M1.1.2 / B M1.3 前置调研 / C M1.7 tts 改名 / D 补 baseline trace。
用户**回的"A"**，意思是选 **A — M1.1.2 SearchPrefs**。

但接着我又问"在当前 session 继续还是新开 session"，**用户回的是要 compact 当前 session**（不新开），并要我写这份 snapshot。

所以：**用户的意图是 compact 当前 session 后继续做 M1.1.2，而不是新开 session**。

---

## 5. M1.1.2 SearchPrefs 起步要做什么

handoff doc **§6** 已经写得很详细。这里浓缩关键点提醒压缩后的你：

### 5.1 字段清单（10 个）
| Settings 字段 | DataStore key | 类型 | 默认值 |
|---|---|---|---|
| searchServices | SEARCH_SERVICES | stringPreferencesKey | `[SearchServiceOptions.DEFAULT]` |
| searchCommonOptions | SEARCH_COMMON | stringPreferencesKey | `SearchCommonOptions()` |
| searchServiceSelected | SEARCH_SELECTED | intPreferencesKey | 0 |
| searchEnabledServiceIds | SEARCH_ENABLED_SERVICE_IDS | stringPreferencesKey | `searchServices.take(1).map { it.id }` |
| searchBuiltinDuckDuckGoEnabled | SEARCH_BUILTIN_DUCKDUCKGO_ENABLED | booleanPreferencesKey | true |
| searchBuiltinBingEnabled | SEARCH_BUILTIN_BING_ENABLED | booleanPreferencesKey | true |
| searchBuiltinJinaEnabled | SEARCH_BUILTIN_JINA_ENABLED | booleanPreferencesKey | true |
| searchBuiltinWikipediaEnabled | SEARCH_BUILTIN_WIKIPEDIA_ENABLED | booleanPreferencesKey | true |
| searchBuiltinHackerNewsEnabled | SEARCH_BUILTIN_HACKERNEWS_ENABLED | booleanPreferencesKey | true |
| searchGoogleWebViewFallbackEnabled | SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED | booleanPreferencesKey | true |

⚠️ **特别注意 PreferencesStore.kt:245-256 的 search 字段 build 逻辑**：
- `searchServices` build 在 reader 里有 distinct 等处理（约 line 196-204，读 SEARCH_SERVICES + deserialize）
- `searchServiceSelected` 在 reader 处理 `coerceIn(0, lastIndex)` 边界检查
- `searchEnabledServiceIds` 在 reader 处理 `filter { id in searchServices.map { it.id } }`

**M1.1.1 pattern 严格遵循**："SearchPrefs 只 mirror raw 数据读取，cleanup/coerce 留 SettingsStore 现有 `.map { settings -> ... }` 阶段不动，到 M1.1.8 聚合层时再决定怎么搬"。

### 5.2 严格按 M1.1.1 pattern

1. 新建 `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/SearchPrefs.kt`
   - `data class SearchPrefsData(...)` 10 字段
   - `class SearchPrefs(context: Context, scope: AppScope)` flow + update API
   - 复用 `SettingsStore.Companion.SEARCH_*` 9 个 key（已经在 PreferencesStore.kt:120-129）
   - **不要重新定义 key**
2. `DataSourceModule.kt` 加 `single { SearchPrefs(get(), get()) }`（在 UIPrefs single 后面）
3. **不动**：SettingsStore 现有 search 字段读写、所有 caller、其他 6 个 Prefs 字段域
4. 构建 `:app:assembleNotion` 必须通过（应 1-2 分钟）
5. 装机 `adb install -r app/build/outputs/apk/notion/app-universal-notion.apk` 验证 firstInstallTime 保留
6. commit on `refactor/p1-godclass`：subject `refactor(p1): M1.1.2 — SearchPrefs 并行读取通道`
7. **必须派 review subagent**（用户硬性要求）— 模板见 handoff §11
8. review PASS 后 push: `git push github-private refactor/p1-godclass`

### 5.3 估时 0.5d，单 session 可完

---

## 6. 环境关键变量（防压缩后忘记）

```bash
# JDK（每次跑 gradle 都要 export）
export JAVA_HOME="/Users/arquiel/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# ADB
ADB=~/Library/Android/sdk/platform-tools/adb

# 设备 (Nothing A069, Snapdragon 7s Gen 4)
$ADB devices  # 应看到 002803623002594

# 构建 / 装机
./gradlew :app:assembleNotion
$ADB install -r app/build/outputs/apk/notion/app-universal-notion.apk

# trace_processor (Perfetto)
~/.local/bin/trace_processor
```

---

## 7. 一些非压缩后明显，但你必须记住的 nuance

### N1: SettingsStore vs PreferencesStore 命名错位
- 文件名：`app/.../data/datastore/PreferencesStore.kt`（1131 行）
- 类名：`class SettingsStore(...)` 在 line 79
- **Phase 3 才改类名**，Phase 1 沿用错位名字（接受）

### N2: 不能用 `assembleNotionDebug`
plan v2 原文写 `assembleNotionDebug` 是错的。`notion` 是 **buildType** 不是 flavor。正确：
- `assembleNotion` ✓
- `testDebugUnitTest` ✓（其他 buildType 没 test source set）
- `lintNotion` ✓

### N3: M1.3 ContextPlanner 已经存在！
`app/.../data/context/ConversationContextPlanner.kt` 已是 231 行 object（仅 token 估算 + compaction）。蓝图 §B "新抽 ContextPlanner" 改为"扩展现有 object"。**到 M1.3 时必须先 1-2h 重调研** `data/context/` 目录（ContextPlanner.kt / ContextEngine.kt 493 行 / ContextFootprintEstimator.kt）。**M1.1.2 不受影响**。

### N4: M1.1.1 留的债（必须 M1.1.8 兑现）
- UIPrefs 没单测（cross-cutting 测试 infra 推到 M1.1.8 一起加）
- UIPrefs.update 是 non-atomic RMW（M1.1.8 改 atomic `edit { p -> val ui = read(p); ... }`）
- **这条债不能再延期**

### N5: review subagent 是硬性要求
每个 milestone 末必须派独立 review subagent gate。不能跳。**M1.1.2 完成后也必须派**。模板见 handoff §11。

### N6: Theme.amberagent 全小写
不是 `Theme.AmberAgent`，是 `Theme.amberagent`（用户选择，匹配 scheme `amberagent`）。后续保持小写。

### N7: 远程操作需要单独授权
sandbox 对 force push / 大规模 delete 单独拦。每次需要用户明确"force 推"之类授权才能跑。普通 push 也按用户 memory 偏好需要确认。

---

## 8. 接下来 immediate 步骤（压缩后立即做的）

```
1. 读完本 snapshot
2. 读完 handoff doc 全文（特别是 §6 M1.1.2 指南 + §11 review 模板 + §15 rebase 更新）
3. 跑 git status / git log -3 验证 git state 跟 §2 一致
4. 跟用户对齐："读完，准备做 M1.1.2 SearchPrefs，估时 0.5d，按 §6 走。开干吗?"
5. 等用户说"开干"
6. 执行 M1.1.2，按 §5.2 八步
```

---

## 9. 如果发现 git state 跟 §2 不一致

可能场景：
- 用户在 GitHub 上又改了什么 → `git fetch github-private && git pull --ff-only github-private refactor/p1-godclass`
- 远程 phase 分支被别处更新 → 看 `git log github-private/refactor/p1-godclass` 决定怎么 sync
- working tree 不 clean → `git status` 看，确认改动是不是用户手工 edit 的，问用户

**不要**自动 reset/discard，先 push back 问用户。

---

## 10. Sanity check (压缩后第一件事跑)

```bash
cd /Users/arquiel/Downloads/AI/rikkashit/rikkahub
git log --oneline -3
# 期望:
#   b25867e7 docs(handoff): 追加 — main 推进 21 commit + phase 分支 rebase + M1.3 发现
#   18bcb40b docs(handoff): 完整接手文档 — Day 1 收尾
#   de7eb8e3 refactor(p1): M1.1.1 — 引入 UIPrefs 作为并行读取通道

git status --short
# 期望: 空（clean working tree） 或 仅本 snapshot 文档未 commit

ls docs/AMBERAGENT_HANDOFF_2026-05-15.md docs/refactor-p1-blueprint.md docs/refactor-p1-assistantprefs-poc.md docs/SESSION_SNAPSHOT_2026-05-15-PM.md
# 4 个文件都应存在
```

---

**snapshot 时间**：2026-05-15 PM，rebase + handoff update 完成后、M1.1.2 开干前
**有效期**：直到 M1.1.2 完成（或 24h 后过期，以先到为准）
