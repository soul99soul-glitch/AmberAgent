# E0 D7：复制格式与 Mermaid 快照修复

## 完成范围

- 新增 `harmony/chat/src/main/ets/chat/message_copy.ts`。通过既有 `parseMarkdown` / `parseInline` AST 提取可读文字，不引入新解析器或 Markdown strip 正则。
- `ChatMessageCopySheet.ets` 的全部复制消费 `messageCopyText`，逐段复制消费 `textPartCopyText`；两个路径都遵循当前纯文本 / Markdown 选择。
- 纯文本去除标题、粗体、斜体、链接等展示语法；图片保留 alt、公式保留源码；代码块和行内代码内部字面字符保持原样。列表保留可读的项目符号、编号与嵌套，表格转为制表符分隔。
- Markdown 全部复制保持原有正文、推理引用块、工具调用标记的语义；纯文本仍只包含 text part，不额外混入 reasoning / tool。
- `MarkdownText.ets` 每个实例初始化一次 `newId` 前缀，所有 Mermaid 块 ID 均包含该前缀、streamKey 和块序号。空 streamKey 的多个推理卡 / tool preview，以及同 streamKey 的两个视图不再共享快照 ID。
- Mermaid 导出从 `MarkdownText` 迁移到 `MermaidBlock` 内部，由分页导出 worker 依据实际 SVG 高度处理。已最小移除外层旧导出按钮、`exportMermaid` 方法与仅供该方法使用的 ImageKit / ImageSaveSupport 引用，避免固定高度 WebView 的外层快照再次裁切；实例唯一块 ID 保留。
- `index.ts` 仅在既有 Markdown AST 导出旁添加复制 helper 导出，保留其他 worker 的导出变更。

## 回归与验证

1. 修改前使用生产 `toText` 路径复现：正文 Markdown 标记未去除、代码围栏也留在纯文本中，2/2 FAIL。
2. 修改后的生产 helper 测试覆盖：bold / italic / heading / link / inline code / fenced code / nested lists / quote / image / math / table / formatted link label / whole and segment formats / reasoning and tool policy / blank text。
3. 在 `harmony/chat/` 运行：
   - `./node_modules/.bin/tsx --test src/test/message_copy.test.ts src/test/markdown_blocks.test.ts`：51/51 PASS（新增 8 项，既有 Markdown 43 项）。
   - `./node_modules/.bin/tsc --noEmit -p tsconfig.json`：exit 0。
   - 已有文件 `git diff --check`：exit 0。

## 验证边界

本子任务未操作 MermaidBlock、ChatPage、ChatExportSheet，未构建、签名、安装、提交或操作设备。ArkTS lint 与模拟器截图由主集成回路统一完成。外层导出移除后已核查 ID 仍传递至 MermaidBlock；真实分页导出与多个 Mermaid 同屏截图由分页 worker 与主 agent 验证。
