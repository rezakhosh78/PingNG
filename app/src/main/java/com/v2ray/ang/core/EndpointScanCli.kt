package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AngApplication
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.dto.entities.ProfileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

enum class EndpointIpFamily(val cliValue: String) {
    IPV4("ipv4"),
    IPV6("ipv6"),
}

enum class EndpointPortScanMode(val cliValue: String) {
    FIRST_REACHABLE("first-reachable"),
    ALL_OPEN_PORTS("all-open-ports"),
    ALL_KNOWN_PORTS("all-known-ports"),
}

data class EndpointScanOptions(
    val timeoutSeconds: Int = 2,
    val parallelJobs: Int = 30,
    val ipFamily: EndpointIpFamily = EndpointIpFamily.IPV4,
    val portScanMode: EndpointPortScanMode = EndpointPortScanMode.FIRST_REACHABLE,
) {
    fun toCliArguments(includePortScanMode: Boolean = true): List<String> = buildList {
        add("--timeout-seconds")
        add(timeoutSeconds.coerceIn(1, 600).toString())
        add("--parallel-jobs")
        add(parallelJobs.coerceIn(1, 256).toString())
        add("--ip-family")
        add(ipFamily.cliValue)
        if (includePortScanMode) {
            add("--port-scan-mode")
            add(portScanMode.cliValue)
        }
    }
}

internal fun buildEndpointScanArguments(
    binaryPath: String,
    protocol: String,
    accountFilePath: String,
    scanOptions: EndpointScanOptions,
    extraArguments: List<String> = emptyList(),
): List<String> = buildList {
    addAll(listOf(binaryPath, "scan", "-p", protocol, "-n", "3"))
    // Check that the tunnel continues to carry traffic after its handshake.
    if (protocol == "wg") add("-P")
    addAll(scanOptions.toCliArguments(includePortScanMode = protocol != "masque-h2"))
    addAll(listOf("-plain", "-no-report", "-a", accountFilePath))
    addAll(extraArguments)
}

internal fun endpointScanOptionsFromValues(values: List<String>): EndpointScanOptions {
    // Earlier versions persisted their built-in defaults after each scan.
    // Interpret that exact legacy tuple as today's defaults; keep custom values.
    if (values == listOf("2", "10", "IPV4", "FIRST_REACHABLE")) return EndpointScanOptions()
    return EndpointScanOptions(
        timeoutSeconds = values.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 600) ?: 2,
        parallelJobs = values.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 256) ?: 30,
        ipFamily = runCatching { EndpointIpFamily.valueOf(values.getOrNull(2).orEmpty()) }
            .getOrDefault(EndpointIpFamily.IPV4),
        portScanMode = runCatching { EndpointPortScanMode.valueOf(values.getOrNull(3).orEmpty()) }
            .getOrDefault(EndpointPortScanMode.FIRST_REACHABLE),
    )
}

/** Persistent scanner controls shared by the editors and connection-triggered AWG scans. */
object EndpointScanSettings {
    private fun key(guid: String) = "ENDPOINT_SCAN_OPTIONS_${guid.ifBlank { "draft" }}"

    fun load(guid: String): EndpointScanOptions {
        val values = com.v2ray.ang.handler.MmkvManager.decodeSettingsString(key(guid))
            ?.split('|') ?: return EndpointScanOptions()
        return endpointScanOptionsFromValues(values)
    }

    fun save(guid: String, options: EndpointScanOptions) {
        com.v2ray.ang.handler.MmkvManager.encodeSettings(key(guid),
            "${options.timeoutSeconds}|${options.parallelJobs}|${options.ipFamily.name}|${options.portScanMode.name}")
    }

    fun transfer(fromGuid: String, toGuid: String) {
        if (fromGuid != toGuid) save(toGuid, load(fromGuid))
    }
}

/** Runs the pinned endpoint scanner as a separate process from the app's Go runtimes. */
object EndpointScannerCli {
    private const val EXECUTABLE_NAME = "libendpointscanner.so"
    private const val SCAN_TIMEOUT_MS = 4 * 60 * 1000L
    private const val SCAN_IDLE_TIMEOUT_MS = 40_000L
    private const val MAX_SCAN_TIMEOUT_MS = 30 * 60 * 1000L
    private const val REGISTER_TIMEOUT_MS = 60_000L
    private fun scanTimeoutMillis(options: EndpointScanOptions): Long =
        maxOf(SCAN_TIMEOUT_MS, options.timeoutSeconds.coerceIn(1, 600) * 60_000L)
            .coerceAtMost(MAX_SCAN_TIMEOUT_MS)

