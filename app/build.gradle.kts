import groovy.json.JsonSlurper
import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

val buildLocalProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun projectProperty(vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
        (findProperty(name) as? String)
            ?: System.getenv(name)
            ?: buildLocalProperties.getProperty(name)
    }.orEmpty()

fun String.asBuildConfigString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

val xiaomiXmsAppId = projectProperty("xiaomiXmsAppId", "XIAOMI_XMS_APP_ID")
val baseApplicationId = "app.amber.agent"

// Build types that share the Amber UI/runtime contract — must each call
// `applyAmberUiBuildConfig(...)` in the `buildTypes {}` block below to set
// optional applicationIdSuffix + VERSION/XMS/Firebase fallback BuildConfig fields consistently.
// See the afterEvaluate parity check at file end for why.
val amberUiBuildTypes = setOf("notion")

// Populated by `applyAmberUiBuildConfig(...)` invocations; compared against
// `amberUiBuildTypes` after configuration.
val amberUiBuildConfigApplied = mutableSetOf<String>()
val googleServicesPackageByVariant = linkedMapOf(
    "release" to baseApplicationId,
    "debug" to "$baseApplicationId.debug",
    "notion" to baseApplicationId,
    "baseline" to "$baseApplicationId.debug",
)

fun googleServicesClient(packageName: String): Map<*, *>? = runCatching {
    val configFile = file("google-services.json")
    if (!configFile.exists()) return@runCatching null
    val root = JsonSlurper().parse(configFile) as? Map<*, *> ?: return@runCatching null
    val clients = root["client"] as? List<*> ?: return@runCatching null
    clients
        .mapNotNull { it as? Map<*, *> }
        .firstOrNull {
            val clientInfo = it["client_info"] as? Map<*, *>
            val androidClientInfo = clientInfo?.get("android_client_info") as? Map<*, *>
            androidClientInfo?.get("package_name") == packageName
        }
}.getOrNull()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    // (Previously) org.mozilla.rust-android-gradle.rust-android — REMOVED for
    // AGP 9 incompatibility (see document/build.gradle.kts header). Native
    // builds for libmarkdown_parser.so + libregex_transformer.so are driven
    // by hand-rolled Exec tasks below.
}

android {
    namespace = "app.amber.agent"
    compileSdk = 37

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = 392
        versionName = "2.6.1"

        testInstrumentationRunner = "app.amber.agent.AmberAgentAndroidTestRunner"
        manifestPlaceholders["xiaomiXmsAppId"] = xiaomiXmsAppId
        manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "false"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    // Amber UI buildType helper. Build types using the custom Amber UI resource
    // set call this so VERSION / XMS / Firebase fallback BuildConfig fields stay consistent.
    // An empty packageSuffix intentionally keeps the canonical applicationId.
    val applyAmberUiBuildConfig: com.android.build.api.dsl.ApplicationBuildType.(String) -> Unit = { packageSuffix ->
        amberUiBuildConfigApplied.add(name)
        applicationIdSuffix = packageSuffix.ifEmpty { null }
        buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
        buildConfigField("String", "VERSION_CODE", "\"${defaultConfig.versionCode}\"")
        buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
        buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
        buildConfigField("Boolean", "FIREBASE_LOCAL_FALLBACK_ALLOWED", "true")
        manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "true"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
            buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
            buildConfigField("Boolean", "FIREBASE_LOCAL_FALLBACK_ALLOWED", "false")
            manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "false"
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
            buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
            buildConfigField("Boolean", "FIREBASE_LOCAL_FALLBACK_ALLOWED", "true")
            manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "true"
        }
        create("notion") {
            initWith(getByName("debug"))
            matchingFallbacks.add("debug")
            applyAmberUiBuildConfig("")
        }
        create("baseline") {
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
            buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
            buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
            buildConfigField("Boolean", "FIREBASE_LOCAL_FALLBACK_ALLOWED", "true")
            manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "true"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // TD.Rust.1c — the HtmlDiffNormalizer (+ other native bridges)
        // unconditionally call android.util.Log on first use. Without this
        // flag, Log methods throw RuntimeExceptionNotMocked in JVM unit
        // tests. Returning defaults (no-op for void methods) keeps the
        // bridge's lazy-load path testable without Robolectric.
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        getByName("main").assets.srcDir("$buildDir/generated/assets/embeddedTerminalRuntime")
    }
    androidResources {
        generateLocaleConfig = true
        // Skip APK compression on bundled font files. Noto Serif SC OTF (~11.6MB) takes
        // a perceptible CPU hit per launch if AAPT2 deflates it; storing uncompressed
        // grows the APK ~10% but lets the framework mmap the font directly. JetBrains
        // Mono TTF benefits the same way.
        noCompress.add("otf")
        noCompress.add("ttf")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // TokenCounter is not wired into production; do not ship stale local builds.
            excludes += "**/libtokenizer.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    // Bring the cargo-built .so files into the APK's jniLibs. The Rust JNI
    // block below registers the cargo-ndk Exec tasks that populate this dir.
    // See docs/RUST_NATIVE_SPIKE_PLAN.md §2.3.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${layout.buildDirectory.get()}/rustJniLibs/android")
        }
    }
}

