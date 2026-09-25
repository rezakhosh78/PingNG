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
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    private const val MAX_PARALLEL_MASQUE_ATTEMPTS = 6
    private const val MAX_PARALLEL_ENDPOINT_PROBES = 64
    private const val MAX_DESYNC_FALLBACK_PROBES = 3

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

        val configuredCandidates = profile.warpMasqueEndpointCandidates?.trim()
        val candidateSource = if (
            configuredCandidates.isNullOrBlank() ||
            configuredCandidates == WarpMasqueConfig.LEGACY_DEFAULT_ENDPOINTS
        ) {
            WarpMasqueConfig.DEFAULT_ENDPOINTS
        } else {
            configuredCandidates
        }
        val candidates = parseCandidates(candidateSource)

        val configuredPrimary = profile.warpMasquePrimaryEndpoint?.trim().orEmpty()
        val manualPrimary = configuredPrimary.isNotBlank() &&
            configuredPrimary != WarpMasqueConfig.DEFAULT_ENDPOINT &&
            configuredPrimary != WarpMasqueConfig.PRIMARY_ENDPOINT
        val manualList = candidateSource != WarpMasqueConfig.DEFAULT_ENDPOINTS &&
            candidates.size == 1
        val customMode = profile.warpMasqueEndpointMode == WarpMasqueConfig.ENDPOINT_MODE_CUSTOM
        val legacyManualMode = profile.warpMasqueEndpointMode == null
        val ordered = if (customMode) {
            val customHost = profile.warpMasquePrimaryEndpoint?.trim().orEmpty()
            val customPort = profile.warpMasqueEndpointPort ?: WarpMasqueConfig.DEFAULT_PORT
            parseCandidates("$customHost:$customPort").take(1)
        } else if (legacyManualMode && (manualPrimary || manualList)) {
            check(candidates.isNotEmpty()) { "WARP MASQUE endpoint list is empty" }
            listOf(if (manualPrimary) orderCandidates(candidates, profile).first() else candidates.first())
        } else {
            check(candidates.isNotEmpty()) { "WARP MASQUE endpoint list is empty" }
            orderCandidates(candidates, profile)
        }
        check(ordered.isNotEmpty()) { "Invalid custom WARP MASQUE endpoint" }

        // The requested primary endpoint gets a real MASQUE/data-plane attempt
        // first. A plain TCP connect is not enough to call an endpoint healthy.
        val primary = ordered.firstOrNull()
        var lastFailure: Throwable? = null
        if (primary != null) {
            try {
                checkStartupActive()
                LogUtil.i(
                    AppConfig.TAG,
                    "$TAG data-plane test 1/${ordered.size}: ${primary.rendered} (primary)",
                )
                val started = startEndpoint(binary, configFile, runtimeDir, primary, profile)
                LogUtil.i(
                    AppConfig.TAG,
                    "$TAG data-plane result 1/${ordered.size}: ${primary.rendered} " +
                        if (started != null) "healthy" else "failed",
                )
                if (started?.let {
                        activate(it.process, guid, profile, primary)
                    } == true) return
            } catch (error: Throwable) {
                lastFailure = error
                LogUtil.w(AppConfig.TAG, "$TAG primary endpoint ${primary.rendered} failed: ${error.message}")
            }
        }

        // Only after the primary fails do we scan the remaining range. The
        // TCP preflight is merely an ordering optimization; every candidate
        // still needs a real SOCKS -> HTTP/2 -> MASQUE handshake below.
        val fallbackCandidates = if (customMode) emptyList() else ordered.drop(1)
        checkStartupActive()
        // The TCP preflight only establishes reachability, never MASQUE health.
        // Keep the data-plane scan bounded so a completely blocked /24 cannot
        // leave the connection in discovery for hundreds of serial batches.
        val reachable = reachableCandidates(fallbackCandidates.take(96))
        val attempts = (reachable + fallbackCandidates.filterNot { it in reachable }).take(48)
        val desyncEnabled = PingNgCompat.isNativeDesyncEnabled(profile)
        val masqueAttempts = if (desyncEnabled) attempts.take(MAX_DESYNC_FALLBACK_PROBES) else attempts
        val healthy = findHealthyFallback(
            binary = binary,
            configFile = configFile,
            runtimeDir = runtimeDir,
            candidates = masqueAttempts,
            profile = profile,
            totalCandidates = ordered.size,
            completedBeforeStart = 1,
        )
        if (healthy != null) {
            try {
                checkStartupActive()
                // The parallel probes use temporary SOCKS ports. Restart the
                // winner on the profile's stable port before handing it to Xray.
                destroyStarted(healthy)
                val process = startEndpoint(binary, configFile, runtimeDir, healthy.endpoint, profile)
                if (process != null && activate(process.process, guid, profile, healthy.endpoint)) return
                lastFailure = IllegalStateException("winner did not start on the configured SOCKS port")
            } catch (error: Throwable) {
                lastFailure = error
                LogUtil.w(AppConfig.TAG, "$TAG endpoint ${healthy.endpoint.rendered} failed on final start: ${error.message}")
            }
        }

        // Some native Desync profiles intentionally split/fake the first TLS
        // flight. Cloudflare's MASQUE HTTP/2 control channel can reject those
        // profiles with EOF even though the CONNECT/SOCKS bridge is healthy.
        // Keep trying Desync first; if every Desync-routed endpoint fails,
        // make a small direct retry so enabling Desync cannot strand the VPN.
        if (desyncEnabled && context !is com.v2ray.ang.service.CoreProxyOnlyService) {
            PingNgDiagnostics.record(
                "WARP MASQUE endpoints rejected the selected Desync profile; retrying without outer Desync",
            )
            try {
                checkStartupActive()
                val directPrimary = startEndpoint(
                    binary, configFile, runtimeDir, primary ?: ordered.first(), profile,
                    routeThroughDesync = false,
                )
                if (directPrimary != null && activate(directPrimary.process, guid, profile, directPrimary.endpoint)) return
            } catch (error: Throwable) {
                lastFailure = error
                LogUtil.w(AppConfig.TAG, "$TAG direct retry for ${primary?.rendered} failed: ${error.message}")
            }

            checkStartupActive()
            val directCandidates = (listOfNotNull(primary) + attempts)
                .distinct()
                .take(MAX_PARALLEL_MASQUE_ATTEMPTS * 4)
            val directHealthy = findHealthyFallback(
                binary = binary,
                configFile = configFile,
                runtimeDir = runtimeDir,
                candidates = directCandidates,
                profile = profile,
                totalCandidates = directCandidates.size,
                completedBeforeStart = 0,
                routeThroughDesync = false,
            )
            if (directHealthy != null) {
                try {
                    checkStartupActive()
                    destroyStarted(directHealthy)
                    val process = startEndpoint(
                        binary, configFile, runtimeDir, directHealthy.endpoint, profile,
                        routeThroughDesync = false,
                    )
                    if (process != null && activate(process.process, guid, profile, directHealthy.endpoint)) return
                    lastFailure = IllegalStateException("direct MASQUE winner did not start on the configured SOCKS port")
                } catch (error: Throwable) {
                    lastFailure = error
                    LogUtil.w(AppConfig.TAG, "$TAG direct endpoint ${directHealthy.endpoint.rendered} failed: ${error.message}")
                }
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

    private fun resolveBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libwarpmasque.so").also {
            check(it.isFile) {
                "WARP MASQUE needs the bundled Android arm64 core; binary was not found"
            }
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
        root.addProperty("endpoint_v4", endpoint.host)
        if (http2Enabled) root.addProperty("endpoint_h2_v4", endpoint.host)
        if (desyncProxy == null) root.remove("http_proxy") else root.addProperty("http_proxy", desyncProxy)
        configFile.writeText(JsonUtil.toJsonPretty(root).orEmpty())
    }

    private fun startEndpoint(
        binary: File,
        configFile: File,
        runtimeDir: File,
        endpoint: Endpoint,
        profile: ProfileItem,
        portOverride: Int = socksPort(profile),
        routeThroughDesync: Boolean = true,
    ): StartedEndpoint? {
        val http2Enabled = profile.warpMasqueHttp2Enabled ?: true
        val finalPort = socksPort(profile)
        val attemptConfig = if (portOverride == finalPort) {
            configFile
        } else {
            File(
                runtimeDir,
                "attempt-${endpoint.host.replace(Regex("[^A-Za-z0-9_.-]"), "_")}-$portOverride.json",
            ).also { configFile.copyTo(it, overwrite = true) }
        }
        val desyncProxy = if (routeThroughDesync && PingNgCompat.isNativeDesyncEnabled(profile)) {
            WarpMasqueDesyncProxy.proxyUrlForEndpoint(endpoint.host, endpoint.port)
        } else null
        patchEndpoint(attemptConfig, endpoint, http2Enabled, desyncProxy)
        val args = mutableListOf(
            binary.absolutePath,
            "-c", attemptConfig.absolutePath,
            "socks",
            "-b", socksBind(profile),
            "-p", portOverride.toString(),
            // The SOCKS listen port (-p) is different from the remote MASQUE
            // endpoint port. Without -P the usque core defaults to 443, so a
            // manually entered endpoint port was silently ignored.
            "-P", endpoint.port.toString(),
        )
        val configuredSni = profile.warpMasqueSni?.trim()
            ?.takeIf { it.isNotBlank() && it != WarpMasqueConfig.LEGACY_DEFAULT_SNI }
            ?: WarpMasqueConfig.DEFAULT_SNI
        profile.warpMasqueSni = configuredSni
        configuredSni.let {
            args += listOf("-s", it)
        }
        dnsServers(profile).forEach { dns -> args += listOf("-d", dns) }
        if (http2Enabled) args += "--http2"
        var process: Process? = null
        try {
            val builder = ProcessBuilder(args).directory(runtimeDir).redirectErrorStream(true)
            if (desyncProxy != null) {
                builder.environment().apply {
                    put("HTTP_PROXY", desyncProxy)
                    put("HTTPS_PROXY", desyncProxy)
                    put("http_proxy", desyncProxy)
                    put("https_proxy", desyncProxy)
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
                if (attemptConfig != configFile) runCatching { attemptConfig.delete() }
                return null
            }
            checkStartupActive()
            val warmupSocket = openWarmupSocks(profile, portOverride)
            val ready = warmupSocket != null && waitForMasque(startedProcess, masqueConnected)
            runCatching { warmupSocket?.close() }
            if (!ready) {
                destroyProcess(startedProcess)
                if (attemptConfig != configFile) runCatching { attemptConfig.delete() }
                return null
            }
            checkStartupActive()
            return StartedEndpoint(startedProcess, endpoint, portOverride, attemptConfig)
        } catch (error: Throwable) {
            process?.let(::destroyProcess)
            if (attemptConfig != configFile) runCatching { attemptConfig.delete() }
            throw error
        } finally {
            startupProcess = null
        }
    }

    private fun findHealthyFallback(
        binary: File,
        configFile: File,
        runtimeDir: File,
        candidates: List<Endpoint>,
        profile: ProfileItem,
        totalCandidates: Int,
        completedBeforeStart: Int,
        routeThroughDesync: Boolean = true,
    ): StartedEndpoint? {
        if (candidates.isEmpty()) return null
        // The native Desync engine has process-global state. Parallel MASQUE
        // handshakes all traverse its single SOCKS listener and can corrupt or
        // reset each other's outer TLS probes, so serialize fallback attempts
        // whenever this profile routes through Desync.
        val parallelism = if (routeThroughDesync && PingNgCompat.isNativeDesyncEnabled(profile)) 1
            else MAX_PARALLEL_MASQUE_ATTEMPTS
        val executor = Executors.newFixedThreadPool(parallelism)
        val completedTests = AtomicInteger(completedBeforeStart)
        try {
            for ((batchIndex, batch) in candidates.chunked(parallelism).withIndex()) {
                checkStartupActive()
                val winner = AtomicReference<StartedEndpoint?>(null)
                val running = Collections.synchronizedSet(mutableSetOf<StartedEndpoint>())
                val completion: CompletionService<StartedEndpoint?> = ExecutorCompletionService(executor)
                repeat(batch.size) { index ->
                    val endpoint = batch[index]
                    completion.submit(Callable {
                        val testPort = temporarySocksPort(profile, batchIndex * parallelism + index)
                        var started: StartedEndpoint? = null
                        try {
                            LogUtil.i(AppConfig.TAG, "$TAG data-plane test ${endpoint.rendered} on port $testPort")
                            started = startEndpoint(
                                binary, configFile, runtimeDir, endpoint, profile, testPort,
                                routeThroughDesync = routeThroughDesync,
                            )
                        } catch (error: Throwable) {
                            LogUtil.d(AppConfig.TAG, "$TAG data-plane exception ${endpoint.rendered}: ${error.message}")
                        } finally {
                            val finished = completedTests.incrementAndGet()
                            LogUtil.i(
                                AppConfig.TAG,
                                "$TAG data-plane result $finished/$totalCandidates: ${endpoint.rendered} " +
                                    if (started != null) "healthy" else "failed",
                            )
                        }
                        val ready = started ?: return@Callable null
                        running += ready
                        if (winner.compareAndSet(null, ready)) {
                            ready
                        } else {
                            destroyStarted(ready)
                            running -= ready
                            null
                        }
                    })
                }

                var completed = 0
                while (completed < batch.size && winner.get() == null) {
                    checkStartupActive()
                    val result = completion.take().get()
                    completed++
                    if (result != null) winner.compareAndSet(null, result)
                }
                val selected = winner.get()
                if (selected != null) {
                    synchronized(running) {
                        running.filter { it !== selected }.forEach(::destroyStarted)
                        running.removeIf { it !== selected }
                    }
                    LogUtil.i(AppConfig.TAG, "$TAG first healthy endpoint ${selected.endpoint.rendered}")
                    return selected
                }
            }
        } finally {
            executor.shutdownNow()
        }
        return null
    }

    private fun temporarySocksPort(profile: ProfileItem, index: Int): Int {
        val preferred = socksPort(profile) + 100 + index
        if (preferred in 1024..65535) return preferred
        return runCatching {
            java.net.ServerSocket(0).use { it.localPort }
        }.getOrDefault(socksPort(profile))
    }

    private fun destroyStarted(started: StartedEndpoint) {
        destroyProcess(started.process)
        if (started.configFile != null) runCatching { started.configFile.delete() }
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
        val rendered: String get() = "$host:$port"
    }

    private data class StartedEndpoint(
        val process: Process,
        val endpoint: Endpoint,
        val port: Int,
        val configFile: File?,
    )

    private fun orderCandidates(candidates: List<Endpoint>, profile: ProfileItem): List<Endpoint> {
        val configuredPrimary = profile.warpMasquePrimaryEndpoint?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: WarpMasqueConfig.DEFAULT_ENDPOINT
        val primaryToken = if (configuredPrimary.contains(':')) {
            configuredPrimary
        } else {
            "$configuredPrimary:${profile.warpMasqueEndpointPort ?: WarpMasqueConfig.DEFAULT_PORT}"
        }
        val primary = parseCandidates(primaryToken).firstOrNull()
            ?: parseCandidates(WarpMasqueConfig.PRIMARY_ENDPOINT).first()
        return listOf(primary) + candidates.filter { it != primary }
    }

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

    /**
     * Order the full fallback range by a short TCP/443 check. Results are
     * consumed as they complete, so one dead address cannot delay progress
     * reporting or hold up all later addresses in the list.
     */
    private fun reachableCandidates(candidates: List<Endpoint>): List<Endpoint> {
        if (candidates.isEmpty()) return emptyList()
        LogUtil.i(AppConfig.TAG, "$TAG endpoint probe 0/${candidates.size}")
        val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_ENDPOINT_PROBES, candidates.size))
        val completion: CompletionService<Endpoint?> = ExecutorCompletionService(executor)
        candidates.forEach { endpoint ->
            completion.submit(Callable {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 350)
                    }
                    endpoint
                }.getOrNull()
            })
        }
        val reachable = ArrayList<Endpoint>()
        try {
            repeat(candidates.size) { index ->
                val endpoint = runCatching { completion.take().get() }.getOrNull()
                if (endpoint != null) reachable += endpoint
                LogUtil.i(
                    AppConfig.TAG,
                    "$TAG endpoint probe ${index + 1}/${candidates.size}" +
                        (endpoint?.let { ": ${it.rendered} reachable" } ?: ": unreachable"),
                )
            }
            return reachable
        } finally {
            executor.shutdownNow()
        }
    }

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
        val normalized = token.removePrefix("[").removeSuffix("]")
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
        val port = normalized.substringAfterLast(':', "443").toIntOrNull() ?: 443
        val host = normalized.substringBeforeLast(':')
        return if (host.isNotBlank()) listOf(Endpoint(host, port)) else emptyList()
    }
}
