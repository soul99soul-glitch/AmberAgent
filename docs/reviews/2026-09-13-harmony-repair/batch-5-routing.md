# Batch 5R — R23 统一 Want 派发 + UI-F34 有效模型同源

范围：R23（非首页接收 Want 滞留/被覆盖 → 统一前台路由派发）+ UI-F34（助手绑定模型与菜单/选择器显示、写入不一致）。

独占文件（本 agent 只改这些）：
- 新增 `harmony/entry/src/main/ets/platform_impl/ChatModelSelectionSupport.ets`
- `harmony/entry/src/main/ets/entryability/EntryAbility.ets`
- `harmony/entry/src/main/ets/pages/ChatModelPickerPage.ets`
- `harmony/entry/src/main/ets/pages/ChatListPage.ets`（仅深链消费去重，未动其列表 owner 的 firstShow/loadPreviews 新改）

未改 `ChatPage.ets` / `AppContainer.ets`（主代理独占）。约束遵守：已加载 `harmony-arkts`；API 基线 12（`getRouter`/`UIContext` since ≥10、`router.pushUrl` 既有）；状态装饰器全 V1；无新依赖/新框架；未跑 hvigor/全仓 lint（主代理统一）。

---

## 1. UI-F34 — 有效模型同源

### 机制

运行时发送走 `AppContainer.resolveChatProviderChoice(assistant)`（`resolveChatRuntime` → `assistant.chatModelId` 非空优先，失效回落全局）。此前 UI 顶部菜单/选择器只读写全局键 `chat_selected_provider_id/model_id`，且 `describeChatModel()` 无参 = 全局，导致绑定助手会话里「当前」标记、上下文窗口、推理档位显示错误模型，切换静默无效。

本批新增 **唯一 resolver/写入口** `ChatModelSelectionSupport.ets`，**不新建第二套解析**：

- 读 `resolveEffectiveChatChoice(assistantId?)` 直接委托既有 `resolveChatProviderChoice(assistant)`，未传/助手不存在 → 全局。展示与发送消费同一 resolver，天然同源。
- 写 `saveEffectiveChatChoice(selection, assistantId?)`：
  - 先校验所选 `{providerId, modelId(人类可读)}` 命中 **enabled + `hasUsableAuth`** provider 下 `type='chat'` 且 **内部 UUID 非空** 的真实模型；不存在/损坏/不可用 → **reject**（不写坏数据，绝不新造 `ProviderModel.id`）。
  - **助手已绑定**（`chatModelId` 非空）→ 经 `mutateAssistant`（`assistants` 键共享锁）把绑定改成所选模型的**内部 UUID `ProviderModel.id`**（非 `modelId` 字符串）；锁内若助手已被删除 → **reject**。
  - **显式传 assistantId 但助手不存在** → **reject**（终审 P2：不得降级写全局，否则会污染其它未绑定会话）。
  - 助手**未绑定** → 写全局选择键（本计划明确接受的既有语义，非缺陷）。
  - 未传 assistantId → 写全局选择键。
- `clearEffectiveChatChoice(assistantId?)` → 清除绑定（跟随全局）。
- `hasEffectiveChatBinding(assistantId?)` → 供 UI 决定是否显示清除入口。

`ChatModelPickerPage` 读取 router 参数 `assistantId`（`ChatPage` 打开完整选择器时携带），「当前」按有效模型标记；绑定态顶部显示「跟随全局选择 → 清除该助手的模型绑定」；点选经 `saveEffectiveChatChoice`；保存失败 `promptAction.showToast`。

### 主代理接线（ChatPage / 已按协调确认）

ChatPage 已 import 本 helper 并替换调用（见 `ChatPage.ets:134`）。签名如下，展示/发送门统一改用：

```ts
resolveEffectiveChatChoice(assistantId?: string): Promise<ChatProviderChoice | null>
```

- 所有 `resolveChatProviderChoice(...)` 调用点 → `resolveEffectiveChatChoice(<当前会话 assistantId；无会话传 undefined>)`。
- `describeChatModel()` 替换为 `choice.provider.name / choice.model.modelId`（choice 可能 null → 保持 `''`）。
- 菜单 selected：`currentProviderId = choice?.provider.id ?? ''`，`currentModelId = choice?.model.modelId ?? ''`（即 `buildTopModelMenuGroups` 的 `currentProviderId/currentModelId` 实参）。
- 菜单选中写：`saveEffectiveChatChoice({ providerId, modelId }, assistantId).catch(toast)`。
- 打开完整选择器：`router.pushUrl({ url: 'pages/ChatModelPickerPage', params: { assistantId } })`（无会话可省略）。

