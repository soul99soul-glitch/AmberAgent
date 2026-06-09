plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.amber.feature.system"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":common"))
    implementation(libs.androidx.core.ktx)
}
