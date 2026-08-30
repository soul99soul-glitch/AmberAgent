# C5 Integration

## Accepted

- Novel schema v4 以 canonical `UIMessage` 保存 reasoning、tool output/approval、annotations、usage 和正文文本；
  v2/v3 在 load 时迁移，后续不再写 legacy `content`。
- `NovelCreation` checkpoint 全量 transcript，并通过 Chat 的 `runChatTurnWithTools`、
  `runToolLoopContinuation` 与 `runInteractiveTurn` 承载普通 turn、ask-user 和审批续跑。
- 五个 Novel workspace tools 具有完整 object JSON Schema；只读工具直接执行，write 通过 C4 proposal、
  expected CAS 与 deterministic receipt 受控提交。
- approved/denied/answered 均使用原 toolCallId；退出重进可续跑已持久裁决，非法 patch 不被默认成空写。
- writing/review/state-sync typed policy 已持久化；Entry 仅精确解析可用的 provider/model，不做 silent fallback。
- 工作区固定“创作 / 正文 / 设定”，并接入 shared `AgentMessageRow`/timeline、候选收录、正文一致性、
  分支计划/偏好、伏笔、决定、资料与职责模型设置。

## Review Fixes

- 把受保护 write 从 approval checkpoint 前移除：先持久化 verdict，再由 dispatcher 创建/接受 proposal；
  页面恢复 approved/no-output tool，`open()` 调和 denied tool。
- 执行边界严格验证 operation/path/content discriminated patches，不再字符串化或默认空内容。
- denied output 已 checkpoint 但 proposal 尚不存在的崩溃窗口，重进会补建并 reject；accepted 相反终态报错。
- 一级 tabs 与主要操作补齐 44vp；active tab/mode/material 文字统一使用 `ACCENT_INK`。
- 计划、伏笔、决定反馈移到各自动作附近；同项目持久化 reload 保留反馈，首次进入/换项目才清理。

## Runtime / Device Evidence

- `/tmp/c5-create-fixed.jpeg`：三视图主 tab、创作空态与模式输入区，无横向错位。
- `/tmp/c5-settings-final.jpeg`：模型职责、固定模型不可用原位提示、单列滚动与禁用态。
- `/tmp/c5-plan-feedback-pass.jpeg`：写作偏好保存反馈紧邻对应按钮，后续伏笔卡无重叠或裁切。
- 正确 `entry-default-signed.hap` 覆盖安装到 `5MT0225B12013017`；重启后项目与设置仍从 workspace 加载。

## Verification Evidence

- DeepRead/Chat 全域：exit 0；聚焦 DeepRead 77 passed、Chat 14 passed。
- ArkTS lint：0 defects；AGC build：`BUILD SUCCESSFUL`；真机安装成功。
- Logic review + fix review：PASS；UI review + fix review：PASS。

## Rejected

- 未复制 Chat 的流式、Markdown、reasoning、follow 或工具循环。
- 未把 fenced ask-user 文本、新 user turn 或临时内存卡当作 production continuation。
- 未为失效 fixed model 添加静默 fallback；错误在发送前可见并保留用户绑定。
- 未为恢复链新增平行 proposal/orchestrator；C5 直接消费 C4 durable state。

## Commit

本文件与 C5 实现同一提交；SHA 以该提交 Git 记录为准。