    private fun scanIdleTimeoutMillis(options: EndpointScanOptions): Long =
        maxOf(SCAN_IDLE_TIMEOUT_MS, options.timeoutSeconds.coerceIn(1, 600) * 1_000L + 15_000L)
            .coerceAtMost(MAX_SCAN_TIMEOUT_MS)

    private val ansiEscape = Regex("\\u001B\\[[;\\d?]*[ -/]*[@-~]")
    private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()
    private data class SharedScan(
        val task: Deferred<List<EndpointCandidate>>,
    )
    private val sharedScans = mutableMapOf<String, SharedScan>()
    private val sharedScansLock = Any()
    private val fallbackScanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ScanResult(
        val config: String,
        val usedEndpointScannerCli: Boolean,
        val candidates: List<EndpointCandidate>,
    )

    data class EndpointCandidate(
        val endpoint: String,
        val latencyMs: Long,
    )

    fun encodeCandidates(candidates: List<EndpointCandidate>): String = candidates
        .distinctBy { it.endpoint }
        .sortedWith(compareBy<EndpointCandidate> { if (it.latencyMs < 0) Long.MAX_VALUE else it.latencyMs }
            .thenBy { it.endpoint })
        .joinToString("\n") { "${it.endpoint}|${it.latencyMs}" }

    fun decodeCandidates(value: String?): List<EndpointCandidate> = value.orEmpty().lineSequence()
        .mapNotNull { line ->
            val endpoint = line.substringBefore('|').trim()
            val latency = line.substringAfter('|', "").toLongOrNull()
            val (host, port) = AmneziaWgFmt.splitEndpoint(endpoint)
            if (host.isNullOrBlank() || port?.toIntOrNull()?.let { it in 1..65535 } != true || latency == null) null
            else EndpointCandidate(endpoint, latency)
        }.distinctBy { it.endpoint }.sortedWith(
            compareBy<EndpointCandidate> { if (it.latencyMs < 0) Long.MAX_VALUE else it.latencyMs }
                .thenBy { it.endpoint }
        ).toList()

    fun accountFromConfig(config: String, reserved: String? = null): WarpAccount? = runCatching {
        val parsed = AmneziaWgFmt.readConfig(config)
        val privateKey = parsed.interfaceValues["privatekey"].orEmpty()
        val peerPublicKey = parsed.peers.firstOrNull()?.get("publickey").orEmpty()
        val endpoint = parsed.peers.firstOrNull()?.get("endpoint").orEmpty()
        val (endpointHost, endpointPort) = AmneziaWgFmt.splitEndpoint(endpoint)
        val endpointPortNumber = endpointPort?.toIntOrNull()
        require(privateKey.isNotBlank() && peerPublicKey.isNotBlank())
        require(!endpointHost.isNullOrBlank() && endpointPortNumber != null)
        WarpAccount(
            privateKey = privateKey,
            publicKey = "",
            localAddress = parsed.interfaceValues["address"].orEmpty(),
            peerPublicKey = peerPublicKey,
            endpoint = endpoint,
            endpointHost = endpointHost.orEmpty(),
            endpointPort = endpointPortNumber,
            reserved = reserved?.ifBlank { "0,0,0" } ?: "0,0,0",
            mtu = parsed.mtu ?: 1280,
        )
    }.getOrNull()

    fun accountFromProfile(profile: ProfileItem): WarpAccount? = runCatching {
        val privateKey = profile.secretKey?.trim().orEmpty()
        val peerPublicKey = profile.publicKey?.trim().orEmpty()
        val localAddress = profile.localAddress?.trim().orEmpty()
        require(privateKey.isNotBlank() && peerPublicKey.isNotBlank() && localAddress.isNotBlank())
        val host = profile.server.orEmpty().removePrefix("[").removeSuffix("]").trim()
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 2408
        require(host.isNotBlank())
        WarpAccount(
            privateKey = privateKey,
            publicKey = "",
            localAddress = localAddress,
            peerPublicKey = peerPublicKey,
            endpoint = "${if (host.contains(':')) "[$host]" else host}:$port",
            endpointHost = host,
            endpointPort = port,
            reserved = profile.reserved?.ifBlank { "0,0,0" } ?: "0,0,0",
            mtu = profile.mtu ?: 1280,
        )
    }.getOrNull()

    /** Scans plain WireGuard using the bundled protocol engine. */
    suspend fun scanWireGuardEndpoints(
        context: Context,
        account: WarpAccount,
        onProgress: suspend (String) -> Unit = {},
    ): List<EndpointCandidate> = scanWireGuardEndpoints(
        context,
        account,
        null,
        null,
        EndpointScanOptions(),
        onProgress,
    )

