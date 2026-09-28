# E4 root 集成调用链独立 review

2026-09-28，reviewer `/root/e2_review_domain_ui`。已读 harmony-arkts skill、冻结 E4 contract、手动入口 seam 与实际相关源码。本轮只读产品，不运行测试、lint、构建或设备；只写本报告，保留其他 owner 编辑。

最终 A/B 结论：**PASS，当前没有开放 P1/P2。** 主 Chat 的固定计划、逐步批准、持久后执行、手动禁止模型生成接线成立；原三项 P2 及复核新增的 injected report 丢失 P2 均已局部修复，最终源与已有聚焦 red/green 证据已独立读验。**视觉 C 为设备连接阻塞，未验收，不能记 PASS；A/B 不等同阶段全部完成。**

## 具体发现及修复闭环

### P2：子代理展示 Recipe，但缺少实际 Adapter 消费入口

触发：父助手 full profile 有合法启用的 `recipe__name`，动态 child 的 explicit allowlist 包含该 wrapper 及所需 primitive。`ChatPage.ets:3877–3878` 将 wrapper 放入 baseToolsCache；`subagent_manager.ts:319–326` 的 allowlist∩parentTools 保留它，`subagent_tool_scope.ts` 原来的 executableTools/scopedRegistry 将其真实 schema 展示给 child。原 `subagent_runner.ts:121–132` 仅建普通 ToolLoopOptions，没有 recipeAdapter。实际调用只能得到 `recipes/tools.ts:30–32` 的 `recipe_adapter_required`，目录展示的能力无法执行。

已关闭：`subagent_runner.ts:62,133` 的 configureRecipeLoop 在真实 child loop 构造后、运行前 await；`AppContainer.ets:806–815` 仅在原 tools 已含 Recipe 时接实际 Store/原 terminalGuard。`ScopedRecipeLoop.ets` 固定 original allowedNames/primitives，再按同 installed snapshot 刷新 Adapter/wrappers 与 child registry。原获准名字之外的新 Recipe 不加入；缺失 primitive 不从父目录补齐。Cron 当前未展示 Recipe，未作为断点。子代理既有内存会话、approval_required 终态与缺少 child 交互续跑的边界没有被这次 DI 接线误称为持久审批恢复。二次 scope 的 injected report 问题见下。

已读 `/tmp/e4-child-loop-red.log`（callback 未调用，1 fail）与 `/tmp/e4-child-loop-green.log`（实际 child port/loop/Recipe primitive 场景 1/1 PASS），以及原 focused source。该测试证明真实 callback 接入，平台 DI 刷新作用域由本次源码复核；它没有覆盖 injected report，不外推该边界已通过。

### P2：取消和未知 Recipe 结果被工具卡/Activity 标成成功

触发一：当前 Python/其他 cancellable primitive 真正结算取消后，runner 保存 `{status:"cancelled",error_code:"step_cancelled",...}`。触发二：interactive terminal_session_exec 或未确认终态的 terminal_job_start 返回 `{status:"outcome_unknown",...}`。

`recipes/runner.ts:177–187` 的 terminalParent 给这些结果统一非空 output 和 auto approvalState；`tool_activity.ts:441–456` 仅 Python 工具单独消费取消/超时；`:460–470` 的失败识别不含 Recipe cancelled/outcome_unknown。于是真实 `ChatMessageTools.ets:45–51` 显示成功，派生 Activity 同样 succeeded。停止生成收口保留已有非空 output，不能纠正这个误报。

已关闭：`tool_activity.ts:450–454` 只给 recipe_import / recipe__* 消费其正式终态；cancelled→cancelled，outcome_unknown→failed，原 output/未知说明保留，其余工具的协议不变。已独立读取真实旧 succeeded→cancelled 断言失败日志 `/tmp/e4-recovery-status-red.log` 与最终 `/tmp/e4-recovery-status-green.log`（唯一 focused 1/1 PASS）。

### P2：普通冷启重开的 started Recipe 一直显示执行中

触发：`recipes/runner.ts` dispatch 前的 started await save 成功，进程在实际结果保存前退出；持久父为 auto / output=[] / recipe_v1.phase=started。普通 Home→Chat reopen 不带 recipeManualToolCallId。

