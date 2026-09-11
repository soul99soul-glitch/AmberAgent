# 原 UI 审计复核与处置表

以本实验分支 `d752f90` 为基线；原 main 未完成合并中的已删除 OfficePro 等状态不等于此分支。报告 `docs/ui-audit-2026-09-12.md` 的约 650 条没有逐条展开，不能复算总数。下面复核全部显式 F 编号及 P0 分类，不把风格偏好/未消费组件/理论复杂度直接计成功能 bug。

路径前缀 `UI/` 指 `app/src/main/java/app/amber/feature/ui/`。定点验证命令随实现写入 PROGRESS，不用未经执行的命令冒充通过。

| ID | 分类与可达触发 | 生产依据 | 处置/验证 |
|---|---|---|---|
| F1 | 真问题：多行思考导出缺换行 | `UI/pages/chat/Export.kt:269`，ChatExportSheet→exportToMarkdown | Phase3，复用 ExportMarkdownTest 加最小多行用例 |
| F2 | 潜在缺陷：removeIf捕获错对象；无生产组件调用 | `UI/components/ai/McpPicker.kt:115`，全仓只有定义 | 不作产品P0，不为死组件扩测试 |
| F3 | 潜在契约缺陷：重复modifier；生产调用未传该参数 | `UI/components/ui/Form.kt:30,37`，padding仅Preview | Phase1/3如触及修正契约；不声称42个调用均布局错误 |
| F4 | 条件性真问题：父页面重组丢分享弹窗；并非永远弹不出 | `UI/components/ui/ShareSheet.kt:131` → `pages/setting/SettingProviderDetailPage.kt:61,85,148` | Phase2，remember稳定state，验证显示/关闭/父重组 |
| F5 | 多权限latent；当前只有通知/相机单权限调用 | `UI/components/ui/permission/RememberPermissionState.kt:73`；SettingDisplayPage:143、ChatInput:1161、ChatInputAttachments:691 | 不作当前故障；不扩权限架构 |
| F6 | 本实验基线真问题：OfficePro通知开关只有remember，无消费/持久化 | HEAD `UI/pages/setting/SettingExperimentalOfficeProPage.kt:83,316` | Phase4，核对UI能力真实性，避免虚假开关；原未完成合并已删除此页，勿混淆 |
| F7 | 本实验基线真问题：OfficePro间隔输入被丢弃，后端固定90分钟 | 同页:329、:645；DocRadar.subscribe | Phase4，不为原型新增调度系统，UI如实反映后端能力 |
| F8 | 真问题：MCP空名称可点保存却静默不作为 | `UI/pages/setting/SettingMcpPage.kt:591` 新建弹窗 | Phase2，禁用/校验状态与保存契约一致 |
| F9 | 真问题：关闭测试对话框未取消外层scope请求；同色为另一个语义问题 | `UI/pages/setting/components/ProviderConnectionTester.kt:57,67,87,180` | Phase2，作用域收在弹窗生命周期；错误用error而非改signal决策；不实际调用付费provider作为布局测试 |
| F10 | 条件性真问题：相等Image对象重复时indexOf回首项；仅URL相同不必相等 | `UI/components/message/GeneratedImageCarousel.kt:152`，ChatMessageTools直接传tool.output的Image列表 | Phase3用索引迭代；最多4图常规数据不构成已测性能瓶颈 |
| F11 | 真问题：表格未消费alignment；HTML有序列表固定从1起 | `UI/components/richtext/Markdown.kt:2985` TableNode→TableCellContent；MarkdownNew.kt:480 | Phase3，最小对齐/起始序号修复，保留流式状态机 |
| F12 | 真问题：议会副标题文本直接相接 | `UI/pages/councilroom/CouncilRoomPage.kt:402` Row无间隔；strings的subtitle无前导分隔 | Phase4，显式间距/分隔，长模式名窄屏检查 |
| F13 | 真问题：Provider长名称先测量耗尽行宽，状态点被挤压 | `UI/pages/setting/SettingProviderPage.kt:633` Row中Text无weight，后接LiveDot | Phase2，给可缩文本weight，保留状态点空间 |
| F14 | 真问题：小应用暗色标志取系统而非App主题 | `UI/pages/miniapp/MiniAppRunnerPage.kt:275,376` | Phase4，使用已解析的App主题；系统/App反向模式检查 |
| F15 | 条件性真问题：WebDAV草稿可能捕获dummy空值 | `UI/pages/backup/BackupVM.kt:66` Eager stateIn初值dummy；BackupPage.kt:324 无key remember | Phase4，读取owner真实初值并保留本地编辑，不能每次设置变化覆盖草稿 |
| F16 | 假阳性：去设置经proceed间接调用成功 | PermissionRationaleDialog→PermissionManager:29→PermissionState.kt:176 openAppSettings | 不改授权链；死参数不是用户故障 |
| F17 | 当前生产假阳性：缓存label没有可见消费方 | `UI/pages/chat/ChatDrawerVM.kt:30,91` 确实在分页生成期计算label；但ChatPage已移除旧Drawer，SessionHomePage只给DateHeader生成key而不渲染label | Phase4补查实际消费闭环后撤销该产品缺陷；不引入无用午夜刷新定时器 |
| F18 | 删除失败恢复缺失成立；归因手工state工厂不准确 | `UI/pages/history/HistoryPage.kt:132,204,214` 删除失败仅snackbar，未reset swipe | Phase4按删除结果复位；只换remember工厂不能修好 |

