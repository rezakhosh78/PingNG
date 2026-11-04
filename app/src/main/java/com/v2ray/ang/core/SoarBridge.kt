package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.helper.MessageHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns the SoarDNS client process and its loopback SOCKS5 adapter. */
object SoarBridge {
    const val LABEL = "SoarDNS"
    const val LEGACY_LABEL = "Soar"
    const val PORT = 18002
    private const val START_TIMEOUT_SECONDS = 15L

    @Volatile private var process: Process? = null
    @Volatile private var activeGuid: String? = null
    @Volatile private var startupReady: CountDownLatch? = null
    @Volatile private var firstResolverReady: CountDownLatch? = null
    private val cancelled = AtomicBoolean(false)
    private val startupGeneration = AtomicLong(0)
    private val timestampFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun isActive(): Boolean = process?.isAlive == true && listenerAvailable()

    fun isProfile(profile: ProfileItem): Boolean =
        profile.description?.equals(LABEL, ignoreCase = true) == true ||
            profile.description?.equals(LEGACY_LABEL, ignoreCase = true) == true ||
            profile.description?.equals("SOAR DNS", ignoreCase = true) == true

    fun outboundPort(profile: ProfileItem): Int = PORT

    fun readLog(context: Context, guid: String): String = runCatching {
        File(logDirectory(context), "$guid.log").takeIf(File::isFile)?.readText().orEmpty()
    }.getOrDefault("")

    fun clearLog(context: Context, guid: String) {
        runCatching { File(logDirectory(context), "$guid.log").writeText("") }
    }

    fun cancelStartup() {
        startupGeneration.incrementAndGet()
        cancelled.set(true)
        startupReady?.countDown()
        firstResolverReady?.countDown()
        process?.let { runCatching { it.destroyForcibly() } }
    }

