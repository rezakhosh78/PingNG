package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException

/**
 * Runs the MIT-licensed MASQUE core as a small local SOCKS hop.
 *
 * Xray does not implement Cloudflare's WARP MASQUE dialect. Keeping the core in
 * a separate process lets the existing VPN/TUN and routing code remain intact
 * while still using real MASQUE over TCP/HTTP2 on the wire.
 */
object WarpMasqueBridge {
    private const val TAG = "WarpMasqueCore"
    private const val REGISTER_TIMEOUT_SECONDS = 45L
    private const val START_TIMEOUT_MILLIS = 6_000

    @Volatile
    private var activeProcess: Process? = null
    @Volatile
    private var activeGuid: String? = null
    @Volatile
    private var activeConfigSignature: String? = null
    @Volatile
    private var registrationProcess: Process? = null
    @Volatile
    private var startupProcess: Process? = null
    private val startupCancelled = AtomicBoolean(false)

    /** Lets the connect button interrupt a slow MASQUE registration/endpoint scan. */
    fun cancelStartup() {
        startupCancelled.set(true)
        registrationProcess?.let(::destroyProcess)
        startupProcess?.let(::destroyProcess)
        EndpointScannerCli.cancelActiveScans()
    }

    private fun checkStartupActive() {
        if (startupCancelled.get()) throw CancellationException("WARP connection canceled")
    }

    /**
     * Registers the MASQUE device before Android's VPN interface is created.
     * A registration request made after TUN setup can be routed into the
     * not-yet-running WARP tunnel and wait until the startup timeout.
     */
    fun prepareRegistration(context: Context, guid: String, profile: ProfileItem) {
        startupCancelled.set(false)
        val binary = resolveBinary(context)
        val runtimeDir = File(context.filesDir, "warp-masque").apply { mkdirs() }
        val configFile = File(runtimeDir, "$guid.json")
        ensureRegistered(binary, runtimeDir, configFile, profile)
        persistProfile(guid, profile)
    }

    /** Creates a MASQUE registration using the local SOCKS proxy of another running PingNG profile. */
    @Synchronized
    fun registerWithProxy(context: Context, guid: String, profile: ProfileItem, socksPort: Int): Boolean {
        require(socksPort in 1..65535) { "Registration Proxy SOCKS port is invalid" }
        startupCancelled.set(false)
        val binary = resolveBinary(context)
        val runtimeDir = File(context.filesDir, "warp-masque").apply { mkdirs() }
        val configFile = File(runtimeDir, "$guid.json")
        ensureRegistered(
            binary = binary,
            runtimeDir = runtimeDir,
            configFile = configFile,
            profile = profile,
            registrationProxy = "socks5://127.0.0.1:$socksPort",
            registrationTimeoutSeconds = REGISTER_TIMEOUT_SECONDS,
        )
        persistProfile(guid, profile)
        return true
    }