---

## 2. R23 — 统一 Want 派发

### 根因

`EntryAbility.onNewWant` 只写 AppStorage；唯一消费者是根页 `ChatListPage`（`aboutToAppear`/`onPageShow`）。栈顶为 ChatPage/设置页时无消费者 → 滞留；未消费参数还会被后续 Want 覆盖。冷启动首页消费是反例（正常）。

### 机制（`EntryAbility.ets`）

AppStorage 键（沿用现有 topic/novel 键，`wantRouteSeq`/`wantRouteReady` 为本批新元数据）：

| 键 | 含义 |
|---|---|
| `wantTopicId` / `wantTitle` | DeepRead 目标（既有） |
| `wantNovelProjectId` / `wantNovelBranchId` / `wantNovelJobId` | Novel 目标（既有） |
| `wantRouteSeq: number` | 待处理 Want 序列号（0 = 无） |
| `wantRouteReady: boolean` | 路由就绪门（`loadContent` 成功后置 true） |

导出（准确签名）：

```ts
export const routePendingWant = (): void
export const markWantRoutingReady = (): void
export const publishWantRoute = (
  topicId: string, title: string,
  novelProjectId: string, novelBranchId: string, novelJobId: string,
): void
```

- `publishWantRoute`（`routeFromWant` 调用）：校验后的目标写入待处理槽 + `wantRouteSeq++`，随后尝试派发。三类语义（终审 P2 澄清）：
  1. **完整目标**（topic 或 novel 三键齐全）→ 落参 + `seq++` + 派发。
  2. **含路由字段但不完整**（如只给 `novelProjectId`）→ 这是一个新的畸形意图，**作废旧 pending**（`wantRouteSeq=0` + 清字段），避免用户被误路由到上一条合法目标；既有 seq 守卫保证旧在途 push 的迟到回调不会再清/跳（旧回调查到 seq 已变 → 不清新参数、重派时因 seq=0 空操作）。
  3. **完全不含路由字段的普通激活**（无参数点图标 / 只有无关参数）→ **保留 pending，不机械清**；否则冷启动已挂起的目标会被一次无关激活吞掉。
- `routePendingWant`：`ready` 未置 / `seq<=0` / 跳转在途 → 空操作。命中目标经**全局 `router.pushUrl`**（`@kit.ArkUI`）跳 `pages/NovelWorkspacePage`（`projectId/branchId/jobId/tab='chapters'`）或 `pages/DeepReadArticlePage`（`topicId/title`）。**仅 pushUrl 成功且 `wantRouteSeq` 仍 == 本次 seq 才清参数**；seq 变了 → 保留新参数并重派。pushUrl catch → 保留 pending（不清），留待下次触发重试。
- `markWantRoutingReady`：`loadContent` 成功回调里调用，置 ready 并补跳冷启动挂起的 Want。
- 触发点：`onNewWant`（任意栈顶，能力内即时派发）+ `loadContent` 成功（冷启动补跳）+ `ChatListPage` show/appear（兜底重试）。

### ChatListPage 去重

`consumeDeepLink()` 不再自行读键/`pushUrl`，改为单行委托 `routePendingWant()`。单一派发点 + in-flight/已清守卫 → 不会 onNewWant 与本页 show 双导航；`aboutToAppear`/`onPageShow` 仍各调一次仅作 ready 前兜底（无 pending 空操作）。其 F08 列表 owner 的 `firstShowDone`/`loadPreviews` 保持不动。

---

## 3. 修改文件

