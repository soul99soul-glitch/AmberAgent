plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val amberNativeXcfDir = "${rootProject.projectDir}/native/AmberNative.xcframework"

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    // Configure cinterop + linker for iOS targets
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val slice = if (target.name.contains("Simulator")) "ios-arm64-sim" else "ios-arm64"
        target.compilations.getByName("main").cinterops.create("AmberNative") {
            defFile = file("src/nativeInterop/cinterop/AmberNative.def")
            includeDirs("$amberNativeXcfDir/$slice/Headers")
        }
        target.binaries.forEach { binary ->
            binary.linkerOpts(
                "-L$amberNativeXcfDir/$slice",
                "-lamber_ffi",
            )
        }
    }
}
