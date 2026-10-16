package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpProxySettingsTest {

    @Test
    fun acceptsPsiphonStyleLocalHttpProxy() {
        val proxy = HttpProxySettings.from("127.0.0.1", "8080")

        assertEquals("127.0.0.1", proxy?.host)
        assertEquals(8080, proxy?.port)
    }

    @Test
    fun stripsIpv6BracketsAndKeepsOptionalCredentials() {
        val proxy = HttpProxySettings.from("[::1]", "3128", "user", "pass")

        assertEquals("::1", proxy?.host)
        assertEquals("user", proxy?.username)
        assertEquals("pass", proxy?.password)
        assertEquals("http://user:pass@[::1]:3128", proxy?.asHttpUrl())
    }

    @Test
    fun encodesProxyCredentialsForUpstreamUrl() {
        val proxy = HttpProxySettings.from("proxy.example", "8080", "user@name", "p:a ss")

        assertEquals("http://user%40name:p%3Aa%20ss@proxy.example:8080", proxy?.asHttpUrl())
    }

    @Test
    fun rejectsIncompleteOrUnsafeHostAndPort() {
        assertNull(HttpProxySettings.from("", "8080"))
        assertNull(HttpProxySettings.from("proxy host", "8080"))
        assertNull(HttpProxySettings.from("127.0.0.1", "0"))
        assertNull(HttpProxySettings.from("127.0.0.1", "65536"))
    }
}
