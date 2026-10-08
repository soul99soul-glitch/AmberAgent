# Phase 2：异步 provider API host 同步

OAuth provider 修复将 MemoryTopicOps、MemoryDreamOps、模型健康检查与 OCR 调用的同步 `buildProviderApi` 接口迁移为异步 `prepareProviderApi`。本改动仅同步两处测试 host，不改 production，不改任何原断言。

在 `harmony/chat` 执行：

```sh
npx tsx --test src/test/memory_topic_store.test.cjs src/test/task_model_consumers.test.cjs
```

**Red：2 tests / 0 pass / 2 fail。** 未同步 host 时 Memory topic 首次运行得到 `failed`，模型健康检查得到 `failed`；fixture 仅提供旧同步接口。

最小修复：

- `memory_topic_store.test.cjs`：mock 名称改为 `prepareProviderApi`，加 `async`；返回与原 mock 相同的 API 对象，保留 provider/custom headers/参数捕获和真实记忆链路。
- `task_model_consumers.test.cjs`：mock 名称改为 `prepareProviderApi`，加 `async`；保留 provider、消息、参数捕获和真实 OCR/健康/image 逻辑。

**Green：2 tests / 2 pass / 0 fail**，退出码 0；日志 `/tmp/amber-review-phase2-harness-green.log`。`git diff --check` 通过。

仍验证真实模型 reference 选择、OCR 和健康检查使用同一 provider、重复模型 UUID 缓存隔离，以及记忆聚合写入/取消/超时行为。fixture 的 async API 工厂不替代 OAuth 刷新实现；刷新应由独立 production factory 测试验证。
