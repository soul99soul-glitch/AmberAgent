# P9 PDF 文件链与逐页复审

状态：PDF gate PASS。实际系统保存100%两页、180%三页均独立严格解析并逐页目测，字号倍率精确证实、全篇文本一致、同篇捕获模板一致、阅读字号已恢复。保留额外175%样本且明确真实倍率。未发现需要生产修复的具体断链。

范围：只读生产代码；独占本报告。未构建、签名、安装、操作设备、读取凭据或修改其他 agent 的成果。

## 已确认的数据流

1. `DeepReadArticlePage.ets:286–292, 437–462, 840–845`：字号为当前阅读偏好（默认 1.0，允许 0.7–1.8、步进 0.05），字体也从阅读页传入导出面板。阅读页与 PDF 都调用 `renderCapturedDeepReadTemplateHtml`。模板归属取文章 `output.templateSnapshot`，不重新读取全局模板设置。
2. `DeepReadExportPanel.ets:82–98`：面板从 `getRepository().get(topicId)` 读取已保存文章，检查正文，记录 `isComplete(output)`、标题及 `deepread-<sha256(topicId)>` 文件名。PDF 按钮仅完整稿可用（141–164、289–295）；它不使用正在生成的 UI 草稿。
3. `DeepReadTemplate.ets:288–310`：有效自定义快照使用快照 HTML；内置 editorial 及默认 native 用公共 HTML renderer（native 阅读与 HTML 导出承载同份结构化内容，但不声明像素一致）。缺失／不可用的旧快照显示明确回退文案。`customTemplateHtml` prop 虽由阅读页传入，PDF 实际模板选择取仓储快照，不取该 prop。
4. `DeepReadTemplate.ets:32–84, 175–191, 312–340`：`--ds` 由传入字号生成并限于 0.7–1.8；标题、正文和小字均应用倍率。章节涵盖 topic、hero、summary、timeline、core_points、analysis、diagram、images、reading、references。自定义模板缺少章节槽时补齐未消费章节；有 `{{content}}` 时放入全部章节。
5. `DeepReadExportPanel.ets:16–21, 151–158`：导出 HTML 添加 A4 print CSS（取消 body padding、标题避页后断、图等避内部断页、长代码可折行、表头重复）。这证明规则已进入 HTML，不证明具体产物排版无瑕疵。
6. `DeepReadExportPanel.ets:166–217, 239–268, 312–322`：Web 从 `about:blank` 初始化后加载唯一 preview URL 的完整 HTML；同轮 URL／data-document 的 page-end 才启用导出，旧预览与旧 theme epoch 回调被拦截。`createPdf` 参数为 8.27×11.69 inch、四边 0.5 inch、scale=1、打印背景；原生 `pdf.pdfArrayBuffer()` 返回 Uint8Array，仅检查非空后交给文件层，无字符串转码／base64 重编码。
7. `DeepReadExportFiles.ets:34–54, 61–70, 129–137`：API>=14 且 Web capability 门控 PDF。`DocumentViewPicker.save({newFileNames:[fileName]})` 选中 URI 后直接写字节；取消或面板已关闭则不写。文件打开使用 CREATE/TRUNC，按 Uint8Array 的 byteOffset/byteLength 切出 ArrayBuffer 并循环处理短写，写完关闭 fd，才返回已保存。

## 缓存与取回边界

- **系统保存 PDF 无应用 PDF 缓存路径。** 原生 PDF 字节留在 `exportPdf` 局部变量中，`saveBytes` 写入 picker 返回的首个 URI，且不保存／日志记录该 URI。真正保存产物必须从 root 实际选择的系统目录取得；不能从源码推断特定设备 Documents provider 的物理路径。
- **文件分享单独使用缓存。** `DeepReadExportFiles.ets:72–93` 路径是 `${context.cacheDir}/deepread-share-${UUID}/${fileName}`，URI 经 `fileUri.getUriFromPath` 传 ShareController。成功打开分享面板后保留文件让接收方读取；准备／分享失败清除本轮缓存。可辅助核对原生字节，但此缓存不能替代“系统保存真实文件”证据。
- 文件层不会改变 PDF view 范围；`deepread_export_files.test.cjs:139–155` 已覆盖非零 byteOffset 和分段短写，`deepread_export_panel.test.cjs:487–510` 已覆盖原生对象原样交付。这是已有测试范围说明，本次未重复运行，也不把模拟 PDF header 当实际 PDF 完整性证据。

## 普通倍率实际文件

root 从系统 picker 实际保存目录 `/storage/media/100/local/files/Docs/Download/p9-reader-normal.pdf` 取回本报告目录下 `artifacts/p9-reader-normal.pdf`；`device/p9-pdf-normal-picker-directory.jpeg` 可见 Download 与明确文件名，`device/p9-pdf-normal-saved.jpeg` 可见“PDF 文件已保存”。这是系统保存路径，与分享缓存无关。

独立读取实际 PDF（隔离 `/tmp/amber-p9-pdf-env` 的 pypdf strict=True、PyMuPDF）结果：467094 bytes，SHA256 `7145e9646e1b01647a4777cb1215ece85262ca032f59a7dcf273fe9ebd624a5d`，PDF-1.4，未加密，xref／`%%EOF` 可解析；2 页均 595.92×841.92 pt（配置 A4），两页均有可提取中文文本及可渲染内容。这足以确认当前真实系统保存文件不是 header-only fixture，原生数据实际可用。

已独立看全 `artifacts/pdf-normal-render/page-01.png`、`page-02.png`，并对照 `device/p9-normal-reader.jpeg`、`p9-pdf-normal-preview.jpeg`：

