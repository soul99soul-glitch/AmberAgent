# Novel 领域调用链独立复审

日期：2026-10-03。工作区 `/Users/arquiel/Downloads/AI/amber-harmony-preview`，HEAD `5ea51f7a5`；结论针对当前包含既有大量 dirty/untracked 工作的源码，不把 HEAD 的已提交版本当作本轮待审版本。产品为独立 `app.amber.novel`。

先独立审阅生产调用链和现有测试，再用真实 `NovelCreation → file repository` 内存 FileStore 探针验证。确认两个 P2 逻辑问题，经主 agent 授权后局部修复；未新增状态机、存储格式、通用 commit 拦截或重试兜底。没有构建、签名、安装或打开设备。

## 确认发现及修复

### N-L1 / P2：项目重命名使未结束代写、润色任务失去恢复能力

**触发场景**：作者启动代写或批量润色，返回项目列表重命名项目；同样影响暂停、失败但可恢复的任务。

**调用链**：`NovelProjectsPage.submitRename` 调用 `getNovelCreation().rename`（`harmony/entry/src/main/ets/pages/NovelProjectsPage.ets:219`）；原 `NovelCreation.rename` 直接提交 `rename`。提交增加 ledger head；作业冻结的 `expectedCas` 未更新。下一次 `assertGhostwriteWorkspaceBinding` / `assertPolishWorkspaceBinding` 严格验证原 head（`harmony/deepread/src/main/ets/novel/repository.ts:1182`、`:1191`），因而失败。`retryGhostwriteJob` 先走同一 `loadBoundGhostwriteJob`（`:2897`），仍拒绝过期 CAS，作者点击重试无法恢复。

**修前实际证据**：临时探针 `/tmp/novel-rereview-probe.ts` 使用生产 creation / file repository，将 writer 卡住后调用 `creation.rename`，再释放 writer。输出：

```json
{
  "writerReached": true,
  "renameSaved": "author rename while writer active",
  "oldHead": "c1-ecae275032abf124",
  "currentHead": "c2-ecae275032abf124",
  "stage": "failed",
  "failure": "工作区已变化，拒绝覆盖",
  "retryError": "NovelError: 工作区已变化，拒绝覆盖",
  "chapters": 0
}
```

树摘要完全相同而 head 变化，说明即使正文未变，元数据重命名仍足以破坏恢复。原有 `novel_ghostwrite_repository` 测试只验证 CAS drift 拒绝覆盖，没有验证面向作者的重命名准入和随后恢复。

**最小修法（已实施）**：仅在 `NovelCreation.rename`（`harmony/deepread/src/main/ets/novel/creation.ts:934`）读取当前分支既有两类 durable jobs；若任何任务非 `completed` / `cancelled`，返回“请先完成或取消当前代写/润色任务，再重命名项目”。保留仓库的严格 CAS 和原有正文漂移保稿路径。项目列表已有 catch 显示错误；工作区的 `NovelGhostwriteCard:224`、`NovelPolishJobCard:238` 起已有取消入口。

**修后证据**：`novel_rename_jobs.test.ts:22` 通过真实 startGhostwrite→rename→pause→rename→failure→rename 链验证拒绝前后 CAS 和名称未变、原任务仍可 retry、cancel 后 rename 成功；`:46` 验证实际 chapter polish 的 running / paused 准入和 cancel 后恢复操作。两条测试修前均报 `Missing expected rejection`，修后通过。

**范围**：此处是公开项目重命名入口的准入修复。未将所有正文、状态提取、对话 checkpoint 或仓库低层提交都禁用；那些路径原有拒绝覆盖与显式恢复语义继续由既有实现负责。

### N-L2 / P2：资料别名等实际注入信息变化后，旧写作候选仍可收录

**触发场景**：作者生成写作候选后，将人物别名从“老张”改成“小李”，资料标题和正文不变，随后收录旧候选。标签、自定义类别、always / smart 注入方式也会影响实际请求。

**调用链**：`material_injection.ts:31` / `:58` / `:71` 实际读取别名、标签、注入方式和自定义类别构建/选择上下文；原 `candidate_provenance.novelPlanDigest` 却只纳入资料 id/kind/title/content。`mutations.collect`（`harmony/deepread/src/main/ets/novel/mutations.ts:248`）调用 `assertNovelCandidateCurrent`，摘要未变，错误接受旧来源。

**修前实际证据**：临时探针 `/tmp/novel-rereview-material-probe.ts` 走生产 upsertMaterial→generate→checkpoint→完成→仅改 aliases→collectMessage。实际 systemPrompt 含 `Aliases: 老张`；当前资料别名已是 `["小李"]`；前后摘要都为 `d40adca1`；收录成功写入“旧人物别名：老张。”，章节数 1。正式生产链回归修前报 `Missing expected rejection`。

**最小修法（已实施）**：`harmony/deepread/src/main/ets/novel/candidate_provenance.ts:24` 使用已有 `normalizeNovelMaterialFields`，将非空 aliases / tags / customKind 和非默认 injectionMode 纳入现有摘要；tags 与实际注入文本一样排序。没有把候选改成全对话 revision / CAS 绑定。

**兼容边界**：空 aliases/tags、自定义类别空串、默认 always 仍省略于序列化载荷，保持旧无 metadata 候选的既有摘要；固定 legacy fixture 摘要仍为 `a284ac10`。旧候选若来自带非默认 metadata 的请求，旧摘要未记录这些证据，修后会提示来源已变并要求重新生成。没有用旧摘要匹配兜底，否则会再次放行本次确认的漏洞。