googleServicesPackageByVariant.forEach { (variant, packageName) ->
    tasks.matching { it.name == "process${variant.replaceFirstChar { it.uppercase() }}GoogleServices" }
        .configureEach {
            if (variant == "release") {
                doFirst("require release google-services client") {
                    if (googleServicesClient(packageName) == null) {
                        throw GradleException(
                            "Release builds require app/google-services.json to contain a Firebase client for $packageName."
                        )
                    }
                }
            } else {
                onlyIf("google-services.json contains a Firebase client for $packageName") {
                    googleServicesClient(packageName) != null
                }
            }
        }
}

// ---------------------------------------------------------------------------
// Rust JNI build — hand-rolled cargo-ndk invocations.
//
// Replaces the Mozilla rust-android-gradle plugin (incompatible with AGP 9 —
// see Codex Round 2 review). App-owned crates land .so files into
// `app/`'s jniLibs; document/highlight modules contribute their own native
// libraries via AAR merge.
// Output ABI: arm64-v8a only (matches `android.defaultConfig.ndk.abiFilters`
// + `splits.abi.include`). When a future Mozilla-plugin release supports
// AGP 9 + multiple crates per module, this block can be revisited.
// ---------------------------------------------------------------------------

val androidNdkHome: String =
    providers.environmentVariable("ANDROID_NDK_HOME").orNull
        ?: providers.gradleProperty("android.ndkPath").orNull
        ?: System.getProperty("android.ndk")
        ?: "${System.getenv("ANDROID_HOME") ?: System.getProperty("user.home") + "/Library/Android/sdk"}/ndk/27.0.12077973"

/** Build one Rust crate into `app/build/rustJniLibs/android/<abi>/lib<name>.so`. */
fun registerCargoBuild(taskName: String, crateDir: String, displayName: String) =
    tasks.register<Exec>(taskName) {
        group = "rust"
        description = "Build lib${displayName}.so for arm64-v8a via cargo-ndk"
        workingDir = file(crateDir)
        environment("ANDROID_NDK_HOME", androidNdkHome)
        commandLine = listOf(
            "cargo", "ndk",
            "-t", "arm64-v8a",
            "-o", file("${layout.buildDirectory.get()}/rustJniLibs/android").absolutePath,
            "build", "--release",
            "--manifest-path", file("$crateDir/Cargo.toml").absolutePath,
        )
        isIgnoreExitValue = false
        onlyIf {
            val cargoNdkAvailable = try {
                ProcessBuilder("cargo", "ndk", "--version").start().waitFor() == 0
            } catch (_: Throwable) { false }
            if (!cargoNdkAvailable) {
                logger.lifecycle("[rust] cargo-ndk not on PATH; skipping $displayName native build (JVM fallback will kick in at runtime)")
            }
            cargoNdkAvailable
        }
    }

val cargoBuildMarkdownParser =
    registerCargoBuild("cargoBuildMarkdownParser", "../native/markdown-parser", "markdown_parser")
val cargoBuildRegexTransformer =
    registerCargoBuild("cargoBuildRegexTransformer", "../native/regex-transformer", "regex_transformer")
val cargoBuildReaderExtractor =
    registerCargoBuild("cargoBuildReaderExtractor", "../native/reader-extractor", "reader_extractor")