    @Synchronized
    fun startIfNeeded(context: Context, guid: String, profile: ProfileItem) {
        val current = activeProcess
        val requestedSignature = startupSignature(profile)
        if (current?.isAlive == true && activeGuid == guid && activeConfigSignature == requestedSignature) return
        startupCancelled.set(false)
        stop()

        val binary = resolveBinary(context)
        val runtimeDir = File(context.filesDir, "warp-masque").apply { mkdirs() }
        val configFile = File(runtimeDir, "${guid}.json")
        ensureRegistered(binary, runtimeDir, configFile, profile)
        persistProfile(guid, profile)

        val configuredPrimary = profile.warpMasquePrimaryEndpoint?.trim().orEmpty()
        val primary = if (profile.warpMasqueScanPreparedOnce == true) {
            // The UI process already scanned this connection and displayed its
            // progress. Consume its persisted result without a second scan in
            // the service process.
            profile.warpMasqueScanPreparedOnce = false
            persistProfile(guid, profile)
            parseCandidates(profile.warpMasqueSelectedEndpoint.orEmpty()).firstOrNull()
                ?: throw IllegalStateException("Prepared WARP MASQUE endpoint is invalid")
        } else if (!WarpMasqueConfig.shouldAutoScan(profile)) {
            val saved = profile.warpMasqueSelectedEndpoint?.trim().takeUnless { it.isNullOrBlank() }
            val configured = configuredPrimary.takeIf {
                it.isNotBlank() && (it != WarpMasqueConfig.DEFAULT_ENDPOINT || saved.isNullOrBlank())
            }?.let { value ->
                val parsed = com.v2ray.ang.fmt.AmneziaWgFmt.splitEndpoint(value)
                val alreadyHasPort = value.startsWith("[") && parsed.second?.toIntOrNull() in 1..65535 ||
                    value.count { it == ':' } == 1 && parsed.second?.toIntOrNull() in 1..65535
                if (alreadyHasPort) value else {
                    val host = value.removePrefix("[").removeSuffix("]")
                    "${com.v2ray.ang.util.Utils.getIpv6Address(host)}:${profile.warpMasqueEndpointPort ?: WarpMasqueConfig.DEFAULT_PORT}"
                }
            }
            parseCandidates(configured ?: saved ?: WarpMasqueConfig.PRIMARY_ENDPOINT).firstOrNull()
                ?: throw IllegalStateException("Invalid manual WARP MASQUE endpoint")
        } else {
            val sni = WarpMasqueConfig.normalizeSni(profile.warpMasqueSni)
            checkStartupActive()
            LogUtil.i(AppConfig.TAG, "$TAG searching for an H2 endpoint")
            val scannedEndpoint = try {
                val candidates = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    EndpointScannerCli.scanMasqueH2Endpoints(context, guid, sni, EndpointScanSettings.load(guid))
                }
                profile.awgEndpointCandidates = EndpointScannerCli.encodeCandidates(candidates)
                candidates.first().endpoint.also {
                    AwgEndpointScanState.complete(guid, "Endpoint selected: $it", it)
                }
            } catch (error: Throwable) {
                checkStartupActive()
                AwgEndpointScanState.fail(guid, "MASQUE/H2 endpoint scan failed")
                PingNgDiagnostics.record("MASQUE/H2 endpoint scan failed", error)
                throw error
            }
            checkStartupActive()
            profile.warpMasqueSelectedEndpoint = scannedEndpoint
            persistProfile(guid, profile)
            parseCandidates(scannedEndpoint).firstOrNull()
                ?: throw IllegalStateException("Endpoint scan returned an invalid address")
        }

        var lastFailure: Throwable? = null
        try {
            checkStartupActive()
            LogUtil.i(AppConfig.TAG, "$TAG starting selected H2 endpoint " + primary.rendered)
            val started = startEndpoint(binary, configFile, runtimeDir, primary, profile)
            if (started != null && activate(started.process, guid, profile, primary)) return
            lastFailure = IllegalStateException("selected endpoint failed the H2 data-plane check")
        } catch (error: Throwable) {
            lastFailure = error
            LogUtil.w(AppConfig.TAG, "$TAG selected endpoint " + primary.rendered + " failed: " + error.message)
        }

        // Retry the same scanned endpoint directly if the Desync layer interferes with H2.
        val desyncEnabled = PingNgCompat.isNativeDesyncEnabled(profile)
        if (desyncEnabled && context !is com.v2ray.ang.service.CoreProxyOnlyService) {
            PingNgDiagnostics.record(
                "WARP MASQUE selected endpoint rejected the Desync profile; retrying without outer Desync",
            )
            try {
                checkStartupActive()
                val direct = startEndpoint(
                    binary, configFile, runtimeDir, primary, profile,
                    routeThroughDesync = false,
                )
                if (direct != null && activate(direct.process, guid, profile, primary)) return
            } catch (error: Throwable) {
                lastFailure = error
                LogUtil.w(AppConfig.TAG, "$TAG direct retry for " + primary.rendered + " failed: " + error.message)
            }
        }

