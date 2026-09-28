# E5 共享接线与 UI 独立审查

2026-09-28。审查者 `/root/e2_review_domain_ui`。只读当前源码与已有 focused 报告；未改产品、未运行测试/lint/build/sign、未操作设备。仅写本报告，保留其他 owner 的变更。已读 harmony-arkts skill、E5 合同及 model/runner/store/remote/native/UI 报告。

## 当前判定

**A/B 共享调用链审查 PASS：确认的两项 P2 已局部修复并独立源复核，当前本范围没有未关闭 P1/P2。C pending，设备离线（Empty），不能以源码/编译/fake port 代替实际视觉或 JSVM 验收。**

没有确认 P1。此前 OpenAPI 基础工具缺失候选已撤回：实际嵌套 factory 提供真实 `http_request`，详见下文。已核当前 runner 固定 child 参数匹配与 expected_base_hash 检查，本报告不替代独立 package/store/native 审查。

## 已发现的精准问题

### P2：审批后 JS 续跑未绑定本轮取消 signal（已关闭，C 未验）

- 触发：JS 第一条写工具产生 pending → 批准 → native reply 后执行长计算 → 当前聊天点击停止。
- `ChatPage.ets:1408` 在暂停完成后清旧 controller；`:4245–4246` 为审批续跑建立新 controller；停止 `:1352` 只 abort 本轮 controller。
- 原版 `recipe_loop.ts:71` 将新 signal 传入 adapter.advance；原 `plugins/runner.ts:417` await `js.reply` 时只有事件前后的 aborted 检查，没有当前 signal → `js.cancel` relay。
- `NativePluginJsTransport.ets:74–79` 仅监听首次 start 的旧 signal，`:93–96` reply 也只查它。故批准后计算期间点击停止不会立即关 native 宿主 gate/结算等待，须等下一事件或预算超时才能进入 cancelled。没有据此声称新增 primitive 会绕过 domain 的后续 signal 检查。
- 最终独立源复核：`plugins/runner.ts:316–318` 在既有冷检查/abort guard 之后，为每次 JS advance relay 当前 signal → `js.cancel(executionId)`；`:450` finally detach，范围包含 `:428` 的原栈 reply 等待。固定 executionId 不被可变 checkpoint 改写；非 JS 不附加监听；return pending 也 detach。不改 reply/native、不重新 start。
- owner 在原 JS 用例内增加一条 reply barrier 断言，先红（Stop 未结算）后受影响 focused **1/1 PASS**，starts 仍1。这里只核源与回执，没有重跑；真实 native 取消仍属 C pending。

### P2：新增插件卡使 Recipes 入口丢失左右边距（已关闭，C 未验）

- 触发：设置 → 运行环境 → 工具编排。
- 原版 `SettingSandboxPage.ets:420–422` 把新 CardGroup 插在 Recipes CardGroup 与其 `.margin({left:GUTTER,right:GUTTER})` 之间，margin 只修饰插件卡，Recipes 卡丢失既有 gutter。
- 父 Column `:455–456` 为 100% 宽；实际 `PageTemplate.ets:377–384` CardGroup 为 100% 且没有默认 margin，因此 Recipes 和插件/其它卡边缘不齐；两个独立卡间也无间距。
- 最终独立源复核：`SettingSandboxPage.ets:420–423` 恢复 Recipes 自身左右 GUTTER，插件卡自身左右 GUTTER + top8。没有改全页布局、没有新 UI 测试。实际目测仍属 C pending。

## A：实际调用与授权边界

