package com.v2ray.ang.core

import android.util.Base64
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * Compact, non-JSON share format for the two WARP profile types.
 *
 * Values are URL-safe base64 fields inside a PingNG-owned URI.  This keeps
 * credentials out of a readable JSON document while retaining every field
 * needed to recreate a WARP or WARP Plus profile on another device.
 */
object WarpShareCodec {
    private const val PREFIX = "pingng-warp://profile"
    private const val VERSION = "1"

    sealed class Decoded {
        data class Masque(val profile: ProfileItem) : Decoded()
        data class WireGuard(val profile: ProfileItem) : Decoded()
        data class Plus(
            val parent: ProfileItem,
            val inner: ProfileItem,
            val outer: ProfileItem,
        ) : Decoded()
    }

    fun encodeMasque(profile: ProfileItem): String = buildUri(
        "m" to fields(
            "r" to profile.remarks,
            "ec" to profile.warpMasqueEndpointCandidates,
            "pe" to profile.warpMasquePrimaryEndpoint,
            "pp" to profile.warpMasqueEndpointPort,
            "mode" to profile.warpMasqueEndpointMode,
            "dn" to profile.warpMasqueDeviceName,
            "sni" to profile.warpMasqueSni,
            "dns" to profile.warpMasqueDns,
            "h2" to profile.warpMasqueHttp2Enabled,
            "sb" to profile.warpMasqueSocksBind,
            "sp" to profile.warpMasqueSocksPort,
            "cfg" to profile.warpMasqueConfigJson,
            "sel" to profile.warpMasqueSelectedEndpoint,
            "ps" to profile.psiphonEnabled,
            "pr" to profile.psiphonRegion,
            "pm" to profile.psiphonMode,
            "ci" to profile.psiphonCdnIps,
            "cs" to profile.psiphonCdnSni,
            "cl" to profile.psiphonCdnSets,
            "ppro" to profile.pingNgProfile,
            "da" to profile.pingNgDesyncArgs,
        ),
    )

    fun encodeWireGuard(profile: ProfileItem): String = buildUri(
        "w" to fields(
            "r" to profile.remarks,
            "s" to profile.server,
            "p" to profile.serverPort,
            "sk" to profile.secretKey,
            "pk" to profile.publicKey,
            "psk" to profile.preSharedKey,
            "a" to profile.localAddress,
            "res" to profile.reserved,
            "mtu" to profile.mtu,
            "ka" to profile.warpKeepAlive,
            "ec" to profile.warpEndpointCandidates,
            "mode" to profile.warpEndpointTestMode,
            "fm" to profile.finalMask,
            "sel" to profile.warpWireGuardSelectedEndpoint,
            "ps" to profile.psiphonEnabled,
            "pr" to profile.psiphonRegion,
            "pm" to profile.psiphonMode,
            "ci" to profile.psiphonCdnIps,
            "cs" to profile.psiphonCdnSni,
            "cl" to profile.psiphonCdnSets,
            "ppro" to profile.pingNgProfile,
            "da" to profile.pingNgDesyncArgs,
        ),
    )

    fun encodePlus(parent: ProfileItem, inner: ProfileItem, outer: ProfileItem): String {
        val values = linkedMapOf<String, Any?>(
            "r" to parent.remarks,
            "mode" to parent.warpEndpointTestMode,
            "fe" to parent.warpFinalMaskEnabled,
            "fm" to parent.warpFastFinalMask,
            "fa" to parent.warpAllFinalMask,
            "ic" to parent.warpInnerEndpointCandidates,
            "oc" to parent.warpOuterEndpointCandidates,
            "sel" to parent.warpSelectedEndpoint,
            "ps" to parent.psiphonEnabled,
            "pr" to parent.psiphonRegion,
            "pm" to parent.psiphonMode,
            "ci" to parent.psiphonCdnIps,
            "cs" to parent.psiphonCdnSni,
            "cl" to parent.psiphonCdnSets,
            "ppro" to parent.pingNgProfile,
            "da" to parent.pingNgDesyncArgs,
        )
        addLayer(values, "i", inner)
        addLayer(values, "o", outer)
        return buildUri("p" to values)
    }