val cargoBuildSyncCrypto =
    registerCargoBuild("cargoBuildSyncCrypto", "../native/sync-crypto", "sync_crypto")
val cargoBuildMarkdownPreprocess =
    registerCargoBuild("cargoBuildMarkdownPreprocess", "../native/markdown-preprocess", "markdown_preprocess")
val cargoBuildJsonExpr =
    registerCargoBuild("cargoBuildJsonExpr", "../native/json-expr", "json_expr")
val cargoBuildHtmlDiffNormalizer =
    registerCargoBuild("cargoBuildHtmlDiffNormalizer", "../native/html-diff-normalizer", "html_diff_normalizer")

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn(cargoBuildMarkdownParser)
        dependsOn(cargoBuildRegexTransformer)
        dependsOn(cargoBuildReaderExtractor)
        dependsOn(cargoBuildSyncCrypto)
        dependsOn(cargoBuildMarkdownPreprocess)
        dependsOn(cargoBuildJsonExpr)
        dependsOn(cargoBuildHtmlDiffNormalizer)
    }
}

val requiredRustSharedLibraries = listOf(
    "highlight_parser",
    "html_diff_normalizer",
    "json_expr",
    "markdown_parser",
    "markdown_preprocess",
    "office_parsers",
    "reader_extractor",
    "regex_transformer",
    "sync_crypto",
)

val forbiddenRustSharedLibraries = listOf(
    "tokenizer",
)

val rustNativeCheckedBuildTypes = setOf("release", "notion", "baseline")

afterEvaluate {
    rustNativeCheckedBuildTypes.forEach { buildTypeName ->
        val capitalized = buildTypeName.replaceFirstChar { it.uppercase() }
        val verifyTask = tasks.register("verify${capitalized}RustNativeLibs") {
            group = "verification"
            description = "Fail $buildTypeName APK builds when required Rust .so files are missing"
            dependsOn("package$capitalized")
            doLast {
                val apkDir = layout.buildDirectory.dir("outputs/apk/$buildTypeName").get().asFile
                val apks = apkDir.listFiles { file ->
                    file.isFile && file.extension.equals("apk", ignoreCase = true)
                }?.toList().orEmpty()
                if (apks.isEmpty()) {
                    throw GradleException("No APKs found in ${apkDir.absolutePath}; cannot verify Rust native libs")
                }
                val requiredEntries = requiredRustSharedLibraries.map { name ->
                    "lib/arm64-v8a/lib$name.so"
                }
                val forbiddenEntries = forbiddenRustSharedLibraries.map { name ->
                    "lib/arm64-v8a/lib$name.so"
                }
                val failures = buildList {
                    apks.forEach { apk ->
                        ZipFile(apk).use { zip ->
                            val missing = requiredEntries.filter { entry -> zip.getEntry(entry) == null }
                            if (missing.isNotEmpty()) {
                                add("${apk.name} missing ${missing.joinToString()}")
                            }
                            val forbidden = forbiddenEntries.filter { entry -> zip.getEntry(entry) != null }
                            if (forbidden.isNotEmpty()) {
                                add("${apk.name} contains removed native libs ${forbidden.joinToString()}")
                            }
                        }
                    }
                }
                if (failures.isNotEmpty()) {
                    throw GradleException(
                        buildString {
                            appendLine("Required Rust native libraries are missing from $buildTypeName APK output.")
                            failures.forEach { appendLine("- $it") }
                            append("Install cargo-ndk or fix the Rust JNI build before shipping this variant.")
                        }
                    )
                }
            }
        }
        tasks.named("assemble$capitalized").configure {
            dependsOn(verifyTask)
        }
    }
}

