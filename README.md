# AmberAgent Monorepo

AmberAgent 的单主线仓库。Android 与 iOS 是两个独立应用项目，共享核心按真实消费面逐步抽取。

```text
apps/android/   Android App 与当前过渡共享实现
apps/ios/       iOS App 与当前过渡 KMP/Native 实现
core/           稳定共享核心的抽取入口（当前不冒进搬码）
docs/current/   当前事实、仓库地图与 Core 边界
scripts/        平台工作区工具
```

当前阶段不合并旧 Android/iOS 历史，也不把任一旧分支覆盖到另一端。两个来源工作区及其未提交恢复包均保留；见 `docs/current/PROJECT_STATE.md`。

## Build roots

```bash
cd apps/android && ./gradlew help
cd apps/ios && ./gradlew :shared:tasks
cd apps/ios && xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -showdestinations
```

## Platform workspaces

仓库首次提交后，可创建只暴露单个平台与 Core 的稀疏 worktree：

```bash
./scripts/create-platform-worktree.sh ios ../amberagent-monorepo-ios
./scripts/create-platform-worktree.sh android ../amberagent-monorepo-android
```

日常任务从对应 worktree 的 `apps/ios` 或 `apps/android` 进入。跨端契约任务才打开完整仓库。
