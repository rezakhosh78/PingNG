import java.net.URI
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.io.RandomAccessFile as ElfRandomAccessFile
import java.nio.ByteBuffer as ElfByteBuffer
import java.nio.ByteOrder as ElfByteOrder
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

abstract class PrepareSnowflakeRuntimeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val iptProxyAar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDirectory: DirectoryProperty

    @get:InputFile
    abstract val d8Executable: RegularFileProperty

    @get:Input
    abstract val windowsHost: Property<Boolean>

    @TaskAction
    fun prepareRuntime() {
        val iptProxyFile = iptProxyAar.singleFile
        val generatedAssets = assetsDirectory.get().asFile
        val generatedJniLibs = jniLibsDirectory.get().asFile
        generatedAssets.deleteRecursively()
        generatedJniLibs.deleteRecursively()

        val workDirectory = temporaryDir.resolve("snowflakeRuntime")
        workDirectory.deleteRecursively()
        workDirectory.mkdirs()
        val classesJar = File(workDirectory, "IPtProxy-classes.jar")
        val snowflakeAssetDirectory = File(generatedAssets, "tor-snowflake-runtime")
        snowflakeAssetDirectory.mkdirs()

        ZipFile(iptProxyFile).use { aar ->
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

        val isWindows = windowsHost.get()
        val d8 = d8Executable.get().asFile
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

/** Builds the official Bepass Go package as a private, runtime-loaded Android library. */
abstract class PrepareBepassRuntimeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bepassSourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDirectory: DirectoryProperty

    @get:InputFile
    abstract val d8Executable: RegularFileProperty

    @get:Input
    abstract val ndkDirectory: Property<String>

    @get:Input
    abstract val windowsHost: Property<Boolean>

    @TaskAction
    fun prepareRuntime() {
        val source = bepassSourceDirectory.get().asFile
        check(File(source, "go.mod").isFile && File(source, "mobile/mobile.go").isFile) {
            "Bepass Go source is incomplete: $source"
        }
        val gradleHome = project.gradle.gradleUserHomeDir
        val go = resolveCachedGo(gradleHome)
        val toolchain = File(ndkDirectory.get())
        check(toolchain.isDirectory) { "Android NDK is missing: $toolchain" }
        val sdk = toolchain.parentFile?.parentFile ?: error("Could not resolve Android SDK from $toolchain")
        val cacheRoot = File(gradleHome, "caches/pingng/bepass-runtime")
        val goModCache = File(gradleHome, "caches/pingng/gomodcache").apply { mkdirs() }
        val goBuildCache = File(gradleHome, "caches/pingng/gobuildcache").apply { mkdirs() }
        val goBin = File(cacheRoot, "bin").apply { mkdirs() }
        val gomobileVersion = "v0.0.0-20261011034528-197770444867"
        val work = temporaryDir.resolve("bepassRuntime").apply {
            deleteRecursively()
            mkdirs()
        }
        val aar = File(work, "bepass.aar")
        val gomobileName = "gomobile" + if (windowsHost.get()) ".exe" else ""
        val gomobile = File(goBin, gomobileName)

        fun run(command: List<String>, directory: File, environment: Map<String, String> = emptyMap()) {
            val builder = ProcessBuilder(command).directory(directory).redirectErrorStream(true)
            builder.environment().putAll(environment)
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) {
                "Bepass runtime command failed (${command.joinToString(" ")}):\n$output"
            }
        }

        val goEnv = linkedMapOf(
            "GOBIN" to goBin.absolutePath,
            "GOMODCACHE" to goModCache.absolutePath,
            "GOCACHE" to goBuildCache.absolutePath,
            "GOTOOLCHAIN" to "local",
            "GOFLAGS" to "",
            "GOWORK" to "off",
            "GOPROXY" to (System.getenv("GOPROXY")?.takeIf { it.isNotBlank() }
                ?: "https://proxy.golang.org|https://goproxy.io|direct"),
        )
        run(
            listOf(go.absolutePath, "install", "golang.org/x/mobile/cmd/gomobile@$gomobileVersion"),
            source,
            goEnv,
        )
        // Keep gomobile's generated bind package in this module's dependency graph.
        // Pin it to the same x/mobile revision as the gomobile executable.
        run(
            listOf(go.absolutePath, "get", "-tool", "golang.org/x/mobile/cmd/gobind@$gomobileVersion"),
            source,
            goEnv,
        )
        val pathKey = System.getenv().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val androidEnv = goEnv + mapOf(
            "ANDROID_HOME" to sdk.absolutePath,
            "ANDROID_SDK_ROOT" to sdk.absolutePath,
            "ANDROID_NDK_HOME" to toolchain.absolutePath,
            "ANDROID_NDK_ROOT" to toolchain.absolutePath,
            "CGO_LDFLAGS" to "-Wl,-z,max-page-size=16384",
            // gomobile init invokes `go install .../gobind@latest` internally. Put
            // the verified Go toolchain on PATH as well as invoking it by its
            // absolute path above, so gomobile cannot fall back to the runner's
            // older system Go version.
            pathKey to listOf(
                goBin.absolutePath,
                go.absoluteFile.parentFile.absolutePath,
                System.getenv().entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }
                    ?.value.orEmpty(),
            ).joinToString(File.pathSeparator),
        )
        run(listOf(gomobile.absolutePath, "init"), source, androidEnv)
        run(
            listOf(
                gomobile.absolutePath,
                "bind",
                "-target=android",
                "-androidapi=24",
                "-javapkg=com.pingng",
                "-o", aar.absolutePath,
                "./mobile",
            ),
            source,
            androidEnv + mapOf("GOFLAGS" to "-mod=mod"),
        )

        val generatedAssets = assetsDirectory.get().asFile
        val generatedJniLibs = jniLibsDirectory.get().asFile
        generatedAssets.deleteRecursively()
        generatedJniLibs.deleteRecursively()
        val runtimeAssets = File(generatedAssets, "pingng-bepass-runtime").apply { mkdirs() }
        val classesJar = File(work, "bepass-classes.jar")
        ZipFile(aar).use { archive ->
            val classesEntry = archive.getEntry("classes.jar")
                ?: error("Bepass AAR does not contain classes.jar")
            archive.getInputStream(classesEntry).use { input ->
                classesJar.outputStream().use { output -> input.copyTo(output) }
            }
            val nativeAbis = mutableSetOf<String>()
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val parts = entry.name.split('/')
                if (entry.isDirectory || parts.size != 3 || parts[0] != "jni" || parts[2] != "libgojni.so") continue
                val destination = File(generatedJniLibs, "${parts[1]}/libpingng_bepass.so")
                destination.parentFile.mkdirs()
                archive.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                nativeAbis += parts[1]
            }
            val requiredAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            check(nativeAbis.containsAll(requiredAbis)) {
                "Bepass AAR is missing native libraries for ${requiredAbis - nativeAbis}"
            }
        }

        val d8Output = File(work, "dex").apply { mkdirs() }
        val d8Args = listOf("--min-api", "24", "--output", d8Output.absolutePath, classesJar.absolutePath)
        val d8 = d8Executable.get().asFile
        val d8Command = if (windowsHost.get()) listOf("cmd.exe", "/d", "/c", d8.absolutePath) + d8Args
            else listOf(d8.absolutePath) + d8Args
        val d8Process = ProcessBuilder(d8Command).redirectErrorStream(true).start()
        val d8Log = d8Process.inputStream.bufferedReader().use { it.readText() }
        check(d8Process.waitFor() == 0) { "D8 failed to prepare the Bepass runtime:\n$d8Log" }
        val dexFiles = d8Output.listFiles()
            ?.filter { it.isFile && it.extension.equals("dex", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        check(dexFiles.isNotEmpty()) { "D8 produced no Bepass runtime DEX files" }
        dexFiles.forEach { dex -> dex.copyTo(File(runtimeAssets, dex.name), overwrite = true) }
    }

    private fun resolveCachedGo(gradleHome: File): File {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val arch = when (archName) {
            "amd64", "x86_64", "x64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Unsupported host CPU for Bepass build: $archName")
        }
        val os = when {
            osName.startsWith("windows") -> "windows"
            osName.startsWith("mac") || osName.startsWith("darwin") -> "darwin"
            osName.startsWith("linux") -> "linux"
            else -> error("Unsupported host OS for Bepass build: $osName")
        }
        val executable = File(
            gradleHome,
            "caches/pingng/toolchains/go-1.26.3/$os-$arch/go/bin/go" + if (os == "windows") ".exe" else "",
        )
        check(executable.isFile) {
            "Verified Go 1.26.3 is prepared by buildEndpointScannerAndroid before Bepass packaging"
        }
        return executable
    }
}

