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
    return false
}

fun isValidAmneziaWgApk(file: java.io.File): Boolean = try {
    if (!file.isFile || file.length() < 1024 * 1024) {
        false
    } else {
        ZipFile(file).use { archive ->
            amneziaWgAbis.all { abi ->
                val entry = archive.getEntry("lib/$abi/libwg-go.so")
                entry != null && entry.size > 512 * 1024
            }
        }
    }
} catch (_: Exception) {
    false
}

fun downloadAmneziaWgApk(target: java.io.File, url: String): Boolean {
    target.parentFile.mkdirs()
    val temporary = target.resolveSibling("${target.name}.download")
    repeat(3) {
        try {
            temporary.delete()
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.instanceFollowRedirects = true
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (isValidAmneziaWgApk(temporary)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
                return true
            }
        } catch (_: Exception) {
            // Retry partial downloads and transient release-host errors.
        } finally {
            if (!isValidAmneziaWgApk(temporary)) temporary.delete()
        }
    }
    return false
}

fun isValidAmneziaWgNativeLibrary(file: java.io.File): Boolean = try {
    if (!file.isFile || file.length() <= 512 * 1024) {
        false
    } else {
        // The official Go linker keeps the module dependency table in the
        // shared object. The record is tab-delimited (not space-delimited),
        // e.g. `dep<TAB>github.com/.../v3<TAB>v3.1.20260814`. Checking that
        // exact record and FIX14 build marker prevent an unpatched official
        // library or a stale hotfix from replacing the runtime-isolated build.
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        val marker = "dep\tgithub.com/amnezia-vpn/amneziawg-go/v3\tv$amneziaWgAndroidVersion"
        bytes.contains(marker) &&
            bytes.contains("PingNG-AWG-FIX15-PSIPHON-NETSTACK-20261005")
    }
} catch (_: Exception) {
    false
}

fun ensureAmneziaWgNativeLibraries() {
    val nativeLibraries = amneziaWgAbis.map { abi ->
        layout.projectDirectory.file("libs/$abi/libwg-go.so").asFile
    }
    if (nativeLibraries.all(::isValidAmneziaWgNativeLibrary)) return

    error("AmneziaWG FIX15 native libraries are missing or stale. Restore the bundled app/libs files or rebuild using native/amneziawg/build-android.sh. The unpatched official APK cannot replace this runtime-isolated build.")
}

// The standalone MASQUE/HTTP2 core is bundled per Android ARM ABI. Keeping
// both executables in the project makes Android builds deterministic.
val warpMasqueAbis = listOf("arm64-v8a", "armeabi-v7a")
val warpMasqueBinaries = warpMasqueAbis.associateWith { abi ->
    layout.projectDirectory.file("libs/$abi/libwarpmasque.so").asFile
}
warpMasqueBinaries.forEach { (abi, binary) ->
    check(binary.isFile && binary.length() > 1024 * 1024) {
        "WARP MASQUE core is missing from app/libs/$abi"
    }
}

// Bundle the endpoint scanner for each Android ABI supported by this project.
// Keeping the executables in source prevents builds from silently omitting
// endpoint scanning when release downloads are unavailable.
val endpointScannerAbis = mapOf(
    "arm64-v8a" to (2 to 183),
    "armeabi-v7a" to (1 to 40),
    "x86_64" to (2 to 62),
    "x86" to (1 to 3),
)
fun isValidEndpointScannerExecutable(file: java.io.File, elfClass: Int, machine: Int): Boolean = try {
    if (!file.isFile || file.length() < 1024) {
        false
    } else {
        val header = file.inputStream().use { it.readNBytes(20) }
        header.size == 20 &&
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            (header[4].toInt() and 0xff) == elfClass && header[5] == 1.toByte() &&
            header[16] == 3.toByte() && header[17] == 0.toByte() &&
            (header[18].toInt() and 0xff) == machine && (header[19].toInt() and 0xff) == 0
    }
} catch (_: Exception) {
    false
}
endpointScannerAbis.forEach { (abi, elf) ->
    val executable = layout.projectDirectory.file("libs/$abi/libendpointscanner.so").asFile
    check(isValidEndpointScannerExecutable(executable, elf.first, elf.second)) {
        "Android $abi endpoint scanner is missing or invalid: app/libs/$abi/libendpointscanner.so"
    }
}

if (!isValidAar(libV2rayFile)) {
    check(downloadValidAar(
        libV2rayFile,
        "https://github.com/2dust/AndroidLibXrayLite/releases/download/v$xrayAndroidLibVersion/libv2ray.aar",
    )) { "libv2ray.aar is missing or corrupted, and a valid replacement could not be downloaded" }
}

ensureAmneziaWgNativeLibraries()

