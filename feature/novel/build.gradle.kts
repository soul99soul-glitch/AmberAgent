plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.amber.feature.novel"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("test") {
            // Shared iOS/Android golden fixtures live at repo-root test-fixtures/.
            resources.srcDir(rootProject.file("test-fixtures"))
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    // Plan dependency direction: novel -> ai + settings + app-infra (+ serialization/coroutines).
    // :core:types is not a real included module in this workspace; omit until it exists.
    api(project(":ai"))
    // One-way bridge out of the legacy JSON engine: the workspace format is the destination.
    api(project(":feature:novel-workspace"))
    api(project(":core:settings"))
    api(project(":core:app-infra"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
