package com.v2ray.ang.core

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Minimal local SOCKS5 CONNECT listener backed by JSch direct-tcpip channels. */
object SshSocksBridge {
    private var listener: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val channels = CopyOnWriteArrayList<ChannelDirectTCPIP>()
    @Volatile private var logFile: File? = null

    private fun log(message: String) {
        logFile?.let { file -> MasterDnsBridge.appendDnsttLog(file, "SSH/SOCKS5: $message") }
    }

    @Synchronized fun start(session: Session, port: Int, log: File) {
        stop()
        logFile = log
        val server = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))
        listener = server
        log("Local listener ready on 127.0.0.1:$port")
        Thread({
            while (!server.isClosed && session.isConnected) {
                val client = try { server.accept() } catch (_: Exception) { break }
                clients.add(client)
                Thread({ serve(session, client) }, "SshSocksClient").apply { isDaemon = true; start() }
            }
        }, "SshSocksListener").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        log("Local listener stopped")
        listener?.close()
        listener = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        channels.forEach { runCatching { it.disconnect() } }
        channels.clear()
        logFile = null
    }

    private fun InputStream.readExact(length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(data, offset, length - offset)
            if (count < 0) error("SOCKS connection closed")
            offset += count
        }
        return data
    }

    private fun serve(session: Session, socket: Socket) {
        var channel: ChannelDirectTCPIP? = null
        var destination = "unknown destination"
        try {
            socket.use { client ->
                client.soTimeout = 10_000
                val input = client.getInputStream()
                val output = client.getOutputStream()
                val hello = input.readExact(2)
                require(hello[0].toInt() == 5 && hello[1].toInt() > 0)
                val methods = input.readExact(hello[1].toInt() and 0xff)
                require(methods.any { it.toInt() == 0 })
                output.write(byteArrayOf(5, 0))
                val request = input.readExact(4)
                require(request[0].toInt() == 5 && request[1].toInt() == 1)
                val host = when (request[3].toInt() and 0xff) {
                    1 -> InetAddress.getByAddress(input.readExact(4)).hostAddress.orEmpty()
                    3 -> String(input.readExact(input.readExact(1)[0].toInt() and 0xff), Charsets.UTF_8)
                    4 -> InetAddress.getByAddress(input.readExact(16)).hostAddress.orEmpty()
                    else -> error("Unsupported SOCKS address type")
                }
                val portBytes = input.readExact(2)
                val remotePort = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
                destination = "$host:$remotePort"
                val tunnel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel = tunnel
                channels.add(tunnel)
                tunnel.setHost(host)
                tunnel.setPort(remotePort)
                tunnel.setOrgIPAddress("127.0.0.1")
                tunnel.setOrgPort(client.port)
                val remoteInput = tunnel.inputStream
                val remoteOutput = tunnel.outputStream
                try {
                    tunnel.connect(10_000)
                } catch (e: Exception) {
                    output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                    throw e
                }
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                log("TCP forwarding established to $host:$remotePort")
                client.soTimeout = 0
                val upstream = Thread({
                    try {
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            remoteOutput.write(buffer, 0, count)
                            // JSch buffers channel output until flush. Flush each
                            // read so small TLS handshakes reach the server before
                            // the browser waits for a response.
                            remoteOutput.flush()
                        }
                    } catch (_: Exception) {}
                    // Closing the channel here also kills its download direction. Close
                    // only the SSH channel output so the server can still send its reply.
                    finally { runCatching { remoteOutput.close() } }
                }, "SshSocksUpload").apply { isDaemon = true; start() }
                try { remoteInput.copyTo(output); output.flush() } catch (_: Exception) {}
                upstream.join(500)
            }
        } catch (e: Exception) {
            log("TCP forwarding failed to $destination: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        } finally {
            channel?.let { channels.remove(it); runCatching { it.disconnect() } }
            clients.remove(socket)
        }
    }
}
