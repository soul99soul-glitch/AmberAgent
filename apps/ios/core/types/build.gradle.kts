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
            api(project(":ai-core"))
            api(project(":core:ai:api"))
            api(project(":core:ai-prompts"))
            api(project(":core:sync:api"))
            api(project(":core:context:api"))
            api(project(":feature:live:api"))
            api(project(":feature:modelcouncil:api"))
            api(project(":feature:terminal:api"))
            api(project(":feature:office:api"))
            api(project(":feature:board:api"))
            api(project(":feature:subagent:api"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
    }
}
