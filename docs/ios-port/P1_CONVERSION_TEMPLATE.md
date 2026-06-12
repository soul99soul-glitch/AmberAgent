# P1 KMP 模块转换模板

> 日期：2026-06-12
> 样板模块：`core/agent-utils`

## 1. 前置条件

在执行任何模块转换前，确认以下条件已满足：

- 根项目 `build.gradle.kts` 已声明 `kotlin-multiplatform` 插件 (`apply false`)
- `gradle/libs.versions.toml` 的 `[plugins]` 段已有 `kotlin-multiplatform` 条目
- Kotlin/Native 工具链已下载（首次编译 iOS target 时自动完成，约 2 分钟）

## 2. 转换步骤

### Step 1：修改 `build.gradle.kts`

**Before（kotlin.jvm 模式）：**
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // 如有
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // ...
}
```

**After（KMP 模式）：**
```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization) // 如有
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            // 其他依赖也移到这里
        }
    }
}
```

**要点：**
- 去掉 `java {}` 块和 `jvmToolchain`（KMP 的 jvm() target 自带合理默认值）
- 依赖从顶层 `dependencies {}` 移入 `sourceSets.commonMain.dependencies {}`
- `api` / `implementation` 语义不变

### Step 2：移动源码

```bash
# 创建 commonMain 目录
mkdir -p <module>/src/commonMain/kotlin

# 移动源码（保留包结构）
mv <module>/src/main/kotlin/<package-path> <module>/src/commonMain/kotlin/<package-path>

# 删除旧的 main 目录
rm -rf <module>/src/main
```

如果模块有测试：
```bash
mkdir -p <module>/src/commonTest/kotlin
mv <module>/src/test/kotlin/<package-path> <module>/src/commonTest/kotlin/<package-path>
```

**注意**：kotest-runner-junit5 不支持 Kotlin/Native。commonTest 只能用 `kotlin-test` + `kotest-assertions`（assertions 库支持 KMP）。如果测试依赖 kotest runner 的特定功能，留在 `src/jvmTest`。

### Step 3：验证

```bash
# 1. iOS 目标编译
./gradlew :<module>:compileKotlinIosSimulatorArm64

# 2. Android 构建不回归
./gradlew :app:assembleDebug

# 3. 如有测试
./gradlew :<module>:jvmTest
```

## 3. 踩坑记录

### 3.1 插件版本冲突

**错误**：`The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version`

**原因**：根项目 `build.gradle.kts` 没有声明 `kotlin-multiplatform` 插件 (`apply false`)，导致子项目通过 version catalog 引入时与 classpath 上的 Kotlin 插件版本冲突。

**解决**：在根 `build.gradle.kts` 的 `plugins {}` 块中添加：
```kotlin
alias(libs.plugins.kotlin.multiplatform) apply false
```

### 3.2 下游依赖无需改动

KMP 模块的 `jvm()` target 产出的 artifact 与纯 JVM 模块完全兼容。下游 Android 模块的 `project(":core:agent-utils")` 依赖**不需要任何改动**。

### 3.3 Kotlin/Native 首次下载

首次编译 iOS target 会下载 LLVM 工具链和 libffi（约 200MB），后续构建不再需要。下载位置：`~/.konan/`。

## 4. 适用于 `android.library` 惯性模块的额外步骤

对于被审计为"惯性挂了 Android 插件"的接口模块（如 `core/ai/api`），转换前需额外一步：

**Step 0：去掉 Android 插件**

将 `build.gradle.kts` 中的：
```kotlin
plugins {
    alias(libs.plugins.android.library)
}
android { ... }
```
替换为 KMP 配置（同 Step 1）。同时删除模块中的 `AndroidManifest.xml`（如有）。
