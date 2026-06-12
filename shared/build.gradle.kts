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

    // Produce static Apple framework for iOS consumption
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Re-export all shared KMP modules via api() so Swift sees all types
            api(project(":ai-core"))
            api(project(":core:types"))
            api(project(":core:ai:api"))
            api(project(":core:ai:generation:api"))
            api(project(":core:ai:transformers:api"))
            api(project(":core:event"))
            api(project(":core:agent-runtime"))
            api(project(":core:agent-utils"))
            api(project(":core:llm"))
            api(project(":core:ai-prompts"))
            api(project(":core:sync:api"))
            api(project(":core:context:api"))
            api(project(":core:automation:api"))
            api(project(":feature:history"))
            api(project(":feature:webview"))
            api(project(":feature:board:api"))
            api(project(":feature:chat:api"))
            api(project(":feature:deepread:api"))
            api(project(":feature:live:api"))
            api(project(":feature:office:api"))
            api(project(":feature:terminal:api"))
            api(project(":feature:modelcouncil:api"))
            api(project(":feature:subagent:api"))
            api(project(":feature:runtime:api"))
            api(project(":feature:tools:api"))
            api(project(":core:memory:api"))
            api(project(":core:agent-store-room"))
            api(project(":core:native"))
        }
    }
}
