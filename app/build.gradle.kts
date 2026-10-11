import java.net.URI
import java.net.HttpURLConnection
import java.io.RandomAccessFile as ElfRandomAccessFile
import java.nio.ByteBuffer as ElfByteBuffer
import java.nio.ByteOrder as ElfByteOrder
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
    .orElse("26.9.30")
    .get()
val libV2rayFile = layout.projectDirectory.file("libs/libv2ray.aar").asFile
val amneziaWgAndroidVersion = providers.gradleProperty("AMNEZIAWG_ANDROID_VERSION")
    .orElse("3.1.20260828")
    .get()
// The v3.1.20260814 GitHub release asset contains a transposed digit in its filename.
val amneziaWgAssetVersion = if (amneziaWgAndroidVersion == "3.1.20260814") {
    "3.1.202060814"
} else {
    amneziaWgAndroidVersion
}
val amneziaWgApkUrl = providers.gradleProperty("AMNEZIAWG_ANDROID_APK_URL")
    .orElse("https://github.com/amnezia-vpn/amneziawg-android/releases/download/v$amneziaWgAndroidVersion/AmneziaWG-$amneziaWgAssetVersion.apk")
    .get()
val amneziaWgApk = layout.projectDirectory.file("libs/amneziawg-android-$amneziaWgAndroidVersion.apk").asFile
val amneziaWgAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

// IPtProxy and libv2ray both embed gomobile's `go.Seq` runtime and both ship
// `libgojni.so`. Keep IPtProxy out of the app compile/runtime classpaths; its
// classes and native library are loaded by TorSnowflakeTransportService from
// a private DEX/native directory in the isolated :TorSnowflake process.
val iptProxyRuntimeConfiguration = configurations.create("iptProxyRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val snowflakeRuntimeAssetsDir = layout.buildDirectory.dir("generated/snowflakeRuntime/assets")
val snowflakeRuntimeJniLibsDir = layout.buildDirectory.dir("generated/snowflakeRuntime/jniLibs")

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