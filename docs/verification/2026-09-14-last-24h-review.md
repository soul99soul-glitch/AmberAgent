# 过去 24 小时改动：审查与精准修复

## 范围与方法

- Android 仓库；基线 HEAD 为 `209f4ad`。
- 提交范围：`3ff8cd4`、`38eb5bb`、`6aa0132`、`209f4ad`，以及审查开始时的未提交改动。
- 七个子代理分别检查运行内核/Provider、WebMount/ZCode、子代理与 Dock、聊天 UI、设置/主题、导航/内容页、工作区/记忆页。主代理复核候选、安排复现、检查修复边界并集中验证。
- 审查不等于断言每个问题均由这四次提交首次引入：本轮检查的是这些改动触及的完整生产调用链。
- 保留既有 WebMount/ZCode、任务卡外观和其它 WIP；没有把全部工作树改动归为本轮修复，也没有执行提交或推送。

## 确认问题与修复

### 运行与状态闭环

| 问题及触发 | 最小修复 | 主要位置 |
| --- | --- | --- |
| 同一工具批次同时存在 Auto 与待审批调用，批准后 Auto sibling 被遗漏；恢复期间策略变严也可能仍执行同批其它调用 | 仅对带有已持久审批/决定的同批 Auto 重新判定；保持原始调用顺序；有 ASK 时整批等待，不伪造 Approved，不恢复纯 Auto 的截断调用 | `DefaultRunKernel.kt` |
| followup 绕过子代理并发上限 | 复用全局 admission，在写入 followup 队列前预留容量；拒绝不留下 QUEUED 消息；预留转运行在同一锁内完成 | `SubAgentManager.kt` |
| 审批暂停的线程可被普通 followup 覆盖 | 对 APPROVAL_REQUIRED 返回明确错误，保留原暂停状态 | `SubAgentManager.kt` |
| 任务快照先发布，详情可能先绑定到不存在的 live flow | start/followup 先建立进程内流和 mailbox，再发布可见任务快照 | `SubAgentManager.kt` |
| 重启后 ThreadGraph 仍等待审批，但任务/Dock/聊天卡投影为中断或运行；冷取消后状态不同步 | 使用既有 ThreadGraph 状态修正 UI 投影，按 generation key 失效；取消态不接受旧审批缓存；冷取消同步 TaskStore | `SubAgentDockState.kt`、`SubAgentDockDetails.kt`、`ChatMessageSubAgentStep.kt`、`SubAgentManager.kt` |
| followup 只有结构化结果时，旧答案 seed 被当成本轮输出 | 在私有运行上下文区分 previous answer，禁止 seed 写入本轮终态；保留真正的本轮 Text/Reasoning 和历史 transcript | `SubAgentManager.kt` |

内核缺陷已在旧代码上通过两条新回归复现；修复后验证包含真实 Room ledger 的两个 effect 均进入 FINISHED。

### 设置与 UI

| 问题及触发 | 最小修复 | 主要位置 |
| --- | --- | --- |
| 标题/建议/OCR/压缩 Prompt 每次输入直接保存，取消不能撤销；重复出现恢复默认按钮 | 使用本地 draft，确认时捕获输入值并按最新设置提交；取消不写入；删除重复入口 | `SettingModelPage.kt` |
| 删除当前主题包留下悬空应用标记 | 仅清理匹配的 appliedThemePackageId，保留颜色、字体和布局；保存失败保留原补偿路径 | `ThemePackageManager.kt` |
| 被移除的显示开关只在读取侧归一，写入后的直接发布可能重新暴露旧值 | 读、写、立即发布复用同一四字段归一函数 | `SettingsAggregator.kt` |
| 当前模型在后一个服务商时，初始索引把前组折叠模型也计入，导致当前模型不可见 | 初始定位与实际折叠组及间隔行的计数一致 | `ModelList.kt` |
| 首页仅保留第一个继续候选，并丢失继续流的错误/重试入口 | 保留首项直接打开，增加其余候选的有界选择列表；恢复错误重试回调 | `SessionHomePage.kt` |
| 浏览器任务卡按钮在 200% 字体下被固定高度裁切 | 按钮改为最小 36dp，可随文字增高；正常收起条保持 36dp | `WebMountTaskCard.kt` |
| 字体选择项、备份标签、个人统计和技能统计在多语言/窄屏下裁切 | 分别采用最小高度、单行省略、自然换行或横向滚动；保持原输入列与数字基线 | `SettingDisplayPage.kt`、`BackupPage.kt`、`ProfilePage.kt`、`SkillsPage.kt` |
| 热点详情长标题/多来源使下方动作不可达 | 详情内容可滚动 | `BoardPage.kt` |
| Council 长作者名/模型名挤压角色，大字体被固定高度限制 | 名称受约束省略，角色可换行，身份 tag 使用最小高度 | `CouncilTimelineTab.kt` |
| 记忆库把全部记录塞进单个 Lazy item，失去逐行虚拟化 | 恢复 itemsIndexed、稳定 key 和分组圆角；保留编辑、删除、接受、忽略动作 | `SettingAgentMemoryPage.kt` |

