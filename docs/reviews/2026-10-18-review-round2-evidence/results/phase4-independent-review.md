# Phase 4 独立复核

结论：**B25 源码、真实消息路由及针对性验证通过，无阻塞缺陷。** 本 reviewer 未参与 B25 实现，未修改生产或测试文件。其余四项 media/backup 修复采用独立 subagent 的分包复核结论，来源为 `results/phase4-media-backup-fixes.md` 中 Independent post-implementation review；本次不重复其全部实现检查。

## 审查范围与判断

- `response_content.ts` 和 `response_content.test.ts` 对照本任务 `baseline/` 逐段 diff：本阶段仅改变 makeThreeSegment 的场景失败路径并增加坏终态测试；既有 U05 等任务开始前修复未混入 B25。
- `ChatVisualFixture.ets` 任务开始前干净，对照 HEAD diff：新增 `raw-three-error` case；复用原有 raw-three payload、streamFrames、replayDebugVisual。未添加 provider 请求、默认持久化、权限或新的运行框架。
- 闭合且正常的 scene 仍走 JSON.parse → normalizeThreeScene → ready，输出同一 normalized scene 和 cover。未闭合时继续沿用既有 completedThreeObjects 路径。
- 闭合且损坏时仍产生 invalid-three-scene，segment.status 与 artifact.status 均为 error、complete=false；仅复用当前 body 的 completedThreeObjects 提取合法对象。该入口继续逐对象 normalizer、唯一 ID 过滤和 48 对象上限，外部 120000 UTF-8 字节上限保持在 fallback 前，未放宽坐标、尺寸、几何类型或脚本规则。
- 无合法对象的来源替换仍返回 artifact=null；含其他合法对象的新来源只显示新 body 的对象。实现无跨来源缓存、共享 last scene 或旧状态迁移，不会借保留画面延续其他消息来源。
- `WidgetMessageContent.ResponseSegment` 在 error artifact 非空时保留 VisualArtifactCard/VisualArtifactPreview，同时显示局部诊断。已检查 `logs/phase4-three-native-route-after.log`：坏 JSON 尾部和无效第二对象均保留预览子树，区别于修复前仅诊断 Text。
- `conversation_artifacts.ts` 收录 scene 要求 artifact.status==='ready' 且 closed；`sceneArtifactExport` 自身也拒绝非 ready。error fallback 因此只能预览，不会成为完整产物或误导出。

## 独立验证

1. 已阅读作者 red/green：新回归在旧实现失败（14 pass / 1 fail），修复后 targeted 37/37 通过。
2. 本 reviewer 独立重跑相同三个相关 suites：37/37 通过，0 fail/cancelled/skipped。日志 `results/smart-ui-phase4-review-tests.log`。
3. 额外独立 probe 通过：真实 parser + collector + export；验证合法完整场景正常收录、无效 scale 和重复对象 ID 不渲染、error scene 不进产物架且导出拒绝、49 对象时 error preview 最多 48 个、超过字节预算没有 preview、来源替换不保留旧对象。日志 `results/smart-ui-phase4-review-probe.log`；脚本同名前缀 `.ts`。
4. probe 提取并执行真实 fixture 的 threePayload/rawThreePayload：raw-three-error 保留 cube/orb/ring 三个合法对象，显示 error、complete=false、产物架无 scene；普通 raw-three 仍为 ready。
5. 受审三个文件 `git diff --check` 通过。一次有限可维护性检查确认该修复收口在原 owner，无额外 fallback 层、重试、抽象或目录重构。

## 证据边界

本次独立复核没有运行原生 lint/build/sign/install、真实 provider 或设备操作；最终 Debug 包与截图由 root 在共享模拟器上串行完成。此处通过的是源码与本地行为复核，不能替代最终原生包和截图验收。默认 fixture 不写 DB：持久化仍只在显式 amberChatVisualPersist=true 时调用既有 save，新增 case 没有改变该开关。

## 受审文件哈希

以下 SHA-256 为本 reviewer 检查后的当前版本，后续文件改变应重新判断对应验证证据：

```
4e899ade732acba6e87aa5b55534dce1c4fd111a6ca07a67cd987289d36d20bc  response_content.ts
03b45f37aac8fbc965f423abec128b197d5bf3ea0693bb0674cdb1cd1c6eabad  response_content.test.ts
dffd40427ce6de74d25865eef223f44180a00590759d81b5d3700f46e628fa38  ChatVisualFixture.ets
```
