# E11 Jev / 议会 UI

2026-09-28。生产检查点 `ed2452cdf`；已读 frozen E11 contract/design 与 harmony-arkts skill。D 仅修改 SettingJevPage、CouncilPage、CouncilRunPage、SettingModelCouncilPage、ChatMessageTools、ChatMessageToolPreviewSheet，新增小 JevApprovalFacts/CouncilToolModePicker/CouncilSeatTools。仓内没有独立席位设置页，SettingExperimentalModelCouncilPageFull 仅跳转真实设置页，未修改。root 后续明确扩展 ownership 到 ChatMessageAskUserStep 的三处触控尺寸；未改 domain、DI、Service、Row、ChatPage 或其他 owner 文件。

## 冻结消费接口

- `ChatMessageTools/ChatMessageToolPreviewSheet` 新增可选 `@Prop jevSubjectHash:string=''`；`JevApprovalFacts {part:UIMessagePartTool,subjectHash:string}` 只在 parent pending、传入当前 hash 非空且 `readJevApprovalTriage` 匹配时显示。没有从 metadata 自取 hash 自证，也不自行推断 locator/user identity。root 用同 canonical subject helper 对 Row/preview 透传。
- 三事实仅“只读：是/否/未知、可逆：…、与任务相关：…”同色文字，Flex 自动换行；未改变 capsule 批准/拒绝、按钮顺序、风险颜色或推荐批准。新组件使用本地 V1 palette 与 dark/theme Watch。
- `CouncilToolModePicker {mode:CouncilToolMode,disabled:boolean,onSelect(mode)}` 是两配置页复用的 38 行组件；三项真实 off/search/full，描述全文换行、44vp 以上触控区。FULL 说明实际默认助手（首个配置）启用且可用工具与原审批设置，不暗示自动批准；席位模型保持独立模型库。
- `CouncilSeatTools {snapshot:CouncilToolSnapshot,active:boolean,onVerdict(request,verdict)}` 直接使用 B 的 frozen DTO 和消息结构。ToolCard 复用现有 ChatMessageTools / ChatMessageAskUserStep / ChatMessageToolPreviewSheet；请求保持实际 run/round/seat/conversation/message/part/toolCall/hash，不批量批准、不复制上一 child 批准。Recipe/Plugin ask_user 只投影表单，答案载荷通过原 parse/build helper 构造，提交仍是 parent request。卡片 key 带 subjectHash，使换 child 时旧答题草稿与详情 sheet 不继续绑定下一步。

## 页面闭环

- SettingJevPage 两新用途保留独立 mode/allowances；所有旧 makeJevSettings 重建都保留 approvalTriage/webAutomation，purpose patch 逐字段复制。全局 OFF/SHADOW 生效上限直接取 shared `resolveJevPurposeMode`，保留用途偏好且显示实际生效值。外发描述只称有限工具摘要、任务意图分类（不含原句）、有限页面摘要；明确排除原始脚本、凭据和完整参数。Web 消费者尚待 E12，当前文案不称网页执行已接通。
- 设置保存按实际 KV Promise 顺序串行完成，失败保留当前草稿与重试入口；未再保存后立即重读旧设置覆盖草稿。状态读取错误与实际写失败分开，保存成功不会被状态读取失败误报。初始设置读取失败不会开放默认草稿自动覆盖；离页停止 UI 回填。未发评估请求或连接测试。
- CouncilPage 持久默认功率进入 UI，本轮选择写入实际 RuntimeSetting 与 TaskInput；开始准备期间禁止更改功率，模式在 prepare/start 前固定。原动态席位 planning 调用显式 `toolMode:'off'`，不借 Chat provider；Settings 连通性测试同样显式 OFF。SettingModelCouncilPage 实际加载/保存 toolMode，沿原 dirty/save/back 流程，save 错误保留草稿。
- CouncilRunPage `subscribeTools(runId,listener)` 即刻回放并按 round/seat 整体替换实际快照；调用真实 `manager.submitToolVerdict(request,verdict)`，返回 false 明确提示审批已结束/步骤已变化。工具卡仅当前运行与当前 round 尚未终态且有匹配 pending 时交互。取消调用原 manager.cancel；离页释放 live/tools 订阅与定时器，不假恢复或取消整台宿主。
- 实际 snapshot sources 显示完整标题/长 URL/service，44vp 以上单次系统浏览器入口，只允许 HTTP(S)。来源只取 actual DTO，不从最终正文猜来源。轮次最终显示 completed/failed/cancelled/timed_out/interrupted 与真实内容/错误；已有部分文字不会隐藏最终错误。无 live manager 时不恢复执行；终态快照保留由 B 负责，UI 不重放 STARTED 写工具。
- 来源列表身份按 root 最终冻结的实际 `(url,service)` 使用 `JSON.stringify([source.url,source.service])` 联合键，替换生产稿已有的 index-URL 键；来源数据与显示不改。同 URL 的搜索/抓取服务记录各自保留。仅该文件必要 lint `/tmp/e11-ui-sourcekey-lint.log` 退出 0，官方 `No defects found in your code.`；无新增测试或整组重跑。
- 独立 B 已确认的综合裁决重开 P2 精准修复：synth 分支仍错误优先，live 空时使用真实 `result.finalRecommendation`，不会把已完成结果显示成“无裁决内容”。不增加历史恢复或 scheduler。
- root 已确认的 answered 控件尺寸 P2：仅 ChatMessageAskUserStep interactive 选项、清空、提交增 minHeight 44，清空另增 minWidth 44；回调、表单、装饰保持。现有 Chat 同受这三处最小触控修复。
- 独立 B 同一触控检查最后确认预览入口 P2：root 扩展 D ownership 到 AgentToolCapsule，仅 `canOpen` 可点击 Row 的 minHeight 从 32 改 44，非点击 else 保持 32。原预览回调、颜色、布局属性与状态均保持，未改其它分支。
  该文件仅一次必要 lint `/tmp/e11-ui-preview44-lint.log` 退出 0、0 error / 1 原有优先 Builder warn；未重跑其它文件、测试或设备。

## 必要验证

所有 owned 文件 <1000 行；`git diff --check` 通过。这 10 个受影响文件仅一次官方 ArkTS lint，日志 `/tmp/e11-ui-lint.log`，退出 0、0 error / 3 warn（已有 AskUserStep 优先 Builder 建议与两个原循环读取 State 的性能建议；未为建议扩大重构）。DTO/manager exports 使用真实 shared source；新增 UI API 均来自仓内既有 Column/Flex/constraintSize/bindSheet/common.startAbility 路径，无新 SDK 接口或 stub。ArkUI DSL 类型由 root 统一实际 CompileArkTS 反馈，不以普通 TS parser 空检查冒称 UI 类型通过。未新建/运行 UI 测试、全量测试、build/sign/device 或外部模型/账户请求；亮暗视觉、触控与真实 provider 验收仍是 root 独立门。
