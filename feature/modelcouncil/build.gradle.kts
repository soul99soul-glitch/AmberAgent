plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.amber.feature.modelcouncil"
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
    api(project(":feature:modelcouncil:api"))
    api(project(":ai"))
    api(project(":core:app-infra"))
    api(project(":core:settings"))
    api(project(":feature:task"))
    api(project(":feature:terminal"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
