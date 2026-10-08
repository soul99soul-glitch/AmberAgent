# Standards review

> 此报告记录测试清理前的实现快照；原始日志见 [归档索引](verification-log-index.md)。

范围：最终 `scope.json` 中 30 个文件，当前 SHA 全部匹配；HEAD `b2d054bd7a81c64795497098d54cf30ad049e04f`。审阅 scoped 实现，并逐路径对照 `/tmp/amber-deepread-hierarchy-baseline-20261005` 初始实现冻结快照检查修复增量；该快照不是改动前版本。不归责其它 WIP。依据当前 AGENTS、harmony-arkts、code-review；最终 **0 个未关闭 Standards 问题**。

## 已关闭 P2：冷启动续写把新来源绑定为旧引用

触发：旧网页持久化 `researchSource.sourceId=src-1`，冷进程抓取不同网页再次得到 `src-1`。初审快照 `run_manager.ts:114–117` 仅按 sourceId 判等，`:195–203` 同 ID 写编号 map。影响：新网页引用 `[1]` 指向旧报道、新来源漏列。

真实 `createRunContext` probe `/tmp/amber-hierarchy-cold-source-id-probe.ts` 修前输出两 URL／一编号，`sourceNumbers={"src-1":1}`；既有计数器仅为触发依据。最终 `run_manager.ts:114–132,203–218` 统一网页 URL、持久化输入 ID，旧网页按 URL 判等，同 identity 贯穿 evidence/plan/prompt。重跑同 probe 得旧 URL 编号 1、新 URL 编号 2，旧列表顺序不变；同标题本地输入仍保持独立编号。生产 SHA `f5740402e86f1f6f917f4f1bf7ca92568cf5d134348ff58eb5666642519f1097`。无网络调用。

## 已排除与验证

未发现新增高版本 API、V2 混用、全局主题可变状态、计时器或订阅归属问题。新增字段可选；旧字符串 uncertainties、旧引用与内容经兼容分支读取。独立 DeepRead 使用 structured；宿主具备 tool 能力时仍保留原 tools 分支。解析与共享章节判断为小模块，无证据支持额外 Fowler 重构；lint 已覆盖项目不重复报告。

Native mixed old/new 来源修复已复核：两路径追加“补充来源”，保持编号、按 URL／无 URL 标题去重；有新来源时独立扩展阅读隐藏以免重复。本地来源重试沿既有 section 推断完成旧稿归属，无新增模型请求。

独立重跑 pipeline、native hierarchy、structured、export、template、container **79/79 通过**，含真实方法冷 ID 碰撞回归。新增测试文件仅更新相关调用数、来源字段与局部复现，不掩盖原行为。构建、签名与设备视觉验证由主 agent 负责，本报告不替代这些验收。

## Web 锚点四文件增量复查

主设备曾确认仅放行 `about:blank#` 未发生滚动；不能把上一轮守卫测试视为滚动验收。现在 Article `:597–614`、Workbench `:407–408,831–836` 以各自固定 `.invalid` HTTPS 文档 URL 作为 loadData base/history，传原始 HTML；仅精确本页或本页 `#` 放行，路径/域名前缀伪装不通行。Article 外链仍要求 live/main-frame/gesture，preview 继续拦截外链。session/epoch/attached/initialized 门控未改变，不因最终文档 pageEnd 再次 loadData。沿用 `DeepReadExportPanel.ets:180` PDF 先例；未新增 API、权限、订阅或计时器。

官方离线 `API参考/ArkWeb_方舟Web/ArkTS_API/ohos_web_webview_Webview_/Class_WebviewController/arkts-apis-webview-webviewcontroller` loadData 参数表确认 baseUrl 支持 HTTPS、非空 historyUrl 用于历史管理；原输出 `/tmp/hierarchy-webview-official.txt:439–468` 已读。独立执行 reader/template **52/52 通过**，覆盖原始中文、CSS `#`、`%`、文档地址及导航生产方法。主 agent 重装后点击来源 1，已实际由首屏滚动到来源 `[1]`／“本文引用”，证据 `device/20-final-preview-top` → `device/21-final-citation-target` JSON/JPEG；本 reviewer 仅复核已有文件、未操作设备。代码与主设备验证共同关闭本轮锚点问题。

下表为本次增量前 SHA → 本次复核 SHA（均匹配更新 scope；不表示最初改动前版本）：

| 文件 | 增量前 SHA | 复核 SHA |
| --- | --- | --- |
| DeepReadArticlePage.ets | `57a2062aba5dc1478d35403aa89788670952b81c47d481dd645317909153c0d2` | `bf9260c0d28f6c5ba3b90a47dcf233a07e38313f2a3ede4c91ec5bf75e932868` |
| DeepReadTemplateWorkbench.ets | `667f2023774c073b1b29499351319f9e7f6a6c9ec90bc36bbe262661ff22103d` | `3bfdd71013ae1e22d8e68f44bdc920ddbd7af387526ca3ec434c7d4c5649d93c` |
| deepread_reader.test.cjs | `0a48078efa255e8325166c446df4a17840cecaa15a38ecc773537a0917253107` | `af0c76f0bf1d87a9c99bc8cb995b5af12006de9cca59b4fa2b065d86a70f5c21` |
| deepread_template.test.cjs | `c4b91ea746ff79a21ae5bddb8f6577e52e37afb25699646767ed6e4fedfacf75` | `ea3a463473e8f0e86b540e03e1cf567151a7273199e21ea3bbd47f4fbeba1742` |
