package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsFullConfigCodecTest {

    @Test
    fun masterDnsFullConfigKeepsResolversAndEveryProfileSetting() {
        val profile = ProfileItem.create(EConfigType.SOCKS).copy(
            remarks = "Master test",
            description = MasterDnsBridge.LABEL,
            masterDnsDomain = "dns.example.com",
            masterDnsEncryptionKey = "private-key",
            masterDnsMethod = 3,
            masterDnsAdvanced = "{\"MTU_TEST_PARALLELISM\":\"256\"}",
            masterDnsResolvers = "8.8.8.8:53\n1.1.1.1:53",
            masterDnsReuseValidResolvers = true,
            psiphonEnabled = true,
            psiphonRegion = "NL",
            psiphonMode = "cdn",
            psiphonCdnIps = "192.0.2.8",
        )

        val text = DnsFullConfigCodec.encode(profile)
        val decoded = DnsFullConfigCodec.decode(text, MasterDnsBridge.LABEL)

        assertEquals(profile, decoded)
        assertTrue(text.contains("masterDnsResolvers"))
        assertTrue(text.contains("1.1.1.1:53"))
        assertTrue(DnsFullConfigCodec.suggestedFileName(profile).matches(
            Regex("MasterDNS_dns\\.example\\.com_\\d{4}-\\d{2}-\\d{2}\\.txt")
        ))
    }

    @Test
    fun soarDnsFullConfigDisablesDiscoveryWhenManualResolversArePresent() {
        val profile = ProfileItem.create(EConfigType.SOCKS).copy(
            remarks = "Soar test",
            description = SoarBridge.LABEL,
            soarZone = "t.example.com",
            soarServerKey = "ab".repeat(32),
            soarResolvers = "8.8.8.8:53",
            soarDiscoveryEnabled = true,
            soarDiscoveryCountry = "IR",
            soarTcpMode = "only",
            soarAdvanced = "{\"extraDiscoveryResolvers\":\"9.9.9.9:53\"}",
            psiphonEnabled = true,
            psiphonCdnSni = "cdn.example.com",
        )

        val decoded = DnsFullConfigCodec.decode(DnsFullConfigCodec.encode(profile), SoarBridge.LABEL)
        assertEquals(false, decoded?.soarDiscoveryEnabled)
        assertNull(decoded?.soarDiscoveryCountry)
        assertEquals(profile.soarResolvers, decoded?.soarResolvers)
        assertEquals(profile.soarAdvanced, decoded?.soarAdvanced)
    }

    @Test
    fun malformedAndWrongProtocolProfilesAreRejected() {
        val soar = ProfileItem.create(EConfigType.SOCKS).copy(
            description = SoarBridge.LABEL,
            soarZone = "t.example.com",
            soarServerKey = "ab".repeat(32),
        )

        assertNull(DnsFullConfigCodec.decode("not a profile"))
        assertNull(DnsFullConfigCodec.decode(DnsFullConfigCodec.encode(soar), MasterDnsBridge.LABEL))
    }
}
