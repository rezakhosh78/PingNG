package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AngApplication
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** One application-owned scan shared by the editor and the connection flow. */
object AwgEndpointScanManager {
    private var active: Deferred<String>? = null
    val isRunning: Boolean get() = active?.isActive == true

    @Synchronized
    fun start(context: Context, guid: String, profile: ProfileItem, skipAutoScanOnce: Boolean): Deferred<String> {
        active?.takeIf { it.isActive }?.let { return it }
        val app = context.applicationContext as AngApplication
        val task = app.applicationScope.async(Dispatchers.IO) {
            try {
                val account = EndpointScannerCli.accountFromConfig(profile.amneziawgConfig.orEmpty(), profile.reserved)
                    ?: error("AWG WARP configuration is incomplete")
                AwgEndpointScanState.begin(guid, "Scanning endpoints…")
                val result = EndpointScannerCli.scan(app, account) { line ->
                    AwgEndpointScanState.update(guid, line)
                }
                val endpoint = AmneziaWgFmt.readConfig(result.config).peers.firstOrNull()?.get("endpoint").orEmpty()
                val (host, port) = AmneziaWgFmt.splitEndpoint(endpoint)
                require(!host.isNullOrBlank() && !port.isNullOrBlank()) { "Endpoint scan returned no endpoint" }
                withContext(Dispatchers.IO) {
                    val current = MmkvManager.decodeServerConfig(guid) ?: profile
                    val updated = current.copy(
                        amneziawgConfig = AmneziaWgFmt.setField(current.amneziawgConfig.orEmpty(), "Peer", "Endpoint", endpoint),
                        server = host,
                        serverPort = port,
                        awgEndpointCandidates = EndpointScannerCli.encodeCandidates(result.candidates),
                        awgSkipAutoScanOnce = skipAutoScanOnce,
                    )
                    MmkvManager.encodeServerConfig(guid, updated)
                }
                AwgEndpointScanState.complete(guid, "Endpoint selected: $endpoint", endpoint)
                endpoint
            } catch (cancelled: CancellationException) {
                AwgEndpointScanState.clear(guid)
                throw cancelled
            } catch (error: Throwable) {
                AwgEndpointScanState.fail(guid, "Endpoint scan failed")
                throw error
            }
        }
        active = task
        return task
    }

    @Synchronized
    fun cancel() {
        active?.cancel()
        active = null
        val guid = AwgEndpointScanState.state.value.guid
        if (guid.isNotBlank()) AwgEndpointScanState.clear(guid)
    }
}
