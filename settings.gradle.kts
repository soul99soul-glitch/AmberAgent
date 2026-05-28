pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.itextsupport.com/android")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.objectbox") {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
            // (Removed) `org.mozilla.rust-android-gradle.rust-android` override:
            // it mapped to a non-existent module
            // `gradle.plugin.org.mozilla.rust-android-gradle:plugin:0.9.6` and
            // broke fresh-clone Gradle config. The standard plugins DSL +
            // gradlePluginPortal above resolve the plugin correctly without
            // any override — Codex review fix.
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        google()
        mavenCentral()
        maven("https://jitpack.io")
        mavenLocal()
    }
}

rootProject.name = "amberagent"
include(":app")
include(":highlight")
include(":ai")
include(":search")
include(":tts")
include(":common")
include(":document")
include(":web")
include(":core:agent-runtime")
include(":core:agent-store-room")
include(":feature:deepread:api")
include(":feature:chat:api")
include(":core:agent-utils")
include(":feature:history")
include(":feature:webview")
