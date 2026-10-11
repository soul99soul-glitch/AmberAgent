# P4 独立 Review：产物索引、导出与提示边界

日期：2026-10-10（Asia/Shanghai）

审查范围：`conversation_artifacts.ts`、`conversation_declared_artifacts.test.ts`、`generative_widget_prompt.ts`、`response_content.ts`，以及当前已接入的 `ChatArtifactShelf`、`VisualArtifactPreview`、`VisualArtifactExport`、`ChatPage.openArtifactShelf` 调用链。没有执行构建、签名或设备操作；模拟器设备证据由 root 提供，真机因 phone offline 保留缺口。

## 结论

域层的 typed index 已按当前选中分支收集 `svg`、`scenes`、`uiSnapshots`，旧 `widgets` 仍走历史解析；新 SVG 只有匹配来源、快照和 DOMParser proof 才能成为 `ready`，3D 导出明确为规范对象 JSON/静态 SVG 封面，UI 导出明确标注 `interactive: false`。未知版本、重复 ID、缺失终态不会进入可导出的 typed index。域、router、prompt 和 entry targeted tests 当前均通过。

新 `DECLARED_CONTENT_INSTRUCTION` 与 `parseSmartUi` / `parseResponseContent` 的边界一致：state-first、六类节点、`visibleWhen` 字面相等、同消息资源引用、独立 SVG/Three 围栏、MiniApp 分离和静态动作均能在当前实现中落地。P0 两个模型均已达到 18/20，当前实际运行时已切换到 `resolveGenerativeWidgetInstruction('declared-content-v1')`；旧 widget 解析仍保留给历史内容。

## Findings

### P4-1：SVG proof 没有从消息行传到产物架，导出链路实际不可达（已关闭）

证据：`ChatPage.openArtifactShelf()` 在 `ChatPage.ets:2478` 只调用
`collectConversationArtifacts(full, this.displaySettings.enableGenerativeWidgets)`，没有传入 `svgProofs`。因此 `conversation_artifacts.ts:209-219` 对每个闭合 SVG 都只能生成 `status: 'partial'`、`validation: null`。

架内虽然在 `ChatArtifactShelf.ets:91-105` 创建了 proof，但 `svgProof()` 又要求 `selectedSvg.status === 'ready'`。当前状态永远是 partial，所以新 SVG 的“等待 SVG 校验”按钮不会进入可导出状态；即使预览宿主回调 `ok=true`，也只改变 `svgValidationOk`，不改变 `selectedSvg.status`。这使计划要求的“DOM proof 跨生命周期后可导出”没有闭环。

关闭证据：Shelf 现在自己挂载 strict SVG preview，对 partial candidate 执行 DOMParser 校验，接收 canonical SVG 后把当前 artifact 提升为 ready；导出仍经过 `svgArtifactExport(artifact, proof)`，缺 canonical、失败 proof、超限 canonical 均拒绝。无需把消息行 proof 提升为新的全局状态。

### P4-2：架内 proof 身份只有消息级，迟到的验证结果可能误标另一个 SVG（已关闭）

证据：`ChatArtifactShelf.ets:45-47` 的 `sourceKey` 只有 `conversationId/nodeId/messageId`，没有 `blockIndex/sourceStart/blockId/protocol/version`；`SvgPreview` 的回调在 `:213-216` 读取当时的 `this.selectedSvg.source`，而不是捕获发起验证的 artifact。若同一条消息有两个合法 SVG，先前卡片的异步回调迟到时，当前选中卡片仍会被同一个消息级 key 接受。

关闭证据：Shelf 的回调同时校验 selection epoch、完整声明 source、raw snapshot 和 canonical snapshot；预览重挂载使用 `ForEach([svgSelectionEpoch])`，旧宿主回调无法更新新选择。域层 proof 继续使用完整 source 和 raw snapshot。

### P4-3：同名 block ID 跨消息合法，但 Shelf 的列表 key 只有 ID（已关闭）

计划规定声明 ID 只在当前助手消息内有效，因而不同消息可以合法使用相同 `id`。域层通过完整 source 区分它们，但 `ChatArtifactShelf.ets:341`、`:356`、`:371` 的 `ForEach` key 分别只返回 `artifact.id`。同一会话出现两个 `id="diagram"` 的 SVG 或场景时，ArkUI 列表 key 重复，可能复用错误的行或触发列表更新异常。

关闭证据：SVG、3D、UI 三类新列表都使用 `id + conversationId/nodeId/messageId + blockIndex/sourceStart/blockId/protocol/version` 生成 key；同一显式 ID 在不同消息中不会复用 ArkUI 行。

