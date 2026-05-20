import groovy.json.JsonSlurper
import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

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
val baseApplicationId = "me.rerere.amberagent"

// Build types that share the notion UI theme contract — must each call
// `applyNotionLikeUi(...)` in the `buildTypes {}` block below. See the
// afterEvaluate parity check at file end for why.
val notionLikeBuildTypes = setOf("notion", "refactortest")

// Populated by `applyNotionLikeUi(...)` invocations; compared against
// `notionLikeBuildTypes` after configuration.
val notionLikeApplied = mutableSetOf<String>()

fun googleOAuthConfigured(packageName: String): Boolean = runCatching {
    val configFile = file("google-services.json")
    if (!configFile.exists()) return@runCatching false
    val root = JsonSlurper().parse(configFile) as? Map<*, *> ?: return@runCatching false
    val clients = root["client"] as? List<*> ?: return@runCatching false
    val client = clients
        .mapNotNull { it as? Map<*, *> }
        .firstOrNull {
            val clientInfo = it["client_info"] as? Map<*, *>
            val androidClientInfo = clientInfo?.get("android_client_info") as? Map<*, *>
            androidClientInfo?.get("package_name") == packageName
        } ?: return@runCatching false
    val oauthClients = client["oauth_client"] as? List<*> ?: return@runCatching false
    val clientTypes = oauthClients
        .mapNotNull { it as? Map<*, *> }
        .mapNotNull { it["client_type"]?.toString() }
        .toSet()
    "1" in clientTypes && "3" in clientTypes
}.getOrDefault(false)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    // Rust JNI integration for the markdown-parser crate. Stages
    // libmarkdown_parser.so into the APK's jniLibs. See
    // docs/RUST_NATIVE_SPIKE_PLAN.md §2.3 and the matching block in
    // document/build.gradle.kts.
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6"
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = 383
        versionName = "2.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Notion-like buildTypes share the same UI theme contract (notion-tuned
    // palette + typography + container shapes — see Theme.kt:159, Color.kt
    // and SettingDisplayPage.kt). Any buildType that should LOOK like notion
    // MUST call this helper to populate UI-critical BuildConfig flags.
    //
    // The helper records each invocation into [notionLikeApplied]. After
    // configuration, the parity check below (afterEvaluate at file scope)
    // compares that set against [notionLikeBuildTypes] (the declared
    // source-of-truth set) and fails the build if any buildType is missing
    // a call or if any buildType called the helper without being declared.
    // (Lesson from commit 45a3da4b — refactortest originally hand-wrote
    // NOTION_LIKE=false and tipped over visually on device 3B164901CEF00000.)
    val applyNotionLikeUi: com.android.build.api.dsl.ApplicationBuildType.(String) -> Unit = { packageSuffix ->
        notionLikeApplied.add(name)
        applicationIdSuffix = packageSuffix
        buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
        buildConfigField("String", "VERSION_CODE", "\"${defaultConfig.versionCode}\"")
        buildConfigField("Boolean", "NOTION_LIKE", "true")
        buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
        buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
        buildConfigField("Boolean", "GOOGLE_OAUTH_CONFIGURED", googleOAuthConfigured("$baseApplicationId$packageSuffix").toString())
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
            buildConfigField("Boolean", "NOTION_LIKE", "false")
            buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
            buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
            buildConfigField("Boolean", "GOOGLE_OAUTH_CONFIGURED", googleOAuthConfigured(baseApplicationId).toString())
            manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "false"
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            buildConfigField("Boolean", "NOTION_LIKE", "false")
            buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
            buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
            buildConfigField("Boolean", "GOOGLE_OAUTH_CONFIGURED", googleOAuthConfigured("$baseApplicationId.debug").toString())
            manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "true"
        }
        create("notion") {
            initWith(getByName("debug"))
            matchingFallbacks.add("debug")
            applyNotionLikeUi(".notion")
        }
        create("refactortest") {
            // Dedicated buildType for sanity-installing the refactor/p1-godclass
            // branch alongside the user's regular notion install. Different
            // applicationId → Android treats them as two separate apps;
            // separate data dir, no preference / Room DB collision.
            //
            // Calls applyNotionLikeUi() so UI parity with notion is by
            // construction — flipping any UI-critical flag here in isolation
            // would diverge the visual surface and break sanity testing.
            initWith(getByName("debug"))
            matchingFallbacks.add("debug")
            applyNotionLikeUi(".refactortest")
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
            buildConfigField("Boolean", "NOTION_LIKE", "false")
            buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
            buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
            buildConfigField("Boolean", "GOOGLE_OAUTH_CONFIGURED", googleOAuthConfigured("$baseApplicationId.debug").toString())
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

    // Bring the cargo-built .so files (libmarkdown_parser.so) into the APK's
    // jniLibs. See cargo { } block below + docs/RUST_NATIVE_SPIKE_PLAN.md §2.3.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${layout.buildDirectory.get()}/rustJniLibs/android")
        }
    }
}

