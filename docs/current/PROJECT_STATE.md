# AmberAgent Current Project State

Last updated: 2026-08-24

## Current migration state

- 新仓路径：`/Users/arquiel/Downloads/AI/amberagent-monorepo`
- 长期主线：`main`；Android 与 iOS 不再使用永久平台分支。
- 公开 canonical：`https://github.com/soul99soul-glitch/AmberAgent`；默认分支已切换到 Monorepo。
- 私有预演仓库：`https://github.com/soul99soul-glitch/AmberAgent-Monorepo-Staging`；保留为回滚与发布预演来源。
- `apps/ios/` 来自旧 iOS 工作区当前快照，含未提交生产改动。
- `apps/android/` 来自旧 Android `main` 工作区当前快照，含未提交 Novel 改动。
- 两个旧工作区及本地恢复包继续保留；没有通过 unrelated-history merge 拼接旧历史。
- `.migration/snapshots/` 保存两端 patch、未跟踪文件和 SHA-256，本地忽略不提交。
- 两份内容相同且不参与构建的 `amberagent.zip` 设计归档未导入；旧仓原件保留。

## Remote publication

- Monorepo 公开切换基线：`66fcdcce042fb678e68a84dbbd18d7102668f581`。
- 旧 Android `main` 保留为 `legacy/android-main`，仍指向 `8d8f33db1af1e72a54bf620338eb6f88a016a251`。
- 恢复标签：`legacy/android-main-2026-08-23` 与 `legacy/ios-pr13-2026-08-23`。
- 新 `main` 与 `legacy/android-main` 均禁止 force-push 和删除。
- 旧 iOS PR #13 已留下迁移说明并关闭；Issues #14、#15 保持打开。
- 公开仓库原 URL、Stars、Forks、Issues 与 Releases 容器未更换。

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
- 私有 staging PR #2：Android Native Build Check 通过，包含 Cargo tests、Debug APK 构建与 4 个关键 Rust `.so` 封装校验。
- 私有 staging PR #2：iOS Shared Export Check 通过，包含 `Shared.framework` 链接与 Swift 导出可达性检查。
- 已从公开 URL 全新克隆切换候选，确认 HEAD、`git fsck`、无 gitlink、无陈旧 `.gitmodules`。
- 切换前完整 bundle 已验证，SHA-256：`1b73af2b8f812858390ff40c5d7bf4be04e57caf79c9e77fe94b58da8a9ebc3f`。
- 以上不代表真机、真实 provider、系统后台行为或 kill/relaunch 已验证。

## Next steps

1. Android 与 iOS 日常任务继续从各自平台 worktree 创建短期分支与 PR。
2. 从两端真实消费面选择一个窄 façade，建立首组跨端 Golden Fixtures。
3. 只迁移该纵向切片到根 `core/`，保持两端可独立回滚。
4. 切片稳定后删除对应重复实现；在此之前跨端改动需显式双写或暂停。

## Known risks

- 两端快照包含并发 WIP；验证结果必须按平台分别记录。
- 过渡期 Core 重复，目录统一不等于共享逻辑已经统一。
- iOS 时序性能测试出现过一次非确定性抖动，应在后续相关改动中继续观察。
- 构建通过不代表真机、真实 provider、后台系统行为或 kill/relaunch 已验证。
