import com.android.build.api.artifact.SingleArtifact
import org.apache.tools.ant.filters.FixCrLfFilter
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
}

// Magisk module metadata, written into module.prop
val moduleId = "gesture_switch"
val moduleName = "Gesture Switch"
val moduleAuthor = "misaka-20002"
val moduleDescription = "Switches navigation mode by fold state on flip phones (3-button folded, gestures open)"
val moduleVersionName = "v0.1.5"
val moduleVersionCode = 6

val moduleProps = mapOf(
    "id" to moduleId,
    "name" to moduleName,
    "versionName" to moduleVersionName,
    "versionCode" to moduleVersionCode,
    "author" to moduleAuthor,
    "description" to moduleDescription,
)

// The APK is only a container: classes.dex is loaded into system_server by the native
// Zygisk loader, and the stripped native libraries become zygisk/<abi>.so.
android {
    namespace = "io.github.misaka20002.gestureswitch"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.misaka20002.gestureswitch"
        // Keep in sync with APP_PLATFORM in jni/Application.mk (InMemoryDexClassLoader needs 26)
        minSdk = 26
        targetSdk = 37
        versionCode = moduleVersionCode
        versionName = moduleVersionName
    }

    // R8 for both build types: the native loader reads exactly one classes.dex, and only R8
    // guarantees that (unminified debug builds split the code into several dex files).
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path("jni/Android.mk")
        }
    }

    lint {
        // The Java code runs inside system_server and uses hidden APIs on purpose.
        disable += setOf("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    }
}

// zip<Variant>: package the APK's classes.dex and native libraries as a flashable module,
// build/outputs/magisk/<id>-<version>-<variant>.zip.
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val apk = apkDir.map { dir ->
            dir.asFileTree.matching { include("*.apk") }.files.singleOrNull()
                ?: throw GradleException("Expected one APK for ${variant.name}")
        }

        val zip = tasks.register<Zip>("zip$cap") {
            group = "magisk"
            description = "Packages the ${variant.name} build as a flashable Magisk/KernelSU module zip."

            archiveFileName.set("$moduleId-$moduleVersionName-${variant.name}.zip")
            destinationDirectory.set(layout.buildDirectory.dir("outputs/magisk"))
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            includeEmptyDirs = false
            // expand() values are not tracked by Gradle on their own.
            inputs.properties(moduleProps)

            from("magisk") {
                filesMatching("module.prop") { expand(moduleProps) }
                // Text files must have LF endings on the device, even when built on Windows.
                // Only text files: the filter would corrupt binaries added here later.
                filesMatching(listOf("module.prop", "config.prop", "**/*.sh", "META-INF/**")) {
                    filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
                }
            }

            from(apk.map { zipTree(it) }) {
                include("classes.dex", "lib/*/lib$moduleId.so")
                eachFile {
                    val segments = relativePath.segments
                    if (segments[0] == "lib") {
                        // lib/<abi>/libgesture_switch.so -> zygisk/<abi>.so
                        relativePath = RelativePath(true, "zygisk", "${segments[1]}.so")
                    }
                }
            }

            doFirst {
                ZipFile(apk.get()).use { file ->
                    val entries = file.entries().asSequence().map { it.name }.toList()
                    val dex = entries.filter { it.matches(Regex("classes[0-9]*\\.dex")) }
                    if (dex != listOf("classes.dex")) {
                        throw GradleException("Expected a single classes.dex, found $dex")
                    }
                    if (entries.none { it.startsWith("lib/") && it.endsWith("/lib$moduleId.so") }) {
                        throw GradleException("APK does not contain lib$moduleId.so")
                    }
                }
            }
        }

        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(zip) }
    }
}
