import groovy.json.JsonSlurper
import com.android.build.api.dsl.Packaging
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
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
val uploadCrashlyticsMapping = projectProperty("uploadCrashlyticsMapping", "UPLOAD_CRASHLYTICS_MAPPING")
    .ifBlank { "true" }
    .toBooleanStrict()
val baseApplicationId = "app.amber.agent"
val graphiteDebugApplicationIdSuffix = ".graphite"
val chatDeviceFixtureEnabled =
    project.findProperty("chatDeviceFixture")?.toString()?.toBoolean() == true
// Phase-2 diagnostics: independent of BuildConfig.DEBUG. Default OFF; both
// sides of a comparison must pass the same value.
val chatPerfTraceEnabled =
    project.findProperty("chatPerfTrace")?.toString()?.toBoolean() == true
// Opt-in independent fixture host package; never selected by default builds.
val chatPerfFixtureEnabled =
    project.findProperty("chatPerfFixture")?.toString()?.toBoolean() == true
// -PprofileableDebug=true builds the debug variant non-debuggable and profileable. It installs over
// the existing `.graphite` debug build (same package, same signature, data kept), so frame timing
// and Baseline Profile collection run on real conversations without debuggable-ART overhead.
val profileableDebugEnabled =
    project.findProperty("profileableDebug")?.toString()?.toBoolean() == true

// Build types that share the Amber UI/runtime contract — must each call
// `applyAmberUiBuildConfig(...)` in the `buildTypes {}` block below to set
// optional applicationIdSuffix + VERSION/XMS/Firebase/OAuth BuildConfig fields consistently.
// See the afterEvaluate parity check at file end for why.
val amberUiBuildTypes = mutableSetOf("graphite")
if (chatPerfFixtureEnabled) amberUiBuildTypes += "perffixture"

// Populated by `applyAmberUiBuildConfig(...)` invocations; compared against
// `amberUiBuildTypes` after configuration.
val amberUiBuildConfigApplied = mutableSetOf<String>()
// Per-variant Firebase client lookup. With the `surface` flavor dimension the
// process<Variant>GoogleServices task name carries the flavor (e.g.
// processNovelDebugGoogleServices) and the expected package = flavor
// applicationId + buildType applicationIdSuffix. Standalone flavors can drop
// their own client json into app/src/<flavor>/google-services.json; until then
// their process tasks skip exactly like the graphite debug variant already does.
val surfaceFlavorApplicationIds = linkedMapOf(
    "full" to baseApplicationId,
    "novel" to "app.amber.novel",
    "deepread" to "app.amber.deepread",
)
val buildTypeApplicationIdSuffixes = mapOf(
    "debug" to graphiteDebugApplicationIdSuffix,
    "baseline" to ".debug",
    "perffixture" to ".perffixture",
)

fun googleServicesPackageFor(flavorName: String?, buildTypeName: String?): String =
    surfaceFlavorApplicationIds[flavorName.orEmpty()].orEmpty() +
        buildTypeApplicationIdSuffixes[buildTypeName].orEmpty()

fun googleServicesFiles(variant: String): List<File> = listOf(
    file("src/$variant/google-services.json"),
    file("google-services.json"),
)

fun googleServicesClient(packageName: String, variant: String): Map<*, *>? =
    googleServicesFiles(variant).firstNotNullOfOrNull { configFile ->
        googleServicesClientFrom(configFile, packageName)
    }

/** Variant-aware client lookup: accepts src/<variant>/, src/<flavor>/ and
 * src/<buildType>/ json locations in addition to the module root file. */
fun googleServicesClientForVariant(
    packageName: String,
    variantName: String,
    flavorName: String,
    buildTypeName: String,
): Map<*, *>? = listOf(
    file("src/$variantName/google-services.json"),
    file("src/$flavorName/google-services.json"),
    file("src/$buildTypeName/google-services.json"),
    file("google-services.json"),
).firstNotNullOfOrNull { configFile ->
    googleServicesClientFrom(configFile, packageName)
}

