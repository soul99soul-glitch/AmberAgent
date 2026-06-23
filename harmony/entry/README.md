# Deep Read 鸿蒙 Entry 模块(Phase 6-7 底线交付)

Stage 模型 UIAbility + ArkUI 页面 + DI 容器 + 7 个 `@kit` 平台实现。

## 状态:**底线交付(代码就位,未编译)**

工具链门槛已验证:**DevEco Studio 未安装 / API 24 SDK 未安装 / `hvigorw` 不在 PATH**。
本模块代码全部写好,但 `.hap` 无法编译验证 — 所有需 ArkTS 编译验证的点标 `TODO(toolchain/ArkTS)`。
装好 DevEco + API 24 后,逐个 TODO 校正即可编出第一个 HAP。

## 结构

```
src/main/
├── module.json5              # Stage 模型声明(abilities + 权限 INTERNET/NOTIFICATION)
├── ets/
│   ├── entryability/
│   │   └── EntryAbility.ets  # UIAbility:onCreate/onNewWant 收 URL→deriveTopicId(§7.2)
│   ├── pages/
│   │   ├── Index.ets              # DeepReadHomePage(输入框+历史列表,§7.1)
│   │   ├── DeepReadArticlePage.ets # 进度+文章渲染+取消/重试(§7.1/§7.4)
│   │   └── HistoryPage.ets        # 7d 历史(§7.1)
│   ├── components/
│   │   ├── ArticleRenderer.ets    # 杂志风文章渲染 hero/timeline/analysis/diagram(§7.3)
│   │   └── StageProgressCard.ets  # 单 stage 进度卡(§7.4)
│   ├── di/
│   │   └── AppContainer.ets       # 依赖注入:组装 deepread 逻辑 + @kit 平台实现
│   └── platform_impl/
│       ├── RcpHttpClient.ets          # HttpClient → @kit.RemoteCommunicationKit(§8.1)
│       ├── RdbRepository.ets          # Database/Repository → @kit.ArkData relationalStore(§3.4/§3.6)
│       ├── PreferencesStorage.ets     # Storage → @kit.ArkData preferences(§3.8)
│       ├── OpenAiCompatibleAiClient.ets # AiClient → OpenAI 兼容 HTTP+SSE(§4.8/§8.5)
│       ├── SearchRegistry.ets         # SearchProviderRegistry → Tavily + fallback(§8.6)
│       └── NotificationNotifier.ets   # Notifier → @kit.NotificationKit(§6.7/§8.8)
└── resources/
    ├── base/element/{string,color}.json
    └── base/profile/main_pages.json   # 3 页路由
```

## 依赖

`@amber/deepread-domain`(file:../deepread)— 纯逻辑层(domain/agent/research),
通过 `src/main/ets/index.ts` barrel 导出 `run/runSection/createScheduler/deriveTopicId` 等。
deepread 逻辑层已 440 tests pass。

## 已实现 vs 待工具链验证

| 模块 | 逻辑 | 待 DevEco+API24 验证 |
|---|---|---|
| EntryAbility | ✅ share/deep-link 路由、后台/前台 setBackgrounded | Want.parameters 类型、router params |
| 3 ArkUI 页面 | ✅ 输入/进度/历史结构 | ArkUI 装饰器语法校验、ForEach 泛型 |
| ArticleRenderer | ✅ 7 个 block 结构 | @Builder 装饰器、diagram Canvas 绘制 |
| AppContainer | ✅ DI 装配 | — |
| RcpHttpClient | ✅ 契约映射 | RCP Session/fetch 签名、TextDecoder |
| RdbRepository | ✅ get/save/clear/observe | relationalStore API、ability context 注入 |
| PreferencesStorage | ✅ getString/setString | preferences API |
| OpenAiAiClient | ✅ 单轮 generateText | **SSE 流式 + tool loop + budget + retry 组装**(标 TODO) |
| SearchRegistry | ✅ Tavily+fallback | enabled() 同步契约修正 |
| NotificationNotifier | ✅ running/completed/failed/cancel | notificationManager API、Want 跳转 |

## 编译验证后的下一步

1. 装好 DevEco + API 24,配置 `DEVECO_SDK_HOME`
2. `hvigorw assembleHap` — 逐个 TODO 校正 ArkTS 编译错误(预期:类型签名、@kit API 命名)
3. 第一个 `.hap` 装模拟器(解决 API 24 vs 26 版本不匹配)
4. OpenAiAiClient 补全 SSE + tool loop agent 组装(纯逻辑已在 deepread)
5. 后台/前台续跑(§6.5):EntryAbility.onBackground 已写 setBackgrounded,scheduler 已有逻辑
