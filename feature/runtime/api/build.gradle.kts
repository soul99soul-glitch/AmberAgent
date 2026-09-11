plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.amber.feature.runtime.api"
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
    // Step 6: ExecutionPolicy's allowedSystemCapabilities uses the Capability
    // enum (feature/tools/api); no reverse dependency exists (feature/tools/api
    // does not depend on feature/runtime/api), so this stays acyclic.
    api(project(":feature:tools:api"))
    // P1-6: ExecutionPaths (shared gate/narrow canonicalization) anchors
    // workspace-relative paths with WorkspacePaths; feature/workspace is a
    // leaf module, so this stays acyclic.
    api(project(":feature:workspace"))
    api(libs.kotlinx.serialization.json)
}