val prepareSnowflakeRuntime = tasks.register("prepareSnowflakeRuntime") {
    inputs.files(iptProxyRuntimeConfiguration)
    outputs.dir(snowflakeRuntimeAssetsDir)
    outputs.dir(snowflakeRuntimeJniLibsDir)

    doLast {
        val iptProxyAar = iptProxyRuntimeConfiguration.resolve().singleOrNull()
            ?: error("Expected exactly one IPtProxy AAR in iptProxyRuntime")
        val generatedAssets = snowflakeRuntimeAssetsDir.get().asFile
        val generatedJniLibs = snowflakeRuntimeJniLibsDir.get().asFile
        project.delete(generatedAssets, generatedJniLibs)

        val workDirectory = layout.buildDirectory.dir("tmp/snowflakeRuntime").get().asFile
        project.delete(workDirectory)
        workDirectory.mkdirs()
        val classesJar = File(workDirectory, "IPtProxy-classes.jar")
        val snowflakeAssetDirectory = File(generatedAssets, "tor-snowflake-runtime")
        snowflakeAssetDirectory.mkdirs()

        ZipFile(iptProxyAar).use { aar ->
            val classesEntry = aar.getEntry("classes.jar")
                ?: error("IPtProxy AAR does not contain classes.jar")
            aar.getInputStream(classesEntry).use { input ->
                classesJar.outputStream().use { output -> input.copyTo(output) }
            }

            var copiedNativeLibraries = 0
            val entries = aar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val parts = entry.name.split('/')
                if (entry.isDirectory || parts.size != 3 || parts[0] != "jni" || parts[2] != "libgojni.so") {
                    continue
                }
                val destination = File(generatedJniLibs, "${parts[1]}/libpingng_snowflake.so")
                destination.parentFile.mkdirs()
                aar.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                copiedNativeLibraries++
            }
            check(copiedNativeLibraries > 0) { "IPtProxy AAR does not contain libgojni.so for any ABI" }
        }

        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val d8Name = if (isWindows) "d8.bat" else "d8"
        val buildTools = android.sdkDirectory.resolve("build-tools")
            .listFiles()
            ?.filter { File(it, d8Name).isFile }
            .orEmpty()
        val selectedBuildTools = buildTools.maxWithOrNull(Comparator { left, right ->
            val leftParts = left.name.split('.').map { it.toIntOrNull() ?: 0 }
            val rightParts = right.name.split('.').map { it.toIntOrNull() ?: 0 }
            var comparison = 0
            for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
                comparison = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
                if (comparison != 0) break
            }
            comparison
        }) ?: error("Android SDK build-tools with $d8Name are required to build the Snowflake runtime")
        val d8 = File(selectedBuildTools, d8Name)
        val dexOutputDirectory = File(workDirectory, "dex").apply { mkdirs() }
        val d8Arguments = listOf(
            "--min-api", "24",
            "--output", dexOutputDirectory.absolutePath,
            classesJar.absolutePath,
        )
        val command = if (isWindows) {
            listOf("cmd.exe", "/d", "/c", d8.absolutePath) + d8Arguments
        } else {
            listOf(d8.absolutePath) + d8Arguments
        }
        val d8Process = ProcessBuilder(command).redirectErrorStream(true).start()
        val d8Output = d8Process.inputStream.bufferedReader().use { it.readText() }
        check(d8Process.waitFor() == 0) { "D8 failed to prepare IPtProxy runtime DEX:\n$d8Output" }

        val dexFiles = dexOutputDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("dex", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        check(dexFiles.isNotEmpty()) { "D8 did not produce any Snowflake runtime DEX files" }
        dexFiles.forEach { dex -> dex.copyTo(File(snowflakeAssetDirectory, dex.name), overwrite = true) }
    }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.pingng.android"
        minSdk = 24
        targetSdk = 37
        // Increment for the isolated Snowflake runtime DEX and native library packaging.
        versionCode = 763
        // PingNG Pi42 version metadata. Upstream tag 2.3.10 source changes integrated.
        versionName = "v2.3.10-Pi42"
        // Keep the bundled native engine version observable without calling a
        // native diagnostic entry point during tunnel startup. Some older
        // extracted libwg-go.so builds can panic from awgVersion() itself.
        buildConfigField("String", "AMNEZIAWG_ENGINE_VERSION", "\"$amneziaWgAndroidVersion\"")

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
            jniLibs.srcDir(snowflakeRuntimeJniLibsDir.get().asFile)
            assets.srcDir(snowflakeRuntimeAssetsDir.get().asFile)
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

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
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
            // These files are executables, not shared libraries. Never run strip on them.
            keepDebugSymbols.add("**/libwarpmasque.so")
            keepDebugSymbols.add("**/libstormdns.so")
            keepDebugSymbols.add("**/libdnstt.so")
            keepDebugSymbols.add("**/libmasterdns.so")
            keepDebugSymbols.add("**/libendpointscanner.so")
            // Preserve the Go JNI symbol table used by the isolated Psiphon class loader.
            keepDebugSymbols.add("**/libpingng_psiphon.so")
            // IPtProxy embeds a separate gomobile Go runtime for Snowflake.
            keepDebugSymbols.add("**/libgojni.so")
            keepDebugSymbols.add("**/libpingng_snowflake.so")
        }
    }

}