| 文件 | 修复 | 最小机制 |
|---|---|---|
| `entry/src/main/ets/platform_impl/ChatModelSelectionSupport.ets`（新增） | UI-F34 | 有效模型唯一 resolver/写入口；委托既有 `resolveChatProviderChoice`；绑定写内部 UUID；校验 enabled/auth/type |
| `entry/src/main/ets/pages/ChatModelPickerPage.ets` | UI-F34 | 读 router `assistantId`；「当前」按有效模型；绑定态清除入口；写经 `saveEffectiveChatChoice` |
| `entry/src/main/ets/entryability/EntryAbility.ets` | R23 | 待处理槽 + seq + ready 门；`routePendingWant` 全局派发；`onNewWant`/`loadContent` 成功触发；nav 成功且 seq 匹配才清 |
| `entry/src/main/ets/pages/ChatListPage.ets` | R23 去重 | `consumeDeepLink` 委托 `routePendingWant`，删除本页二次 `pushUrl` |
| `tests/fixtures/contracts/effective_chat_model_cases.json`（新增） | F34/R23 | 纯契约用例表（有效模型优先级、保存拒绝/绑定 UUID、Want 路由不变量） |
| `probes/probe-batch5r-routing-model.cjs`（新增，1 个） | 终审 P2 回归 | 无头驱动真实 `ChatModelSelectionSupport` / `EntryAbility`，断言 P2 修复 |

## 4. 终审 P2 修正（本轮）

1. **不 fallback 写全局**：`saveEffectiveChatChoice` 显式传 `assistantId` 但助手不存在 → `throw 助手不存在,模型绑定未保存:<id>`；锁内发现助手已删除 → `throw 助手已删除,模型绑定未保存:<id>`。二者均不写全局（避免把「会话绑定意图」污染到全局、影响其它未绑定会话）。仅 **未绑定** 助手才沿既有全局语义。
2. **损坏模型拒绝**：匹配到的 `ProviderModel.id` 为空/纯空白 → 视为不可绑定，reject（不写空串、**绝不新造 id**，新造会让其它引用该绑定的路径失效）。
3. **Want 三类语义区分**（见 §2 第 2 点）：完整 → 派发；含字段但不完整 → invalidate 旧 pending 防误路由（seq 守卫保证旧在途不误清/误跳）；完全无路由字段的普通激活 → 保留 pending。

## 5. 测试与静态检查

- `harmony/scripts/lint-arkts.sh`（`LINT_EXIT_ON=none`）逐文件：`ChatModelSelectionSupport.ets` / `EntryAbility.ets` / `ChatModelPickerPage.ets` / `ChatListPage.ets` 均 **0 error**（"No defects found"，P2 修正后复跑 helper/EntryAbility 仍 0）。
- 1 个新 probe `probes/probe-batch5r-routing-model.cjs`（真实模块 + 桩依赖，不新增 suite）：11/11 断言通过，日志 `probes/probe-batch5r-routing-model.log`。覆盖 F34 (1) 显式助手缺失→throw 不写全局、(2) 未绑定→全局（接受语义）、(3) 绑定→内部 UUID、(4) 空 UUID 模型→拒、(5) disabled provider→拒、(6) 锁内删除→throw 不写全局；R23 (A) 合法派发+成功后清、(B) 不完整意图作废旧 pending 且旧在途不误路由、(C) 无字段激活保留 pending、(D) 导航失败保留 pending、(E) novel 目标派发。
- 未跑 hvigor/全仓 lint（主代理统一串行）；未做设备截图（无在线设备，不声称 UI 已验证）。

## 6. 共享接线需求 / 未解决点

1. **ChatPage（主代理独占）**：按 §1 签名把 `resolveChatProviderChoice` 调用点与菜单 selected/label 切到 `resolveEffectiveChatChoice`；菜单写入用 `saveEffectiveChatChoice`；打开选择器传 `assistantId`。ChatPage 已 import（`ChatPage.ets:134`）。
2. **AppContainer（主代理独占，可选）**：`describeChatModel()` 仍是无参全局；若 ChatPage 直接以 `choice.provider.name / choice.model.modelId` 组标签，则无需改。若要保留 `describeChatModel`，可加 `describeEffectiveChatModel(assistantId?)` 委托本 helper（非必需）。
3. **设备验证项（不声称通过）**：前台任意栈顶收到通知点击时 `onNewWant` 是否即时触发；`router.pushUrl` 在 Ability 上下文（无页面栈时）的 reject 语义；冷启动 loadContent 成功回调与 router ready 的时序。需模拟器/真机点击通知实测。
4. **F39 第 1/3 点（sourceUrl 断链、清键先于导航）**：清键先于导航已由本批「nav 成功才清」解决；`sourceUrl` 断链属 Notifier 侧（非本批文件），未触及。
