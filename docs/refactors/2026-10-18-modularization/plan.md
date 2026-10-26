# 仓库模块化执行计划

最初基线：2026-10-18，`harmony-preview`，`cf0292929`。用户在继续执行时明确：**本轮只拆鸿蒙版，不包含 Android 或 Web 前端。**

恢复执行时工作区存在另轮清理 WIP，已保存差异与文件哈希到 `/tmp/amber-modularization-cf0292929/resume-20261018-140328/`；保留当前清理结果，不恢复已移除的模块、未接入功能或历史文件。

## 目标与范围

- 沿当前生产调用链整理职责，解决装配、运行状态、存储事务和页面编排集中；常规手写业务实现尽量不超过 1500 行，1000 行以上复核内聚性。
- 当前鸿蒙基线：24 个自维护生产目录文件超过 1000 行、9 个超过 1500 行，详见 `harmony-resume-baseline.json`。最初全仓 50/17 统计只保留历史参考，不作为本轮执行范围。
- 保留三 HAR、ArkUI V1/API 12、当前生产入口、存储格式、取消/审批/恢复语义。先在模块内部整理，不引入新 DI、通用控制器框架或 V2 迁移。Android/Gradle/Web 现有 WIP 保持原状，不修改、不构建、不安装。
- 本轮不改兄弟 `DeepRead` 仓库。保留 host 实际调用的 DeepRead 代码；独立应用构建必须记录真实源码根，不能以兄弟仓构建代替本仓验收。
- 不删除已标记的历史/未接入代码，不提交、推送或发布。修改与设备安装保留现有用户数据。

## 拆分质量约束

1. 一个状态、锁、队列、缓存和资源生命周期只有一个权威所有者。状态与行为一起迁移，不把整个宿主对象传入子模块。
2. 新抽出模块不得回读装配根；兼容门面仅供尚未迁移调用者。普通转发不是重构成果。
3. 保留真实原子性：小说正文/版本/ledger/checkout/job/receipt 一次提交；Memory 源提交队列唯一；通知与闹钟共享记录锁。
4. 公开入口、持久化 wire format、工具名称/结果、prompt 文本、模型选择与认证轮换保持；行为调整只处理证实问题并留证据。
5. 保持鸿蒙当前实际运行路径，不复制生成状态、滚动策略或 AST，不恢复已清理的未接入功能。
6. 1500 行是约束而不是切割算法；公开导出清单、内嵌第三方、数据与生成文件明确列例外。避免新文件承接同样多的无关职责。

## Phase 与完成门

### Phase 0：生产入口、基线与行为保护

- 固定文件清单、关键导出/存储格式和各平台实际构建入口；检查现有设备、SDK、签名、依赖可用性。
- 运行 Harmony 现有测试与基线构建。此前 Android/Web 检查只作历史记录，用户已将其排除，不再推进。
- 对 AppContainer 的真实 Cron 执行体补行为刻画，覆盖工具循环、待审批失败、取消与通知；不以另一路域执行器测试替代。
- 独立 subagent 审查计划与生产调用/测试覆盖；登记 UI 基线和可执行场景，确认后进入 Phase 1。

### Phase 1：Harmony 装配与 ConversationRun 依赖方向

- 提取 provider/auth、本地执行资源、能力装配、自动化和产品装配职责。ProviderRuntime 不接管 SSH/Python/Mosh 副作用，由 run preparation 显式组合。
- RunService、Preparation、ToolCatalog 使用明确中立契约与依赖；去掉对 AppContainer 的反向读取。保留唯一 session/run/queue/write ordering 所有者。
- AppContainer 收敛为装配与兼容门面，各子模块生产可达。
- 受影响行为测试、ArkTS lint、实际 App 构建/签名/安装运行；独立 subagent 复核调用链、后台生成和审批/队列状态；检查可见 UI 回归，修复确认问题。

### Phase 2：Harmony 聊天页与首页