/**
 * Builds Endpoint Scanner for Android as part of the normal Android Gradle build.
 * Go is downloaded into Gradle's user cache on first use and checked against
 * the official go.dev SHA-256 values. The Android NDK remains the project's
 * pinned side-by-side NDK installation.
 */
abstract class BuildEndpointScannerTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scannerSourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDirectory: DirectoryProperty

    @get:Input
    abstract val ndkDirectory: Property<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val goVersion: Property<String>

    @get:Input
    abstract val androidApiLevel: Property<Int>

    @get:Input
    abstract val hostIdentifier: Property<String>

    private data class Host(val os: String, val arch: String, val ndkTags: List<String>) {
        val key: String get() = "$os-$arch"
    }

    private data class AndroidTarget(
        val abi: String,
        val goArch: String,
        val goArm: String?,
        val clangTriple: String,
        val elfClass: Int,
        val elfMachine: Int,
    )

    @TaskAction
    fun buildScanner() {
        val source = scannerSourceDirectory.get().asFile
        check(File(source, "go.mod").isFile && File(source, "go.sum").isFile) {
            "Endpoint Scanner Go source is incomplete: $source"
        }

        val host = currentHost()
        val go = resolveGo(host)
        val ndkRoot = File(ndkDirectory.get())
        check(ndkRoot.isDirectory) {
            "Android NDK ${ndkVersion.get()} is missing from $ndkRoot. Android Gradle Plugin should install the pinned NDK after its SDK licenses are accepted."
        }
        val prebuiltRoot = File(ndkRoot, "toolchains/llvm/prebuilt")
        val ndkHost = host.ndkTags.firstOrNull { File(prebuiltRoot, it).isDirectory }
            ?: error("No Android NDK host toolchain found under $prebuiltRoot for ${host.os}/${host.arch}")
        val toolchainBin = File(prebuiltRoot, "$ndkHost/bin")
        val clangName = if (host.os == "windows") "clang.exe" else "clang"
        val clang = File(toolchainBin, clangName)
        check(clang.isFile) { "Android NDK Clang compiler is missing: $clang" }

        val outputRoot = jniLibsDirectory.get().asFile
        outputRoot.deleteRecursively()
        check(outputRoot.mkdirs() || outputRoot.isDirectory) {
            "Could not create generated endpoint scanner directory: $outputRoot"
        }

        val gradleCache = project.gradle.gradleUserHomeDir
        val goModCache = File(gradleCache, "caches/pingng/gomodcache")
        val goBuildCache = File(gradleCache, "caches/pingng/gobuildcache")
        goModCache.mkdirs()
        goBuildCache.mkdirs()

        val targets = listOf(
            AndroidTarget("arm64-v8a", "arm64", null, "aarch64-linux-android", 2, 183),
            AndroidTarget("armeabi-v7a", "arm", "7", "armv7a-linux-androideabi", 1, 40),
            AndroidTarget("x86_64", "amd64", null, "x86_64-linux-android", 2, 62),
            AndroidTarget("x86", "386", null, "i686-linux-android", 1, 3),
        )

        targets.forEach { target ->
            val targetOutput = File(outputRoot, "${target.abi}/libendpointscanner.so")
            check(targetOutput.parentFile.mkdirs() || targetOutput.parentFile.isDirectory)
            // Go's CC parser understands quoted command paths. Normalize Windows
            // separators first so paths with spaces work the same on every host.
            val normalizedCompilerCommand =
                "\"${clang.absolutePath.replace('\\', '/')}\" --target=${target.clangTriple}${androidApiLevel.get()}"
            val command = listOf(
                go.absolutePath,
                "build",
                "-trimpath",
                "-buildvcs=false",
                "-buildmode=pie",
                "-ldflags=-X main.version=0.16.0-pingng",
                "-o",
                targetOutput.absolutePath,
                ".",
            )
            val processBuilder = ProcessBuilder(command)
                .directory(source)
                .redirectErrorStream(true)
            val env = processBuilder.environment()
            env["GOOS"] = "android"
            env["GOARCH"] = target.goArch
            env["CGO_ENABLED"] = "1"
            env["CC"] = normalizedCompilerCommand
            env["CGO_LDFLAGS"] = "-Wl,-z,max-page-size=16384"
            env["GOFLAGS"] = "-mod=readonly"
            env["GOTOOLCHAIN"] = "local"
            env["GOMODCACHE"] = goModCache.absolutePath
            env["GOCACHE"] = goBuildCache.absolutePath
            // Retry public module mirrors after any proxy error, including HTTP 403.
            env["GOPROXY"] = System.getenv("GOPROXY")?.takeIf { it.isNotBlank() }
                ?: "https://proxy.golang.org|https://goproxy.io|direct"
            target.goArm?.let { env["GOARM"] = it } ?: env.remove("GOARM")
            val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
            env[pathKey] = toolchainBin.absolutePath + File.pathSeparator + env[pathKey].orEmpty()

            logger.lifecycle("Building Endpoint Scanner ${goVersion.get()} for ${target.abi} using Android NDK ${ndkVersion.get()}...")
            val process = processBuilder.start()
            val buildOutput = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "Endpoint Scanner build failed for ${target.abi} (exit $exitCode):\n$buildOutput"
            }
            validateScannerBinary(targetOutput, target)
        }
    }

    private fun currentHost(): Host {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val arch = when (archName) {
            "amd64", "x86_64", "x64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Automatic Go toolchain setup does not support host CPU '$archName'")
        }
        return when {
            osName.startsWith("windows") -> Host("windows", arch, listOf("windows-x86_64", "windows-arm64"))
            osName.startsWith("mac") || osName.startsWith("darwin") ->
                Host("darwin", arch, listOf("darwin-x86_64", "darwin-arm64"))
            osName.startsWith("linux") -> Host("linux", arch, listOf("linux-x86_64", "linux-aarch64", "linux-arm64"))
            else -> error("Automatic Go toolchain setup does not support host OS '${System.getProperty("os.name")}'")
        }
    }

    private fun resolveGo(host: Host): File {
        val version = goVersion.get()
        check(version == "1.26.3") { "No verified Go archive is configured for Go $version" }
        val packageHashes = mapOf(
            "windows-amd64" to "20d2ceafb4ed41b96b879010927b28bc92a5be57a7c1801ce365a9ca51d3224a",
            "windows-arm64" to "95cd63bc6b0da77409ba819215afea9ddf5702c55a3b20af3dd90ea95c7b130c",
            "darwin-amd64" to "278d580b32e299fe4a9c990fcf2d02acfe538c7e551a6ee18f9c7164573d2c63",
            "darwin-arm64" to "875cf54a15311eee2c99b9dd67c68c4a49351d489ab622bf2cfd28c8f2078d3c",
            "linux-amd64" to "2b2cfc7148493da5e73981bffbf3353af381d5f93e789c82c79aff64962eb556",
            "linux-arm64" to "9d89a3ea57d141c2b22d70083f2c8459ba3890f2d9e818e7e933b75614936565",
        )
        val expectedHash = packageHashes[host.key]
            ?: error("No verified Go $version distribution is configured for ${host.key}")
        val archiveName = "go$version.${host.os}-${host.arch}." + if (host.os == "windows") "zip" else "tar.gz"
        val cacheRoot = File(project.gradle.gradleUserHomeDir, "caches/pingng/toolchains/go-$version")
        val installDirectory = File(cacheRoot, host.key)
        val goExecutable = File(installDirectory, "go/bin/go" + if (host.os == "windows") ".exe" else "")
        if (isExpectedGo(goExecutable, version)) return goExecutable

        cacheRoot.mkdirs()
        val archive = File(cacheRoot, archiveName)
        val partialArchive = File(cacheRoot, "$archiveName.download")
        if (!archive.isFile || sha256(archive) != expectedHash) {
            archive.delete()
            partialArchive.delete()
            logger.lifecycle("Downloading the verified Go $version toolchain from go.dev (first build only)...")
            val connection = URI("https://go.dev/dl/$archiveName").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 600_000
            connection.instanceFollowRedirects = true
            try {
                connection.inputStream.use { input -> partialArchive.outputStream().use { output -> input.copyTo(output) } }
            } finally {
                connection.disconnect()
            }
            check(sha256(partialArchive) == expectedHash) {
                partialArchive.delete()
                "SHA-256 verification failed for the official Go archive $archiveName"
            }
            check(partialArchive.renameTo(archive)) { "Could not cache Go archive at $archive" }
        }

        val stagingDirectory = File(cacheRoot, "${host.key}.extracting")
        stagingDirectory.deleteRecursively()
        stagingDirectory.mkdirs()
        val archiveTree = if (archive.extension.equals("zip", ignoreCase = true)) {
            project.zipTree(archive)
        } else {
            project.tarTree(project.resources.gzip(archive))
        }
        project.copy {
            from(archiveTree)
            into(stagingDirectory)
        }
        val stagedGoRoot = File(stagingDirectory, "go")
        val stagedGoExecutable = File(stagedGoRoot, "bin/go" + if (host.os == "windows") ".exe" else "")
        check(stagedGoExecutable.isFile) { "Go archive did not extract a valid toolchain to $stagedGoExecutable" }
        if (host.os != "windows") {
            listOf(File(stagedGoRoot, "bin"), File(stagedGoRoot, "pkg/tool")).forEach { toolDirectory ->
                toolDirectory.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, true) }
            }
        }
        installDirectory.deleteRecursively()
        check(stagingDirectory.renameTo(installDirectory)) {
            "Could not install the Go toolchain under $installDirectory"
        }
        archive.delete()
        check(isExpectedGo(goExecutable, version)) { "Installed Go toolchain is not Go $version: $goExecutable" }
        return goExecutable
    }

    private fun isExpectedGo(executable: File, version: String): Boolean {
        return try {
            if (!executable.isFile) {
                false
            } else {
                val process = ProcessBuilder(executable.absolutePath, "version").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor() == 0 && output.contains("go$version")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun validateScannerBinary(file: File, target: AndroidTarget) {
        check(file.isFile && file.length() >= 1024) { "Endpoint Scanner produced no executable for ${target.abi}: $file" }
        val header = file.inputStream().use { it.readNBytes(20) }
        check(header.size == 20 && header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte() && (header[4].toInt() and 0xff) == target.elfClass &&
            header[5] == 1.toByte() && header[16] == 3.toByte() && header[17] == 0.toByte() &&
            (header[18].toInt() and 0xff) == target.elfMachine && header[19] == 0.toByte()) {
            "Endpoint Scanner output has an invalid Android PIE ELF header for ${target.abi}: $file"
        }
        val contents = file.readBytes().toString(Charsets.ISO_8859_1)
        check(contents.contains("/system/bin/linker")) {
            "Endpoint Scanner output for ${target.abi} is not linked for Android"
        }
        check(!contents.contains("serve one endpoint as a local SOCKS5 proxy")) {
            "The obsolete local SOCKS5 server is still present in the ${target.abi} scanner binary"
        }
    }
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
    .orElse("26.10.10")
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

// Endpoint Scanner is rebuilt from the bundled source during Android packaging. This
// keeps stale scanner executables out of app/libs and the resulting APK.
val endpointScannerNdkVersion = "30.0.14904198"
val endpointScannerSourceDirectory = rootProject.layout.projectDirectory.dir("native/endpointscanner/engine")
val endpointScannerJniLibsDirectory = layout.buildDirectory.dir("generated/endpointScanner/jniLibs")
val bepassSourceDir = rootProject.layout.projectDirectory.dir("native/bepass")
val bepassRuntimeAssetsDir = layout.buildDirectory.dir("generated/bepassRuntime/assets")
val bepassRuntimeJniLibsDir = layout.buildDirectory.dir("generated/bepassRuntime/jniLibs")

if (!isValidAar(libV2rayFile)) {
    check(downloadValidAar(
        libV2rayFile,
        "https://github.com/2dust/AndroidLibXrayLite/releases/download/v$xrayAndroidLibVersion/libv2ray.aar",
    )) { "libv2ray.aar is missing or corrupted, and a valid replacement could not be downloaded" }
}

ensureAmneziaWgNativeLibraries()

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37
    ndkVersion = endpointScannerNdkVersion

    defaultConfig {
        applicationId = "com.pingng.android"
        minSdk = 24
        targetSdk = 37
        // Keep versionCode monotonic for Pi46 while preserving ABI-specific APK codes.
        versionCode = 767
        // PingNG Pi46 is synchronized with official v2rayNG 2.3.11.
        versionName = "v2.3.11-Pi46"
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
            "az",
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
            keepDebugSymbols.add("**/libpingng_bepass.so")
        }
    }

}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val d8ExecutableName = if (isWindowsHost) "d8.bat" else "d8"
val d8ExecutableProvider = providers.provider {
    val buildTools = android.sdkDirectory.resolve("build-tools")
        .listFiles()
        ?.filter { File(it, d8ExecutableName).isFile }
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
    }) ?: error("Android SDK build-tools with $d8ExecutableName are required to build the Snowflake runtime")
    File(selectedBuildTools, d8ExecutableName)
}

