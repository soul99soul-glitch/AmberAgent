# Phase 2 Provider OAuth 修复

## 已确认问题和修复边界

CHAT-01 的同一失败模式覆盖自动/手动压缩、自动标题/建议/记忆提取共享生成函数、Daydream、主题聚合、OCR/视觉健康探针及子代理。所有这些真实 Entry 调用点直接 `buildProviderApi`，均绕过 OAuth refresh，且未提供 Codex accountId。ChatPage 手动标题相同问题交由 UI 负责人在 Phase 3 接线。

最小方案：把 `prepareProviderRuntime` 已有 OAuth 分支提取为小的 `prepareProviderAuth`，只返回 provider 深拷贝和 accountId；既有 runtime 复用此函数。Entry 导出 `prepareProviderApi(provider, headers)`，保持辅助任务原有 TextGenerationParams 和 header 边界，尤其 OCR 仍仅传模型且不带 assistant/custom 参数。helper 在等待刷新前对 headers 建快照。

子代理复用现有 generation-port 实例内 map：异步 `resolveModel` 刷新并缓存 auth snapshot；同步 `makeProvider` 保留 accountId，不新增全局 provider 状态。自动压缩工厂继续同步返回 `ChatStreamProvider`，实际 `streamText` 时刷新，保留工具路径同步依赖与压缩参数。无额外重试、兜底或 provider 选择规则变化。

CHAT-05 的 Entry 接线仅向 foreground `prepareContext` 传 `abortSignal: ctx.signal`；domain 负责人在异步 precompact 分支显式去掉该信号，保留现有独立生命周期。

## RED

`provider_auxiliary_auth_entry.test.cjs` 通过 TypeScript AST 提取并执行生产 Entry 工厂，SDK/存储端口替换，HTTP 使用真实协议适配器。覆盖 7 种任务 × Codex/Grok/API-key，断言实际 HTTP Authorization/accountId、源 choice 不变、原任务 headers/body/reasoning/temperature 边界。补 helper 的刷新等待快照与失败不外呼用例。

初始 21 项结果：7 个 API-key 用例全部通过，14 个 OAuth 用例全部因实际 `Bearer stale` 对期望 fresh token 失败。红日志：`/tmp/phase2-provider-auth-red.log`。未用缺少 helper 导致的错误代替主问题复现。

## GREEN

Phase 2 已实施上述最小方案，并为 foreground context preparation 新增真实 Entry 接线测试。23 项新 Entry 用例全部通过；连现有 12 项 provider runtime 和 4 项 DeepRead Entry 共 **39/39 通过**。日志：`/tmp/phase2-provider-auth-green.log`。

定向 ArkTS lint 覆盖修改的 8 个生产文件：**0 error / 54 warn**，警告主要来自现有纯 TS 文件 JSON 深拷贝/类型解析，以及 Date 性能提示。日志：`/tmp/phase2-provider-auth-lint.log`。`git diff --check` 通过。构建、签名、设备验证由主 agent 在集成后统一执行；此报告不把 HTTP fixture 视为真实云端接受。

尚待独立 subagent 审查及主 agent 的集成验证。ChatPage 手动标题由 Phase 3 UI 负责人使用已导出的 `prepareProviderApi` 完成接线。
