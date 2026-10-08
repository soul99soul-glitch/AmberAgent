# DeepRead 当前工作区 subagent review

2026-10-05，4 个独立 reviewer 分别检查业务闭环、Standards、iOS Spec 和 UI 几何；主 agent 负责核对复现、独占设备采集和最终判断。

**主生成与持久化链路有完整实现，未发现本轮可确认的 P0/P1 主链阻断；边缘流程仍有缺口，不能宣布完全复刻或全部闭环。** 本轮确认 8 个 P2、3 个 P3：Standards 1 P2；Spec/调用链 7 P2 + 1 P3；UI 2 P3。等级分别按各轴判断，没有将既有动效缺口重复算成新问题。

## 范围与证据

- HEAD：`b2d054bd7a81c64795497098d54cf30ad049e04f`。审阅当前 DeepRead WIP 及直接共享调用链，不把 408 项既有工作树状态都归责此前 UI 变更。
- 当前安装深读包 SHA-256：`c76700f55309b579d2ea469ba3e242f0c3c940a42fb97c57418128896e8821f9`。审阅开始及结束均核对安装源码清单，**846 个 main TS/ETS 文件无漂移**：[最终源码核对](final-source-verification.json)。不将只读源码比较冒充本轮构建、签名或安装。
- iOS 参考为当前 `amberagent-ios/iosApp/DeepReadApp/Sources` 与 `DeepReadCore`，辅以本轮采集的 iOS 设置/发现/版式截图。两个模拟器宽度及配置不同，没有逐像素等同声明。
- 只写审阅报告、方法 probe 与截图；没有修改生产代码、测试断言、账号、模型配置、文章或持久阅读设置，没有真实 provider 请求、文件分享、安装、提交或推送。临时 iOS 深色外观及 MCP session defaults 已恢复。
- 证据分类：源码对应 S；本轮现有测试 T；当前生产方法/表达式抽取并注入端口复现 E；本轮模拟器操作、截图和 layout D。E 证明该条件下的实际方法行为，不代表真实云端或 native 事件顺序验收。

## Standards

**1 个 P2，未发现额外硬规范违例。** [独立报告](standards.md)

| ID / 等级 | 触发与影响 | 位置与证据 | 最小修正方向 |
|---|---|---|---|
| ST1 / P2 | 发现设置即时写入尚未完成就返回；发现页先读旧值，写入完成后列表仍用旧偏好 | `DeepReadDiscoverySettingsPage.ets:103–140` → `BoardPage.ets:497–527`；E：[复现](standards-return-race-repro.cjs)、[结果](standards-return-race-repro.log) | 对有限写入完成与首次返回刷新建立顺序，覆盖标题及系统返回；不引入通用事件框架 |

生命周期、timer/sensor 的归属与失活清理、API12 新能力门控、独立产品边界及设置会话草稿均已核对。强调色快速离页、预览旧回调只保留未验证风险，不据假设要求重构。

## Spec / 调用链

**7 个 P2、1 个 P3。** [闭环独立报告](closure.md)、[iOS Spec 独立报告](spec.md)

| ID / 等级 | 触发与影响 | 当前位置 / iOS 对应 | 证据 |
|---|---|---|---|
| C1 / P2 | 聚合热点含多个文本来源时，每张来源卡展示合并 inputText，正文归属错误 | `DeepReadSourcesPage.ets:222`；Article 保存各 source 后另合并 inputText | S、E：[实际表达式复现](closure-repro.cjs)、[结果](closure-repro-result.log) |
| C2 / P2 | 打开原链接被浏览器拒绝时提示“已复制链接”，实际剪贴板写入为零 | `BoardPage.ets:478、481` | S、E：同上，异步拒绝及同步异常两条路径 |
| S1 / P2 | 有主题但网页链接为 ftp 等非法 URL，仍保存并进入文章流程，输入错误没有内联拦截 | `DeepReadComposerSheet.ets:132–162` / iOS `DeepReadComposerView.swift:149–159` | S、E：保存与 onCreated 均 1 次、error 为空；不声称已经产生模型费用 |
| S2 / P2 | 点击具体模型/搜索服务行先打开整个目录，没有定位该对象，需再次辨认选择 | `DeepReadSettingsForm.ets:172–177、190–196` / iOS `DeepReadSettingsView.swift:36–46、62–74` | S：入口与目的页参数均核对；目录功能可用，非断路 |
| S3 / P2 | iOS 五槽位模板校验通过，但 font_css / analysis_html / extended_reading_html 未替换，章节补到末尾 | `DeepReadTemplate.ets:243–333` / iOS `IOSDeepReadHTMLSecurity.swift:203–213`、`IOSDeepReadTemplates.swift:202–214` | S、E：生产 validator + renderer，三槽位残留 |
| S4 / P2 | 只剩一个过期/无效搜索服务时无法删除 | `ChatSearchSettingsPage.ets:467–472、879–880` / iOS `DeepReadSettingsView.swift:74` | S、E：单服务删除写入 0 次；修复需限定独立深读产品边界 |
| S6 / P2 | 重新生成时旧正文仍保留，但导出被 running 条件禁用 | `DeepReadArticlePage.ets:273–276、858–860` / iOS `DeepReadDetailView.swift:127–133` | S：旧稿存在条件与分享禁用条件核对；本轮没有真实再生成/文件导出 |
| S5 / P3 | 展开第二来源会关闭第一来源，无法同时对照多份正文 | `DeepReadSourcesPage.ets:25、96–105` / iOS `DeepReadDetailView.swift:253–269` | S：单 expanded ID 对应 iOS 独立 DisclosureGroup |