    /** Joins an editor scan and a connection-time scan for the same profile/account. */
    suspend fun scanWireGuardEndpoints(
        context: Context,
        account: WarpAccount,
        scanGuid: String?,
        progressLabel: String? = null,
        scanOptions: EndpointScanOptions = EndpointScanOptions(),
        onProgress: suspend (String) -> Unit = {},
    ): List<EndpointCandidate> {
        val appContext = context.applicationContext
        val identity = listOf(
            account.privateKey,
            account.publicKey,
            account.peerPublicKey,
            account.localAddress,
            account.reserved,
            account.mtu.toString(),
        ).joinToString("\u0000")
        val requestKey = sharedScanKey("wg", scanGuid, "$identity\u0000$scanOptions")
        return runSharedProtocolScan(
            context = appContext,
            requestKey = requestKey,
            stateGuid = scanGuid,
            progressLabel = progressLabel ?: "WireGuard",
            onProgress = onProgress,
        ) { report ->
            scanProtocolEndpoints(appContext, "wg", account, emptyList(), scanOptions, report)
        }
    }

    suspend fun scanBestWireGuardEndpoint(
        context: Context,
        account: WarpAccount,
        onProgress: suspend (String) -> Unit = {},
    ): EndpointCandidate = scanWireGuardEndpoints(context, account, onProgress).first()

    /** Registers and caches the separate MASQUE identity required for an H2 scan. */
    suspend fun scanMasqueH2Endpoints(
        context: Context,
        profileId: String,
        sni: String?,
        scanOptions: EndpointScanOptions,
        onProgress: suspend (String) -> Unit = {},
    ): List<EndpointCandidate> {
        val appContext = context.applicationContext
        val normalizedSni = sni?.trim().orEmpty()
        val requestKey = sharedScanKey("masque-h2", profileId, "$normalizedSni\u0000$scanOptions")
        return runSharedProtocolScan(
            context = appContext,
            requestKey = requestKey,
            stateGuid = profileId,
            progressLabel = "MASQUE/H2",
            onProgress = onProgress,
        ) { report ->
            withContext(Dispatchers.IO) {
                val binary = requireExecutable(appContext)
                val workDir = File(appContext.noBackupFilesDir, "endpoint-scan").apply { mkdirs() }
                val accountFile = File(workDir, "masque-${profileId.fileSafe()}.json")
                ensureMasqueAccount(binary, workDir, accountFile)
                val extraArguments = normalizedSni.takeIf { it.isNotBlank() }
                    ?.let { listOf("-masque-sni", it) }.orEmpty()
                runProtocolScan(binary, workDir, accountFile, "masque-h2", extraArguments, scanOptions, report)
            }
        }
    }

    suspend fun scanMasqueH2Endpoints(
        context: Context,
        profileId: String,
        sni: String?,
        onProgress: suspend (String) -> Unit = {},
    ): List<EndpointCandidate> = scanMasqueH2Endpoints(
        context,
        profileId,
        sni,
        EndpointScanOptions(),
        onProgress,
    )

    suspend fun scanBestMasqueH2Endpoint(
        context: Context,
        profileId: String,
        sni: String?,
        onProgress: suspend (String) -> Unit = {},
    ): EndpointCandidate = scanMasqueH2Endpoints(context, profileId, sni, onProgress).first()

    fun scanBestMasqueH2EndpointBlocking(
        context: Context,
        profileId: String,
        sni: String?,
    ): EndpointCandidate = runBlocking(Dispatchers.IO) {
        scanBestMasqueH2Endpoint(context, profileId, sni)
    }

    /** Stops child scans during connection cancellation. */
    fun cancelActiveScans() {
        val tasks = synchronized(sharedScansLock) {
            sharedScans.values.map { it.task }.also { sharedScans.clear() }
        }
        tasks.forEach { it.cancel(CancellationException("Endpoint scan canceled")) }
        activeProcesses.toList().forEach(::terminate)
    }

