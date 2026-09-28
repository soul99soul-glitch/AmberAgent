# E11 独立逻辑与调用链审查

2026-09-28。已读 E11 frozen contract / Jev-council design，沿已载 harmony-arkts skill 审查。当前生产 owners 与 root 装配仍进行中，**A 总结论 pending**；不将尚未装配的 caller/exports 报成最终缺陷。

仅拥有本报告，只读生产源；未自行运行测试、lint、构建、签名或设备，也未增加检查矩阵。具体问题待 final ready 源确认后精准回喂 owner/root；设备/真实账户验收保持独立边界。

## 审查责任

- Jev：保存后的 canonical pending subject、父/包 child/固定 target 绑定，中性 OFF/SHADOW/ACTIVE、用途 allowance 与外发材料、取消/迟到写回、原人类审批不变。
- Council：固定席位 ModelConfig、同 seat store 保存先于发布/效果、实际工具与来源回流、精确父 locator/child subject 审批、ask_user、取消/deadline/STARTED/冷重开及错误传播。
- Root：运行前固定 default Assistant/范围/批准设置，continued live 撤销只缩不扩、共享 discovery/package oracle/MCP、原 dispatcher/native signal、Service durable pending 后调度与删除取消、UI 当前 subject 消费。

## 当前状态

合同与已有 pending/store/model 边界已建立审查基线。A/C/E/root 当前阅读只用于理解装配接缝，不据 in-progress 代码签结或报告缺项。待各 owner source-ready 与 root integration-ready 后补最终证据和问题闭环。

### B Council domain final 分段通过

B 已 source-ready，最终只读核 `council/{models,runner,manager,validator,prompts,approval_wait}.ts`，当前没有 confirmed P1/P2：

- `normalizeCouncilToolMode`、旧配置 OFF、input 覆盖与固定 task/seat copy 保持；Manager 构造复制真实独立 ModelConfig pool/settings，没有 Chat import 或当前 Chat provider 回退。普通 seat generate 接真实 key/mode，synthesis 明确 OFF。
- `approval_wait.ts:31–46,52–104` 校验同 run/round/seat/conversation、exact parent locator/pending/无 output、完整 part 与 child subject；所有 pending waiter 在 emit 前登记。提交只结算本 waiter，重复/旧 subject/旧 part/错 signal 拒绝。真正 verdict 保存/继续属于 C，Manager 不冒充耐久存储。
- `manager.ts:422–490` 原根 signal→席位 signal→模型/审批等待同传，席位/总 deadline 覆盖 human wait；终态关闭迟到 text/tools，移除 waiter 和 pending publication。已修即时 onTools 取消时 raceCancel 必须接住原 producer reject 的实际竞态，`:111–118` 即使 signal 已 aborted 仍安装原 Promise 的结算处理。C/root 实际 paused native owner 清理反馈后再次窄复核：每席及裁判 finally 先 ended=true，再 abort 原自身 signal，成功/存失败/runner 异常均释放同 owner，peer signal 独立；完成结果不改写。
- 空 final 文本和未决工具不能 completed；失败/取消仍从最后工具 snapshot 保留真实 toolMessages/sources。`prompts.ts:18–29` 将真实工具输出/来源标为 data 回传后轮/裁判；JSONL 按 writeTail 归档，IO 失败公开为 result warnings，归档并非审批保存屏障。

只引用 B 已有必要 group `/tmp/e11-council-manager-group.log` 1/1 PASS（113.272ms）和 owner 的窄 types/lint 回执（types exit0，6 源 0 error/597 warnings）；terminal cleanup 在原 group 必要反例 `/tmp/e11-council-native-cleanup-red.log` → `/tmp/e11-council-native-cleanup-green.log` 最终 1/1（139.597ms）已读。本人未重跑。Entry loop/store/assembly 和 root 完整接线仍须后续最终复核，B 分段通过不提前代表 E11 总结论。

### Root partial-ready 已核 / 新发现

`RunToolCatalog.ets` 与 `ConversationRunTools.ets` 机械提取保持旧 Chat primitive profile、registry/introspection、tool_search 和原 subagent 附加路径；克隆保留 approval 三 flags、recipe/plugin envelope、mcpTarget 与原 activity conversation scope，refresh 同次替换 adapters/baseTools/final tools。未发现 confirmed 旧 Chat 回归。Service reserve/delete/cancel 的分诊取消与 durable final pending 后调度、async queued mutation 和 Row exact parent locator 当前源已核；完整 Jev/Council DI 仍待 ready。

**P2 — 已发 root，精准源修复核关闭：** 旧 `openToolPreview` / Builder 在 pending 先打开详情、Jev 后异步 metadata 回填时仍传打开时捕获的 private part，中性三事实须关闭重开才出现。Root `ChatPage.ets:2894–2906` 新 `currentToolPreviewPart()` 从 Service canonical currentMessages/exact locator 找同 parent toolCallId；`:3578–3579` 同传当前 part/current subject，异步 metadata 现在进入原详情，child subject 变化仍丢旧 facts。没有新增 key、协议或刷新框架。

`AppContainer.ets:163–171` Jev host 初始化只注册真实 read/queued mutate/settings/evaluate closures，没有立即调用回 DI。Host 仅从保存的 permission_trace 提取实际 policy，mutates=false 不伪称 readonly；两 purpose shadow 各自 serial/bounded20，只存 metadata/facts/selected 等窄观察，不保存 bindingJSON/原命令或页面正文。完整 A 迟到 queued guard 与 C Council source gate 仍待最终 ready。
