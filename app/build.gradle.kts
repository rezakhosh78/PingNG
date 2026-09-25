import java.net.URI
import java.net.HttpURLConnection
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

// Android Studio versions that still request this legacy Kotlin model task
// during Gradle sync fail before any Android task can be inspected. Newer
// Kotlin/AGP combinations no longer create it, so provide a harmless
// compatibility task for the IDE model request.
if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel") {
        group = "ide"
        description = "Compatibility task for Android Studio Gradle model sync"
    }
}

// AndroidLibXrayLite is the maintained Android packaging of Xray-core.
// Keep the version overridable so a newer signed AAR can be tested without
// editing the build logic: ./gradlew -PXRAY_ANDROID_LIB_VERSION=...
val xrayAndroidLibVersion = providers.gradleProperty("XRAY_ANDROID_LIB_VERSION")
    .orElse("26.9.9")
    .get()
val libV2rayFile = layout.projectDirectory.file("libs/libv2ray.aar").asFile

fun isValidAar(file: java.io.File): Boolean = try {
    if (!file.isFile || file.length() <= 1024) {
        false
    } else {
        ZipFile(file).use { archive ->
            archive.getEntry("AndroidManifest.xml") != null &&
                archive.getEntry("classes.jar") != null
        }
    }
} catch (_: Exception) {
    false
}

fun downloadValidAar(target: java.io.File, url: String): Boolean {
    libV2rayFile.parentFile.mkdirs()
    val temporary = target.resolveSibling("${target.name}.download")
    repeat(3) {
        try {
            temporary.delete()
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (isValidAar(temporary)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
                return true
            }
        } catch (_: Exception) {
            // Retry a partial/interrupted release download.
        } finally {
            if (!isValidAar(temporary)) temporary.delete()
        }
    }
    return false
}

// The standalone MASQUE core is bundled under an app-owned name. Keeping the
// executable in the project makes Android builds deterministic and offline.
val warpMasqueBinary = layout.projectDirectory.file("libs/arm64-v8a/libwarpmasque.so").asFile

check(warpMasqueBinary.isFile && warpMasqueBinary.length() > 1024 * 1024) {
    "WARP MASQUE core is missing from app/libs/arm64-v8a"
}

if (!isValidAar(libV2rayFile)) {
    check(downloadValidAar(
        libV2rayFile,
        "https://github.com/2dust/AndroidLibXrayLite/releases/download/v$xrayAndroidLibVersion/libv2ray.aar",
    )) { "libv2ray.aar is missing or corrupted, and a valid replacement could not be downloaded" }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pingng.android"
        minSdk = 24
        targetSdk = 37
        versionCode = 752
        // v2rayNG 2.3.9-compatible PingNG build with the Pi39 feature set.
        // Keep the project-specific version code so installs upgrade cleanly
        // from the previous Pi37 builds.
        versionName = "v2.3.9-Pi38"

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    // Removed the -fdroid suffix from here
                    output.outputFileName = "PingNG_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "PingNG_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // Keep Ninja's generated object paths short on Windows even when
            // the project itself is checked out in a deeply nested folder.
            buildStagingDirectory = File(
                System.getProperty("java.io.tmpdir"),
                "pingng-cmake-${Integer.toUnsignedString(rootProject.projectDir.absolutePath.hashCode(), 36)}"
            )
        }
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Core Libraries
    implementation(mapOf("name" to "libv2ray", "ext" to "aar"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
