package com.v2ray.ang.ui.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.v2ray.ang.AppConfig.DEFAULT_PORT
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_MTU
import com.v2ray.ang.core.AwgWarpConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.util.JsonUtil

class ServerUiState(
    configType: EConfigType,
    remarks: String = "",
    address: String = "",
    port: String = DEFAULT_PORT.toString(),
    pingNgProfile: String = "Off",
    pingNgDesyncArgs: String = "",
    psiphonEnabled: Boolean = false,
    psiphonRegion: String = "ANY",
    psiphonMode: String = "auto",
    psiphonCdnIps: String = "",
    psiphonCdnSni: String = "",
    psiphonCdnSets: String = "",
    password: String = "",
    method: String = "",
    flow: String = "",
    encryption: String = "",
    username: String = "",
    secretKey: String = "",
    amneziawgConfig: String = "",
    autoScanEndpoint: Boolean = AwgWarpConfig.DEFAULT_AUTO_SCAN_ENDPOINT,
    awgEndpointCandidates: String = "",
    awgSkipAutoScanOnce: Boolean = false,
    publicKey: String = "",
    preSharedKey: String = "",
    reserved: String = "0,0,0",
    localAddress: String = WIREGUARD_LOCAL_ADDRESS_V4,
    mtu: String = WIREGUARD_LOCAL_MTU,
    obfsPassword: String = "",
    portHopping: String = "",
    portHoppingInterval: String = "",
    bandwidthDown: String = "",
    bandwidthUp: String = "",
    network: String = NetworkType.TCP.type,
    headerType: String = "none",
    mode: String = "",
    xhttpMode: String = "",
    serviceName: String = "",
    authority: String = "",
    host: String = "",
    path: String = "",
    xhttpExtra: String = "",
    finalMask: String = "",
    seed: String = "",
    kcpMtu: String = "",
    kcpTti: String = "",
    browserDialerMode: String = "",
    streamSecurity: String = "",
    sni: String = "",
    allowInsecure: Boolean = false,
    fingerPrint: String = "",
    alpn: String = "",
    cipherSuites: String = "",
    publicKeyReality: String = "",
    shortId: String = "",
    spiderX: String = "",
    mldsa65Verify: String = "",
    echConfigList: String = "",
    verifyPeerCertByName: String = "",
    pinnedCA256: String = "",
    isFetchingCert: Boolean = false
) {
    var configType by mutableStateOf(configType)
    var remarks by mutableStateOf(remarks)
    var address by mutableStateOf(address)
    var port by mutableStateOf(port)
    var pingNgProfile by mutableStateOf(pingNgProfile)
    var pingNgDesyncArgs by mutableStateOf(pingNgDesyncArgs)
    var psiphonEnabled by mutableStateOf(psiphonEnabled)
    var psiphonRegion by mutableStateOf(psiphonRegion)
    var psiphonMode by mutableStateOf(psiphonMode)
    var psiphonCdnIps by mutableStateOf(psiphonCdnIps)
    var psiphonCdnSni by mutableStateOf(psiphonCdnSni)
    var psiphonCdnSets by mutableStateOf(psiphonCdnSets)
    var password by mutableStateOf(password)
    var method by mutableStateOf(method)
    var flow by mutableStateOf(flow)
    var encryption by mutableStateOf(encryption)
    var username by mutableStateOf(username)
    var secretKey by mutableStateOf(secretKey)
    var amneziawgConfig by mutableStateOf(amneziawgConfig)
    var autoScanEndpoint by mutableStateOf(autoScanEndpoint)
    var awgEndpointCandidates by mutableStateOf(awgEndpointCandidates)
    var awgSkipAutoScanOnce by mutableStateOf(awgSkipAutoScanOnce)
    var publicKey by mutableStateOf(publicKey)
    var preSharedKey by mutableStateOf(preSharedKey)
    var reserved by mutableStateOf(reserved)
    var localAddress by mutableStateOf(localAddress)
    var mtu by mutableStateOf(mtu)
    var obfsPassword by mutableStateOf(obfsPassword)
    var portHopping by mutableStateOf(portHopping)
    var portHoppingInterval by mutableStateOf(portHoppingInterval)
    var bandwidthDown by mutableStateOf(bandwidthDown)
    var bandwidthUp by mutableStateOf(bandwidthUp)
    var network by mutableStateOf(network)
    var headerType by mutableStateOf(headerType)
    var mode by mutableStateOf(mode)
    var xhttpMode by mutableStateOf(xhttpMode)
    var serviceName by mutableStateOf(serviceName)
    var authority by mutableStateOf(authority)
    var host by mutableStateOf(host)
    var path by mutableStateOf(path)
    var xhttpExtra by mutableStateOf(xhttpExtra)
    var finalMask by mutableStateOf(finalMask)
    var seed by mutableStateOf(seed)
    var kcpMtu by mutableStateOf(kcpMtu)
    var kcpTti by mutableStateOf(kcpTti)
    var browserDialerMode by mutableStateOf(browserDialerMode)
    var streamSecurity by mutableStateOf(streamSecurity)
    var sni by mutableStateOf(sni)
    var allowInsecure by mutableStateOf(allowInsecure)
    var fingerPrint by mutableStateOf(fingerPrint)
    var alpn by mutableStateOf(alpn)
    var cipherSuites by mutableStateOf(cipherSuites)
    var publicKeyReality by mutableStateOf(publicKeyReality)
    var shortId by mutableStateOf(shortId)
    var spiderX by mutableStateOf(spiderX)
    var mldsa65Verify by mutableStateOf(mldsa65Verify)
    var echConfigList by mutableStateOf(echConfigList)
    var verifyPeerCertByName by mutableStateOf(verifyPeerCertByName)
    var pinnedCA256 by mutableStateOf(pinnedCA256)
    var isFetchingCert by mutableStateOf(isFetchingCert)

    fun toProfileItem(initialConfig: ProfileItem): ProfileItem {
        val isVmess = configType == EConfigType.VMESS
        val isVless = configType == EConfigType.VLESS
        val isShadowsocks = configType == EConfigType.SHADOWSOCKS
        val isSocksOrHttp = configType == EConfigType.SOCKS || configType == EConfigType.HTTP
        val isWireguard = configType == EConfigType.WIREGUARD
        val isAmneziaWg = configType == EConfigType.AMNEZIAWG
        val isHysteria2 = configType == EConfigType.HYSTERIA2
        val supportsDesync = configType == EConfigType.VMESS ||
            configType == EConfigType.VLESS ||
            configType == EConfigType.SHADOWSOCKS ||
            configType == EConfigType.SOCKS ||
            configType == EConfigType.HTTP ||
            configType == EConfigType.TROJAN

        return initialConfig.copy(
            configType = configType,
            remarks = remarks,
            server = address,
            serverPort = port,
            pingNgProfile = if (supportsDesync) pingNgProfile.nullIfBlank() else null,
            pingNgDesyncArgs = if (supportsDesync) pingNgDesyncArgs.nullIfBlank() else null,
            psiphonEnabled = psiphonEnabled,
            psiphonRegion = psiphonRegion.ifBlank { "ANY" }.uppercase(),
            psiphonMode = psiphonMode.ifBlank { "auto" }.lowercase(),
            psiphonCdnIps = psiphonCdnIps.nullIfBlank(),
            psiphonCdnSni = psiphonCdnSni.nullIfBlank(),
            psiphonCdnSets = psiphonCdnSets.nullIfBlank(),
            password = password,
            method = when {
                isVmess || isShadowsocks -> method
                isVless -> encryption
                else -> null
            },
            flow = if (isVless) flow else null,
            username = if (isSocksOrHttp) username else null,
            secretKey = if (isWireguard || isAmneziaWg) secretKey else null,
            amneziawgConfig = if (isAmneziaWg) amneziawgConfig else null,
            autoScanEndpoint = if (isAmneziaWg && initialConfig.description == AwgWarpConfig.DESCRIPTION) {
                autoScanEndpoint
            } else {
                null
            },
            awgEndpointCandidates = if (isAmneziaWg && initialConfig.description == AwgWarpConfig.DESCRIPTION) {
                awgEndpointCandidates.ifBlank { null }
            } else null,
            awgSkipAutoScanOnce = if (isAmneziaWg && initialConfig.description == AwgWarpConfig.DESCRIPTION) {
                awgSkipAutoScanOnce
            } else null,
            publicKey = when {
                isWireguard || isAmneziaWg -> publicKey
                streamSecurity == REALITY -> publicKeyReality
                else -> null
            },
            preSharedKey = if (isWireguard || isAmneziaWg) preSharedKey else null,
            reserved = if (isWireguard) reserved else null,
            localAddress = if (isWireguard || isAmneziaWg) localAddress else null,
            mtu = if (isWireguard || isAmneziaWg) mtu.toIntOrNull() else null,
            obfsPassword = if (isHysteria2) obfsPassword else null,
            portHopping = if (isHysteria2) portHopping else null,
            portHoppingInterval = if (isHysteria2) portHoppingInterval else null,
            bandwidthDown = if (isHysteria2) bandwidthDown else null,
            bandwidthUp = if (isHysteria2) bandwidthUp else null,
            network = network,
            headerType = headerType,
            mode = mode.nullIfBlank(),
            xhttpMode = xhttpMode.nullIfBlank(),
            serviceName = serviceName.nullIfBlank(),
            authority = authority.nullIfBlank(),
            host = host,
            path = path,
            xhttpExtra = xhttpExtra.nullIfBlank(),
            finalMask = finalMask.nullIfBlank(),
            seed = seed.nullIfBlank(),
            kcpMtu = kcpMtu.toIntOrNull(),
            kcpTti = kcpTti.toIntOrNull(),
            browserDialerMode = if (network in listOf(NetworkType.WS.type, NetworkType.XHTTP.type)) {
                browserDialerMode.nullIfBlank()
            } else {
                null
            },
            security = streamSecurity,
            sni = sni,
            insecure = allowInsecure,
            fingerPrint = fingerPrint,
            alpn = alpn,
            cipherSuites = cipherSuites,
            shortId = shortId,
            spiderX = spiderX,
            mldsa65Verify = mldsa65Verify,
            echConfigList = echConfigList,
            verifyPeerCertByName = verifyPeerCertByName,
            pinnedCA256 = pinnedCA256
        ).also { config ->
            if (isAmneziaWg) {
                runCatching { AmneziaWgFmt.parse(amneziawgConfig, forceAmneziaWg = true) }
                    .getOrNull()?.let { parsed ->
                        config.server = parsed.server
                        config.serverPort = parsed.serverPort
                        config.secretKey = parsed.secretKey
                        config.localAddress = parsed.localAddress
                        config.publicKey = parsed.publicKey
                        config.preSharedKey = parsed.preSharedKey
                        config.mtu = parsed.mtu
                        config.amneziawgConfig = amneziawgConfig
                    }
            }
        }
    }

    companion object {
        fun fromProfileItem(
            initialConfig: ProfileItem
        ): ServerUiState =
            ServerUiState(
                configType = initialConfig.configType,
                remarks = initialConfig.remarks,
                address = initialConfig.server ?: "",
                port = initialConfig.serverPort ?: DEFAULT_PORT.toString(),
                pingNgProfile = initialConfig.pingNgProfile ?: "Off",
                pingNgDesyncArgs = initialConfig.pingNgDesyncArgs ?: "",
                psiphonEnabled = initialConfig.psiphonEnabled,
                psiphonRegion = initialConfig.psiphonRegion.orEmpty().ifBlank { "ANY" },
                psiphonMode = initialConfig.psiphonMode.orEmpty().ifBlank { "auto" },
                psiphonCdnIps = initialConfig.psiphonCdnIps.orEmpty(),
                psiphonCdnSni = initialConfig.psiphonCdnSni.orEmpty(),
                psiphonCdnSets = initialConfig.psiphonCdnSets.orEmpty(),
                password = initialConfig.password ?: "",
                method = initialConfig.method ?: "",
                flow = initialConfig.flow ?: "",
                encryption = initialConfig.method ?: "",
                username = initialConfig.username ?: "",
                secretKey = initialConfig.secretKey ?: "",
                amneziawgConfig = initialConfig.amneziawgConfig ?: "",
                autoScanEndpoint = initialConfig.autoScanEndpoint ?: AwgWarpConfig.DEFAULT_AUTO_SCAN_ENDPOINT,
                awgEndpointCandidates = initialConfig.awgEndpointCandidates.orEmpty(),
                awgSkipAutoScanOnce = initialConfig.awgSkipAutoScanOnce == true,
                publicKey = initialConfig.publicKey ?: "",
                preSharedKey = initialConfig.preSharedKey ?: "",
                reserved = initialConfig.reserved ?: "0,0,0",
                localAddress = initialConfig.localAddress ?: WIREGUARD_LOCAL_ADDRESS_V4,
                mtu = initialConfig.mtu?.toString() ?: WIREGUARD_LOCAL_MTU,
                obfsPassword = initialConfig.obfsPassword ?: "",
                portHopping = initialConfig.portHopping ?: "",
                portHoppingInterval = initialConfig.portHoppingInterval ?: "",
                bandwidthDown = initialConfig.bandwidthDown ?: "",
                bandwidthUp = initialConfig.bandwidthUp ?: "",
                network = initialConfig.network ?: NetworkType.TCP.type,
                headerType = initialConfig.headerType ?: "none",
                mode = initialConfig.mode ?: "",
                xhttpMode = initialConfig.xhttpMode ?: "",
                serviceName = initialConfig.serviceName ?: "",
                authority = initialConfig.authority ?: "",
                host = initialConfig.host ?: "",
                path = initialConfig.path ?: "",
                xhttpExtra = initialConfig.xhttpExtra ?: "",
                finalMask = initialConfig.finalMask ?: "",
                seed = initialConfig.seed ?: "",
                kcpMtu = initialConfig.kcpMtu?.toString() ?: "",
                kcpTti = initialConfig.kcpTti?.toString() ?: "",
                browserDialerMode = initialConfig.browserDialerMode ?: "",
                streamSecurity = initialConfig.security ?: "",
                sni = initialConfig.sni ?: "",
                allowInsecure = initialConfig.insecure == true,
                fingerPrint = initialConfig.fingerPrint ?: "",
                alpn = initialConfig.alpn ?: "",
                cipherSuites = initialConfig.cipherSuites ?: "",
                publicKeyReality = initialConfig.publicKey ?: "",
                shortId = initialConfig.shortId ?: "",
                spiderX = initialConfig.spiderX ?: "",
                mldsa65Verify = initialConfig.mldsa65Verify ?: "",
                echConfigList = initialConfig.echConfigList ?: "",
                verifyPeerCertByName = initialConfig.verifyPeerCertByName ?: "",
                pinnedCA256 = initialConfig.pinnedCA256 ?: ""
            )

        fun from(
            initialConfig: ProfileItem
        ): ServerUiState = fromProfileItem(initialConfig)

        val Saver: Saver<ServerUiState, String> = Saver(
            save = { JsonUtil.toJson(it.toProfileItem(ProfileItem.create(it.configType))) },
            restore = { saved ->
                JsonUtil.fromJsonSafe(saved, ProfileItem::class.java)?.let {
                    fromProfileItem(it)
                }
            }
        )
    }
}
