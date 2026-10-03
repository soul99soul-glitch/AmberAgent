# P6 小说状态保存时钟碰撞独立复审

2026-10-03。**独立复审通过，54/54 测试通过。** 本轮只审查 `novel/state_rebuild.ts` 的 persist commandId，生产代码只读。P5 润色源与此前已通过的审查一致；这是 P6 全域复验暴露的既有低概率同毫秒 receipt 碰撞，不是 P5 润色行为变更。

## 修前实际反例

固定 `Date.now()`，执行真实 `creation.startStateRebuild → FileNovelRepository.commitProject`。初始化和目标规划都为 cursor 0 / running；原 commandId `${operation.id}:state:${cursor}:${status}:${Date.now()}` 相同，仓库把目标规划保存当作初始化 receipt 重放，返回旧 `targets=[]`。

独立运行 `/tmp/amber-p6-state-clock-probe.ts` 确认实际结果：`status=failed, cursor=0, targets=0, requests=0`，错误为“状态任务尚未完成，未提交重建结果”。模型尚未启动，因此依赖模型请求出现的 held 测试也会一直等待。

## 最小修复与独立复验

实现 owner 只将 persist commandId 的时间戳后缀换成已有 `novelId()`，时间字段和业务流程保持原逻辑。`models.ts` 的现 ID 工厂每次调用递增 `novelSeq`，ID 包含该 sequence；即使同毫秒且随机值相同，两次保存仍得到不同 commandId，不需要新增状态机或重试层。

独立 `/tmp/p6-state-clock-independent-review.ts` 同时固定 `Date.now()` 与 `Math.random()`，穿过实际 Creation、文件仓库和模型订阅，确认：

- 观察到初始 `cursor=0/targets=0/running` 与目标规划 `cursor=0/targets=2/running` 两次保存，后者未被 receipt 重放吞掉。
- 两章各发一次真实抽取请求，最终 `completed/cursor=2/targets=2`。
- 新建冷 repository/creation 后仍读取到两个正文事件和 `Rider` 待确认身份，完成 operation 持久化正常。

`novel_state_rebuild.test.ts`、`novel_structured_state.test.ts`、`novel_continuity_repair.test.ts` 三个完整 suites 加独立探针合计 **54/54 通过**，涵盖原有重试、分支/正文变化拒绝、取消、身份确认及修复提案链。

证据日志：`/tmp/p6-state-clock-independent-review.log`、`/tmp/p6-state-independent-focused.log`。未执行构建、安装或设备操作，本报告不扩张对应验收结论。
