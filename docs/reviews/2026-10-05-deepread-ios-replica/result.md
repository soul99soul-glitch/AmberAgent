# 深度阅读 iOS 界面复刻交付记录

2026-10-05。本轮已将鸿蒙**独立深度阅读**现有界面按当前 iOS 工作树重做，并构建、签名、安装到鸿蒙模拟器。它仍不能称为 iOS 全功能完整复刻：精读／只读原文、4 种阅读编排、8 种阅读样式及 8 种结构化生成模板尚需补齐业务链。本轮没有添加无实际消费者的选项。

## 基准与范围

- Harmony 源根：`/Users/arquiel/Downloads/AI/amber-harmony-preview`，HEAD `b2d054bd7a81c64795497098d54cf30ad049e04f`。保留任务开始前的 dirty/untracked 工作，本轮未提交或推送。
- iOS 只读参照：`/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp` 及直接引用的 DeepReadCore，HEAD `4782e5a04e5700c34c7bf1016dcb8f047e66e6e4`。该产品含未提交工作，基准为实际工作树。
- 重新编译当前 iOS 源码并运行在 iPhone 17 Pro 模拟器；`reference/ios-current-*` 才是本轮有效运行参照。早期无 `current` 名称的截图来自旧安装包，不用于最终视觉结论。
- 鸿蒙独立包：`app.amber.deepread.reader`，入口 `EntryAbility`，HDC `127.0.0.1:5557`。共享宿主的视觉分支与既有业务能力保留；未构建或安装 Android 宿主。

## 已实现及证据

| 页面或行为 | 实际改动 | 证据与边界 |
| --- | --- | --- |
| 统一设计 | iOS 暖纸浅／暗色、5 个强调色、纸粒纹、大标题、分组纸卡、统一边距。UI 字体使用既有字体的 Latin subset，中文走系统回退；文章阅读衬线字体保留。 | lint、编译、当前 iOS/Harmony 截图及独立视觉复核。系统字体不能当作跨平台同一字体文件。 |
| 发现 | 刊头、日期／期号／节日、热点密度、单榜分组、刷新与旧内容提示、导航栏创建入口。 | 最终稳定浅色图；真数据热点和配置调用保留。 |
| 根导航 | 发现／阅读库／设置三个底部页签；创建采用 sheet。搜索、筛选和滚动位置在切换后恢复。 | 实际搜索保持验证；正文切设置仍保留三个页签。修复 replace 初始化早于清栈导致漏掉根导航的问题。 |
| 创建 | 阅读主题、补充文本／链接／文件、模板、开始操作的表单层级。单一导入和保存控制器复用。 | Pura 90 模拟器键盘 UI 树、点击 bounds 和截图；路由失败后重开同一已保存稿的行为测试。 |
| 阅读库／设置 | 大标题、全文搜索、状态筛选、真实文章状态卡；强调色、模型、搜索、阅读字体／字号与热点配置进入实际既有存储。 | 当前字体截图、查询恢复和持久文章状态验证；搜索设置只合并本表单拥有的字段。Harmony 现有状态种类与 iOS 不完全相同。 |
| 正文 | 左返回／标题、右 Aa／来源／分享胶囊；默认杂志的标题、段落、时间轴、引用和来源排版；保留重试、取消、旧稿、字号与导出。 | 当前工具栏截图、部分稿失败提示、完整受控稿；用户自定义 HTML 自身 CSS 不被改写。 |
| 编辑室 | 采访／整理／撰稿／付印四阶段，真实阶段驱动；新稿持续等待到首次运行终态，旧稿继续可读。完成章依赖真实保存完成信号。 | 本地受控模型通过真实调度、段落校验和保存链；各阶段截图。未以静态截图证明流畅度或完成章的完整逐帧动画。 |
| 模板 | 纸卡列表、编辑／预览 sheet、可持久化描述、代码区、校验与未保存退出确认；AI 要求仍为 push 页，生成后进入编辑 sheet。 | 实际方法／存储测试；设备验证描述键盘、继续编辑保持输入、明确放弃后不保存，以及 AI 要求页 push 入口。AI 保存／取消返回要求页另有行为测试；实际云端 AI 模板生成未验收。 |
| 原始来源／导出 | 来源卡、URL／采集状态／错误、展开原文、复制／浏览器；文本、Markdown、PDF 与文件保存。 | 长 URL／原文展示截图；既有完成稿真实生成 PDF 并打开系统分享面板，未发送给外部对象。 |

## 验证结果

