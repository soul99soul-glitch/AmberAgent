plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.amber.core.settings"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    api(project(":ai"))
    api(project(":core:model"))
    api(project(":core:app-infra"))
    api(project(":core:agent-utils"))
    api(project(":feature:terminal:api"))
    api(project(":feature:board:api"))
    api(project(":feature:live:api"))
    api(project(":feature:modelcouncil:api"))
    api(project(":feature:office:api"))
    api(project(":feature:subagent:api"))
    api(project(":core:ai-prompts"))
    api(project(":core:memory:api"))
    api(project(":core:sync:api"))
    api(project(":core:context:api"))
    api(project(":core:ai:api"))
    api(project(":search"))
    api(project(":tts"))
    api(libs.androidx.datastore.preferences)
    api(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.runtime:runtime")
}