模型初始定位及浏览器大字体裁切均有旧代码失败、修复后通过的回归证据。

## 排除或未确认的候选

- **消息长按层普遍吞掉子控件点击**：原始触摸 Compose 测试通过，撤回该 P1 结论；未重写生产手势。
- **ZCode 必须强制重载保存网址**：与复用当前 pooled WebView 的既定契约不符，合法导航/登录跳转也可能改变 origin；未增加强制 reload。
- **仅空白的 Lexical 草稿属于必须保留的业务内容**：现有编辑器及发送按钮本就采用 trim 判空，未证明有业务内容丢失；未扩大拦截。
- **连接切换必然导致错绑或凭据泄露**：已有条件写入、连接检查、owner 绑定及失败关闭，未证实上述后果；未增加新的连接锁或 generation 框架。
- **同毫秒 followup 必然复用旧 Dock key**：旧 key 使用本轮启动时间，不是终态更新时间；没有给出生产中的同毫秒触发证据，未引入序号系统。
- **MiniApp 错误区必然挤出 footer**：现有中间区域有 weight 约束，静态判断不足；未复现实机键盘场景，未修改。
- `PreferencesStoreTest` 的旧 Prompt 字面短语断言与当前表达不符，不代表 tool_search/expanded_tools 生产契约丢失。本轮未为此改生产 Prompt，也未将全仓测试宣称为全绿。

## 验证

相关测试共 **117 项通过**，未运行全仓所有测试：

| 测试类/组 | 通过数 |
| --- | ---: |
| DefaultRunKernelTest | 27 |
| SubAgentThreadGraphIntegrationTest | 23 |
| SubAgentDockStateTest | 14 |
| ThemePackageManagerTest | 9 |
| SettingModelPagePromptTest | 1 |
| ModelMenuInteractionTest | 7 |
| ChatMessageLongPressTest | 3 |
| WebMountTaskCardTest | 4 |
| HomeCompactLayoutTest | 6 |
| Backup/Profile/Skills 布局测试 | 3 |
| Board/Council/Memory/Display 布局测试 | 4 |
| SettingsAggregatorHelpersTest | 7 |
| SecretPrefsChainRoundTripTest | 9 |

- 子代理恢复测试使用真实 Room/运行管理器与脚本化 runner；对象重建模拟冷启动，不等同于真机杀进程验收。
- Prompt 测试操作真实 Compose 控件并读取 SettingsAggregator/DataStore；验证取消不保存、确认后更新。
- 记忆库测试验证 120 条候选/记录的末项初始不组成、滚动后可见，没有声称具体设备帧率提升。
- WebMount 子代理执行 `webmount-bridge-regression.cjs`、`zcode-ui-tree-regression.cjs` 通过；Chrome Lexical 回归遇到进程 SIGABRT，作为环境缺口保留。
- 真实工作区 `:app:compileDebugKotlin` 通过，包含保留的既有 WIP；`git diff --check` 通过。
- 本轮手机未被 ADB 检测到，没有新增真机安装、IME 验收或真实 Provider/ZCode 问答验收。