- ChatPage 按会话展示、时间线分页/定位/滚动/IME、composer 附件草稿与预检、Smart UI 会话、产物/消息动作拆完整职责。
- 页面只保留路由、页面生命周期、主题和区域装配；后台生成继续归 RunService。目标 1000–1400 行，禁止巨大 host 接口。
- 页面存活的草稿、请求序号和订阅由页面持有的具体职责 session 维护；主题 ForEach 内组件不持有这些资源。使用当前 V1 的 `@Observed` / `@ObjectLink`，可见字段在各 session 第一层，数组整体替换；构造函数只初始化，从页面观察实例显式启动异步订阅。先用一个完整小职责在真实 App 验证观察更新及主题重挂载保留，再迁移时间线。
- 时间线的当前行解析、message.id 身份、唯一 ForEach、分页定位、跟底、IME 和产物位置一起迁移。保留 seed 闭包从当前状态重读，禁止以新的 @Prop 快照固定流式消息。
- HomeListPane 拆继续卡聚合、列表/计数/删除撤销、导出与内聚视图，目标 600–850 行。
- Home 的跨实例 cache 与 pendingDeleteId 保持唯一共享 owner；组件主题重建不提交删除。表层仅转发宿主可见性与路由，继续卡文字和点击均读取当前候选。
- 迁移原行为测试并保留断言；验流式提交身份、分页/分支、跟底/用户暂停、键盘、离页重连、草稿/Smart UI、队列和 root/两栏。
- 独立 subagent 审逻辑与 UI：明暗主题、窄/宽布局、弹层、图标文本对齐、边距/间距/大小及溢出；修复确认问题后进入下一阶段。

### Phase 3：小说存储、运行与工作区

- repository 顺序：codec/校验 → 唯一事务 owner → history/proposal → ghostwrite/polish repository。保持项目锁、原子目录交换、schema 迁移和损坏原件保留。
- creation 拆普通运行（包含工具延续/恢复/checkpoint）、持久任务驱动、连续性修复；复用现有 runners，Map 只做进程内去重。
- workspace 按讨论/稿件/素材/写作控制/任务会话迁移状态与行为；目标 900–1400 行。
- 验原子提交故障恢复、并发实例、CAS/claim/receipt 重放、普通恢复/审批/分支/草稿/后台作业；完成 lint/build/安装与明暗主题 UI 场景。
- 独立 subagent 审事务闭环、跨 UIAbility/WorkAbility 调用及 UI 细节，修复后继续。

### Phase 4：鸿蒙配置页与编辑会话

- ProviderDetail 拆草稿映射/校验/保存、认证会话、模型目录、脱敏导出；pending-provider 交接移出页面模块，保留现有认证/模型精确选择与迟到回调失效约束。
- SettingDeepRead 仅在本仓 host/reference 范围按完整载入/保存流程拆；区分 host 即时保存与独立产品 staged drafts，不修改兄弟仓库。
- ChatSearchSettings 将主设置/列表与来源编辑会话分开，保持17类来源的类型转换、identity/enable/selected-index及保存失败草稿语义。
- 独立 subagent 审调用链、编辑状态归属和 UI；实际检查表单对齐、输入区高度、边距、键盘、弹层与明暗主题，修复证实问题。

### Phase 5：鸿蒙能力、平台存储与公开导出

- Skill 分离元数据、库/缓存、包导入、运行提示词、工具，消除私有依赖穿透；PromptConfig 分离模型/目录、codec、原子仓库、工具。
- Artifact 按 HTTP、archive、Office、image 整理，保持工具协议与路径/解包限制。
- DeviceSchedule 按 calendar/app reminder/agent reminder/permissions 整理，通知和闹钟仍共享记录锁；Memory 仅整理当前仍维护的源事务、抽取/优化与派生文档提交，保留唯一队列及 sourceSaved 语义，不恢复已清理的 dream/import-export。
- AgentCron 的表达式/时区计算和工具输入输出可与唯一 manager 分离，任务锁、持久化和运行调度继续同属 manager；WebMount 设置只提取具有独立表单/连接状态/订阅生命周期的通用 OAuth 区域，保留当前站点、HAR、Cookie 与飞书入口。
- section_writer_tools 复核 payload/visuals/fallback 与 writer 状态，保持唯一 update/readiness owner；Council manager 复核转录归档/报告格式与席位运行的职责，不拆散取消和审批。
- PageTemplate、聚焦协议适配器及已内聚的1000–1500行文件逐项记录保留理由，不制造碎片。chat/index 按能力组织内部导出并保留 HAR 兼容入口；以实际 HAR 编译验证公开面，合理清单例外明确登记。
- 独立 subagent 审逻辑、生产接线与相关 UI，运行受影响验证，修复确认问题。

