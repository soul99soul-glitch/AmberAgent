# E4 Recipe 审批展示与父调用定位

2026-09-28。按 E4 合同，只新增 `RecipeApprovalSummary.ets`，修改 ChatMessageTools / ChatMessageToolPreviewSheet / AgentMessageRow 三个直接消费者，并新增一个必要的 UI 投影测试。本子任务没有改 ChatPage、AppContainer、tool_loop 或 runner，没有构建、签名、操作设备或提交 Git。

## 实际行为

- 45 行 helper `recipeApprovalView(parent)` 只读取持久 `metadata.recipe_v1`，从固定 descriptor / import preview / pendingStep 展示内容；不查询 live Store、不重新解析绑定或捕获 SSH 目标。`recipeAskUserPart(parent)` 仅产生显示用 part，原 pendingStep 不变。
- Recipe pending 卡显示固定 Recipe 名/版本、步骤序号/ID/真正 primitive 工具名、该 pendingStep 已解析实际参数。详细页展示完整参数并支持复制；父 wrapper 的模型输入不再被误作当前步骤参数。
- import 卡显示候选 canonical hash 和已安装 baseHash（无基线显示首次安装）；详情显示固定 workspacePath、两个 hash 与完整 canonicalJSON。不是直接信任模型入参自称的 hash。
- pendingStep 是 SSH/Mosh 工具时，卡与详情沿现有 SSHApprovalSummary 读取该 step 已保存的目标和协议，显示真实命令；不显示父 Recipe 的空 target，也不查询当前默认主机。
- 审批/拒绝胶囊仍持有父 part 的 toolCallId；预览仍打开父 part，精确 locator 由 AgentMessageRow 的原父 part 得到。

## ask_user 闭环

实际查明的断点是 AgentMessageRow 只按外层 `toolName=ask_user` 显示表单，Recipe 父调用无法输入答案；同时 runner 原等待分支只接受 approved/denied，父 answered 不会推进。

现在 Recipe 的 pendingStep 为 ask_user 时，复用 ChatMessageAskUserStep 的问题/选项/输入/已回答显示；显示 part 输入来自固定 pendingStep，toolCallId 和 approvalState 来自父 part。提交仍调用 `submitAskAnswers(parent)`，按真实子步骤问题构造 answers 载荷，再用父 toolCallId / 父 locator 调用现有 onAnswerTool。此类卡禁用“批准”按钮，避免批准替代回答；仍可拒绝整个 Recipe。普通 ask_user 路径保持。

已与 runner owner `/root/e2_domain_runtime` 协商，不新增 checkpoint 字段。其 owner 已将父 answered 的原 answer 传给 saved pendingStep 的 answered，再走真实 decide/dispatcher；普通 approved 回到等待，不替代答案。owner 报告一个 focused dispatcher 回答续跑红绿测试，runner 6/6 通过；该修改与测试由其 owner 实施，本子任务未接管文件。

## UI 尺寸与验证

沿用现有 Column、JsonTree、问答表单、SSHUIColors、tokens、12/16px padding、4/6px space 与 8px radius。没有重做布局或主题。详情输入/输出区原 `width('100%') + 左右 margin(16)` 会超出父宽度，改为固定 100% 外容器的左右 padding(16)，内部 JSON/文本占剩余宽度，保留原视觉边距；长 hash 使用既有 BREAK_ALL，完整参数/canonical 内容留在可滚动详情中。

- 唯一 UI 投影测试：`node --test harmony/tests/recipe-approval-summary.test.cjs`。先新增测试红（缺对应 helper），随后真实 `.ets` helper 经既有 TypeScript transpile/VM 方式执行；1/1 通过。验证真实 pending primitive 输入/固定 SSH 标签不被父输入替换；ask_user 问题与回答状态使用父 ID、原子步骤不变；import 固定 hash/base/canonical 内容不变。证据 `/tmp/amber-e4-approval-red.log`、`/tmp/amber-e4-approval-test.log`。不声称这个宿主投影测试等同 ArkUI 渲染。
- 精确 lint：`harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/components/RecipeApprovalSummary.ets harmony/entry/src/main/ets/components/ChatMessageTools.ets harmony/entry/src/main/ets/components/ChatMessageToolPreviewSheet.ets harmony/entry/src/main/ets/components/AgentMessageRow.ets`，0 error / 1 warn / 0 suggestion；仅剩 AgentMessageRow 既有循环访问 state 的性能提示。证据 `/tmp/amber-e4-approval-lint.log`。首次普通 sandbox 禁止 SDK 安装目录日志写入，同一已授权验证命令权限升级后通过；没有更改 lint 规则。
- 自有文件 `git diff --check` 通过。helper 45 行，卡片 220 行，详情 368 行。root 已在 index 导出 RecipeRunCheckpoint / RecipeImportCheckpoint，消费现有类型而非新造 domain 协议。

root 统一负责全量 ArkTS 编译、HAP/设备和长内容亮暗截图，以及父审批真实点击与整条 Recipe 的阶段复审；这里没有声称这些验收已通过。