`ChatPage.ets:1199–1248` 只加载持久会话，只有带手动路由 id 的初始 auto 才调用 resume；`recipes/runner.ts:255–257` 的 outcome_unknown 仅在 advance 内执行。RDB getById/tail window 是普通解码读取，没有其他 Recipe 冷启 consumer。`tool_activity.ts:448–449` 对该空 output 总是 running，页面 sending=false，也没有审批按钮。**不会自动重放，安全边界成立**，但合同第11条要求的已中断/未知结果没有呈现。

已关闭：`recipes/runner.ts:193–219` 的纯 recoverInterruptedRecipes 仅收口真正 Recipe run/started、空 output 且 checkpointOf 有效的父；沿原 terminalParent 转 outcome_unknown/finished，不调用 port、Store、capture 或 primitive。ready、awaiting_approval、已输出终态和其它工具保持，原已完成步骤/失败步/descriptor 固定，未变节点/分支引用保留。

实际消费者 `ChatPage.ets:1209–1217` 在普通载入时先排除 hasActiveConversationRuns；从同步检查到 beginConversationRestore 之前没有 await；短恢复锁跨 await repository.save，随后才刷新原 oldestLoadedIndex 起的尾窗 rows 并赋回 recovered full。保存错误直接显示，不执行、不更新为未落盘终态。手动初始 ready 不受恢复影响，route auto 仍可首跑；awaiting_approval 仍可原卡继续。已读 `/tmp/e4-recovery-status-recovery-red.log`（函数未实现，1 fail）、最终唯一 focused 的进度/分支/幂等/零 effect 断言；没有通过调用 advance 冒充冷启消费者。

### 新 P2：刷新 child scope 丢失已注入 subagent_report

触发：真实 child scope 含 Recipe，`GenerationSubAgentRunner.run` 在 manager 首次 scope 之后，以 `subagent_runner.ts:319–321` 原样追加 reportCapture.tool()。新 ScopedRecipeLoop 刷新又调用 `scopedSubAgentTools`，其 `subagent_tool_scope.ts:147–148` 排除全部 subagent_*，包含这个必要的 injected report。

修前结果：原 child 模型 schema/执行 map 确定缺少 subagent_report，不能在正常 Recipe 轮提交结构报告。report-only retry 因不含 Recipe 而不进新 DI，可补救成功；不能声称所有 child 必然永久失败。但正常 report 已被迫依赖额外模型轮，预算或模型不配合才 fallback。原 child focused 没有 report、未调用平台 refresh，不能覆盖此问题。

已关闭：独立读取 `ScopedRecipeLoop.ets:11–13,22`，只固定 initial request 已有的 reportTools，将其从 Recipe primitives 分离，再附在重建 scoped catalog 外。保留原对象/schema/execute；不给 supervisor 通行、不铸造新报告工具、不扩原 allowlist，每轮仅附一次。

只强化现有同一个 child focused：真实 GenerationSubAgentRunner 注 report，transpile 实际平台 ScopedRecipeLoop 源并 require 实际 chat-domain index；从 callback、目录刷新、真实 loop/provider 发 Recipe 与 report，到 structured summary 均是原调用链。已独立读 `/tmp/e4-child-scoped-report-red.log`（refreshedReport 为 undefined，1 fail）和 `/tmp/e4-child-scoped-report-green.log`（同项 1/1 PASS）。红场景中的 failed 是主动断言令测试 child 提前返回，并非声称产品所有 child 最终 failed；绿场景同时证明 report 对象/schema/execute 保留、首轮真实 report 被 capture、Recipe 读一次与成功输出、后安装 outside_child 不越原 scope。无新增矩阵、未重复原 suite。

## 已确认的主链

