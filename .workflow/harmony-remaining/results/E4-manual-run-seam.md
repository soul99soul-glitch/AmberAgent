# E4 手动运行的最小接线（只读结论）

2026-09-28。仅核对当前源码，不修改产品、不运行测试、构建或设备。root 正在接 `tool_loop`；以下行号是接线前的当前工作区快照。

**结论：RecipesPage 提交固定 name/hash/三类型 inputs，ChatPage 创建绑定真实 Assistant 的会话和真实 assistant ToolPart，先落库，再进入同一 RecipeLoopAdapter。必须给现有 loop 一个按 toolCallId 定位的手动续跑边界，初次执行和审批续跑均不进入模型生成，终态落库后直接结束。** 不能经 `initialText → send()`，不能另建 UI runner，也不能把 parent 标成 approved 来跳过生成。

## 已有可复用边界

以下路径相对 `/Users/arquiel/Downloads/AI/amber-harmony-preview/`。

| 当前源码 | 已确认行为及含义 |
| --- | --- |
| `harmony/entry/src/main/ets/pages/ChatPage.ets:1177` | 路由参数为 `Record<string,string>`；可提交独立 `recipeName/recipeHash/recipeInputsJson/assistantId` 字符串。 |
| `ChatPage.ets:1183`、`:1199` | 现有会话先载 tail，再 `ensureFullConversationLoaded`。写整会话前必须保留完整 nodes，不能从 tail window 构造保存快照。 |
| `ChatPage.ets:1254`、`:1266` | 无 conversationId 时 `getChatAssistants → makeConversation → seedConversationWithPresets`；此时只在内存，**没有保存**。 |
| `ChatPage.ets:1259` | 指定 assistantId 未找到时现有普通路由回落首个助手；手动 Recipe 入口必须先核对精确 id 存在，不能声称绑定原助手而实际换了一个。 |
| `ChatPage.ets:1279` | initialText 最后调用 send/sendWithoutAnswer；前者会请求模型，不能借此让模型重新猜要跑哪个 Recipe。 |
| `harmony/chat/src/main/ets/chat/conversation.ts:61`、`:80` | `makeConversation`、`patchConversation` 可复用；`toMessageNode:31` 包装真实消息；保留已有 presets。 |
| `harmony/chat/src/main/ets/chat/context_assembly.ts:171` | `seedConversationWithPresets` 仅在空会话插入 preset，不执行模型、不保存。 |
| `harmony/deepread/src/main/ets/agent/message.ts:81`、`:119` | ToolPart 正式字段是 type/toolCallId/toolName/input/output/approvalState/metadata；无 streamIndex。`makeUIMessage('assistant',[parent])` 不伪造 modelId/usage。 |
| `harmony/chat/src/main/ets/chat/chat_turn.ts:70` | `ConversationStore` 只有 `save(conv)`，无需新存储协议。 |
| `harmony/entry/src/main/ets/di/AppContainer.ets:1005` | `getChatRepository()` 是实际 RDB repository。 |
| `harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets:300` | save 按 conversationId 排队，数据库不可用会拒绝写入；`:114` 重写该会话全部 nodes，`:129` 事务提交。必须 await 实际保存后才执行 primitive。 |
| `ChatPage.ets:4092` | `runViaKernel` 接收现成 operation；`chat_kernel_host.ts:128/149` 追踪保存并调用 operation，宿主不自行请求模型，可继续复用。 |

## 建议固定的窄入口

新增一次路由请求，界面仍使用普通 ChatPage：

```ts
interface ManualRecipeRunRequest {
  assistantId: string;
  recipeName: string;
  recipeHash: string;
  recipeInputsJson: string; // 精确 string/number/boolean，不用非空字符串推断 boolean
  requestId: string;
}
```

- 最小实现创建**新的实际助手会话**，标题 `Recipe · <name>`；无需引入 existing-conversation 选择、队列或新的运行页面。RecipesPage 必须使用当前助手的真实 id。
- 严格输入校验与普通动态工具共用 manifest oracle。已安装包必须启用、名称和 hash 精确匹配；UI 预览之后包变了即拒绝，不能静默改跑新版本。
- 在**同一 Adapter.prepare 得到的固定 descriptor**上核对 UI expected hash；不能只在 UI listInstalled 时比一次 hash，然后 prepare 又读取另一版本。可把手动请求的 expected hash 作为 parent metadata 的窄字段传入 prepare；不是 manifest 输入，不加到 recipe 动态工具 schema。
- parent 使用真实 `toolName='recipe__'+name`、`input=JSON.stringify(inputs)`、唯一 `toolCallId`、`output=[]`、`approvalState={type:'auto'}`。加入一条简洁 user 运行意图和末条 assistant parent，使用现有构造器；不调用 send()。
- parent 持久化标记手动运行及请求 id/expected hash，`recipe_v1` 仍由同一 Adapter.prepare/advance 生成；不另造 runner checkpoint。标记必须随 ToolPart 保存，审批后/重新打开页面能恢复同一路线。
- await `getChatRepository().save(conversation)` 成功才进入运行。save 拒绝应展示错误，primitive 调用数为零。新路由消费发生在 async 操作前，并记住 requestId，避免 aboutToAppear/返回导航重复创建或执行。

