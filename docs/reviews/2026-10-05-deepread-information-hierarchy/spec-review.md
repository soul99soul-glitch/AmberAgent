# Spec 复核：深读信息层级

> 此报告记录测试清理前的实现快照；原始日志见 [归档索引](verification-log-index.md)。

范围为 `scope.json` 指定的 25 个文件，初审时 SHA-256 全部吻合。读取真实 iOS 根 `/Users/arquiel/Downloads/AI/DeepRead`，没有使用过时副本。初审时指定 `/tmp` 基线全部已有生产文件与当时当前文件 diff 为零，因此初审按冻结 scope/hash，而非错误声称 before/after；下列发现不归责到某次提交。后续修复以增量源码和最终 scope/hash 复查。

## Looks implemented but wrong

**初审 P2：混合旧稿/新稿的两条 Native 会隐藏旧参考资料（现已关闭，见增量结论）。**触发：旧部分稿 `references=[A]`，显式续跑后增加 `sources=[B]`，旧正文仍可读。`harmony/entry/src/main/ets/components/deepread/DeepReadArticleHierarchy.ets:11–12` 只返回 B；`DeepReadArticleMagazine.ets:277–278`、`DeepReadNativeArticleBody.ets:347–348` 均未再消费 A。实际 pure helper 输入返回标题仅 `[B]`，A 仍存于 output，属于可见内容遗漏，非持久数据丢失。

依据是本轮明确“旧稿可读”及新 iOS `Core/DeepReadStructures.swift:89–114` 的非空合并规则、`Core/DeepReadCore/IOSDeepReadDraftGenerator.swift:305、362` 从 initialOutput 累积。Harmony 自己的 HTML `DeepReadTemplate.ets:227–235` 和 Markdown `domain/export.ts:190–198` 已明确保留混合旧参考资料，两条 Native 与它们不一致。建议在新编号来源之外显示“补充来源”，保留原 sources 索引，不重新编号旧引用。

## Missing / partial

五种轻量模板当前没有全部同等对应，属于已知边界，不是本轮新增缺陷；本轮无需新建模板系统，也不能宣称保留 iOS 全部五种能力。

## Scope creep

未确认越界改动：已有图片、旧 quotes/implications 和自定义 HTML 能力保留合理。存储 sourceId 用于恢复后编号稳定，有实际调用路径和测试。

## Confirmed

- 新 iOS `Core/DeepReadStructures.swift:13–35` 的 conclusion、判断引用、why、诉求/原话、impacts/watch、分类 uncertainties、统一 sources，已接 parser、持久 output、HTML、Markdown及两条 Native；四类 topicType 排序与 `Core/IOSDeepReadStructuredRenderer.swift:32–37` 一致。
- `agent/run_manager.ts:195–222、317–333` 在 structured mode 规划一次+三段模型调用，来源本地组装；`runSection:508` 和 `run:488` 的仅来源续跑没有额外规划请求。`AppContainer.ets:326` 独立深读实际选择 structured。
- `DeepReadArticlePage.ets:334–362` 保留完整缓存直接展示、部分稿显式续跑；`run_manager.ts:297–307` 再生成完整保存前保留旧稿。
- 实跑 pipeline/native/export 三个测试文件 **31/31 通过**：四次请求、来源零请求、定向来源修复、旧完整稿不改、输入重排/同名正文的稳定编号、字段导出及两条引用点击 handler 均覆盖。测试端口替身不代表真实 provider 质量。

## 未验证

没有操作设备、调用云端模型或重跑构建/签名。此报告不能证明实际排版边界、长文滚动或字形；由主 agent 统一集成与设备验证。除所述混合来源遗漏外，未确认新的 Spec 阻塞项。

## Native 修复增量复查

原 P2 来源遗漏已在源码/实际方法层关闭：新增 `DeepReadArticleHierarchy.ets:16–27` 的 `nativeSupplementarySources` 以 URL（无 URL 时 title）去重；两条 Native 各显示独立“补充来源”，该组不标生成编号，原 `sources` 数组与引用位置不变。复跑 native hierarchy **13/13 通过**，worker 红/绿证据为 `/tmp/amber-deepread-native-mixed-sources-{red,green}.log`。

**修复中曾发现 P3：混合来源的旧扩展阅读重复（现已关闭，见下）。**新增 helper 同时收集 `references.concat(extendedReading)`，但 `DeepReadArticleMagazine.ets:279`、`DeepReadNativeArticleBody.ets:349` 仍无条件显示原“扩展阅读”。当 `sources=[B]`、`extendedReading=[C]` 且 C 不在 sources 中时，C 同时显示于“扩展阅读”和“补充来源”；HTML `DeepReadTemplate.ets:297` 已在 numbered sources 非空时抑制原 reading 章节。建议 Native 相同条件处理，或只补尚未独立显示的 links。已通知主 agent；当前 13 项测试尚未覆盖重复组装的该条件。

