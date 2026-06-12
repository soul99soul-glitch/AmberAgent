plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        val jvmMain by getting
        val nativeMain by creating { }
        val iosArm64Main by getting { dependsOn(nativeMain) }
        val iosSimulatorArm64Main by getting { dependsOn(nativeMain) }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.property)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
