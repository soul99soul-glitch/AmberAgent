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
        ":feature:subagent:api",
        ":feature:runtime:api",
        ":feature:tools:api",
        ":core:memory:api",
        ":core:agent-store-room",
        ":core:native",
    )

    // Produce static Apple framework for iOS consumption
    // export() causes Kotlin/Native to generate ObjC headers for all transitively
    // visible types from these modules — api() alone does NOT do this.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            sharedProjects.forEach { export(project(it)) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            sharedProjects.forEach { api(project(it)) }
        }
    }
}
