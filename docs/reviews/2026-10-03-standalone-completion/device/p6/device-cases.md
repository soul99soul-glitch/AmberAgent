# P6 设备验收

设备为原有 127.0.0.1:5555，1320×2856；独立 app.amber.deepread.reader，与宿主不同 bundle。使用本地受控 provider 与自建文章/模板，没有读取或迁移宿主凭据。

| 流程 | 实际证据 | 结果 |
| --- | --- | --- |
| 独立首页、20源5分类 | latest-home、discovery-settings/source-categories/tech-finance/finance-life JPEG/AX | 分类与开关可达；公共榜单有新内容；不将每端点长期在线作为保证 |
| 新模板与预览保存 | template-editor/preview/saved | 合法 starter 直接渲染并保存选用 A |
| 当次选择 | compose-template-selected | 本篇 A 明确选中，20MiB说明可见 |
| 普通模型生成 | structured-complete、request-receipts.json | 规划和四阶段5次实际 HTTP，无工具；四段与合法图解完成 |
| 离页与实时库 | library-live、library-return | 同一条目生成中变完成，无重新启动任务 |
| 模板稳定与冷读 | template-global-changed、library-cold-complete、article-cold-template-A | 当前默认改为杂志，原文章冷读仍 A 且正文完整 |
| 来源点击 | article-sources、source-browser | HTTP URL 交给真实系统浏览器 oracle.com |
| TXT 文件保存分享 | txt-save-picker/saved/share | Download完成保存；ShareKit收到1.32KB TXT文件；未发送外部接收人 |
| PDF同源与真实产物 | pdf-preview/save-picker/saved/share | A4预览与原稿相同模板A；ArkWeb生成；系统保存和468.57KB文件ShareKit |
| 首选搜索与Google开关 | search-settings-fixed、manage-search | 新页正常打开，实际设置持久与模型搜索链已测；按既有开关配置 |
| Google私有原生宿主与纯文本 | google-host-running/progress、google-only-sources；/tmp/amber-p6-google-host-hilog.log | 禁其他免费搜索后真实原生WebId1挂载；外部采集deadline超时；本地文本仍四阶段完成。未宣称Google结果质量通过 |
| 最终三个UI修复 | library-placeholder-fixed、providers-copy-fixed、search-settings-fixed | 占位文字可读、独立文案正确、@Builder后页面崩溃关闭 |

原生文件流程证明实际系统接收与分享文件，不声称已读取设备保存文件字节或验证每页PDF印刷品质。模板Reader/PDF同源另由真实RDB独立探针逐字验证。实际云模型质量/授权、零扫描OCR及系统长时后台保障均不在本地fixture结论内。