    private suspend fun runSharedProtocolScan(
        context: Context,
        requestKey: String,
        stateGuid: String?,
        progressLabel: String,
        onProgress: suspend (String) -> Unit,
        scan: suspend (suspend (String) -> Unit) -> List<EndpointCandidate>,
    ): List<EndpointCandidate> {
        val guid = stateGuid.orEmpty().ifBlank { requestKey.take(16) }
        val scope = (context.applicationContext as? AngApplication)?.applicationScope ?: fallbackScanScope
        val task = synchronized(sharedScansLock) {
            val cached = sharedScans[requestKey]
            if (cached != null && cached.task.isActive) {
                cached.task
            } else {
                val work = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        AwgEndpointScanState.begin(guid, "Scanning endpoints…")
                        try {
                            val candidates = scan { message ->
                                val visibleMessage = message
                                AwgEndpointScanState.update(guid, visibleMessage)
                                onProgress(visibleMessage)
                            }
                            val selected = candidates.firstOrNull()?.endpoint.orEmpty()
                            AwgEndpointScanState.complete(
                                guid,
                                "Endpoint selected: $selected",
                                selected,
                            )
                            candidates
                        } catch (cancelled: CancellationException) {
                            AwgEndpointScanState.clear(guid)
                            throw cancelled
                        } catch (error: Throwable) {
                            AwgEndpointScanState.fail(guid, "Endpoint scan failed")
                            throw error
                        }
                }
                val entry = SharedScan(work)
                work.invokeOnCompletion {
                    synchronized(sharedScansLock) {
                        if (sharedScans[requestKey] === entry) sharedScans.remove(requestKey)
                    }
                }
                sharedScans[requestKey] = entry
                work.start()
                work
            }
        }
        return task.await()
    }

    private fun sharedScanKey(protocol: String, scanGuid: String?, identity: String): String {
        val payload = "$protocol\u0000${scanGuid.orEmpty()}\u0000$identity"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun scanProtocolEndpoints(
        context: Context,
        protocol: String,
        account: WarpAccount,
        extraArguments: List<String>,
        scanOptions: EndpointScanOptions,
        onProgress: suspend (String) -> Unit,
    ): List<EndpointCandidate> = withContext(Dispatchers.IO) {
        val binary = requireExecutable(context)
        val workDir = File(context.noBackupFilesDir, "endpoint-scan").apply { mkdirs() }
        val accountFile = File(workDir, "account-${System.nanoTime()}.json")
        accountFile.writeText(accountFileJson(account))
        restrictPrivateFile(accountFile)
        try {
            runProtocolScan(binary, workDir, accountFile, protocol, extraArguments, scanOptions, onProgress)
        } finally {
            runCatching { accountFile.writeText("") }
            accountFile.delete()
        }
    }

    private suspend fun runProtocolScan(
        binary: File,
        workDir: File,
        accountFile: File,
        protocol: String,
        extraArguments: List<String>,
        scanOptions: EndpointScanOptions,
        onProgress: suspend (String) -> Unit,
    ): List<EndpointCandidate> {
        require(protocol == "wg" || protocol == "masque-h2")
        // Keep the scanner's plain event stream (and omit -best) so the UI can
        // show each reachable endpoint as it arrives, then let the user choose.
        val arguments = buildEndpointScanArguments(
            binaryPath = binary.absolutePath,
            protocol = protocol,
            accountFilePath = accountFile.absolutePath,
            scanOptions = scanOptions,
            extraArguments = extraArguments,
        )
        val candidates = linkedMapOf<String, EndpointCandidate>()
        var lastStatusAt = 0L
        var lastPortProgressAt = 0L
        var scannedEndpoints = 0L
        var scannedTotal = 0L
        onProgress("Scanning endpoints…")
        suspend fun runAttempt(): Pair<Int, String> = runChild(
            workDir,
            arguments,
            scanTimeoutMillis(scanOptions),
            idleTimeoutMs = scanIdleTimeoutMillis(scanOptions),
        ) { raw ->
            val line = ansiEscape.replace(raw, "").trim()
            val endpointEvent = endpointEventPattern.matchEntire(line)
            if (endpointEvent != null) {
                val endpoint = endpointEvent.groupValues[1].trim()
                val latency = endpointEvent.groupValues[2].toLongOrNull() ?: -1L
                val (host, port) = AmneziaWgFmt.splitEndpoint(endpoint)
                if (!host.isNullOrBlank() && port?.toIntOrNull()?.let { it in 1..65535 } == true) {
                    val previous = candidates.put(endpoint, EndpointCandidate(endpoint, latency))
                    if (previous == null || previous.latencyMs != latency) {
                        onProgress("Found $endpoint · ${if (latency >= 0) "$latency ms" else "reachable"}")
                    }
                }
            } else if (progressEventPattern.matches(line)) {
                val values = progressEventPattern.matchEntire(line)!!.groupValues
                val checked = values[1].toLongOrNull()
                val total = values[2].toLongOrNull()
                if (checked != null && total != null && total > 0 && checked in 0..total) {
                    scannedEndpoints = checked
                    scannedTotal = total
                    if (checked == 0L) PingNgDiagnostics.record("Endpoint scan phase started ($protocol): 0/$total")
                    onProgress("Scanned $checked/$total endpoints…")
                }
            } else if (portProgressEventPattern.matches(line)) {
                val values = portProgressEventPattern.matchEntire(line)!!.groupValues
                val checked = values[1].toLongOrNull()
                val total = values[2].toLongOrNull()
                val now = System.nanoTime()
                if (checked != null && total != null && total > 0 && checked in 0..total &&
                    (checked == total || now - lastPortProgressAt >= 200_000_000L)
                ) {
                    lastPortProgressAt = now
                    onProgress("Port checks $checked/$total · scanned $scannedEndpoints/$scannedTotal endpoints…")
                }
            } else if (workerProgressEventPattern.matches(line)) {
                val values = workerProgressEventPattern.matchEntire(line)!!.groupValues
                val ready = values[1].toIntOrNull()
                val total = values[2].toIntOrNull()
                if (ready != null && total != null && total > 0) {
                    onProgress("Preparing scan workers $ready/$total…")
                }
            } else {
                // Keep the live card readable while scanner output is arriving
                // quickly, without exposing command arguments or file paths.
                val now = System.nanoTime()
                val safeStatus = safeScanStatus(line)
                if (safeStatus != null && now - lastStatusAt >= 120_000_000L) {
                    lastStatusAt = now
                    onProgress(safeStatus)
                }
            }
        }
        val result = try {
            runAttempt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stalled: Throwable) {
            PingNgDiagnostics.record("Endpoint scan retry after stalled attempt ($protocol)", stalled)
            onProgress("Scanner stream interrupted; retrying endpoint checks…")
            try {
                runAttempt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryFailure: Throwable) {
                if (candidates.isEmpty()) throw retryFailure
                PingNgDiagnostics.record(
                    "Endpoint scan retry ended after verified endpoints were found ($protocol)",
                    retryFailure,
                )
                0 to "verified endpoints retained"
            }
        }
        check(result.first == 0 || candidates.isNotEmpty()) {
            val familyError = if (scanOptions.ipFamily == EndpointIpFamily.IPV6 &&
                result.second.contains("no routable IPv6 address", ignoreCase = true)
            ) "This network has no routable IPv6 address for endpoint scanning" else null
            familyError ?: "Endpoint scan failed (${result.first})"
        }
        check(candidates.isNotEmpty()) { "No reachable $protocol endpoint was found" }
        val sorted = candidates.values.sortedWith(compareBy<EndpointCandidate> {
            if (it.latencyMs < 0) Long.MAX_VALUE else it.latencyMs
        })
        onProgress("Endpoint selected: ${sorted.first().endpoint}")
        return sorted
    }

    private suspend fun ensureMasqueAccount(binary: File, workDir: File, accountFile: File) {
        if (hasMasqueCredentials(accountFile)) return
        runCatching { accountFile.delete() }
        val result = runChild(
            workDir,
            listOf(binary.absolutePath, "register", "-a", accountFile.absolutePath),
            REGISTER_TIMEOUT_MS,
        )
        check(result.first == 0 && hasMasqueCredentials(accountFile)) {
            "WARP H2 account setup failed (${result.first})"
        }
        restrictPrivateFile(accountFile)
    }

    fun hasMasqueH2Account(context: Context, profileId: String): Boolean =
        hasMasqueCredentials(File(File(context.applicationContext.noBackupFilesDir, "endpoint-scan"),
            "masque-${profileId.fileSafe()}.json"))

    suspend fun prepareMasqueH2Account(context: Context, profileId: String, socksPort: Int? = null) {
        withContext(Dispatchers.IO) {
            val workDir = File(context.applicationContext.noBackupFilesDir, "endpoint-scan").apply { mkdirs() }
            val accountFile = File(workDir, "masque-${profileId.fileSafe()}.json")
            if (hasMasqueCredentials(accountFile)) return@withContext
            val binary = requireExecutable(context.applicationContext)
            runCatching { accountFile.delete() }
            val result = runChild(workDir,
                listOf(binary.absolutePath, "register", "-a", accountFile.absolutePath),
                REGISTER_TIMEOUT_MS,
                registrationSocksPort = socksPort)
            check(result.first == 0 && hasMasqueCredentials(accountFile)) {
                "WARP H2 account setup failed (${result.first})"
            }
            restrictPrivateFile(accountFile)
        }
    }

    private suspend fun runChild(
        workDir: File,
        arguments: List<String>,
        timeoutMs: Long,
        idleTimeoutMs: Long = timeoutMs,
        registrationSocksPort: Int? = null,
        onOutput: suspend (String) -> Unit = {},
    ): Pair<Int, String> = coroutineScope {
        val process = ProcessBuilder(arguments)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = workDir.absolutePath
                environment()["TERM"] = "dumb"
                environment()["NO_COLOR"] = "1"
                environment()["GOMAXPROCS"] = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).toString()
                if (registrationSocksPort != null) {
                    require(registrationSocksPort in 1..65535)
                    environment()["PINGNG_MASQUE_REGISTRATION_SOCKS"] =
                        "socks5://127.0.0.1:$registrationSocksPort"
                }
            }
            .start()
        activeProcesses += process
        val lastOutputAt = AtomicLong(System.nanoTime())
        val output = Collections.synchronizedList(mutableListOf<String>())
        val outputReader = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = ansiEscape.replace(rawLine, "")
                            .filter { it == '\t' || it >= ' ' }.trim()
                        if (line.isNotEmpty()) {
                            if (idleTimeoutMs == timeoutMs ||
                                line.startsWith("PINGNG_PROGRESS\t") ||
                                line.startsWith("PINGNG_PORT_PROGRESS\t") ||
                                line.startsWith("PINGNG_WORKERS\t") ||
                                line.startsWith("PINGNG_ENDPOINT\t")
                            ) lastOutputAt.set(System.nanoTime())
                            synchronized(output) {
                                output += line
                                if (output.size > 120) output.removeAt(0)
                            }
                            try {
                                onOutput(line)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } catch (closedDuringShutdown: IOException) {
                // Android can report the child pipe closed just before the
                // process handle changes to exited. Give the child a short
                // grace period before treating the stream close as a scan error.
                val readerContext = kotlinx.coroutines.currentCoroutineContext()
                readerContext.ensureActive()
                val exited = !process.isAlive || runCatching {
                    process.waitFor(250, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                readerContext.ensureActive()
                if (!exited && process.isAlive) throw closedDuringShutdown
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) throw IllegalStateException("Endpoint scan timed out")
                if (System.nanoTime() - lastOutputAt.get() >= TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)) {
                    throw IllegalStateException("Endpoint scan stopped making progress")
                }
            }
            outputReader.join()
            process.exitValue() to synchronized(output) { output.joinToString("\n") }
        } finally {
            activeProcesses -= process
            if (process.isAlive) terminate(process)
            outputReader.cancelAndJoin()
        }
    }

    private fun hasMasqueCredentials(accountFile: File): Boolean = runCatching {
        val root = JsonParser.parseString(accountFile.readText()).asJsonObject
        val masque = root.getAsJsonObject("masque")
        listOf("private_key", "peer_public_key", "id", "token")
            .all { masque?.get(it)?.asString?.isNotBlank() == true }
    }.getOrDefault(false)

    private fun requireExecutable(context: Context): File = executable(context).also { binary ->
        check(binary.isFile && binary.length() > 1024 && binary.canExecute()) {
            "Endpoint scanning is unavailable for this device"
        }
    }

    private fun restrictPrivateFile(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun terminate(process: Process) {
        runCatching { process.destroy() }
        runCatching { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
    }

    private fun String.fileSafe(): String = replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)

    fun isAvailable(context: Context): Boolean = executable(context).let {
        it.isFile && it.length() > 1024 && it.canExecute()
    }

    suspend fun scan(
        context: Context,
        account: WarpAccount,
        onProgress: suspend (String) -> Unit = {},
    ): ScanResult = scan(context, account, EndpointScanOptions(), onProgress)

    suspend fun scan(
        context: Context,
        account: WarpAccount,
        scanOptions: EndpointScanOptions,
        onProgress: suspend (String) -> Unit = {},
    ): ScanResult {
        // Keep AWG's established bundled scan and endpoint pool. Its progress
        // events are forwarded to the shared UI state; do not replace this
        // mode with the larger in-app discovery pool.
        return scanWithBundledCli(context, account, scanOptions, onProgress)
    }

    private suspend fun scanWithBundledCli(
        context: Context,
        account: WarpAccount,
        scanOptions: EndpointScanOptions,
        onProgress: suspend (String) -> Unit,
    ): ScanResult {
        onProgress("Preparing endpoint scan…")
        val binary = requireExecutable(context)
        val (config, candidates) = runEndpointScanner(context, binary, account, scanOptions, onProgress)
        return ScanResult(config, true, candidates)
    }

    private fun executable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, EXECUTABLE_NAME)

    private suspend fun runEndpointScanner(
        context: Context,
        binary: File,
        account: WarpAccount,
        scanOptions: EndpointScanOptions,
        onProgress: suspend (String) -> Unit,
    ): Pair<String, List<EndpointCandidate>> = withContext(Dispatchers.IO) {
        val workDir = File(context.noBackupFilesDir, "awg-warp-endpointscanner").apply { mkdirs() }
        val runId = System.nanoTime().toString()
        val accountFile = File(workDir, "account-$runId.json")
        val configFile = File(workDir, "config-$runId.conf")
        val reportFile = File(workDir, "endpoints-$runId.txt")
        accountFile.writeText(accountFileJson(account))
        configFile.delete()
        reportFile.delete()

        try {
            coroutineScope {
                val process = ProcessBuilder(
                    binary.absolutePath,
                    "scan",
                    "-p", "awg",
                    "-P",
                    // Probe fewer addresses per subnet, while verifying more
                    // working tunnels concurrently. Keep -P: UDP replies alone
                    // do not establish that an endpoint carries traffic.
                    "-n", "3",
                    "-plain",
                    "-jc", AwgWarpConfig.JUNK_PACKET_COUNT.toString(),
                    "-jmin", AwgWarpConfig.JUNK_PACKET_MIN.toString(),
                    "-jmax", AwgWarpConfig.JUNK_PACKET_MAX.toString(),
                    "-a", accountFile.absolutePath,
                    "-conf", configFile.absolutePath,
                    "-o", reportFile.absolutePath,
                ).apply {
                    command().addAll(scanOptions.toCliArguments())
                }.directory(workDir).redirectErrorStream(true).apply {
                    environment()["HOME"] = workDir.absolutePath
                    environment()["TERM"] = "dumb"
                    environment()["NO_COLOR"] = "1"
                    environment()["GOMAXPROCS"] = Runtime.getRuntime().availableProcessors()
                        .coerceIn(2, 8)
                        .toString()
                }.start()
                val output = Collections.synchronizedList(mutableListOf<String>())
                onProgress("Checking endpoint reachability…")
                val outputReader = launch(Dispatchers.IO) {
                    var lastUiProgressAt = 0L
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { rawLine ->
                            val line = ansiEscape.replace(rawLine, "").filter { it == '\t' || it >= ' ' }.trim()
                            if (line.isNotEmpty()) {
                                synchronized(output) {
                                    output += line
                                    if (output.size > 80) output.removeAt(0)
                                }
                                // The scanner can print hundreds of rows per
                                // second. Updating Compose for every row blocks
                                // this reader on the main thread, fills the
                                // process pipe, and makes the scan appear to
                                // hang. Keep the live UI responsive while the
                                // process continues at full speed.
                                val now = System.nanoTime()
                                val progressEvent = progressEventPattern.matchEntire(line)
                                val workerEvent = workerProgressEventPattern.matchEntire(line)
                                val visibleLine = progressEvent?.let {
                                    "Scanned ${it.groupValues[1]}/${it.groupValues[2]} endpoints…"
                                } ?: workerEvent?.let {
                                    "Preparing scan workers ${it.groupValues[1]}/${it.groupValues[2]}…"
                                } ?: line
                                if (progressEvent != null || workerEvent != null || visibleLine.contains(
                                        Regex("(?i)\\bScanned\\s+\\d+\\s*(?:/|of)\\s*\\d+"),
                                    ) || now - lastUiProgressAt >= 120_000_000L
                                ) {
                                    lastUiProgressAt = now
                                    withContext(Dispatchers.Main.immediate) { onProgress(visibleLine.take(180)) }
                                }
                            }
                        }
                    }
                }

                try {
                    val exitCode = withTimeout(scanTimeoutMillis(scanOptions)) {
                        withContext(Dispatchers.IO) { process.waitFor() }
                    }
                    outputReader.join()
                    if (exitCode != 0) {
                        val details = synchronized(output) { output.takeLast(8).joinToString("\n") }
                        throw IllegalStateException(
                            details.ifBlank { "Endpoint Scanner endpoint scan exited with code $exitCode" }
                        )
                    }
                    val scannedConfig = configFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
                    require(scannedConfig.contains("[Interface]") && scannedConfig.contains("[Peer]")) {
                        "Endpoint scan completed without writing an AmneziaWG config"
                    }
                    val endpoint = AmneziaWgFmt.value(scannedConfig, "Peer", "Endpoint")
                    require(endpoint.isNotBlank()) { "Endpoint scan has no selected endpoint" }
                    onProgress("Sorting reachable endpoints by ping…")
                    val reportCandidates = reportFile.takeIf(File::isFile)
                        ?.readLines()?.let(::parseWorkingReport).orEmpty()
                    val parsedCandidates = reportCandidates.ifEmpty {
                        synchronized(output) { output.toList() }.mapNotNull(::parseCandidateLine)
                    }
                    val candidates = decodeCandidates(encodeCandidates(
                        parsedCandidates + EndpointCandidate(endpoint, parsedCandidates
                            .firstOrNull { it.endpoint == endpoint }?.latencyMs ?: -1L)
                    ))
                    val selected = candidates.firstOrNull { it.latencyMs >= 0 }?.endpoint ?: endpoint
                    onProgress("Selected $selected")
                    AwgWarpConfig.render(account, selected) to candidates
                } finally {
                    if (process.isAlive) {
                        process.destroy()
                        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    }
                    outputReader.cancelAndJoin()
                }
            }
        } finally {
            runCatching { accountFile.writeText("") }
            accountFile.delete()
            runCatching { configFile.writeText("") }
            configFile.delete()
            reportFile.delete()
        }
    }

    private val endpointPattern = Regex("""(?:\[(?:[0-9A-Fa-f:]+)\]|(?:\d{1,3}\.){3}\d{1,3}):\d{1,5}""")
    private val endpointEventPattern = Regex(
        """^PINGNG_ENDPOINT\t((?:\[[0-9A-Fa-f:]+\]|(?:\d{1,3}\.){3}\d{1,3}):\d{1,5})\t(-?\d+)$"""
    )
    private val progressEventPattern = Regex("""^PINGNG_PROGRESS\t(\d+)\t(\d+)$""")
    private val portProgressEventPattern = Regex("""^PINGNG_PORT_PROGRESS\t(\d+)\t(\d+)$""")
    private val workerProgressEventPattern = Regex("""^PINGNG_WORKERS\t(\d+)\t(\d+)$""")
    private val latencyPattern = Regex("""\b(\d{1,5}(?:\.\d+)?)(?:\s*)(?:ms|milliseconds?)\b""", RegexOption.IGNORE_CASE)
    private val scanCountPattern = Regex("""\b(\d+)\s*/\s*(\d+)\b""")

    private fun safeScanStatus(raw: String): String? {
        val line = raw.trim()
        if (line.isBlank()) return null
        val lower = line.lowercase()
        val count = scanCountPattern.find(line)?.groupValues
        return when {
            lower.contains("phase 1") || lower.contains("discovery") -> "Finding reachable endpoints…"
            lower.contains("phase 2") || lower.contains("verifying tunnel") -> "Checking endpoint tunnels…"
            count != null -> "Scanned ${count[1]} of ${count[2]} endpoints…"
            lower.contains("sorting") -> "Sorting reachable endpoints…"
            else -> null
        }
    }

    /** Only the rows before the torn-down section represent working tunnels. */
    internal fun parseWorkingReport(lines: List<String>): List<EndpointCandidate> {
        val working = mutableListOf<EndpointCandidate>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("# ") && trimmed.contains("torn down", ignoreCase = true)) break
            if (trimmed.startsWith("#") || trimmed.startsWith("ENDPOINT") || trimmed.isBlank()) continue
            val columns = trimmed.split(Regex("\\s+"))
            val endpoint = columns.firstOrNull() ?: continue
            if (endpointPattern.matchEntire(endpoint) == null) continue
            // With -P, TUN PING follows ENDPOINT PING in the report.
            val ping = columns.getOrNull(2)?.takeIf { it.endsWith("ms") }
                ?: columns.getOrNull(1)?.takeIf { it.endsWith("ms") }
                ?: continue
            val latency = ping.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: continue
            working += EndpointCandidate(endpoint, latency)
        }
        return decodeCandidates(encodeCandidates(working))
    }

    private fun parseCandidateLine(line: String): EndpointCandidate? {
        val endpoint = endpointPattern.find(line)?.value ?: return null
        val latency = latencyPattern.findAll(line).lastOrNull()?.groupValues?.get(1)
            ?.toDoubleOrNull()?.toLong() ?: return null
        val (_, port) = AmneziaWgFmt.splitEndpoint(endpoint)
        if (port?.toIntOrNull()?.let { it in 1..65535 } != true) return null
        return EndpointCandidate(endpoint, latency)
    }

    private fun accountFileJson(account: WarpAccount): String {
        val addresses = account.localAddress.split(',').map(String::trim).filter(String::isNotBlank)
        // The scanner's account loader feeds these values to netip.ParseAddr,
        // which accepts only a host address (not a CIDR). The app keeps the
        // WireGuard interface values as 172.16.0.2/32 and .../128, so remove
        // the prefix before writing the scanner JSON input.
        val ipv4 = addresses.firstOrNull { !it.contains(':') }?.substringBefore('/')
        val ipv6 = addresses.firstOrNull { it.contains(':') }?.substringBefore('/')
        return JsonObject().apply {
            addProperty("private_key", account.privateKey)
            addProperty("peer_public_key", account.peerPublicKey)
            ipv4?.let { addProperty("ipv4", it) }
            ipv6?.let { addProperty("ipv6", it) }
        }.toString()
    }


}
