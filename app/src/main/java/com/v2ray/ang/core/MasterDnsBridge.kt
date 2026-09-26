package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.AppConfig
import com.v2ray.ang.helper.MessageHelper
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the upstream MasterDnsVPN client as the SOCKS5 endpoint used by Xray. */
object MasterDnsBridge {
    const val PORT = 18000
    const val LABEL = "MasterDNS"
    @Volatile private var process: Process? = null
    private var activeGuid: String? = null
    private val cancelled = AtomicBoolean(false)
    private val resultPattern = Regex("""(?:Accepted|Rejected) \((\d+)/(\d+)\)""")
    private val catalogPattern = Regex("""Connection Catalog: (\d+) domain-resolver pairs""")
    @Synchronized fun isActive(): Boolean = process?.isAlive == true

    /** Stop an in-flight MTU scan immediately when the connect button is cancelled. */
    fun cancelStartup() {
        cancelled.set(true)
        process?.destroyForcibly()
    }

    fun isProfile(profile: ProfileItem): Boolean = profile.description == LABEL

    @Synchronized
    fun start(context: Context, guid: String, profile: ProfileItem) {
        require(isProfile(profile))
        if (activeGuid == guid && process?.isAlive == true) return
        stop()
        cancelled.set(false)
        val domain = profile.masterDnsDomain.orEmpty().trim()
        val key = profile.masterDnsEncryptionKey.orEmpty()
        val method = profile.masterDnsMethod ?: 1
        require(domain.isNotEmpty() && domain.none { it.isWhitespace() || it == '"' || it == '\\' }) { "Invalid MasterDNS domain" }
        require(key.isNotEmpty()) { "MasterDNS encryption key is required" }
        require(method in 0..5) { "Invalid MasterDNS method" }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libmasterdns.so")
        check(binary.isFile) { "MasterDNS ARM64 executable is unavailable" }
        val root = File(context.filesDir, "masterdns/$guid").apply { mkdirs() }
        val defaults = context.assets.open("masterdns_client_config.toml").bufferedReader().use { it.readText() }
        val advanced = profile.masterDnsAdvanced?.takeIf { it.isNotBlank() } ?: defaults
        // User editable advanced TOML may override defaults, but never the local adapter or identity.
        val reserved = setOf("DOMAINS", "ENCRYPTION_KEY", "DATA_ENCRYPTION_METHOD", "PROTOCOL_TYPE", "LISTEN_IP", "LISTEN_PORT")
        val filtered = advanced.lineSequence().filter { line ->
            val name = line.substringBefore('=').trim()
            name !in reserved
        }.joinToString("\n")
        val config = buildString {
            append("DOMAINS = [\"").append(domain).append("\"]\n")
            append("ENCRYPTION_KEY = ").append(tomlString(key)).append('\n')
            append("DATA_ENCRYPTION_METHOD = ").append(method).append('\n')
            append("PROTOCOL_TYPE = \"SOCKS5\"\nLISTEN_IP = \"127.0.0.1\"\nLISTEN_PORT = $PORT\n")
            append(filtered).append('\n')
        }
        val configFile = File(root, "client_config.toml").apply { writeText(config) }
        val resolvers = profile.masterDnsResolvers?.takeIf { it.isNotBlank() }
            ?: context.assets.open("masterdns_client_resolvers.txt").bufferedReader().use { it.readText() }
        require(resolvers.lineSequence().any { it.trim().isNotEmpty() && !it.trim().startsWith('#') }) {
            "MasterDNS requires at least one resolver"
        }
        val resolverFile = File(root, "client_resolvers.txt").apply { writeText(resolvers) }
        val log = File(root, "client.log")
        val started = ProcessBuilder(binary.absolutePath, "-config", configFile.absolutePath,
            "-resolvers", resolverFile.absolutePath)
            .directory(root).redirectErrorStream(true).start()
        process = started
        activeGuid = guid
        Thread({
            var lastCompleted = -1
            var lastTotal = 0
            var lastSentAt = 0L
            try {
                FileOutputStream(log, true).bufferedWriter().use { writer ->
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            writer.appendLine(line)
                            writer.flush()
                            val counts = resultPattern.find(line)
                            val total = counts?.groupValues?.get(2)?.toIntOrNull()
                                ?: catalogPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
                            val completed = counts?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            if (total != null && total > 0 && completed in 0..total &&
                                (total != lastTotal || completed > lastCompleted) &&
                                process === started
                            ) {
                                val now = System.currentTimeMillis()
                                if (completed == 0 || completed == total || now - lastSentAt >= 100L) {
                                    runCatching {
                                        MessageHelper.sendMsg2UI(context, AppConfig.MSG_MASTERDNS_PROGRESS,
                                            "$guid|$completed|$total")
                                    }
                                    lastSentAt = now
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
        val deadline = System.currentTimeMillis() + 90_000
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
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 200) } }.isSuccess) return
            try { Thread.sleep(150) } catch (e: InterruptedException) {
                stop()
                Thread.currentThread().interrupt()
                throw e
            }
        }
        stop()
        error("MasterDNS SOCKS5 listener did not start; see MasterDNS client log")
    }

    @Synchronized
    fun stop() {
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
