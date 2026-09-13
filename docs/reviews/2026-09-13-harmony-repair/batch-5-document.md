# Batch 5D — F44 文档预览小端口（DocumentSupport）

日期: 2026-09-13  分支: harmony-preview  基线: bdce551dd
范围: UI-F44（文档附件打开/预览，entry 平台侧数据供给）。仅改独占文件
`harmony/entry/src/main/ets/platform_impl/DocumentSupport.ets`；未触碰 ChatPage/AppContainer/index.ts 或其它 agent 文件。

约束遵守：已加载 `harmony-arkts`；API 基线 12；无新依赖/新框架；不写临时真实文件；不外网读取；未跑 hvigor/全仓 lint（主代理统一串行）。

---

## 修改文件与修复 ID

| 文件 | 修复 | 最小机制 |
|---|---|---|
| `harmony/entry/src/main/ets/platform_impl/DocumentSupport.ets` | F44 | 新增导出 `readDocumentPreview(document): Promise<string>`：data:*;base64 / file:// 文本与已落地 DOCX/PPTX/EPUB 解析 → 预览文本；复用域层解析与分类，只做内存字节端口注入 |

未新增业务文件、未新增测试文件；新增 1 个 isolated smoke 探针（见下）。

## 单函数签名（供 ChatPage 接线）

```ts
export const readDocumentPreview = (
  document: UIMessagePartDocument,
): Promise<string>
```

同时导出两个上限常量（仅本模块预览口径）：`MAX_PREVIEW_INPUT_BYTES = 10 * 1024 * 1024`、`MAX_PREVIEW_CHARS = 100 * 1024`。

### 语义
- 返回 `Promise<string>`：正常/已知失败均以**字符串**返回（沿用域层 `[ERROR, ...]` 文案风格），仅在真正异常时 throw；调用方可统一 `.then(text => …).catch(e => …)`。
- 仅接受 `data:`（`;base64`）与 `file://`；其余（含 `http/https`）→ `[ERROR, unsupported file uri: <name>]`，**绝不外网读取任意 URL**。
- PDF → `[ERROR, pdf preview not supported: <name>]`（MuPDF native 未移植，明确不支持；不静默降级为二进制占位）。
- 非文本且非 office 的二进制 → `[ERROR, preview unsupported for this file type: <name>]`（不返回二进制占位，避免 UI 灌无意义内容）。
- office 类型的 mime 按扩展名归一（`.docx/.pptx/.epub/.pdf`），弥补 ChatPage `mimeByName` 未收录 epub、以及 data 文档 mime 可能为 `application/octet-stream`。

## 关键实现（复用既有，不重写）

- 分类/分发/错误文案：`@amber/chat-domain` 的 `isLikelyTextFile`、`readDocumentContent`。
- 解析：`parseDocxFromZip` / `parsePptxFromZip` / `parseEpubFromZip`（域层），平台侧注入 `inflateRawEntry` + `createEntryXmlPull` / `createEntryXmlPullNoNs`（`EntryDocumentParser.ets`，未改动），以及 `readZipEntries` / `readZipEntryText`。
- 内存字节端口：`readDocumentPreview` 内构造临时 `DocumentReaderDeps`（`statFile` 返回内存 size、`readTextFile` 做 UTF-8 decode 截断、`parse*` 走真实域层解析），**不写任何临时真实文件**。
- 大小防护（先判后分配）：
  - data: 先按 base64 长度估算（`floor(len/4)*3`）超 10MiB 即拒绝，未分配解码缓冲；解码后再复核一次实际字节。
  - file:// 先 `statSync` 校验普通文件（`S_IFREG`）与 `size`，超 10MiB 直接拒绝，**不 open/read**。
- 输出截断 100KiB（`MAX_PREVIEW_CHARS = 100*1024`，即 102400 chars）+ `[TRUNCATED: preview exceeds 102400 characters]` 标记。
- `createEntryDocumentReader()` 未改动（保持 `statFile` 仅 file:// 的现有 Port 语义）。

## Scope 说明：`createEntryDocumentReader.statFile` 既有缺陷（未修）

`statFile` 仅 `file://`，故「**发送**」data: 文档仍走 `[ERROR, invalid file uri]`。按契约要求「不大改现有 Port 语义、可先只 preview 并明确 scope」，本次**仅新增预览入口**，未改 `createEntryDocumentReader`。若要修发送路径，建议后续单独变更（例如给 `statFile` 增加 data: 分支或让发送侧先落盘/直接构造 handle），需同步核验 `document_transformer` 的测试与错误文案兼容性。

## 测试与静态检查

1. 目标 lint（改动文件）：
   `LINT_EXIT_ON=none harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/DocumentSupport.ets`
   → `No defects found in your code.`
   （codelinter 打印的 `Some error occurred during linting` 为既有噪声：未改动的 `EntryDocumentParser.ets` 同文件同输出，均 0 defect）
2. 文档域既有测试（复用，不新增）：
   `cd harmony/chat && npx tsx --test src/test/document_transformer.test.ts`
   → tests 7 / pass 7 / fail 0。
3. 新增 isolated smoke 探针（1 个，非业务代码）：
   `node docs/reviews/2026-09-13-harmony-repair/probes/probe-f44-document-preview.cjs`
   → 11/11 通过，日志 `probes/probe-f44-document-preview.log`。
   方法：经 `probe-lib.cjs` transpile+require 加载**真实** `DocumentSupport.ets` 与**真实**域层（document_transformer/zip_archive/docx/pptx/epub），仅 mock 平台边（`@kit.CoreFileKit` 内存文件表、`@kit.ArkTS` util、entry XML/ZIP 平台适配器置为“不可达即抛”）。无设备、无网络、无真实文件、无临时文件写入。覆盖：pdf 拒绝；http 拒绝；data 文本直出；二进制拒绝；file:// 文本；file:// 超限先判；data 超限先估长；100KiB 截断标记；DOCX/PPTX/EPUB 路由到真实域层解析的否定文案（`Unable to find document content in DOCX file` / `No slides found in PPTX file` / `Unable to find OPF file in EPUB`）。

未做设备/模拟器截图目测（当前无在线设备）；**不声称 UI 已验证通过**。

## 共享接线需求 / 未解决点

1. **ChatPage 已完成接线（无需再改）**：`ChatPage.ets:162` 已 `import { createEntryDocumentReader, readDocumentPreview }`，`openDocumentPreview`（:2209-2223）按 `Promise<string>` 消费并带 seq 防错序、`.catch` 兜底；签名与实现一致。本 agent 未改 ChatPage。
2. DOCX/PPTX/EPUB 的**成功解析路径**依赖入口模块 `xml.XmlPullParser`，headless 探针将其置为不可达，仅验证了否定文案路由；真实成功解析与预览 UI 需与主代理统一构建后在模拟器上目测。
3. EPUB 在 `ChatPage.mimeByName` 无映射 → 预览侧已用扩展名归一兜底；发送侧仍按 `application/octet-stream` 处理（受 scope 说明第 1 条限制，本次不改）。
4. F44 组件侧（`ContentAttachmentChip`/`AgentMessageRow` 的 `openable/onOpenDocument`）由 batch-5-components 交付，数据供给由本文件补齐，两端均不负责设备验证。
