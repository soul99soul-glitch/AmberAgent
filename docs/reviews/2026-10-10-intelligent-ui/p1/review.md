# P1 Closure Review

日期：2026-10-10（Asia/Shanghai）

审查范围：`response_content.ts` 及对应测试、`MiniAppOutputTransformer` 的新协议排除、域 exports、`AgentMessageRow.responseAssets → WidgetMessageContent → VisualArtifactCard → VisualArtifactPreview → visual/{svg-host.html,host.html,host.js}` 全调用链；同时核对跨 text-group 资源、迟到资源、partial/ready 与 draft 权限隔离、legacy 读取、DOM validation/revision/source replacement 与实际设备布局。

本轮只补了一条 prefixed-root 回归测试并更新本报告，没有修改生产调用方。既有 P1 证据为 51 项聚焦测试通过、改动 ArkTS lint 0 error/1 warning、Debug build 成功；本轮另外复跑了 24 项 chat/entry 聚焦测试、Three runtime 4 项测试和 `harmony/tests/visual_preview_host.py` 的浏览器宿主检查，均通过。`raw-svg.jpeg/json`、`raw-three-fixed.jpeg/json` 是 HarmonyOS 模拟器截图和 AX 树证据，不是真机证据。

本轮重新执行完整 `assembleApp` 时，构建在 ArkTS 编译阶段失败，但错误集中在当前 P4 工作树的 `platform_impl/VisualArtifactExport.ets`（2 个 structural typing 错误）和 `components/ChatArtifactShelf.ets`（2 个缺失 `onContentGrow` 错误），不在本次 P1 调用链；P1 改动文件的定向 lint 仍为 0 error/1 warning。该失败不能记作 P1 构建通过，P4 接线完成后需重新构建。

## 结论

P1 的单入口路由、旧协议读取、SVG/Three 独立宿主、同消息资源注册表、迟到资源等待和 MiniApp 候选排除调用链已经接通。SVG 和 Three 的模拟器画面布局通过目测和 AX 树检查：卡片宽度、标题内边距、Web 容器、内容上下留白没有错位；SVG Web 的 `HitTestMode.None` 符合被动预览要求，Three 的操控提示条与画布边界相接且热区可用。之前的 3 个 finding 和 Three fallback 均已关闭；只保留一个不阻塞 P1 的真机/缺失 xmlns bbox 证据缺口。

## Previous findings 收口

### 1. Three.js 不可用时显示空白画布，静态 cover 没有送入 fallback（已关闭）

严重度：中（已关闭）

旧证据是 `[raw-three.jpeg](./raw-three.jpeg)` 和 `[raw-three.json](./raw-three.json)`。修复后证据为 `[raw-three-fixed.jpeg](./raw-three-fixed.jpeg)` 和 `[raw-three-fixed.json](./raw-three-fixed.json)`；模拟器显示完整三维内容，AX 树中的 Web 节点为 `resource:/RAWFILE/visual/host.html`、`HitTestMode.None`，下方操控条为独立可点击热区。浏览器宿主回归也强制让 `threeApi.update()` 失败，确认静态 cover 显示、`#scene` 隐藏、交互保持关闭。

根因是 `VisualArtifactPreview` 曾在 Three 路径丢弃 `artifact.snapshot`，而模拟器的 WebGL 画面还受到 `preserveDrawingBuffer` 配置影响。当前实现把可信 cover 放入同 revision 的 `document`，Three renderer 和 WebGL 上下文都开启保留绘制结果；host fallback 使用该 document，`info.ok=false` 时不开放操控。模拟器 AX 的 Web 节点 `[76,872][1244,1849]`、操控条 `[73,1849][1247,2003]` 无重叠，截图不再为空。

### 2. 合法的完整 SVG XML 会被 native 预检正则误判为未闭合（已关闭）

严重度：中（已关闭）

原实现的 `rootClosed` 只接受内容以 `</svg>` 或 `<svg .../>` 结束。现在 native 路由改为保留一个 `partial` candidate，把完整 XML 交给 trusted host 的 `DOMParser`；因此尾部注释、处理指令、XML 声明、带前缀根元素和缺失 xmlns 的内容不会在路由层被截成错误。

```text
<svg xmlns="http://www.w3.org/2000/svg"></svg>
<!-- trailing comment -->

<svg:svg xmlns:svg="http://www.w3.org/2000/svg"></svg:svg>
```

