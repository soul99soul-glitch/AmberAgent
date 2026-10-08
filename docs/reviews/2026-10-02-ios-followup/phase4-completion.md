# Phase 4 完成记录

小说设定 pending/accepted/rejected 收据留在原消息后，真正丢失来源才放到尾部。本章计划专用卡区分待审批、批准未执行、真实应用、拒绝、结构化失败和无法确认；应用与拒绝回执匹配 proposal_id。批准/拒绝走原 toolCallId+locator 和 CAS/领域事务，终态无重复动作，完整 patch 和 Jev facts 保留。

root8投影+15领域通过，独立8+4行缓存+15领域共27/27通过；chat/纯投影 tsc 通过，定点 lint0error。真实 ArkTS 构建发现嵌套 builder 属性收窄丢失，仅提取四行读 helper 后通过。设备发现查看/收起触区宽度36.3vp，仅加 minWidth44；最终 build3/sign3/verify-app/原AGC覆盖安装启动通过。

Pura90 模拟器原位置五计划状态、三设定状态、长标题/状态对齐、完整patch滚动、浅暗主题、底部边距与44×44vp完整触区通过。实际采用经过原资料确认页并新增资料，实际忽略和采用都在 force-stop/reopen 后留在原来源位置。独立 reviewer 直接检查10张代表图与最终AX，没有未处理的具体发现。原浅色与生成式开关 ON 已恢复。

证据：/tmp/amber-followup-phase4-ui-acceptance.md、/tmp/amber-followup-ui/phase4-final-touch-full-{light,dark}.jpeg；/tmp/amber-ios-followup-phase4-build3.log、phase4-sign3.log。fixture写作模型故意不存在，界面恢复在模型校验处停止，没有付费生成/计划批准请求；领域真实工具链另由15项测试覆盖。物理设备与云端仍是不同验收边界。
