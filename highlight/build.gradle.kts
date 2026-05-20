plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Rust JNI integration for the tree-sitter highlight crate. Drives
    // cargo-ndk against ../native/highlight-parser and stages the resulting
    // .so per ABI under build/rustJniLibs/android. Mirrors document/.
    // See docs/RUST_NATIVE_SPIKE_PLAN.md §2.3.
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6"
}

android {
    namespace = "me.rerere.highlight"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${layout.buildDirectory.get()}/rustJniLibs/android")
        }
    }
}

cargo {
    module = "../native/highlight-parser"
    libname = "highlight_parser"
    targets = listOf("arm64", "arm", "x86_64")
    profile = "release"
    apiLevel = 24
    extraCargoBuildArguments = listOf("--package", "highlight-parser")
    targetDirectory = "../native/target"
    verbose = true
}

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn("cargoBuild")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    api(libs.quickjs)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