## 原 P0 九项

1. TextArea 空label不渲染：源码成立，但无生产调用；不是产品P0。
2. FormItem：同F3，无当前触发。
3. ShareSheet：同F4，条件性状态问题，降级。
4. 权限归因：同F5，当前调用范围不触发。
5. DeepRead 固定杂志色板：设计隔离存在，不能仅因不跟accent定P0。Phase4按阅读与chrome分层决定。
6. sandboxStatusContainerColor：无引用函数中的色值不是用户可见错误。不为删死代码扰动任务。
7. McpPicker：同F2，无生产调用。
8. `RoundedCornerShape(50)`：明确假阳性，Int为percent，不是物理像素。当前Compose 1.11.0 foundation.aar的javap表明 `CornerSize(int)`创建`PercentCornerSize`，`CircleShape`本身调用`RoundedCornerShape(50)`。保留。
9. SAF文件名ISO时间：含冒号属实，但`toLocalDateTime`无时区偏移；是否保存失败取决于文档provider。未复现，不称P0；Phase3可以用可移植命名改进体验，不引入重试/备用写入。

## S1–S8

- S1 多源存在：主要是设计一致性欠账。`AmberTokens.kt:112`明确signal=accent为既有决策；Phase1桥接已有调色板，错误语义单独处理，不凭“多源”重写所有组件。
- S2 字阶差异存在；文档标题另有层级。ModelList字距乘字符串长度与自身0.15em注释冲突是真P2，Phase2修复；不要把Markdown H1强压19sp。
- S3 图标视觉尺寸不等于点击热区，原清单不能直接当成20+可达缺陷。Phase1共享控件、Phase2–4具体页面检查布局/语义区域；不盲目给所有小图标套48dp造成挤压。
- S4 圆角多档不等于故障，且Int重载判断错误。统一卡片/按钮，保留气泡、图片、特殊控件形态。
- S5 pressable确有设计注释依据；Material反馈仍然是有效反馈。精修共享入口，保留需长按/拖动等专用交互和流式动画。
- S6 机器事实字体分工有规范依据，但系统Monospace并非功能bug。逐触及组件改AmberMono，正文保持sans/既有用户字体设置。
- S7 页面边距分散：按16dp内容基线收敛，阅读沉浸区单独处理，不要求所有嵌套元素机械同一padding。
- S8 主线程/组合期IO需区分真实调用。`WebView.shouldInterceptRequest`为后台回调，不能把其中runBlocking直接叫主线程IO；死代码不作P0。已确认的磁盘工作可另标风险，但不趁UI任务重写Novel运行owner。

## 分模块补查

- Skill文件允许`examples/basic.md`，SkillManager→SkillPaths canonical边界检查拒绝越界；UI未禁`/`不是路径穿越漏洞。
- BackupDialog退出是恢复后防旧内存回写的明确行为，保留。
- Board刷新有结果变化effect提前结束，15秒只是超时兜底；撤销“固定转15秒”。
- Log默认true在已保存false时可能短暂显示不符，低等级显示问题。
- TodayBoard权限说明remember可能在从系统设置返回时陈旧，限文案，不等于权限绕过。
- Live每项结果上游cleanAnalysisItem限90字符，原文“无限撑高”遗漏约束；小屏/大字体仍需布局验收。
- SearXNG密码无掩码、混合HTML段距差异：源码成立，分别进入Phase2/3。
