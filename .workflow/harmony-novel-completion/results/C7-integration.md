# C7 Integration

## Accepted

- `.amber/jobs.json` v2 统一保存 ghostwrite/polish；同分支非终结作业互斥，创建/切换分支均拒绝绕过。
- 润色冻结连续章节、四类上下文、模型策略、branch/CAS；candidate/review 绑定 job/chapter/source/candidate digest。
- writing/review 职责复用 Chat adapter 与 read-only tool profile；最多两次定向重写，审核通过才原子替换正文。
- 章节旧版本、正文、ledger、undo、receipt、checkout 与 job cursor 在同一次 workspace root swap 中提交。
- start/pause/resume/retry/cancel、冷恢复、系统窗口 yield、每次后台最多一章和稳定通知/深链均由持久 stage 驱动。
- API 9 `File.lock(true)` 在项目外部锁文件上串行化 Entry/WorkScheduler 跨进程 root swap。
- 正文页接入 durable 创建面板/阶段卡；离页只停止轮询，运行作业锁定撤销、编辑、分支与普通 mutation。

## Review Fixes

- durable validator 与 commit 重新计算 target/context/candidate digest，拒绝 jobs.json 冷篡改。
- candidate/review 增加 `jobId`，禁止同章同源的跨 job 移植。
- repository 的进程内 Promise tail 外再加跨进程文件锁，并覆盖 recovery/migration/import/export 等隐式写入口。
- 活跃作业同时阻止 createBranch/switchBranch；通知 requested branch 先切换后加载精确 job。
- 旧终态通知不能遮蔽同分支新活跃 job；页面优先投影当前活跃 job，workspace lock 不失真。
- 运行作业时撤销 handler/颜色/点击三层 gate；展开后的候选、审稿和失败文本由卡片 Scroll 完整承载。
- 用户文案不再暴露内部 `ordinal` 术语。

## Evidence

- C7 focused 41/41；DeepRead 773/773；Chat 2033/2033。
- 双 repository 并发 claim/checkpoint/commit、cold tamper、receipt replay 与单版本备份均有回归测试。
- ArkTS lint 0 defects；AGC `BUILD SUCCESSFUL`；`5MT0225B12013017` 覆盖安装成功。
- `bm dump` 确认 `NovelPolishWorkAbility` 注册为独立 `app.amber.deepread:workScheduler` 进程。
- Logic review PASS；UI review PASS；跨进程锁静态 gate PASS。
- `/tmp/c7-polish-sheet-accepted.png`：无章节/无模型 gate、四类上下文、风险说明、禁用按钮和底部安全区无错位/裁切。
- 设备未配置 Chat Provider 且项目无章节，因此未声称真机云端润色或真实通知回跳成功；受控 provider 覆盖完整后台推进链。

## Commit

本文件与 C7 实现同一提交；SHA 以该提交 Git 记录为准。
