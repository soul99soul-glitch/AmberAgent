plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:types"))
            api(project(":core:agent-utils"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        // 大多数 storage 逻辑只在 jvmTest 跑：验证 save/load/delete/metadata/index 重建的纯逻辑。
        // iOS simulator 只补 actual 文件语义契约，避免把 java.nio 相关测试搬到 iOS target。
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":ai-core"))
        }
        iosSimulatorArm64Test.dependencies {
            implementation(kotlin("test"))
        }
    }
}
