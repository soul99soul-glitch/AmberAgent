# P5 普通创作恢复独立审查

状态：**CLOSED，最终冻结源码及独立实际调用复验通过，无剩余已确认问题**。只审普通创作、原请求 runtime snapshot 和生产 DI；润色、克隆/收录/交换、专属 UI 视觉由其他 reviewer 负责。只读生产代码，报告由本 reviewer 独占。构建、实屏、云服务和后台存活不由本报告替代。

## 已核实际链路

`NovelWorkspacePage.recoverOrdinaryRun → creation.retryOrdinaryRun/resumeOrdinaryRun → readWorkspaceSnapshot → 原 record ID/branch/transcript 验证 → 原 request/runtime → NovelChatRunAdapter.prepareOrdinaryRequest → NovelInteractiveAdapter → AppContainer.resolveNovelInteractiveRuntime/novelRuntimeConfig → buildProviderApi → provider`。

- ordinaryRun 原输入、context/system、原 canonical history、tool profile、model target、候选来源和 runtime snapshot 持久在当前分支 `.amber` project record。repository 显式 decode 和 snapshot encode 均保留新字段；公开交换没有 export 该运行记录。
- Retry 使用原 request.history，不将失败 partial 当作模型输入；呈现 transcriptPrefix 是当前保存对话，所以失败内容保留且新请求/结果追加。Resume 用原 transcriptPrefix、保存的 canonical partial 和同 response ID/cursor，没有第二个 user；outward snapshot 与 active historyCount 使用同一呈现前缀。
- ordinary checkpoint 核对同 branch、record ID、running 状态后一起持久正文/cursor。terminal 后迟到普通 checkpoint 拒绝；现 transcript 不再匹配时在解析 provider 前拒绝恢复。
- AppContainer 快照 providerId 是容器 ID、modelId 是内部 UUID；Responses cursor 身份单独使用 effective provider ID。冷恢复仍按容器和模型 ID 查当前认证，再核 effective endpoint/header 指纹。无 key/header 内容写入 snapshot；原生成 body 参数、assistant 和 context window 从冻结 JSON 读取。
- Entry.prepareOrdinaryRequest 透传 adapter；AppContainer.makeProvider 将 Responses 参数传给 buildProviderApi 第四参数，后者 forward runId/resumeFrom/partialMessages/onCheckpoint 到 OpenAI API。非 Responses 路径继续 runtime.api；支持门限限定官方 OpenAI API-key Responses。

## 独立验证

- 普通领域 + creation + C5 continuation + AskUser 取消组合 **29/29**，`/tmp/amber-p5-ordinary-review-focused.log`。
- runtime snapshot 实际 body 与指纹测试 **2/2**，`/tmp/amber-p5-runtime-review-focused.log`；原 body/窗口保留，secret 不出现，认证轮换允许，endpoint 或配置 header 漂移拒绝。
- 额外独立执行实际 AppContainer 的 `buildProviderApi/novelRuntimeConfig/resolveNovelInteractiveRuntime` 函数抽取（`/tmp/amber-p5-runtime-di-review.cjs`）：原 container UUID 与 effective overwrite ID 分离、cold lookup 参数正确、当前认证轮换、冻结温度和窗口、Responses 第四参数 runId/cursor/partial/checkpoint 全部通过。实际请求 builder 仍生成原温度 0.3/窗口 16000；HTTP 认证使用旋转后的当前 key，快照没有 key。该检查证明生产 DI 执行接线，API 底层 HTTP 行为另待 transport 最终冻结测试。
- Transport actual Creation → Adapter → HTTP、completed checkpoint crash 和 resume/terminal 组合独立 **39/39**（`/tmp/amber-p5-ordinary-http-review.log`）：第一次 POST 断线保存正文/游标，冷重建真实 creation/repository 后同 ID GET/no body/starting_after 补正文，只有一个 user/assistant；validated completed raw checkpoint 后最终 commit 故障，冷恢复零新增 HTTP。过期 ID/错误 provider/禁用 resume 不 POST 兜底，checkpoint 失败不推进 cursor。
- AskUser 原状态反例修复后独立组合 **30/30**；原 `/tmp/amber-p5-tool-failure-review.mts` 变 `status:failed/retryAllowed:true`，新续接原 operation/history 保留；额外 `/tmp/amber-p5-tool-failure-late-review.mts` 确认终止续接的迟到 checkpoint 拒绝。outward 普通恢复 hooks 的既有 ETS 测试先通过 **5/5**，最终新增 same-ID 用例结果见下方。

