package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.handler.MmkvManager

/** Creates WARP account keys on first connect, only for profiles that do not have them yet. */
object WarpProfileKeyProvisioner {
    fun supports(profile: ProfileItem?): Boolean =
        AwgWarpConfig.isProfile(profile) ||
            WarpWireGuardConfig.isProfile(profile) ||
            profile?.let { WarpPlusConfig.isDescription(it.description) } == true

    fun needsProvisioning(guid: String, profile: ProfileItem?): Boolean {
        if (!supports(profile) || profile == null) return false
        return when {
            AwgWarpConfig.isProfile(profile) -> !hasAwgCredentials(profile)
            WarpWireGuardConfig.isProfile(profile) -> !hasWireGuardCredentials(profile)
            WarpPlusConfig.isDescription(profile.description) -> {
                val children = resolveWarpPlusChildren(guid, profile) ?: return true
                children.any { (_, child) -> !hasWireGuardCredentials(child) }
            }
            else -> false
        }
    }

    /** Saves any missing account keys in the same profile GUIDs before the connection starts. */
    suspend fun provisionIfNeeded(context: Context, guid: String): Boolean {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        when {
            AwgWarpConfig.isProfile(profile) -> {
                if (hasAwgCredentials(profile)) return false
                val account = register(context, profile.warpRegistrationProxyGuid, guid)
                MmkvManager.encodeServerConfig(guid, withAccount(profile, account).copy(
                    awgEndpointCandidates = null,
                    awgSkipAutoScanOnce = false,
                ))
                return true
            }

            WarpWireGuardConfig.isProfile(profile) -> {
                if (hasWireGuardCredentials(profile)) return false
                val account = register(context, profile.warpRegistrationProxyGuid, guid)
                MmkvManager.encodeServerConfig(guid, withAccount(profile, account).copy(
                    awgEndpointCandidates = null,
                    warpEndpointCandidates = null,
                    warpWireGuardSelectedEndpoint = null,
                ))
                return true
            }

            WarpPlusConfig.isDescription(profile.description) -> {
                val children = resolveWarpPlusChildren(guid, profile)
                    ?: throw IllegalStateException("WARP Plus child profiles were not found")
                val proxyGuid = profile.warpRegistrationProxyGuid ?: WarpRegistrationProxy.AUTO
                var changed = false
                if (children.any { (_, child) -> !hasWireGuardCredentials(child) }) {
                    MmkvManager.encodeServerConfig(guid, profile.copy(
                        awgEndpointCandidates = null,
                        warpSelectedEndpoint = null,
                    ))
                }
                children.forEach { (childGuid, _) ->
                    val current = MmkvManager.decodeServerConfig(childGuid)
                        ?: throw IllegalStateException("WARP Plus child profile was not found")
                    if (!hasWireGuardCredentials(current)) {
                        val otherKeys = children.asSequence()
                            .filter { it.first != childGuid }
                            .mapNotNull { MmkvManager.decodeServerConfig(it.first)?.secretKey?.takeIf(String::isNotBlank) }
                            .toSet()
                        var attempts = 0
                        var account: WarpAccount
                        do {
                            account = register(context, proxyGuid, guid)
                            attempts++
                        } while (account.privateKey in otherKeys && attempts < 3)
                        check(account.privateKey !in otherKeys) {
                            "WARP Plus registration returned a key already used by its other hop"
                        }
                        MmkvManager.encodeServerConfig(childGuid, withAccount(current, account).copy(
                            awgEndpointCandidates = null,
                            warpEndpointCandidates = null,
                            warpWireGuardSelectedEndpoint = null,
                        ))
                        changed = true
                    }
                }
                val refreshed = children.mapNotNull { (childGuid, _) ->
                    MmkvManager.decodeServerConfig(childGuid)
                }
                if (refreshed.size == 2) {
                    check(refreshed[0].secretKey != refreshed[1].secretKey) {
                        "WARP Plus requires separate keys for its Inner and Outer profiles"
                    }
                }
                return changed
            }

            else -> return false
        }
    }

    internal fun hasWireGuardCredentials(profile: ProfileItem): Boolean =
        !profile.secretKey.isNullOrBlank() &&
            !profile.publicKey.isNullOrBlank() &&
            !profile.localAddress.isNullOrBlank()

