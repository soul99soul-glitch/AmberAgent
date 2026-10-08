# Amber Android × Jev v2 计划

日期：2026-09-30。依据：iOS 线程「评估 jev 决策在 amber 链路的合理性」的评估与 `amberagent-ios/docs/product/jev-v2-plan.md`，对照本仓库 Jev 集成（`core/jev`、`app/.../core/jev`）逐项核对。

## 与 iOS 的差异（决定哪些照搬、哪些不搬）

- 安卓没有独立的 active 等待预算：active 与 shadow 都等到请求 deadline（1.2 s / Vercel 4 s）。因此 iOS 的"400 ms 迟到白付费""shadow 指标高估 active"两项不存在；对应的安卓问题是 **shadow 也阻塞请求准备路径**。
- 安卓的上下文筛选省略标记指向 `conversation_expand`，但该工具只返回消息文本部分（`UIMessage.toText()` 不含工具输出），被省略的块实际取不回。

## 共同约束

- 每个新能力是独立用途：off = 零网络；shadow 只记指标、不改行为、不阻塞主路径；active 失败/超时/跳过一律回到现有行为。
- 已有用途行为不变（除 Phase 0 修复点）。
- 每个 phase 先写失败测试再实现；完成后跑 Jev 全部套件与相关回归，并由 review 子代理（Sonnet 5.5 + high）检查逻辑闭环、调用链与 UI 细节。

## Phase 0：修复现有问题（与 iOS 进度无关）

1. **上下文筛选的取回路径**（`JevToolOutputProjector`）
   - 同名工具、同参数的调用在请求中已出现过时，后一次输出不再投影（保持全文）；前一次的投影结果不变（缓存锚不变，前缀稳定）。
   - 省略标记改为"用相同参数再次调用该工具即可取回原文"。
2. **shadow 不阻塞主路径**（工具发现、记忆召回、上下文筛选）
   - `JevRuntime` 提供后台 shadow 作用域；三处调用方在 shadow 模式下把判断放到后台，立即返回原路径结果。校准记录与指标照常写入。
   - 模型调度（议会席次）不在首字路径上，网页/屏幕循环的 shadow 是 dry-run 语义，不改。
3. ~~记忆候选不给置顶记忆出题~~（已撤回）
   - 语料基线 mt01 证实：置顶记忆的判分会决定"全部判为不相关 → 回退词面基线"是否触发；不给它出题会让只有置顶记忆相关的任务回退到基线，混入 10 条无关记忆。它不是白问，保持原样。

验收：同参重读得到全文；shadow 下三处调用立即返回、后台仍产生一次出站和校准记录。

## Phase 1：压缩保留（TOOL_RESULT_RETENTION）——已实施

- `JevToolResultRetention`：候选口径复用 `PreparedContextEditor.wouldClearToolResult`，且其后已有 assistant 回复；候选仍在保留窗口内时后台一次最多 8 条问 Jev，保留概率 ≥ 0.7 才跳过清空（>16k 仍截成预览）。
- 移出窗口时未判定 / 失败 / shadow / 判定不保留 → 照旧清空并固定，迟到结果不翻转；请求准备路径不等待网络。
- state 预算按 UTF-8 字节考虑（大纲 6k 字、样本 800 字），避免中文会话超 48KB 静默失效。

## Phase 2：自动批准复核（AUTO_APPROVAL_GATE）——已实施

- `JevAutoApprovalGate`：三道风险（破坏性 / 外发数据 / 超出任务范围）+ "用户是否明确要求"；任一风险 ≥ 0.8 收紧为人工审批；明确要求可豁免，外发数据除外；子代理不享受豁免。
- 接入 `DefaultRunKernel`：`resolveDecision` 以 `settings` / `settings_unattended` 放行、且本来需要审批的 Auto 调用才复核；命中写入 metadata `jev_auto_approval_escalation`，审批卡显示"自动批准已暂停：原因"。
- 高风险自动批准的恢复路径不释放被复核收紧的 Pending。
- 只发送脱敏后的标量参数摘要（与 iOS 审批分诊同口径），不发送参数原文。

## Phase 3：完成声明校验（COMPLETION_CHECK）——已实施

- `JevCompletionCheck`：事实门本地判断（本轮成功写入文件，且最后一次写入后没有运行检查命令）；满足才问"是否宣称完成 / 已验证"，≥ 0.8 续跑一轮。
- 接入 `DefaultRunKernel`：最终回复无工具调用时判断；续跑以一条**可见的用户消息**（"【自动校验】……"）追加一轮——一轮 agent 输出合并在同一条 assistant 消息里，若只注入系统提示，续跑输出会拼进原文字且请求以 assistant 收尾（预填，较新的 Claude 模型不支持）。每次 run 至多一次；下一步处于 FINAL 预算阶段时不续跑。
- 最终回复只取最后一个工具调用之后的文本；发送写入路径，因此同时需要"任务文本"和"工具元数据"两个范围。

## 不做

- 模型调度改"任务分级"、删除旧上下文筛选：与 iOS 计划一致，本轮不做。

## 已知差异与遗留

- Recipe 嵌套步骤不经自动批准复核（步骤由配方定义，非模型临时决定；外层 Recipe 调用已复核）。iOS 为 recipe 执行器也接了复核。
- 设置页（用户决定）：普通模式每个用途只有一个开关，打开但不可用时显示原因（总开关 / API Key / 缺少的数据授权）；"影子"改名"测试"，三档只在开发者模式出现。每个用途所需授权统一定义在 `JevPurpose.requiredScopes`。已通知 iOS 线程同步。
- 全部效果尚无真实 Jev Key 与真机数据验证。
