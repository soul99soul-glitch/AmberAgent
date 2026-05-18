# Refactor Phase 2 Kickoff — `SettingExperimentalPage.kt`

> 写于 2026-05-18，Phase 1 合并到 main 后立即开稿。下次开 session 读这个文档就能直接进入 Phase 2 的拆分。

---

## 0. 一句话上下文

Phase 1（4 个 god class 拆分）已合并到 main，PR #1，merge commit `9301b318`。Phase 2 第一刀目标 **`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingExperimentalPage.kt`（2447 行，🟢 低风险）**，**尚未开工**。

---

## 1. 工作环境

| 项 | 值 |
|---|---|
| Worktree | `/Users/arquiel/Downloads/AI/rikkashit/rikkahub-refactor-godclass/`（**沿用 P1 同一个 worktree**）|
| 禁区 | `/Users/arquiel/Downloads/AI/rikkashit/rikkahub/`（Codex 主 worktree，per memory `feedback_rikkahub_worktree_isolation`）|
| 当前分支 | `refactor/p2-settings-experimental`（从 `github-private/main` 出，已 tracking 远端 main 但**尚无 commit**）|
| 远端 | `github-private` = `soul99soul-glitch/AmberAgent`（fork），`origin` = `rikkahub/rikkahub`（公开 upstream，不动） |
| Java | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=$JAVA_HOME/bin:$PATH` |
| Gradle | 必加 `--offline`（代理 127.0.0.1:7897 不稳） |
| Baseline 单测 | **6 / 463 failed**（main 当前状态。Phase 1 时是 6/427；main 后续加了 36 个 test。失败的精确 6 个：GenerativeUiPlannerTest×2, ContextFootprintEstimatorTest×3, DefaultProvidersTest×1） |

---

## 2. P1 经验教训（避坑用）

1. **命名 namespace**：god-class 内部抽出的 top-level helper 起名要带前缀，避免与未来 main 新代码冲突（参考 P1 merge 时 `stringProp` → `accessStringProp` 改名事故 — main 后续 ToolSearch.kt 引入同名 helper 撞车）
2. **跨包 visibility 收紧**：`public fun` → `internal fun` 前必须 grep 所有跨包 caller，确认仍在同 module（`internal` 是模块作用域）
3. **merge main 进 refactor**：main 对已抽出代码的 hunks 要逐个 port 到 sibling 文件，不能 take-ours 一刀切；至少 4 个 hunk 必须手动 port（P1 的真实案例）
4. **dead-import 清理**：只清这次拆分变 orphan 的 import，**绝不顺手清 pre-existing dead**（Codex Round 1 抓过 foundation.Canvas 误判事故）
5. **byte-equal 严格度**：函数体内的 FQN（如 `Activity.RESULT_OK`）也算 byte-equal 范畴；只有签名 / 参数类型的 FQN→short 可以接受为 IDE 风格清理
6. **EOF 空行**：strip + write 后 `git diff --check` 确认 EOF 没多余空行

---

## 3. 硬规则（不能违反）

1. **Worktree 隔离**：只动 `rikkahub-refactor-godclass/`，不动 `rikkahub/`
2. **不 amend**：commit 完只能加新 commit（CLAUDE.md）
3. **不动不理解**（CLAUDE.md §3）：dead import 清理只清这次拆分变 orphan 的；pre-existing dead 留着
4. **每 commit sub-agent review**：Round 1 必跑；Round 2 仅在 Round 1 REQUEST CHANGES 我修了之后
5. **Baseline 6/463**：拆完每次 `:app:testDebugUnitTest --offline` 必须 = 6 failed（精确 6 个 test 名匹配）
6. **3 变体 assemble**：每完成一个独立拆分（或整个 PR 完成时）跑 `:app:assembleDebug :app:assembleNotion :app:assembleRefactortest --offline`，必须 BUILD SUCCESSFUL
7. **Compile gate**：每次 strip + 写新文件后立即 `:app:compileDebugKotlin --offline` 验证

---

## 4. 拆分流程模板（沿用 P1）

```
1. 读目标文件，grep 块边界（@Composable / @ScopeName / private fun + 行号范围）
2. grep 每个符号的外部 call site（决定 internal vs private）
3. 写 pre-cut 分析文档（symbol 清单、调用图、风险评级、切分方案）→ 用户 GO 再动手
4. 写新 sibling 文件（同 package，含必要 imports，标对 internal/private）
5. awk strip 旧块：awk 'NR<X || NR>Y' file > tmp && mv tmp file
6. compile gate
7. dead-import 清理（grep 每个候选 "<symbol>" file | grep -v "^import"，0 ref 才删；逐个验证不是 pre-existing dead）
8. compile 再验
9. testDebugUnitTest（验证 baseline 6/463）
10. commit
11. sub-agent Round 1 review
12. (可选) Round 2 if NITs/CHANGES
13. push
14. 全部 stage 完成后：3 变体 assemble + 起 PR + Codex 独立 review
```

### Internal vs Private 决策表
- 外部 file 有 caller → `internal`
- 跨包 caller → `internal`（仍可见同 module）
- 仅本块内部用 → `private`（file-private 到新文件）

---

## 5. Phase 2 目标：`SettingExperimentalPage.kt`（**未开工**）

### 5.1 基本信息
- 路径：`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingExperimentalPage.kt`
- 行数：**2447**
- 风险：🟢 **低**（设置页，各 section 大概率独立）
- 拆分策略预判：按 section 切（每个 setting 子区域抽成一个 sibling 文件）

### 5.2 第一步要做的事（开工前）

**先做 pre-cut 分析，不要直接动刀**。流程：
1. 读完整文件了解结构
2. grep `@Composable` / `private fun` 找所有 composables
3. 列 symbol 清单：每个 composable 是干什么的、被谁调用、可以归到哪个 section
4. 画调用图：哪些 composable 互相调用、哪些独立
5. 评估 cross-file caller（其他 Setting 页是否调这里的东西？）
6. 出**切分方案文档**（`docs/REFACTOR_P2_SETTING_EXP_PRECUT_2026-05-XX.md`），等用户 GO 才动 strip

### 5.3 风险点初判

不读代码无法精确判断，但常见风险：
- Setting 页通常有大量 `LocalSettings.current` / `LocalSettings.collectAsState()` 的状态钩取 — 抽出后要确认这些 hook 还能跨文件工作
- 实验性设置可能依赖 `BuildConfig` 或 feature flag — 不要在拆分中改 flag 逻辑
- 可能有 `LaunchedEffect` 副作用启动 — 拆出去后 effect key 要保持稳定

### 5.4 后续 Phase 2 候选（按 ROI 排）

完成 SettingExperimentalPage 后的候选（每个起独立分支独立 PR）：
1. `ui/components/message/ChatMessageTools.kt` (2605, 🟡 中风险)
2. `service/ChatService.kt` (2311, 🔴 高风险)
3. `ui/pages/chat/ChatList.kt` (2251, 🟡 中-高)
4. `ui/pages/setting/SettingProviderDetailPage.kt` (1705, 🟢 低)
5. `ui/components/message/ChatMessage.kt` (1634, 🟡 中)
6. `ui/pages/setting/SettingSearchPage.kt` (1571, 🟢 低)

**不要碰**：`ui/components/richtext/MarkdownNew.kt` + `Markdown.kt` —— 并列存在疑似 in-progress 迁移/重写，先查清楚。

---

## 6. 关键文件路径速查

- 目标：`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingExperimentalPage.kt`
- 相邻 setting 页（参考结构）：`SettingProviderDetailPage.kt` / `SettingSearchPage.kt` / `SettingModelPage.kt`
- Compose 常用：
  - `ui/components/setting/FormItem`（参考 setting 页表单约定）
  - `ui/context/LocalSettings`
- 历史文档：
  - `docs/SESSION_5_HANDOFF.md`（P1 ChatInput Sandbox pre-cut，可作 pre-cut 文档模板）
  - `docs/chatinput-composers-plan.md`（P1 Composers pre-cut）

---

## 7. 失败回退

每个 stage 拆完都建议 push，远端备份。如果某次 stage 拆崩：
```bash
git reset --hard <上一个 stage 的 commit>
# 或如果还没 commit:
git checkout -- .
```

Phase 2 整体起点：`9301b318`（Phase 1 合 main 后的状态）+ Phase 2 分支额外 fast-forward 拉的 main commits。
