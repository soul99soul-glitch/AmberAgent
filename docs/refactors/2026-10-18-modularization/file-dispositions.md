# 鸿蒙超千行文件的最终职责判断

统计与路径见 final-source-inventory.json。基线24份自维护生产TS/ArkTS全部有处理判断；最终11份超过1000行、0份超过1500行。最大1407行为纯公开清单，最大业务实现1200行。数字不作为拆分质量的替代证据。

| 基线文件 | 原行数 | 最终原文件行数 | 阶段与处理理由 |
| --- | ---: | ---: | --- |
| [ChatPage](../../../harmony/entry/src/main/ets/pages/ChatPage.ets) | 5471 | 1200 | Phase2：9个页面持有会话owner和内聚视图；保留路由/生命周期/区域装配，唯一canonical消息与timeline/IME策略 |
| [NovelWorkspacePage](../../../harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets) | 4641 | 1063 | Phase3：6会话/5视图，运行状态归domain；动作按ID回读canonical消息，Prop副本不作domain输入 |
| [repository](../../../harmony/deepread/src/main/ets/novel/repository.ts) | 3498 | 26 | Phase3：兼容工厂与12个格式/树/事务/领域仓库；唯一事务owner，整树原子提交/CAS/receipt重放不分散 |
| [creation](../../../harmony/deepread/src/main/ets/novel/creation.ts) | 2362 | 1121 | Phase3：普通运行、持久任务驱动、连续性修复、工具提案分别迁移；公开门面保持 |
| [AppContainer](../../../harmony/entry/src/main/ets/di/AppContainer.ets) | 1990 | 387 | Phase1：具体装配/产品/资源边界，保留组合入口与兼容门面；运行模块不回读root |
| [ChatProviderDetailPage](../../../harmony/entry/src/main/ets/pages/ChatProviderDetailPage.ets) | 1638 | 187 | Phase4：编辑保存、认证、模型目录、脱敏导出和pending单槽各自完整边界 |
| [HomeListPane](../../../harmony/entry/src/main/ets/components/HomeListPane.ets) | 1593 | 877 | Phase2：集合/计数/删除撤销、继续卡、导出和行视图；共享cache/pendingDelete唯一 |
| [index](../../../harmony/chat/src/main/ets/index.ts) | 1553 | 1407 | Phase5：HAR公开清单；Memory/SmartUI按能力组织，2262公开符号和type/value身份不变，无业务状态 |
| [SettingDeepReadPage](../../../harmony/entry/src/main/ets/pages/SettingDeepReadPage.ets) | 1506 | 304 | Phase4：独立草稿/提交与host即时设置及视图分开；保留两个产品不同保存合同 |
| [agent_prompt_config](../../../harmony/chat/src/main/ets/chat/agent_prompt_config.ts) | 1231 | 46 | Phase5：角色definitions、专属codec、原子repository、tool；六真实消费者直接依赖角色定义，原路径兼容 |
| [device_schedule_tools](../../../harmony/chat/src/main/ets/chat/device_schedule_tools.ts) | 1199 | 49 | Phase5：中立契约/codec及calendar/reminder/alert完整工具组；14工具顺序和统一审批协议保持 |
| [skills](../../../harmony/chat/src/main/ets/chat/skills.ts) | 1191 | 22 | Phase5：metadata/package事务/library唯一cache/mobile prompt/tools；消除private deps穿透，窄业务读取能力 |
| [SettingExperimentalWebMountPage](../../../harmony/entry/src/main/ets/pages/SettingExperimentalWebMountPage.ets) | 1178 | 876 | Phase5：提取OAuth observed session/表单/订阅生命周期，其他站点/HAR/Cookie/飞书原方法保持 |
| [context_compact](../../../harmony/chat/src/main/ets/chat/context_compact.ts) | 1117 | 1117 | Phase5保留：同一消息压缩契约的纯规划/估算/payload/注入；外层已有执行与持久化边界 |
| [ConversationRunService](../../../harmony/entry/src/main/ets/platform_impl/ConversationRunService.ets) | 1108 | 1109 | Phase1保留：唯一run/session/queue/审批/写入顺序owner；已去除root反向读取，不拆散运行事实 |
| [agent_cron](../../../harmony/chat/src/main/ets/chat/agent_cron.ts) | 1101 | 760 | Phase5：纯表达式/时区计算与工具协议迁出；manager保留唯一任务锁、持久化、claim和scheduler |
| [DeviceSchedulePort](../../../harmony/entry/src/main/ets/platform_impl/DeviceSchedulePort.ets) | 1096 | 105 | Phase5：权限、Calendar adapter和notification/alarm adapter；后两种提醒保留跨全部port实例共享记录锁 |
| [BoardPage](../../../harmony/entry/src/main/ets/pages/BoardPage.ets) | 1084 | 1084 | Phase5保留：单一发现会话加载/缓存/投影/导航；卡片、composer、传感器和聚合算法已有边界 |
| [workspace_artifacts](../../../harmony/chat/src/main/ets/chat/workspace_artifacts.ts) | 1082 | 56 | Phase5：真实复用ZIP writer、完整archive、HTTP、document、image；唯一tracker，原工具及限额/路径语义 |
| [DeepReadArticlePage](../../../harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets) | 1064 | 1064 | Phase5保留：单篇output/生成观察/恢复共用生命周期；阅读器/工具栏/来源/导出已独立 |
| [NovelProjectsPage](../../../harmony/entry/src/main/ets/pages/NovelProjectsPage.ets) | 1024 | 1024 | Phase5保留：项目库操作共用互斥和刷新；持久化、校验与恢复均在服务层 |
| [PageTemplate](../../../harmony/entry/src/main/ets/components/PageTemplate.ets) | 1022 | 1022 | Phase5保留：已命名的公共组件集合，无集中产品状态/异步流程，按原职责使用 |
| [section_writer_tools](../../../harmony/deepread/src/main/ets/agent/section_writer_tools.ts) | 1014 | 532 | Phase5：纯codec/fallback/visuals分离；output/update/双计数保留同一factory闭包，finish两路径语义保持 |
| [NovelChapterReaderPage](../../../harmony/entry/src/main/ets/pages/NovelChapterReaderPage.ets) | 1012 | 1012 | Phase5保留：当前章读取/编辑/版本事务共用project/chapter/CAS/busy；算法和确认预览已有边界 |

基线外的ChatSearchSettingsPage也已按主设置/17型编辑会话完整拆分。MemoryStore保留唯一源写队列、ID分配、预算与sourceSaved错误合同；Council保留运行/席位signal/审批协调，seat source-store为持久屏障、JSONL仅归档。不恢复已清理Dream/import-export。

保留理由来自实际入口、状态归属及变化原因；详见各phase和任务证据phase5-{retained,platform,capability}-boundaries.md。最终独立review、SDK/数据/UI证据及未测门见phase-6.md。
