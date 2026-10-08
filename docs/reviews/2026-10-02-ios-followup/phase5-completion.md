# Phase 5 交付记录

四个新用途已接通：工具结果筛选、上下文保留、自动批准复核、完成校验。全部默认关闭，独立 off/shadow/active 与数据范围许可；全局 mode 作为上限。复用原 Jev 协议、工具审批和发送链路，没有新增重试、第二套审批或自动验证循环。

工具筛选与保留仅修改 prepared projection，canonical history 保留原结果；实际 file_read/session_read 正文可筛选，同参重读恢复全文。自动、手动压缩消费同一投影。自动批准复核接到前台、子代理、议会、recipe/plugin/goal 的实际执行前，风险暂停通过原审批卡显示原因。完成提示只绑定当前 run 和选中历史，用户点击才续跑；已有草稿/附件/编辑只追加验证文字。

独立 A/B/C review 的具体问题均已修复：当前任务遗漏、ScopedRecipeLoop 重装配遗漏、同消息内重读识别、旧分支提醒、整个评估 deadline、执行前撤销许可，以及审批风险理由未显示。详见三个 review 报告。

## 最后必要修复

真实模拟器离线主链发现非流式工具续跑会替换同一 assistant 的旧工具结果。只在 `generateAssistantOnce` 非流式入口将单次 reply 按 delta 追加，成功与错误 partialChunk 共用入口；accumulator 原流式完整快照替换规则保持。仅新增一个必要回归，覆盖两次审批续跑、Provider 输入、canonical 工具输出和完成事实。相关既有测试 79/79 通过，独立 review 76/76 通过；没有继续扩写测试。

## 验证证据

- 整合测试：2026-10-03 chat 2787/2787、deepread 1189/1189、Entry 78/78；两共享模块 tsc 通过。
- 最后 2026-10-05 精准修复：相关 79/79、独立 76/76，chat tsc 通过，单文件 lint 0 errors。上述全量结果早于最后修复，不冒称再次全量运行。
- 最新 `b2d054bd7` 当前工作区构建成功，签名及 verify-app 通过，原 AGC HAP 覆盖安装成功；保留此前提交和 WIP，没有 commit/push。
- 构建与签名日志：`/tmp/amber-ios-followup-phase5-build-final.log`、`/tmp/amber-ios-followup-phase5-sign-final.log`；必要回归和独立 review 日志：`/tmp/amber-followup-nonstream-regression.log`、`/tmp/amber-ios-followup-phase5-final-narrow-review-tests.log`。

模拟器最新主链已通过：真实文件 write/edit 各批准一次，实际输出均由本地服务校验，最终回复仍保留两项工具结果，完成提示显示。浅色实际验证/关闭触区 44×44vp。一次主链 HTTP 记录为 chat3、辅助2（含零候选 memory extraction1）、Jev4，零拒绝；完成问题实际请求两题。模型和 Jev 服务全部为绑定 localhost 的自有离线 fixture。

浅暗 UI、实际草稿保留/只追加、四用途设置以及直接工具/recipe 风险原因均验收通过，无需新增 UI 修复；详见 `phase5-ui-review.md`。暗色必要重放成功主链计数相同，另有一笔来源未确定的 fixture 拒绝计数，报告保留此事实，没有将其当作零或掩盖。

自有聊天和工作区文件经正常 UI 删除，自有小说按产品正常软删除。公开自有备份/导出文件已精确移除。使用最后 stopped-app 新鲜配置仅删除 synthetic provider/assistant/Jev，并核对：原两助手/九服务商 JSON 原字符串完全相同，原 Jev key 仍不存在，没有自有 UUID 配置引用，设备 readback SHA 完全一致，文件 UID/GID/mode 未变。其他当前设置和运行计数保留，未用旧整份配置覆盖。恢复后启动到原主页；本地 mock 停止，模拟器恢复原停止状态，HDC forward 列表为空。验证后移除了含原凭据的临时捕获和只读数据库副本，保留非敏感 proof `/tmp/amber-phase5-cleanup-proof.json`。

五个 phase 状态已完成。真实付费 Provider/Jev、物理设备性能和长时后台不属于本次离线验收证据；本轮完成不表示所有 iOS 能力完全相同。
