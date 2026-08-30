# C1 Integration

## Accepted

- canonical 富消息协议下沉到 DeepRead；Chat 改为重导出，旧 `kind`/epoch/string approval/
  `streamIndex` 只在一次性迁移适配中读取。
- 从 Chat 提取完整 `AgentMessageRow`，保留 reasoning、tool、ask-user、subagent、council、
  Markdown、footer 与 streaming key 行为；Chat 成为首个调用者。
- 提取 `InteractiveMessageTimeline`；Chat 保留原窗口、跟底与 scroller 状态机，Novel 使用
  text-only canonical 投影与同一行渲染器。
- review 确认 capability 只能门控操作。普通 tool、ask-user、mini-app、subagent、council
  在只读宿主仍显示内容，且未授权入口不注册无效手势。
- UI review 确认重复 `Amber` 标签已消除、Novel 16vp gutter 无二次叠加、用户气泡/
  assistant Markdown/输入器基线和长文本换行正常。

## Runtime Defect Closed

- 初版真机从小说列表打开“修仙日常”会终止应用进程。
- 单变量二分定位到 Timeline slot：父组件局部 `@Builder` 直接作为 `BuilderParam` 传入后，
  运行时 `this` 被重绑定到子组件。
- 根据官方 ArkUI 文档改为箭头包装父 Builder；红色设备回归脚本由稳定失败转为稳定通过。

## Review Fixes

- 修复只读 capability 把整张 tool/ask/mini-app/subagent/council 卡隐藏的问题。
- 二次 review 发现 pending ask-user 的候选项也被隐藏；改为静态 chips 保留内容，只有
  interactive 分支注册点击，文本输入/清空/提交继续受 capability 门控。
- 修复 Novel header 的 `amber` + `Amber · 讨论` 重复显示。

## Rejected

- 未为 Novel 建第二套富消息 schema、Markdown renderer、stream accumulator 或滚动状态机。
- 未用 disabled/no-op 手势伪装不可用操作。
- 未在 C1 把一次性 Novel completion 标为 live streaming；真实流式资格门留在 C2。

## Verification Evidence

- DeepRead：658 passed，0 failed。
- Chat：2012 passed，0 failed。
- ArkTS lint：0 error；仅既有 custom-component performance warning。
- AGC build：`BUILD SUCCESSFUL`。
- Device `5MT0225B12013017`：install success；`/tmp/c1-novel-open-check.sh` PASS。
- Screenshot：`/tmp/c1-final-verified.jpeg`、`/tmp/c1-workspace-bottom.jpeg`。
- Logic review：最终通过，无剩余可复现问题。
- UI review：最终通过，无 C1 引入的证据缺陷。

## Commit

本文件与 C1 实现同一提交；SHA 以该提交 Git 记录为准。
