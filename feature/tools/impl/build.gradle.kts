plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.amber.feature.tools.impl"
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
    api(project(":ai"))
    api(project(":core:model"))
    api(project(":core:settings"))
    api(project(":core:app-infra"))
    api(project(":core:agent-utils"))
    api(project(":core:automation:api"))
    api(project(":feature:tools:api"))
    api(project(":feature:tools:access"))
    api(project(":feature:runtime:api"))
    api(project(":feature:task"))
    api(project(":feature:terminal"))
    api(project(":feature:terminal:api"))
    api(project(":feature:modelcouncil"))
    api(project(":feature:modelcouncil:api"))
    api(project(":feature:subagent"))
    api(project(":feature:subagent:api"))
    api(project(":feature:workspace"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
}
