package com.v2ray.ang.core
import org.junit.Assert.*
import org.junit.Test

class DnsTunnelArgumentsTest {
    private val key = "ab".repeat(32)
    @Test fun udpAddsDefaultPort() {
        val args = DnsTunnelArguments.dnstt("core", "udp", "8.8.8.8", key, "t.example.com", 18000)
        assertEquals(listOf("core", "-udp", "8.8.8.8:53", "-pubkey", key, "t.example.com", "127.0.0.1:18000"), args)
    }
    @Test fun transportsAndIpv6() {
        assertEquals("https://example.com/dns-query", DnsTunnelArguments.dnstt("core", "doh", "https://example.com/dns-query", key, "t.example.com", 18000)[2])
        assertEquals("[2001:db8::1]:853", DnsTunnelArguments.dnstt("core", "dot", "2001:db8::1", key, "t.example.com", 18000)[2])
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPublicKey() {
        DnsTunnelArguments.dnstt("core", "udp", "8.8.8.8", "invalid", "t.example.com", 18000)
    }
    @Test(expected = IllegalArgumentException::class) fun multipleResolversAreRejected() {
        DnsTunnelArguments.dnstt("core", "udp", "8.8.8.8\n1.1.1.1", key, "t.example.com", 18000)
    }
}