[Spec probe](spec-probe.cjs) 与 [结果](spec-probe-result.json) 可复跑；主 agent 已复核 closure、Spec 和 Standards 方法结果。

主链核对：Composer/发现入口 → 输入与模板快照 → repository upsert → Article → scheduler/run token → 真实配置快照 → 来源采集 → 规划 → 四段生成与 JSON 校验 → await 持久保存 → COMPLETE → 完成反馈/阅读库观察 → 已存文章重开。取消、失败、续跑、单段重试、导出快照、通知 Want 路由与后台恢复边界均有下游实现。保存拒绝会上抛，视觉完成不代替存储完成；重生成失败保留旧稿。固定通知 requestCode 的疑点经官方 Want identity 参数规则核对后排除，不能套用 Android PendingIntent 判为覆盖。

## UI 几何与交互细节

**确认 2 个 P3；当前默认字体与单一手机宽度未发现 P0/P1/P2 几何故障。** [18 步页面健康与尺寸报告](ui.md)

| ID / 等级 | 可见问题 | 精确证据 / 位置 | 最小修正方向 |
|---|---|---|---|
| U2 / P3 | 创建阅读“导入文件”显示空圆圈 | [当前截图](device/19-composer.jpeg)；Composer `:253、261` 使用 `file`，`components/ui/Icon.ets` 不支持该名字，`:158` fallback circle | 两处换成现有 `doc`；已选附件行同样受影响（附件行是源码证据） |
| U3 / P3 | 发现热点标题列随序号宽度轻微移动 | [当前截图](device/18-discovery.jpeg)、[layout](device/18-discovery.json)；01 宽80px、02宽86px，标题x236/x242；`DeepReadDiscoveryCards.ets:63–64`，iOS 使用 monospacedDigit | 给序号统一宽度；偏移 **6px / 1.71vp** |

本轮实看设置顶部/搜索区/末尾、模板列表/编辑/键盘/预览、发现页、创建阅读及键盘、阅读库、文章、Aa 菜单、来源展开和导出面板。各步骤截图及 layout 在 [device](device)，iOS 本轮参考在 [reference](reference)。保存/生成/删除/重新生成均未触发；菜单打开并不代表实际系统文件分享完成。

模拟器截图为 1320×2856px、density3.5。已量化外边距18vp、设置行内边距16vp、保存高52vp、底栏270×64vp、文章操作点击面积44×44vp、阅读卡片间距12vp。默认字号下未见横向溢出、卡片重叠或底部操作永久不可达；滚动 viewport 的 bounds 裁剪不误报为固定高度错误。

混合字号 section 的 center 与 iOS firstTextBaseline 有排版差异，但当前没有文字相撞，记录为进一步精确复刻方向，不另报 P 缺陷。

模板预览最初疑似空白，已撤销稳定故障判断。新组首开/关闭后同主题重开，在 +3s/+11s 采集 [30](device/30-preview-first-3s.jpeg)、[31](device/31-preview-first-11s.jpeg)、[32](device/32-preview-second-3s.jpeg)、[33](device/33-preview-second-11s.jpeg)，四个独立 snapshot 路径的文件 SHA-256 完全相同 `b1b2e190bae86f0808086219d29198a164a9b2837bf0b23001fc012496421e5e`，四份页面树均含示例标题。图片查看工具对相同编码显示出相反状态，不能据此报产品稳定空白。旧回调条件 probe 只列未复现风险，不进入确认计数。

## 验证与未验范围

- 闭环 reviewer 本轮运行 11 个现有测试文件：**114/114 通过**，[日志](closure-tests.log)。覆盖作业、signal、失败持久化、进度、输入、库、导出、Want、模型选择与生命周期。
- Standards reviewer 本轮运行 4 个现有测试文件：**40/40 通过**。两组可能重叠，不合并声称154个独立测试；测试通过也没有关闭本轮实际方法复现的缺口。
- 此前 4861/4861 全量测试属于上轮交付，本轮没有再次执行全量、构建、签名或安装。
- 未验证：API12 真机、长时间后台及进程回收、多通知实机点击、真实云端成功率、系统文件授权/分享、最大系统字号、横屏/tablet、超长模型名、读屏、全部动效帧率及触感。键盘开启时末尾按钮可达性与普通关闭键盘后的可达性分别记，不混为全面验收。
- 此前明确的原生 Tab 随滚动缩小、来源卡到文章共享缩放、原文/样式/编排选择三组缺口保留；本轮未修复，不用默认导航充当对应动效。

审阅完成，确认问题仍开放。优先处理内容归属、非法输入、模板合同和设置返回刷新，再处理恢复提示与对象级导航，最后做图标/列对齐等局部精修；各轴的级别保持独立。
