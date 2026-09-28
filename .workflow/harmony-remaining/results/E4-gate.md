# E4 gate：Recipes

2026-09-28。源码实现与独立 A/B review 通过，Release 构建和本地签名验证通过；C 设备视觉验收待连接恢复，E4 保持未全部完成。合同 `packets/E4-contract.md`。

## 已闭环的产品链

设置 → Sandbox → Recipes → Workspace 固定候选预览 → candidate/base hash CAS 安装、启停、删除 → 当前助手真实 primitive 目录 → 同轮 tool_search / schema / execution 刷新 → 固定 descriptor 顺序 runner → 每步当前批准、持久 started 后副作用 → 结果与取消/未知状态。

页面手动启动保存真实助手会话及固定父 ToolPart，await repository.save 后导航，恢复只执行该调用，不发模型或辅助生成。仍需要现有 ChatRuntime 的真实 Provider/模型配置，不注入假模型。子代理采用原 explicit allowlist，同一 Store/Adapter 与 native target guard，保留原 injected subagent_report。

## 必要检查与修复

- Manifest/schema/binding/canonical 五项域检查通过；保留名 display_title 的唯一真实缺陷只做单项 red→green。实际 Swift/JS Unicode/slash fixture canonical 357 bytes、hash `fbc71d9558f002fcaba9b9f74de1b5743de7a6d0f13a38f057d59cf78fd55790` 一致。
- 实文件 Store 一组检查通过：prepare 零写、candidate/base CAS、停用保持、原子写失败保留、坏数据显式失败及生命周期。未增加排列组合测试。
- Runner 六项关键检查、唯一 Workspace→真实已有 CPython addon→Workspace 链通过；两次副作用分别批准，无提前执行或重放。
- Loop 原三项中两项首跑通过；目录同参数重新搜索缺陷修后仅重跑受影响的一项。覆盖手动零模型生成、固定 checkpoint、混合批次暂停、目录更新及 scope。
- 手动入口 save barrier、审批 UI 投影各一项通过。取消/unknown 状态与普通冷启 started 恢复只做一项实际 red→green；不会重演已开始副作用。
- Child 同一项强化为真实 GenerationSubAgentRunner + 实际平台 ScopedRecipeLoop，修复 report 被二次 scope 丢弃后 1/1 通过；原 report 对象/schema/execute 保留，首轮报告完成，新增 Recipe 不越原 scope。
- 独立 `E4-review-model-store.md`、`E4-review-integration.md` A/B PASS，原四项 P2 已闭环，没有开放 P1/P2。只读审查没有重跑已通过检查。
- Chat strict types exit0，diff check exit0；owner 新模块窄 ArkTS lint 均 0 error。最终 RouteParams/RouterOptions 与 ScopedRecipeLoop 两文件 lint 0 error/1 warn（`/tmp/e4-final-two-files-lint.txt`）。
- Root 十文件 lint 的 SDK 性能子扫描持续 13 分钟停在84%，已结束本任务该子进程，不改 SDK 配置。语法输出 0 error/563 warn（`/tmp/e4-root-final-lint.txt`），**性能扫描未完成，退出0不算完整 lint PASS**。编译/类型与新模块窄 lint 独立成立，未继续循环重试此工具问题。
- 最终 Release `/tmp/e4-release-route-final-build.log`：BUILD SUCCESSFUL 12.341s，消费全部 E4 源码；仅编译回喂修正显式字段、文本类型、路由对象接口，未重复业务测试。
- `/tmp/e4-release-sign.log`：本地 TestCA codesign、SHA256 digest、verify-app success。该临时包不用于设备安装，设备仍用项目 AGC 签名。

各专项细节与原日志索引见 E4 owner/review 报告。宿主逻辑证据不冒充 Harmony 设备执行。

## 待补门

新包完成后唯一只读 hdc list targets 返回 `[Empty]`，无可用设备；没有再重启、重置或重复模拟器测试。待恢复后仅做最终 Release 安装/启动，以及列表、固定长预览、输入键盘、逐步批准、失败/取消结果的必要亮暗色截图并独立 C 审查。E3 最后 Mosh 布局图也保留待补，不重复已通过原生矩阵。

E5 独立源码实施继续；此 gate 不将设备或 SDK 性能检查限制标成完成。并发外部 Mac Gateway 提交不属于 E4，也未纳入本阶段审查结论。
