# Phase 4 实施与验证

设定提案和本章计划卡保留在原消息位置，结果按现有持久状态与真实工具回执呈现。批准继续复用原调用链，没有新增业务执行入口。

- `NovelWorkspacePage` 使用窄投影将设定 `pending / accepted / rejected` 放到对应来源消息后，来源同时兼容 `NovelMessage.id` 与 `uiMessage.id`；只有来源不在完整持久历史中的提案进入尾部。活动运行截取 history 不会把暂时隐藏的来源误判为 orphan。
- `NovelSettingProposalCard` 展示待确认、已采用、已忽略；已处理状态没有动作。采用和忽略仍走原 repository mutation / material adoption 链路。设定采用失败仍在原错误入口显示，保持 pending 供重试，没有创建假的 durable failed record 或额外持久错误字段。
- `AgentMessageRow` 为合法的整份本章计划提案提供单一专用呈现，卡内批准/拒绝传原 `toolCallId + locator` 给宿主，继续沿 `continueNovelTool` 路由。该分支保留 Jev facts，不再同时渲染 generic approval。混合路径、非法 patch 和坏 JSON 保留原 generic UI。
- 计划 `approved` 无 output 仅表示已批准、待执行；只有 output 的 `status=accepted` 且 `proposal_id` 与 input 精确相同才表示已应用。`rejected` 回执也要求相同 ID。原 approval denied 表示已拒绝。
- 结构化失败复用 `indicatesFailure`，接受实际错误标准化返回的无 `proposal_id` 结果，或相同 ID 的失败；显式不同 ID 的失败不能归为本卡终态。无法确认的结果保持“结果待确认”并显示原 output，避免假成功和丢失错误证据。
- 卡片保留所有 patch 的原序、完整写入内容和清空语义；状态与标题留 8px 间距，动作维持 44px 最小高度。没有新增 OS API。

验证：

- 先 RED 复现缺失原位置设定投影、计划批准与应用无法区分，再 GREEN：投影测试 **8/8**。
- 原工具继续和材料采用 domain 回归 **15/15**；AgentMessageRow 分组缓存回归 **4/4**。
- chat tsc 通过；临时复制两份纯 `.ets` 投影为 `.ts`，依实际 domain 类型独立 tsc 通过。
- 7 个改动 ArkTS 文件 focused lint：**0 errors / 2 既有组件 warnings**。最终计划投影单文件 lint：**0 defects**。
- root 的 build1 暴露嵌套 builder 中 `this.project` 的非空收窄丢失；已用四行只读 `settingProposalsAt` helper 在方法内收窄修复，没有扩大运行时兜底。修复后的 Page 单文件 lint **0 defects**，投影 **8/8** 再次通过；后续 build 由 root 串行执行。

本记录确认源码、focused tests 和 lint 边界；build1 的编译失败已修，build2、签名、安装、明暗主题及真实卡片位置/尺寸验收由 root 串行进行，未在这里冒充完成。离线 fixture 来源见 `/tmp/amber-followup-phase4-fixture-location.json`，包含实际 repository mutation 生成的设定终态和计划回执，native export/import/reopen 已验证，零 Provider 请求。

模拟器实际检查发现查看/收起触区只有 36.3×44vp；仅增加 minWidth44，原按钮布局保留。该文件定点 lint0 defects，root build3/sign3/install 后浅暗均实测 44×44vp。
