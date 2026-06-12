plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }

        commonMain.dependencies {
            implementation(project(":ai-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Ktor client (SSE plugin is bundled in ktor-client-core since Ktor 3.4.3)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        iosArm64Main.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        iosSimulatorArm64Main.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
