# DeepRead 三处修复独立交叉复核

日期：2026-10-03。HEAD `5ea51f7a5`，审查对象为当前冻结的工作区实际源码，保留既有 dirty/untracked 与其他 agent 改动。本复核者没有修改生产代码或正式测试，没有构建、签名、安装或设备操作。

结论：**DR-L1、DR-L2、DR-L3 的确认反例均已在目标调用链上收口，独立复核通过；本次没有新的已确认缺陷。** 此结论来自重新阅读生产源码、运行实际领域/Entry 调用测试及补充整轮 force 临时探针，不沿用原报告的 PASS 标签。

## DR-L1：旧版部分稿 READY 推断与 writer 初始化一致

生产路径为 ArticlePage 显式继续/单段重试 → scheduler → `run` / `runSection` → `generateStages` → `createRunContext` → structured/tools supervisor → `finishIfPossible`。

`run_manager.ts:436` 起根据 `withInferredSectionStates(cached)` 选择未完成阶段。修复后 `run_manager.ts:180` 的非 force writer 初始化同样使用推断后的输出，避免 stage selection 已认为前三段 READY，而 writer 只看到空 sectionStates。force 分支仍从 `makeEmptyDeepReadOutput()` 开始，因此未把“保留旧 READY”误用于全篇重生成。

`deepread_refresh_review.test.ts` 前两条测试运行真实 `run` / `runSection` 和实际 structured supervisor；模型端口提供扩展阅读 JSON，验证仅写一个缺失段、四段 READY、COMPLETE 以及旧 summary/timeline/analysis/template snapshot 保持。它不是对 `withInferredSectionStates` 的镜像单元测试。测试标题中的“completes once”由一次段落请求加最终 COMPLETE 证明；该 fixture 没有计数 repository.save，不将标题本身视作“一次总落盘”的证据。续跑原有阶段 checkpoint 仍会多次保存，这是正常行为。

## DR-L2：force 的成功新正文实际进入整轮模型请求

`run_manager.ts:157` 将 force 明确传给 `mergeCollectedSources`。`input_sources.ts:55` 的默认 `refreshWeb=false` 保持普通继续的 ready 来源复用；true 时只匹配同 URL 的 web/search 并替换成功研究正文，保留原输入 ID、更新 research metadata。同 URL 的 text/file 不成为成功刷新替换目标。合并以数组副本和新对象实现，原缓存来源不被原地改写。失败 issue 遇旧 ready 来源跳过，保留已取得正文。

预取生产边界 `source_prefetcher.fetchSource` 只有超过最小正文门槛才返回 `DeepReadSource`，因此成功列表不是“空正文也视作刷新成功”。`generationSources` 将新 input body 重新填入 evidenceText，随后 evidencePack → generateArticlePlan → runSupervisor 使用同一份新证据。

原四条 regression 中两条来源测试实际走 `createRunContext`，证明 fresh evidencePack、source id、metadata 和本地输入保留；它们没有完成全篇写作，因此本复核另外运行只在 `/tmp` 的整轮探针 `/tmp/deepread-fix-independent-probe.ts`：

1. 仓库初态为旧 COMPLETE / 四段 READY，带原网页正文与精确保留的 `inputText`。
2. `run(force=true)` 使用可控 prefetch 返回同 URL 新正文；通过真实 article planner 和四段 structured supervisor。
3. 每次实际模型端口收到的 prompt 都断言含 fresh evidence marker、不含旧 body marker；每次模型调用时仓库引用仍等于旧文章。
4. repository.save 断言只允许 isComplete 输出，完成后恰好保存一次；原旧来源对象仍保持旧 body。

实际结果：

```json
{
  "ok": true,
  "complete": true,
  "actualPrompts": 5,
  "completeSaves": 1,
  "oldBodyRetained": "旧正文标识old-evidence-xxx",
  "newPersistedBody": "新正文标识fresh-evidence-yyy",
  "inputIdPreserved": true
}
```

