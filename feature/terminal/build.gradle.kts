plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.amber.feature.terminal"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
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
    api(project(":core:model"))
    api(project(":core:app-infra"))
    api(project(":core:settings"))
    api(project(":feature:runtime:api"))
    api(project(":feature:task"))
    api(project(":feature:workspace"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation("com.github.mwiede:jsch:2.28.7")
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // An explicitly requested loopback or Docker IT run must contact the current server.
    outputs.upToDateWhen {
        !providers.environmentVariable("AMBER_SSH_LOOPBACK_FIXTURE").isPresent &&
            !providers.environmentVariable("AMBER_SSH_IT").isPresent
    }
}
