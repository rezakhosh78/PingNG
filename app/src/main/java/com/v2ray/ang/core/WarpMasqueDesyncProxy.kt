package com.v2ray.ang.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local HTTP CONNECT adapter for the MASQUE/H2 process. The external MASQUE
 * core honors http_proxy; this adapter forwards CONNECT through PingNG's
 * SOCKS5 Desync listener so Desync sees the real outer TLS ClientHello.
 */
object WarpMasqueDesyncProxy {
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var upstreamSocksPort: Int = 0
    @Volatile private var acceptThread: Thread? = null
    private val endpointListeners = mutableMapOf<String, ServerSocket>()
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "WarpMasque-DesyncProxy-Client").apply { isDaemon = true }
    }

    @Synchronized
    fun start(socksPort: Int): String {
        require(socksPort in 1..65535) { "WARP MASQUE Desync SOCKS port is unavailable" }
        if (listener?.isBound == true && !listener!!.isClosed && upstreamSocksPort == socksPort) {
            return proxyUrl(listener!!.localPort)
        }
        stop()
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
        }
        listener = server
        upstreamSocksPort = socksPort
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "WarpMasque-DesyncProxy-Client").apply { isDaemon = true }
        }
        workers = executor
        acceptThread = Thread({ acceptLoop(server, executor) }, "WarpMasque-DesyncProxy-Accept").apply {
            isDaemon = true
            start()
        }
        val url = proxyUrl(server.localPort)
        PingNgDiagnostics.record("WARP MASQUE outer HTTP CONNECT is routed through Desync at $url")
        return url
    }

    fun proxyUrl(): String? = listener?.takeUnless { it.isClosed }?.let { proxyUrl(it.localPort) }

    /** Each MASQUE process gets a proxy pinned to the IP it is testing. */
    @Synchronized
    fun proxyUrlForEndpoint(endpointHost: String, endpointPort: Int): String {
        check(listener?.isClosed == false && upstreamSocksPort in 1..65535) {
            "WARP MASQUE Desync HTTP proxy is not running"
        }
        require(endpointPort in 1..65535)
        val endpointKey = "$endpointHost:$endpointPort"
        endpointListeners[endpointKey]?.takeUnless { it.isClosed }?.let {
            return proxyUrl(it.localPort)
        }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
        }
        endpointListeners[endpointKey] = server
        Thread({ acceptLoop(server, workers, endpointHost, endpointPort) }, "WarpMasque-DesyncProxy-$endpointHost").apply {
            isDaemon = true
            start()
        }
        return proxyUrl(server.localPort)
    }

    @Synchronized
    fun stop() {
        runCatching { listener?.close() }
        endpointListeners.values.forEach { runCatching { it.close() } }
        endpointListeners.clear()
        listener = null
        upstreamSocksPort = 0
        acceptThread = null
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        workers.shutdownNow()
        runCatching { workers.awaitTermination(300, TimeUnit.MILLISECONDS) }
        workers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "WarpMasque-DesyncProxy-Client").apply { isDaemon = true }
        }
    }

    private fun acceptLoop(
        server: ServerSocket,
        executor: java.util.concurrent.ExecutorService,
        endpointHost: String? = null,
        endpointPort: Int? = null,
    ) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            clients += client
            runCatching {
                executor.execute {
                    try {
                        client.tcpNoDelay = true
                        client.soTimeout = 10_000
                        handleConnect(client, upstreamSocksPort, endpointHost, endpointPort)
                    } catch (error: Throwable) {
                        PingNgDiagnostics.record("WARP MASQUE HTTP CONNECT via Desync failed", error)
                    } finally {
                        clients -= client
                        runCatching { client.close() }
                    }
                }
            }.onFailure {
                clients -= client
                runCatching { client.close() }
            }
        }
    }

    private fun handleConnect(client: Socket, socksPort: Int, endpointHost: String?, endpointPort: Int?) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val requestLine = readHttpLine(input) ?: return
        val fields = requestLine.trim().split(Regex("\\s+"), limit = 3)
        if (fields.size < 2 || !fields[0].equals("CONNECT", ignoreCase = true)) {
            output.write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
            return
        }
        while (true) {
            val header = readHttpLine(input) ?: return
            if (header.isEmpty()) break
        }
        val (host, port) = parseAuthority(fields[1])
        val dialHost = endpointHost?.takeIf {
            host.equals("cloudflareaccess.com", ignoreCase = true) ||
                host.equals("consumer-masque.cloudflareclient.com", ignoreCase = true)
        } ?: host
        val dialPort = if (dialHost != host) endpointPort ?: port else port
        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress("127.0.0.1", socksPort), 4_000)
            upstream.tcpNoDelay = true
            val socksIn = upstream.getInputStream()
            val socksOut = upstream.getOutputStream()
            socksOut.write(byteArrayOf(0x05, 0x01, 0x00))
            socksOut.flush()
            val greeting = ByteArray(2)
            readFully(socksIn, greeting)
            check(greeting[0] == 0x05.toByte() && greeting[1] == 0x00.toByte()) { "Desync SOCKS5 rejected no-auth mode" }
            // The Go HTTP proxy CONNECT authority is cloudflareaccess.com.
            // Sending that DNS name to SOCKS bypasses the endpoint IP being
            // verified and makes the core reject the different pinned key.
            socksOut.write(connectRequest(dialHost, dialPort))
            socksOut.flush()
            val reply = ByteArray(4)
            readFully(socksIn, reply)
            check(reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()) {
                "Desync SOCKS5 CONNECT failed with code ${reply[1].toInt() and 0xff}"
            }
            val addressLength = when (reply[3].toInt() and 0xff) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> socksIn.read().also { check(it >= 0) }
                else -> error("Invalid Desync SOCKS5 address type")
            }
            skipFully(socksIn, addressLength + 2)
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
            PingNgDiagnostics.record(
                "WARP MASQUE outer TLS CONNECT through Desync: $dialHost:$dialPort (SNI $host)",
            )
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    private fun connectRequest(host: String, port: Int): ByteArray {
        val numericAddress = host.contains(':') || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        val address = if (numericAddress) InetAddress.getByName(host) else null
        val bytes = when (address) {
            is Inet4Address -> byteArrayOf(0x01) + address.address
            is Inet6Address -> byteArrayOf(0x04) + address.address
            else -> {
                val domain = java.net.IDN.toASCII(host).toByteArray(StandardCharsets.US_ASCII)
                require(domain.isNotEmpty() && domain.size <= 255) { "Invalid WARP MASQUE proxy hostname" }
                byteArrayOf(0x03, domain.size.toByte()) + domain
            }
        }
        return byteArrayOf(0x05, 0x01, 0x00) + bytes + byteArrayOf((port shr 8).toByte(), port.toByte())
    }

    private fun parseAuthority(authority: String): Pair<String, Int> {
        val value = authority.trim()
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            require(close > 1) { "Invalid CONNECT authority" }
            val host = value.substring(1, close)
            val port = value.substring(close + 1).removePrefix(":").toIntOrNull() ?: 443
            require(port in 1..65535)
            return host to port
        }
        val colon = value.lastIndexOf(':')
        val host = if (colon > 0) value.substring(0, colon) else value
        val port = if (colon > 0) value.substring(colon + 1).toIntOrNull() ?: 443 else 443
        require(host.isNotBlank() && port in 1..65535) { "Invalid CONNECT authority" }
        return host to port
    }

    private fun readHttpLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= 8192) {
            val value = input.read()
            if (value < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (value == '\n'.code) return line.toString("ISO-8859-1").removeSuffix("\r")
            line.write(value)
        }
        error("HTTP CONNECT line exceeds limit")
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) error("Unexpected EOF during SOCKS5 negotiation")
            offset += count
        }
    }

    private fun skipFully(input: InputStream, bytes: Int) {
        repeat(bytes) { check(input.read() >= 0) { "Unexpected EOF during SOCKS5 reply" } }
    }

    private fun relay(client: Socket, upstream: Socket) {
        val clientToUpstream = Thread({
            runCatching { client.getInputStream().copyTo(upstream.getOutputStream()) }
            runCatching { upstream.shutdownOutput() }
        }, "WarpMasque-DesyncProxy-Upload").apply { isDaemon = true; start() }
        runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
        runCatching { client.shutdownOutput() }
        runCatching { clientToUpstream.join(2_000L) }
    }

    private fun proxyUrl(port: Int) = "http://127.0.0.1:$port"
}
