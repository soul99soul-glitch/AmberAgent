plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.amber.feature.workspace"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

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
    api(libs.kotlinx.coroutines.core)
    implementation("androidx.documentfile:documentfile:1.0.1")
}