这里的 metadata 字段名由 root 与 runner worker 集中固定；无需新实体表、持久化队列或通用 ledger。

## 现有 continuation 需要收窄的位置（root ownership）

`ChatPage.ets:4217–4289` 已有 resume 的页面存活检查、sending、operationAbort、generationToken、runtime、kernel 和保存回调。保留这些接线，将手动目标传给 root 所接的 RecipeLoopAdapter 分支。

建议仅增加 `ToolLoopOptions.manualToolCallId?: string`（名称可按 root 最终协议）：

1. 定位末条 assistant 中该 id 的 `recipe__*` parent；初次 auto 也直接选择它，进入同一个 Adapter.prepare/advance。目标缺失或类型不符应报具体错误，不回落模型。
2. 暂停等待步骤审批时按现有保存/UI 路线返回；以后 `applyToolVerdict → resumeToolLoop` 从保存的手动标记取回相同 id。
3. 该 parent 完成、失败或被拒绝，保存并 emit 后立即 return；不要下一轮 generate，也不要消费新的 steer 消息来扩大这次手动运行。
4. 手动标记保留在已保存 parent；页面局部 boolean 不能独自决定审批续跑语义。普通模型发起 Recipe 保持原 loop 行为。

当前证据：

- `message.ts:22`、`:170` 只将 approved/denied/answered 视为 resumable；初始 auto 不会被现有 `tool_loop.ts:278` 选中。
- `tool_loop.ts:307` 无 resumable 工具即生成，`:369` 调用 `generateAssistantOnce`；`:483` 保存工具结果后循环继续，所以普通 continuation 在结果之后仍会生成。
- `tool_loop.ts:530` 的 `runToolLoopContinuation` 只是 normal loop 入口，**不是 tool-only**。maxSteps 是预算，且主 loop 下限钳制，不能作为禁止模型调用的语义开关。
- `ChatPage.ets:4184–4203` 保存 verdict 并调用 resume；不需第二套批准入口。
- `ChatPage.ets:4297–4306` 的完成回调另外触发标题、建议、记忆提取。手动路线跳过这些自动模型请求，保留 finishGenerationCommon、必要通知与界面收口；采用预设标题。

## 两个不能误称已解决的边界

**run guard 不是会话互斥锁。** `ConversationRunGuard.ets:11–23` 只防恢复冲突并累加 activeRuns；不会拒绝已有运行。`ChatPage.ets:4065` 使用它。手动入口必须沿用 `sending`、`pageAlive`、conversationId、generationToken 的异步有效性检查；新建独立会话即可缩小冲突，不在 E4 引入 E6 并行宿主改造。

**不请求模型仍可能要求配置模型。** `AppContainer.ets:1487–1508` 的 resolveChatRuntime 没有可用 Provider/模型就返回 null，`:1542` 可能刷新 OAuth；`ChatPage.ets:4232–4238` 据此拒绝运行。现有 buildToolLoop 依赖 ChatRuntime。最小接法可以复用已配置助手，UI 应诚实显示配置要求；若必须支持无 Provider 的纯本机 Recipe，只提取实际 assistant/local primitive 的窄 hydration，不能造 dummy provider/model 或声称当前路径已无该依赖。这不是 kernel 自动生成导致的限制。

## 交付与必要验收

- RecipesPage owner：只交固定 request，三类型输入控件和已安装 descriptor 预览；不直接 execute primitive。
- Root：route/ChatPage/loop 接线、manual marker、expected-hash prepare 校验、续跑目标、结果收口。
- Runner/store owner：继续既定 Adapter/Store，无第二个手动执行器。
- 不新增本报告专用测试框架；在 E4 已有关键验收内覆盖：手动首跑和审批续跑零模型请求；一次保存后执行；旧 hash 和 save 失败零副作用；返回导航不重跑；真实助手与 toolpart 重开可见。本文未执行这些验收，不声称已通过。
