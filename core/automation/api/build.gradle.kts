plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.amber.core.automation.api"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
