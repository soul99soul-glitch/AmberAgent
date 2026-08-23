plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.amber.feature.tools.access"
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
    api(project(":common"))
    api(project(":feature:tools:api"))
    api(project(":feature:runtime:api"))
    api(project(":feature:system"))
    api(project(":feature:workspace"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
