package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AmneziaWgFmtTest {
    private val config = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.66.66.2/32, fd00::2/128
        DNS = 1.1.1.1
        MTU = 1280
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 15
        S2 = 15
        S3 = 15
        S4 = 15
        H1 = 123456789
        I1 = <b 0xc70000000108><rc 8><t><r 50>
        HeaderProtectionKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        ContentPaddingAddition = 8
        RekeyAfterTime = 180
        RekeyTimeout = 5
        RejectAfterTime = 360
        KeepaliveTimeout = 10
        MaxHandshakeAttempts = 5
        RandomTrailers = true
        DisableCookies = false
        I2 =

        [Peer]
        PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Endpoint = 127.0.0.1:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    private val plainWireguard = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.66.66.2/32
        DNS = 1.1.1.1
        MTU = 1280

        [Peer]
        PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Endpoint = 127.0.0.1:51820
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun detectsAmneziaKeysAndKeepsSeparateProfileType() {
        assertTrue(AmneziaWgFmt.isAmneziaWgConfig(config))
        val profile = AmneziaWgFmt.parse(config)
        assertNotNull(profile)
        assertEquals(EConfigType.AMNEZIAWG, profile?.configType)
        assertEquals(config, profile?.amneziawgConfig)
    }

    @Test
    fun emptyEditorTemplateExposesInterfaceAndPeerSectionsBeforeImport() {
        val editable = AmneziaWgFmt.readEditableConfig(AmneziaWgFmt.EMPTY_EDIT_CONFIG)
        assertNotNull(editable)
        assertTrue(editable?.interfaceValues?.containsKey("privatekey") == true)
        assertEquals(1, editable?.peers?.size)
        assertTrue(editable?.peers?.firstOrNull()?.containsKey("endpoint") == true)
    }

    @Test
    fun removesAmneziaWgBrandFromDefaultRemarks() {
        assertEquals("vpn.example.org", AmneziaWgFmt.cleanRemark("AmneziaWG · vpn.example.org"))
        assertEquals("vpn.example.org", AmneziaWgFmt.cleanRemark("AmneziaWG", "vpn.example.org"))
    }

    @Test
    fun serializesAwgKeysToOfficialUserspaceNames() {
        val uapi = AmneziaWgFmt.toGoUapi(config)
        assertTrue(uapi.contains("jc=4\n"))
        assertTrue(uapi.contains("header_protection_key=" + "00".repeat(32) + "\n"))
        assertTrue(uapi.contains("content_padding_addition=8\n"))
        assertTrue(uapi.contains("rekey_after_time=180\n"))
        assertTrue(uapi.contains("rekey_timeout=5\n"))
        assertTrue(uapi.contains("reject_after_time=360\n"))
        assertTrue(uapi.contains("keepalive_timeout=10\n"))
        assertTrue(uapi.contains("max_handshake_attempts=5\n"))
        assertTrue(uapi.contains("random_trailers=1\n"))
        assertTrue(uapi.contains("disable_cookies=0\n"))
        assertTrue(uapi.contains("i1=<b 0xc70000000108><rc 8><t><r 50>\n"))
        assertTrue(uapi.contains("allowed_ip=0.0.0.0/0\n"))
        assertTrue(uapi.contains("persistent_keepalive_interval=25\n"))
    }

    @Test
    fun protectsTheNativeEngineFromInvalidJunkPacketRanges() {
        val reversedRange = config.replace("Jmin = 40", "Jmin = 70")
            .replace("Jmax = 70", "Jmax = 50")
        assertThrows(IllegalArgumentException::class.java) {
            AmneziaWgFmt.toGoUapi(reversedRange)
        }

        val fixedSizeRange = config.replace("Jmin = 40", "Jmin = 70")
        val uapi = AmneziaWgFmt.toGoUapi(fixedSizeRange)
        assertTrue(uapi.contains("jmin=70\n"))
        assertTrue(uapi.contains("jmax=71\n"))
    }

    @Test
    fun rejectsJunkCountsAndNegativeCustomPacketSizesThatCanCrashGo() {
        val excessiveJunk = config.replace("Jc = 4", "Jc = 129")
        assertThrows(IllegalArgumentException::class.java) {
            AmneziaWgFmt.toGoUapi(excessiveJunk)
        }

        val negativeSignatureSize = config.replace("<r 50>", "<r -5>")
        assertThrows(IllegalArgumentException::class.java) {
            AmneziaWgFmt.toGoUapi(negativeSignatureSize)
        }
    }

    @Test
    fun rejectsUnsupportedCustomPacketCounterTagBeforeNativeStartup() {
        val unsupportedCounterTag = config.replace("<rc 8><t>", "<rc 8><c><t>")
        assertThrows(IllegalArgumentException::class.java) {
            AmneziaWgFmt.toGoUapi(unsupportedCounterTag)
        }
    }

    @Test
    fun rejectsOddLengthStaticBytesTagBeforeNativeStartup() {
        val oddStaticBytesTag = config.replace("0xc70000000108", "0xabc")
        assertThrows(IllegalArgumentException::class.java) {
            AmneziaWgFmt.toGoUapi(oddStaticBytesTag)
        }
    }

    @Test
    fun repairsKnownLegacyQuicStaticBytesExample() {
        val legacyExample = config.replace("0xc70000000108", "0xc7000000010")
        val uapi = AmneziaWgFmt.toGoUapi(legacyExample)
        assertTrue(uapi.contains("i1=<b 0xc70000000108><rc 8><t><r 50>\n"))
    }

    @Test
    fun acceptsOfficialRezaStyleTransportPaddingAndRandomTrailerSettings() {
        val rezaStyle = config
            .replace(
                "S1 = 15\n        S2 = 15\n        S3 = 15\n        S4 = 15",
                "S1 = 123\n        S2 = 89\n        S3 = 35\n        S4 = 22",
            )
            .replace("H1 = 123456789", "H1 = 413209252\n        H2 = 537973910\n        H3 = 1230367076\n        H4 = 2026571936")
            .replace("I1 = <b 0xc70000000108><rc 8><t><r 50>", "I1 = <r 217>")
            .replace("ContentPaddingAddition = 8", "ContentPaddingAddition = 16-48")
            .replace("RekeyAfterTime = 180", "RekeyAfterTime = 103-131")
            .replace("RekeyTimeout = 5", "RekeyTimeout = 6-7")
            .replace("RejectAfterTime = 360", "RejectAfterTime = 190-273")
            .replace("KeepaliveTimeout = 10", "KeepaliveTimeout = 8-12")
            .replace("MaxHandshakeAttempts = 5", "MaxHandshakeAttempts = 18-41")
            .replace("DisableCookies = false", "DisableCookies = on")
        val uapi = AmneziaWgFmt.toGoUapi(rezaStyle)
        assertTrue(uapi.contains("s4=22\n"))
        assertTrue(uapi.contains("i1=<r 217>\n"))
        assertTrue(uapi.contains("random_trailers=1\n"))
        assertTrue(uapi.contains("disable_cookies=1\n"))
        assertTrue(uapi.indexOf("replace_peers=true\n") > uapi.indexOf("disable_cookies=1\n"))
    }

    @Test
    fun leavesPlainWireguardForExistingImporter() {
        assertFalse(AmneziaWgFmt.isAmneziaWgConfig(plainWireguard))
        assertEquals(null, AmneziaWgFmt.parse(plainWireguard))
    }

    @Test
    fun detectsAwgSignatureEvenWhenTheConfigHasMalformedLines() {
        assertTrue(AmneziaWgFmt.isAmneziaWgConfig("""
            # exported from Amnezia
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Jc = 4
            this is not a valid key/value line
        """.trimIndent()))
    }

    @Test
    fun dedicatedAmneziaImportCanForceTheSeparateEngineForPlainWgFormat() {
        val profile = AmneziaWgFmt.parse(plainWireguard, forceAmneziaWg = true)
        assertEquals(EConfigType.AMNEZIAWG, profile?.configType)
        assertTrue(AmneziaWgFmt.toGoUapi(plainWireguard).contains("replace_peers=true\n"))
    }

    @Test
    fun acceptsUtf8BomAndEmptyOptionalAwgFields() {
        val parsed = AmneziaWgFmt.parse("\uFEFF$config")
        assertNotNull(parsed)
        assertTrue(AmneziaWgFmt.toGoUapi(config).contains("i1="))
    }

    @Test
    fun updatesEditableFieldsWithoutDroppingOtherSettings() {
        val source = config.replace("Jc = 4", "Jc = 4 # keep this comment")
        val updated = AmneziaWgFmt.setField(source, "Interface", "Jc", "9")
        assertEquals("9", AmneziaWgFmt.value(updated, "Interface", "Jc"))
        assertEquals("true", AmneziaWgFmt.value(updated, "Interface", "RandomTrailers"))
        assertTrue(updated.contains("Jc = 9 # keep this comment"))
        assertTrue(updated.contains("I1 = <b 0xc70000000108><rc 8><t><r 50>"))

        val withListenPort = AmneziaWgFmt.setField(updated, "Interface", "ListenPort", "51820")
        assertEquals("51820", AmneziaWgFmt.value(withListenPort, "Interface", "ListenPort"))

        val endpointChanged = AmneziaWgFmt.setField(updated, "Peer", "Endpoint", "vpn.example:443")
        assertEquals("vpn.example", AmneziaWgFmt.splitEndpoint(
            AmneziaWgFmt.value(endpointChanged, "Peer", "Endpoint").orEmpty()
        ).first)
        assertNotNull(AmneziaWgFmt.parse(endpointChanged, forceAmneziaWg = true))
    }

    @Test
    fun editsTheRequestedPeerAndPreservesIpv6Endpoints() {
        val twoPeers = "$plainWireguard\n\n[Peer]\nPublicKey = ${"A".repeat(43)}=\nEndpoint = [2001:db8::2]:51821\nAllowedIPs = 10.0.0.0/8"
        val updated = AmneziaWgFmt.setField(twoPeers, "Peer", "Endpoint", "[2001:db8::3]:51822", peerIndex = 1)
        assertEquals("127.0.0.1:51820", AmneziaWgFmt.value(updated, "Peer", "Endpoint", peerIndex = 0))
        assertEquals("[2001:db8::3]:51822", AmneziaWgFmt.value(updated, "Peer", "Endpoint", peerIndex = 1))
        assertEquals("2001:db8::3" to "51822", AmneziaWgFmt.splitEndpoint("[2001:db8::3]:51822"))
    }
}