tasks.configureEach {
    val consumesGeneratedSnowflakeRuntime =
        name.startsWith("merge") && (name.endsWith("Assets") || name.endsWith("JniLibFolders"))
    // AGP 9's lint model reads source-set assets directly instead of depending
    // on the mergeAssets task, so declare the generated Snowflake inputs here.
    val generatesLintVitalModel = name.startsWith("generate") && name.endsWith("LintVitalReportModel")
    if (consumesGeneratedSnowflakeRuntime || generatesLintVitalModel) {
        dependsOn(prepareSnowflakeRuntime)
    }
}

dependencies {
    implementation("info.guardianproject:tor-android:0.4.9.11")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    add(iptProxyRuntimeConfiguration.name, "com.netzarchitekten:IPtProxy:5.5.1")
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
    implementation("com.github.mwiede:jsch:0.2.24")

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
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Retain PingNG's existing JUnit 4 regression tests during the upstream migration.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

// Release builds must contain actual Android executables, never renamed Linux cores.
val validateDnsTunnelCores by tasks.registering {
    doLast {
        val abis = (project.findProperty("ABI_FILTERS") as? String)?.split(';')
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        for (abi in abis) for (core in listOf("stormdns", "dnstt")) {
            val binary = layout.projectDirectory.file("libs/$abi/lib$core.so").asFile
            check(binary.isFile && binary.length() > 1024) {
                "Missing Android $abi $core core: $binary. See CHANGES_DNS_FA.md."
            }
            val header = binary.inputStream().use { it.readNBytes(20) }
            check(header.size == 20 && header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte() && header[16] == 3.toByte()) {
                "$binary must be an Android PIE ELF executable"
            }
            val expectedMachine = mapOf("arm64-v8a" to 183, "armeabi-v7a" to 40, "x86_64" to 62, "x86" to 3)[abi]
            val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
            check(machine == expectedMachine) { "$binary architecture does not match $abi" }
            check(binary.readBytes().toString(Charsets.ISO_8859_1).contains("/system/bin/linker")) {
                "$binary is not an Android executable linked with the Android system linker"
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("ReleaseBuild")) dependsOn(validateDnsTunnelCores)
}

val validateArmV7CoreSet by tasks.registering {
    doLast {
        val armV7Dir = layout.projectDirectory.dir("libs/armeabi-v7a").asFile
        val cores = listOf("dnstt", "masterdns", "pingng_psiphon", "stormdns", "warpmasque")
        for (core in cores) {
            val binary = File(armV7Dir, "lib$core.so")
            check(binary.isFile && binary.length() > 1024) {
                "Missing Android armeabi-v7a $core core: $binary"
            }
            val header = binary.inputStream().use { it.readNBytes(20) }
            check(header.size == 20 && header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()) {
                "$binary must be an ELF core for Android"
            }
            val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
            check(machine == 40) { "$binary architecture does not match armeabi-v7a" }
            val fileType = (header[16].toInt() and 255) or ((header[17].toInt() and 255) shl 8)
            check(fileType == 3 || core == "masterdns") {
                "$binary has an unexpected ELF type for the armeabi-v7a core set"
            }
            if (core in listOf("dnstt", "stormdns", "warpmasque")) {
                check(binary.readBytes().toString(Charsets.ISO_8859_1).contains("/system/bin/linker")) {
                    "$binary is not an Android executable linked with the Android system linker"
                }
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("ReleaseBuild")) dependsOn(validateArmV7CoreSet)
}

fun hasCompleteElfSectionTable(file: File): Boolean = try {
    ElfRandomAccessFile(file, "r").use { input ->
        if (input.length() < 64) return@use false
        val header = ByteArray(64)
        input.readFully(header)
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() || header[5] != 1.toByte()
        ) return@use false
        val buffer = ElfByteBuffer.wrap(header).order(ElfByteOrder.LITTLE_ENDIAN)
        when (header[4].toInt() and 0xff) {
            1 -> {
                val offset = buffer.getInt(32).toLong() and 0xffffffffL
                val entrySize = buffer.getShort(46).toLong() and 0xffffL
                val count = buffer.getShort(48).toLong() and 0xffffL
                offset <= input.length() && entrySize * count <= input.length() - offset
            }
            2 -> {
                val offset = buffer.getLong(40)
                val entrySize = buffer.getShort(58).toLong() and 0xffffL
                val count = buffer.getShort(60).toLong() and 0xffffL
                offset >= 0 && offset <= input.length() && entrySize * count <= input.length() - offset
            }
            else -> false
        }
    }
} catch (_: Exception) {
    false
}

val validatePsiphonNativeLibraries by tasks.registering {
    doLast {
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            val binary = layout.projectDirectory.file("libs/$abi/libpingng_psiphon.so").asFile
            check(hasCompleteElfSectionTable(binary)) {
                "Invalid or truncated Psiphon native library for $abi: $binary"
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("Build")) dependsOn(validatePsiphonNativeLibraries)
}