| 边界 | 只读结论 |
| --- | --- |
| 快照 / search / execution | ChatPage 每轮从同一 Store installed snapshot 创建 Adapter 和 wrappers/registry/introspection/search；primitive 工厂只在该 turn 建一次。tool_loop 先 await refreshTools，再读当前 adapter 和 tools。replaceTools 保留仍存在暴露名、删除失效名，保留既定 lazyMode；下一模型轮的 schema 来自该 exposure。 |
| 当前 scope / 重复观察 | recipe_loop 新调用无 checkpoint 时要求当前 definitions 包含 wrapper；已有 pinned 执行仍继续验证当前 primitive。原 repeatable observation set 增加 search/list/policy/Recipe list/validate 五项，同一 search 参数在安装/停用/删除目录变化后读取新 schema；原 effect 重复守卫保留。 |
| 内部 primitive lookup | recipe_loop 对 Recipe 子步骤使用同一完整 authorized tool snapshot，包含隐藏的合法依赖；准备时移除 Recipe/插件 wrapper。没有用 lazy stepDefsMap 错拒绝声明依赖。 |
| 逐步骤批准 | port.decide 与 dispatch 明确传空 run-trust；runner 新 step 为 auto，先 capture，再 decide；当前步 approved 只复制给 saved pendingStep，完成清批准，下一步重新 auto/decide。原 explicit unattended 全局设置继续有效。 |
| native target | 主 Chat 仍装原 terminalGuard capture/hook。已解析参数的 capture 保存进 pendingStep，awaiting_approval 不再次 capture；UI 从该持久 step 展示目标。 |
| before effect / persistence | prepare 固定内容后 save；每步 started await save 成功后才 dispatch；完成进度 save 在 dispatch catch 之外，持久失败不会包装成可安全重试。retry disabled；按父输入身份/唯一 locator 保存、emit，每次保存后更新 current。 |
| pending / ordered batch | Recipe output=[]，进度在 metadata；当前 pending 即 break，不先请求模型。混合批次按原输入序，暂停后后续副作用不启动。approved/denied/answered continuation 用同一 Adapter；已输出终态不再执行。 |
| timeout / cancellation | 原 signal 通过 step controller 传 dispatcher，等待真实 primitive 结算；JS 原接口不消费 signal，合同和 owner 报告均未冒充硬取消。started 重入与真实普通载入恢复均只 unknown，不重放。 |
| 手动固定入口 | RecipePageLaunch 一次选精确 enabled+name+hash，用同 descriptor 构造/prepare checkpoint；保留实际默认 Assistant/presets，真实 user/assistant ToolPart 与唯一 id，AUTO/空 output。await repository.save 后才 push ChatPage，没有直接 primitive runner。 |
| 手动无生成 / auxiliary | ChatPage 从持久 recipe_manual 取回 exact toolCallId，loop 用该 id 走 continuation，缺失不回落模型；该 parent 完成或 pending 后结束。prepareContext/memory blocks/input transformers/provider generation 路线被跳过；maybeAutoTitle/Suggestions/MemoryExtraction 和 drainPendingQueue 也被明确跳过。 |
| 路由 / UI state | SettingPage→SettingSandboxPage→RecipesPage，main_pages 已注册。安装预览/确认同 Store；启停/删除精确 expected hash。三类型输入按 V1 immutable 数组更新，按钮 busy/background guard、44vp，路径/number 52vp；局部主题 palette watch、explicit placeholderColor、GUTTER padding、底部 navBarHeight、Scroll TopStart 源码均有接线。源码不替代真实视觉验收。 |

## 准确的现有边界与证据

- 手动执行仍要求现有 ChatRuntime 的可用 Provider/模型配置；resolveChatRuntime null 会给真实配置错误。入口本身没有模型调用，但没有实现无 Provider 的纯本机 hydration，也没有 dummy model。这一已有 seam 文档明确登记的边界没有被当成本轮新增 bug 或虚假完成声明。
- `recipe` 新 category 当前按现有 toolProfile 过滤；full 会获得 wrappers，coding/minimal 等不自动获得新 category。实际内层 primitives 仍经真实 localTools/profile 过滤。没有自行扩大 profile 权限。
- ask_user 父表单展示/answered 是 owner 已知修复：已读最终 helper 和直接 consumers，定位仍为父 ToolPart，展示从 saved pendingStep 投影，普通 approved 不能冒充答案；不重复列问题。
- 已读 owner 的 model/store/runner/page-launch/approval 证据报告及真实 native-chain fixture 源码，未重复运行必要检查。另读 `E4-loop-fixture.md` 与实际原三场景：最初 2 pass/1 affected fail，root 精准修复后只 affected 1/1 PASS；`/tmp/e4-recipe-loop-fresh-directory-green.log` 为真实同参 search 目录变化 green，没有把修改参数的中途实验当最终证据。manual 零 stream、混合批次原两项已通过，不要求重新重复。Node Workspace+既有 CPython addon 三步证据不等于 Harmony 平台实际运行；类型/lint/build/sign/install各门由 root 分别记录。
- **C：device blocked / 未验收。** 没有 E4 新亮暗设备截图，本报告未操作设备，也未用源码尺寸、宿主 projection 或 E2/E3 历史截图冒充 E4 UI PASS。root 仍需实际列表/长预览/输入表单/步骤批准与失败结果检查。
