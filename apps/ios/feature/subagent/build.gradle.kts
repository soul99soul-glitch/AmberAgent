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
            api(project(":feature:subagent:api"))
            api(project(":ai-core"))
            api(project(":ai-provider-openai"))
            api(project(":core:app-infra"))
            api(project(":core:types"))
            api(project(":core:agent-utils"))
            api(project(":core:ai:generation:api"))
            api(project(":feature:history"))
            api(project(":feature:runtime:api"))
            api(project(":feature:task"))
            api(project(":feature:tools:api"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
    }
}
