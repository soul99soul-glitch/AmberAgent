# AmberAgent HarmonyOS Phase 0 交付小结

> 分支：`harmony-preview`  
> 目录：`/Users/mi/Downloads/AI/AmberAgent-harmony-preview`  
> 状态：source-only 阶段完成；真机/SDK spike 等 Gate 0 解锁后继续。

## 当前结论

Phase 0 的 source-only 部分已经收口。现在不要继续堆文档或假工程，下一步要先把 HarmonyOS 工具链装好。

本机 Gate 0 检查结果是 `blocked`：缺 DevEco Studio、`hvigor`、`ohpm`、`hdc` 和设备/模拟器 target。Node、Rust、Cargo 已经在本机可用，但这还不足以跑 HarmonyOS 真机 spike。

这意味着：

- 可以继续保留现有 source-only 契约作为 Phase 1 输入。
- 不能把任何 HarmonyOS 能力标成 `pass` / `limited` / `fail`。
- 不能创建手写的假 DevEco 工程文件。
- 等工具链就绪后，优先跑三个真 go/no-go：SSE、RDB/Preferences、ArkUI + Markdown 流式渲染。

## 已交付内容

### 计划与矩阵

- `docs/harmony-native-migration-plan.md`：Android 到原生 HarmonyOS NEXT 的整体迁移计划。
- `docs/harmony-phase0-spike-plan.md`：Phase 0 的 spike 工作拆分。
- `docs/harmony-capability-matrix.md`：能力矩阵、Gate 0 状态、P0/P1/P2/replace 分类。
- `docs/harmony-gate0-report.md`：本机工具链检查报告，当前为 blocked。

### 安全 scaffold

- `harmony/README.md`
- `harmony/spikes/README.md`
- `harmony/native/rust-napi/README.md`
- `harmony/tests/fixtures/README.md`

这些文件只是占位和约束，不是 DevEco 工程。当前目录下没有手写 `hvigorfile.ts`、`module.json5`、`build-profile.json5` 或 `oh-package.json5`。

### Android 源码 inventory

- `scripts/harmony-inventory/scan-harmony-inventory.mjs`
- `docs/harmony-inventory/README.md`
- `docs/harmony-inventory/rust-native.md`
- `docs/harmony-inventory/providers-streaming.md`
- `docs/harmony-inventory/storage-room-preferences.md`
- `docs/harmony-inventory/platform-android-apis.md`
- `docs/harmony-inventory/tools.md`
- `docs/harmony-inventory/ui-pages.md`

这些清单会在后面给 AI worker 当输入，避免靠记忆迁移。

### fixtures 与可执行契约

Fixtures 放在 `harmony/tests/fixtures/`，覆盖：

- OpenAI-compatible SSE streaming
- UIMessage / Conversation / MessageNode
- transformer 语义
- agent loop 成功、失败、取消路径
- Markdown 流式稳定块
- RDB rows / Preferences shape

可执行检查：

- `scripts/harmony-inventory/validate-harmony-fixtures.mjs`
- `scripts/harmony-inventory/check-source-contracts.mjs`

当前验证结果：

```text
Fixture validation passed (54 JSON/JSONL records checked).
Source contract checks passed (10 check groups).
```

## 已修正的关键问题

过程中做了多轮 subagent review，修掉了几处容易埋雷的问题：

- Rust NAPI 不再作为 Chat 生死门。Android 侧 native 本来就有 fallback，鸿蒙版先保证 ArkTS 路径能跑。
- Gate 0 的概念 ID 和脚本探针 ID 分开：矩阵用 `G0-xx`，脚本用 `G0-Txx`。
- 依赖设备/SDK 的能力都保持 `toolchain_blocked`，没有提前写成通过。
- SSE parser skeleton 修正了单行 event 返回裸对象的问题。
- `check-source-contracts.mjs` 不再只做纸面 JSON 校验，已经覆盖 SSE normalization、storage row mapping、agent loop、transformer 和 contract 引用。
- Streaming contract 里明确标注：tool delta 合并、reasoning finishedAt、final full-message replacement 还没有 executable accumulator，要到 Phase 1 ArkTS 实现时补。

## 后续不要做什么

现在先别做这些：

- 不要继续手写 DevEco 工程配置。
- 不要声明 RDB、ArkUI、NAPI、Accessibility 已经可行。
- 不要继续无限扩 fixtures。source-only 的收益已经开始下降。
- 不要把终端/proot、通知监听、Accessibility 当成 P0 阻塞项。

## 下一步

唯一真正有价值的下一步是解锁 Gate 0：

1. 安装 DevEco Studio。
2. 安装 HarmonyOS NEXT SDK，确认是否有 Full SDK。
3. 配好 debug 签名。
4. 准备一台 HarmonyOS NEXT 设备或可用模拟器。
5. 确认 `hvigor`、`ohpm`、`hdc` 可用。
6. 重新运行：

```bash
node scripts/harmony-inventory/check-harmony-gate0.mjs
```

Gate 0 通过后，再跑真正的 P0 spike：

1. OpenAI-compatible SSE streaming。
2. RDB + Preferences。
3. ArkUI + Markdown 流式渲染。

这三个跑通，鸿蒙原生版才算真正进入实现阶段。
