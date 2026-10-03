# P6 深度阅读 UI 实施与验证

## 实际产品链路

- 创建页增加「本篇版式」，可选择内置及自定义模板。开始生成前读取当前所选模板正文并保存 `templateId/templateSnapshot`，与作者粘贴、链接及导入文件一同持久化，再转交现有文章 runtime。选择已删除模板时可见报错，不创建文章；文件选择和模板初次读取各自保持请求归属。
- 文章页的「本篇版式」归属、Web 阅读及 PDF 均使用文章持久快照。字号、字体仍为阅读偏好；改变或删除全局模板不改变已有文章。旧归属或缺失快照显示明确说明。Web 失败后的原生阅读仅为当前显示选择，不改文章快照或后续 PDF。
- 明确重新生成时，scheduler.run 显式传入本篇 `templateSnapshot`；领域与 DI 优先消费该参数，避免 force 路径改用最新全局模板。现有实际 cached-force 用例同时断言保存稿与实际调用参数的原自定义 HTML 一致。iOS 当前按 templateId 从可变模板库渲染，本次完整 HTML 冻结是这里新增的归属保证，未把它误记为原 iOS 已有行为。
- 发现页传入的真实热点文本保存在 `inputSources/inputText`，保留来源和排名，不制造不存在的 URL。普通打开已有完整缓存不重新生成；作者明确重新生成且当前无活动任务时，先更新本次热点来源、排名和真实链接，再由原生成入口消费，同时保留原已保存正文、模板归属和创建时间。活动任务仍在运行时明确提示先取消，不改来源或另启动任务。
- 阅读库按真实 repository 历史和 scheduler 活动任务事件更新。页面可见时订阅，隐藏或离开时释放；返回重订阅并保留搜索及过滤。尚无缓存的活动任务也有可达行，未伪造 cache entry；模型或运行服务暂不可用时持久文章仍可阅读。标题栏「创建」直接进入已有创建页。
- 来源页为真实 HTTP／HTTPS 来源提供「打开网页」，调用系统浏览器，打开失败在原页可见；复制 URL 保留。长来源标题、URL 与已保存正文自然换行。
- 导出面板提供真实「保存 TXT 文件／分享 TXT 文件」；复用既有原生文件选择及文件分享端口，写入 UTF-8 bytes 与纯文本 MIME。纯文本按现有 Chat Markdown AST 转换正文，不用通用正则剥离；来源、日期、URL、图注等字面元数据保留。部分稿保留真实部分状态标记及 `-partial.txt` 文件名，PDF 仍须完整稿。原文本分享、Markdown 及 PDF 入口保留。
- 单文件 20 MiB 仅属于深读导入器 `MAX_DEEPREAD_SOURCE_BYTES`。聊天附件的 `MAX_DOCUMENT_ATTACHMENT_BYTES` 仍为 10 MiB，未改变全局附件政策。
- 文章根 Stack 挂载独立 `DeepReadGoogleSearchHost({ topicId })`；组件及系统搜索接线由 root 负责，使用独立 WebMountSession，未借用 Chat 会话。

## 代码边界与验证

本 ownership 的生产文件为 `DeepReadComposePage`、`DeepReadArticlePage`、`DeepReadHistoryPage`、`DeepReadSourcesPage`、`DeepReadExportPanel`、`DeepReadExportFiles`、`DeepReadSourceImporter`，以及 root 明确批准的 `domain/export.ts` 纯文本正文 callback 路径。领域模板、repository／scheduler 事件和 Google host 分别由其原 owner 提供；未另建 UI 存储、轮询或任务状态机。

新增实际 Compose／Reading／导入调用方法测试 13 项，加既有 Reader 18 项及 Workspace 保存 4 项，**35/35 通过**（`/tmp/amber-p6-compose-reader-tests.log`）。测试执行真实模板 capture、resolution 和 HTML renderer，覆盖模板删除后仍读原内容、明确缺失归属、late capture 不持久化、无 URL 热点、已有热点明确重生成消费新来源及排名、活动任务拒绝更新，以及 12 MiB 深读文件读取／20 MiB 拒绝与聊天 10 MiB 原值。读取字号／字体的两次 await 完成后统一核页面 token 再写入，首用确认读取后也核归属，新增两个实际 late callback 用例通过。

Exports／Sources 实际方法和原生文件端口消费者 **45/45**、纯文本领域 **5/5**、PDF 同快照消费者所在既有 library-entry **3/3** 通过。测试真实写 UTF-8 文件 bytes、纯文本 MIME 分享、部分稿标识、AST 纯文本正文、字面链接及图注保留、保存稿快照与晚回调；不是只匹配源字符串。独立审查发现来源页旧 initial get 可能覆写先到的新 observer 正文，已仅以局部 sourceVersion 区分读取优先，新增实际反例通过。

阅读库新实际 UI 方法 **8/8**、既有 UI-07 返回入口及领域 Library／History **8/8** 独立通过。独立审查发现只有活动任务而缓存为零时，手动刷新失败被旧缓存数量 guard 隐藏；现按实际可见行显示错误，原生产方法及渲染 guard 反例复验关闭。详见 `p6-library-ui-independent-review.md`，未增加轮询或全局 UI 偏好。

上述 UI 与既有 Provider／页面生命周期组合 **124/124 通过**（`/tmp/amber-p6-ui-focused.log`）。deepread／chat 双模块 `tsc --noEmit` 均通过（`/tmp/amber-p6-ui-deepread-tsc.log`、`/tmp/amber-p6-ui-chat-tsc.log`）。Compose／Article／Importer 窄 lint **0 error、3 warn**：原文件选择循环 State 读取建议及两个已知 DTO JSON clone 性能建议，未为建议引入新的 SDK clone API；Library 单页 lint 无缺陷；Exports／Sources 四文件 lint **0 error**，其中 Entry 三文件无明细缺陷、领域原 ArkTS 类型解析 warning 单独保留在 `/tmp/amber-p6-sources-export-lint.log`。

新增 UI 控件最小 44 vp；创建、来源与导出滚动体保持 TopStart，输入页沿用 RESIZE，异步结果核页面与本次请求归属。源码声明和方法测试不等于设备视觉验收。

源码已冻结，交叉独立 review 文件为 `p6-ui-independent-review.md` 与 `p6-library-ui-independent-review.md`，各 reviewer 检查其他 owner 的生产文件，未以自审代替独立检查。构建、签名、安装、设备视觉、系统浏览器与系统文件接收各为独立 gate，由 root 统一执行。本文件仅记录当前源码与方法证据。

独立 UI reviewer 的 Compose／Reading／来源／导出组合 **83/83** 加纯文本领域 **5/5** 通过；SOURCE01、READER01 及已有热点 force 输入事实均已复验关闭。阅读库独立 reviewer 的 active-only 错误真实渲染反例也关闭。当前 ownership 无剩余源码 blocker，后续设备新事实仍由其对应 gate 记录。

root 最终设备 review 在 `device/p6/library-live.jpeg` 确认深色阅读库搜索框的占位文字对比不足。已仅补 `placeholderColor(INK3)`，沿用创建页主题色惯例，逻辑、宽高与边距不变。既有阅读库实际方法回归与单页 lint 记录在 `/tmp/amber-p6-history-placeholder-tests.log`、`/tmp/amber-p6-history-placeholder-lint.log`；新包占位文字视觉复验由 root 统一执行。