cargo {
    module = "../native/markdown-parser"
    libname = "markdown_parser"
    targets = listOf("arm64", "arm", "x86_64")
    profile = "release"
    apiLevel = 26
    // extraCargoBuildArguments removed (P3 sweep) — `module` already points
    // at the markdown-parser crate root, so --package is redundant.
    targetDirectory = "../native/target"
    verbose = true
}

// Second Rust crate (`regex-transformer`) — the Mozilla rust-android plugin
// only supports ONE `cargo {}` block per Android module, so we hand-roll a
// `cargo ndk` invocation that mirrors what the plugin does for markdown-parser
// and stages the resulting .so files alongside it in build/rustJniLibs/android.
// Cross-component review V2 P1 fix.
val androidNdkHome: String =
    providers.environmentVariable("ANDROID_NDK_HOME").orNull
        ?: providers.gradleProperty("android.ndkPath").orNull
        ?: System.getProperty("android.ndk")
        ?: "${System.getenv("ANDROID_HOME") ?: System.getProperty("user.home") + "/Library/Android/sdk"}/ndk/27.0.12077973"

val cargoBuildRegexTransformer = tasks.register<Exec>("cargoBuildRegexTransformer") {
    group = "rust"
    description = "Build libregex_transformer.so for arm64-v8a, armeabi-v7a, x86_64 via cargo-ndk"
    workingDir = file("../native/regex-transformer")
    environment("ANDROID_NDK_HOME", androidNdkHome)
    commandLine = listOf(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", file("${layout.buildDirectory.get()}/rustJniLibs/android").absolutePath,
        "build", "--release",
        "--manifest-path", file("../native/regex-transformer/Cargo.toml").absolutePath,
    )
    // Optional: skip silently if cargo-ndk isn't installed so CI without Rust
    // doesn't blow up unrelated builds. Adapter will see no .so and fall back
    // to JVM at runtime — same behaviour as if the build succeeded but the
    // device's ABI wasn't bundled.
    isIgnoreExitValue = false
    onlyIf {
        val cargoNdkAvailable = try {
            ProcessBuilder("cargo", "ndk", "--version").start().waitFor() == 0
        } catch (_: Throwable) { false }
        if (!cargoNdkAvailable) {
            logger.lifecycle("[rust] cargo-ndk not on PATH; skipping regex-transformer native build (JVM fallback will kick in at runtime)")
        }
        cargoNdkAvailable
    }
}

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn("cargoBuild")
        dependsOn(cargoBuildRegexTransformer)
    }
}

// ---------------------------------------------------------------------------
// BuildConfig parity check — keeps notion-like buildTypes visually identical.
//
// `notion` is the canonical buildType driving the visible UI (notion-tuned
// palette + typography + container shapes). Any sanity / dogfood variant
// that should LOOK like notion must call `applyNotionLikeUi(...)` in the
// `buildTypes { }` block above. The helper sets NOTION_LIKE=true (and the
// rest of the UI-critical flags) and records the call in [notionLikeApplied].
//
// After configuration, this afterEvaluate compares [notionLikeApplied]
// (who actually called the helper) against [notionLikeBuildTypes] (who is
// declared to be notion-like). Any mismatch fails the build at configure
// time — before any APK can ship.
//
// Lesson from commit 45a3da4b — `refactortest` originally hand-wrote
// `NOTION_LIKE = "false"` and tipped over visually on device 3B164901CEF00000.
// ---------------------------------------------------------------------------
afterEvaluate {
    val missing = notionLikeBuildTypes - notionLikeApplied
    check(missing.isEmpty()) {
        "These buildTypes are declared notion-like but did NOT call applyNotionLikeUi(): $missing.\n" +
            "    → Open app/build.gradle.kts, find the buildType {} block for each,\n" +
            "      and call applyNotionLikeUi(\".<suffix>\") so UI-critical flags get set.\n" +
            "    → Or, if the buildType should not actually be notion-like, remove it from notionLikeBuildTypes."
    }
    val unexpected = notionLikeApplied - notionLikeBuildTypes
    check(unexpected.isEmpty()) {
        "These buildTypes called applyNotionLikeUi() but are NOT in notionLikeBuildTypes: $unexpected.\n" +
            "    → Either remove the call (the buildType shouldn't look like notion),\n" +
            "      or add each name to the notionLikeBuildTypes set so the contract is documented."
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
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":tts"))
    implementation(project(":common"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
