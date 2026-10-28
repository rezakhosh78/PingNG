package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AmneziaWgFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpProfileKeyProvisionerTest {
    @Test
    fun wireGuardIsProvisionedOnlyWhenAccountCredentialsAreMissing() {
        val missing = ProfileItem.create(EConfigType.WIREGUARD).apply {
            description = WarpWireGuardConfig.DESCRIPTION
        }
        val ready = missing.copy(
            secretKey = "private-key",
            publicKey = "peer-public-key",
            localAddress = "172.16.0.2/32",
        )

        assertTrue(WarpProfileKeyProvisioner.needsProvisioning("warp", missing))
        assertFalse(WarpProfileKeyProvisioner.needsProvisioning("warp", ready))
        assertFalse(WarpProfileKeyProvisioner.needsProvisioning(
            "regular", ProfileItem.create(EConfigType.WIREGUARD),
        ))
    }

    @Test
    fun awgAccountFieldsAreWrittenWithoutChangingItsSelectedEndpointOrObfuscation() {
        val profile = ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            description = AwgWarpConfig.DESCRIPTION
            server = "8.6.112.31"
            serverPort = "500"
            amneziawgConfig = """[Interface]
PrivateKey =
Address =
Jc = 7
Jmin = 12

[Peer]
PublicKey =
Endpoint =
AllowedIPs = 0.0.0.0/0, ::/0"""
        }
        val result = WarpProfileKeyProvisioner.withAccount(profile, account())

        assertEquals("8.6.112.31", result.server)
        assertEquals("500", result.serverPort)
        assertEquals("private-key", AmneziaWgFmt.value(result.amneziawgConfig.orEmpty(), "Interface", "PrivateKey"))
        assertEquals("172.16.0.2/32", AmneziaWgFmt.value(result.amneziawgConfig.orEmpty(), "Interface", "Address"))
        assertEquals("peer-public-key", AmneziaWgFmt.value(result.amneziawgConfig.orEmpty(), "Peer", "PublicKey"))
        assertEquals("8.6.112.31:500", AmneziaWgFmt.value(result.amneziawgConfig.orEmpty(), "Peer", "Endpoint"))
        assertEquals("7", AmneziaWgFmt.value(result.amneziawgConfig.orEmpty(), "Interface", "Jc"))
        assertEquals("12", AmneziaWgFmt.value(result.amneziawgConfig.orEmpty(), "Interface", "Jmin"))
    }

    @Test
    fun existingAwgAccountIsRecognizedFromTheSavedConfig() {
        val profile = ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            description = AwgWarpConfig.DESCRIPTION
            amneziawgConfig = """[Interface]
PrivateKey = private-key
Address = 172.16.0.2/32

[Peer]
PublicKey = peer-public-key
Endpoint = 8.6.112.31:500
AllowedIPs = 0.0.0.0/0, ::/0"""
        }

        assertFalse(WarpProfileKeyProvisioner.needsProvisioning("warp-awg", profile))
    }

    @Test
    fun explicitAwgEndpointTakesPrecedenceOverProfileFallback() {
        val profile = ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            description = AwgWarpConfig.DESCRIPTION
            server = "8.6.112.31"
            serverPort = "500"
            amneziawgConfig = """[Interface]
PrivateKey =
Address =

[Peer]
PublicKey =
Endpoint = 162.159.192.1:2408
AllowedIPs = 0.0.0.0/0, ::/0"""
        }

        val result = WarpProfileKeyProvisioner.withAccount(profile, account())

        assertEquals("162.159.192.1:2408", AmneziaWgFmt.value(result.amneziawgConfig.orEmpty(), "Peer", "Endpoint"))
        assertEquals("162.159.192.1", result.server)
        assertEquals("2408", result.serverPort)
    }

    private fun account() = WarpAccount(
        privateKey = "private-key",
        publicKey = "client-public-key",
        localAddress = "172.16.0.2/32",
        peerPublicKey = "peer-public-key",
        endpoint = "203.0.113.7:2408",
        endpointHost = "203.0.113.7",
        endpointPort = 2408,
        reserved = "1,2,3",
        mtu = 1280,
    )
}
