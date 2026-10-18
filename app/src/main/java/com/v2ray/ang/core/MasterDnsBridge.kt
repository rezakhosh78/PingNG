package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.AppConfig
import com.v2ray.ang.helper.MessageHelper
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs MasterDnsVPN or DNSTT as the local TCP/SOCKS endpoint used by Xray. */
object MasterDnsBridge {
    const val PORT = 18000
    const val SSH_SOCKS_PORT = 18001
    const val LABEL = "MasterDNS"
    const val UI_LABEL = "MasterDNS"
    const val DNSTT = "DNSTT"
    private const val DEFAULT_DNSTT_SSH_KEEPALIVE_SECONDS = 6
    private const val DNSTT_SSH_KEEPALIVE_COUNT_MAX = 3
    private const val MIN_DNSTT_SSH_KEEPALIVE_SECONDS = 5
    private const val MAX_DNSTT_SSH_KEEPALIVE_SECONDS = 300
    private val dnsttLogTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val dnsttLogTimeLock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var sshSession: Session? = null
    @Volatile private var sshSessionRequired = false
    @Volatile private var sshLogFile: File? = null
    private val sshLogger = object : Logger {
        override fun isEnabled(level: Int): Boolean = sshLogFile != null
        override fun log(level: Int, message: String) {
            val file = sshLogFile ?: return
            val label = when (level) {
                Logger.DEBUG -> "DEBUG"
                Logger.INFO -> "INFO"
                Logger.WARN -> "WARN"
                Logger.ERROR -> "ERROR"
                Logger.FATAL -> "FATAL"
                else -> "EVENT"
            }
            // JSch reports negotiation and authentication state, not credentials.
            appendDnsttLog(file, "SSH/$label: ${message.replace('\n', ' ')}")
        }
    }
    @Volatile private var activeGuid: String? = null
    private val cancelled = AtomicBoolean(false)
    private val resultPattern = Regex("""(?:Accepted|Rejected) \((\d+)/(\d+)\)""")
    private val validPattern = Regex("""totals:\s*valid=(\d+)""")
    private val ansiPattern = Regex("\u001B\\[[0-9;]*m")
    private val colorTagPattern = Regex("</?[a-z]+>")
    private val nonLatinScriptPattern = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF]")
    private val catalogPattern = Regex("""Connection Catalog: (\d+) domain-resolver pairs""")
    @Synchronized fun isActive(): Boolean = process?.isAlive == true &&
        (!sshSessionRequired || sshSession?.isConnected == true)

    fun outboundPort(profile: ProfileItem): Int =
        if (profile.description == DNSTT && profile.dnsTunnelMode == "SSH") SSH_SOCKS_PORT else PORT

    private fun dnsttSshKeepaliveSeconds(profile: ProfileItem): Int =
        (profile.dnsTunnelSshKeepaliveSeconds ?: DEFAULT_DNSTT_SSH_KEEPALIVE_SECONDS)
            .coerceIn(MIN_DNSTT_SSH_KEEPALIVE_SECONDS, MAX_DNSTT_SSH_KEEPALIVE_SECONDS)

    internal fun appendDnsttLog(log: File, message: String) {
        val timestamp = synchronized(dnsttLogTimeLock) { dnsttLogTimeFormat.format(Date()) }
        runCatching { log.appendText("$timestamp ${message.replace(nonLatinScriptPattern, "?")}\n") }
    }

    private fun sanitizeExistingDnsttLog(log: File) {
        runCatching {
            if (log.isFile) {
                val previous = log.readText()
                if (nonLatinScriptPattern.containsMatchIn(previous)) {
                    log.writeText(previous.replace(nonLatinScriptPattern, "?"))
                }
            }
        }
    }

    private fun createDnsttSshSession(profile: ProfileItem, log: File): Session {
        sshLogFile = log
        JSch.setLogger(sshLogger)
        val username = profile.dnsTunnelSshUsername?.trim().orEmpty()
        require(username.isNotEmpty()) { "DNSTT SSH username is required" }
        return JSch().getSession(username, "127.0.0.1", PORT).apply {
            // The connect call below has its own timeout. Once authenticated,
            // let JSch's configured keepalive detect dead links instead of
            // applying a second, idle socket-read timeout.
            timeout = 0
            setPassword(profile.dnsTunnelSshPassword.orEmpty())
            setConfig("StrictHostKeyChecking", "no")
            // Keep the SSH control connection active through quiet periods and
            // detect a stalled DNS/Noise stream after a bounded number of misses.
            setServerAliveInterval(dnsttSshKeepaliveSeconds(profile) * 1_000)
            setServerAliveCountMax(DNSTT_SSH_KEEPALIVE_COUNT_MAX)
        }
    }

    private fun isCurrentDnsttRun(expectedProcess: Process, guid: String): Boolean =
        !cancelled.get() && process === expectedProcess && activeGuid == guid && expectedProcess.isAlive

    private fun startDnsttSshSupervisor(expectedProcess: Process, guid: String, profile: ProfileItem, log: File) {
        Thread({
            var failedAttempts = 0
            while (isCurrentDnsttRun(expectedProcess, guid)) {
                val currentSession = sshSession
                if (currentSession?.isConnected == true) {
                    try {
                        Thread.sleep(5_000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    continue
                }

                failedAttempts++
                appendDnsttLog(log, "WARNING: SSH connection lost; starting reconnect attempt $failedAttempts.")
                val newSession = try {
                    createDnsttSshSession(profile, log)
                } catch (e: Exception) {
                    appendDnsttLog(log, "ERROR: Could not prepare SSH reconnect: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                    null
                }

                if (newSession != null) {
                    val canConnect = synchronized(this) {
                        if (!isCurrentDnsttRun(expectedProcess, guid)) {
                            false
                        } else {
                            SshSocksBridge.stop()
                            sshSession?.disconnect()
                            sshSession = newSession
                            true
                        }
                    }
                    if (!canConnect) {
                        newSession.disconnect()
                        return@Thread
                    }

                    try {
                        newSession.connect(60_000)
                        val listenerRestored = synchronized(this) {
                            if (isCurrentDnsttRun(expectedProcess, guid) && sshSession === newSession) {
                                SshSocksBridge.start(newSession, SSH_SOCKS_PORT, log)
                                true
                            } else {
                                false
                            }
                        }
                        if (!listenerRestored) {
                            newSession.disconnect()
                            return@Thread
                        }
                        failedAttempts = 0
                        appendDnsttLog(log, "SSH over DNSTT reconnected; local SOCKS5 is ready at 127.0.0.1:$SSH_SOCKS_PORT.")
                        continue
                    } catch (e: Exception) {
                        synchronized(this) {
                            if (sshSession === newSession) sshSession = null
                            if (isCurrentDnsttRun(expectedProcess, guid)) SshSocksBridge.stop()
                        }
                        newSession.disconnect()
                        appendDnsttLog(log, "ERROR: SSH reconnect attempt failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                    }
                }

                val delayMs = when {
                    failedAttempts <= 1 -> 1_000L
                    failedAttempts == 2 -> 2_000L
                    failedAttempts == 3 -> 5_000L
                    else -> 15_000L
                }
                appendDnsttLog(log, "Next reconnect attempt in ${delayMs / 1_000} seconds.")
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
            if (process === expectedProcess && !expectedProcess.isAlive) {
                appendDnsttLog(log, "DNSTT core stopped; ending SSH reconnect attempts.")
            }
        }, "DnsttSshReconnect").apply { isDaemon = true; start() }
    }

    fun readLog(context: Context, guid: String, engine: String): String {
        val name = if (engine == DNSTT) "dnstt.log" else "masterdns.log"
        val file = File(File(context.filesDir, "dns-tunnel"), "${guid.replace(Regex("[^a-zA-Z0-9_-]"), "_")}/$name")
        if (!file.isFile) return "No log yet. Connect this profile to create a log."
        val recent = ArrayDeque<String>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                recent.addLast(line)
                if (recent.size > 1000) recent.removeFirst()
            }
        }
        return recent.joinToString("\n").takeLast(128_000)
    }

    fun clearLog(context: Context, guid: String, engine: String) {
        if (guid.isBlank()) return
        val name = if (engine == DNSTT) "dnstt.log" else "masterdns.log"
        val root = File(context.filesDir, "dns-tunnel")
        val directory = File(root, guid.replace(Regex("[^a-zA-Z0-9_-]"), "_"))
        if (directory.isDirectory) File(directory, name).writeText("")
    }

    /** Stop an in-flight MTU scan immediately when the connect button is cancelled. */
    fun cancelStartup() {
        cancelled.set(true)
        SshSocksBridge.stop()
        sshSession?.disconnect()
        process?.destroyForcibly()
    }

    fun isProfile(profile: ProfileItem): Boolean = profile.description in setOf(LABEL, "StormDNS", DNSTT)

    @Synchronized
    fun start(context: Context, guid: String, profile: ProfileItem) {
        require(isProfile(profile))
        if (activeGuid == guid && isActive()) return
        stop()
        cancelled.set(false)
        val domain = profile.masterDnsDomain.orEmpty().trim()
        val key = profile.masterDnsEncryptionKey.orEmpty()
        val method = profile.masterDnsMethod ?: 1
        val isDnstt = profile.description == DNSTT
        sshSessionRequired = isDnstt && profile.dnsTunnelMode == "SSH"
        require(domain.isNotEmpty() && domain.none { it.isWhitespace() || it == '"' || it == '\\' }) { "Invalid MasterDNS domain" }
        require(key.isNotEmpty()) { "MasterDNS encryption key is required" }
        require(method in 0..5) { "Invalid MasterDNS method" }
        val binary = File(context.applicationInfo.nativeLibraryDir, if (isDnstt) "libdnstt.so" else "libstormdns.so")
        check(binary.isFile && binary.canExecute()) {
            "${if (isDnstt) DNSTT else UI_LABEL} Android core is missing for this APK ABI: ${binary.name}"
        }
        val root = File(context.filesDir, "dns-tunnel/${guid.replace(Regex("[^a-zA-Z0-9_-]"), "_")}").apply { mkdirs() }
        val resolvers = profile.masterDnsResolvers?.takeIf { it.isNotBlank() }
            ?: context.assets.open("masterdns_client_resolvers.txt").bufferedReader().use { it.readText() }
        require(resolvers.lineSequence().any { it.trim().isNotEmpty() && !it.trim().startsWith('#') }) {
            "MasterDNS requires at least one resolver"
        }
        val resolverFile = File(root, "client_resolvers.txt").apply { writeText(resolvers) }
        val defaults = context.assets.open("stormdns_client_config.toml").bufferedReader().use { it.readText() }
        val advanced = MasterDnsSettings.withDefaults(defaults, profile.masterDnsAdvanced)
        val signature = java.security.MessageDigest.getInstance("SHA-256")
            .digest((domain + "\n" + key + "\n" + method + "\n" + resolvers + "\n" + advanced).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val signatureFile = File(root, "identity.sha256")
        val successfulCache = File(root, "successful-resolver-scan.sha256")
        val validResolverFile = File(root, "valid-resolvers.txt")
        val candidateFile = File(root, "valid-resolvers-candidate.txt")
        val reuseValid = !isDnstt && profile.masterDnsReuseValidResolvers
        val cachedRecords = if (reuseValid && validResolverFile.isFile) runCatching {
            val lines = validResolverFile.readLines()
            lines.takeIf { records -> records.firstOrNull() == "pingng-mtu-v1" && records.size > 1 &&
                records.drop(1).all { line ->
                    val fields = line.split(' ')
                    val keyParts = fields.firstOrNull()?.split('|').orEmpty()
                    fields.size == 3 && keyParts.size == 3 &&
                        (keyParts[1].toIntOrNull()?.let { it in 1..65535 } == true) &&
                        (fields[1].toIntOrNull()?.let { it in 38..512 } == true) &&
                        (fields[2].toIntOrNull()?.let { it in 20..4096 } == true)
                } }
        }.getOrNull() else null
        val hasSuccessfulCache = reuseValid && signatureFile.takeIf { it.isFile }?.readText() == signature &&
            successfulCache.takeIf { it.isFile }?.readText() == signature &&
            cachedRecords != null
        if (signatureFile.takeIf { it.isFile }?.readText() != signature) {
            successfulCache.delete()
            validResolverFile.delete()
        }
        if (!reuseValid) successfulCache.delete()
        signatureFile.writeText(signature)
        if (!hasSuccessfulCache) candidateFile.delete()
        val activeResolverFile = if (hasSuccessfulCache) {
            File(root, "fast-resolvers.txt").apply {
                writeText(cachedRecords!!.drop(1).map { record ->
                    val parts = record.substringBefore(' ').split('|')
                    val address = parts[0]
                    "${if (':' in address) "[$address]" else address}:${parts[1]}"
                }.distinct().joinToString("\n", postfix = "\n"))
            }
        } else resolverFile
        // User editable advanced TOML may override defaults, but never the local adapter or identity.
        val reserved = setOf("DOMAINS", "ENCRYPTION_KEY", "DATA_ENCRYPTION_METHOD", "PROTOCOL_TYPE", "LISTEN_IP", "LISTEN_PORT", "SOCKS5_AUTH", "LOCAL_DNS_ENABLED")
        val filtered = advanced.lineSequence().filter { line ->
            val name = line.substringBefore('=').trim()
            name !in reserved
        }.joinToString("\n")
        val config = buildString {
            append("DOMAINS = [\"").append(domain).append("\"]\n")
            append("ENCRYPTION_KEY = ").append(tomlString(key)).append('\n')
            append("DATA_ENCRYPTION_METHOD = ").append(method).append('\n')
            append("PROTOCOL_TYPE = \"SOCKS5\"\nLISTEN_IP = \"127.0.0.1\"\nLISTEN_PORT = $PORT\n")
            append("SOCKS5_AUTH = false\nLOCAL_DNS_ENABLED = false\n")
            append(filtered).append('\n')
        }
        val configFile = File(root, "client_config.toml").apply { writeText(config) }
        val log = File(root, if (isDnstt) "dnstt.log" else "masterdns.log")
        if (isDnstt) {
            sanitizeExistingDnsttLog(log)
            appendDnsttLog(log, "========== Starting DNSTT connection ==========")
            appendDnsttLog(log, "[Step 1/3] Starting DNS/Noise core; transport=${profile.dnsTunnelTransport ?: "udp"}, mode=${profile.dnsTunnelMode ?: "SOCKS5"}")
            appendDnsttLog(log, "Resolver: ${resolvers.trim()} | Domain: $domain")
            PingNgDiagnostics.record("DNSTT starting: transport=${profile.dnsTunnelTransport ?: "udp"}, mode=${profile.dnsTunnelMode ?: "SOCKS5"}")
        } else {
            log.appendText("\n=== Connecting $UI_LABEL ===\n")
            log.appendText("Resolver startup: ${if (hasSuccessfulCache) "Fast (saved valid resolvers)" else "Full scan"}\n")
        }
        val command = if (isDnstt) DnsTunnelArguments.dnstt(
            binary.absolutePath, profile.dnsTunnelTransport ?: "udp", resolvers, key, domain, PORT)
        else listOf(binary.absolutePath, "--config", configFile.absolutePath,
            "--resolvers", activeResolverFile.absolutePath)
        val launcher = ProcessBuilder(command)
            .directory(root).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        if (reuseValid) {
            launcher.environment()[if (hasSuccessfulCache) "PINGNG_MASTERDNS_MTU_CACHE_IN" else "PINGNG_MASTERDNS_MTU_CACHE_OUT"] =
                (if (hasSuccessfulCache) validResolverFile else candidateFile).absolutePath
        }
        val started = launcher.start()
        process = started
        activeGuid = guid
        val sessionReady = AtomicBoolean(false)
        val dnsttListenerReady = AtomicBoolean(false)
        val dnsttStreamReady = AtomicBoolean(false)
        Thread({
            var lastCompleted = -1
            var lastTotal = 0
            val acceptedResults = mutableSetOf<Int>()
            var lastValid = -1
            var lastSentAt = 0L
            try {
                FileOutputStream(log, true).bufferedWriter().use { writer ->
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            writer.appendLine(if (isDnstt) line.replace(nonLatinScriptPattern, "?") else line)
                            writer.flush()
                            val plainLine = line.replace(ansiPattern, "").replace(colorTagPattern, "")
                            if (plainLine.contains("Session Initialized Successfully")) sessionReady.set(true)
                            if (isDnstt && plainLine.contains("begin session")) dnsttListenerReady.set(true)
                            if (isDnstt && plainLine.contains("begin stream")) dnsttStreamReady.set(true)
                            val counts = resultPattern.find(plainLine)
                            val total = counts?.groupValues?.get(2)?.toIntOrNull()
                                ?: catalogPattern.find(plainLine)?.groupValues?.get(1)?.toIntOrNull()
                            val resultIndex = counts?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            if (counts != null && counts.value.startsWith("Accepted") &&
                                total != null && resultIndex in 1..total) acceptedResults.add(resultIndex)
                            val reportedValid = validPattern.find(plainLine)?.groupValues?.get(1)?.toIntOrNull()
                            val valid = maxOf(lastValid, reportedValid ?: acceptedResults.size, 0)
                            val completed = maxOf(lastCompleted, resultIndex, valid, 0)
                            if (total != null && total > 0 && completed in 0..total &&
                                (total != lastTotal || completed > lastCompleted || valid != lastValid) &&
                                process === started
                            ) {
                                val now = System.currentTimeMillis()
                                if (completed == 0 || completed == total || valid != lastValid || now - lastSentAt >= 100L) {
                                    runCatching {
                                        MessageHelper.sendMsg2UI(context, AppConfig.MSG_MASTERDNS_PROGRESS,
                                            "$guid|$completed|$total|$valid")
                                    }
                                    lastSentAt = now
                                    lastValid = valid
                                }
                                lastCompleted = completed
                                lastTotal = total
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // A stopped process may close the pipe while the reader is exiting.
            }
        }, "MasterDnsProgress").apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + 180_000
        var listenerReady = false
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.get()) {
                stop()
                error("MasterDNS startup cancelled")
            }
            if (!started.isAlive) {
                val details = log.takeIf { it.isFile }?.readLines()?.takeLast(8)?.joinToString(" ").orEmpty()
                stop()
                error("MasterDNS client exited: $details")
            }
            if (!listenerReady && isDnstt) listenerReady = dnsttListenerReady.get()
            if (!listenerReady && !isDnstt) listenerReady = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 200) }
            }.isSuccess
            // The core opens its SOCKS listener before a remote session exists.
            if (listenerReady && !isDnstt && sessionReady.get()) {
                if (reuseValid && !hasSuccessfulCache) {
                    if (candidateFile.isFile && candidateFile.readText().startsWith("pingng-mtu-v1\n")) {
                        candidateFile.copyTo(validResolverFile, overwrite = true)
                        successfulCache.writeText(signature)
                    }
                }
                return
            }
            if (listenerReady && isDnstt) {
                if (profile.dnsTunnelMode == "SSH") {
                    // Opening SSH itself starts the first DNSTT stream. An extra
                    // empty probe consumes a stream and may stall before SSH starts.
                    appendDnsttLog(log, "[Step 2/3] Listener is ready; starting tunnel and SSH handshake.")
                }
                if (profile.dnsTunnelMode != "SSH") {
                    try {
                        appendDnsttLog(log, "[Step 2/3] Listener is ready; checking the remote SOCKS5 server.")
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", PORT), 2_000)
                            socket.soTimeout = 30_000
                            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                            socket.getOutputStream().flush()
                            val version = socket.getInputStream().read()
                            val method = socket.getInputStream().read()
                            check(version == 5 && method == 0) {
                                "DNSTT remote endpoint is not an unauthenticated SOCKS5 server (reply: $version/$method)"
                            }
                        }
                        appendDnsttLog(log, "Connected: DNSTT tunnel and remote SOCKS5 are ready.")
                        PingNgDiagnostics.record("DNSTT remote SOCKS5 handshake succeeded")
                    } catch (e: Exception) {
                        appendDnsttLog(log, "ERROR: Remote SOCKS5 check failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                        PingNgDiagnostics.record("DNSTT remote SOCKS5 handshake failed", e)
                        stop()
                        throw e
                    }
                }
                if (isDnstt && profile.dnsTunnelMode == "SSH") {
                    try {
                        if (cancelled.get()) error("DNSTT SSH startup cancelled")
                        // The server's -upstream target is already the SSH port.
                        // A SOCKS5 fallback sends a SOCKS greeting to SSH and
                        // cannot repair a DNS/Noise timeout.
                        val keepaliveSeconds = dnsttSshKeepaliveSeconds(profile)
                        appendDnsttLog(log, "[Step 3/3] Connecting SSH directly through DNSTT; resolvers=${resolvers.trim()}, domain=$domain")
                        appendDnsttLog(log, "SSH keepalive: every $keepaliveSeconds seconds; disconnect after $DNSTT_SSH_KEEPALIVE_COUNT_MAX missed replies.")
                        // DNS/KCP/Noise may need multiple round trips before SSH
                        // sends its banner. Do not cut that handshake off at 20 s.
                        for (attempt in 1..2) {
                            if (cancelled.get()) error("DNSTT SSH startup cancelled")
                            val session = createDnsttSshSession(profile, log)
                            sshSession = session
                            appendDnsttLog(log, "[SSH] Connection attempt $attempt/2")
                            try {
                                session.connect(60_000)
                                SshSocksBridge.start(session, SSH_SOCKS_PORT, log)
                                appendDnsttLog(log, "[SSH] Authentication succeeded; local SOCKS5 listener is ready.")
                                break
                            } catch (e: Exception) {
                                val streamOpened = dnsttStreamReady.get()
                                val stage = if (streamOpened) "SSH negotiation/authentication" else "DNS/Noise handshake"
                                appendDnsttLog(log, "ERROR: Attempt $attempt failed during $stage: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                                PingNgDiagnostics.record("DNSTT attempt $attempt $stage failed", e)
                                session.disconnect()
                                sshSession = null
                                if (streamOpened || attempt == 2 || cancelled.get() || !started.isAlive) {
                                    if (!streamOpened) appendDnsttLog(log,
                                        "ERROR: DNSTT did not open a stream; DNS/KCP/Noise handshake did not complete.")
                                    throw e
                                }
                                Thread.sleep(500L * attempt)
                            }
                        }
                        appendDnsttLog(log, "Connected: DNSTT tunnel, SSH session, and SOCKS5 are ready at 127.0.0.1:$SSH_SOCKS_PORT.")
                        PingNgDiagnostics.record("DNSTT SSH authenticated; local SOCKS5 ready")
                        startDnsttSshSupervisor(started, guid, profile, log)
                    } catch (e: Exception) {
                        appendDnsttLog(log, "ERROR: SSH connection failed: ${e.message.orEmpty()}")
                        stop()
                        throw e
                    }
                }
                return
            }
            try { Thread.sleep(150) } catch (e: InterruptedException) {
                stop()
                Thread.currentThread().interrupt()
                throw e
            }
        }
        stop()
        val details = log.takeIf { it.isFile }?.readLines()?.takeLast(8)?.joinToString(" ").orEmpty()
        if (!isDnstt && listenerReady) log.appendText("Session did not initialize; review server key/domain and resolver health.\n")
        error("${if (listenerReady && !isDnstt) "MasterDNS session did not initialize" else "DNS tunnel listener did not start"}: $details")
    }

    @Synchronized
    fun stop() {
        SshSocksBridge.stop()
        sshSession?.disconnect()
        sshSession = null
        sshSessionRequired = false
        sshLogFile = null
        process?.let { running ->
            running.destroy()
            runCatching { running.waitFor(700, TimeUnit.MILLISECONDS) }
            if (running.isAlive) running.destroyForcibly()
        }
        process = null
        activeGuid = null
    }

    private fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { char -> when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(char)
        } }
        append('"')
    }
}