    @Synchronized
    fun start(
        context: Context,
        guid: String,
        profile: ProfileItem,
        waitForHealthyResolver: Boolean = false,
        cancellationRequested: () -> Boolean = { false },
    ) {
        require(isProfile(profile)) { "Not a SoarDNS profile" }
        check(!cancellationRequested()) { "SoarDNS startup was cancelled" }
        val generation = startupGeneration.get()
        if (activeGuid == guid && isActive() && !cancelled.get()) return
        stop()
        cancelled.set(false)
        checkStartupNotCancelled(generation, cancellationRequested)

        val executable = File(context.applicationInfo.nativeLibraryDir, "libsoar.so")
        check(executable.isFile) {
            "SoarDNS client is missing for this Android ABI: ${executable.name}"
        }
        val runtimeDirectory = File(context.filesDir, "soar").apply { mkdirs() }
        val configFile = File(runtimeDirectory, "${UUID.randomUUID()}.json")
        val logFile = File(logDirectory(context), "$guid.log")
        val resolverList = SoarSettings.resolverLines(profile.soarResolvers.orEmpty())
        val useDiscovery = SoarSettings.effectiveDiscoveryEnabled(
            profile.soarResolvers,
            profile.soarDiscoveryEnabled,
        )
        val advanced = SoarSettings.decode(profile.soarAdvanced)
        val discoveryCandidates = if (useDiscovery) {
            SoarSettings.resolverLines(advanced.extraDiscoveryResolvers)
        } else {
            emptyList()
        }
        require(resolverList.isNotEmpty() || useDiscovery) {
            "Add at least one DNS resolver or enable resolver discovery"
        }
        SoarSettings.validationError(
            advanced,
            profile.soarTcpMode.orEmpty().ifBlank { "auto" },
            validateDiscovery = useDiscovery,
        )?.let { error(it) }
        val safeGuid = guid.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { UUID.randomUUID().toString() }
        val cachePath = File(runtimeDirectory, "resolver-cache/$safeGuid.txt")
        val config = JSONObject()
            .put("zone", profile.soarZone.orEmpty().trim().trimEnd('.'))
            .put("serverKey", profile.soarServerKey.orEmpty().trim())
            .put("resolvers", JSONArray(resolverList))
            .put("discoveryEnabled", useDiscovery)
            .put("discoveryCountry", if (useDiscovery) {
                SoarSettings.effectiveDiscoveryCountry(profile.soarResolvers, profile.soarDiscoveryCountry).orEmpty()
            } else {
                ""
            })
            .put("discoveryCandidates", JSONArray(discoveryCandidates))
            .put("discoverySampleRanges", if (useDiscovery) advanced.discoverySampleRanges else 0)
            // Scan-speed controls also govern the explicit-resolver scan. In manual mode the
            // native client only reuses cached entries that are still in this profile's list.
            .put("discoveryWant", advanced.discoveryWant)
            .put("discoveryConcurrency", advanced.discoveryConcurrency)
            .put("discoveryPaceMs", advanced.discoveryPaceMs)
            .put("discoveryCacheEnabled", advanced.discoveryCacheEnabled)
            .put("discoveryCachePath", if (advanced.discoveryCacheEnabled) cachePath.absolutePath else "")
            .put("tcp", profile.soarTcpMode.orEmpty().ifBlank { "auto" })
            .put("safeQName", advanced.safeQName)
            .put("maxQName", advanced.maxQName)
            .put("holdMs", advanced.holdMs)
            .put("maxInflight", advanced.maxInflight)
            .put("maxPathWindow", advanced.maxPathWindow)
            .put("maxAnswer", advanced.maxAnswer)
            .put("streamWindowKB", advanced.streamWindowKB)
            .put("sendBufferKB", advanced.sendBufferKB)
            .put("maxStreams", advanced.maxStreams)
            .put("sessionIdleSeconds", advanced.sessionIdleSeconds)
            .put("listen", "127.0.0.1:$PORT")
        configFile.writeText(config.toString())
        configFile.setReadable(true, true)
        configFile.setWritable(true, true)
        logFile.parentFile?.mkdirs()
        logFile.writeText("${timestamp()} Starting SoarDNS client; zone=${profile.soarZone.orEmpty().trim()}\n")

        val started = ProcessBuilder(executable.absolutePath, "--config", configFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        process = started
        activeGuid = guid
        val ready = CountDownLatch(1)
        val firstResolver = CountDownLatch(1)
        startupReady = ready
        firstResolverReady = firstResolver
        Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        appendLog(logFile, line)
                        publishResolverProgress(context, guid, line)
                        if (line.startsWith("SOAR_SOCKS_LISTENING ")) ready.countDown()
                    }
                }
            }.onFailure { appendLog(logFile, "Output reader failed: ${it.message.orEmpty()}") }
        }, "PingNG-Soar-Log").apply { isDaemon = true; start() }

        try {
            checkStartupNotCancelled(generation, cancellationRequested)
            check(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS) && started.isAlive && !cancelled.get()) {
                val exit = runCatching { started.exitValue() }.getOrNull()
                val detail = if (exit != null) "client exited ($exit)" else "SOCKS listener did not start"
                "$detail. Review the SoarDNS log for details."
            }
            checkStartupNotCancelled(generation, cancellationRequested)
            check(listenerAvailable()) { "SoarDNS local SOCKS5 listener is unavailable" }
            appendLog(logFile, "Local SOCKS5 listener ready at 127.0.0.1:$PORT")
            if (waitForHealthyResolver) {
                awaitFirstHealthyResolver(started, firstResolver, generation, cancellationRequested)
                appendLog(logFile, "First healthy DNS resolver verified; VPN startup may continue")
            }
        } catch (error: Throwable) {
            stop()
            throw error
        } finally {
            startupReady = null
            if (firstResolverReady === firstResolver) firstResolverReady = null
            configFile.delete()
        }
    }

    @Synchronized
    fun stop() {
        cancelled.set(true)
        val old = process
        process = null
        activeGuid = null
        startupReady?.countDown()
        startupReady = null
        firstResolverReady?.countDown()
        firstResolverReady = null
        if (old != null) {
            runCatching { old.destroy() }
            if (runCatching { old.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false).not()) {
                runCatching { old.destroyForcibly() }
            }
        }
    }

    private fun listenerAvailable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 250) }
        true
    }.getOrDefault(false)

    private fun awaitFirstHealthyResolver(
        started: Process,
        ready: CountDownLatch,
        generation: Long,
        cancellationRequested: () -> Boolean,
    ) {
        while (true) {
            checkStartupNotCancelled(generation, cancellationRequested)
            check(started.isAlive) {
                "SoarDNS client exited before verifying a healthy DNS resolver. Review the SoarDNS log."
            }
            if (ready.await(250, TimeUnit.MILLISECONDS)) break
        }
        checkStartupNotCancelled(generation, cancellationRequested)
        check(started.isAlive) { "SoarDNS client stopped after verifying a DNS resolver" }
    }

    private fun checkStartupNotCancelled(generation: Long, cancellationRequested: () -> Boolean) {
        check(!cancelled.get() && startupGeneration.get() == generation && !cancellationRequested()) {
            "SoarDNS resolver scan was cancelled"
        }
    }

    private fun logDirectory(context: Context) = File(context.filesDir, "soar/logs")

    private fun publishResolverProgress(context: Context, guid: String, line: String) {
        if (!line.startsWith("SOAR_DNS_SCAN ")) return
        val fields = line.removePrefix("SOAR_DNS_SCAN ").trim().split(Regex("\\s+"))
        val numericFields = if (fields.firstOrNull()?.equals("manual", ignoreCase = true) == true ||
            fields.firstOrNull()?.equals("discovery", ignoreCase = true) == true
        ) fields.drop(1) else fields
        val values = numericFields.mapNotNull(String::toIntOrNull)
        if (values.size != 3) return
        val (completed, total, valid) = values
        if (total <= 0 || completed !in 0..total || valid !in 0..completed) return
        runCatching {
            MessageHelper.sendMsg2UI(context, AppConfig.MSG_SOAR_PROGRESS, "$guid|$completed|$total|$valid")
        }
        if (valid > 0 && activeGuid == guid) firstResolverReady?.countDown()
    }

    private fun timestamp(): String = synchronized(timestampFormat) { timestampFormat.format(Date()) }

    private fun appendLog(file: File, line: String) {
        runCatching { file.appendText("${timestamp()} $line\n") }
    }
}
