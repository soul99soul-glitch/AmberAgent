# AmberAgent Repository Instructions

## Repository responsibility

- 本仓只有一个长期主线：`main`。Android 与 iOS 不使用永久平台分支。
- `apps/android/` 是 Android 产品项目；普通 Android 任务不得读取 `apps/ios/`。
- `apps/ios/` 是 iOS 产品项目；普通 iOS 任务不得读取 `apps/android/`。
- `core/` 只接收经过两端真实消费面证明稳定的协议、模型与纯逻辑。
- 当前仍处于过渡期：两端各自携带历史共享实现。不要为了目录整齐一次性去重。

## Automatic start

1. 读取 `docs/current/PROJECT_STATE.md`，再读取目标平台最近的 `AGENTS.md`。
2. 运行 `git status --short --branch`，保护现有 WIP。
3. 用一句话定义成功标准，只定位生产入口、直接调用者和定点测试。
4. 初始侦察默认不超过 12 个文件；只有 import、调用链或编译错误才能扩大范围。

## Scope discipline

- 平台任务默认只读写 `apps/<platform>/` 与明确列出的 `core/` 文件。
- 不对另一平台做顺手 parity、格式化、清理或重构。
- 不以全仓 diff、旧 PR 或历史报告作为普通任务入口。
- 不自动遍历 `docs/archive/`、vendor、生成物或另一平台目录。
- 跨端变更必须写明共享契约、两端消费者和各自验证命令。

## Git and migration safety

- 未明确要求时不 commit、push、reset、clean、rebase、stash 或关闭远端 PR。
- 旧仓与 `.migration/snapshots/` 是恢复来源，不在普通任务中修改或删除。
- 同一文件混有他人 WIP 时先读 diff；无法隔离就停止并说明。
- 平台工作通过短期分支或 detached worktree 开展，不重新建立永久 iOS/Android 分支。

## Verification

- Android：先跑相关 JVM 测试，再按风险扩大到 `assembleDebug`。
- iOS/KMP：先跑相关 Gradle 测试或 framework compile，再跑定点 Xcode 测试/构建。
- 模拟器、真机、真实 provider、后台系统行为分别报告，不互相替代。
- 完成后检查本轮文件、`git diff --check` 与最终 status。

## Documentation

- `docs/current/PROJECT_STATE.md` 只保留当前事实、验证、阻塞和下一步，目标是几分钟读完。
- 历史证据留在旧仓或外部归档，不恢复按日期无限追加。
- `docs/current/CORE_BOUNDARY.md` 是抽取边界；实现证据与测试优先于文档。
