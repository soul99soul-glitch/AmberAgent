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
        // 测试只在 jvmTest 跑：验证 save/load/delete/metadata/index 重建的纯逻辑。
        // 故意不上 iosSimulator test target——iOS actual (NSFileManager) 是薄包装，
        // 逻辑等价于 jvmMain actual (java.io.File)，单端 JVM 真实文件 IO 验证已足够，
        // 也避免在 iOS test target 上引入 java.nio 不可用的问题。
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":ai-core"))
        }
    }
}
