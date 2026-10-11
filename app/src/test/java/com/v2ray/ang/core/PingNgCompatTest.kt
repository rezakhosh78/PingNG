package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PingNgCompatTest {

    @Test
    fun nativeDesyncSupportsRegularTcpProxyProfilesButNotTunnelProfiles() {
        val vmess = ProfileItem(
            configType = EConfigType.VMESS,
            pingNgProfile = PingNgCompat.PROFILE_LIGHT
        )
        assertTrue(PingNgCompat.isNativeDesyncEnabled(vmess))
        assertNotNull(PingNgCompat.buildCommandLine(vmess, 18190))

        val wireguard = ProfileItem(
            configType = EConfigType.WIREGUARD,
            pingNgProfile = PingNgCompat.PROFILE_LIGHT
        )
        assertFalse(PingNgCompat.isNativeDesyncEnabled(wireguard))
    }

    @Test
    fun balancedProfileBuildsNativeDesyncCommand() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            pingNgProfile = PingNgCompat.PROFILE_BALANCED
        }

        val command = requireNotNull(PingNgCompat.buildCommandLine(profile, 18191))

        assertEquals("ciadpi", command.first())
        assertTrue(command.containsAll(listOf("--disorder", "1", "--tlsrec", "1+s")))
        assertEquals(listOf("--ip", "127.0.0.1", "--port", "18191"), command.takeLast(4))
    }

    @Test
    fun warpMasqueUsesSelectedCustomDesyncArguments() {
        val profile = ProfileItem.create(EConfigType.WARP).apply {
            description = WarpMasqueConfig.DESCRIPTION
            pingNgProfile = PingNgCompat.PROFILE_CUSTOM
            pingNgDesyncArgs = "--proto=tls --split 3 --delay-range 0-1"
        }

        val command = requireNotNull(PingNgCompat.buildCommandLine(profile, 18193))

        assertTrue(command.containsAll(listOf("--proto=tls", "--split", "3")))
        assertFalse(command.contains("--tlsrec"))
        assertEquals(listOf("--ip", "127.0.0.1", "--port", "18193"), command.takeLast(4))
    }

    @Test
    fun customProfilePreservesQuotedArgumentsAndForcesLoopbackListener() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            pingNgProfile = PingNgCompat.PROFILE_CUSTOM
            pingNgDesyncArgs = "--fake -1 --tls-sni 'cover.example' --port 9999"
        }

        val command = requireNotNull(PingNgCompat.buildCommandLine(profile, 18192))

        assertTrue(command.contains("cover.example"))
        assertEquals(listOf("--ip", "127.0.0.1", "--port", "18192"), command.takeLast(4))
    }

    @Test
    fun nativeProxyIsAttachedWithoutChangingTransportIdentity() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            pingNgProfile = PingNgCompat.PROFILE_SEVERE
            server = "origin.example.com"
            host = "edge.example.com"
            path = "/ws"
            sni = "sni.example.com"
        }
        val primary = V2rayConfig.OutboundBean(
            protocol = "vless",
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean()
        )
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(primary),
            routing = V2rayConfig.RoutingBean("AsIs", rules = arrayListOf())
        )

        assertTrue(PingNgCompat.attachNativeProxy(config, profile, 18193))
        assertEquals(PingNgDesyncManager.OUTBOUND_TAG, primary.streamSettings?.sockopt?.dialerProxy)
        assertEquals("sni.example.com", profile.sni)
        assertEquals("edge.example.com", profile.host)
        assertEquals("/ws", profile.path)
        val local = config.outbounds.single { it.tag == PingNgDesyncManager.OUTBOUND_TAG }
        assertEquals("127.0.0.1", local.settings?.address)
        assertEquals(18193, local.settings?.port)
    }

    @Test
    fun smartCustomBuilderUsesOnlyEmbeddedDesyncOptions() {
        val args = PingNgCompat.buildCustomArguments(
            PingNgCompat.CustomOptions(
                method = "Fake",
                position = "-1",
                fakeTtl = "9",
                tlsRecordPosition = "2",
                timeoutSeconds = "4",
                fakeSni = "cover.example",
            )
        )

        assertTrue(args.contains("--fake -1"))
        assertTrue(args.contains("--ttl 9"))
        assertTrue(args.contains("--tls-sni cover.example"))
        assertTrue(args.contains("--tlsrec 2+s"))
        assertFalse(args.contains("--fake-sni"))
        assertFalse(args.contains("--fake-tls-mod"))
    }

    @Test
    fun multipleFakeSnisSurviveBuildAndParseRoundTrip() {
        val args = PingNgCompat.buildCustomArguments(
            PingNgCompat.CustomOptions(
                method = "Fake",
                fakeSni = "one.example, two.example | three.example",
            )
        )

        assertEquals(3, PingNgCompat.shellSplit(args).count { it == "--tls-sni" })
        assertEquals(
            "one.example, two.example, three.example",
            PingNgCompat.parseCustomOptions(args).fakeSni,
        )
    }

    @Test
    fun fakeSniSupportsSniRelativeOffsetWithoutImplicitTlsRecordSplitter() {
        val args = PingNgCompat.buildCustomArguments(
            PingNgCompat.CustomOptions(
                method = PingNgCompat.METHOD_FAKE_SNI,
                position = "1",
                positionSuffix = "+s",
                tlsRecordPosition = "",
                fakeSni = "www.google.com",
            )
        )

        assertTrue(args.contains("--fake 1+s"))
        assertTrue(args.contains("--tls-sni www.google.com"))
        assertFalse(args.contains("--tlsrec"))
        assertEquals("+s", PingNgCompat.parseCustomOptions(args).positionSuffix)
    }

    @Test
    fun fakeSniSeparatorsAreNormalizedToCommaSeparatedValues() {
        assertEquals(
            "one.example, two.example, three.example, four.example",
            PingNgCompat.normalizeFakeSniList(
                "one.example two.example|three.example;four.example"
            ),
        )
        assertEquals(
            "one.example, two.example, ",
            PingNgCompat.normalizeFakeSniList("one.example,two.example "),
        )
        assertEquals(
            "one.example, two.example",
            PingNgCompat.canonicalFakeSniList("one.example two.example "),
        )
    }

    @Test
    fun desyncMethodDisplayNamesKeepNativeFlags() {
        assertTrue(
            PingNgCompat.buildCustomArguments(
                PingNgCompat.CustomOptions(method = PingNgCompat.METHOD_FAKE_SNI)
            ).contains("--fake")
        )
        assertTrue(
            PingNgCompat.buildCustomArguments(
                PingNgCompat.CustomOptions(method = PingNgCompat.METHOD_OUT_OF_BAND)
            ).contains("--oob 1+s")
        )
        assertTrue(
            PingNgCompat.buildCustomArguments(
                PingNgCompat.CustomOptions(method = PingNgCompat.METHOD_DISORDER_OUT_OF_BAND)
            ).contains("--disoob 1+s")
        )
    }

    @Test
    fun regularOobUsesPi13SystemTtlByDefault() {
        val args = PingNgCompat.buildCustomArguments(
            PingNgCompat.CustomOptions(method = PingNgCompat.METHOD_OUT_OF_BAND)
        )

        assertFalse(args.contains("--oob-ttl"))
        assertEquals("0", PingNgCompat.parseCustomOptions(args).oobTtl)
    }

    @Test
    fun disorderOobUsesPi13TtlOneByDefault() {
        val args = PingNgCompat.buildCustomArguments(
            PingNgCompat.CustomOptions(method = PingNgCompat.METHOD_DISORDER_OUT_OF_BAND)
        )

        assertFalse(args.contains("--oob-ttl"))
        assertEquals("1", PingNgCompat.parseCustomOptions(args).oobTtl)
    }

    @Test
    fun disorderOobAcceptsAndPersistsExplicitZero() {
        val args = PingNgCompat.buildCustomArguments(
            PingNgCompat.CustomOptions(
                method = PingNgCompat.METHOD_DISORDER_OUT_OF_BAND,
                oobTtl = "0",
            )
        )

        assertTrue(args.contains("--oob-ttl 0"))
        assertEquals("0", PingNgCompat.parseCustomOptions(args).oobTtl)
    }

    @Test
    fun oobTtlSurvivesBuildAndParseRoundTrip() {
        listOf(
            PingNgCompat.METHOD_OUT_OF_BAND,
            PingNgCompat.METHOD_DISORDER_OUT_OF_BAND,
        ).forEach { method ->
            val args = PingNgCompat.buildCustomArguments(
                PingNgCompat.CustomOptions(method = method, oobTtl = "6")
            )

            assertTrue(args.contains("--oob-ttl 6"))
            assertEquals("6", PingNgCompat.parseCustomOptions(args).oobTtl)
        }
    }

    @Test
    fun regularOobAllowsExplicitTtlOne() {
        val args = PingNgCompat.buildCustomArguments(
            PingNgCompat.CustomOptions(
                method = PingNgCompat.METHOD_OUT_OF_BAND,
                oobTtl = "1",
            )
        )

        assertTrue(args.contains("--oob-ttl 1"))
    }

    @Test
    fun controlledRandomizationAndHiddenNativeOptionsRoundTrip() {
        val args = PingNgCompat.buildCustomArguments(
            PingNgCompat.CustomOptions(
                method = PingNgCompat.METHOD_SPLIT,
                splitRange = "1-3",
                tlsRecordPosition = "1-3",
                delayRange = "1-5",
                automaticFallback = true,
                modifyHttpHeaders = true,
                hosts = "example.com test.example",
                portFilter = "443-8443",
                udpFakeCount = "1",
                tcpFastOpen = true,
            )
        )

        assertTrue(args.contains("--split-range 1-3+s"))
        assertTrue(args.contains("--tlsrec 1-3+s"))
        assertTrue(args.contains("--delay-range 1-5"))
        assertTrue(args.contains("--auto=torst,redirect,ssl_err"))
        assertTrue(args.contains("--mod-http h,d,r"))
        assertTrue(args.contains("--hosts ':example.com test.example'"))
        assertEquals("1-3", PingNgCompat.parseCustomOptions(args).splitRange)
    }

    @Test
    fun existingUserProxyChainIsNotReplaced() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            pingNgProfile = PingNgCompat.PROFILE_SEVERE
        }
        val primary = V2rayConfig.OutboundBean(
            protocol = "vless",
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                sockopt = V2rayConfig.OutboundBean.StreamSettingsBean.SockoptBean(
                    dialerProxy = "user-hop"
                )
            )
        )
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(primary),
            routing = V2rayConfig.RoutingBean("AsIs", rules = arrayListOf())
        )

        assertFalse(PingNgCompat.attachNativeProxy(config, profile, 18194))
        assertEquals("user-hop", primary.streamSettings?.sockopt?.dialerProxy)
    }

    @Test
    fun automaticTunerGeneratesABroadControlledSearchSpace() {
        val candidates = PingNgDesyncTuner.generate()

        assertTrue(candidates.size >= 300)
        assertEquals(candidates.size, candidates.map { it.arguments }.distinct().size)
        assertTrue(candidates.all { it.arguments.contains("--proto=tls") })
        assertTrue(candidates.any { it.arguments.contains("--split") })
        assertTrue(candidates.any { it.arguments.contains("--disorder") })
        assertTrue(candidates.any { it.arguments.contains("--fake") })
    }
}
