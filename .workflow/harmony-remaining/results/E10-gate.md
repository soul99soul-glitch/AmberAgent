# E10 gate: Google builtin tools and complete source display

2026-09-28，基点 `caa5b9d50`。源实现、独立 A/B 和最终实际 Release/本地验签已收口；回执见下。设备 C 保持 pending，没有新增模拟器恢复、矩阵或外网服务商调用。

## 实际请求与来源闭环

- 原 ProviderModel.tools / codec / toChatModel 传递搜索、网址上下文。Google 有 search/url_context 时明确内置模式，映射实际 googleSearch/urlContext，不注册本地/MCP functionDeclarations；无此两项沿原函数声明。仅 image_generation 不写空 tools、不抑制函数；原 customBody 最后覆盖保持。
- 模型设置 URL 草稿读取/保存与 search/image 同源；effective providerOverwrite ?? container 决定协议说明。不支持的已存选择保留。提前说明内置模式的函数边界，不虚构 Gemini 混用或 OpenAI URL-context 能力。
- 同一 candidate 来源 helper 处理非流及 SSE；metadata-only tail 发空 assistant parts 与真实 annotations，保留实际 retrieval status 与原 searchEntryPoint.renderedContent。原 accumulator append/distinct 不变，不加假正文。
- DeepRead 唯一注解定义新增两 named DTO，Chat facade/index 同源导出。serialize/parse 显式处理 url_context / google_search_suggestions，不落为 generation_interrupted；搜索确认其余真实拷贝点均保留完整 annotations，无另一影子 schema。
- Common 在原 footer 消费网址状态和原 Search Suggestions；成功/失败/付费限制/UNSAFE/未知分别如实显示。独立小 Web 组件以 API12 FIT_CONTENT/SYNC_RENDER、controllerAttached/loadData 路径显示原 HTML/CSS，单导航回调开实际外链，无 JS bridge。长来源换行、≥44vp 操作、消息宽度/主题/安全区按源码审查；未将其称为设备视觉验收。

## 必要检查与精确修复

- 唯一端到端 fixture `google_builtin_tools.test.ts`（84行）实际旧 body 为 functionDeclarations 红→实现后绿 1/1：真实 Provider/model codec→toChatModel→Google API fake HTTP→正文+metadata-only tail→实际 accumulator→serialize/reload，来源去重、成功/失败 URL 与 exact HTML 保存；同组覆盖非流式、仅图片保原函数与无空 tools。没有追加旧 Chat/auth 全套或 UI 镜像测试。
- Domain 窄 strict types PASS，两源 lint 0error/270跨文件解析 warning。Root 5共享类型/导出/codec源 `/tmp/e10-root-lint.log` 0error/207跨文件解析 warning，未掩盖 warnings。
- 模型页 owned lint 0error/1原有 JSON deep-clone warning，diff check0；不为旧 clone 顺手重构。
- 独立 B confirmed 移除覆写后旧 provider 草稿会在确认时恢复覆写（P2）：成功提交后同步无 override 的真实 snapshot 与 protocol，局部修复并源复核，无新增框架或测试。

## 最终 artifact / C gate

C 源冻结，独立 A/B PASS；最终实际 Release 与本地验签 PASS。HDC 已记录离线，不重复 polling；C 仍需原签名安装启动、真实 Google模型接受/来源，以及亮暗设置页/长来源/HTML自适应/外链单次打开。局部 Test-CA 包仅验签，不装原签名设备。源检查点后继续 E11。

### Root 最终实际回执

初实际 Release16.056s PASS；独立 B 定位 Common 状态/HTML错误文字主题不响应（P2），只给两个组件加局部 V1 palette/muted state 与 dark/theme watchers，不改全局 token/ChatPage、不重复加载 HTML。一次受影响 lint NoDefects，A/B 源复核关闭；没有新测试组或重复端到端 fixture。最终 `/tmp/e10-release-final-build.log` **Release9.545s PASS**，generated BuildProfile release/DEBUG=false；`/tmp/e10-release-final-sign.log` exit0、Digest verify=true、verify-app success，未安装。已有 symbol symlink/zip warning 保留。独立 A/B 报告无未关闭确认 P1/P2，设备/真实 Google C 保持 pending；继续 E11。