    internal fun hasAwgCredentials(profile: ProfileItem): Boolean {
        val config = AmneziaWgFmt.readEditableConfig(profile.amneziawgConfig.orEmpty()) ?: return false
        return !config.interfaceValues["privatekey"].isNullOrBlank() &&
            !config.interfaceValues["address"].isNullOrBlank() &&
            !config.peers.firstOrNull()?.get("publickey").isNullOrBlank()
    }

    internal fun withAccount(profile: ProfileItem, account: WarpAccount): ProfileItem {
        if (AwgWarpConfig.isProfile(profile)) {
            val source = profile.amneziawgConfig?.takeIf(String::isNotBlank)
                ?: AmneziaWgFmt.EMPTY_EDIT_CONFIG
            val existingAwgEndpoint = AmneziaWgFmt.value(source, "Peer", "Endpoint")
            val endpoint = validEndpoint(existingAwgEndpoint)
                ?: validEndpoint("${profile.server.orEmpty()}:${profile.serverPort.orEmpty()}")
                ?: validEndpoint(account.endpoint)
                ?: error("WARP registration returned an invalid endpoint")
            val validHost = endpoint.first
            val validPort = endpoint.second
            val config = runCatching {
                source
                    .let { AmneziaWgFmt.setField(it, "Interface", "PrivateKey", account.privateKey) }
                    .let { AmneziaWgFmt.setField(it, "Interface", "Address", account.localAddress) }
                    .let { AmneziaWgFmt.setField(it, "Interface", "MTU", account.mtu.toString()) }
                    .let { AmneziaWgFmt.setField(it, "Peer", "PublicKey", account.peerPublicKey) }
                    .let { AmneziaWgFmt.setField(it, "Peer", "Endpoint", "${formatHost(validHost)}:$validPort") }
            }.getOrElse { AwgWarpConfig.render(account, "${formatHost(validHost)}:$validPort") }
            return profile.copy(
                server = validHost,
                serverPort = validPort,
                secretKey = account.privateKey,
                publicKey = account.peerPublicKey,
                localAddress = account.localAddress,
                reserved = account.reserved,
                mtu = account.mtu,
                amneziawgConfig = config,
            )
        }

        val endpoint = validEndpoint("${profile.server.orEmpty()}:${profile.serverPort.orEmpty()}")
            ?: validEndpoint(account.endpoint)
            ?: error("WARP registration returned an invalid endpoint")
        val validHost = endpoint.first
        val validPort = endpoint.second
        return profile.copy(
            server = validHost,
            serverPort = validPort,
            secretKey = account.privateKey,
            publicKey = account.peerPublicKey,
            localAddress = account.localAddress,
            reserved = account.reserved,
            mtu = account.mtu,
        )
    }

    private suspend fun register(context: Context, proxyGuid: String?, excludeGuid: String): WarpAccount =
        WarpRegistrationProxy.register(
            context = context,
            proxyGuid = proxyGuid?.takeIf(String::isNotBlank) ?: WarpRegistrationProxy.AUTO,
            excludeGuid = excludeGuid,
        )

    private fun resolveWarpPlusChildren(
        parentGuid: String,
        parent: ProfileItem,
    ): List<Pair<String, ProfileItem>>? {
        val remarks = parent.proxyChainProfiles.orEmpty().split(',')
            .map(String::trim).filter(String::isNotBlank)
        if (remarks.size < 2) return null

        val profiles = MmkvManager.decodeAllServerList().mapNotNull { childGuid ->
            MmkvManager.decodeServerConfig(childGuid)?.let { childGuid to it }
        }
        val selected = remarks.take(2).map { remark ->
            profiles.firstOrNull { (_, item) ->
                item.configType == EConfigType.WIREGUARD && item.managedBy == parentGuid && item.remarks == remark
            }
                ?: profiles.firstOrNull { (_, item) ->
                    item.configType == EConfigType.WIREGUARD && item.remarks == remark
                }
                ?: return null
        }
        return selected.takeIf { children -> children.map { it.first }.distinct().size == 2 }
    }

    private fun validEndpoint(raw: String): Pair<String, String>? {
        val (host, port) = AmneziaWgFmt.splitEndpoint(raw)
        if (host.isNullOrBlank() || port?.toIntOrNull()?.let { it in 1..65535 } != true) return null
        return host to port
    }

    private fun formatHost(host: String): String =
        if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
}
