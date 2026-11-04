package com.v2ray.ang.handler

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

/** Applies the app-wide HTTP CONNECT proxy to direct OkHttp connections. */
object HttpProxyClient {

    fun apply(
        builder: OkHttpClient.Builder,
        proxy: HttpProxySettings? = SettingsManager.getConnectHttpProxy(),
    ): OkHttpClient.Builder {
        if (proxy == null) return builder

        builder.proxy(
            Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(proxy.host, proxy.port),
            ),
        )
        val username = proxy.username?.takeIf { it.isNotBlank() } ?: return builder
        val password = proxy.password.orEmpty()
        builder.proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(username, password))
                    .build()
            }
        }
        return builder
    }
}
