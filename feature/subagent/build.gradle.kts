plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.amber.feature.subagent"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    api(project(":feature:subagent:api"))
    api(project(":ai"))
    api(project(":core:model"))
    api(project(":core:settings"))
    api(project(":core:app-infra"))
    api(project(":core:agent-utils"))
    api(project(":core:ai:generation:api"))
    api(project(":feature:history"))
    api(project(":feature:runtime:api"))
    api(project(":feature:task"))
    api(project(":feature:tools:api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
