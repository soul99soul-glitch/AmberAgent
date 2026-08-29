# Harmony 版功能完善计划(2026-08-29 盘点驱动)

来源:同日全仓"壳子/半成品"审计(交叉验证 2026-08-25 parity backlog + 近期提交 + stub 标记扫描 + 实现层核查)。
范围:A 档纯壳 3 项 / B 档半成品 12 项 / C 档死开关 1 项。已删除的 TTS、Office Pro 不在范围内。

总原则(全程生效):
- 验证回路:lint(改动文件)→ build → sign → 真机/模拟器截图目测,一步不少
- chat/deepread 域改动必须配套单测(现有 2012 + 631 全绿是底线)
- 诚实 UI:未实装就禁用+标注,禁止假反馈(F1 治理原则)
- 新 API 先 `devecocli docs search` 核实,高于 API 12 的一律 `canIUse()` 包裹
- 状态装饰器全 V1;禁 `export let` 全局状态

---

## Phase 0 — 卫生速效(0.5 天)

| # | 事项 | 落点 |
|---|---|---|
| 0.1 | Terminal 死开关诚实化:本地工具页 Terminal tools 选项禁用 + 「未实装」标注(对齐 Cron 页做法),待 Phase 5.3 落地后解禁 | `ChatAssistantLocalToolsPage.ets` |
| 0.2 | 过时注释清理:`AppContainer.ets` 头部 3-5 行(mock 描述已失实)、`ChatPage.ets:2213`(任务模型未移植——D-087 已接线) | 两文件 |
| 0.3 | 本计划登记进 backlog,完结项销账 | parity backlog 文档 |

验收:lint 0 error / 构建 / 真机目测 Terminal 选项呈禁用态。

## Phase 1 — 通知接真(1-2 天,用户感知最大)

1. **模块冲突 spike(先行)**:`NotificationNotifier.ets:3` 登记的 `@ohos.notification` vs `notificationManager` 冲突,用 `devecocli docs search notificationManager` 确认 API 26 SDK 正确 import 形态,最小 spike 验证 publish/cancel/请求授权链路。
2. **NotificationNotifier 接真**:running/completed/failed 三类 + cancelRunning,替换 hilog 实现;EntryAbility 启动时 `requestEnableNotification`。
3. **生成完成通知并轨**:ChatPage 的 enableNotificationOnMessageGeneration 走同一 notifier。

风险:冲突若为 SDK bug,降级 `@kit.NotificationKit` 入口;再不行保留 hilog 并在设置页标注。
验收:真机触发 DeepRead,通知栏真实出现、可点击取消、开关可控。

## Phase 2 — 交互补全(2-4 天)

| # | 事项 | 技术路线 | 风险 |
|---|---|---|---|
| 2.1 | 系统权限页发起申请 | 逐项 `docs search` 核实可申请性(通讯录/日历鸿蒙 Kit 化后未必有独立权限);可申请项 `requestPermissionsFromUser`,不可申请项维持"不支持"徽标 | 权限模型差异大,预期部分项只能保持展示 |
| 2.2 | 分享升级系统面板 | `@kit.ShareKit` systemShare 或 Want 分享;失败降级现行剪贴板 | 低 |
| 2.3 | 提示词导入/导出 | DocumentViewPicker 导出 JSON + 导入解析合并,对齐 Android ExportDialog | 低 |
| 2.4 | 文档类附件 picker | DocumentSelectOptions 多选 → 接 DocumentSupport/发送链(图片 picker 已有先例可抄) | 中:附件解析链 |
| 2.5 | 模型参数页补全 | overwrite 编辑面对齐 `SettingProviderModelSettingsForm.kt` | 低 |

验收:每项真机操作走通;导入的提示词出现在列表;文档附件能进消息并可见。

## Phase 3 — 数据与搜索(3-5 天)

1. **消息全文搜索**(最大项):chat 域新增 `searchMessages`(FTS 优先——relationalStore 底层 SQLite,FTS5 可用性需 spike 验证;不可用则 LIKE + JS 分词降级,中文需 ngram 处理);ChatRdbRepository 加查询入口;UI 入口:抽屉搜索升级为会话+消息双结果。
2. **多选导出**:会话多选 → Markdown/JSON 经文件 picker 写出。
3. **AssistantMemoryPage 并轨**:去掉本地占位列表,复用核心记忆 MemoryStore CRUD(或重定向到设置页,二选一)。

验收:chat 单测新增搜索用例(中文命中);导出文件在文件管理器可打开。

## Phase 4 — 呈现层补全(2-4 天)

| # | 事项 | 说明 |
|---|---|---|
| 4.1 | SubAgent/议会结果完整卡片 | 对齐 Android SubAgentBlock/CouncilBlock,替换 ChatPage:513 登记的占位卡 |
| 4.2 | webview 子系统 | ArkWeb `Web` 组件(API 12 基线内,权限/沙箱先 docs 核实);先做通用 web 预览页,再接 SandboxActivitySheet |
| 4.3 | 助手扩展页补 tab | quickMessages/skills 两 tab 数据源已存在(全局页真实),只补助手级绑定 |
| 4.4 | 视觉残项 | backlog V 系列剩余(按压态/TypeScale/spacing 收敛)小步并行 |

验收:截图目测对齐 Android 基准;web 预览真机可滚动。

## Phase 5 — 基建硬活(需立项决策,预估另计)

