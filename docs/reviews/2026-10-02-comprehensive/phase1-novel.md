# Phase 1 — Novel 数据与作业闭环

本轮精准修复 6 组已复现问题。原有未提交实现保留；无新增 provider API、UI、调度重试或历史存储机制。

实际文件：

- `harmony/deepread/src/main/ets/novel/repository.ts`
- `harmony/deepread/src/main/ets/novel/creation.ts`
- `harmony/deepread/src/main/ets/novel/ghostwrite.ts`
- `harmony/deepread/src/main/ets/novel/polish.ts`
- 新增 `harmony/deepread/src/test/novel_audit_regressions.test.ts`

修复行为：

1. modelPolicy 是项目三职责路由，switch 原有实现明确从 current 保留，和 name / lastGenerationGranularity 相同。将它从 branch-owned head 比对移除，保留所有正文、资料、计划、版本、富会话等分支内容严格一致校验。不新造 metadata commit 或 undo 记录。
2. Ghostwrite 暂停/失败/等待用户校验 candidate 时使用 resumeStage 所指执行阶段。取消终态保留候选和审核用于现有状态查看，但不再要求候选与尚未完成的下一 rewrite attempt 相等；仍校验候选 ID、plan、chapter ordinal、attempt 范围与审核绑定。Polish 同族取消终态修复复用此边界，其 pause/fail 已有 effectiveExecutionStage。
3. Ghostwrite/Polish driving Set 改为保存现有 runner promise 的 Map。resume/retry 等待已取消或失败的旧 owner 收口，再恢复与启动新 driver；无 timer retry。旧 owner promise 收口前保留 key，旧回调不能写新 owner 任务。
4. 内容 checkpoint/commit、claim/resume/retry 保持原 CAS 保护。用户取消及失败收口只校验 project/branch 身份，不把 workspace 漂移当作不能终止作业的理由。Ghostwrite 活跃 owner 的取消仍要求匹配且未过期的 token/epoch；暂停/失败无 claim 或过期无有效 owner 可取消。有效 owner 下 null/伪造 claim 继续拒绝。保留作者普通编辑，不扩大 Workspace 锁。
5. 追加回归发现：已有 undo 文件被保留进 passthrough；新 undo 先写，旧文件随后回写覆盖，导致第二/三次编辑的撤销回到更早稿。makeUndo 时排除当前分支旧 undo 的 passthrough；其它分支文件保留。仍是一层 undo。
6. 代笔启动序号直接复用既有 nextNovelChapterOrdinal，对包含废弃章节的全部章占位分配。原 maxOrdinal 循环跳过 discarded，允许代笔重用其章号；恢复同标题旧章会重复 path/changedPaths 并破坏台账。仅此启动调用修正，不改 UI 过滤、模型序号解析或其它分配路径。

红绿证据：

- 最初 `/tmp/novel-audit-red.test.ts`：修前 0/4，修后 4/4。
- 连续 undo 新红测：新增原稿 → 第二稿 → 第三稿 → undo，修前章节为空（旧空项目 undo 覆盖最近快照），修后一章第二稿。
- NOV06 完整真实链红测：废弃第1章 → 代笔生成同标题章 → 恢复废弃旧章 → open，修前 `工作区台账损坏`，修后两个正文和章号1/2完整可读。
- 正式新增回归：`harmony/deepread/node_modules/.bin/tsx --test harmony/deepread/src/test/novel_audit_regressions.test.ts`，11/11 PASS。
- Novel 全部纯领域测试：`harmony/deepread/node_modules/.bin/tsx --test harmony/deepread/src/test/novel*.test.ts`，初次 327/327 PASS；NOV06 追加后的并行运行共330条、328 PASS，2条新 Phase2 discussion_archive 预算红测尚待该 agent 实现（日志 /tmp/phase1-novel-tests.log），本轮回归均通过。
- deepread `tsc --noEmit -p harmony/deepread/tsconfig.json` PASS。
- `git diff --check` PASS。

正式回归同时覆盖 ghostwrite/polish pause→immediate resume、旧回调隔离、重写 pause/fail/cancel 冷读、CAS 漂移真实写入仍拒绝、有效/null/错误 claim 的取消边界、失效作业 failure 可见及解除锁、项目模型切分支并 undo、磁盘书稿漂移仍拒绝、连续多次改稿后撤销最近稿。

独立 phase review、当前树 ArkTS lint/build/sign 由主 agent 统一推进。这里没有修改 UI/API，无设备/真云模型/真实系统后台验收声明。
