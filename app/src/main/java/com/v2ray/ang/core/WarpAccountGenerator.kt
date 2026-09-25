package com.v2ray.ang.core

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import java.math.BigInteger
import java.net.Proxy
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A WireGuard profile returned by Cloudflare's free WARP registration API. */
data class WarpAccount(
    val privateKey: String,
    val publicKey: String,
    val localAddress: String,
    val peerPublicKey: String,
    val endpoint: String,
    val endpointHost: String,
    val endpointPort: Int,
    val reserved: String,
    val mtu: Int = 1280,
)

/**
 * Registers a fresh, unauthenticated WARP device.  The API is the same public
 * registration endpoint used by wgcf; no Cloudflare account is required.
 * X25519 is kept here as a small BigInteger implementation so the Android app
 * does not need a crypto provider that is unavailable on older API levels.
 */
object WarpAccountGenerator {
    private const val API = "https://api.cloudflareclient.com/v0a1922/reg"
    private const val WARP_MTU = 1280
    private val random = SecureRandom()
    private val prime = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val a24 = BigInteger.valueOf(121665)

    fun register(proxy: Proxy? = null): WarpAccount {
        val privateBytes = ByteArray(32).also(random::nextBytes)
        val publicBytes = scalarMult(privateBytes, ByteArray(32).also { it[0] = 9 })
        val privateKey = encode(privateBytes)
        val publicKey = encode(publicBytes)

        val requestJson = JsonObject().apply {
            addProperty("key", publicKey)
            addProperty("install_id", "")
            addProperty("fcm_token", "")
            addProperty("tos", timestamp())
            addProperty("model", "PingNG Android")
            addProperty("locale", "en_US")
            addProperty("type", "Android")
        }.toString()

        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectionSpecs(listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build()
            ))
            .build()
        val body = requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(API)
            .header("User-Agent", "okhttp/3.12.1")
            .header("CF-Client-Version", "a-6.3-1922")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("WARP registration failed (${response.code}): ${text.take(180)}")
            }
            return parseResponse(privateKey, publicKey, text)
        }
    }

    private fun parseResponse(privateKey: String, publicKey: String, raw: String): WarpAccount {
        val root = JsonParser.parseString(raw).asJsonObject
        val config = root.obj("config").takeIf { it.entrySet().isNotEmpty() }
            ?: throw IllegalStateException("WARP registration returned no config")
        val iface = config.obj("interface")
        val addresses = iface.obj("addresses")
        val v4 = addresses.string("v4").removeSuffix("/32")
        if (v4.isBlank()) throw IllegalStateException("WARP registration returned no IPv4 address")
        val v6 = addresses.string("v6").removeSuffix("/128")
        val localAddress = buildString {
            append(v4).append("/32")
            if (v6.isNotBlank()) append(",").append(v6).append("/128")
        }

        val peer = config.arr("peers").firstOrNull()?.asJsonObject
            ?: throw IllegalStateException("WARP registration returned no peer")
        val peerPublicKey = peer.string("public_key")
        if (peerPublicKey.isBlank()) throw IllegalStateException("WARP registration returned no peer key")

        val endpointValue = peer.get("endpoint")
        val endpointText = when {
            endpointValue?.isJsonPrimitive == true -> endpointValue.asString
            endpointValue?.isJsonObject == true -> {
                val endpointObject = endpointValue.asJsonObject
                endpointObject.string("v4").ifBlank { endpointObject.string("v6") }
            }
            else -> ""
        }.trim()
        if (endpointText.isBlank()) throw IllegalStateException("WARP registration returned no endpoint")
        val endpointParts = splitEndpoint(endpointText)
        val endpointPort = peer.int("port")?.takeIf { it in 1..65535 }
            ?: endpointParts.second.takeIf { it in 1..65535 }
            ?: 2408
        val endpointHost = endpointParts.first
            .removePrefix("[")
            .removeSuffix("]")
            .trim()
        if (endpointHost.isBlank()) throw IllegalStateException("WARP registration returned an invalid endpoint")
        val endpoint = "$endpointHost:$endpointPort"

        val clientId = config.string("client_id").ifBlank { root.string("client_id") }
        val reserved = reservedBytes(clientId)
        return WarpAccount(
            privateKey = privateKey,
            publicKey = publicKey,
            localAddress = localAddress,
            peerPublicKey = peerPublicKey,
            endpoint = endpoint,
            endpointHost = endpointHost,
            endpointPort = endpointPort,
            reserved = reserved.joinToString(","),
            mtu = iface.string("mtu").toIntOrNull() ?: WARP_MTU,
        )
    }

    private fun JsonObject.obj(name: String): JsonObject =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

    private fun JsonObject.arr(name: String) =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: com.google.gson.JsonArray()

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asInt

    private fun splitEndpoint(value: String): Pair<String, Int?> {
        val text = value.trim()
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close > 0) {
                val host = text.substring(0, close + 1)
                val port = text.substring(close + 1).removePrefix(":").toIntOrNull()
                return host to port
            }
        }
        val separator = text.lastIndexOf(':')
        if (separator > 0 && text.indexOf(':') == separator) {
            return text.substring(0, separator) to text.substring(separator + 1).toIntOrNull()
        }
        return text to null
    }

    private fun reservedBytes(clientId: String): List<Int> {
        val decoded = sequenceOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        ).mapNotNull { flags ->
            runCatching { Base64.decode(clientId, flags) }.getOrNull()
        }.firstOrNull { it.size >= 3 }
        if (decoded != null) return decoded.take(3).map { it.toInt() and 0xff }
        val hex = clientId.replace("-", "")
        if (hex.length >= 6 && hex.take(6).all { it in "0123456789abcdefABCDEF" }) {
            return listOf(0, 2, 4).map { hex.substring(it, it + 2).toInt(16) }
        }
        throw IllegalStateException("WARP registration returned invalid client_id")
    }

    private fun timestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    internal fun scalarMult(scalar: ByteArray, uBytes: ByteArray): ByteArray {
        val k = scalar.copyOf().also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = (it[31].toInt() and 127 or 64).toByte()
        }
        val x1 = fromLittleEndian(uBytes)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = (k[t shr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt
            val a = mod(x2 + z2)
            val aa = mod(a * a)
            val b = mod(x2 - z2)
            val bb = mod(b * b)
            val e = mod(aa - bb)
            val c = mod(x3 + z3)
            val d = mod(x3 - z3)
            val da = mod(d * a)
            val cb = mod(c * b)
            x3 = mod((da + cb) * (da + cb))
            z3 = mod(x1 * (da - cb) * (da - cb))
            x2 = mod(aa * bb)
            z2 = mod(e * (aa + a24 * e))
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return toLittleEndian(mod(x2 * z2.modInverse(prime)))
    }

    private fun mod(value: BigInteger): BigInteger = value.mod(prime)

    private fun fromLittleEndian(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray())

    private fun toLittleEndian(value: BigInteger): ByteArray {
        val source = value.toByteArray()
        val result = ByteArray(32)
        for (i in result.indices) {
            val sourceIndex = source.size - 1 - i
            if (sourceIndex >= 0) result[i] = source[sourceIndex]
        }
        return result
    }
}
