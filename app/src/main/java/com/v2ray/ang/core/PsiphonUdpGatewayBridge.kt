package com.v2ray.ang.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local SOCKS5 UDP-associate endpoint backed by Psiphon's BadVPN UDPGW port.
 *
 * The Psiphon runtime exposes only its TCP SOCKS listener. UDP is encoded with
 * the BadVPN UDPGW wire format and sent through a SOCKS CONNECT to the bundled
 * Psiphon destination 127.0.0.1:7300. All local listeners bind to loopback.
 */
object PsiphonUdpGatewayBridge {
    private const val UDPGW_HOST = "127.0.0.1"
    private const val UDPGW_PORT = 7300
    private const val SOCKS_VERSION = 5
    private const val SOCKS_NO_AUTH = 0
    private const val SOCKS_UDP_ASSOCIATE = 3
    private const val SOCKS_CONNECT = 1
    private const val SOCKS_SUCCESS = 0
    private const val SOCKS_GENERAL_FAILURE = 1
    private const val SOCKS_ATYP_IPV4 = 1
    private const val SOCKS_ATYP_DOMAIN = 3
    private const val SOCKS_ATYP_IPV6 = 4
    private const val UDPGW_FLAG_KEEPALIVE = 0x01
    private const val UDPGW_FLAG_REBIND = 0x02
    private const val UDPGW_FLAG_DNS = 0x04
    private const val UDPGW_FLAG_IPV6 = 0x08
    private const val MAX_ASSOCIATIONS = 48
    private const val MAX_FLOWS_PER_ASSOCIATION = 4096
    private const val UDP_IDLE_TIMEOUT_MS = 1_000
    private const val KEEPALIVE_INTERVAL_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val SOCKS_TIMEOUT_MS = 4_000

    private val lifecycleLock = Any()
    private val associationSlots = Semaphore(MAX_ASSOCIATIONS)
    private val sessions = ConcurrentHashMap.newKeySet<GatewaySession>()
    private val loggedConnectedGeneration = AtomicInteger(-1)
    private val loggedResponseGeneration = AtomicInteger(-1)
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var listenerPort = 0
    @Volatile private var psiphonSocksPort = 0
    @Volatile private var generation = 0
    @Volatile private var lastUnavailableLogMs = 0L
    @Volatile private var profileIdentifier = "unknown"