**修后证据**：`novel_candidate_provenance.test.ts:100` 验证真实请求包含旧人物别名，实际编辑别名后 collect 拒绝且正文仍零章。`:115` 覆盖四种注入字段与默认字段兼容。默认字段/legacy fixture 最终复跑通过。

## 本轮其他调用链结论

| 路径 | 核对结论 | 当前证据 |
| --- | --- | --- |
| ghostwrite writer/review/planner→checkpoint→commit | 正文与下一章计划、receipt、job cursor 同一次 staging tree 安装；非末章缺少 nextPlan 先走 planner；末章清计划；不从模型 review 直接覆写作者 plot | repository `commitGhostwriteChapter:2941`；runner 及 repository 测试通过 |
| owner / lease / pause / cancel / cold resume | claim token+epoch、租约和冻结 CAS 共同阻止旧 executor commit；暂停取消先持久化再停模型；冷恢复继续 durable stage | ghostwrite runner/repository、polish repository 相关测试通过 |
| history / branch | 可达 ancestry 限制历史选择与进度；分支切换 hydration；undo 追加 commit；未结束 durable job 阻止分支切换 | workspace_history_repository、ghostwrite progress、material_inheritance 回归通过 |
| shared materials / branch overrides | 共享资料更新传播到继承分支；override、hidden、恢复继承各有真实存储 roundtrip；跨分支非终态任务阻止共享修改 | novel_material_inheritance 10 条通过；material_fields 7 条通过 |
| chapter contract / upcoming arc | confirmed 合同进入实际写作/审核，draft 不进入；合同与 arc 同步 thisChapterPlan/futurePlan，因此不是本轮摘要遗漏对象 | novel_chapter_contract 10 条通过；chapter_contract.ts 源码核对 |
| ordinary failure / interrupt / resume | 原请求模型配置、历史与 context 冻结；冷 retry 保留旧 partial 但发送原请求；resume 使用 canonical partial/cursor；终止后的迟到 checkpoint 拒绝 | novel_ordinary_run 7 条通过；creation ordinary 生产链核对 |
| state rebuild / identity | 块预算、来源正文摘要、身份资料、取消和 retry cursor 校验；失败不清 stale writing gate；成功完整重建才解除 | novel_state_rebuild 13 条通过；state_rebuild.ts / completeStateOperation 核对 |
| polish chapter / durable batch | 哨兵不完整不得收录；批量冻结章节和 context、联合审核、原章版本备份、原子替换、逐章结果及稀疏选章 | candidate provenance、polish creation/repository 回归通过 |

未将以下初步疑点当成额外 bug：ghostwrite review `stateDelta` 不直接提交到作者 plot 的行为有明确的“确定性正文事实指针 / 作者 plot 自行维护”边界，且已有生产存储测试；作者 brief 在成功保存新 candidate 时清除，不会泄漏到下一章；合同和 arc 也已同步到摘要纳入的旧字段。没有凭同名接口差异判断功能缺失。

## 验证与交付边界

- 改前独立领域重点集：89/89 通过；覆盖 ghostwrite repository/runner、polish repository、ordinary run、state rebuild、workspace history repository、material inheritance、chapter contract。
- 本次变更新增 4 条 focused regression，先获得真实 RED，再局部修改生产逻辑；最终两份回归文件 16/16 通过。
- 改后相关集：56/56 通过；覆盖 candidate provenance、rename jobs、creation ghostwrite、ghostwrite repository、polish creation、material injection/fields、ordinary run。补充固定 legacy 摘要断言后再次执行上述两份 focused 文件 16/16。
- `harmony/deepread/node_modules/.bin/tsc --noEmit -p harmony/deepread/tsconfig.json` 通过。
- `harmony/scripts/lint-arkts.sh harmony/deepread/src/main/ets/novel/creation.ts harmony/deepread/src/main/ets/novel/candidate_provenance.ts`：退出码 0，Errors 0 / Warns 2005。警告大量为纯 TS 跨模块解析产生的 no-unsafe 系列，本轮没有把此结果称作零警告或与旧基线完全相同。
- 生产变更仅 `creation.ts` 的 rename 局部与 `candidate_provenance.ts` 的资料来源摘要；测试为新增 `novel_rename_jobs.test.ts`、更新 `novel_candidate_provenance.test.ts`。保护其他 agent 的并行修改与原有 dirty/untracked 文件。
- 当前证明止于源码、领域/存储真实调用链、模型端口可控回归及 lint/TypeScript；真实 provider、系统后台生命周期、打包、签名、安装与最新设备 UI 未在本子任务验证。

## 全量回归兼容修正

主 agent 最后执行 DeepRead 全量集时，1455/1456 通过，唯一失败为 `novel_audit_regressions.test.ts:146` 的“失效 CAS 的代笔能保存失败原因，失败且无 owner 的作业可取消”。该旧 fixture 使用公开 `creation.rename` 制造 CAS 漂移，而这正是 N-L1 修复后禁止的任务准入，因而在到达原断言前被合理拒绝。

经主 agent 授权，仅将此测试前提替换为既有 `creation.saveChapter`，在 `setupGhostwrite` 已存在的旧章上实际作者改稿，制造真实正文来源/CAS 漂移。`failGhostwriteJob` 保存失败与清除 owner、`retryGhostwriteJob` 拒绝覆盖、`cancelGhostwrite` 成功的全部原断言原样保留；生产源码未变。更新后该 audit suite **11/11** 通过并冻结。这是让旧回归使用仍受支持的触发方式，不是削弱 CAS 或取消验证。
