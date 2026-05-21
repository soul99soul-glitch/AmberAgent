plugins {
    alias(libs.plugins.android.library)
    // Rust JNI integration. Drives `cargo-ndk` against ../native/office-parsers
    // and stages the resulting .so per ABI under build/rustJniLibs/android.
    // See docs/RUST_NATIVE_SPIKE_PLAN.md §2.3.
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6"
}

android {
    namespace = "me.rerere.document"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Bring the cargo-built .so files into the AAR's jniLibs.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${layout.buildDirectory.get()}/rustJniLibs/android")
        }
    }
}

cargo {
    module = "../native/office-parsers"
    libname = "office_parsers"
    // app currently ships arm64-v8a only (see app/build.gradle.kts ndk.abiFilters
    // + splits.abi.include). Building arm + x86_64 here would just inflate CI
    // without ending up in the APK. Narrow to arm64 — Codex review fix.
    targets = listOf("arm64")
    profile = "release"
    apiLevel = 26
    // Share target/ dir across all 3 crates in the workspace.
    // extraCargoBuildArguments removed (P3 sweep) — `module` already points
    // at the office-parsers crate root, so --package is redundant.
    targetDirectory = "../native/target"
    verbose = true
}

// Make the `preBuild` task depend on `cargoBuild` so .so files are in place
// before AAR is packaged. Wrapped in afterEvaluate because cargoBuild is
// registered by the rust-android plugin after evaluation.
afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn("cargoBuild")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
