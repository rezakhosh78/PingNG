package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonObject
import com.v2ray.ang.fmt.AmneziaWgFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Runs the pinned endpoint scanner as a separate process from the app's Go runtimes. */
object EndpointScannerCli {
    private const val EXECUTABLE_NAME = "libendpointscanner.so"
    private const val SCAN_TIMEOUT_MS = 4 * 60 * 1000L
    private val ansiEscape = Regex("\\u001B\\[[;\\d?]*[ -/]*[@-~]")

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

    /** Returns reachable endpoints ordered by the measured handshake latency. */
    suspend fun scanCandidates(
        account: WarpAccount,
        onProgress: suspend (String) -> Unit = {},
    ): List<EndpointCandidate> = discoverInApp(account, onProgress)
        .sortedBy { it.latencyMs }
        .map { hit ->
            EndpointCandidate(
                endpoint = hit.endpoint.host.toEndpointHost() + ":" + hit.endpoint.port,
                latencyMs = hit.latencyMs,
            )
        }

    fun isAvailable(context: Context): Boolean = executable(context).let {
        it.isFile && it.length() > 1024 && it.canExecute()
    }

    suspend fun scan(
        context: Context,
        account: WarpAccount,
        onProgress: suspend (String) -> Unit = {},
    ): ScanResult {
        onProgress("Preparing endpoint scan…")
        val binary = executable(context)
        if (!binary.isFile || binary.length() <= 1024 || !binary.canExecute()) {
            val candidates = scanCandidates(account, onProgress)
            val selected = candidates.firstOrNull() ?: error("No reachable endpoint was found")
            onProgress("Selected ${selected.endpoint} · ${selected.latencyMs} ms")
            return ScanResult(AwgWarpConfig.render(account, selected.endpoint), false, candidates)
        }
        return try {
            val (config, candidates) = runEndpointScanner(context, binary, account, onProgress)
            ScanResult(config, true, candidates)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (scannerError: Throwable) {
            // Older scanner builds can abort before producing a config when an
            // account address contains a CIDR suffix. Do not turn that helper
            // crash into a VPN connection failure: use the in-app probe, and
            // if it finds nothing, retain the endpoint already on the profile.
            onProgress("Endpoint scanner unavailable; trying direct scan…")
            val candidates = try {
                scanCandidates(account, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            val selected = candidates.firstOrNull()?.endpoint
                ?: account.endpoint.takeIf(String::isNotBlank)
                ?: throw scannerError
            onProgress("Using ${if (candidates.isNotEmpty()) "best scanned" else "saved"} endpoint: $selected")
            ScanResult(AwgWarpConfig.render(account, selected), false, candidates)
        }
    }

    private fun executable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, EXECUTABLE_NAME)

    private suspend fun runEndpointScanner(
        context: Context,
        binary: File,
        account: WarpAccount,
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
                    "-jt", "24",
                    "-plain",
                    "-jc", AwgWarpConfig.JUNK_PACKET_COUNT.toString(),
                    "-jmin", AwgWarpConfig.JUNK_PACKET_MIN.toString(),
                    "-jmax", AwgWarpConfig.JUNK_PACKET_MAX.toString(),
                    "-a", accountFile.absolutePath,
                    "-conf", configFile.absolutePath,
                    "-o", reportFile.absolutePath,
                ).directory(workDir).redirectErrorStream(true).apply {
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
                                if (now - lastUiProgressAt >= 120_000_000L) {
                                    lastUiProgressAt = now
                                    withContext(Dispatchers.Main.immediate) { onProgress(line.take(180)) }
                                }
                            }
                        }
                    }
                }

                try {
                    val exitCode = withTimeout(SCAN_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { process.waitFor() }
                    }
                    outputReader.join()
                    if (exitCode != 0) {
                        val details = synchronized(output) { output.takeLast(8).joinToString("\n") }
                        throw IllegalStateException(
                            details.ifBlank { "Endpoint scan v${AwgWarpConfig.ENDPOINT_SCAN_VERSION} exited with code $exitCode" }
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
    private val latencyPattern = Regex("""\b(\d{1,5}(?:\.\d+)?)(?:\s*)(?:ms|milliseconds?)\b""", RegexOption.IGNORE_CASE)

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

    /** Handshake-only fallback for ABIs without the official Android/arm64 executable. */
    private suspend fun scanInApp(
        account: WarpAccount,
        onProgress: suspend (String) -> Unit,
    ): String {
        val hits = discoverInApp(account, onProgress)
        val selected = hits.minByOrNull { it.latencyMs }?.endpoint?.let { endpoint ->
            endpoint.host.toEndpointHost() + ":" + endpoint.port
        } ?: throw IllegalStateException("No reachable AWG endpoint was found in the in-app scan")
        return AwgWarpConfig.render(account, selected)
    }

    private suspend fun discoverInApp(
        account: WarpAccount,
        onProgress: suspend (String) -> Unit,
    ): List<AwgEndpointScanner.Hit> {
        val endpoints = parseEndpoints(WarpPlusConfig.ALL_ENDPOINTS)
        onProgress("Checking AWG endpoints…")
        var lastUiProgressAt = 0L
        return AwgEndpointScanner.scan(
            endpoints = endpoints,
            identity = AwgEndpointScanner.Identity(account.privateKey, account.peerPublicKey),
            ratePerSecond = 6_000L,
            workers = 384,
            timeoutMs = 300,
            maxHits = 48,
            stopAfterHits = 12,
            acceptCookieReplies = true,
            awgJunkCount = AwgWarpConfig.JUNK_PACKET_COUNT,
            onProgress = { tested, total ->
                val now = System.nanoTime()
                if (tested == total || now - lastUiProgressAt >= 150_000_000L) {
                    lastUiProgressAt = now
                    withContext(Dispatchers.Main.immediate) {
                        onProgress("AWG endpoint scan: $tested/$total")
                    }
                }
            },
        )
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

    private fun parseEndpoints(raw: String): List<WarpEndpointTester.Endpoint> = raw
        .split(',', ';', '\n', '\r')
        .mapNotNull { value ->
            val text = value.trim()
            if (text.isBlank()) return@mapNotNull null
            val host: String
            val port: String
            if (text.startsWith("[")) {
                val closing = text.indexOf(']')
                if (closing <= 0) return@mapNotNull null
                host = text.substring(1, closing)
                port = text.substring(closing + 1).removePrefix(":")
            } else {
                val separator = text.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null
                host = text.substring(0, separator)
                port = text.substring(separator + 1)
            }
            val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            WarpEndpointTester.Endpoint(host, portNumber)
        }.distinct()

    private fun String.toEndpointHost(): String = if (contains(':') && !startsWith("[")) "[$this]" else this
}