| 页 | 实际内容与排版观察 | 结论 |
|---|---|---|
| 1 | 完整标题 `Local Reader Source Verification`、product、摘要、两个实体标签、时间线、核心要点、深度分析（争议／作者观点／影响）、图解标题与节点1。暗色背景、橙色 accent、圆角卡片和正文层级与本篇“杂志 · 立场”阅读截图一致。标题在 A4 较宽内容区成为一行，手机截图为两行，属于尺寸改变后的自然重排。 | 中文清楚，无方块、行间重叠、横向溢出或卡片截断。 |
| 2 | 节点2“四段生成”、节点3“保存与导出”，再接两条关系边。页面剩余留白较大，因为文章在此结束；不是整页空白，所有图解节点卡片完整。 | 与第1页顺序连续，未找到漏页、重复段落或断字。 |

普通正文 PDF 字号包括 11.625 pt（15.5 CSS px×0.75）、摘要 12.75 pt、章节标题15 pt、文章标题22.5 pt，符合 fontScale=1 的 CSS 字号。保存后 UI 提示与实际严格解析／逐页图共同支持普通文件结构与视觉通过。

## 大字号实际文件与内容对照

root 从同一系统 Download 目录取回首份大字号 PDF，最初系统名为 `p9-reader-large.pdf`，现按实测倍率保留为 `artifacts/p9-reader-175.pdf` 及 `artifacts/pdf-175-render/`。`device/p9-pdf-large-save-picker.jpeg` 显示原文件名与 Download，`device/p9-pdf-large-saved.jpeg` 可见保存提示。独立 pypdf strict=True／PyMuPDF 结果：467732 bytes，SHA256 `ab856db13f2131a20d919745e936ad7ea1c9c4d5e630a39e0161bd8b5035ffa7`，PDF-1.4、正常 EOF、未加密，3 页 A4。所有文字 span 位于页面边界内。

全部渲染页已独立查看：

| 页 | 实际排版观察 | 结论 |
|---|---|---|
| 1 | 标题自然重排两行；摘要6行、实体标签、时间线、核心要点标题及 point 正文。point 卡片在页末延续到第2页。 | 没有字被页边切断，标题层级和段距可读。 |
| 2 | 起首为同一 point 的 supporting 句，与第1页连续；深度分析及观点／影响完整，3节点均完整呈现，随后第一条关系边。 | card 跨页属于当前分页观察；未造成重复、缺字或重叠。 |
| 3 | 第二条关系边“四段生成 → 保存与导出”完整显示，随后大片尾部留白。 | 尾页稀疏，但不是空白页或正文丢失。记录分页结果，不据此提出未授权／未经定义成功标准的优化。 |

普通／大字号 PDF 合并各页文本并去除所有空白后，均为379字符，严格相等；标题、摘要、时间线、核心要点、深度分析、3节点、2边没有发生丢失或重复。`/tmp/amber-standalone-provider-fixture.py:96–99` 原受控模型扩展段分支明确生成 `extended_reading:[]`、`references:[]` 及3节点2边；因此 PDF 不显示空“扩展阅读／参考来源”标题符合原内容，不是漏导。两套页面均保持同一暗色杂志模板、橙 accent、卡片和标题层级。

### 倍率证据差异

首份大字号实际标题39.375 pt／普通22.5 pt=1.75，小字15.75／9=1.75，章节标题26.25／15=1.75，摘要22.3125／12.75=1.75；正文20.34 pt亦符合15.5×1.75 CSS px取整至27.12后×0.75。独立核查及时纠正“16次A+即180%”的操作叙述；root 确认先前连续点击尚未完成时并发打开导出面板，遮挡了一次点击，此为验收操作竞态。改用串行点击后真实180%文件的字号精确1.8倍，不构成产品字号缺陷。

## 180%上限实际文件与最终结论

`device/p9-pdf-180-save-picker.jpeg` 可见 Download 中 `p9-reader-180.pdf`，`device/p9-pdf-180-saved.jpeg` 可见“PDF 文件已保存”；取回产物为 `artifacts/p9-reader-180.pdf`，467493 bytes，SHA256 `25d9d9f451d7f06754a0da4884c40aa6a04e4ecccf93519767ee0686c7d40493`。独立 pypdf strict=True／PyMuPDF 成功读取，PDF-1.4、正常 EOF、未加密，3页均595.92×841.92 pt。所有文本 span 均在页界内。

实测标题40.5／22.5=1.8、正文20.925／11.625=1.8、章节标题27／15=1.8、小字16.2／9=1.8，摘要22.95／12.75=1.8；支持真实180%上限传入PDF，未发生额外打印缩放。100%、175%、180%三份PDF全文去空白后均379字符且严格相等。

独立看完 `artifacts/pdf-180-render/page-01.png` 至 `page-03.png`，对照 `device/p9-reader-180.jpeg` 与保存后预览：同一捕获杂志模板，字号层级、暗色与橙色accent一致，内容区按A4宽度自然重排。第1页摘要／时间线／核心要点，第2页接核心要点supporting、分析、3节点与第1条关系边，第3页末关系边；跨页处字符完整、没有重叠、横向溢出、方块字或整页空白。第3页尾部留白较大，作为实际分页观察保留，不声称“分页最优”。

`device/p9-reader-font-restored-100.jpeg` 恢复到普通阅读可视结构，对应AX摘要bounds与原普通阅读同为 `[129,1224][1253,1954]`。本review未执行设备操作，恢复操作由root负责，截图／AX在此独立核对。

结论：本篇真实系统保存PDF链、完整原生数据、当前捕获模板、100%／180%字号及逐页内容／排版验收通过。仅覆盖当前受控无外链作品、杂志模板、当前已安装包／模拟器；不外推所有自定义CSS、长表格、远程图像、零售手机。生产代码未变，无新增测试／build／sign／install要求。