| # | 事项 | 前置与风险 |
|---|---|---|
| 5.1 | **Cron 执行器** | WorkSchedulerExtensionAbility + 无头聊天运行(chat 域 runGeneration 去 UI 化);鸿蒙后台任务配额受应用市场策略约束,需真机 24h 长稳;这是 B/C 档中唯一可能被平台政策卡住的项 |
| 5.2 | **WebMount 实装** | 依赖 4.2 webview;抓取/挂载链对齐 Android;解锁后替换 Alpha 占位页 |
| 5.3 | **终端沙箱(HiSH 路线)** | 已定"可行、保留"。分三步:①调研 spike——libqemu-system-aarch64.so(MIT)集成进 native 模块 + rootfs 打包分发(首包 vs 按需下载)+ JIT 限制适配(手机无 JIT,性能预算评估);②MVP——terminal_execute 单命令跑通 busybox;③整合——会话保持 + workspace 路径映射 + 沙盒页状态真值。完成后解禁 0.1 的开关 |

## 里程碑与总量

- **M1(第 1 周)**:Phase 0+1 —— 通知真实、死开关清零
- **M2(第 2-3 周)**:Phase 2+3 —— 交互与数据面追平 Android 主干
- **M3(第 3-4 周)**:Phase 4 —— 呈现层补全
- **M4(另计)**:Phase 5 按立项决策逐个打穿

单人节奏估算:M1-M3 约 3-5 周;每阶段结束跑一次全仓 lint(基线 0 error)+ 全量单测 + 真机回归截图。

## 待用户决策

1. Phase 5 三项是否立项(cron 受平台配额、WebMount 依赖 webview、终端工程量最大)
2. 消息搜索降级容忍度:FTS 不可用时 LIKE 降级是否可接受
3. 排期偏好:按本计划顺序(M1→M3),还是先插队某项

---

## 执行记录(2026-08-29 同日)

**M1-M3 已全部落地**(P0 卫生 / P1 通知接真 / P2 交互补全 / P3 数据与搜索 / P4 呈现层),
提交:1e6598833(P0+P1)、be16ccf15(P2)、d10c6b8c9(P3)、4c3b1a3c4(P4)。
通知 e2e 真机验证通过(通知栏真实出现「正在深度阅读」)。

**审计销账(信息滞后,已实现无需重做)**:附件 picker(attach chip 三按钮)、
Markdown 链接点击(system browsable intent)、SubAgent/Council 完整卡片(436 行组件 +
RunSheet 流式详情)。

**P2.1 权限 ACL 实测**:READ_CALENDAR 放行;READ_CONTACTS 被 debug profile ACL 拒
(install 9568289),需 AGC 后台把该权限加入调试 Profile ACL 后解注释 module.json5 对应条目。

**P3.1 消息搜索**:FTS 未用,LIKE 粗筛 + 纯文本精滤(域函数 messageNodeRowPlainText/
snippetAround/searchMessageHitsIn,RDB + 内存双实现);关键词含引号/换行存在 JSON 转义
假阴性(登记,可接受)。

## Phase 5 技术方案定稿(2026-08-29,待立项排期)

### 5.1 Cron 执行器
- **API 已核实**:`WorkSchedulerExtensionAbility`(API 9+,基线内)+ workScheduler 延迟任务;
  系统对回调资源严格管控(执行窗口受限,约分钟级)。
- **实现路径**:module.json5 注册 extensionAbilities(WorkSchedulerExtensionAbility 入口,
  type: 'workScheduler')+ AgentCronStore 的 schedulerPort 由 startWork/stopWork 落真实调度;
  回调 onWorkStart 里执行任务。
- **关键工程**:无头聊天生成——ChatPage 5300 行承载全部生成编排,需抽离 headless 生成管线
  (chat 域纯函数化 runGeneration:provider 解析/流式组装/落库,无 UI 依赖)。这是最大单项,
  预计 3-5 天 + 真机 24h 长稳(生成 30s-2min 是否落在系统回调窗口内需实测;不满足则改
  reminderAgentManager 代理提醒 + 用户点开应用执行的降级形态)。
- **风险**:应用市场上架的延迟任务配额策略;执行窗口可能不够长生成。

### 5.2 WebMount
- **依赖**:通用 web 预览页(ArkWeb `Web` 组件,API 8+,INTERNET 权限已声明)+ Web 组件
  javaScriptProxy 双向通道(提取 DOM/执行注入)。
- **实现路径**:新建 WebMountSession 单例组件(屏外/隐藏 Web 实例池)→ tool 实装
  (webmount 打开/点击/输入/滚动/截图,webmount_eval 逐次确认门已有)→ ChatPage 工具注册表
  解除空转门 + 本地工具页去掉 unavailable 标记 → SandboxActivitySheet 接 web 预览。
- **预估**:4-6 天(含截图能力 pixelmap 导出与站点兼容性调试)。

### 5.3 终端沙箱(HiSH 路线)
- **Spike 结论(2026-08-29)**:HiSH(MIT)以 `libqemu-system-aarch64.so` 承载完整 arm64
  Linux 内核 + Alpine qcow2 rootfs,手机形态支持,嵌入仅要求"关于"页标注来源;
  JIT 仅 Pad/2in1,手机纯解释执行(性能预算需 MVP 实测)。
- **实现路径**:①native 模块集成 libqemu(NDK 编译链 + hap 内打包 or 首启下载 rootfs——
  上架政策需确认按需下载二进制的合规性)②terminal_execute MVP(busybox 单命令,stdin/stdout
  经 IPC 桥接 native 子进程)③会话保持 + workspace 路径映射(9p/共享目录)+ 沙盒页状态真值
  ④解禁本地工具页 Terminal 开关。
- **预估**:1-2 周级;建议先做 3 天 MVP spike(busybox ls 跑通)再决定全量投入。