// ---------------------------------------------------------------------------
// Amber UI buildType parity check.
//
// Amber UI variants must each call `applyAmberUiBuildConfig(...)` so
// VERSION/XMS/Firebase fallback BuildConfig fields stay consistent with the canonical app
// shape. This afterEvaluate compares the declared set in [amberUiBuildTypes]
// against the helper call recorder [amberUiBuildConfigApplied]; any mismatch
// fails configure time.
// ---------------------------------------------------------------------------
afterEvaluate {
    val missing = amberUiBuildTypes - amberUiBuildConfigApplied
    check(missing.isEmpty()) {
        "These buildTypes are declared Amber UI variants but did NOT call applyAmberUiBuildConfig(): $missing.\n" +
            "    → Open app/build.gradle.kts, find the buildType {} block for each,\n" +
            "      and call applyAmberUiBuildConfig(\"\" or \".<suffix>\")."
    }
    val unexpected = amberUiBuildConfigApplied - amberUiBuildTypes
    check(unexpected.isEmpty()) {
        "These buildTypes called applyAmberUiBuildConfig() but are NOT in amberUiBuildTypes: $unexpected.\n" +
            "    → Either remove the call, or add each name to amberUiBuildTypes."
    }
}

fun downloadRuntimeFile(localPath: String, remoteUrl: String, expectedChecksum: String? = null) {
    val file = file(localPath)
    if (file.exists() && file.length() > 0L && expectedChecksum == null) return
    file.parentFile?.mkdirs()
    val digest = MessageDigest.getInstance("SHA-256")
    val connection = URI(remoteUrl).toURL().openConnection()
    connection.getInputStream().use { input ->
        file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val readBytes = input.read(buffer)
                if (readBytes < 0) break
                output.write(buffer, 0, readBytes)
                digest.update(buffer, 0, readBytes)
            }
        }
    }
    var checksum = BigInteger(1, digest.digest()).toString(16)
    while (checksum.length < 64) checksum = "0$checksum"
    if (expectedChecksum != null && checksum != expectedChecksum) {
        file.delete()
        throw GradleException(
            "Wrong checksum for $remoteUrl:\nExpected: $expectedChecksum\nActual:   $checksum"
        )
    }
}

val prepareEmbeddedTerminalRuntime by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/assets/embeddedTerminalRuntime/embedded-terminal-runtime")
    outputs.dir(outputDir)
    doLast {
        val root = outputDir.get().asFile
        root.mkdirs()
        downloadRuntimeFile(
            localPath = root.resolve("proot").absolutePath,
            remoteUrl = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/aarch64/proot"
        )
        downloadRuntimeFile(
            localPath = root.resolve("libtalloc.so.2").absolutePath,
            remoteUrl = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/aarch64/libtalloc.so.2"
        )
        downloadRuntimeFile(
            localPath = root.resolve("alpine.tar.gz").absolutePath,
            remoteUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
        )
    }
}

tasks.named("preBuild") {
    dependsOn(prepareEmbeddedTerminalRuntime)
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.profileinstaller)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.config)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ucrop
    implementation(libs.ucrop)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":tts"))
    implementation(project(":common"))
    implementation(project(":core:app-infra"))
    implementation(project(":core:model"))
    implementation(project(":core:settings"))
    implementation(project(":core:event"))
    implementation(project(":core:usage"))
    implementation(project(":core:llm"))
    implementation(project(":core:agent-runtime-impl"))
    implementation(project(":core:agent-runtime"))
    implementation(project(":core:agent-store-room"))
    implementation(project(":feature:deepread:api"))
    implementation(project(":feature:chat:api"))
    implementation(project(":feature:terminal:api"))
    implementation(project(":feature:board:api"))
    implementation(project(":feature:live:api"))
    implementation(project(":feature:modelcouncil:api"))
    implementation(project(":feature:office:api"))
    implementation(project(":feature:subagent:api"))
    implementation(project(":core:ai-prompts"))
    implementation(project(":core:memory:api"))
    implementation(project(":core:sync:api"))
    implementation(project(":core:context:api"))
    implementation(project(":core:ai:api"))
    implementation(project(":core:ai:transformers:api"))
    implementation(project(":core:ai:generation:api"))
    implementation(project(":feature:subagent"))
    implementation(project(":core:automation:api"))
    implementation(project(":feature:tools:impl"))
    implementation(project(":feature:board:impl"))
    implementation(project(":feature:runtime:api"))
    implementation(project(":feature:tools:api"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:modelcouncil"))
    implementation(project(":feature:tools:access"))
    implementation(project(":feature:system"))
    implementation(project(":feature:history"))
    implementation(project(":feature:webview"))
    implementation(project(":feature:task"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:icloud"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
