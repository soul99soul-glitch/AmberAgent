# Spec review：iOS 深读复刻 WIP

结论：**已报的既有能力 UI 缺陷已关闭；完整复刻仍因待定业务范围而是 partial**。审查基准为 `ios-spec.md` 与 `reference/ios-current-*.jpg`；旧 `ios-discovery-light.jpg` 不作为目标。第四轮仅查看主 agent 最终安装后更新的发现/文章稳定截图及字体源码，未重复全盘探索、构建、操作设备或修改业务代码。早期条目的行号对应当轮 WIP。

## 已复验源码修复

- **失败页（§6）**：`DeepReadArticlePage.ets:918–925` 已先放顶部恢复 panel，再显示文档图标、“文章尚未生成”及配置说明，原居中错误卡问题关闭。
- **采集失败（§6）**：`DeepReadArticlePage.ets:709–710、790` 已汇总来源 failed 数；`DeepReadArticleStatusPanel.ets:28、43、57–59` 让完整稿也保留失败数、原因提示及重试入口。
- **编辑室（§7、§11）**：`DeepReadNewsroomDesk.ets:94` 改为 maxWidth 28 + layoutWeight，解除 10 字固定宽度溢出；`:63–67、103–113` 补当前节点 pulse、主图标 breathe 与真实阶段 opacity 切换。阶段仍取 scheduler，计时器只改变装饰。
- **完成时刻（§7）**：`DeepReadArticlePage.ets:235–243、402–413` 增加观察现有运行 COMPLETE 后检查保存稿的触发；`:223–231` 加首篇、十篇、百篇计数；`DeepReadArticlePressMoment.ets:35` 区分 1.4/2.6 秒。历史重开不直接盖章，隐藏/后台由 cycle 和 active 门控。
- **背景（§2.3）**：`DeepReadPaperBackground.ets:10–35` 实现当地 22:00–04:59、520 半径渐变、深色 PLUS、1.6 秒入场和减弱动效；发现 `BoardPage.ets:756`、库 `DeepReadHistoryPage.ets:362`、设置 `SettingDeepReadPage.ets:709` 均接入。
- **模板（§9）**：`DeepReadTemplateSettingsPage.ets:142–153` 改 48×48 图标预览及“文章模板”，不再拿 reader style swatch 表示生成模板；`DeepReadTemplateWorkbenchPage.ets:110–144` 已加未保存返回确认。
- **导航（§1、§11）**：文章错误路由已改 `DeepReadArticlePage.ets:267–270` 的真实模板页并捕获异常。库 query/filter/scroll 保存恢复在 `DeepReadHistoryPage.ets:123–125、147–151`。发现/库使用真实 bindSheet（`BoardPage.ets:827、DeepReadHistoryPage.ets:422`），共享 `DeepReadComposerSheet`；创建保存后 push 详情并关闭 sheet（`BoardPage.ets:731–733`），有取消回调。
- **文章 footer / toolbar（§1、§6）**：`DeepReadArticlePage.ets:950` 已放底部 TabBar 并根据入口选中；`DeepReadTabBar.ets:30–36` 对详情切 Tab 做 replace 后清历史栈。`DeepReadArticleToolbar.ets:60–96` 改左圆形 Back + 左侧 17 标题 + 右侧控件胶囊；分享改方框上箭头 Path。最终更新的 `device/harmony-article-light.jpeg` 已实见这套 toolbar 和 footer，原居中标题与错误图标问题关闭。
- **印章 / 模板（§7、§9）**：`DeepReadArticlePressMoment.ets:50` 已改半透明色 + Thin 背景模糊。模板描述增加 optional 兼容字段（`DeepReadTemplate.ets:194–205`）、编辑输入和保存（`DeepReadTemplateWorkbench.ets:281、607–610`）。自定义编辑/预览改真实 sheet（`DeepReadTemplateSettingsPage.ets:82、87、196–206`）；描述与 push 编辑问题关闭。AI 要求页现已恢复 push（`:91–92`），校验后的草稿通过 `DeepReadTemplateWorkbenchPage.ets:49–56、92–109` 打开 editor sheet；此方向已复验源码，尚未看到最终运行图。
- **壳层字形 / 稳定发现画面（§2.2、§3）**：最终发现图中的“把热点读深一点”“多来源热点”已呈黑体形状，与当前 iOS 参考的中文字形方向一致；英文热点仍保留衬线。`DeepReadDesign.ets:11–12` 将 `DeepReadUISerif` 与正文 `readingSerif` 分开，`:20` 注册 Latin subset；`DeepReadArticleMagazine.ets:18` 继续使用原正文衬线。原把正文宋体强加壳层的缺陷关闭。更新截图中的前 6 张热点卡均已稳定、不透明，原过渡帧证据不足问题关闭。

## 剩余差异与待定范围

精读、只读原文、4 编排、8 样式及专用生成模板涉及 Harmony 当前没有的业务结构，**列待用户范围决定**，不能用无运行能力菜单伪装完成。阅读库目前只有 4 个 domain 状态（`harmony/deepread/src/main/ets/domain/library.ts:7`），对应 5 个筛选；与 iOS 6 个筛选也属于应明确交付边界的能力差异。

未发现为视觉任务越界重写 provider 或生成管线；保留 Harmony 多文件、导出、备份能力是合理范围。

## 证据限制

已目测主 agent 提供的 `device/harmony-library-light.jpeg`、`harmony-settings-dark.jpeg`、`harmony-sources-light.jpeg`、`composer-keyboard-final.jpeg`、`harmony-pdf-preview-light.jpeg`：库卡层次、暗色强调色/文字、来源长 URL 折行、键盘上方主题输入、PDF 预览控制未见重大重叠或裁切。PDF 截图只证明可见预览，不证明实际文件已导出。

最终 `harmony-discovery-light.jpeg` 已是稳定列表：刊头、日期/节日徽章、多来源区、排名、来源摘要、箭头、暖纸卡与 3 Tab 均可见，未发现重大几何重叠。两平台屏宽、系统栏和热点内容不同，不能据此声称逐像素相同。

最终 `harmony-article-light.jpeg` 明确是**部分正文 + 失败状态**：显示“文章尚未完成”“补全章节”、深度分析 JSON 错误、来源失败数，并保留已有正文；画面证明失败提示与旧稿同时可见，**不能称文章生成完成**。本文 reviewer 未运行主 agent 所报告的 build6/4784 测试，也未重复设备操作；最终模板 sheet、印章触感、大字号和新稿四段运行继续以主 agent 后续证据为准，不将这两张图扩大为真实 provider 或完整成功链证明。
