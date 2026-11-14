import org.apache.tools.ant.filters.FixCrLfFilter

plugins {
    alias(libs.plugins.android.library)
}

// Magisk module metadata, written into module.prop
val moduleId = "gesture_switch"
val moduleName = "Gesture Switch"
val moduleAuthor = "misaka-20002"
val moduleDescription = "Zygisk module"
val moduleVersionName = "v0.1.0"
val moduleVersionCode = 1

val moduleProps = mapOf(
    "id" to moduleId,
    "name" to moduleName,
    "versionName" to moduleVersionName,
    "versionCode" to moduleVersionCode,
    "author" to moduleAuthor,
    "description" to moduleDescription,
)

android {
    namespace = "io.github.misaka20002.gestureswitch"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        // Keep in sync with APP_PLATFORM in jni/Application.mk
        minSdk = 21
    }

    externalNativeBuild {
        ndkBuild {
            path("jni/Android.mk")
        }
    }
}

// zip<Variant>: package the stripped native libraries as a flashable Magisk module,
// build/outputs/magisk/<id>-<version>-<variant>.zip with zygisk/<abi>.so inside.
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val stripTask = "strip${cap}DebugSymbols"

        val zip = tasks.register<Zip>("zip$cap") {
            group = "magisk"
            description = "Packages the ${variant.name} build as a flashable Magisk module zip."
            dependsOn(stripTask)

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
                filesMatching(listOf("module.prop", "**/*.sh", "META-INF/**")) {
                    filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
                }
            }

            // .../lib/<abi>/libgesture_switch.so -> zygisk/<abi>.so
            from(provider { tasks.getByName(stripTask).outputs.files }) {
                include("**/lib$moduleId.so")
                eachFile {
                    val abi = relativePath.segments.let { it[it.size - 2] }
                    relativePath = RelativePath(true, "zygisk", "$abi.so")
                }
            }
        }

        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(zip) }
    }
}
