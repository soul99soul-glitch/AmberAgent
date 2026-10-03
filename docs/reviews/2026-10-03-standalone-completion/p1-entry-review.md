# P1 独立产品入口与公共配置审查

审查范围：ProductIdentity、EntryAbility、BoardPage、NovelProjectsPage、两独立设置页、DeepReadCompose／Article／Sources，以及实际可达的 Provider、模型、模板设置链。审查只读；本文件由审查 agent 独占。当前工作树包含其他 agent 的工作，未重置或覆盖任何源码。

## Findings

| 编号 | 严重度 | 已确认触发与影响 | 当前源码定位 | 最小修复 |
|---|---|---|---|---|
| P1-E01 | P2 | 模型父服务商鉴权可用，但单模型 `providerOverwrite` 不可用时，小说职责模型选择器仍宣称可用；实际 fixed runtime 使用 override 鉴权后拒绝启动。菜单与执行事实源不一致。 | `AppContainer.ets` `loadNovelModelOptions` 原 1587–1595；`makeProviderChoice` 原 1350–1354；`resolveConfiguredProviderChatModel` 原 1528–1538 | 保留小说 fixed 路径已有父鉴权门，逐模型计算 `hasUsableAuth(provider) && makeProviderChoice(provider, model) !== null`。不放开执行鉴权、不增加 fallback。 |
| P1-E02 | P2 | 编辑尚未保存的润色偏好／上下文开关，进入同页服务商、搜索或外观子页再返回，`onPageShow → reload` 将持久值覆盖编辑草稿。 | `NovelStandaloneSettingsPage.ets` 原 40–43、58–76 | 初次完整加载；从子页返回仅刷新模型列表、标签及职责默认值。保留编辑项，不引入通用 dirty 状态框架。 |
| P1-E03 | P2（产品边界） | 小说独立应用 → 设置 → 模型服务商 → “深度阅读配置”可进入另一产品设置。服务商底部还展示聊天标题／建议／压缩等无关任务。既有共享设置菜单未收口。 | `ChatProviderSettingsPage.ets` 原 311–322、366 | 按当前 `productKind` 显示对应独立设置入口；宿主保持已有设置链。 |
| P1-E04 | P3（UI） | 小说设置底部“保存润色偏好”贴屏幕左边，其他动作及正文左边距为 20 vp。 | `NovelStandaloneSettingsPage.ets` 原 207–209 | 加与邻近动作一致的左右 20 vp padding。 |
| P1-E05 | P3（UI） | 创建深读和来源资料页直接给自定义 Icon 设 44×44 热区，Icon 内部是未居中的 SymbolGlyph，箭头位于热区上侧，视觉上高于标题。 | `DeepReadComposePage.ets` 原 98–99；`DeepReadSourcesPage.ets` 原 54；`Icon.ets` 201–204 | 在这两页以 44×44 的 Row 居中包裹 Icon；保持本地范围，不修改全局 Icon。 |

UI 证据来自上一轮截图，证明上轮已出现且本次审查时相应源码仍存在：

- `docs/reviews/2026-10-03-harmony-standalone/device/novel-final-settings-lower.jpeg`：保存动作 x=0，邻近搜索／外观／上下文文字均有左边距。
- `docs/reviews/2026-10-03-harmony-standalone/device/deepread-compose-keyboard.jpeg`、`deepread-compose-light.jpeg`：箭头比“创建深读”标题高；源码 Icon 内部无居中容器。

没有凭文本搜索推定其他 UI 错位；新包截图验证应由主 agent 在修复后执行。

## 已查明的闭环

