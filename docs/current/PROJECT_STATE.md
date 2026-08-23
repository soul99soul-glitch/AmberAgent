# AmberAgent Current Project State

Last updated: 2026-08-23

## Current migration state

- 新仓路径：`/Users/arquiel/Downloads/AI/amberagent-monorepo`
- 长期主线：`main`；Android 与 iOS 不再使用永久平台分支。
- `apps/ios/` 来自旧 iOS 工作区当前快照，含未提交生产改动。
- `apps/android/` 来自旧 Android `main` 工作区当前快照，含未提交 Novel 改动。
- 两个旧工作区保持原样；未 merge、rebase、reset、clean、push 或关闭 PR。
- `.migration/snapshots/` 保存两端 patch、未跟踪文件和 SHA-256，本地忽略不提交。
- 两份内容相同且不参与构建的 `amberagent.zip` 设计归档未导入；旧仓原件保留。

## Source baselines

### iOS

- Source: `/Users/arquiel/Downloads/AI/amberagent-ios`
- Branch: `feat/ios-provider-parity-claude`
- HEAD: `1fbe173fe420fab51ab3a321c1d803de8f4bbada`
- Imported WIP: 37 tracked files, +2775/-315；新增 vendor TextKit 测试已导入。
- 临时 iPhone 截图仅在恢复 tar 中，不进入活跃源码。

### Android

- Source: `/Users/arquiel/Downloads/AI/amberagent-Android`
- Branch: `main`
- HEAD: `8d8f33db1af1e72a54bf620338eb6f88a016a251`
- Imported WIP: 16 tracked files, +1780/-250。

## Architecture status

- Android 与 iOS 是同一 monorepo 中的两个独立应用项目，平台边界由目录和稀疏 worktree 表达。
- 当前是过渡快照：两端仍各自携带历史共享实现，尚未建立单一 Core 代码源。
- iOS 构建仍在 `apps/ios` 内调用 Gradle 生成动态 `Shared.framework`。
- 根 `core/` 当前只定义抽取边界；不能一次迁移 32 个模块。

## Verification status

- 恢复 patch/tar 已生成，并完成 SHA-256 校验。
- `apps/ios`: `./gradlew help` 通过。
- `apps/ios`: `:ai-provider-openai:jvmTest` 与 `:shared:linkDebugFrameworkIosSimulatorArm64` 通过。
- `apps/ios`: XcodeGen 已从 `iosApp/project.yml` 重建被忽略的工程。
- `apps/ios`: `ChatReasoningCardTests`、`IOSAgentToolEngineTests`、`IOSGeminiProviderTests`、`NovelCollaborationModeTests`、`NovelLiveModelAdapterTests` 在 iPhone 17 Pro Simulator 整组复跑通过。首轮仅一项时序测试偶发失败，单项复跑与整组复跑均通过；未修改阈值。
- `apps/android`: `./gradlew help` 与 `:feature:novel-workspace:testDebugUnitTest` 通过。
- `apps/android`: `NovelWorkspaceRuntimeTest` 18 tests、0 failures、0 skipped。
- 以上不代表真机、真实 provider、系统后台行为或 kill/relaunch 已验证。

## Next steps

1. 从 iOS 的真实 Swift 消费面选一个窄 façade，建立首组跨端 Golden Fixtures。
2. 只迁移该纵向切片到根 `core/`，保持两端可独立回滚。
3. 切片稳定后删除对应重复实现；在此之前跨端改动需显式双写或暂停。
4. 需要远端协作时再决定新仓 remote、旧 PR 归档和发布迁移，不自动处理。

## Known risks

- 两端快照包含并发 WIP；验证结果必须按平台分别记录。
- 过渡期 Core 重复，目录统一不等于共享逻辑已经统一。
- iOS 时序性能测试出现过一次非确定性抖动，应在后续相关改动中继续观察。
- 构建通过不代表真机、真实 provider、后台系统行为或 kill/relaunch 已验证。