    /** Starts the loopback SOCKS listener once and returns its TCP port. */
    @JvmStatic
    fun ensureListening(profileId: String? = null): Int = synchronized(lifecycleLock) {
        profileId?.trim()?.takeIf(String::isNotEmpty)?.let { profileIdentifier = it }
        listener?.takeIf { !it.isClosed }?.let { return@synchronized listenerPort }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
        }
        val token = ++generation
        listener = server
        listenerPort = server.localPort
        Thread({ acceptLoop(server, token) }, "pingng-psiphon-udpgw-accept").apply {
            isDaemon = true
            start()
        }
        record("bridge listening on 127.0.0.1:$listenerPort")
        listenerPort
    }

    /** Makes existing bridge sessions use the currently connected Psiphon SOCKS port. */
    @JvmStatic
    fun setPsiphonSocksPort(port: Int) {
        val nextPort = port.takeIf { it in 1..65535 } ?: 0
        val previous = psiphonSocksPort
        psiphonSocksPort = nextPort
        if (nextPort == 0 || (previous in 1..65535 && previous != nextPort)) {
            sessions.toList().forEach(GatewaySession::close)
        }
    }

    /** Returns the bridge's loopback TCP port after preparing its listener. */
    @JvmStatic
    fun listenPort(): Int = ensureListening()

    /** Stops the listener and closes every active UDP association. */
    @JvmStatic
    fun stop() {
        val server: ServerSocket?
        synchronized(lifecycleLock) {
            generation++
            psiphonSocksPort = 0
            server = listener
            listener = null
            listenerPort = 0
        }
        runCatching { server?.close() }
        sessions.toList().forEach(GatewaySession::close)
    }

    private fun acceptLoop(server: ServerSocket, token: Int) {
        while (isCurrent(server, token)) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (error: Throwable) {
                if (isCurrent(server, token)) {
                    record("bridge accept failed", error)
                }
                break
            }
            if (!socket.inetAddress.isLoopbackAddress || !associationSlots.tryAcquire()) {
                runCatching { socket.close() }
                continue
            }
            Thread({
                try {
                    handleClient(socket, token)
                } finally {
                    associationSlots.release()
                }
            }, "pingng-psiphon-udpgw-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(client: Socket, token: Int) {
        val session = GatewaySession(client, token)
        sessions.add(session)
        try {
            client.tcpNoDelay = true
            client.soTimeout = SOCKS_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()
            if (!negotiateLocalSocks(input, output)) return
            val command = readSocksRequest(input)
            if (command != SOCKS_UDP_ASSOCIATE) {
                writeSocksReply(output, SOCKS_GENERAL_FAILURE)
                return
            }

            val upstreamPort = psiphonSocksPort
            if (upstreamPort !in 1..65535 || !isCurrent(token)) {
                writeSocksReply(output, SOCKS_GENERAL_FAILURE)
                return
            }

            val udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                soTimeout = UDP_IDLE_TIMEOUT_MS
            }
            session.udpSocket = udpSocket
            val tunnel = try {
                openUdpgwTunnel(upstreamPort)
            } catch (error: Throwable) {
                udpSocket.close()
                session.udpSocket = null
                logGatewayUnavailable(error)
                writeSocksReply(output, SOCKS_GENERAL_FAILURE)
                return
            }
            session.upstream = tunnel.socket
            if (!isCurrent(token)) return
            recordOncePerGeneration(
                loggedConnectedGeneration,
                token,
                "SOCKS CONNECT to UDPGW accepted; waiting for a real UDP response",
            )

            val localAddress = InetAddress.getByName("127.0.0.1").address
            output.write(byteArrayOf(
                SOCKS_VERSION.toByte(), SOCKS_SUCCESS.toByte(), 0, SOCKS_ATYP_IPV4.toByte(),
                localAddress[0], localAddress[1], localAddress[2], localAddress[3],
                (udpSocket.localPort ushr 8).toByte(), udpSocket.localPort.toByte(),
            ))
            output.flush()
            client.soTimeout = 0

            startTunnelReader(session, tunnel.input)
            startControlWatcher(session, input)
            runUdpLoop(session, tunnel.output)
        } catch (error: Throwable) {
            if (!session.closed.get() && isCurrent(token)) {
                record("association ended", error)
            }
        } finally {
            session.close()
            sessions.remove(session)
        }
    }

    private fun negotiateLocalSocks(input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
        val methodCount = input.read()
        if (version != SOCKS_VERSION || methodCount !in 1..32) {
            runCatching { output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xff.toByte())) }
            return false
        }
        val methods = ByteArray(methodCount)
        readFully(input, methods)
        if (methods.none { (it.toInt() and 0xff) == SOCKS_NO_AUTH }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xff.toByte()))
            output.flush()
            return false
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), SOCKS_NO_AUTH.toByte()))
        output.flush()
        return true
    }

    /** Returns (command, destination address type); the requested bind address is ignored. */
    private fun readSocksRequest(input: InputStream): Int {
        val version = input.read()
        val command = input.read()
        val reserved = input.read()
        val addressType = input.read()
        if (version != SOCKS_VERSION || reserved != 0 || addressType !in SOCKS_ATYP_IPV4..SOCKS_ATYP_IPV6) {
            throw EOFException("Invalid local SOCKS request")
        }
        skipSocksAddress(input, addressType)
        readU16(input)
        return command
    }

    private fun skipSocksAddress(input: InputStream, addressType: Int) {
        when (addressType) {
            SOCKS_ATYP_IPV4 -> skipFully(input, 4)
            SOCKS_ATYP_IPV6 -> skipFully(input, 16)
            SOCKS_ATYP_DOMAIN -> {
                val length = input.read()
                if (length < 1) throw EOFException("Invalid SOCKS domain")
                skipFully(input, length)
            }
            else -> throw EOFException("Unsupported SOCKS address type")
        }
    }

    private data class UdpgwTunnel(
        val socket: Socket,
        val input: BufferedInputStream,
        val output: OutputStream,
    )

    /** Opens the Psiphon local SOCKS listener, then CONNECTs to the embedded UDPGW endpoint. */
    private fun openUdpgwTunnel(socksPort: Int): UdpgwTunnel {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = SOCKS_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, SOCKS_NO_AUTH.toByte()))
            output.flush()
            if (readUnsigned(input) != SOCKS_VERSION || readUnsigned(input) != SOCKS_NO_AUTH) {
                throw SocketException("Psiphon SOCKS did not accept no-auth")
            }
            val remote = InetAddress.getByName(UDPGW_HOST).address
            output.write(byteArrayOf(
                SOCKS_VERSION.toByte(), SOCKS_CONNECT.toByte(), 0, SOCKS_ATYP_IPV4.toByte(),
                remote[0], remote[1], remote[2], remote[3],
                (UDPGW_PORT ushr 8).toByte(), UDPGW_PORT.toByte(),
            ))
            output.flush()
            val version = readUnsigned(input)
            val reply = readUnsigned(input)
            val reserved = readUnsigned(input)
            val addressType = readUnsigned(input)
            if (version != SOCKS_VERSION || reserved != 0) {
                throw SocketException("Invalid Psiphon SOCKS CONNECT response")
            }
            skipSocksAddress(input, addressType)
            readU16(input)
            if (reply != SOCKS_SUCCESS) {
                throw SocketException("Psiphon UDPGW CONNECT rejected with code $reply")
            }
            socket.soTimeout = 0
            return UdpgwTunnel(socket, input, output)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun startTunnelReader(session: GatewaySession, input: InputStream) {
        Thread({
            try {
                while (!session.closed.get() && isCurrent(session.token)) {
                    val frame = readPacketProtoFrame(input) ?: break
                    val reply = parseUdpgwReply(frame, session)
                    if (reply != null) {
                        recordOncePerGeneration(
                            loggedResponseGeneration,
                            session.token,
                            "first UDPGW response received from the selected Psiphon server",
                        )
                        val packet = DatagramPacket(reply.payload, reply.payload.size, reply.client)
                        session.udpSocket?.send(packet)
                    }
                }
            } catch (error: Throwable) {
                if (!session.closed.get() && isCurrent(session.token)) {
                    record("tunnel reader stopped", error)
                }
            } finally {
                session.close()
            }
        }, "pingng-psiphon-udpgw-recv").apply {
            isDaemon = true
            start()
        }
    }

    private fun startControlWatcher(session: GatewaySession, input: InputStream) {
        Thread({
            try {
                val unexpected = input.read()
                if (unexpected >= 0) {
                    record("local control socket sent unexpected data")
                }
            } catch (_: Throwable) {
                // Closing the SOCKS control socket is the normal end of a UDP association.
            } finally {
                session.close()
            }
        }, "pingng-psiphon-udpgw-control").apply {
            isDaemon = true
            start()
        }
    }

    private fun runUdpLoop(session: GatewaySession, output: OutputStream) {
        val buffer = ByteArray(65_535)
        var lastKeepaliveAt = System.currentTimeMillis()
        while (!session.closed.get() && isCurrent(session.token)) {
            val udpSocket = session.udpSocket ?: return
            try {
                val datagram = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(datagram)
                if (!datagram.address.isLoopbackAddress) continue
                val decoded = decodeSocksUdp(datagram.data, datagram.offset, datagram.length) ?: continue
                val client = datagram.socketAddress as? InetSocketAddress ?: continue
                sendUdpgwDatagram(session, output, decoded, client)
                lastKeepaliveAt = System.currentTimeMillis()
            } catch (_: SocketTimeoutException) {
                val now = System.currentTimeMillis()
                if (now - lastKeepaliveAt >= KEEPALIVE_INTERVAL_MS) {
                    sendUdpgwKeepalive(session, output)
                    lastKeepaliveAt = now
                }
            } catch (_: SocketException) {
                return
            }
        }
    }

    private data class RemoteTarget(val address: InetAddress, val port: Int)
    private data class SocksUdpDatagram(val target: RemoteTarget, val payload: ByteArray)

    private fun decodeSocksUdp(data: ByteArray, offset: Int, length: Int): SocksUdpDatagram? {
        if (length < 10 || data[offset] != 0.toByte() || data[offset + 1] != 0.toByte() || data[offset + 2] != 0.toByte()) {
            return null
        }
        var position = offset + 3
        val addressType = data[position++].toInt() and 0xff
        val addressLength = when (addressType) {
            SOCKS_ATYP_IPV4 -> 4
            SOCKS_ATYP_IPV6 -> 16
            // Avoid InetAddress.getByName here: an OS DNS lookup could bypass Psiphon.
            SOCKS_ATYP_DOMAIN -> return null
            else -> return null
        }
        if (position + addressLength + 2 > offset + length) return null
        val address = runCatching {
            InetAddress.getByAddress(data.copyOfRange(position, position + addressLength))
        }.getOrNull() ?: return null
        position += addressLength
        val port = ((data[position].toInt() and 0xff) shl 8) or (data[position + 1].toInt() and 0xff)
        position += 2
        return SocksUdpDatagram(RemoteTarget(address, port), data.copyOfRange(position, offset + length))
    }

    private fun sendUdpgwDatagram(session: GatewaySession, output: OutputStream, datagram: SocksUdpDatagram, client: InetSocketAddress) {
        val target = datagram.target
        val addressBytes = target.address.address
        val flow = session.flowFor(client, target)
        var flags = if (target.address is Inet4Address) 0 else UDPGW_FLAG_IPV6
        if (target.port == 53) flags = flags or UDPGW_FLAG_DNS
        if (flow.rebind) flags = flags or UDPGW_FLAG_REBIND
        val packet = ByteArrayOutputStream(3 + addressBytes.size + 2 + datagram.payload.size).apply {
            write(flags)
            write(flow.conid and 0xff)
            write((flow.conid ushr 8) and 0xff)
            write(addressBytes)
            write((target.port ushr 8) and 0xff)
            write(target.port and 0xff)
            write(datagram.payload)
        }.toByteArray()
        writePacketProtoFrame(output, packet)
    }

    private fun sendUdpgwKeepalive(session: GatewaySession, output: OutputStream) {
        writePacketProtoFrame(output, byteArrayOf(UDPGW_FLAG_KEEPALIVE.toByte(), 0, 0))
    }

    private data class FlowAssignment(val conid: Int, val rebind: Boolean)

    private class GatewaySession(val control: Socket, val token: Int) {
        val closed = AtomicBoolean(false)
        @Volatile var udpSocket: DatagramSocket? = null
        @Volatile var upstream: Socket? = null
        private val flowLock = Any()
        private data class Flow(val client: InetSocketAddress, val target: RemoteTarget)
        private val flowsById = LinkedHashMap<Int, Flow>(16, 0.75f, true)
        private val idsByTarget = HashMap<Flow, Int>()
        private var nextConid = 0

        fun flowFor(client: InetSocketAddress, target: RemoteTarget): FlowAssignment = synchronized(flowLock) {
            val key = Flow(client, target)
            idsByTarget[key]?.let { existing ->
                flowsById[existing]
                return@synchronized FlowAssignment(existing, false)
            }
            var rebind = false
            val conid = if (flowsById.size < MAX_FLOWS_PER_ASSOCIATION) {
                val candidate = findUnusedConid()
                nextConid = (candidate + 1) and 0xffff
                candidate
            } else {
                val eldest = flowsById.entries.iterator().next()
                flowsById.remove(eldest.key)
                idsByTarget.remove(eldest.value)
                rebind = true
                eldest.key
            }
            flowsById[conid] = key
            idsByTarget[key] = conid
            FlowAssignment(conid, rebind)
        }

        fun flowForId(conid: Int): Pair<InetSocketAddress, RemoteTarget>? = synchronized(flowLock) {
            flowsById[conid]?.let { it.client to it.target }
        }

        private fun findUnusedConid(): Int {
            repeat(65_536) {
                val candidate = nextConid and 0xffff
                nextConid = (nextConid + 1) and 0xffff
                if (!flowsById.containsKey(candidate)) return candidate
            }
            return 0
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { control.close() }
            runCatching { udpSocket?.close() }
            runCatching { upstream?.close() }
        }
    }

    private data class ClientReply(val client: InetSocketAddress, val payload: ByteArray)

    private fun parseUdpgwReply(frame: ByteArray, session: GatewaySession): ClientReply? {
        if (frame.size < 3) return null
        val flags = frame[0].toInt() and 0xff
        if (flags and UDPGW_FLAG_KEEPALIVE != 0) return null
        val conid = (frame[1].toInt() and 0xff) or ((frame[2].toInt() and 0xff) shl 8)
        val (client, target) = session.flowForId(conid) ?: return null
        val addressLength = if (flags and UDPGW_FLAG_IPV6 != 0) 16 else 4
        val headerEnd = 3 + addressLength + 2
        if (frame.size < headerEnd) return null
        val replyAddress = InetAddress.getByAddress(frame.copyOfRange(3, 3 + addressLength))
        val replyPort = ((frame[3 + addressLength].toInt() and 0xff) shl 8) or
            (frame[4 + addressLength].toInt() and 0xff)
        if (replyAddress != target.address || replyPort != target.port) return null
        val sourceBytes = target.address.address
        val sourceType = if (target.address is Inet4Address) SOCKS_ATYP_IPV4 else SOCKS_ATYP_IPV6
        val payload = ByteArrayOutputStream(3 + sourceBytes.size + 2 + frame.size - headerEnd).apply {
            write(0)
            write(0)
            write(0)
            write(sourceType)
            write(sourceBytes)
            write(target.port ushr 8)
            write(target.port and 0xff)
            write(frame, headerEnd, frame.size - headerEnd)
        }.toByteArray()
        return ClientReply(client, payload)
    }

    private fun writePacketProtoFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size <= 0xffff) { "UDPGW frame exceeds protocol limit" }
        synchronized(output) {
            // BadVPN PacketProto uses a 16-bit little-endian packet length.
            output.write(payload.size and 0xff)
            output.write((payload.size ushr 8) and 0xff)
            output.write(payload)
            output.flush()
        }
    }

    private fun readPacketProtoFrame(input: InputStream): ByteArray? {
        val low = input.read()
        if (low < 0) return null
        val high = readUnsigned(input)
        val size = low or (high shl 8)
        if (size !in 1..65_535) throw SocketException("Invalid UDPGW packet size")
        return ByteArray(size).also { readFully(input, it) }
    }

    private fun writeSocksReply(output: OutputStream, reply: Int) {
        output.write(byteArrayOf(
            SOCKS_VERSION.toByte(), reply.toByte(), 0, SOCKS_ATYP_IPV4.toByte(),
            127, 0, 0, 1, 0, 0,
        ))
        output.flush()
    }

    private fun readU16(input: InputStream): Int = (readUnsigned(input) shl 8) or readUnsigned(input)

    private fun readUnsigned(input: InputStream): Int = input.read().takeIf { it >= 0 }
        ?: throw EOFException("Unexpected end of SOCKS stream")

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("Unexpected end of SOCKS stream")
            offset += count
        }
    }

    private fun skipFully(input: InputStream, length: Int) {
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong()).toInt()
            if (skipped > 0) {
                remaining -= skipped
            } else {
                readUnsigned(input)
                remaining--
            }
        }
    }

    private fun isCurrent(token: Int): Boolean = generation == token && listener?.isClosed == false

    private fun isCurrent(server: ServerSocket, token: Int): Boolean =
        generation == token && listener === server && !server.isClosed

    private fun recordOncePerGeneration(marker: AtomicInteger, token: Int, message: String) {
        if (marker.getAndSet(token) != token) record(message)
    }

    private fun logGatewayUnavailable(error: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastUnavailableLogMs < 10_000L) return
        lastUnavailableLogMs = now
        record(
            "127.0.0.1:$UDPGW_PORT is unavailable through the selected Psiphon SOCKS tunnel; UDP stays fail-closed",
            error,
        )
    }

    private fun record(message: String, error: Throwable? = null) {
        PingNgDiagnostics.record("Psiphon UDPGW [$profileIdentifier] $message", error)
    }
}