1. **真实入口与 oracle**：`SettingSandboxPage.PluginsCard` → `pages/PluginsPage`，main_pages 已注册。DI `getPluginStore` 使用唯一 Entry Store；中心、手动启动均按当前默认 assistant 的真实 LocalPrimitives/MCP/search/WebMount 与 tool-profile 构造 oracle。Chat 则使用该会话 assistant。未把中心默认助手 oracle 当全部会话永久目录。
2. **OpenAPI 候选撤回**：`AssistantLocalPrimitives.ets:40–51` 在助手开启 `workspace_files` 时调用 `createWorkspaceArtifactTools`；`workspace_artifacts.ts:747–765` 提供真实 `http_request`。中心与 Chat 均消费该 factory 及 profile gate。没开工作区工具时 `missingPrimitive` 是真实 scope 结果。runner 对 OpenAPI 保留该 definition 的审批元数据，仅将 execute 换成固定 URL/method/input/domain port；不需新增永远开放 HTTP 工具。
3. **同轮目录/搜索/执行**：Chat `buildToolLoop` 先取允许的 primitive registry，然后取 Recipe/Plugin installed snapshot；同 catalog 回填 adapters、wrapper registry、introspection、tool_search。`refreshTools` 更新 exposure 与两个 adapters。`pluginEnvelope` 和 `mcpTarget` 在 registry/scopedToConversation wrapper 中保留。新调用必须仍在 definitions；持久 pinned 调用不被 live 包替换。
4. **child scope**：DI 对真实 child Recipe/Plugin 名称调用 `configureEntryScopedRecipeLoop`。该 helper 固定 original allowedNames 与 primitives，只刷新原名 package wrapper，保留 subagent_report 与原 native approval guard。不会因 Store 新包把缺失 primitive/新 wrapper 加进 child。
5. **每步审批与持久化**：package batch 按原顺序处理 siblings；child `capture → decide → await save pending/started → dispatch`，package child 的运行批准名单传 `[]`，普通 run trust 不穿透下一 host call。Plugin import 有独立 mandatory/alwaysAsk；生命周期管理为敏感变更、禁止投机/并发。调用后已知结果先保存，再 reply 同一 VM 栈；保存失败不重 dispatch。
6. **手动无辅助模型调用**：页面启动 helper 使用同 adapter.prepare 固定 descriptor/inputs/hash/test/import preview，先 await Repository.save，再按精确 toolCallId 路由。Chat manual continuation 只接受持久 manual parent，pending/finished 停止；完成跳过 title/suggestions/memory extraction 与 queue drain。模型配置是入口校验，执行 helper 不创建或请求 provider。
7. **冷启与后台**：普通 Chat 冷载先排除 active conversation runs，再组合 Recipe/Plugin recovery；beginConversationRestore → await save → 刷新原展示尾窗。started 或丢 VM 的 JS waiting/running 记 outcome_unknown，不 source replay。EntryAbility 设置 backgrounded 后同步 cancel native plugin sessions；回前台不自动 source 重启。当前页审批续跑 signal 接缝已按上方 P2 局部修复。

## B：审批 consumer 与 UI 源细节

- `PluginApprovalSummary` 只读取固定 `metadata.plugin_v1`；import 展示实际候选 hash、原基线、信任/权限 diff/enable/source；run 展示 descriptor/inputs/test 与真实 pending child。没有从 live Store 或可变 parent input 重建审批对象。
- `ChatMessageTools`、`ChatMessageToolPreviewSheet`、`AgentMessageRow` 三个 consumer 都接该 helper。摘要保留当前 child 参数与完整 hash；详情展示固定审核 JSON 及实际 child 输入。ask_user 投影保存 child 的题目/metadata，但使用 parent toolCallId/approvalState，提交回 parent 精确 locator；普通批准按钮在 ask_user 时隐藏，拒绝仍可用。已知 answered 修复不重复登记。
- Activity 消费 Plugin `cancelled → cancelled`、`outcome_unknown/test_failed → failed`；管理工具与 wrapper 的 policy/category/envelope 均沿真实目录。未把 trial validation 成功显示为已执行成功。
- PluginsPage 使用 V1 state/watch 色板、顶部 Scroll 对齐、父 Scroll gutter、52vp 输入与 44vp 按钮；hash/schema/source 支持换行与复制。按钮按 loaded/busy/backgrounded/当前工具是否缺失控制；源码没有先前 E2 的 full-width 外 margin 截断模式。
- 编译回喂后的 PluginJSONEditor 改为明确 `@Prop text/editable` + onTextChange 更新父 @State，输入和 expected_result 分别更新，清对应 issue；没有把 null 和未提供预期值合并。损坏包保留严格 currentHash 的删除/回退入口，null pointer 不猜 hash。
- 新 SettingSandbox sibling 卡 gutter 绑定错误已精准修复，详见上方 P2。其余源码审查未发现新的实质 P1/P2；不把源码可读性当视觉 PASS。

## 既有证据与 C 边界

- 已读 `E5-loop-fixture.md` 的两个 focused 实际 domain/manual/permission/catalog 用例、`E5-plugins-ui.md` 的两个 launch/projection 用例和 `E5-approval-ui.md` 的 helper 证据；没有重跑。controlled native/router ports 仅证明各自边界，不证明 OHOS JSVM 或页面真实操作。
- root 回执：最终共享编译修复后 Release **14.201s SUCCESS**，13 文件精准 lint **0 error / 775 warning**（`/tmp/e5-release-compiler-fixes-build.log`、`/tmp/e5-root-targeted-lint.log`）。这是 root 的构建/静态证据，本审查未运行这些动作。
- **C pending**：设备 Empty，无 E5 真实亮暗中心/审批布局截图与 JSVM 手动续跑/取消验收。签名、安装、启动及真实 provider/native/backend 验收分别由 root 记录，不能由本报告替代。设备恢复后只补合同要求的关键流程，不扩无必要 UI 状态矩阵。