- `EntryAbility.onCreate` 先把本应用 AbilityContext、bundle→productKind 写入 AppStorage，再 load 产品根页。小说及深读根页 `onPageShow` 均调用 `markWantRoutingReady`；冷启动 pending Want 在页面 Router 可用后消费。
- `getAppBundleName()` 读取当前上下文 applicationInfo；通知及 WorkAbility 共用当前 bundle。没有发现独立产品仍将用户导回宿主的硬编码链。
- PreferencesStorage 的 `getPreferences(ctx, 'deep_read_settings')` 和 RdbRepository 的 `getRdbStore(context, ...)` 使用当前应用上下文。相同文件名不会跨独立 bundle 共用沙盒。
- 深读创建页先将标题、完整粘贴文本、链接、全部文件来源和状态 `upsert`，成功后 `replaceUrl` 到文章页。文章页读取同一 repository，首用确认后拿 scheduler；每轮 `createRunManager` 重新解析最新 `deepread_task_model`、鉴权和工具能力。
- scheduler 初始化及历史查看无需可用模型；缺模型失败发生在实际 run 创建依赖处，保留输入。文章缓存完成时只展示，部分进度需要显式继续，正在运行时只订阅；本审查未发现需要增加重试或兜底层的断链。
- 深读模型页返回会刷新实际 Provider 模型配置；固定选择使用稳定 provider/model id。模板工作台 AI 生成调用 `getDeepReadTextRuntime`，与阅读模型共用选择事实；HTML 验证后显式保存，模板选择和文章渲染相接。
- NovelProjectsPage 从子页返回重读项目列表；BoardPage 隐藏时停止热榜请求并失效迟到回调，返回时重新读取配置。来源详情订阅在退出时注销。

## 核验边界

针对性运行现有 `harmony/entry/src/test/product_identity.test.cjs`：7/7 通过，覆盖三产品根页、通知 bundle、WorkAbility 调度 bundle、未初始化上下文不会误投宿主。使用项目已有 tsx loader 和 chat tsconfig，没有新增测试或源码改动。

本次是 P1 只读代码／既有截图审查，不替代新包构建、签名、实际 UI 及真实模型验收。未发现 P0/P1 级入口或持久化阻断。上述 5 项在最小修复、针对性验证和新包截图复查通过前不应标为 closed。

## 修复后源码复查

- P1-E01：已改为逐模型 `hasUsableAuth(provider) && makeProviderChoice(provider, model) !== null`，与小说 fixed resolver 的父鉴权门和有效 override 门一致。
- P1-E02：已增加 `reload(readPreferences)`，完成首次读取后返回子页不覆盖润色文本与四项上下文草稿。组合路径曾发现：Provider 新入口可打开第二个小说设置页并更改职责默认模型；第一页面旧 defaults 可能覆盖其他角色。主 agent 已补齐：每次返回读取并赋值 defaults/researchEnabled，条件只保留 context/preference 草稿字段。当前源码复查通过。
- P1-E03：Provider ConfigurationLinks 已按产品显示小说职责模型或阅读模型入口，宿主保持原有配置菜单。
- P1-E04：保存润色动作已补左右 20 vp padding，与同页按钮一致。
- P1-E05：两新页均已改为局部 44×44 居中 Row 包 Icon，全局 Icon 未修改。

新包 UI 截图仍由主 agent 构建安装后提供；本段只确认源码修复，没有用旧截图冒充新版本验收。

最终针对性复跑 `product_identity.test.cjs` + `standalone_entry_lifecycle.test.cjs`：9/9 通过。后者执行提取出的实际 reload/onPageShow/模型选择方法，验证返回刷新职责默认、模型标签和选项，同时保留未保存润色文本与上下文；鉴权覆盖父凭据与模型 override 的组合。

## 新包截图复查

主 agent 已构建、签名、覆盖安装并冷启动 P1 包，审查 agent 使用 `view_image` 逐图目测以下新截图：

- `device/p1/novel-settings-lower.jpeg`：保存润色偏好与外观、搜索服务、上下文行文字均沿同一 20 vp 左边距，不再贴屏幕左边。深色背景上的输入、开关及操作文字可见；正文输入框和开关右边界整齐。
- `device/p1/novel-preference-after-return.jpeg`：输入框保留 `P1 unsaved author preference`，与主 agent 提供的“未保存输入→外观→返回”操作证据一致。
- `device/p1/deepread-compose-alignment.jpeg`：返回箭头与创建深读标题垂直中心对齐，页面输入框、添加文件及生成动作左右一致，没有发现横向溢出或边距错位。
- `device/p1/deepread-sources-alignment.jpeg`：返回箭头与来源资料标题对齐，资料卡文字及查看正文动作位于卡内，卡片左右留白与页面一致。

最终状态：P1-E01–E05 全部 closed。入口、存储、确认门、资料提交、每轮模型捕获链无剩余 blocker；新包截图确认本次两个 UI 修复生效。截图只覆盖上述实际页面状态，不据此扩大宣称所有页面、屏幕尺寸或真实模型行为已验收。