可复现命令（在 `harmony/chat` 目录）：

```bash
./node_modules/.bin/tsx --eval 'import {parseResponseContent} from "./src/main/ets/chat/response_content.ts"; const nl=String.fromCharCode(10); const b="<svg xmlns=\\"http://www.w3.org/2000/svg\\"></svg>"+nl+"<!-- trailing comment -->"; const s="```amber-svg v=1 id=x"+nl+b+nl+"```"; const x=parseResponseContent(s,false)[0]; console.log(x.status,x.complete,x.diagnostics.map(d=>d.code));'
```

现有 `response_content.test.ts` 覆盖尾部注释/处理指令和 prefixed-root candidate；`visual_preview_host.py` 覆盖 strict DOM validation、缺失 xmlns 修复、嵌套 SVG、尾部垃圾和多根节点。native 仍保留完整 snapshot，不通过 substring 截取 XML 根。

### 3. 明显无效 XML 在宿主校验前被投影成 ready，校验结果没有回写到内容状态（已关闭）

严重度：中（已关闭）

下面的完整 fenced 内容现在只产生待证明的 `partial` candidate，尽管有非空 snapshot：

```text
<svg xmlns="http://www.w3.org/2000/svg"><g></svg>
```

`VisualArtifactPreview → VisualArtifactCard → WidgetMessageContent → AgentMessageRow` 现在把 `(partIndex, sourceStart, id, snapshot)` 和 DOM 结果回流到整条 assistant message 的 asset registry。`applySvgValidation` 只接受当前完整 snapshot，旧 revision、旧 source 或 snapshot 不匹配时返回原 projection；成功才把 closed SVG 提升为 ready，失败标为 error。`response_content.test.ts`、`message_row_response_assets.test.cjs` 和浏览器 host 回归覆盖了成功、失败、旧 snapshot 和保留上一个安全画面。

## 已确认无问题的链路

- 相邻 text part 由 `groupMessageParts` 合并；`responseAssets()` 再把同一 assistant message 的 SVG/Three 资源提供给每个 `WidgetMessageContent`，UI 先到、资源后到会得到 `asset-pending`，资源到达后重新解析。当前测试覆盖了完整 registry 和 late artifact。
- UI 的 asset 引用诊断不会把 SVG/Three 失败提升为 UI 失败，符合 P1 先保持原生静态文字 fallback、P2 才做交互的范围。没有把“尚未有 choice/button renderer”误判为 P1 bug。
- 新协议与 `show-widget`、`widget`、`generative-ui` 分流；legacy 仍交给 `parseGenerativeWidgets`，没有重解释历史内容。现有 legacy 回归通过。
- `stripDeclaredResponseBlocks()` 只剥离新协议围栏并保留原文；`mightContainMiniApp()` 的新 UI 排除回归通过。混合真实 MiniApp JSON 与新协议同一 text body 的端到端样例仍建议在 P1 关闭前补一条，但本轮未发现已复现的运行时错判。
- `VisualArtifactPreview` 在 SVG/Three 宿主切换时调用 `detachHost()` 而不删除 `AmberVisualHost` proxy，仅在组件销毁时释放 proxy；host message 带 revision，过期结果会被丢弃。此处未发现可复现的 stale message 问题。
- SVG 模拟器 AX 显示 Web 容器约 `[76,872][1244,1380]`、`HitTestMode.None`，标题约 `[115,799][1205,848]`；Three 修复后 AX 显示 Web 约 `[76,872][1244,1849]`，操控条约 `[73,1849][1247,2003]`，没有重叠或错位。截图中的节点间距和卡片边距符合单列消息宽度。`raw-svg` 的 viewBox 内部留白属于图形内容，不计为宿主布局问题。

## 收口条件

P1 closure 已满足：Three fallback 与模拟器证据、SVG candidate/DOM proof、跨 text-group validation registry、legacy/MiniApp 分流均有实现和回归。保留两个交付记录：当前截图与 AX 来自 HarmonyOS 模拟器，缺少真实零售设备上的 missing-xmlns bbox 复测；当前完整 assembleApp 被 P4 WIP 文件阻塞，不能把本轮记为全仓构建通过。这两项不改变 P1 逻辑 closure，但应在 P4 真机混合内容验收和 P4 接线完成后补齐。P1 不要求此阶段扩展原生 choice/button 目录；P4 导出仍必须只接受对应 revision DOM 校验通过的 SVG。