        stop()
        throw IllegalStateException(
            "WARP MASQUE could not establish an HTTP/2 tunnel" +
                (lastFailure?.message?.let { ": $it" } ?: "")
        )
    }

    @Synchronized
    fun stop() {
        activeProcess?.let { process ->
            runCatching { process.destroy() }
            runCatching { process.waitFor(700, TimeUnit.MILLISECONDS) }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
        activeProcess = null
        activeGuid = null
        activeConfigSignature = null
    }

    private fun resolveBinary(context: Context): File {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libwarpmasque.so")
        val supportedAbi = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        check(supportedAbi in android.os.Build.SUPPORTED_ABIS) {
            "WARP MASQUE requires an ARM64 or ARMv7 device; supported device ABIs: " +
                android.os.Build.SUPPORTED_ABIS.joinToString()
        }
        check(binary.isFile && binary.canExecute()) {
            "WARP MASQUE core for $supportedAbi was not extracted from the APK. Install the matching ABI APK " +
                "built with extractNativeLibs=true (expected: ${binary.absolutePath})"
        }
        return binary
    }

    private fun ensureRegistered(
        binary: File,
        runtimeDir: File,
        configFile: File,
        profile: ProfileItem,
        registrationProxy: String? = null,
        registrationTimeoutSeconds: Long = REGISTER_TIMEOUT_SECONDS,
    ) {
        val suppliedConfig = profile.warpMasqueConfigJson?.trim().orEmpty()
        if (suppliedConfig.isNotBlank()) {
            val suppliedRoot = JsonUtil.parseString(suppliedConfig)
                ?: throw IllegalStateException("Existing MASQUE config JSON is invalid")
            configFile.writeText(JsonUtil.toJsonPretty(suppliedRoot).orEmpty())
            check(isRegisteredConfig(configFile)) {
                "Existing MASQUE config JSON is missing private_key, access_token or id"
            }
            return
        }
        if (isRegisteredConfig(configFile)) {
            if (profile.warpMasqueConfigJson.isNullOrBlank()) {
                profile.warpMasqueConfigJson = configFile.readText()
            }
            return
        }
        // A killed/timeout registration can leave a partial JSON file behind.
        // Do not let that stale file make every later start skip registration.
        if (configFile.exists()) runCatching { configFile.delete() }
        // The MASQUE core tries to load the path passed to -c before entering
        // register. Passing a not-yet-existing destination therefore prevents
        // registration entirely. Run register in an isolated directory using
        // its documented default config.json, then move the result atomically
        // to the per-profile file used by socks mode.
        val registrationConfig = File(runtimeDir, "config.json")
        if (registrationConfig.exists()) runCatching { registrationConfig.delete() }
        val registrationDeviceName = profile.warpMasqueDeviceName?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: WarpMasqueConfig.randomDeviceName()
        val registerCommand = ProcessBuilder(
            binary.absolutePath,
            "register",
            "-n", registrationDeviceName,
            "--accept-tos",
        ).directory(runtimeDir).redirectErrorStream(true)
        registrationProxy?.let { proxy ->
            registerCommand.environment().apply {
                put("HTTP_PROXY", proxy)
                put("HTTPS_PROXY", proxy)
                put("http_proxy", proxy)
                put("https_proxy", proxy)
            }
        }
        val register = registerCommand.start()
        registrationProcess = register
        val output = StringBuilder()
        consumeLog(register, null, output)
        // Android services do not have an interactive TTY. The explicit flag
        // makes registration deterministic and prevents the child process
        // from waiting for a hidden Terms-of-Service prompt.
        runCatching { register.outputStream.close() }
        if (!register.waitFor(registrationTimeoutSeconds, TimeUnit.SECONDS)) {
            runCatching { register.destroy() }
            runCatching { register.waitFor(500, TimeUnit.MILLISECONDS) }
            if (register.isAlive) runCatching { register.destroyForcibly() }
            val detail = synchronized(output) { output.toString().trim() }
            throw IllegalStateException(
                "WARP MASQUE registration timed out" +
                    detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            )
        }
        registrationProcess = null
        checkStartupActive()
        check(register.exitValue() == 0 && registrationConfig.isFile) {
            "WARP MASQUE registration failed: ${synchronized(output) { output.toString().trim() }}"
        }
        registrationConfig.copyTo(configFile, overwrite = true)
        runCatching { registrationConfig.delete() }
        check(isRegisteredConfig(configFile)) {
            "WARP MASQUE registration returned an invalid config"
        }
        profile.warpMasqueConfigJson = configFile.readText()
        profile.warpMasqueDeviceName = registrationDeviceName
    }

    private fun isRegisteredConfig(configFile: File): Boolean = runCatching {
        val root = JsonUtil.parseString(configFile.readText())?.asJsonObject ?: return@runCatching false
        listOf("private_key", "access_token", "id")
            .all { key -> root.get(key)?.asString?.isNotBlank() == true }
    }.getOrDefault(false)

    private fun patchEndpoint(
        configFile: File,
        endpoint: Endpoint,
        http2Enabled: Boolean,
        desyncProxy: String?,
    ) {
        val root = JsonUtil.parseString(configFile.readText())?.asJsonObject
            ?: JsonObject()
        // Keep the normal endpoint in sync for core versions that use it
        // during registration or reconnect before entering HTTP/2 mode.
        val isIpv6 = endpoint.host.contains(':')
        root.addProperty(if (isIpv6) "endpoint_v6" else "endpoint_v4", endpoint.host)
        if (http2Enabled) {
            root.addProperty(if (isIpv6) "endpoint_h2_v6" else "endpoint_h2_v4", endpoint.host)
        }
        if (desyncProxy == null) root.remove("http_proxy") else root.addProperty("http_proxy", desyncProxy)
        configFile.writeText(JsonUtil.toJsonPretty(root).orEmpty())
    }

    private fun startEndpoint(
        binary: File,
        configFile: File,
        runtimeDir: File,
        endpoint: Endpoint,
        profile: ProfileItem,
        routeThroughDesync: Boolean = true,
    ): StartedEndpoint? {
        val http2Enabled = profile.warpMasqueHttp2Enabled ?: true
        val listenPort = socksPort(profile)
        val desyncProxy = if (routeThroughDesync && PingNgCompat.isNativeDesyncEnabled(profile)) {
            WarpMasqueDesyncProxy.proxyUrlForEndpoint(endpoint.host, endpoint.port)
        } else null
        val upstreamProxy = desyncProxy
        patchEndpoint(configFile, endpoint, http2Enabled, upstreamProxy)
        val args = mutableListOf(
            binary.absolutePath,
            "-c", configFile.absolutePath,
            "socks",
            "-b", socksBind(profile),
            "-p", listenPort.toString(),
            // The SOCKS listen port (-p) is different from the remote MASQUE
            // endpoint port. Without -P the usque core defaults to 443, so a
            // manually entered endpoint port was silently ignored.
            "-P", endpoint.port.toString(),
        )
        val configuredSni = WarpMasqueConfig.normalizeSni(profile.warpMasqueSni)
        profile.warpMasqueSni = configuredSni
        configuredSni.let {
            args += listOf("-s", it)
        }
        dnsServers(profile).forEach { dns -> args += listOf("-d", dns) }
        if (http2Enabled) args += "--http2"
        var process: Process? = null
        try {
            val builder = ProcessBuilder(args).directory(runtimeDir).redirectErrorStream(true)
            if (upstreamProxy != null) {
                builder.environment().apply {
                    put("HTTP_PROXY", upstreamProxy)
                    put("HTTPS_PROXY", upstreamProxy)
                    put("http_proxy", upstreamProxy)
                    put("https_proxy", upstreamProxy)
                }
            }
            val startedProcess = builder.start()
            process = startedProcess
            startupProcess = startedProcess
            val socksListening = AtomicBoolean(false)
            val masqueConnected = AtomicBoolean(false)
            consumeLog(
                startedProcess,
                endpoint,
                connected = masqueConnected,
                listening = socksListening,
            )
            if (!waitForSocksListener(startedProcess, socksListening)) {
                destroyProcess(startedProcess)
                return null
            }
            checkStartupActive()
            val warmupSocket = openWarmupSocks(profile, listenPort)
            val ready = warmupSocket != null && waitForMasque(startedProcess, masqueConnected)
            runCatching { warmupSocket?.close() }
            if (!ready) {
                destroyProcess(startedProcess)
                return null
            }
            checkStartupActive()
            return StartedEndpoint(startedProcess, endpoint)
        } catch (error: Throwable) {
            process?.let(::destroyProcess)
            throw error
        } finally {
            startupProcess = null
        }
    }

    private fun destroyProcess(process: Process) {
        runCatching { process.destroy() }
        runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    private fun activate(
        process: Process,
        guid: String,
        profile: ProfileItem,
        endpoint: Endpoint,
    ): Boolean {
        activeProcess = process
        activeGuid = guid
        activeConfigSignature = startupSignature(profile)
        profile.warpMasqueSelectedEndpoint = endpoint.rendered
        profile.server = endpoint.host
        profile.serverPort = endpoint.port.toString()
        persistProfile(guid, profile)
        LogUtil.i(AppConfig.TAG, "$TAG connected via ${endpoint.rendered}")
        return true
    }

    private fun startupSignature(profile: ProfileItem): String = listOf(
        profile.warpMasqueEndpointMode,
        profile.warpMasquePrimaryEndpoint,
        profile.warpMasqueEndpointPort,
        profile.warpMasqueEndpointCandidates,
        profile.warpMasqueSni,
        profile.warpMasqueHttp2Enabled,
        profile.warpMasqueDeviceName,
        profile.warpMasqueDns,
        profile.warpMasqueConfigJson,
        profile.pingNgProfile,
        profile.pingNgDesyncArgs,
    ).joinToString("\u0000")

    private fun persistProfile(guid: String, profile: ProfileItem) {
        if (profile.warpRegistrationInternalProxy == true) {
            MmkvManager.encodeEphemeralServerConfig(guid, profile)
        } else {
            MmkvManager.encodeServerConfig(guid, profile)
        }
    }

    private fun waitForSocksListener(process: Process, listening: AtomicBoolean): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            checkStartupActive()
            if (!process.isAlive) return false
            if (listening.get()) return true
            Thread.sleep(60)
        }
        return false
    }

    /**
     * The MASQUE core establishes its session only after the first SOCKS request.
     * The warm-up request is kept open by the caller while the core completes the
     * HTTP/2/MASQUE handshake and emits its successful connection log.
     */
    private fun waitForMasque(process: Process, connected: AtomicBoolean): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            checkStartupActive()
            if (!process.isAlive) return false
            if (connected.get()) return true
            Thread.sleep(60)
        }
        return false
    }

    /**
     * The MASQUE core opens the connection lazily, after the first SOCKS request.
     * Keep this request open while waiting for its real HTTP/2 handshake.
     * We intentionally do not require the remote destination to answer: an
     * endpoint is healthy once the MASQUE control/data path is established.
     */
    private fun openWarmupSocks(profile: ProfileItem, port: Int): Socket? = runCatching {
        Socket().also { socket ->
            socket.soTimeout = 2_000
            socket.connect(
                InetSocketAddress(socksProbeHost(profile), port),
                2_000,
            )
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val methodReply = ByteArray(2)
            readFully(input, methodReply)
            check((methodReply[0].toInt() and 0xff) == 5 && (methodReply[1].toInt() and 0xff) == 0) {
                "MASQUE SOCKS5 negotiation rejected"
            }
            // Literal IP avoids a DNS dependency during endpoint selection.
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, 0x01, 0xBB.toByte()))
            output.flush()
        }
    }.getOrNull()

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            check(read > 0) { "SOCKS5 warmup closed before reply" }
            offset += read
        }
    }

    private fun consumeLog(
        process: Process,
        endpoint: Endpoint?,
        collector: StringBuilder? = null,
        connected: AtomicBoolean? = null,
        listening: AtomicBoolean? = null,
    ) {
        Thread {
            runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        collector?.let {
                            synchronized(it) {
                                if (it.length < 8_000) it.appendLine(line)
                            }
                        }
                        val normalized = line.lowercase()
                        if (normalized.contains("connected to masque server")) {
                            connected?.set(true)
                        }
                        if (normalized.contains("socks proxy listening")) {
                            listening?.set(true)
                        }
                        val important = endpoint == null || listOf(
                            "http/2 mode enabled",
                            "using http/2 endpoint",
                            "socks proxy listening",
                            "connected to masque server",
                            "failed",
                            "error",
                            "fatal",
                            "unable",
                        ).any { marker -> normalized.contains(marker) }
                        val message = "$TAG${endpoint?.let { "[${it.rendered}]" } ?: ""}: $line"
                        if (important) LogUtil.i(AppConfig.TAG, message) else LogUtil.d(AppConfig.TAG, message)
                    }
                }
            }
        }.apply {
            name = "pingng-warp-masque-log"
            isDaemon = true
            start()
        }
    }

    private data class Endpoint(val host: String, val port: Int) {
        val rendered: String get() = "${com.v2ray.ang.util.Utils.getIpv6Address(host)}:$port"
    }

    private data class StartedEndpoint(
        val process: Process,
        val endpoint: Endpoint,
    )

    private fun socksBind(profile: ProfileItem): String =
        profile.warpMasqueSocksBind?.trim()?.takeIf { it.isNotBlank() }
            ?: WarpMasqueConfig.DEFAULT_SOCKS_BIND

    private fun socksPort(profile: ProfileItem): Int =
        (profile.warpMasqueSocksPort ?: WarpMasqueConfig.DEFAULT_SOCKS_PORT)
            .coerceIn(1, 65535)

    private fun socksProbeHost(profile: ProfileItem): String =
        when (val bind = socksBind(profile)) {
            "0.0.0.0", "::", "[::]" -> "127.0.0.1"
            else -> bind.removePrefix("[").removeSuffix("]")
        }

    private fun dnsServers(profile: ProfileItem): List<String> =
        profile.warpMasqueDns.orEmpty()
            .split(',', ';', '\n', '\r', ' ', '\t')
            .map(String::trim)
            .filter(String::isNotBlank)

    private fun parseCandidates(raw: String?): List<Endpoint> {
        val value = raw.orEmpty().ifBlank { WarpMasqueConfig.DEFAULT_ENDPOINTS }
        return value.split(',', ';', '\n', '\r', ' ', '\t')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .flatMap { expandToken(it).asSequence() }
            .distinctBy { it.rendered }
            .toList()
    }

    private fun expandToken(token: String): List<Endpoint> {
        val normalized = token.trim()
        if (normalized.startsWith("[")) {
            val close = normalized.indexOf(']')
            if (close > 0 && normalized.getOrNull(close + 1) == ':') {
                val port = normalized.substring(close + 2).toIntOrNull()
                if (port != null && port in 1..65535)
                    return listOf(Endpoint(normalized.substring(1, close), port))
            }
            return emptyList()
        }
        val slash = normalized.indexOf('/')
        if (slash > 0) {
            val base = normalized.substring(0, slash)
            val prefix = normalized.substring(slash + 1).substringBefore(':').toIntOrNull()
            val port = normalized.substringAfter(':', "443").toIntOrNull() ?: 443
            if (prefix == 24 && base.split('.').size == 4) {
                val octets = base.split('.').mapNotNull(String::toIntOrNull)
                if (octets.size == 4) {
                    return (1..254).map { host -> Endpoint("${octets[0]}.${octets[1]}.${octets[2]}.$host", port) }
                }
            }
        }
        val bareIpv6 = normalized.count { it == ':' } > 1
        val port = if (bareIpv6) 443 else normalized.substringAfterLast(':', "443").toIntOrNull() ?: 443
        val host = if (bareIpv6) normalized else normalized.substringBeforeLast(':')
        return if (host.isNotBlank()) listOf(Endpoint(host, port)) else emptyList()
    }
}