val prepareSnowflakeRuntime = tasks.register<PrepareSnowflakeRuntimeTask>("prepareSnowflakeRuntime") {
    iptProxyAar.from(iptProxyRuntimeConfiguration)
    assetsDirectory.set(snowflakeRuntimeAssetsDir)
    jniLibsDirectory.set(snowflakeRuntimeJniLibsDir)
    d8Executable.set(layout.file(d8ExecutableProvider))
    windowsHost.set(isWindowsHost)
}

val buildEndpointScannerAndroid = tasks.register<BuildEndpointScannerTask>("buildEndpointScannerAndroid") {
    scannerSourceDirectory.set(endpointScannerSourceDirectory)
    jniLibsDirectory.set(endpointScannerJniLibsDirectory)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    ndkVersion.set(endpointScannerNdkVersion)
    goVersion.set("1.26.3")
    androidApiLevel.set(24)
    hostIdentifier.set("${System.getProperty("os.name")}-${System.getProperty("os.arch")}")
}

val prepareBepassRuntime = tasks.register<PrepareBepassRuntimeTask>("prepareBepassRuntime") {
    bepassSourceDirectory.set(bepassSourceDir)
    assetsDirectory.set(bepassRuntimeAssetsDir)
    jniLibsDirectory.set(bepassRuntimeJniLibsDir)
    d8Executable.set(layout.file(d8ExecutableProvider))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    windowsHost.set(isWindowsHost)
    dependsOn(buildEndpointScannerAndroid)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareSnowflakeRuntime,
            PrepareSnowflakeRuntimeTask::assetsDirectory,
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareSnowflakeRuntime,
            PrepareSnowflakeRuntimeTask::jniLibsDirectory,
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildEndpointScannerAndroid,
            BuildEndpointScannerTask::jniLibsDirectory,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareBepassRuntime,
            PrepareBepassRuntimeTask::assetsDirectory,
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareBepassRuntime,
            PrepareBepassRuntimeTask::jniLibsDirectory,
        )
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && (name.endsWith("Assets") || name.endsWith("JniLibFolders"))) {
        dependsOn(prepareSnowflakeRuntime)
        dependsOn(prepareBepassRuntime)
    }
    // Explicit task edges keep AGP 9 from reporting generated JNI libraries
    // as an implicit dependency during merge or lint tasks.
    if (name.startsWith("merge") && (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))) {
        dependsOn(buildEndpointScannerAndroid)
        dependsOn(prepareBepassRuntime)
    }

    // Keep an explicit edge for LintVital analyzers as an AGP 9 safeguard.
    // The generated-source API above is the primary wiring for assets and JNI libs.
    if (name.startsWith("lintVitalAnalyze", ignoreCase = true)) {
        dependsOn(prepareSnowflakeRuntime)
        dependsOn(buildEndpointScannerAndroid)
        dependsOn(prepareBepassRuntime)
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

    // Lightweight primitives for WireGuard discovery on Android API 24+.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

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
