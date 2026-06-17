# Findings & Decisions: HarmonyOS Native Migration

## Requirements

- 用户要求：为 AmberAgent 制定从 Android 迁移到原生 HarmonyOS 的计划。
- 目标路线：原生 HarmonyOS NEXT（ArkTS/ArkUI），不是 Web 壳。
- 计划需要覆盖：阶段、P0/P1/P2 范围、风险、AI 辅助并行开发、验收标准、里程碑。

## Research Findings

- AmberAgent 当前是 Android Kotlin/Jetpack Compose 应用，模块包括 `app/`、`ai/`、`core/`、`feature/`、`native/`、`web-ui/` 等。
- 当前技术栈包括 Jetpack Compose、Koin、Room、DataStore、WorkManager、OkHttp/kotlinx.serialization、Android Service、AccessibilityService、NotificationListenerService、MediaProjection、WebView、Rust JNI native crates、proot/Alpine terminal runtime。
- HarmonyOS NEXT 原生应用开发采用 ArkTS/ArkUI、Stage 模型、UIAbility/ExtensionAbility、module.json5/hvigor/OHPM/HAP/HAR/HSP。
- HarmonyOS NEXT 不兼容 Android APK、ART、Java/Kotlin/Jetpack Compose，迁移主线应按“原生重写 + 逻辑复用”理解。
- 高风险能力：Accessibility 自动化、通知监听、本地 CLI/proot、长时后台任务、系统级文件/联系人/短信等权限。
- 高复用潜力：Rust native crates、Agent 行为模型、LLM provider 协议、数据模型、测试语料、产品交互设计。
- Reviewer 复核后修正：Rust native 在 Android 侧多处有 fallback，不应作为 Chat 生死门；Phase 0 真正 go/no-go 是 ArkTS SSE、RDB/Preferences、ArkUI+Markdown 流式渲染。
- Phase 0 必须增加 Gate 0 工具链门：DevEco Studio、HarmonyOS NEXT/Full SDK、签名、真机/模拟器、native build path。
- 迁移计划新增能力行：Markdown 渲染、Message Transformer、sync/export crypto、reader/document extraction、clipboard/Want/share、image/OCR。

## Technical Decisions

| Decision | Rationale |
|----------|-----------|
| 计划采用 0-5 六阶段 | 先判定能力边界，再搭共享核心，再做 Chat 闭环，最后铺功能和收敛 |
| P0 聚焦 Agent/LLM/Chat/RDB/Preferences/Rust NAPI | 这些构成最小可用产品核心，且风险可控 |
| P2 放置 Accessibility/终端/通知监听 | 这些可能受系统能力限制，不能绑架主线 |
| 建议 AI-native 多 Agent 并行迁移 | UI、数据模型、RDB、Provider、工具卡片均可并行生成，人工做架构和真机验证 |
| 先做 read-only inventory scripts | 相比手写清单，脚本能重复生成 Android 源锚点，适合作为迁移 prompt 输入 |
| `harmony/` 只放安全占位文档 | 工具链未确认前不提交 fake DevEco/hvigor 工程文件 |

## Issues Encountered

| Issue | Resolution |
|-------|------------|
| HarmonyOS 部分系统能力不一定有 Android 对等 API | 在 Phase 0 设置“生死判定”，不可行则降级/替代/下线 |
| 全量迁移范围过大 | 通过 P0/P1/P2 裁剪，避免 1:1 平移 |

## Resources

- Worktree: `/Users/mi/Downloads/AI/AmberAgent-harmony-preview`
- Plan: `docs/harmony-native-migration-plan.md`
- Phase 0 matrix: `docs/harmony-capability-matrix.md`
- Phase 0 spike plan: `docs/harmony-phase0-spike-plan.md`
- Generated inventory: `docs/harmony-inventory/README.md`
- Inventory script: `scripts/harmony-inventory/scan-harmony-inventory.mjs`
- Harmony placeholder scaffold: `harmony/README.md`
- AmberAgent README: `README.md`, `README_ZH_CN.md`
- Existing architecture notes: `AGENTS.md`, `REFACTOR_PLAN_AGENT_KERNEL.md`, `AmberAgent-Harness-边界审查与建议.md`
- HarmonyOS docs entry: https://developer.huawei.com/consumer/cn/doc/

## Visual/Browser Findings

- 华为文档中心标题显示为“文档中心-HarmonyOS NEXT开发文档-华为开发者联盟”。
- 官方文档入口包括探索、设计、开发、分发、推广与变现、生态合作、支持、文档、管理中心等导航。