### Phase 6：整体验收与交付

- 重算鸿蒙文件规模，对 9 个超 1500 行基线文件逐一对照，列出职责结果与有依据的例外；全部24个超千行文件均有拆分或保留判断。
- 完整 Harmony Node gate、域类型检查、修改文件 ArkTS lint、实际 App 构建、签名、保留数据安装/启动、相关明暗主题/生命周期 UI 验收。
- 独立 subagent 做最终逻辑/调用链/UI 检查；修复确认问题，重跑受影响检查。只做一次有限可维护性审查，不新增无依据的防御、兜底或框架。
- 交付各 Phase 状态、文件规模前后、实际验证与未验证门。测试/构建/签名/安装/启动/provider/真机验收分别陈述，不虚报产品完整。

## 执行与审查策略

- 按 Phase 顺序推进；当前阶段内可将不重叠文件分给实现者并行处理，上一阶段未验收不修改下一阶段。
- 每个 Phase 留下范围、实现、行为/UI 证据、独立 review 发现与修复、剩余门；原始大日志与截图放任务临时证据目录，文档只引用必要结果。
- review 指向具体可达调用、可观察影响、状态/取消/提交约束与截图，不凭静态告警或样式喜好扩大任务。
- 环境阻碍先查明和处理能自行解决的工具链问题；外部账号/真实 provider 不可用的门如实记未验证，持续完成其余已授权工作。

## 进度

| Phase | 状态 | 审查与验收记录 |
|---|---|---|
| 0 | 基线建立与独立 review 完成，端侧缺口已登记 | 见 phase-0.md；两个 fixture 同步修复与独立 review 通过；初始签名/设备缺口由Phase1–6补齐，平台/provider未测门终稿单列；Android/Web已排除 |
| 1 | 阶段职责验收通过；平台/真实provider边界继续登记 | 见 phase-1.md：4588/4588、独立review、编译打包、兼容签名覆盖安装与新UI；一次系统Web冷启动超时空闲重试成功 |
| 2 | 阶段职责、独立代码与已测 UI 验收通过；未测门单列 | 见 phase-2.md：Chat 5471→1200、Home 1593→877；4634/4634、编译打包、AGC覆盖安装、明暗原生交互与旧会话；软键盘/宽屏/provider不外推 |
| 3 | 阶段职责、独立代码与修复包已测 UI 验收通过；未测门单列 | 见 phase-3.md：repository3498→26/最大内部633、creation2362→1121、workspace4641→1063；4651完整门+最终增量、SDK、签名保数据安装和明暗UI；修复Prop副本崩溃后同数据复测通过 |
| 4 | 阶段职责、独立代码与已测 UI 限定验收通过 | 见 phase-4.md：4665/4665、SDK编译打包、保数据安装；明暗配置/模型/搜索路径通过，保留一次输入冻结与归因未定记录 |
| 5 | 阶段职责、独立代码与已测UI验收通过 | 见 phase-5.md：七包独立review、4699/4699、SDK/签名保数据安装、浅暗OAuth与临时草稿生命周期，未测门单列 |
| 6 | 已完成；实际验收限制保留 | 见 phase-6.md：11>1000/0>1500、977冻结源、4699完整门、SDK/签名安装与跨页UI、独立最终限定GO；未提交推送 |
