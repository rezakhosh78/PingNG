package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.OpenVpnProfileCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Reversible, compressed share links for complete OpenVPN profiles. */
object OpenVpnShareCodec {
    const val PREFIX = "PingNG-OpenVPN://"
    private const val VERSION = 1
    private const val MAX_ENCODED_LENGTH = 500_000
    private const val MAX_DECOMPRESSED_LENGTH = 512 * 1024
    private const val MAX_CONFIG_LENGTH = 256 * 1024
    private const val MAX_REMARK_LENGTH = 8 * 1024
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    data class SharedProfile(val remarks: String, val config: String)

    fun encode(profile: ProfileItem): String {
        require(profile.configType == EConfigType.OPENVPN)
        val config = profile.openVpnConfig.orEmpty()
        require(config.isNotBlank() && OpenVpnProfileCodec.looksLikeOpenVpn(config)) {
            "OpenVPN profile content is missing or invalid"
        }
        val remarks = profile.remarks.ifBlank {
            OpenVpnProfileCodec.detect(config).remote.ifBlank { "OpenVPN" }
        }
        val raw = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(VERSION)
                output.writeString(remarks, MAX_REMARK_LENGTH)
                output.writeString(config, MAX_CONFIG_LENGTH)
            }
            bytes.toByteArray()
        }
        val compressed = ByteArrayOutputStream().use { bytes ->
            DeflaterOutputStream(bytes).use { it.write(raw) }
            bytes.toByteArray()
        }
        val payload = encoder.encodeToString(compressed)
        require(payload.length <= MAX_ENCODED_LENGTH) { "OpenVPN profile is too large to share" }
        // Keep the payload in the URI path, not the hostname. URI handlers and
        // link previews may lowercase hostnames, which would corrupt Base64.
        return PREFIX + "p/" + payload
    }

    fun decode(link: String?): SharedProfile? = runCatching {
        val text = link?.trim().orEmpty()
        if (text.length > MAX_ENCODED_LENGTH + 64) return null
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val suffix = text.substring(PREFIX.length)
        val encoded = when {
            suffix.startsWith("p/", ignoreCase = true) -> suffix.substring(2)
            suffix.startsWith("profile?", ignoreCase = true) -> {
                val query = parseQuery(suffix.substringAfter('?', "")) ?: return null
                if (query["v"] != VERSION.toString()) return null
                query["data"]
            }
            else -> suffix
        }?.takeIf { it.isNotBlank() && it.length <= MAX_ENCODED_LENGTH } ?: return null
        val compressed = decoder.decode(encoded)
        val raw = inflateBounded(compressed) ?: return null
        val input = DataInputStream(ByteArrayInputStream(raw))
        if (input.readInt() != VERSION) return null
        val remarks = input.readString(MAX_REMARK_LENGTH)
        val config = input.readString(MAX_CONFIG_LENGTH)
        if (input.available() != 0 || config.isBlank() || !OpenVpnProfileCodec.looksLikeOpenVpn(config)) return null
        SharedProfile(remarks, config)
    }.getOrNull()

    fun parse(link: String): ProfileItem? {
        val shared = decode(link) ?: return null
        val fields = OpenVpnProfileCodec.detect(shared.config)
        return ProfileItem.create(EConfigType.OPENVPN).copy(
            remarks = shared.remarks.ifBlank { fields.remote.ifBlank { "OpenVPN" } },
            server = fields.remote,
            serverPort = fields.port,
            network = fields.protocol,
            openVpnConfig = shared.config,
        )
    }

    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isBlank()) return null
        val fields = linkedMapOf<String, String>()
        query.split('&').forEach { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@forEach
            val name = part.substring(0, separator)
            if (name in fields) return null
            fields[name] = part.substring(separator + 1)
        }
        return fields
    }

    private fun inflateBounded(compressed: ByteArray): ByteArray? {
        if (compressed.isEmpty() || compressed.size > MAX_ENCODED_LENGTH) return null
        return InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_DECOMPRESSED_LENGTH) return null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun DataOutputStream.writeString(value: String, maxLength: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxLength)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(maxLength: Int): String {
        val length = readInt()
        require(length in 0..maxLength && length <= available())
        return String(ByteArray(length).also { readFully(it) }, Charsets.UTF_8)
    }
}
