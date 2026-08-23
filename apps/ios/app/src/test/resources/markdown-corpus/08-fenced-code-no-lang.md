## Fenced Code Blocks Without a Language Tag

Sometimes a code block has no language annotation. The renderer must still treat it as a verbatim block — just without syntax highlighting.

Plain text output from a terminal command:

```
$ ./gradlew :app:assembleDebug

> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig
> Task :app:javaPreCompileDebug
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac
> Task :app:dexBuilderDebug
> Task :app:mergeExtDexDebug
> Task :app:packageDebug

BUILD SUCCESSFUL in 14s
47 actionable tasks: 12 executed, 35 up-to-date
```

A configuration file excerpt without a specific language:

```
[database]
host = 127.0.0.1
port = 5432
name = amberagent_dev
pool_size = 10
timeout = 30s

[server]
bind = 0.0.0.0:8080
workers = 4
log_level = info
```

Raw diff output also often appears without a language tag:

```
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -12,7 +12,7 @@
 android {
-    compileSdk = 34
+    compileSdk = 35
     defaultConfig {
         applicationId = "app.amber.agent"
-        targetSdk = 34
+        targetSdk = 35
     }
 }
```

And sometimes just a quick note about environment variables:

```
ANDROID_HOME=/Users/dev/Library/Android/sdk
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
GRADLE_USER_HOME=/Users/dev/.gradle
```
