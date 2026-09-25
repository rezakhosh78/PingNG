package com.v2ray.ang.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * WARPSCOUT-compatible WARP Plus discovery pass.
 *
 * The upstream WARPSCOUT scanner treats a completed WireGuard handshake as
 * the only reliable reachability signal, then brings up a real tunnel for the
 * second phase. This Android adapter keeps that two-phase design while using
 * the app's existing native WARP Plus chain for the real tunnel phase.
 *
 * Upstream: https://github.com/vernette/warpscout (MIT, vernette).
 */
object WarpScoutEndpointScanner {
    data class Hit(
        val endpoint: WarpEndpointTester.Endpoint,
        val latencyMs: Long,
        /** Completion order of the discovery pass; lower means found earlier. */
        val discoveryOrder: Long = Long.MAX_VALUE,
    )

    private const val DEFAULT_RATE = 2_000L
    private const val DEFAULT_WORKERS = 256
    private const val DEFAULT_TIMEOUT_MS = 350
    private const val DEFAULT_MAX_HITS_TO_VERIFY = 64

    private val random = SecureRandom()

    data class Identity(
        val privateKey: String,
        val peerPublicKey: String,
    )

    suspend fun scan(
        endpoints: List<WarpEndpointTester.Endpoint>,
        identity: Identity,
        ratePerSecond: Long = DEFAULT_RATE,
        workers: Int = DEFAULT_WORKERS,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxHits: Int = DEFAULT_MAX_HITS_TO_VERIFY,
        stopAfterHits: Int? = null,
        acceptCookieReplies: Boolean = false,
        onProgress: suspend (tested: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Hit> = coroutineScope {
        if (endpoints.isEmpty()) return@coroutineScope emptyList()
        val prepared = WireGuardInitiation.prepare(identity) ?: run {
            PingNgDiagnostics.record("WARP discovery cannot start: invalid WireGuard private/peer key")
            return@coroutineScope emptyList()
        }

        val queue = Channel<WarpEndpointTester.Endpoint>(Channel.UNLIMITED)
        val hits = java.util.Collections.synchronizedList(mutableListOf<Hit>())
        val hitCount = AtomicInteger(0)
        val testedCount = AtomicInteger(0)
        val lastProgressNanos = AtomicLong(0L)
        // maxHits bounds the candidates retained for phase two. The optional
        // stopAfterHits is used only by All's first-usable discovery pass;
        // Fast still scans its randomized pool so it can build both same and
        // different endpoint pairs for the real-tunnel verifier.
        val hitLimit = maxHits.coerceIn(1, 512)
        val stopAt = stopAfterHits?.coerceIn(1, hitLimit)
        val stopRequested = AtomicBoolean(false)
        val limiter = PacketRateLimiter(ratePerSecond)
        val workerCount = workers.coerceIn(1, DEFAULT_WORKERS)
        val startedAt = System.nanoTime()

        val producer = launch(Dispatchers.Default) {
            try {
                for (endpoint in endpoints) {
                    if (stopRequested.get()) break
                    queue.send(endpoint)
                }
            } finally {
                queue.close()
            }
        }

        val jobs = (0 until workerCount).map {
            launch(Dispatchers.IO) {
                for (endpoint in queue) {
                    ensureActive()
                    if (stopRequested.get()) break
                    val hit = probe(endpoint, prepared, limiter, timeoutMs, acceptCookieReplies)
                    ensureActive()
                    val tested = testedCount.incrementAndGet()
                    val now = System.nanoTime()
                    // Progress is user-visible, but dispatching a Compose/Main
                    // update for every UDP packet would throttle the scanner
                    // itself on large Medium/Slow endpoint pools.
                    if (tested == endpoints.size || now - lastProgressNanos.get() >= 100_000_000L) {
                        if (lastProgressNanos.compareAndSet(lastProgressNanos.get(), now)) {
                            onProgress(tested, endpoints.size)
                        }
                    }
                    if (hit != null) {
                        val hitsSeen = hitCount.incrementAndGet()
                        synchronized(hits) {
                            // WARPSCOUT's first usable answers are fed into the
                            // real-tunnel phase. Preserve discovery order;
                            // sorting by raw UDP latency made All pair the
                            // wrong endpoints and inflated Verify work.
                            if (hits.size < hitLimit) {
                                hits.add(hit.copy(discoveryOrder = tested.toLong()))
                            }
                        }
                        if (stopAt != null && hitsSeen >= stopAt) stopRequested.set(true)
                    }
                }
            }
        }

        producer.join()
        jobs.forEach { it.join() }
        val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        PingNgDiagnostics.record(
            "WARP UDP discovery: ${endpoints.size} endpoints, " +
                "${hits.size} hits, ${endpoints.size * 1000L / elapsedMs}/s requested=${ratePerSecond}/s limit=$hitLimit",
            )
        onProgress(testedCount.get(), endpoints.size)
        synchronized(hits) { hits.toList() }
    }

    private suspend fun probe(
        endpoint: WarpEndpointTester.Endpoint,
        prepared: WireGuardInitiation.Prepared,
        limiter: PacketRateLimiter,
        timeoutMs: Int,
        acceptCookieReplies: Boolean,
    ): Hit? = withContext(Dispatchers.IO) {
        limiter.awaitSlot()
        val startedAt = System.nanoTime()
        val response = ByteArray(128)
        try {
            val initiation = WireGuardInitiation.create(prepared) ?: return@withContext null
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.coerceIn(50, 2_000)
                val address = InetSocketAddress(endpoint.host, endpoint.port)
                socket.send(DatagramPacket(initiation.packet, initiation.packet.size, address))
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                if (!packet.isWireGuardResponse(initiation.senderIndex, acceptCookieReplies)) return@withContext null
                Hit(endpoint, ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun DatagramPacket.isWireGuardResponse(senderIndex: Int, acceptCookieReplies: Boolean): Boolean {
        if (length < 8) return false
        val buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.int
        val receiverIndex = buffer.int
        if (receiverIndex != senderIndex) return false
        return when (type) {
            2 -> length >= 92 // Completed handshake response.
            // A valid cookie reply identifies this initiation and can be used
            // as a discovery candidate. The real core verification phase then
            // decides whether the endpoint is usable, including cookie retry.
            3 -> acceptCookieReplies && length == 64
            else -> false
        }
    }

    private class PacketRateLimiter(ratePerSecond: Long) {
        private val intervalNanos = if (ratePerSecond <= 0L) 0L else 1_000_000_000L / ratePerSecond
        private val nextSlot = AtomicLong(System.nanoTime())

        suspend fun awaitSlot() {
            if (intervalNanos <= 0L) return
            while (true) {
                val now = System.nanoTime()
                val current = nextSlot.get()
                val slot = max(now, current)
                if (!nextSlot.compareAndSet(current, slot + intervalNanos)) continue
                val waitNanos = slot - now
                if (waitNanos > 0L) {
                    if (waitNanos >= 1_000_000L) {
                        kotlinx.coroutines.delay(waitNanos / 1_000_000L)
                    } else {
                        Thread.yield()
                    }
                }
                return
            }
        }
    }

    /** Minimal Noise_IKpsk2 WireGuard initiation builder for discovery only. */
    private object WireGuardInitiation {
        private val construction = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray()
        private val identifier = "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray()
        private val labelMac1 = "mac1----".toByteArray()

        data class Prepared(
            val peerPublicKey: ByteArray,
            val staticPublicKey: ByteArray,
            val staticSharedKey: ByteArray,
            val macKey: ByteArray,
        )

        data class Initiation(val packet: ByteArray, val senderIndex: Int)

        fun prepare(identity: Identity): Prepared? = runCatching {
            val privateKey = decode(identity.privateKey) ?: return null
            val peerPublicKey = decode(identity.peerPublicKey) ?: return null
            val staticPublicKey = WarpAccountGenerator.scalarMult(privateKey, basePoint())
            val staticSharedKey = WarpAccountGenerator.scalarMult(privateKey, peerPublicKey)
            val macKey = Blake2s.hash(labelMac1 + peerPublicKey)
            Prepared(peerPublicKey, staticPublicKey, staticSharedKey, macKey)
        }.getOrNull()

        fun create(prepared: Prepared): Initiation? = runCatching {
            val (peerPublicKey, staticPublicKey, staticSharedKey, macKey) = prepared
            val ephemeralPrivate = ByteArray(32).also(random::nextBytes)
            val ephemeralPublic = WarpAccountGenerator.scalarMult(ephemeralPrivate, basePoint())

            var hash = protocolHash()
            var chainKey = hash.copyOf()
            // WireGuard mixes its identifier into the handshake hash before
            // the responder key. Without it, every initiation fails the
            // responder's MAC/decryption check and discovery finds zero hits.
            hash = hash(hash, identifier)
            hash = hash(hash, peerPublicKey)
            hash = hash(hash, ephemeralPublic)
            // Noise MixKey(e): the responder derives the same chaining key
            // before it attempts to decrypt the initiator's static key.
            // Omitting this step makes every discovery packet invalid.
            chainKey = kdf1(chainKey, ephemeralPublic)

            val firstDh = WarpAccountGenerator.scalarMult(ephemeralPrivate, peerPublicKey)
            val firstKdf = kdf2(chainKey, firstDh)
            chainKey = firstKdf.first
            val staticCipher = seal(firstKdf.second, staticPublicKey, hash)
            hash = hash(hash, staticCipher)

            val secondKdf = kdf2(chainKey, staticSharedKey)
            val timestamp = tai64n()
            val timestampCipher = seal(secondKdf.second, timestamp, hash)

            val message = ByteArray(116)
            val buffer = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(1)
            val senderIndex = random.nextInt()
            buffer.putInt(senderIndex)
            buffer.put(ephemeralPublic)
            buffer.put(staticCipher)
            buffer.put(timestampCipher)

            val mac1 = Blake2s.keyed(macKey, message).copyOf(16)
            val packet = ByteArray(148).also { packet ->
                message.copyInto(packet, 0)
                mac1.copyInto(packet, 116)
                // mac2 remains zero until a cookie reply is required.
            }
            Initiation(packet, senderIndex)
        }.getOrNull()

        private fun protocolHash(): ByteArray = construction.copyOf(32).let { value ->
            if (construction.size < 32) value else Blake2s.hash(construction)
        }

        private fun hash(left: ByteArray, right: ByteArray): ByteArray = Blake2s.hash(left + right)

        private fun kdf1(chainKey: ByteArray, input: ByteArray): ByteArray {
            val temporary = Blake2s.hmac(chainKey, input)
            return Blake2s.hmac(temporary, byteArrayOf(1))
        }

        private fun kdf2(chainKey: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
            val temporary = Blake2s.hmac(chainKey, input)
            val first = Blake2s.hmac(chainKey, temporary + byteArrayOf(1))
            val second = Blake2s.hmac(chainKey, first + byteArrayOf(2))
            return first to second
        }

        private fun seal(key: ByteArray, plain: ByteArray, associatedData: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(ByteArray(12)))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(plain)
        }

        private fun tai64n(): ByteArray {
            val now = System.currentTimeMillis()
            val seconds = now / 1000L + 0x4000000000000000L
            val nanos = (now % 1000L) * 1_000_000L
            return ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                .putLong(seconds)
                .putInt(nanos.toInt())
                .array()
        }

        private fun decode(value: String): ByteArray? = runCatching {
            Base64.decode(value, Base64.DEFAULT).takeIf { it.size == 32 }
        }.getOrNull()

        private fun basePoint(): ByteArray = ByteArray(32).also { it[0] = 9 }
    }

    /** Small BLAKE2s implementation used by the WireGuard discovery handshake. */
    private object Blake2s {
        private val iv = intArrayOf(
            0x6A09E667, 0xBB67AE85L.toInt(), 0x3C6EF372, 0xA54FF53AL.toInt(),
            0x510E527F, 0x9B05688CL.toInt(), 0x1F83D9AB, 0x5BE0CD19,
        )
        private val sigma = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )

        fun hash(input: ByteArray): ByteArray = digest(input, null)
        fun keyed(key: ByteArray, input: ByteArray): ByteArray = digest(input, key)

        fun hmac(key: ByteArray, input: ByteArray): ByteArray {
            val block = ByteArray(64)
            key.copyInto(block, 0, 0, minOf(key.size, block.size))
            val inner = ByteArray(64) { block[it].toInt().xor(0x36).toByte() }
            val outer = ByteArray(64) { block[it].toInt().xor(0x5c).toByte() }
            return hash(outer + hash(inner + input))
        }

        private fun digest(input: ByteArray, key: ByteArray?): ByteArray {
            val h = iv.copyOf().also { it[0] = it[0] xor (0x01010020 or ((key?.size ?: 0) shl 8)) }
            val data = if (key != null) ByteArray(64).also { key.copyInto(it) } + input else input
            var offset = 0
            var counter = 0L
            while (offset < data.size || offset == 0) {
                val remaining = data.size - offset
                val length = minOf(64, max(remaining, 0))
                val block = ByteArray(64)
                if (length > 0) data.copyInto(block, 0, offset, offset + length)
                offset += length
                counter += length
                compress(h, block, counter, offset >= data.size)
                if (offset >= data.size) break
            }
            val out = ByteArray(32)
            for (i in 0 until 8) {
                out[i * 4] = h[i].toByte()
                out[i * 4 + 1] = (h[i] ushr 8).toByte()
                out[i * 4 + 2] = (h[i] ushr 16).toByte()
                out[i * 4 + 3] = (h[i] ushr 24).toByte()
            }
            return out
        }

        private fun compress(h: IntArray, block: ByteArray, counter: Long, last: Boolean) {
            val v = IntArray(16)
            h.copyInto(v, 0)
            iv.copyInto(v, 8)
            v[12] = v[12] xor counter.toInt()
            v[13] = v[13] xor (counter ushr 32).toInt()
            if (last) v[14] = v[14].inv()
            val m = IntArray(16)
            val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            for (i in m.indices) m[i] = b.getInt()
            repeat(10) { round ->
                val s = sigma[round]
                g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
                g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
                g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
                g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
                g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
                g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
                g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
                g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
        }

        private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            v[a] += v[b] + x
            v[d] = Integer.rotateRight(v[d] xor v[a], 16)
            v[c] += v[d]
            v[b] = Integer.rotateRight(v[b] xor v[c], 12)
            v[a] += v[b] + y
            v[d] = Integer.rotateRight(v[d] xor v[a], 8)
            v[c] += v[d]
            v[b] = Integer.rotateRight(v[b] xor v[c], 7)
        }
    }
}