最终冻结统一复验：

- 普通领域、creation、C5 continuation 和 AskUser 取消：**30/30**，`/tmp/amber-p5-ordinary-review-focused-final.log`。重复工具重试再失败的保存正文仍可读，原 text 不混入 frozen retry 输入；第三次明确重试完成。
- 实际 Adapter、Creation→HTTP、Responses resume/terminal 和 runtime snapshot：**69/69**，`/tmp/amber-p5-ordinary-http-review-final.log`。四条 actual HTTP/AskUser 用例覆盖 cold same-ID GET、completed checkpoint 后收尾崩断、答案执行计数=1，以及正常失败/保存失败均不重写已拒绝 owner 或额外生成。
- 实际 ETS 初次 continueTool、cold retry/resume、同 ID 流式切片、冷卡与复制：**7/7**，`/tmp/amber-p5-ordinary-ui-hook-review-final.log`。额外初次 hook 原反例现 core/UI historyCount 都为 1，同 ID 新正文可见。
- 原实际 partial 反例最终 **3/3**，`/tmp/amber-p5-askuser-partial-review-final.log`：UI snapshot 与 durable raw record 均保留失败正文，原 retry history 与第 3 次实际 provider 输入都不含该失败正文，原答案仍仅执行一次。再将这次真实保存的完整 record 交给生产 ETS `ordinaryPartialText/copyOrdinaryPartial`（`/tmp/amber-p5-real-partial-copy-review.cjs`），精确展示并复制 `PARTIAL_BEFORE_FAILURE`。

## 已确认具体问题

1. **AskUser 已答后失败不可恢复，已修复。** 原 `creation.runToolContinuation` 的失败 catch 只 emit failed，留下 `waiting_user`。真实 creation + file repository 反例从作者回答 checkpoint answered → 网络失败 → cold read 得到 waiting_user 与全部恢复入口禁用。Owner 已冻结新 running continuation 原 operation/history/runtime，失败/停止收口为 failed/interrupted；原反例变绿、迟到 checkpoint 拒绝。恢复不能重发最初 turn 重问作者。
2. **真实 adapter same-ID 续接结果被历史过滤，已修复。** 实际 AskUser executor counter 测试曾在 cold retry 得到“模型没有返回内容”，因 tool loop 复用原工具 assistant ID，Generation 将其当旧 history 排除。Owner 只将 target tool message 移出 exclude，并引入统一 `ordinaryRunHistoryCount`。首次 UI hook 原真实 ETS 反例曾为 core count=1/UI count=2/activeMessages=[]；现 Workspace 恢复和 continueTool 都接同 helper，cold active 仍用实际 active.historyCount，原反例与新增 ETS 行为用例通过。
3. **实际失败 partial UI 可见但冷保存丢失，已修复。** 真实 provider 第 2 次先输出 `PARTIAL_BEFORE_FAILURE` 再网络 throw，曾出现 UI 可见但 durable 仅 user+answered tool。Adapter 现只在非 Responses terminal failure 保存尚未 checkpoint 的最新 raw canonical；已拒绝保存不再写同 owner，Responses 仍由事件/游标原子 checkpoint 负责。领域将保存正文与原工具输入分开：只更新原工具审批/执行 output，原 text/reasoning 保持，最后收口也保留同 ID canonical partial。原反例、保存拒绝、actual executor 计数与冷卡/复制全通过，没有增加生成重试或失败版本 store。

交互范围已核实：普通新 turn 重试保留旧失败回复；工具续接沿同一 assistant，明确重试成功后替换其失败部分。当前恢复文案说明“重试会替换本次工具续接的部分内容；需要保留可先复制”，失败状态下复制入口读真实 durable 内容。没有把工具重试描述为 Responses 服务端续传，也没有把旧 partial 当新输入。

证据边界：上述领域调用使用真实 repository/service；provider 配置测试构造实际请求 body。未运行设备、真实云服务或系统后台任务，不将测试端口和构建成功作为这些能力的验收。
