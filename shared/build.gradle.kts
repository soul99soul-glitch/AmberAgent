plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // SKIE disabled: version 0.9.5 does not support Kotlin 2.3.21 yet.
    // Re-enable once SKIE releases a compatible version.
    // alias(libs.plugins.skie)
}

// skie { isEnabled = false } // Uncomment if skie plugin is applied but should be disabled

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    // Shared KMP modules — referenced by both framework export and commonMain
    val sharedProjects = listOf(
        ":ai-core",
        ":ai-provider-openai",
        ":core:types",
        ":core:ai:api",
        ":core:ai:generation:api",
        ":core:ai:transformers:api",
        ":core:event",
        ":core:agent-runtime",
        ":core:agent-utils",
        ":core:llm",
        ":core:ai-prompts",
        ":core:sync:api",
        ":core:context:api",
        ":core:automation:api",
        ":feature:history",
        ":feature:webview",
        ":feature:board:api",
        ":feature:chat:api",
        ":feature:deepread:api",
        ":feature:live:api",
        ":feature:office:api",
        ":feature:terminal:api",
        ":feature:modelcouncil:api",
        ":feature:modelcouncil",
        ":feature:subagent:api",
        ":feature:runtime:api",
        ":feature:tools:api",
        ":feature:task",
        ":core:memory:api",
        ":core:agent-store-room",
        ":core:native",
    )

    // Produce dynamic Apple framework for iOS consumption.
    // export() causes Kotlin/Native to generate ObjC headers for all transitively
    // visible types from these modules — api() alone does NOT do this.
    //
    // isStatic = false is REQUIRED: bundled SQLite native symbols (e.g.
    // _sqlite3_mutex_held, _sqlite3_unlock_notify) are compiled into the framework
    // via androidx.sqlite:sqlite-bundled. A static framework does not propagate
    // these linker symbols to the final app binary, causing unresolved-symbol link
    // errors. A dynamic framework embeds them correctly.
    //
    // Linker opts for core:native cinterop (AmberNative / libamber_ffi) must be
    // repeated here — they are scoped to :core:native binaries and do not
    // propagate automatically through export(). Mirrors core/native/build.gradle.kts:25-30.
    val amberNativeXcfDir = "${rootProject.projectDir}/native/AmberNative.xcframework"

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val slice = if (target.name.contains("Simulator")) "ios-arm64-sim" else "ios-arm64"

        target.binaries.framework {
            baseName = "Shared"
            isStatic = false
            sharedProjects.forEach { export(project(it)) }
        }

        target.binaries.forEach { binary ->
            binary.linkerOpts(
                "-L$amberNativeXcfDir/$slice",
                "-lamber_ffi",
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            sharedProjects.forEach { api(project(it)) }
        }
    }
}
