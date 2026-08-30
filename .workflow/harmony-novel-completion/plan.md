# Harmony Novel Completion

## Goal

按 `docs/plans/2026-08-30-harmony-novel-product-completion-plan.md` 连续完成 C0-C7，
复用 Chat 的消息、流、工具和呈现深模块，补齐职责模型、候选审稿、持久代笔和后台恢复。

## Success Criteria

- Chat 与 Novel 共用消息行、Timeline、Markdown/reasoning/tool/ask-user 呈现。
- Novel 交互生成复用 Chat provider/tool loop/accumulator/stop，不再走独立 provider。
- creation/stateSync/review 可分别选模型并持久化。
- plan→candidate→review→rewrite→atomic commit→next plan 可恢复且 exactly-once。
- 后台代笔、进度/失败/完成通知和项目深链真机闭环。
- 每 Phase 经逻辑/调用链 review 与 UI review，证据问题修复后两域测试、lint、构建、安装、截图通过并独立提交。

## Current Context

- Branch: harmony-preview；执行开始 HEAD `1efa907ccc79ba1911989f92cc696cb320aa9ff3`。
- 用户脏改：`.agents/skills/harmony-arkts/SKILL.md`；不得覆盖或提交。
- 相关未提交修复：`NovelBatchPolishSheet.ets` 扩大采纳点击区；在对应 UI Phase 复核后归并。
- 真机：`5MT0225B12013017`；API baseline 12；AGC 签名链已可用。

## Constraints

- 状态装饰器全 V1；Array/Object 以整体赋值触发 UI。
- 新 Harmony API 先查官方文档；高于 API 12 必须 canIUse + 明确降级。
- 不改/构建根 `app/` Android；iOS/Android 只作只读基线。
- 不复制 Chat provider、Markdown、stream accumulator、tool loop、stop、follow 状态机。
- 保留脏工作树，逐路径暂存；不使用 add -A/stash/reset/checkout/clean。

## Risks

- NetStack 在目标设备的 SSE 回调存在挂死历史；用受控 SSE 资格门验证，禁止假流式。
- ChatPage 当前为大文件；先机械提取并让 Chat 自用，避免行为与 UI 漂移。
- 项目仓库现有 revision/写入非 CAS/非原子；提交层必须在代笔前补齐并做崩溃矩阵。
- WorkScheduler 执行窗口有限；真实暂停/恢复替代伪完成，不创建重复后台生成链。

## Approval Required

用户已明确授权持续实现、每 Phase 使用 subagent review、构建、安装、真机验证和按 Phase 提交。
删除旧生产代码仅在 C7 确认零 caller 后按明确文件清单执行；不做其他破坏性操作。

## Work Packets

- C0：计划/基线/characterization。
- C1：共享消息行与 Timeline。
- C2：共享交互运行时与真流式资格门。
- C3：持久 transcript、真实工具、职责模型。
- C4：合同/候选/审稿/CAS 原子提交/真实分支版本。
- C5：完整持久代笔流水线。
- C6：WorkScheduler 无头后台、通知与深链。
- C7：旧链清退与全产品真机终验。

## Integration Policy

主 agent 持有集成权。实现 agent 如启用必须拥有不重叠文件；review agent 只读。
subagent 结论逐条回到真实代码/测试/截图复核，理论风险不直接转化为改动。

## Verification

- 受影响测试先行；chat/deepread 两域全量 0 failure。
- `harmony/scripts/lint-arkts.sh <changed>` 0 error。
- `node scripts/harmony-inventory/build-harmony-app.mjs` 生成 AGC 签名包。
- `hdc -t 5MT0225B12013017 install -r ...`，启动、截图；滚动/流式问题补视频或帧证据。
- 每 Phase 运行 workflow artifact 自检并记录提交 SHA/证据。

## Reusable Artifacts

- 本 workflow 目录。
- 产品完成计划及每 Phase review/result 记录。
