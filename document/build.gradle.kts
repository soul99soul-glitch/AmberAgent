plugins {
    alias(libs.plugins.android.library)
    // (Previously) org.mozilla.rust-android-gradle.rust-android v0.9.6 — REMOVED.
    // That plugin still expects AGP's pre-9 `AppExtension` API which doesn't
    // exist in AGP 9.2 (Codex Round 2 reject). The hand-rolled cargo-ndk
    // Exec task below produces the same .so layout the plugin would have,
    // with one less moving part to maintain across AGP upgrades.
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

// ---------------------------------------------------------------------------
// Rust JNI build — hand-rolled cargo-ndk invocation.
//
// Mozilla's rust-android-gradle plugin (0.9.6) ties to AGP <9's `AppExtension`,
// which AGP 9.2 dropped. Until a plugin maintainer ships AGP-9 support, drive
// cargo-ndk ourselves. The task signature mirrors `cargoBuildRegexTransformer`
// in app/build.gradle.kts so the pattern is one place to maintain.
// ---------------------------------------------------------------------------

val androidNdkHome: String =
    providers.environmentVariable("ANDROID_NDK_HOME").orNull
        ?: providers.gradleProperty("android.ndkPath").orNull
        ?: System.getProperty("android.ndk")
        ?: "${System.getenv("ANDROID_HOME") ?: System.getProperty("user.home") + "/Library/Android/sdk"}/ndk/27.0.12077973"

val cargoBuildOfficeParsers = tasks.register<Exec>("cargoBuildOfficeParsers") {
    group = "rust"
    description = "Build liboffice_parsers.so for arm64-v8a via cargo-ndk"
    workingDir = file("../native/office-parsers")
    environment("ANDROID_NDK_HOME", androidNdkHome)
    commandLine = listOf(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", file("${layout.buildDirectory.get()}/rustJniLibs/android").absolutePath,
        "build", "--release",
        "--manifest-path", file("../native/office-parsers/Cargo.toml").absolutePath,
    )
    isIgnoreExitValue = false
    onlyIf {
        val cargoNdkAvailable = try {
            ProcessBuilder("cargo", "ndk", "--version").start().waitFor() == 0
        } catch (_: Throwable) { false }
        if (!cargoNdkAvailable) {
            logger.lifecycle("[rust] cargo-ndk not on PATH; skipping office-parsers native build (JVM fallback will kick in at runtime)")
        }
        cargoNdkAvailable
    }
}

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn(cargoBuildOfficeParsers)
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
