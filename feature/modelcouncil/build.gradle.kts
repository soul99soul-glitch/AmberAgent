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
            api(project(":feature:modelcouncil:api"))
            api(project(":ai-core"))
            api(project(":core:app-infra"))
            api(project(":core:types"))
            api(project(":feature:task"))
            api(project(":feature:terminal:api"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
    }
}