这同时验证了 `canSaveInputCollection` 不写已有可读稿、`saveGuarded` 在完整新稿前保留旧稿的收口。首次临时探针使用过短的 narrative/analysis 例文，被既有 structured JSON 校验拒绝并保留旧文章；替换为仓库已有合法 fixture 文案后通过。该前次失败是探针输入不足，不作为生产 bug。

## DR-L3：Google 补充使用 aggregate 剩余预算，保留已得免费来源

实际路径为 `AppContainer.ets:279` 的每篇文章 searchGoogle 端口 → `SearchRegistry.snapshot` → `createDeepReadSearchProviders` → `createSourcePrefetcher` → HTTP 正文 → evidencePack。

`harmony/chat/src/main/ets/search/deepread_search_registry.ts:60` 从免费 provider 调用之前记录开始时间；`:68` 计算 `9000 - elapsed`，预算用尽立即结束补充循环；`:71` 将剩余预算传给既有 `withinBudget`。Google 超时后不会再开始后续 query，`:80` 返回已合并的免费 hits。外层 prefetch 的 search deadline 为 10000ms，正文采集总预算为 15000ms，因此可选补充先在软截止收口，不再拖到外层把整个 aggregate bucket 丢空。

`deepread_search_budget_review.test.ts` 第一条使用实际 registry + prefetch + HTTP 阅读器，免费 provider 用掉 8000ms、Google 请求挂起，9000ms 时已完成两篇真实正文抓取与来源输出。测试另 tick 到旧的 10000ms 截止，确保 RED 路径不会遗留未结束 promise；通过结果依据是在外层截止前捕获的 completed 状态，不能由额外 tick 假造。验证两个免费 URL 完整保留、只调用一次 Google、原 prefetch child signal 在 finally 中止其请求。第二条免费阶段已耗尽 9000ms 时返回已有 hits、零 Google 调用。

取消表述须精确：`withinBudget` 本身只截止 await，**不会在第 9000ms 立即 abort 原生 Google 请求**。Google 仍使用原 prefetch child signal；父取消透传到 child，或 prefetch 的 finally / 15000ms 总预算终止 child。生产 `AppContainer.ets:282` 实际注入 `createEntryAbortController`；`DeepReadGoogleSearch.ets:51` 起订阅该 signal 停止精确请求 controller 并在 finally 解订阅。测试验证的是这一既有收口，不是新增独立 Google 取消控制器。本轮未因此扩张状态机制。

预算修只针对免费采集完成后的可选 Google 补充。免费 provider 自身若全部迟于外层 search deadline，仍按原 prefetch 预算处理，不能把本修复称作任何慢 provider 都会保住部分结果。

## 独立执行的验证

- DeepRead：`deepread_refresh_review.test.ts`、`input_sources_run.test.ts`、`run_manager.test.ts`、`deepread_structured_pipeline.test.ts` 共 **34/34** 通过。
- 临时整轮 force production-chain probe：5 次真实领域模型请求、1 次完整新稿保存、输入 ID / raw input / 原旧稿对象保护均通过。
- Chat：在 `harmony/chat` cwd 使用 `node_modules/.bin/tsx --test src/test/deepread_search_budget_review.test.ts src/test/deepread_search_registry.test.ts`，共 **10/10** 通过。包括 budget 两条真实 aggregate→prefetch 测试，以及实际 Entry registry 快照、Google 端口、精确请求取消。
- 从仓库根调用 chat tsx 同时跑后者曾遇 `@amber/deepread-domain` alias 解析失败；切换到 chat 项目 cwd 后同文件通过。此为测试 runner 的 tsconfig 定位要求，不是生产模块缺陷。
- 没有重复构建或设备巡检。本独立复核未重跑修改者已完成的 tsc/lint；它们的结果由各自产出报告负责，本报告只声称以上独立执行证据。

当前证明边界为源码及可控 HTTP/model/native port 的实际调用链；真实 provider 服务、在线 Google 页面/验证码、系统后台保活和最新设备视觉仍未在本次验证。