    fun decode(raw: String?): Decoded? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val query = text.substringAfter('?', "")
        if (query.isBlank()) return null
        val fields = query.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val key = part.substring(0, index)
            val rawValue = part.substring(index + 1)
            key to if (key == "v" || key == "kind") rawValue else decodeValue(rawValue)
        }.toMap()
        if (fields["v"] != VERSION) return null
        return when (fields["kind"]) {
            "m" -> decodeMasque(fields)
            "w" -> decodeWireGuard(fields)
            "p" -> decodePlus(fields)
            else -> null
        }
    }

    private fun decodeMasque(values: Map<String, String>): Decoded.Masque? {
        val profile = ProfileItem.create(EConfigType.WARP).apply {
            remarks = values["r"].orEmpty().ifBlank { "WARP" }
            description = WarpMasqueConfig.DESCRIPTION
            warpMasqueEndpointCandidates = values["ec"]
            warpMasquePrimaryEndpoint = values["pe"]
            warpMasqueEndpointPort = values["pp"]?.toIntOrNull()
            warpMasqueEndpointMode = values["mode"]
            warpMasqueDeviceName = values["dn"]
            warpMasqueSni = values["sni"]
            warpMasqueDns = values["dns"]
            warpMasqueHttp2Enabled = values["h2"].toBooleanOrNull()
            warpMasqueSocksBind = values["sb"]
            warpMasqueSocksPort = values["sp"]?.toIntOrNull()
            warpMasqueConfigJson = values["cfg"]
            warpMasqueSelectedEndpoint = values["sel"]
            psiphonEnabled = values["ps"].toBoolean()
            psiphonRegion = values["pr"]
            psiphonMode = values["pm"].orEmpty().ifBlank { "auto" }
            psiphonCdnIps = values["ci"]
            psiphonCdnSni = values["cs"]
            psiphonCdnSets = values["cl"]
            pingNgProfile = values["ppro"]
            pingNgDesyncArgs = values["da"]
        }
        return Decoded.Masque(profile)
    }

    private fun decodePlus(values: Map<String, String>): Decoded.Plus? {
        val inner = decodeLayer(values, "i", false) ?: return null
        val outer = decodeLayer(values, "o", true) ?: return null
        val parent = ProfileItem.create(EConfigType.PROXYCHAIN).apply {
            remarks = values["r"].orEmpty().ifBlank { "WARP Plus" }
            description = WarpPlusConfig.DESCRIPTION
            warpEndpointTestMode = WarpPlusConfig.normalizeEndpointTestMode(values["mode"])
            warpFinalMaskEnabled = values["fe"].toBooleanOrNull() ?: true
            warpFastFinalMask = values["fm"]
            warpAllFinalMask = values["fa"]
            warpInnerEndpointCandidates = values["ic"]
            warpOuterEndpointCandidates = values["oc"]
            warpSelectedEndpoint = values["sel"]
            warpEndpointTestEnabled = true
            warpSkipEndpointTestOnce = false
            psiphonEnabled = values["ps"].toBoolean()
            psiphonRegion = values["pr"]
            psiphonMode = values["pm"].orEmpty().ifBlank { "auto" }
            psiphonCdnIps = values["ci"]
            psiphonCdnSni = values["cs"]
            psiphonCdnSets = values["cl"]
            pingNgProfile = values["ppro"]
            pingNgDesyncArgs = values["da"]
        }
        return Decoded.Plus(parent, inner, outer)
    }

    private fun decodeWireGuard(values: Map<String, String>): Decoded.WireGuard? {
        val server = values["s"].orEmpty()
        val port = values["p"].orEmpty()
        if (server.isBlank() || port.toIntOrNull() == null) return null
        val profile = ProfileItem.create(EConfigType.WIREGUARD).apply {
            remarks = values["r"].orEmpty().ifBlank { "WARP WireGuard" }
            description = WarpWireGuardConfig.DESCRIPTION
            this.server = server
            serverPort = port
            secretKey = values["sk"]
            publicKey = values["pk"]
            preSharedKey = values["psk"]
            localAddress = values["a"]
            reserved = values["res"]
            mtu = values["mtu"]?.toIntOrNull()
            warpKeepAlive = values["ka"]?.toIntOrNull()
            warpEndpointCandidates = values["ec"]
            warpEndpointTestMode = WarpWireGuardConfig.normalizeMode(values["mode"])
            finalMask = values["fm"]
            warpWireGuardSelectedEndpoint = values["sel"]
            psiphonEnabled = values["ps"].toBoolean()
            psiphonRegion = values["pr"]
            psiphonMode = values["pm"].orEmpty().ifBlank { "auto" }
            psiphonCdnIps = values["ci"]
            psiphonCdnSni = values["cs"]
            psiphonCdnSets = values["cl"]
            pingNgProfile = values["ppro"]
            pingNgDesyncArgs = values["da"]
        }
        return Decoded.WireGuard(profile)
    }

    private fun addLayer(values: MutableMap<String, Any?>, prefix: String, profile: ProfileItem) {
        values["${prefix}s"] = profile.server
        values["${prefix}p"] = profile.serverPort
        values["${prefix}sk"] = profile.secretKey
        values["${prefix}pk"] = profile.publicKey
        values["${prefix}psk"] = profile.preSharedKey
        values["${prefix}a"] = profile.localAddress
        values["${prefix}res"] = profile.reserved
        values["${prefix}mtu"] = profile.mtu
        values["${prefix}ka"] = profile.warpKeepAlive
        values["${prefix}ec"] = profile.warpEndpointCandidates
        values["${prefix}fm"] = profile.finalMask
    }

    private fun decodeLayer(values: Map<String, String>, prefix: String, outer: Boolean): ProfileItem? {
        val server = values["${prefix}s"].orEmpty()
        val port = values["${prefix}p"].orEmpty()
        val profile = ProfileItem.create(EConfigType.WIREGUARD).apply {
            description = if (outer) "WARP Plus outer" else "WARP Plus inner"
            this.server = server
            serverPort = port
            secretKey = values["${prefix}sk"]
            publicKey = values["${prefix}pk"]
            preSharedKey = values["${prefix}psk"]
            localAddress = values["${prefix}a"]
            reserved = values["${prefix}res"]
            mtu = values["${prefix}mtu"]?.toIntOrNull()
            warpKeepAlive = values["${prefix}ka"]?.toIntOrNull()
            warpEndpointCandidates = values["${prefix}ec"]
            finalMask = values["${prefix}fm"]
        }
        return profile.takeIf { server.isNotBlank() && port.toIntOrNull() != null }
    }

    private fun fields(vararg entries: Pair<String, Any?>): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>().apply { entries.forEach { (key, value) -> put(key, value) } }

    private fun buildUri(kind: Pair<String, Map<String, Any?>>): String {
        val values = kind.second.entries.joinToString("&") { (key, value) ->
            "${key}=${encodeValue(value?.toString().orEmpty())}"
        }
        return "$PREFIX?v=$VERSION&kind=${kind.first}&$values"
    }

    private fun encodeValue(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    private fun decodeValue(value: String): String = runCatching {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrDefault("")

    private fun String?.toBooleanOrNull(): Boolean? = when (this?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