fun googleServicesClientFrom(configFile: File, packageName: String): Map<*, *>? = runCatching {
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

fun googleOAuthConfigured(packageName: String, variant: String): Boolean {
    val client = googleServicesClient(packageName, variant) ?: return false
    val oauthClients = client["oauth_client"] as? List<*> ?: return false
    return oauthClients
        .mapNotNull { it as? Map<*, *> }
        .any { oauthClient ->
            oauthClient["client_type"]?.toString() == "1" &&
                oauthClient["client_id"]?.toString().orEmpty().isNotBlank()
        }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.baselineprofile)
    // (Previously) org.mozilla.rust-android-gradle.rust-android — REMOVED for
    // AGP 9 incompatibility (see document/build.gradle.kts header). Native
    // builds for libmarkdown_parser.so + libregex_transformer.so are driven
    // by hand-rolled Exec tasks below.
}

android {
    namespace = "app.amber.agent"
    compileSdk = 37
    // Opt-in device verification against the same package/signature as the installed build.
    testBuildType = if (chatPerfFixtureEnabled) "perffixture" else providers.gradleProperty("deviceTestBuildType").getOrElse("debug")
    defaultConfig {
        applicationId = baseApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = 396
        versionName = "2.6.8"

        buildConfigField("boolean", "CHAT_PERF_TRACE", chatPerfTraceEnabled.toString())
        manifestPlaceholders["chatPerfTrace"] = chatPerfTraceEnabled.toString()
        testInstrumentationRunner = if (chatPerfFixtureEnabled) {
            // Fixture-only runner; never changes the default suites' runner.
            "app.amber.agent.perffixture.PerfFixtureAndroidTestRunner"
        } else if (chatDeviceFixtureEnabled) {
            "app.amber.agent.ChatDeviceFixtureRunner"
        } else if (project.findProperty("uiSmokeTest")?.toString()?.toBoolean() == true) {
            "app.amber.agent.AmberAgentUiSmokeTestRunner"
        } else {
            "app.amber.agent.AmberAgentAndroidTestRunner"
        }
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

    // Standalone product split — mirrors the iOS AmberNovel / AmberDeepRead
    // targets. One codebase, three installable products; each standalone flavor
    // gets its own applicationId + sandbox and gates its start surface via
    // StandaloneSurfaces (BuildConfig.STANDALONE_SURFACE).
    flavorDimensions += "surface"
    productFlavors {
        create("full") {
            dimension = "surface"
            // Canonical Amber product — inherits defaultConfig.applicationId.
            buildConfigField("String", "STANDALONE_SURFACE", "\"full\"")
        }
        create("novel") {
            dimension = "surface"
            applicationId = "app.amber.novel"
            buildConfigField("String", "STANDALONE_SURFACE", "\"novel\"")
        }
        create("deepread") {
            dimension = "surface"
            applicationId = "app.amber.deepread"
            buildConfigField("String", "STANDALONE_SURFACE", "\"deepread\"")
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
    // set call this so VERSION / XMS / Firebase fallback / OAuth BuildConfig fields stay consistent.
    // An empty packageSuffix intentionally keeps the canonical applicationId.
    val applyAmberUiBuildConfig: com.android.build.api.dsl.ApplicationBuildType.(String) -> Unit = { packageSuffix ->
        amberUiBuildConfigApplied.add(name)
        applicationIdSuffix = packageSuffix.ifEmpty { null }
        buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
        buildConfigField("String", "VERSION_CODE", "\"${defaultConfig.versionCode}\"")
        buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
        buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
        buildConfigField("Boolean", "FIREBASE_LOCAL_FALLBACK_ALLOWED", "true")
        buildConfigField(
            "Boolean",
            "GOOGLE_OAUTH_CONFIGURED",
            googleOAuthConfigured("$baseApplicationId$packageSuffix", name).toString(),
        )
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
            buildConfigField("Boolean", "GOOGLE_OAUTH_CONFIGURED", googleOAuthConfigured(baseApplicationId, name).toString())
            (this as ExtensionAware).extensions.configure<CrashlyticsExtension>("firebaseCrashlytics") {
                mappingFileUploadEnabled = uploadCrashlyticsMapping
            }
            manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "false"
        }
        debug {
            // Graphite redesign build — distinct applicationId so it installs ALONGSIDE the
            // existing `app.amber.agent` (main) without overwriting it.
            applicationIdSuffix = graphiteDebugApplicationIdSuffix
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            buildConfigField("Boolean", "XIAOMI_XMS_APP_ID_CONFIGURED", xiaomiXmsAppId.isNotBlank().toString())
            buildConfigField("String", "XIAOMI_XMS_APP_ID", "\"${xiaomiXmsAppId.asBuildConfigString()}\"")
            buildConfigField("Boolean", "FIREBASE_LOCAL_FALLBACK_ALLOWED", "true")
            buildConfigField(
                "Boolean",
                "GOOGLE_OAUTH_CONFIGURED",
                googleOAuthConfigured("$baseApplicationId$graphiteDebugApplicationIdSuffix", name).toString(),
            )
            manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "true"
            if (profileableDebugEnabled) {
                isDebuggable = false
                isProfileable = true
            }
        }
        create("graphite") {
            initWith(getByName("debug"))
            matchingFallbacks.add("debug")
            applyAmberUiBuildConfig("")
        }
        if (chatPerfFixtureEnabled) {
            create("perffixture") {
                initWith(getByName("debug"))
                matchingFallbacks.add("debug")
                isDebuggable = false
                isProfileable = true
                // Full flavor applicationId + this suffix = app.amber.agent.perffixture.
                applyAmberUiBuildConfig(".perffixture")
            }
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
            buildConfigField(
                "Boolean",
                "GOOGLE_OAUTH_CONFIGURED",
                googleOAuthConfigured("$baseApplicationId.debug", name).toString(),
            )
            manifestPlaceholders["xiaomiXmsBuildTypeDebug"] = "true"
        }
        // The plugin uses these release-derived variants on device; debug signing
        // keeps them buildable without the distribution keystore.
        // The plugin skips initWith(release) for build types that already exist.
        create("benchmarkRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            (this as ExtensionAware).extensions.configure<CrashlyticsExtension>("firebaseCrashlytics") {
                mappingFileUploadEnabled = false
            }
        }
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            (this as ExtensionAware).extensions.configure<CrashlyticsExtension>("firebaseCrashlytics") {
                mappingFileUploadEnabled = false
            }
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
        // Robolectric Compose UI tests need real resources on the JVM classpath.
        unitTests.isIncludeAndroidResources = true
        // TD.Rust.1a — pass the snapshot-regen flag through to the test JVM so
        // MarkdownRendererSnapshotTest can WRITE goldens instead of asserting.
        // Gradle -P props are project props, not JVM system props, so forward it
        // explicitly: ./gradlew :app:testDebugUnitTest -PupdateMarkdownSnapshots=true
        unitTests.all {
            it.systemProperty(
                "updateMarkdownSnapshots",
                (project.findProperty("updateMarkdownSnapshots") as? String) ?: "false",
            )
            // Replay goldens (test-fixtures/replay/v1): same regen pattern —
            // ./gradlew :app:testDebugUnitTest --tests "*ReplayGoldenTest" -PupdateGoldens=true
            it.systemProperty(
                "updateGoldens",
                (project.findProperty("updateGoldens") as? String) ?: "false",
            )
            it.systemProperty("goldenDir", rootProject.file("test-fixtures").absolutePath)
        }
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        getByName("main") {
            assets.srcDir("$buildDir/generated/assets/embeddedTerminalRuntime")
        }
        // Debug source set so default fullDebug app + androidTest resolve the fixture ABI without a property; never main/release.
        getByName("debug") {
            java.srcDir("src/chatDeviceFixture/java")
            kotlin.srcDir("src/chatDeviceFixture/java")
        }
        if (chatPerfFixtureEnabled) {
            getByName("perffixture") {
                java.srcDir("src/chatDeviceFixture/java")
                kotlin.srcDir("src/chatDeviceFixture/java")
            }
            getByName("androidTest") {
                java.srcDir("src/androidTestPerffixture/java")
                kotlin.srcDir("src/androidTestPerffixture/java")
                assets.srcDir("src/androidTestPerffixture/assets")
            }
        }
        if (chatDeviceFixtureEnabled) {
            getByName("baseline") {
                java.srcDir("src/chatDeviceFixture/java")
                kotlin.srcDir("src/chatDeviceFixture/java")
            }
            getByName("benchmarkRelease") {
                java.srcDir("src/chatDeviceFixture/java")
                kotlin.srcDir("src/chatDeviceFixture/java")
            }
        }
        getByName("test") {
            // Shared iOS/Android golden fixtures live at repo-root test-fixtures/
            // (same wiring as :feature:novel).
            resources.srcDir(rootProject.file("test-fixtures"))
        }
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
            // proot execve() needs the loader in the installer's executable
            // nativeLibraryDir. Android 10+ denies exec from writable app data.
            // Compressed JNI is extracted at install time; 16 KB ELF alignment
            // is still required and checked for every packaged library below.
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

    // Bring the cargo-built .so files into the APK's jniLibs. The Rust JNI
    // block below registers the cargo-ndk Exec tasks that populate this dir.
    // See docs/RUST_NATIVE_SPIKE_PLAN.md §2.3.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${layout.buildDirectory.get()}/rustJniLibs/android")
        }
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

androidComponents {
    // Prune the variant matrix: standalone flavors only need debug (local
    // testing), graphite (canonical applicationId install) and release
    // (distribution). The full flavor keeps every existing build type.
    beforeVariants { variant ->
        if (variant.flavorName != "full" &&
            variant.buildType !in setOf("debug", "graphite", "release")
        ) {
            variant.enable = false
        }
    }
    onVariants { variant ->
        val flavorName = variant.flavorName.orEmpty()
        val buildTypeName = variant.buildType.orEmpty()
        val packageName = googleServicesPackageFor(flavorName, buildTypeName)
        val taskName = "process${variant.name.replaceFirstChar { it.uppercase() }}GoogleServices"
        tasks.matching { it.name == taskName }.configureEach {
            if (flavorName == "full" && buildTypeName == "release") {
                doFirst("require release google-services client") {
                    if (googleServicesClientForVariant(packageName, variant.name, flavorName, buildTypeName) == null) {
                        throw GradleException(
                            "Release builds require app/src/$flavorName/google-services.json or app/google-services.json " +
                                "to contain a Firebase client for $packageName."
                        )
                    }
                }
            } else {
                onlyIf("google-services.json contains a Firebase client for $packageName") {
                    googleServicesClientForVariant(packageName, variant.name, flavorName, buildTypeName) != null
                }
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
        inputs.file(rootProject.file("native/.cargo/config.toml"))
        inputs.file(rootProject.file("native/Cargo.lock"))
        inputs.files(fileTree(crateDir) { include("**/*.rs", "Cargo.toml") })
        commandLine = listOf(
            "cargo", "ndk",
            "-t", "arm64-v8a",
            "rustc", "--release", "--locked", "--lib", "--verbose",
            "--target-dir", rootProject.file("native/target").absolutePath,
            "--", "-C", "link-arg=-Wl,-z,max-page-size=16384",
            "-C", "link-arg=-Wl,-z,common-page-size=16384",
        )
        doLast {
            // cargo-ndk -o copies EVERY workspace .so, including stale artifacts
            // belonging to document/highlight. Publish only this task's library.
            copy {
                from(rootProject.file("native/target/aarch64-linux-android/release/lib$displayName.so"))
                into(layout.buildDirectory.dir("rustJniLibs/android/arm64-v8a"))
            }
            file("${layout.buildDirectory.get()}/rustJniLibs/android/arm64-v8a").listFiles()
                ?.filter { it.name in setOf("liboffice_parsers.so", "libhighlight_parser.so") }
                ?.forEach { it.delete() }
        }
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
val cargoBuildHtmlDiffNormalizer =
    registerCargoBuild("cargoBuildHtmlDiffNormalizer", "../native/html-diff-normalizer", "html_diff_normalizer")

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn(cargoBuildMarkdownParser)
        dependsOn(cargoBuildRegexTransformer)
        dependsOn(cargoBuildReaderExtractor)
        dependsOn(cargoBuildSyncCrypto)
        dependsOn(cargoBuildMarkdownPreprocess)
        dependsOn(cargoBuildHtmlDiffNormalizer)
    }
}

val requiredRustSharedLibraries = listOf(
    "highlight_parser",
    "html_diff_normalizer",
    "markdown_parser",
    "markdown_preprocess",
    "office_parsers",
    "reader_extractor",
    "regex_transformer",
    "sync_crypto",
)

val rustNativeCheckedBuildTypes = setOf("release", "graphite", "baseline")
val pageSizeCheckedBuildTypes = setOf("debug", "release", "graphite", "baseline")

// Flavor-aware variant matrix: novel/deepread have no `baseline` variant
// (pruned in androidComponents.beforeVariants above).
fun variantSlugsFor(buildTypes: Set<String>) = surfaceFlavorApplicationIds.keys.flatMap { flavorName ->
    buildTypes
        .filter { buildTypeName -> flavorName == "full" || buildTypeName != "baseline" }
        .map { buildTypeName -> flavorName to buildTypeName }
}

val rustCheckedVariantSlugs = variantSlugsFor(rustNativeCheckedBuildTypes)
val pageSizeCheckedVariantSlugs = variantSlugsFor(pageSizeCheckedBuildTypes)

fun apkOutputs(flavorName: String, buildTypeName: String): List<File> {
    val apkDir = layout.buildDirectory.dir("outputs/apk/$flavorName/$buildTypeName").get().asFile
    return apkDir.listFiles { file ->
        file.isFile && file.extension.equals("apk", ignoreCase = true)
    }?.toList().orEmpty()
}

fun leU16(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)

fun leU32(buf: ByteArray, offset: Int): Long =
    (buf[offset].toLong() and 0xffL) or
        ((buf[offset + 1].toLong() and 0xffL) shl 8) or
        ((buf[offset + 2].toLong() and 0xffL) shl 16) or
        ((buf[offset + 3].toLong() and 0xffL) shl 24)

fun leU64(buf: ByteArray, offset: Int): Long =
    leU32(buf, offset) or (leU32(buf, offset + 4) shl 32)

fun RandomAccessFile.readU16Le(): Int {
    val lo = readUnsignedByte()
    val hi = readUnsignedByte()
    return lo or (hi shl 8)
}

fun RandomAccessFile.readU32Le(): Long {
    val lo = readU16Le().toLong()
    val hi = readU16Le().toLong()
    return lo or (hi shl 16)
}

fun zipLocalDataOffsets(apk: File): Map<String, Long> {
    val offsets = mutableMapOf<String, Long>()
    RandomAccessFile(apk, "r").use { raf ->
        val length = raf.length()
        check(length >= 22L) { "${apk.name} is too small to be a zip" }
        var eocd = length - 22L
        var found = false
        while (eocd >= 0L && length - eocd <= 22L + 0xffffL) {
            raf.seek(eocd)
            if (raf.readInt() == 0x504b0506) {
                found = true
                break
            }
            eocd--
        }
        check(found) { "${apk.name} is missing a zip EOCD record" }
        raf.seek(eocd + 10)
        val cdCount = raf.readU16Le()
        raf.seek(eocd + 16)
        val cdOffset = raf.readU32Le()
        raf.seek(cdOffset)
        repeat(cdCount) {
            val sig = raf.readInt()
            check(sig == 0x504b0102) { "${apk.name} has a corrupt zip central directory" }
            raf.skipBytes(24)
            val nameLen = raf.readU16Le()
            val extraLen = raf.readU16Le()
            val commentLen = raf.readU16Le()
            raf.skipBytes(8)
            val localHeaderOffset = raf.readU32Le()
            val nameBytes = ByteArray(nameLen)
            raf.readFully(nameBytes)
            raf.skipBytes(extraLen + commentLen)
            val name = String(nameBytes, Charsets.UTF_8)
            if (!name.startsWith("lib/") || !name.endsWith(".so")) return@repeat
            val here = raf.filePointer
            raf.seek(localHeaderOffset)
            check(raf.readInt() == 0x504b0304) { "${apk.name} local header mismatch for $name" }
            raf.skipBytes(22)
            val localNameLen = raf.readU16Le()
            val localExtraLen = raf.readU16Le()
            offsets[name] = localHeaderOffset + 30L + localNameLen + localExtraLen
            raf.seek(here)
        }
    }
    return offsets
}

fun elfLoadIssues(entryName: String, bytes: ByteArray): List<String> {
    if (bytes.size < 64 ||
        bytes[0] != 0x7f.toByte() ||
        bytes[1] != 'E'.code.toByte() ||
        bytes[2] != 'L'.code.toByte() ||
        bytes[3] != 'F'.code.toByte()
    ) {
        return listOf("$entryName: not an ELF file")
    }
    if (bytes[4] != 2.toByte()) return listOf("$entryName: not ELF64")
    if (bytes[5] != 1.toByte()) return listOf("$entryName: not little-endian ELF")
    val type = leU16(bytes, 16)
    // The proot loader is an execve stub, not a dlopen library. It is the only
    // allowed ET_EXEC, and must pass the same LOAD alignment checks as JNI.
    val prootLoader = entryName == "lib/arm64-v8a/libproot-loader.so"
    if (type != 3 && !(prootLoader && type == 2)) return listOf("$entryName: unexpected ELF type $type")
    val phoff = leU64(bytes, 32)
    val phentsize = leU16(bytes, 54)
    val phnum = leU16(bytes, 56)
    if (phoff < 0L || phentsize < 56 || phnum <= 0) return listOf("$entryName: invalid program headers")
    val issues = mutableListOf<String>()
    var loadCount = 0
    for (i in 0 until phnum) {
        val off = phoff + i.toLong() * phentsize
        if (off < 0L || off + 56L > bytes.size) {
            return listOf("$entryName: truncated program headers")
        }
        val start = off.toInt()
        if (leU32(bytes, start).toInt() != 1) continue // PT_LOAD
        loadCount++
        val align = leU64(bytes, start + 48)
        if (align < 16384L) {
            issues += "$entryName: LOAD p_align=$align (< 16384)"
        }
        if (leU64(bytes, start + 8) % 16384L != leU64(bytes, start + 16) % 16384L) {
            issues += "$entryName: LOAD file/virtual offsets are not congruent modulo 16384"
        }
    }
    if (loadCount == 0) issues += "$entryName: no PT_LOAD segments"
    return issues
}

fun verifyApk16KbPageSize(apk: File): List<String> {
    val issues = mutableListOf<String>()
    val dataOffsets = zipLocalDataOffsets(apk)
    ZipFile(apk).use { zip ->
        val soEntries = zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .toList()
        if (soEntries.none { it.name == "lib/arm64-v8a/libproot-loader.so" }) {
            issues += "Missing installer-extracted proot loader"
        }
        soEntries.forEach { entry ->
            if (entry.method == ZipEntry.STORED) {
                val offset = dataOffsets[entry.name]
                if (offset == null) {
                    issues += "${entry.name}: missing zip local-header offset"
                } else if (offset % 16384L != 0L) {
                    issues += "${entry.name}: zip data offset $offset is not 16 KB aligned"
                }
            }
            if (entry.method != ZipEntry.STORED &&
                (entry.method != ZipEntry.DEFLATED || android.packaging.jniLibs.useLegacyPackaging != true)
            ) {
                issues += "${entry.name}: compressed library requires installer extraction"
            }
            zip.getInputStream(entry).use { input ->
                issues += elfLoadIssues(entry.name, input.readBytes())
            }
        }
        // These are copied to disk and launched through the Android linker.
        // Asset packaging must not hide 4 KB ELF payloads from verification.
        listOf("proot", "libtalloc.so.2").forEach { name ->
            val entryName = "assets/embedded-terminal-runtime/$name"
            val entry = zip.getEntry(entryName)
            if (entry == null) issues += "Missing terminal runtime ELF: $entryName"
            else zip.getInputStream(entry).use { issues += elfLoadIssues(entryName, it.readBytes()) }
        }
    }
    return issues
}

afterEvaluate {
    rustCheckedVariantSlugs.forEach { (flavorName, buildTypeName) ->
        val variantSlug = "$flavorName${buildTypeName.replaceFirstChar { it.uppercase() }}"
        val capitalized = variantSlug.replaceFirstChar { it.uppercase() }
        val verifyTask = tasks.register("verify${capitalized}RustNativeLibs") {
            group = "verification"
            description = "Fail $variantSlug APK builds when required Rust .so files are missing"
            dependsOn("package$capitalized")
            doLast {
                val apks = apkOutputs(flavorName, buildTypeName)
                if (apks.isEmpty()) {
                    throw GradleException("No APKs found for $variantSlug; cannot verify Rust native libs")
                }
                val requiredEntries = requiredRustSharedLibraries.map { name ->
                    "lib/arm64-v8a/lib$name.so"
                }
                val failures = buildList {
                    apks.forEach { apk ->
                        ZipFile(apk).use { zip ->
                            val missing = requiredEntries.filter { entry -> zip.getEntry(entry) == null }
                            if (missing.isNotEmpty()) {
                                add("${apk.name} missing ${missing.joinToString()}")
                            }
                        }
                    }
                }
                if (failures.isNotEmpty()) {
                    throw GradleException(
                        buildString {
                            appendLine("Required Rust native libraries are missing from $variantSlug APK output.")
                            failures.forEach { appendLine("- $it") }
                            append("Install cargo-ndk or fix the Rust JNI build before shipping this variant.")
                        }
                    )
                }
            }
        }
        tasks.matching { it.name == "assemble$capitalized" }.configureEach {
            dependsOn(verifyTask)
        }
    }

    pageSizeCheckedVariantSlugs.forEach { (flavorName, buildTypeName) ->
        val variantSlug = "$flavorName${buildTypeName.replaceFirstChar { it.uppercase() }}"
        val capitalized = variantSlug.replaceFirstChar { it.uppercase() }
        val verifyTask = tasks.register("verify${capitalized}NativePageSize") {
            group = "verification"
            description = "Fail $variantSlug APKs whose native libs are not 16 KB ELF/zip aligned"
            dependsOn("package$capitalized")
            doLast {
                val apks = apkOutputs(flavorName, buildTypeName)
                if (apks.isEmpty()) {
                    throw GradleException("No APKs found for $variantSlug; cannot verify 16 KB page size")
                }
                val failures = apks.flatMap { apk ->
                    verifyApk16KbPageSize(apk).map { "${apk.name}: $it" }
                }
                if (failures.isNotEmpty()) {
                    throw GradleException(
                        buildString {
                            appendLine("16 KB page-size check failed for $variantSlug.")
                            failures.forEach { appendLine("- $it") }
                        }
                    )
                }
            }
        }
        tasks.matching { it.name == "assemble$capitalized" }.configureEach {
            dependsOn(verifyTask)
        }
    }
}

// ---------------------------------------------------------------------------
// Amber UI buildType parity check.
//
// Amber UI variants must each call `applyAmberUiBuildConfig(...)` so
// VERSION/XMS/Firebase fallback/OAuth BuildConfig fields stay consistent with the canonical app
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

val embeddedAlpineUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz"
val embeddedAlpineChecksum = "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259"

fun downloadRuntimeFile(localPath: String, remoteUrl: String, expectedChecksum: String) {
    val file = file(localPath)
    if (file.exists() && file.length() > 0L) {
        val existingChecksum = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val readBytes = input.read(buffer)
                if (readBytes < 0) break
                digest.update(buffer, 0, readBytes)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        if (existingChecksum == expectedChecksum) return
        file.delete()
    }
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
    if (checksum != expectedChecksum) {
        file.delete()
        throw GradleException(
            "Wrong checksum for $remoteUrl:\nExpected: $expectedChecksum\nActual:   $checksum"
        )
    }
}

val prepareEmbeddedTerminalRuntime by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/assets/embeddedTerminalRuntime/embedded-terminal-runtime")
    val nativeRuntimeDir = layout.projectDirectory.dir("src/main/embedded-native/arm64-v8a")
    inputs.files(nativeRuntimeDir.file("proot"), nativeRuntimeDir.file("libtalloc.so.2"))
    inputs.property("alpineUrl", embeddedAlpineUrl)
    inputs.property("alpineChecksum", embeddedAlpineChecksum)
    outputs.dir(outputDir)
    doLast {
        val root = outputDir.get().asFile
        root.mkdirs()
        listOf("proot", "libtalloc.so.2").forEach { name ->
            val source = nativeRuntimeDir.file(name).asFile
            check(source.isFile) { "Missing $name; run scripts/build-terminal-runtime.sh" }
            val issues = elfLoadIssues(name, source.readBytes())
            check(issues.isEmpty()) { issues.joinToString("\n") }
            source.copyTo(root.resolve(name), overwrite = true)
        }
        downloadRuntimeFile(
            localPath = root.resolve("alpine.tar.gz").absolutePath,
            remoteUrl = embeddedAlpineUrl,
            expectedChecksum = embeddedAlpineChecksum,
        )
        // Clean the obsolete asset when updating an existing build directory.
        root.resolve("libproot-loader.so").delete()
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
    dependsOn("assembleFullRelease", "bundleFullRelease")
    description = "Build both APK and AAB"
}

// Pre-flavor task name kept for docs/scripts: the `surface` dimension renamed
// variant tasks (testDebugUnitTest -> testFullDebugUnitTest).
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Alias for testFullDebugUnitTest (pre-flavor task name)."
    dependsOn("testFullDebugUnitTest")
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
    baselineProfile(project(":app:baselineprofile"))
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
    implementation(libs.firebase.messaging)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

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

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/noties/jlatexmath-android
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
    implementation(project(":common"))
    implementation(project(":core:app-infra"))
    implementation(project(":core:agent-utils"))
    implementation(project(":core:model"))
    implementation(project(":core:settings"))
    implementation(project(":core:jev"))
    implementation(project(":core:event"))
    implementation(project(":core:usage"))
    implementation(project(":core:agent-runtime-impl"))
    implementation(project(":core:agent-runtime"))
    implementation(project(":core:agent-store-room"))
    implementation(project(":feature:live:api"))
    implementation(project(":core:ai-prompts"))
    implementation(project(":feature:subagent"))
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
    implementation(project(":feature:novel"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // Opt-in empty host for the debug-signed, release-derived .debug variant.
    // Keeps device renderer checks isolated from the canonical installed app.
    if (chatDeviceFixtureEnabled) {
        add("baselineImplementation", libs.androidx.ui.test.manifest)
    }
}
