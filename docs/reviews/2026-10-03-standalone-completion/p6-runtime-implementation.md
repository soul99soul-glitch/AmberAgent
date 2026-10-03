# P6 深读运行链路实现

## 实际差异与修复

本阶段只修改深读生成与搜索入口，小说运行链路保持 P5 冻结状态。对照 iOS `IOSDeepReadDraftGenerator.swift` 的实际主路径，普通模型先规划、再按 overview / narrative / analysis / extendedReading 返回四次 JSON；无需工具能力。Harmony 之前只有分段 writer tool 协议，配置入口同时拒绝非工具模型。

- `agent/run_manager.ts` 接收 `writerMode: tools | structured`，由 Entry 按当前配置模型能力选择。结构化分支仍使用现有来源收集、规划、持久化、阶段恢复和原文替换保护。
- `agent/supervisor_loop.ts` 在 structured 模式给模型 `tools: []`，仅要求目标段 JSON，并将已写入的正文 JSON 作为只读前文。当前段 JSON 经 `agent/structured_stage.ts` 解析后交给原有 section writer 校验并合并，不覆盖其他段落，也不把自由文本 fallback 标成完成。
- 坏 JSON 或普通调用失败只允许现有两次 pass 中的一次纠正；取消不重试，模型迟到结果在写入前检查取消。阶段失败保留真实错误，冷启动只补非 READY 段。
- iOS 扩展段允许链接、头图或至少两节点图解。Harmony 现有合法链接校验保留；合法候选头图或已通过既有图解校验的结构也可通过扩展段正文门槛。纯文本或文件无外链可以完成；任意图片 URL 与无效图解仍不通过。
- `SchedulerRunOptions.templateSnapshot` 贯穿 `createRunManager` 第四个参数、`run`、`generateStages`、`createRunContext`。续跑保留文章原模板；初次/强制运行选择显式快照、运行默认快照、缓存快照。首次失败也保留模板与输入。既有文章只有新稿四段完整保存才被替换。
- `createRunContext.pendingUrls` 只有 ready web/search 才当作已抓正文；发现页带实际 URL 的 ready 榜单背景 text 不再阻止全文抓取。Discovery worker 在同一 source slot 合并背景与正文，保留来源、排名与摘要。

## 搜索主路径

`SearchRegistry.ets` 调用 Chat 的 `deepread_search_registry.ts`，首选来源来自既有 `searchServiceSelected`。已启用且有有效配置的首选只请求该服务；选项失效时选择一个可用配置，最后才进入免费聚合。Google 开关仅在免费结果去重不足三条时进入 `searchGoogle(query, resultSize, signal)`；不存在虚假链接兜底。一次 registry snapshot 冻结服务与开关，下一轮才读取新设置。HTTP 与原生端口共享所属运行取消信号。

原生 Google 独立宿主及 AppContainer DI 由 root 集成，避免占用聊天的全局 WebMountSession。此报告的搜索验证包含真实 SDK HTTP 与注入的原生端口；设备 Google 页面验收由 root 统一完成。

## 验证记录

- `deepread_structured_pipeline.test.ts` 新增七项：五次普通模型调用、坏 JSON 一次纠正、强制失败保旧文、冷续跑只补失败段、纯文本无外链合法图解完成、伪图/无效图解不能完成、显式快照/续写/强制替换的完整阶段持久优先级。
- 新 `chat/src/test/deepread_structured_pipeline.test.ts` 通过真实 localhost Responses HTTP/SSE 执行完整生成链：五次请求完成、六次请求坏 JSON 纠正、四次请求流式取消。3/3，连既有 DeepRead AiClient 测试 17/17；配置 headers、customBody、温度、输出上限和 reasoning 保留，模型 builtin tools 被剥离，所有普通模型请求没有工具。Chat tsc 通过，新测试 lint 无缺陷。
- 模板实际 capture → scheduler 第四参数 → 四段 writer → RunRepository/RDB 验证四项新增。发现 no-source 失败续写分支会把 changed default 覆盖原快照，已先实际反例复现再修正优先级；合并新 structured 七项和模板库十项为 17/17 通过。
- 与原有 run_manager / supervisor_loop / section_writer_tools / scheduler focused：81/81 通过。
- `deepread_discovery.test.ts` actual createRunContext 来源抓取与背景/正文合并反例通过。
- 搜索子任务新增 8/8，组合 31/31；实际 Entry registry → Search SDK HTTP、配置冻结、Google 开关及取消归属。
- DeepRead `tsc --noEmit` 通过。运行链路五个 source 的 ArkTS lint 为 0 errors / 917 warnings（本项目跨模块类型诊断警告保留，未改不相关代码）。搜索 source 为 0 errors / 81 warnings，Chat tsc 通过。
- 五个旧 Registry VM fixture 已接真实新 helper，并保留首个结果预算与取消测试；相关 23/23，类型检查通过。最终全 DeepRead TS+CJS 回归 1442/1442 通过，0 fail / 0 cancelled / 0 skipped；命令为 `./node_modules/.bin/tsx --test src/test/*.test.ts src/test/*.test.cjs`（workdir `harmony/deepread`），日志 `/tmp/amber-p6-full-deepread-final.log`。此前旧模型设置/模板工作台夹具由对应 owner 更新，状态重建 receipt 同毫秒碰撞由授权 owner 精确修复后，再运行此次完整回归。
- 独立 reviewer 再跑领域 96/96、Chat HTTP/client/registry 25/25 通过，工厂配置与模板前后优先级继续由对应独立 reviewer 验收。

日志：`/tmp/amber-p6-structured-pipeline-{red,green}.log`、`/tmp/amber-p6-manual-diagram-{red,green}.log`、`/tmp/amber-p6-runtime-{focused,tsc,lint,all-deepread}.log`、`/tmp/amber-p6-template-pipeline-{tests,green}.log`、`/tmp/amber-p6-search-{red,green,focused,tsc,lint}.log`、`/tmp/amber-p6-structured-real-http-{tests,focused,types,lint}.log`。

本任务未自行执行构建、签名、安装、真实云模型或设备操作；这些门槛由 root 统一验证，不能由 source/HTTP fixture 测试替代。
