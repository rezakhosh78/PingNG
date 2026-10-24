package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.QRCodeDecoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoarShareCodecTest {

    @Test
    fun shareLinkRoundTripsSoarSettingsAndKeepsTheSchemePayloadCaseSafe() {
        val profile = ProfileItem.create(EConfigType.SOCKS).copy(
            remarks = "Soar test profile",
            description = SoarBridge.LABEL,
            server = "127.0.0.1",
            serverPort = SoarBridge.PORT.toString(),
            soarZone = "t.example.com",
            soarServerKey = "ab".repeat(32),
            soarResolvers = "8.8.8.8:53\n[2606:4700:4700::1111]:53",
            soarDiscoveryEnabled = true,
            soarDiscoveryCountry = "IR",
            soarTcpMode = "auto",
            psiphonEnabled = true,
            psiphonRegion = "NL",
            psiphonMode = "cdn",
            psiphonCdnIps = "192.0.2.1",
            psiphonCdnSni = "cdn.example.com",
            psiphonCdnSets = "cloudflare,vercel",
        )

        val link = SoarShareCodec.encode(profile)
        val decoded = SoarShareCodec.decode(link)

        assertTrue(link.startsWith("PingNG-SoarDNS://p/"))
        assertEquals(profile.remarks, decoded?.remarks)
        assertEquals(profile.description, decoded?.description)
        assertEquals(profile.soarZone, decoded?.soarZone)
        assertEquals(profile.soarServerKey, decoded?.soarServerKey)
        assertEquals(profile.soarResolvers, decoded?.soarResolvers)
        assertEquals(false, decoded?.soarDiscoveryEnabled)
        assertNull(decoded?.soarDiscoveryCountry)
        assertEquals(profile.soarTcpMode, decoded?.soarTcpMode)
        assertEquals(profile.psiphonEnabled, decoded?.psiphonEnabled)
        assertEquals(profile.psiphonRegion, decoded?.psiphonRegion)
        assertEquals(profile.psiphonMode, decoded?.psiphonMode)
        assertEquals(profile.psiphonCdnIps, decoded?.psiphonCdnIps)
        assertEquals(profile.psiphonCdnSni, decoded?.psiphonCdnSni)
        assertEquals(profile.psiphonCdnSets, decoded?.psiphonCdnSets)
        assertEquals(SoarBridge.PORT.toString(), decoded?.serverPort)
    }

    @Test
    fun malformedOrUnrelatedSoarLinksAreRejected() {
        assertNull(SoarShareCodec.decode("PingNG-SoarDNS://p/not-base64"))
        assertNull(SoarShareCodec.decode("vmess://example"))
    }

    @Test
    fun discoveryCountryDefaultsToIranAndKeepsAnExplicitUserSelection() {
        val base = ProfileItem.create(EConfigType.SOCKS).copy(
            remarks = "Soar country test",
            description = SoarBridge.LABEL,
            soarZone = "t.example.com",
            soarServerKey = "ab".repeat(32),
            soarDiscoveryEnabled = true,
        )

        val defaultCountry = SoarShareCodec.decode(SoarShareCodec.encode(base))
        val selectedCountry = SoarShareCodec.decode(
            SoarShareCodec.encode(base.copy(soarDiscoveryCountry = "DE")),
        )
        val autoCountry = SoarShareCodec.decode(
            SoarShareCodec.encode(base.copy(soarDiscoveryCountry = "Auto")),
        )

        assertEquals("IR", defaultCountry?.soarDiscoveryCountry)
        assertEquals("DE", selectedCountry?.soarDiscoveryCountry)
        assertEquals("Auto", autoCountry?.soarDiscoveryCountry)
    }

    @Test
    fun oldStoredSoarProfilesRemainRecognizedAfterTheRename() {
        val legacyProfile = ProfileItem.create(EConfigType.SOCKS).apply { description = "Soar" }
        val renamedProfile = ProfileItem.create(EConfigType.SOCKS).apply { description = "SoarDNS" }

        assertTrue(SoarBridge.isProfile(legacyProfile))
        assertTrue(SoarBridge.isProfile(renamedProfile))
    }

    @Test
    fun qrBarcodeRoundTripsSoarDnsProfileAndLegacyShareLink() {
        val profile = ProfileItem.create(EConfigType.SOCKS).copy(
            remarks = "QR SoarDNS",
            description = SoarBridge.LABEL,
            soarZone = "t.example.com",
            soarServerKey = "ab".repeat(32),
            soarResolvers = "8.8.8.8\n1.1.1.1:5353",
        )
        val currentLink = SoarShareCodec.encode(profile)
        val matrix = QRCodeDecoder.createQRCodeMatrix(currentLink, 800, ErrorCorrectionLevel.L)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix.get(index % matrix.width, index / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val reader = MultiFormatReader()
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        val scanned = reader.decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))),
            hints,
        ).text
        val imported = SoarShareCodec.decode(scanned)
        assertEquals(currentLink, scanned)
        assertEquals(profile.soarZone, imported?.soarZone)
        assertEquals("8.8.8.8:53\n1.1.1.1:5353", imported?.soarResolvers)

        val legacyLink = currentLink.replace(SoarShareCodec.PREFIX, SoarShareCodec.LEGACY_PREFIX)
        assertEquals(profile.soarZone, SoarShareCodec.decode(legacyLink)?.soarZone)
    }
}