### P4-4：模型评估证据曾被覆盖，现已闭合

初审时 `p0/model-evaluation.md` 与 `model-evaluation.json` 的模型结果不一致，无法复现双模型门槛。当前证据已恢复为独立、可追溯的结果：

- `p0/model-evaluation.json`：GLM `glm-5.3`，20 条通过 18/20，非流式验收；另有独立 `glm-stream-pilot.json`，`finish_reason=stop` 且收到 `[DONE]`。
- `p0/mify-model-evaluation.json`：Mify `moonshot/kimi-k3`，20 条通过 18/20，非流式验收。
- `p0/model-evaluation.md`：已同步列出两个模型各自的失败类别和 90% 结果，没有把 stream pilot 当作 20 条成绩。

证据显示两个已配置模型均达到至少 18/20；本项关闭。仍需在最终切换记录中注明两组完整验收是 `nonstream_fallback`，stream 只保留 GLM 的终态 pilot 证据。

### P4-5：声明式 v1 prompt 的最终切换仍待 gate closure（已关闭）

关闭证据：`ConversationRunDependencies.ets` 已使用 `resolveGenerativeWidgetInstruction('declared-content-v1')`；显示开关仍控制是否注入。旧 `show-widget`/`generative-ui` 解析和导出路径未删除，历史内容继续可读。

## 已确认的边界

- `collectConversationArtifacts` 使用 `nodeCurrentMessage`，因此只索引当前选中分支；同一消息中的重复声明 ID 被整体排除，未选分支不会进入新 typed index。
- SVG 没有 proof 时保留为 partial，失败 proof 为 error；`svgArtifactExport` 会比较 validator、`ok`、snapshot 和完整 source，旧 HTML 仍由 `widgetArtifactExport` 导出。
- `sceneArtifactExport` 只接受 ready 且至少一个规范对象，封面由 `renderThreeSceneCover` 生成；UI 导出携带 summary/definition 并固定 `interactive: false`，不会伪装成交互界面。
- `parseResponseContent` 对未知版本保留诊断，`collectConversationArtifacts` 不把它误加入新类型；普通 Markdown、旧 `generative-ui` 和 MiniApp 仍有独立路径。

## 验证记录

```text
cd harmony/chat
./node_modules/.bin/tsx --test \
  src/test/conversation_declared_artifacts.test.ts \
  src/test/generative_widget_prompt.test.cjs \
  src/test/conversation_artifacts.test.ts
13 passed, 0 failed

cd ../..
node --test \
  harmony/entry/src/test/visual_artifact_export_entry.test.cjs \
  harmony/entry/src/test/three_runtime.test.cjs \
  harmony/entry/src/test/chat_visual_fixture.test.cjs
7 passed, 0 failed
```

新增的 entry targeted test 检查了 canonical proof、raw/source/snapshot/epoch、source-based list key 和独立导出入口。浏览器回归已验证无 namespace SVG 的 canonical XML 可被独立 Blob 解码且有彩色像素；`svg-ready.jpeg/json` 显示模拟器中的“导出 SVG”已启用。P4-4 的双模型证据见 `p0/model-evaluation-index.json` 及两个独立模型 JSON。

设备边界：当前证据是 HarmonyOS 模拟器；phone target 处于 offline，未把真机安装或真机回归写成通过。完整 debug build 已由 root 另行验证，本 review 没有重复执行构建。

## 当前 ledger

| 项 | 状态 | 下一步 |
|---|---|---|
| P4-1 Shelf 首次 SVG DOM proof 与导出 | closed | Shelf 自己 strict-preview 校验 partial candidate，canonical proof 通过域 export gate |
| P4-2 proof 迟到回调身份 | closed | selection epoch + 完整 source + raw snapshot 丢弃迟到结果 |
| P4-3 新产物列表 key | closed | SVG/3D/UI 使用完整 declared source key |
| P4-4 双模型生成验收证据 | closed | GLM/Kimi 各 18/20；保留独立 JSON 和 stream pilot 说明 |
| P4-5 默认 prompt 切换 | closed | 默认生成已接入 declared-content-v1 resolver，旧协议仅负责历史读取 |
| P4-6 无 xmlns SVG 的 standalone export | closed | trusted host XMLSerializer canonical；raw 保持 source identity，canonical 用于导出 |
| P4-7 真机设备证据 | gap | phone offline；当前只归档模拟器证据，不假说真机已安装 |