主 agent 仍在修 Workbench 锚点和 pipeline cold ID/legacy partial，初审 31 项与原 scope hash 不能被当作这些后续变更的最终集成验证；最终预算/闭环结论待对应增量完成后复查。未再次审全 WIP。

### Native 最终增量：P3 已关闭

两条 Native 新增实际 `standaloneExtendedReading()`（`DeepReadArticleMagazine.ets:28–30`、`DeepReadNativeArticleBody.ets:57–59`）：new numbered sources 非空时返回 `[]`，仅“补充来源”消费未被 sources 覆盖的旧扩展阅读；sources 为空的旧稿继续原独立扩展阅读。重跑 `deepread_native_hierarchy.test.cjs` **15/15 通过**，其中新增两条实际组件方法执行，断言 sources 未填/为空的旧稿保留 C，sources=[B] 的新稿仅补充来源含 C 一次。原 P2 与后续 P3 均在源码/方法级关闭，未确认此 Native 修复有剩余 Spec 缺陷；设备可见性和最后 pipeline 仍等待主集成。

### Pipeline 最终冻结复查

最终 `scope.json` 30 个文件的 SHA-256 全部匹配，`run_manager.ts` 为 `f5740402e86f1f6f917f4f1bf7ca92568cf5d134348ff58eb5666642519f1097`。窄审最终 `:114–132、203–218`：web/search 使用真实 URL 形成稳定身份，text/file 使用持久 input ID；旧 URL 计数型 ID 不会令冷启动另一网站冒用旧编号。`stableSourceLinks` 保留旧位置再追加新来源，模型 evidence 标号读取该映射，不按当前 evidence 顺序重新编号。

最终 `:332–347` 的 structured 路径仍为三段模型写作、来源本地写入，之前 planner 独立一次；首轮合法响应的预算是 **4 次**，不是对错误重试也保证最多4次。`:503–533` 的只剩来源续跑／定向来源修复，使用 `withInferredSectionStates(cached)` 保留旧已写三段，有真实保留来源才能完成；无来源标失败，存储异常上抛，不虚报完成。仍未重写已有旧正文、诉求/引语和模板快照。

本次实际重跑 hierarchy pipeline / refresh review / structured pipeline **22/22 通过**。含完整4请求、定向文本段续跑、仅来源0请求、冷启动 source counter 冲突、同名文本独立身份、输入重排编号稳定，以及旧三段无 sectionStates 的来源补齐与无来源失败。结合此前最终 Native 15/15，已确认所审源码/测试范围内的字段与调用闭环；**无剩余 confirmed Spec defect**。五轻量模板的已知同等能力缺口依旧保留，不扩大交付宣称。构建、签名、设备安装和长内容目测仍由主 agent 完成，本增量不替代这些门槛。

### Web 引用锚点最后调用链复查

仅复查本次 base/history URL 与导航门控增量，不重审全 scope。两生产文件 hash 与更新后的 scope 匹配：

- `DeepReadArticlePage.ets`：`bf9260c0d28f6c5ba3b90a47dcf233a07e38313f2a3ede4c91ec5bf75e932868`。
- `DeepReadTemplateWorkbench.ets`：`3bfdd71013ae1e22d8e68f44bdc920ddbd7af387526ca3ec434c7d4c5649d93c`。

文章 `:597–614` 与 Workbench `:393–408、831–836` 将原始 rendered HTML（没有二次 URL encoding）传给 loadData，并使用固定 synthetic HTTPS 本页作为 base/history；编号 `href="#dr-source-n"` 因而解析到该本页 fragment。导航门控放行精确本页及其 `#`，不会把相同 origin 的其它 path 当本页。文章继续只在 alive、main frame、真实 user gesture 时调用安全外链 openLink；本页引用不转外部浏览器。Workbench 仍阻止外部 HTTP(S) 导航；现有 about:blank/data 兼容路径保留，故不能字面宣称“唯一允许的是本页/#”。

实际复跑 template+reader 两文件 **52/52 通过**，包含两条真实 loadData 参数/中文/CSS `#`/`%` 保真、fragment 导航、外链拒绝、陈旧 session/theme 回调及失败恢复。此最后调用链增量 **无新增 confirmed Spec defect**。主 agent 先前设备复现的“点击编号不滚动”仍需重装后实点验证：52 项只证明源码/方法及参数接通，不等同 Web 内实际滚动发生。
