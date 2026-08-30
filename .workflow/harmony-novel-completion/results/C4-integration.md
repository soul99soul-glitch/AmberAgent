# C4 Integration

## Accepted

- 新增 `workspace_history` 纯域，统一 CAS、commit、receipt、branch metadata、durable proposal、
  undo、unresolved 与 plot digest 的严格校验。
- workspace repository 以 branch snapshot + commit snapshot 持久化完整项目域；fork/switch 物化隔离状态，
  不再用项目级数组冒充分支。
- 所有 `NovelCreation` 生产 mutation 改为先取 fresh status CAS，再经 `commitProject` 携 receipt 提交；
  `updateProject` 只留 C3 兼容门面。
- ledger 记录 parent/branch/tree/mutation/receipt/changed paths/chapter ordinal，并在加载时拒绝非法字段、
  重复 commit/receipt 与断裂 parent 链。
- durable proposal 存于 `.amber/proposals.json`；`NovelCreation` 已暴露查询、创建、审批生产 service seam，
  C5 的 Agent tool 与审批卡直接复用。
- 中间章编辑产生 branch-scoped unresolved 与 plot stale；显式同步/解决均形成新 commit。
- 一层 undo 恢复前一 branch snapshot、追加 restore commit、保留完整历史。
- 工作台顶部显示/切换 active branch；分支面板支持创建、切换、撤销、剧情同步和影响确认。

## Review Fixes

- 用统一 commit parser 与 receipt ledger validator 替换仓储内宽松 JSON 检查；增加 commitId 唯一和 DAG parent
  拓扑校验，实测 forged parent 与重复 receipt 均拒绝加载。
- 将 durable proposal 从 repository-only API 接到 `NovelCreation`，增加 create → pending → accept → 正文落盘
  的 service-level 测试；C5 不再需要另造 proposal seam。
- 分支面板整体可滚动并限制最大高度；分支列表按 1/2/3+ 条目收缩，消除单分支下沉。
- 五个分支操作失败统一显示 2400ms toast，避免错误只落到被 sheet 遮住的聊天区。
- 键盘 RESIZE 真机复核：标题、两条分支、输入和创建按钮均完整可见，较长历史动作可通过外层 Scroll 到达。

## Runtime / Device Evidence

- 真机项目 `4` 从主线创建分支 `2` 后顶部立即显示 `2 ▾`；打开面板可见两条独立分支，点击主线后顶部恢复
  `主线 ▾`。重装新签名包后两条 branch metadata 仍存在。
- `/tmp/c4-sheet-fixed.jpeg`：修复后双分支与创建区紧凑、无遮挡。
- `/tmp/c4-sheet-keyboard-fixed.jpeg`：系统键盘弹出后输入区仍在键盘上沿之上，sheet 可滚动。
- `/tmp/c4-branch-created.jpeg`、`/tmp/c4-switched-main.jpeg`：创建与切换后的可见状态证据。

## Verification Evidence

- DeepRead：697 passed，0 failed；一次并行 CPU 争用导致 `observable.test.ts` 时间断言抖动，单测复跑 5/5，
  串行全量复跑 697/697。
- Chat：2022 passed，0 failed。
- C4 聚焦历史/仓储：30 passed，0 failed。
- ArkTS lint：0 defects。
- AGC build：`BUILD SUCCESSFUL`；正确 `entry-default-signed.hap` 安装成功。
- Logic review + fix review：PASS；UI review + fix review：PASS。

## Rejected

- 未照抄 Android 的内存 proposal、TOCTOU approve 或删除 commit 的 undo。
- 未为 C4 复制 Chat 工具/Markdown/流式链；Agent tools 与审批呈现留在 C5，直接消费已接入的 production seam。
- 未用 UI 标签模拟分支；所有显示状态均来自 durable checkout/branch snapshot。
- 未为理论损坏添加静默 fallback；历史或 tree 漂移一律明确拒绝。

## Commit

本文件与 C4 实现同一提交；SHA 以该提交 Git 记录为准。
