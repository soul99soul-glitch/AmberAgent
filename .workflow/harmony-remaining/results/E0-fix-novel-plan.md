# E0 本章计划通用提案断链修复

状态：代码与窄验证完成，review_d7_d8 独立窄复审 PASS，无新阻断。

## 原始证据与字段语义

- `workspace_history.ts` 的 proposal 路径校验原先仅放行章节与 `plan/plot.md`；`branches/main/plan/this-chapter.md` 抛 `proposal patch 不是受控正文或剧情路径`。
- `repository.ts` 的第二层 target 白名单同样拒绝本章计划，批准分支也没有 `branchSettings.thisChapterPlan` 写回。
- `branchSettingPaths`、`buildSnapshotFiles`、ghostwrite 冻结/提交共同确认：`branchSettings.thisChapterPlan` 是当前分支 `plan/this-chapter.md` 的 canonical 文本，删除操作的语义是清空该字符串；远期计划/写作偏好没有纳入本次 proposal 白名单。

## 实现

- 两层白名单仅新增当前分支 `plan/this-chapter.md`。
- 在既有批准路径内应用 write/delete patch 到 `thisChapterPlan`，继续使用原有 CAS、decision receipt、快照与原子持久化。
- 复用手工修改既有的 ghostwrite 冻结计划检查；运行中改变计划的批准仍拒绝，提案保持 pending，拒绝提案依然可用。
- 单独修改计划保留 `plotStale` 和 `unresolvedFromOrdinal`；章节/plot 原有语义继续通过回归。
- 没有新增专用 UI、协议、备用保存通道或新 SDK API；未改 creation 实现。

## 验证

先新增失败回归再改实现。单独 history/repository 首轮为 21 tests / 16 pass / 5 fail，五个失败均命中原始本章计划白名单错误；tool continuation 的本章计划回归也命中同一错误（失败产生遗留异步等待，已终止该测试进程后用窄套件保留完整失败输出）。

修复后执行：

```sh
cd harmony/deepread
./node_modules/.bin/tsx --test src/test/novel_workspace_history.test.ts src/test/novel_workspace_history_repository.test.ts src/test/novel_c5_tool_continuation.test.ts src/test/novel_ghostwrite_repository.test.ts
./node_modules/.bin/tsc --noEmit
```

结果：32 / 32 PASS；tsc exit 0。

独立复审回喂：`review_d7_d8` 复核两层白名单、批准写回、write/delete 与重载、拒绝/receipt、冻结检查复用，以及 plan-only 的剧情状态保留，独立重跑 32 / 32 PASS；未发现新阻断，未修改业务代码。复审状态另记 `E0-review-D7-D8.md`。

覆盖：待批原文不变与 patch 内容逐字保留、批准 write、批准 delete 清空、原始工具批准/相对路径归一化、持久重载、重复 receipt 不增 revision/ledger、拒绝保留原值且不可改判、跨分支/非法路径/不存在章节拒绝、冻结计划禁止批准改写、计划不清除剧情过期和待确认章节。

`git diff --check` exit 0。对两个生产文件执行 `harmony/scripts/lint-arkts.sh`，首次受已安装 Huawei 工具链日志目录写权限限制，相同命令获授权重试后 exit 0，Errors 0 / Warns 1497。警告集中于纯 TS 模块的 `no-unsafe-*` 规则；此结果不计为有效 lint 通过：主 agent 已确认 lint 的规则/插件加载与 gate 需修正，由 `fix_lint_gate` 修复后统一重验。本 worker 不再重复 lint，也不把 1497 声称为已核实的旧基线。

## 范围与后续集成

仅修改 `harmony/deepread/src/main/ets/novel/workspace_history.ts`、`repository.ts` 与三份直接关联测试。未构建、签名、安装、设备验证或提交；由主 agent 统一走集成验证。UI 预览本次只验证其待批源数据（原文件和 patch 文本），没有把专用 UI 或截图验收计为已完成。