- 全量 `node scripts/test-harmony.mjs`：**4784/4784 通过，184 suites，0 fail / skip**，见 [final-tests.log](final-tests.log)。仅必要更新新增依赖的测试 fixture，原行为断言保留。
- 受影响 ArkTS 文件 scoped lint：**0 errors**，仍有既有深拷贝和组件性能建议；没有声称全仓库零警告。
- 独立 DeepRead 最终构建、签名及签名验证成功，覆盖安装成功，入口实际启动；见 [final-build.log](final-build.log)。编译仍有既有 router 废弃警告。
- 最终签名 HAP：`.harmony-standalone/deepread/entry/build/default/outputs/default/signed/entry-default-signed.hap`，62,483,646 bytes，SHA-256 `16bea4096b977e9b9f8a1fef0f7ca5a59b57743fb1e4dddd6e0c0d98278620bf`。
- 生成目录的 976 个运行文件与当前共享源一致；差异仅为 module.json5、string.json 的独立包品牌配置，以及来自 products/deepread/app_icon.png 的生成图标，见 [verification.json](verification.json)。
- `git diff --check` 通过。独立 Standards 与 Spec 审查的确证问题均已修正，见 [standards-review.md](standards-review.md) 和 [spec-review.md](spec-review.md)。

本地模型只返回本轮固定内容，用于验证界面和运行调用链，**不代表真实云端 provider 或研究质量验收**。网络来源仍走真实采集：新验证稿显示 5 个采集失败，错误继续可见。早期受控稿 `IOS-Replica-20261005` 因 mock 分析字段少于既有最短长度而失败，部分稿保持可读；修正 mock 数据后，新稿 `IOS-Replica-Final-20261005` 四段校验均通过，进入完成正文；退出后阅读库显示“完成”。生产 validator 没有为测试放宽。临时 loopback 模型服务与本轮 HDC reverse 已停止／移除；模型选择恢复为“跟随当前模型”，测试搜索已清空。

## 可复核截图

- [当前 iOS 发现页](reference/ios-current-discovery-light.jpg) 与 [最终 Harmony 发现页](device/harmony-discovery-light.jpeg)、[最终暗色发现页](device/final-discovery-dark.jpeg)。
- [最终浅色设置](device/final-settings-light.jpeg)、[最终暗色设置](device/final-settings-dark.jpeg)、[阅读库持久完成状态](device/final-library-persisted.jpeg)、[最终暗色阅读库](device/final-library-dark.jpeg)。
- [最终完整受控文章](device/final-article-complete.jpeg)、[撰稿阶段](device/final-newsroom-3.jpeg)、[付印阶段](device/final-newsroom-4.jpeg)。
- [创建键盘与输入 bounds](device/composer-keyboard-final.jpeg)、[模板描述键盘](device/final-template-keyboard.jpeg)、[未保存退出确认](device/final-template-dirty-exit.jpeg)、[AI 模板要求页](device/final-template-ai-requirements.jpeg)。
- [原始来源](device/harmony-sources-light.jpeg)、[PDF 预览](device/harmony-pdf-preview-light.jpeg)、[系统 PDF 分享](device/harmony-pdf-share-light.jpeg)。

`before-light` 为启动器；`before-app-light` 实际为早期暗色；带 `partial-early` 的本地稿是部分生成画面。这些都不是最终成功稿或最终浅色验收。

## 尚未达到的完整复刻范围

| iOS 当前能力 | Harmony 现状及需要的工作 |
| --- | --- |
| 单源精读、只读原文、之后生成精读 | 缺原段落 ID、批注／模块结构与免模型原文路径；需接入模型、解析、存储、渲染和恢复，不能仅加切换按钮。 |
| 经典／速读／时间线／观点对照 4 编排 | 缺统一偏好和正文／PDF 内容顺序消费者。 |
| 经典杂志等 8 阅读样式 | 需实际 CSS／原生正文／预览／PDF 联动，当前只有现有默认、斜切和自定义 HTML 链。 |
| 8 内置结构化生成模板 | 当前 HTML 展示模板与 iOS synthesis 生成结构不同；需完整生成契约接入。 |
| iOS 全部任务状态与交互细节 | 状态分类、系统控件、原生转场及辅助功能仍有平台差异。 |

以上业务扩展已单独提出范围问题，等待用户选择；现有界面工作和验证不依赖该选择。当前成果不能标记为“iOS 所有页面／所有功能完全一致”。

夜读光晕逻辑已接入并核准 API，但本轮设备实际当地时间为下午，未实拍夜读时段。物理设备、真实云端生成质量、系统减弱动态效果接线、横屏／最大辅助字号、输入过程中切主题及逐帧性能未验收。iOS 与鸿蒙的系统状态栏、文件选择、权限和分享面板遵循各自原生行为。

## 字体资产来源

新增 `DeepReadUISerif.otf` 为项目既有 `NotoSerifSC.otf` 的 23,072-byte Latin 子集，保留元数据，只用于界面壳层。读取字体仍是原文件。生成方式为 fontTools subset，Unicode `U+0020-024F,U+2000-206F`，保留名称与许可信息；没有替换中文阅读字形或增加外部字体依赖。
