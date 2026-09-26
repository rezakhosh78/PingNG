package com.v2ray.ang.core

import android.content.Context
import android.text.TextUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.PackageUidResolver
import com.v2ray.ang.util.Utils

object CoreConfigManager {
    private const val CONNECT_HTTP_PROXY_TAG = "pingng-connect-http-proxy"
    private const val WARP_MASQUE_RESOLVED_TAG = "pingng-warp-masque-resolved"
    private var initConfigCache: String? = null
    private var initConfigCacheWithTun: String? = null

    //region get config function

    /**
     * Build the runtime configuration for normal startup.
     */
    fun getV2rayConfig(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(
                    status = false,
                    guid = guid,
                    errorMessage = "Failed to build config context"
                )
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext)
            }
            return toConfigResult(configContext, buildUnifiedConfig(configContext))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build a lightweight configuration for latency testing.
     *
     * The core flow is reused, then non-essential sections are removed.
     */
    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(
                    status = false,
                    guid = guid,
                    errorMessage = "Failed to build config context"
                )
            if (configContext.isCustom) {
                return buildV2rayCustomConfig(configContext)
            }
            val v2rayConfig = buildUnifiedConfig(configContext)
            postProcessForSpeedtest(v2rayConfig)

            return toConfigResult(configContext, v2rayConfig)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
            return ConfigResult(
                status = false,
                guid = guid,
                errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Build configuration for custom profiles.
     */
    private fun buildV2rayCustomConfig(configContext: CoreConfigContext): ConfigResult {
        val context = configContext.context
        val raw = MmkvManager.decodeServerRaw(configContext.guid)
            ?: return ConfigResult(
                status = false,
                guid = configContext.guid,
                errorMessage = "Failed to build config context, config is empty"
            )
        val result = ConfigResult(true, configContext.guid, raw)

        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return result

        // Inject or remove traffic statistics configuration based on user preference
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true) {
            if (!json.has("stats")) {
                json.add("stats", JsonObject())
            }
            if (!json.has("policy")) {
                val policyObj = JsonObject()
                val systemObj = JsonObject()
                systemObj.addProperty("statsOutboundUplink", true)
                systemObj.addProperty("statsOutboundDownlink", true)
                policyObj.add("system", systemObj)
                json.add("policy", policyObj)
            }
        } else {
            json.remove("stats")
            json.remove("policy")
        }

        configureConnectHttpProxy(json)

        if (!needTun()) {
            return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
        }

        // Check whether package names need to be replaced with UIDs
        if (SettingsManager.canUseProcessRouting()) {
            val rulesJson = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: JsonArray()

            for (elem in rulesJson) {
                val rule = elem.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val process = rule.get("process")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val packages = process.mapNotNull {
                    it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }.takeIf { it.isNotEmpty() } ?: continue
                val uids = PackageUidResolver.packageNamesToUids(context, packages).takeIf { it.isNotEmpty() } ?: continue

                rule.add("process", JsonArray().apply { uids.forEach { add(it) } })
            }
        }

        // check if tun inbound exists
        val inboundsJson = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { json.add("inbounds", it) }
        val tunNotExists = inboundsJson.none { elem ->
            elem.isJsonObject && elem.asJsonObject.get("protocol")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString == "tun"
        }

        if (tunNotExists) {
            // add tun inbound from template
            val templateConfig = initV2rayConfig(configContext)
            templateConfig.inbounds.firstOrNull { it.tag == "tun" }?.let { inboundTun ->
                inboundTun.settings?.mtu = SettingsManager.getVpnMtu()
                inboundsJson.add(JsonUtil.parseString(JsonUtil.toJson(inboundTun)))
            }
        }

        return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
    }

    /**
     * Build one unified configuration for every non-custom profile type.
     *
     * The analyzed outbound plan is consumed in order and converted to concrete
     * outbounds before routing, DNS, and runtime extras are assembled.
     */
    private fun buildUnifiedConfig(configContext: CoreConfigContext): V2rayConfig {
        require(configContext.resolvedOutbounds.isNotEmpty()) { "resolvedOutbounds must not be empty for a non-CUSTOM context" }
        val primaryResolvedOutbound = configContext.resolvedOutbounds.first()

        val v2rayConfig = initV2rayConfig(configContext)
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.remarks = primaryResolvedOutbound.profile.remarks

        configureInbounds(v2rayConfig)

        if (v2rayConfig.outbounds.isNotEmpty()) {
            v2rayConfig.outbounds.removeAt(0)
        }
        val existingTags = v2rayConfig.outbounds.mapTo(mutableSetOf()) { it.tag }
        val policyGroupBalancerTags = mutableMapOf<String, String>()
        val balancerStrategies = mutableListOf<BalancerStrategy>()

        // resolvedOutbounds is a single ordered plan: index 0 is primary and must be prepended,
        // the rest are routing outbounds and can be appended.
        configContext.resolvedOutbounds.forEachIndexed { index, spec ->
            buildOutbounds(
                resolvedOutbound = spec,
                prepend = index == 0,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }

        val warpMasqueResolvedTag = if (
            WarpMasqueConfig.isDescription(primaryResolvedOutbound.profile.description)
        ) {
            addWarpMasqueResolvedOutbound(v2rayConfig)
        } else {
            null
        }

        val desyncEnabled = PingNgCompat.isNativeDesyncEnabled(primaryResolvedOutbound.profile)
        val desyncDelegatedToMasque = primaryResolvedOutbound.profile.configType == EConfigType.WARP &&
            WarpMasqueConfig.isDescription(primaryResolvedOutbound.profile.description)
        val desyncAttached = if (desyncDelegatedToMasque) {
            // WARP MASQUE's remote TLS socket belongs to its external Go
            // process. The Xray outbound here is only a local SOCKS hop;
            // attaching Desync to it would never touch the outer ClientHello.
            if (desyncEnabled) {
                PingNgDiagnostics.record("WARP MASQUE Desync delegated to outer HTTP CONNECT proxy")
            }
            true
        } else {
            PingNgCompat.attachNativeProxy(
                config = v2rayConfig,
                profileItem = primaryResolvedOutbound.profile,
                port = PingNgDesyncManager.activePort()
            )
        }
        check(!desyncEnabled || desyncAttached) {
            "Desync is enabled but could not be attached to the selected outbound"
        }
        configureConnectHttpProxy(v2rayConfig)

        // User routing rules (policyGroupBalancerTags rewrites TAG_PROXY→balancer when main is POLICYGROUP).
        configureRouting(configContext, v2rayConfig, policyGroupBalancerTags)
        configureFakeDns(v2rayConfig)
        configureDns(configContext, v2rayConfig, policyGroupBalancerTags)
        configureLocalDns(configContext, v2rayConfig)
        configureRootModeDns(v2rayConfig)
        val isWarpPlus = WarpPlusConfig.isDescription(primaryResolvedOutbound.profile.description)
        val isWarpMasque = WarpMasqueConfig.isDescription(primaryResolvedOutbound.profile.description)
        val isMasterDns = MasterDnsBridge.isProfile(primaryResolvedOutbound.profile)
        if (isWarpPlus || isWarpMasque || isMasterDns) {
            // Both WARP transports must receive every application flow. This
            // is especially important for browsers: unlike Telegram, they
            // create many fresh DNS/TCP/QUIC flows and must not fall through
            // to the template's direct outbound or a global block rule. Put
            // the WARP catch-all immediately after DNS interception.
            v2rayConfig.routing.domainStrategy = "IPIfNonMatch"
            val rules = v2rayConfig.routing.rules
            val dnsRules = rules.filter { rule ->
                rule.outboundTag == "dns-out" ||
                    rule.inboundTag?.any { it == AppConfig.TAG_DNS || it == "dns" } == true ||
                    (rule.port == "53" && rule.inboundTag?.any { it == "tun" || it == "socks" } == true)
            }.filterNot { rule -> isMasterDns && rule.outboundTag == AppConfig.TAG_DIRECT }
            rules.clear()
            rules.addAll(dnsRules)
            val inboundTags = arrayListOf("tun", "socks", "http")
            // Android sends the device resolver's UDP packets into the VPN.
            // If local-DNS is disabled, the generic WARP catch-all would send
            // those packets to the MASQUE SOCKS listener instead. Telegram
            // can still appear healthy because it mostly reuses cached IPs,
            // while a browser cannot resolve fresh website hostnames. Always
            // terminate inbound port-53 traffic in Xray's DNS module first.
            if (v2rayConfig.outbounds.none { it.protocol.equals("dns", ignoreCase = true) && it.tag == "dns-out" }) {
                v2rayConfig.outbounds.add(
                    V2rayConfig.OutboundBean(
                        protocol = "dns",
                        tag = "dns-out",
                        settings = null,
                        streamSettings = null,
                        mux = null,
                    )
                )
            }
            if (rules.none { it.outboundTag == "dns-out" && it.port == "53" }) {
                rules.add(
                    0,
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "udp",
                        port = "53",
                        inboundTag = inboundTags,
                        outboundTag = "dns-out",
                    )
                )
            }
            if (isMasterDns) {
                // MasterDnsVPN accepts SOCKS TCP and DNS UDP/53, not QUIC UDP/443.
                // Port 53 is intercepted above, and remaining UDP must fail fast.
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp",
                        inboundTag = inboundTags,
                        outboundTag = AppConfig.TAG_PROXY,
                    )
                )
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "udp",
                        inboundTag = inboundTags,
                        outboundTag = AppConfig.TAG_BLOCKED,
                    )
                )
                PingNgDiagnostics.record("MasterDNS: DNS/53 -> dns-out; TCP -> proxy; other UDP blocked")
            } else if (isWarpMasque && warpMasqueResolvedTag != null) {
                // Resolve hostname-based TCP flows in Xray before handing them
                // to the local MASQUE SOCKS listener. The listener's own DNS
                // (1.1.1.1) is unreliable on restricted networks; literal-IP
                // Telegram flows hid this defect.
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp",
                        inboundTag = inboundTags,
                        outboundTag = warpMasqueResolvedTag,
                    )
                )
                // Chromium and many Android browsers open QUIC (UDP/443)
                // before HTTPS/TCP. A failed UDP-associate can leave the page
                // waiting instead of falling back quickly, even though the
                // MASQUE TCP path is healthy. Reject only QUIC so browsers
                // immediately use the working TCP path; DNS and other UDP
                // traffic remain available through MASQUE.
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "udp",
                        port = "443",
                        inboundTag = inboundTags,
                        outboundTag = AppConfig.TAG_BLOCKED,
                    )
                )
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "udp",
                        inboundTag = inboundTags,
                        outboundTag = AppConfig.TAG_PROXY,
                    )
                )
            } else {
                rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp,udp",
                        inboundTag = inboundTags,
                        outboundTag = AppConfig.TAG_PROXY,
                    )
                )
            }
            // WARP profiles are full-device tunnels. Do not let a global
            // routing preset or a Direct geo rule win before WARP. DNS
            // interception and the MASQUE QUIC fallback rule remain above
            // this catch-all rule.
            if (!isMasterDns) {
                if (isWarpMasque && warpMasqueResolvedTag != null) {
                    PingNgDiagnostics.record("WARP MASQUE UDP/443 blocked; TCP fallback forced")
                }
                PingNgDiagnostics.record("WARP DNS interception: UDP/53 -> dns-out")
                PingNgDiagnostics.record("WARP full-device routing enabled")
            }
        }

        // (added by getDns / getCustomLocalDns) to use the balancer, then add
        // the catch-all balancer rule.
        if (primaryResolvedOutbound.resolvedType == CoreResolvedType.POLICYGROUP) {
            if (v2rayConfig.routing.domainStrategy == "IPIfNonMatch") {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        ip = arrayListOf("0.0.0.0/0", "::/0"),
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp,udp",
                        balancerTag = AppConfig.TAG_BALANCER,
                    )
                )
            }
        }

        applyObservability(v2rayConfig, balancerStrategies)
        applySpeedDisabled(v2rayConfig)
        resolveOutboundDomainsToHosts(v2rayConfig)

        return v2rayConfig
    }

    /**
     * Xray's SOCKS outbound forwards a hostname to the SOCKS server. The
     * standalone MASQUE core then tries to resolve it through its own DNS,
     * which is exactly the failing path seen in diagnostics. A Freedom
     * outbound with UseIPv4 resolves the destination in Xray and uses the
     * SOCKS outbound as its dialer proxy.
     */
    private fun addWarpMasqueResolvedOutbound(v2rayConfig: V2rayConfig): String? {
        val masqueOutbound = v2rayConfig.outbounds.firstOrNull {
            it.tag == AppConfig.TAG_PROXY && it.protocol.equals("socks", ignoreCase = true)
        } ?: return null
        if (v2rayConfig.outbounds.any { it.tag == WARP_MASQUE_RESOLVED_TAG }) {
            return WARP_MASQUE_RESOLVED_TAG
        }

        val resolvedOutbound = V2rayConfig.OutboundBean(
            tag = WARP_MASQUE_RESOLVED_TAG,
            protocol = AppConfig.PROTOCOL_FREEDOM,
            settings = null,
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                network = null,
                sockopt = V2rayConfig.OutboundBean.StreamSettingsBean.SockoptBean(
                    dialerProxy = masqueOutbound.tag,
                    domainStrategy = "UseIPv4",
                ),
            ),
            mux = null,
        )
        v2rayConfig.outbounds.add(resolvedOutbound)
        return WARP_MASQUE_RESOLVED_TAG
    }

    /**
     * Convert one analyzed outbound entry into concrete outbounds and register
     * them to the runtime configuration.
     */
    private fun buildOutbounds(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        if (resolvedOutbound.tag in existingTags) {
            LogUtil.w(AppConfig.TAG, "Resolved outbound tag '${resolvedOutbound.tag}' already exists, skipping duplicated entry")
            return
        }

        when (resolvedOutbound.resolvedType) {
            CoreResolvedType.NORMAL -> handleNormalResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.PROXYCHAIN -> handleProxyChainResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
            )

            CoreResolvedType.POLICYGROUP -> handlePolicyGroupResolvedOutbound(
                resolvedOutbound = resolvedOutbound,
                prepend = prepend,
                existingTags = existingTags,
                v2rayConfig = v2rayConfig,
                policyGroupBalancerTags = policyGroupBalancerTags,
                balancerStrategies = balancerStrategies,
            )
        }
    }

    /**
     * Build and insert a single-node outbound entry.
     */
    private fun handleNormalResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val profile = resolvedOutbound.resolvedProfiles.firstOrNull() ?: run {
            LogUtil.w(AppConfig.TAG, "NORMAL resolved outbound '${resolvedOutbound.tag}' has empty resolvedProfiles, skipping")
            return
        }
        val outbound = convertProfile2Outbound(profile) ?: run {
            LogUtil.w(AppConfig.TAG, "Could not convert NORMAL resolved outbound '${resolvedOutbound.tag}' profile to outbound, skipping")
            return
        }
        outbound.tag = resolvedOutbound.tag
        if (prepend) {
            v2rayConfig.outbounds.add(0, outbound)
        } else {
            v2rayConfig.outbounds.add(outbound)
        }
        existingTags.add(resolvedOutbound.tag)
    }

    /**
     * Build and insert a multi-hop chain entry.
     */
    private fun handleProxyChainResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
    ) {
        val chainOutbounds = resolvedOutbound.resolvedProfiles
            .mapNotNull { convertProfile2Outbound(it) }
            .toMutableList()
        if (chainOutbounds.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has no valid profiles, skipping")
            return
        }
        if (chainOutbounds.size == 1) {
            val outbound = chainOutbounds.first()
            outbound.tag = resolvedOutbound.tag
            if (prepend) {
                v2rayConfig.outbounds.add(0, outbound)
            } else {
                v2rayConfig.outbounds.add(outbound)
            }
            existingTags.add(resolvedOutbound.tag)
            return
        }

        val chainTags = chainOutbounds.mapIndexed { index, _ ->
            if (index == 0) {
                resolvedOutbound.tag
            } else {
                "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-$index"
            }
        }
        if (chainTags.any { it in existingTags }) {
            LogUtil.w(
                AppConfig.TAG,
                "PROXYCHAIN resolved outbound '${resolvedOutbound.tag}' has colliding hop tags, skipping"
            )
            return
        }

        chainOutbounds.forEachIndexed { index, outbound ->
            outbound.tag = chainTags[index]
        }
        for (i in 0 until chainOutbounds.size - 1) {
            val sockopt = chainOutbounds[i].ensureSockopt()
            sockopt.dialerProxy = chainOutbounds[i + 1].tag
            // ensureSockopt() creates a StreamSettingsBean whose default
            // network is TCP. WireGuard is already UDP-based; keep the
            // generated chain identical to Xray's working WARP examples.
            if (chainOutbounds[i].protocol.equals(EConfigType.WIREGUARD.name, ignoreCase = true)) {
                chainOutbounds[i].streamSettings?.network = null
            }
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, chainOutbounds)
        } else {
            v2rayConfig.outbounds.addAll(chainOutbounds)
        }
        chainOutbounds.forEach { existingTags.add(it.tag) }
    }

    /**
     * Build and insert a policy-group entry and its balancer metadata.
     */
    private fun handlePolicyGroupResolvedOutbound(
        resolvedOutbound: CoreConfigContext.ResolvedOutbound,
        prepend: Boolean,
        existingTags: MutableSet<String>,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: MutableMap<String, String>,
        balancerStrategies: MutableList<BalancerStrategy>,
    ) {
        val memberPairs = resolvedOutbound.resolvedProfiles.mapNotNull { profile ->
            convertProfile2Outbound(profile)?.let { ob -> ob to profile }
        }
        if (memberPairs.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' has no valid member outbounds, skipping")
            return
        }

        val memberTagPrefix = "${AppConfig.TAG_PROXY}-${resolvedOutbound.tag}-"
        val membersToAdd = mutableListOf<V2rayConfig.OutboundBean>()
        memberPairs.forEachIndexed { index, (outbound, profile) ->
            val memberTag = "$memberTagPrefix${index + 1}-${profile.remarks.trim()}"
            if (memberTag in existingTags) {
                return@forEachIndexed
            }
            outbound.tag = memberTag
            membersToAdd.add(outbound)
            existingTags.add(memberTag)
        }

        if (membersToAdd.isEmpty()) {
            LogUtil.w(
                AppConfig.TAG,
                "POLICYGROUP resolved outbound '${resolvedOutbound.tag}' produced no unique member tags, skipping"
            )
            return
        }

        if (prepend) {
            v2rayConfig.outbounds.addAll(0, membersToAdd)
        } else {
            v2rayConfig.outbounds.addAll(membersToAdd)
        }

        val balancerTag = if (resolvedOutbound.tag == AppConfig.TAG_PROXY) {
            AppConfig.TAG_BALANCER
        } else {
            "${AppConfig.TAG_BALANCER_PRE}-${resolvedOutbound.tag}"
        }
        val strategyType = BalancerStrategyType.from(resolvedOutbound.profile.policyGroupType)
        val fallbackTag = if (strategyType.supportsObservatory && resolvedOutbound.profile.policyGroupTestOutbounds != false) {
            resolvedOutbound.profile.policyGroupFallbackTag
                ?.takeIf { it.isNotEmpty() && it != AppConfig.TAG_PROXY }
            // Xray excludes dead random/roundRobin candidates only when fallbackTag is set;
            // without this default, an enabled empty field creates no observatory.
                ?: membersToAdd.first().tag
        } else null
        val strategy = buildBalancerStrategy(
            strategyType = strategyType,
            selector = listOf(memberTagPrefix),
            balancerTag = balancerTag,
            fallbackTag = fallbackTag,
        )
        val existingBalancers = v2rayConfig.routing.balancers?.toMutableList() ?: mutableListOf()
        if (existingBalancers.none { it.tag == balancerTag }) {
            existingBalancers.add(strategy.balancer)
            v2rayConfig.routing.balancers = existingBalancers
        }
        balancerStrategies.add(strategy)
        policyGroupBalancerTags[resolvedOutbound.tag] = balancerTag
    }

    /**
     * Trim runtime sections that are not needed for latency testing.
     */
    private fun postProcessForSpeedtest(v2rayConfig: V2rayConfig) {
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.inbounds.clear()
        v2rayConfig.routing.rules.clear()
        v2rayConfig.dns = null
        v2rayConfig.fakedns = null
        v2rayConfig.stats = null
        v2rayConfig.policy = null
        v2rayConfig.outbounds.forEach { key -> key.mux = null }
    }

    /**
     * Wraps proxy-tagged Xray outbounds with an HTTP CONNECT hop. Existing dialerProxy
     * chains (including PingNG Desync) are preserved behind the HTTP hop.
     */
    private fun configureConnectHttpProxy(v2rayConfig: V2rayConfig) {
        val proxy = SettingsManager.getConnectHttpProxy() ?: return
        val proxyOutbounds = v2rayConfig.outbounds.filter { outbound ->
            (outbound.tag == AppConfig.TAG_PROXY || outbound.tag.startsWith("${AppConfig.TAG_PROXY}-")) &&
                // HTTP CONNECT is a TCP proxy. It cannot carry the UDP
                // WireGuard handshake used by WARP, so wrapping WARP hops
                // here creates a config that starts but never connects.
                outbound.protocol.lowercase() !in setOf("freedom", "blackhole", "dns", "wireguard")
        }
        val skippedWarpMasqueAdapters = proxyOutbounds.count { isWarpMasqueLoopbackAdapter(it) }
        val candidates = proxyOutbounds.filterNot { isWarpMasqueLoopbackAdapter(it) }
        if (candidates.isEmpty()) {
            val hasWireGuard = v2rayConfig.outbounds.any { outbound ->
                (outbound.tag == AppConfig.TAG_PROXY || outbound.tag.startsWith("${AppConfig.TAG_PROXY}-")) &&
                    outbound.protocol.equals(EConfigType.WIREGUARD.name, ignoreCase = true)
            }
            LogUtil.w(
                AppConfig.TAG,
                if (skippedWarpMasqueAdapters > 0) {
                    "HTTP proxy skipped for WARP MASQUE local SOCKS adapter; " +
                        "wrapping 127.0.0.1 would break the MASQUE data plane"
                } else if (hasWireGuard) {
                    "HTTP proxy skipped: WireGuard/WARP uses UDP and cannot use HTTP CONNECT"
                } else {
                    "HTTP proxy is enabled but no TCP proxy outbound was found"
                },
            )
            return
        }

        val existingTags = v2rayConfig.outbounds.mapTo(mutableSetOf()) { it.tag }
        val additions = mutableListOf<V2rayConfig.OutboundBean>()
        candidates.forEachIndexed { index, outbound ->
            val previousDialer = outbound.streamSettings?.sockopt?.dialerProxy
            var tag = if (index == 0) CONNECT_HTTP_PROXY_TAG else "$CONNECT_HTTP_PROXY_TAG-$index"
            while (tag in existingTags) tag += "-next"

            val upstream = V2rayConfig.OutboundBean(
                tag = tag,
                protocol = EConfigType.HTTP.name.lowercase(),
                settings = V2rayConfig.OutboundBean.OutSettingsBean(
                    address = proxy.host,
                    port = proxy.port,
                    level = AppConfig.DEFAULT_LEVEL,
                    user = proxy.username,
                    pass = proxy.password,
                ),
                streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                    network = "tcp",
                ),
                mux = null,
            )
            if (!previousDialer.isNullOrBlank()) {
                upstream.ensureSockopt().dialerProxy = previousDialer
            }
            outbound.ensureSockopt().dialerProxy = tag
            additions.add(upstream)
            existingTags.add(tag)
        }
        v2rayConfig.outbounds.addAll(additions)
        LogUtil.i(
            AppConfig.TAG,
            "HTTP CONNECT proxy enabled for ${candidates.size} outbound(s): ${proxy.host}:${proxy.port}" +
                skippedWarpMasqueAdapters.takeIf { it > 0 }
                    ?.let { "; skipped $it WARP MASQUE local adapter(s)" }.orEmpty(),
        )
        if (skippedWarpMasqueAdapters > 0) {
            PingNgDiagnostics.record(
                "Connect through HTTP proxy: WARP MASQUE local adapter bypassed; TCP node outbounds wrapped",
            )
        }
    }

    /** JSON equivalent used for user-supplied custom Xray configurations. */
    private fun configureConnectHttpProxy(json: JsonObject) {
        val proxy = SettingsManager.getConnectHttpProxy() ?: return
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val proxyOutbounds = outbounds.toList().mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { outbound ->
                val tag = outbound.get("tag")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val protocol = outbound.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase()
                (tag == AppConfig.TAG_PROXY || tag.startsWith("${AppConfig.TAG_PROXY}-")) &&
                    protocol !in setOf("freedom", "blackhole", "dns", "wireguard")
            }
        }
        val skippedWarpMasqueAdapters = proxyOutbounds.count { isWarpMasqueLoopbackAdapter(it) }
        val candidates = proxyOutbounds.filterNot { isWarpMasqueLoopbackAdapter(it) }
        if (candidates.isEmpty()) {
            LogUtil.w(
                AppConfig.TAG,
                if (skippedWarpMasqueAdapters > 0) {
                    "HTTP proxy skipped for WARP MASQUE local SOCKS adapter in custom config"
                } else {
                    "HTTP proxy is enabled but the custom config has no TCP proxy outbound"
                },
            )
            return
        }

        val existingTags = outbounds.toList().mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.get("tag")?.asString
        }.toMutableSet()
        candidates.forEachIndexed { index, outbound ->
            val stream = outbound.get("streamSettings")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { outbound.add("streamSettings", it) }
            val sockopt = stream.get("sockopt")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { stream.add("sockopt", it) }
            val previousDialer = sockopt.get("dialerProxy")?.takeIf { it.isJsonPrimitive }?.asString
            var tag = if (index == 0) CONNECT_HTTP_PROXY_TAG else "$CONNECT_HTTP_PROXY_TAG-$index"
            while (tag in existingTags) tag += "-next"

            val settings = JsonObject().apply {
                addProperty("address", proxy.host)
                addProperty("port", proxy.port)
                proxy.username?.let { addProperty("user", it) }
                proxy.password?.let { addProperty("pass", it) }
            }
            val upstreamSockopt = JsonObject()
            previousDialer?.takeIf { it.isNotBlank() }?.let { upstreamSockopt.addProperty("dialerProxy", it) }
            val upstreamStream = JsonObject().apply {
                addProperty("network", "tcp")
                if (upstreamSockopt.size() > 0) add("sockopt", upstreamSockopt)
            }
            val upstream = JsonObject().apply {
                addProperty("tag", tag)
                addProperty("protocol", EConfigType.HTTP.name.lowercase())
                add("settings", settings)
                add("streamSettings", upstreamStream)
            }
            sockopt.addProperty("dialerProxy", tag)
            outbounds.add(upstream)
            existingTags.add(tag)
        }
        LogUtil.i(
            AppConfig.TAG,
            "HTTP CONNECT proxy enabled for custom configuration: ${proxy.host}:${proxy.port}" +
                skippedWarpMasqueAdapters.takeIf { it > 0 }
                    ?.let { "; skipped $it WARP MASQUE local adapter(s)" }.orEmpty(),
        )
    }

    /**
     * WARP MASQUE's `proxy` outbound is a local SOCKS adapter owned by the
     * standalone MASQUE process, not the remote server connection itself.
     * Putting an HTTP CONNECT dialer in front of 127.0.0.1 sends the local
     * adapter through the external proxy and breaks WARP/Psiphon. Normal TCP
     * outbounds still use the setting exactly as configured.
     */
    private fun isWarpMasqueLoopbackAdapter(outbound: V2rayConfig.OutboundBean): Boolean {
        if (!isSelectedWarpMasqueProfile()) return false
        if (!outbound.protocol.equals("socks", ignoreCase = true)) return false
        val tag = outbound.tag
        if (tag != AppConfig.TAG_PROXY && !tag.startsWith("${AppConfig.TAG_PROXY}-")) return false
        val address = outbound.settings?.address?.toString()?.trim()?.trim('"')?.lowercase()
        return address == AppConfig.LOOPBACK || address == "localhost" || address == "::1"
    }

    private fun isWarpMasqueLoopbackAdapter(outbound: JsonObject): Boolean {
        if (!isSelectedWarpMasqueProfile()) return false
        if (!outbound.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString
                .orEmpty().equals("socks", ignoreCase = true)
        ) return false
        val tag = outbound.get("tag")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (tag != AppConfig.TAG_PROXY && !tag.startsWith("${AppConfig.TAG_PROXY}-")) return false
        val address = outbound.getAsJsonObject("settings")
            ?.get("address")?.takeIf { it.isJsonPrimitive }?.asString
            ?.trim()?.lowercase()
        return address == AppConfig.LOOPBACK || address == "localhost" || address == "::1"
    }

    private fun isSelectedWarpMasqueProfile(): Boolean {
        val selectedGuid = MmkvManager.getSelectServer() ?: return false
        val profile = MmkvManager.decodeServerConfig(selectedGuid) ?: return false
        return WarpMasqueConfig.isDescription(profile.description)
    }

    /**
     * Serialize a runtime configuration into a standard result object.
     */
    private fun toConfigResult(configContext: CoreConfigContext, v2rayConfig: V2rayConfig): ConfigResult {
        return ConfigResult(
            status = true,
            guid = configContext.guid,
            content = JsonUtil.toJsonPretty(v2rayConfig) ?: ""
        )
    }

    /**
     * Load the base template from cache or assets and parse it.
     */
    private fun initV2rayConfig(configContext: CoreConfigContext): V2rayConfig {
        val context = configContext.context
        val assets: String
        if (needTun()) {
            assets = initConfigCacheWithTun ?: Utils.readTextFromAssets(context, "v2ray_config_with_tun.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config_with_tun.json")
            }
            initConfigCacheWithTun = assets
        } else {
            assets = initConfigCache ?: Utils.readTextFromAssets(context, "v2ray_config.json")
            if (TextUtils.isEmpty(assets)) {
                error("Missing asset: v2ray_config.json")
            }
            initConfigCache = assets
        }
        return JsonUtil.fromJson(assets, V2rayConfig::class.java)
            ?: error("Failed to parse config template")
    }


    //endregion


    //region some sub function

    private fun needTun(): Boolean {
        return SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()
    }

    /**
     * Configure inbound listeners and related runtime options.
     */
    private fun configureInbounds(v2rayConfig: V2rayConfig) {
        val vpn = SettingsManager.isVpnMode()
        val useHev = SettingsManager.isUsingHevTun()
        val forcedByHev = vpn && useHev
        val forcedBySocksRoot = SettingsManager.isRootMode()
                || MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)

        val enableLocalProxy = forcedByHev || forcedBySocksRoot || MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)

        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val inbound1 = v2rayConfig.inbounds[0]
        if (inbound1.settings == null) {
            inbound1.settings = V2rayConfig.InboundBean.InSettingsBean()
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) != true) {
            inbound1.listen = AppConfig.LOOPBACK
        }
        inbound1.port = socksPort
        inbound1.settings?.udp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        if (socksUsername != null && socksPassword != null) {
            inbound1.settings?.auth = "password"
            inbound1.settings?.accounts = listOf(
                V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean(
                    user = socksUsername,
                    pass = socksPassword
                )
            )
        } else {
            inbound1.settings?.auth = "noauth"
            inbound1.settings?.accounts = null
        }
        val fakedns = MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        val sniffAllTlsAndHttp =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true) != false
        inbound1.sniffing?.enabled = fakedns || sniffAllTlsAndHttp
        inbound1.sniffing?.routeOnly =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
        if (!sniffAllTlsAndHttp) {
            inbound1.sniffing?.destOverride?.clear()
        }
        if (fakedns) {
            inbound1.sniffing?.destOverride?.add("fakedns")
        }

        if (!Utils.isXray()) {
            val inbound2 = JsonUtil.fromJson(JsonUtil.toJson(inbound1), V2rayConfig.InboundBean::class.java)
                ?: error("Failed to clone inbound template")
            inbound2.tag = EConfigType.HTTP.name.lowercase()
            inbound2.port = SettingsManager.getHttpPort()
            inbound2.protocol = EConfigType.HTTP.name.lowercase()
            inbound2.settings?.auth = null
            inbound2.settings?.udp = null
            v2rayConfig.inbounds.add(inbound2)
        }

        if (!enableLocalProxy) {
            v2rayConfig.inbounds.removeIf { it.protocol == "socks" || it.protocol == "http" }
        }

        if (needTun()) {
            val inboundTun = v2rayConfig.inbounds.firstOrNull { e -> e.tag == "tun" }
            inboundTun?.settings?.mtu = SettingsManager.getVpnMtu()
            inboundTun?.sniffing = inbound1.sniffing
        }
    }

    /**
     * Enable fake DNS when local DNS and fake DNS are both enabled.
     */
    private fun configureFakeDns(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        ) {
            v2rayConfig.fakedns = listOf(V2rayConfig.FakednsBean())
        }
    }

    /**
     * Collect domain rules that target one outbound tag.
     */
    private fun collectUserRuleDomainsByTag(tag: String): ArrayList<String> {
        val domain = ArrayList<String>()

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.enabled && key.outboundTag == tag && !key.domain.isNullOrEmpty()) {
                key.domain?.forEach {
                    domain.add(it)
                }
            }
        }

        return domain
    }

    /**
     * Collect domain rules that target non-builtin outbound tags.
     */
    private fun collectCustomOutboundDomains(): ArrayList<String> {
        val domain = ArrayList<String>()

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            if (key.enabled && !AppConfig.BUILTIN_OUTBOUND_TAGS.contains(key.outboundTag)
                && !key.domain.isNullOrEmpty()
            ) {
                key.domain?.forEach {
                    domain.add(it)
                }
            }
        }

        return domain
    }

    /**
     * Configure local DNS inbounds, outbounds, and routing rules.
     */
    private fun configureLocalDns(configContext: CoreConfigContext, v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) != true) {
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) {
            val geositeCn = arrayListOf(AppConfig.GEOSITE_CN)
            val routingDomains = configContext.routingDomainRules
                .asSequence()
                .filter { it.outboundTag != AppConfig.TAG_BLOCKED }
                .flatMap { it.domain.asSequence() }
                .toList()
                .distinct()
            val finalDomain = geositeCn + routingDomains
            // fakedns with all domains to make it always top priority
            v2rayConfig.dns?.servers?.add(
                0,
                V2rayConfig.DnsBean.ServersBean(
                    address = "fakedns",
                    domains = finalDomain
                )
            )
        }

        if (SettingsManager.isVpnMode()) {
            if (SettingsManager.isUsingHevTun()) {
                //hev-socks5-tunnel dns routing
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("socks"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            } else {
                v2rayConfig.routing.rules.add(
                    0, V2rayConfig.RoutingBean.RulesBean(
                        inboundTag = arrayListOf("tun"),
                        outboundTag = "dns-out",
                        port = "53",
                    )
                )
            }
        }

        // DNS outbound
        if (v2rayConfig.outbounds.none { e -> e.protocol == "dns" && e.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * In the root mode the whole device's traffic (incl. raw DNS) is funneled
     * into the core's SOCKS inbound, exactly like the VPN+hev path. Hijack port-53 to the
     * core's DNS module so queries are resolved via the configured resolver through the
     * proxy instead of leaking to (or being mis-resolved by) the local network resolver.
     * Independent of the local-DNS toggle, which is not exposed for root mode.
     */
    private fun configureRootModeDns(v2rayConfig: V2rayConfig) {
        if (!SettingsManager.isRootMode()) return

        if (v2rayConfig.routing.rules.none { it.outboundTag == "dns-out" && it.port == "53" }) {
            v2rayConfig.routing.rules.add(
                0,
                V2rayConfig.RoutingBean.RulesBean(
                    inboundTag = arrayListOf("socks"),
                    outboundTag = "dns-out",
                    port = "53",
                )
            )
        }
        if (v2rayConfig.outbounds.none { it.protocol == "dns" && it.tag == "dns-out" }) {
            v2rayConfig.outbounds.add(
                V2rayConfig.OutboundBean(
                    protocol = "dns",
                    tag = "dns-out",
                    settings = null,
                    streamSettings = null,
                    mux = null
                )
            )
        }
    }

    /**
     * Remove speed-test runtime sections when the feature is disabled.
     */
    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) {
            v2rayConfig.stats = null
            v2rayConfig.policy = null
        }
    }

    /*
    /**
     * Configure DNS servers, hosts, and DNS routing rules.
     */
    private fun configureDns(
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>,
    ) {
        val hosts = mutableMapOf<String, Any>()
        val servers = ArrayList<Any>()

        //remote Dns
        val remoteDns = SettingsManager.getRemoteDnsServers()
        val proxyDomain = (collectUserRuleDomainsByTag(AppConfig.TAG_PROXY) + collectCustomOutboundDomains()).distinct()
        remoteDns.forEach {
            servers.add(it)
        }
        if (proxyDomain.isNotEmpty()) {
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = remoteDns.first(),
                    domains = proxyDomain,
                )
            )
        }

        // domestic DNS
        val domesticDns = SettingsManager.getDomesticDnsServers()
        val directDomain = collectUserRuleDomainsByTag(AppConfig.TAG_DIRECT)
        val isCnRoutingMode = directDomain.contains(AppConfig.GEOSITE_CN)
        val cnRegionFilter = { domain: String ->
            domain.startsWith("geosite:") && (domain.endsWith("-cn") || domain.endsWith("@cn"))
                    || domain == AppConfig.GEOSITE_CN
        }
        val finalDirectDomain = if (isCnRoutingMode) directDomain.filterNot {
            cnRegionFilter(it)
        } else directDomain
        val domesticDnsTags = mutableListOf<String>()
        domesticDns.forEachIndexed { index, element ->
            val tag = AppConfig.TAG_DOMESTIC_DNS + index
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = element,
                    domains = finalDirectDomain,
                    skipFallback = true,
                    tag = tag
                )
            )
            domesticDnsTags.add(tag)
        }
        if (isCnRoutingMode) {
            val geoipCn = arrayListOf(AppConfig.GEOIP_CN)
            val cnRegionDomain = directDomain.filter { cnRegionFilter(it) }
            domesticDns.forEachIndexed { index, element ->
                val geositeCnDnsTag = AppConfig.TAG_DOMESTIC_DNS + index + "_cn_expect"
                servers.add(
                    V2rayConfig.DnsBean.ServersBean(
                        address = element,
                        domains = cnRegionDomain,
                        expectIPs = geoipCn,
                        skipFallback = true,
                        tag = geositeCnDnsTag
                    )
                )
                domesticDnsTags.add(geositeCnDnsTag)
            }
        }

        //block dns
        val blkDomain = collectUserRuleDomainsByTag(AppConfig.TAG_BLOCKED)
        if (blkDomain.isNotEmpty()) {
            hosts.putAll(blkDomain.map { it to AppConfig.LOOPBACK })
        }

        // hardcode googleapi rule to fix play store problems
        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN

        // hardcode popular Android Private DNS rule to fix localhost DNS problem
        hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES
        hosts[AppConfig.DNS_CISCO_SSE_DOMAIN] = AppConfig.DNS_CISCO_SSE_ADDRESSES
        hosts[AppConfig.DNS_CISCO_UMBRELLA_DOMAIN] = AppConfig.DNS_CISCO_UMBRELLA_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN] = AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOH_DOMAIN] = AppConfig.DNS_DNSPOD_DOH_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOT_DOMAIN] = AppConfig.DNS_DNSPOD_DOT_ADDRESSES
        hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES
        hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES
        hosts[AppConfig.DNS_SB_DOMAIN] = AppConfig.DNS_SB_ADDRESSES
        hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES

        //User DNS hosts
        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) {
            val userHostsMap = userHosts?.split(",")
                ?.filter { it.isNotEmpty() }
                ?.filter { it.contains(":") }
                ?.associate { it.split(":").let { (k, v) -> k to v } }
            if (userHostsMap != null) {
                hosts.putAll(userHostsMap)
            }
        }

        // DNS dns
        v2rayConfig.dns = V2rayConfig.DnsBean(
            servers = servers,
            hosts = hosts,
            tag = AppConfig.TAG_DNS,
            enableParallelQuery = if ((domesticDns.size + remoteDns.size) > 2) true else null
        )

        // DNS routing
        v2rayConfig.routing.rules.add(
            V2rayConfig.RoutingBean.RulesBean(
                outboundTag = AppConfig.TAG_DIRECT,
                inboundTag = domesticDnsTags,
                domain = null
            )
        )
        val dnsProxyBalancerTag = policyGroupBalancerTags[AppConfig.TAG_PROXY]
        if (dnsProxyBalancerTag != null) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    balancerTag = dnsProxyBalancerTag,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        } else {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_PROXY,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        }
    }
    */

    /**
     * Configure DNS servers, hosts, and DNS routing rules.
     */
    private fun configureDns(
        configContext: CoreConfigContext,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>,
    ) {
        val servers = ArrayList<Any>()
        val isWarpPlus = WarpPlusConfig.isDescription(
            configContext.resolvedOutbounds.firstOrNull()?.profile?.description
        )
        val isWarpMasque = WarpMasqueConfig.isDescription(
            configContext.resolvedOutbounds.firstOrNull()?.profile?.description
        )
        val isMasterDns = configContext.resolvedOutbounds.firstOrNull()
            ?.profile?.let(MasterDnsBridge::isProfile) == true
        val remoteDns = SettingsManager.getRemoteDnsServers()
        val domesticDns = SettingsManager.getDomesticDnsServers()

        if (isWarpPlus) {
            // Match the supplied WARP Plus template and keep endpoint DNS
            // resolution deterministic on IPv4-only networks.
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = "1.1.1.1",
                    tag = "remote-dns",
                )
            )
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = "8.8.8.8",
                    domains = listOf("full:engage.cloudflareclient.com"),
                    skipFallback = true,
                )
            )
        } else if (isWarpMasque) {
            // Keep the user's DNS choice, but force IPv4 resolution for the
            // MASQUE path. This avoids browser failures caused by an IPv6
            // answer on networks where the WARP core is using IPv4 ingress.
            remoteDns.forEach { servers.add(it) }
        } else {
            remoteDns.forEach { servers.add(it) }
        }

        val hosts = buildDnsHostsFromRoutingRules(configContext)
        val cnDomesticDnsTags = if (isWarpPlus) mutableListOf<String>() else buildDnsCnModeFromRoutingRules(configContext, servers, domesticDns)
        val domesticDnsTags = if (isWarpPlus) mutableListOf<String>() else buildDnsFromRoutingRules(
            configContext = configContext,
            servers = servers,
            remoteDns = remoteDns,
            domesticDns = domesticDns
        )
        domesticDnsTags.addAll(cnDomesticDnsTags)

        v2rayConfig.dns = V2rayConfig.DnsBean(
            servers = servers,
            hosts = hosts,
            queryStrategy = if (isWarpPlus || isWarpMasque || isMasterDns) "UseIPv4" else null,
            tag = AppConfig.TAG_DNS,
            enableParallelQuery = if (!isWarpPlus && (domesticDns.size + remoteDns.size) > 2) true else null
        )

        if (domesticDnsTags.isNotEmpty()) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_DIRECT,
                    inboundTag = ArrayList(domesticDnsTags),
                    domain = null
                )
            )
        }

        val dnsProxyBalancerTag = policyGroupBalancerTags[AppConfig.TAG_PROXY]
        if (dnsProxyBalancerTag != null) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    balancerTag = dnsProxyBalancerTag,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        } else {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(
                    outboundTag = AppConfig.TAG_PROXY,
                    inboundTag = arrayListOf(AppConfig.TAG_DNS),
                    domain = null
                )
            )
        }
    }

    private fun buildDnsHostsFromRoutingRules(configContext: CoreConfigContext): MutableMap<String, Any> {
        val hosts = mutableMapOf<String, Any>()

        val blockDomains = configContext.routingDomainRules
            .asSequence()
            .filter { it.outboundTag == AppConfig.TAG_BLOCKED }
            .flatMap { it.domain.asSequence() }
            .toList()
        if (blockDomains.isNotEmpty()) {
            hosts.putAll(blockDomains.map { it to AppConfig.LOOPBACK })
        }

        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN
        hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES
        hosts[AppConfig.DNS_CISCO_SSE_DOMAIN] = AppConfig.DNS_CISCO_SSE_ADDRESSES
        hosts[AppConfig.DNS_CISCO_UMBRELLA_DOMAIN] = AppConfig.DNS_CISCO_UMBRELLA_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN] = AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOH_DOMAIN] = AppConfig.DNS_DNSPOD_DOH_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOT_DOMAIN] = AppConfig.DNS_DNSPOD_DOT_ADDRESSES
        hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES
        hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES
        hosts[AppConfig.DNS_SB_DOMAIN] = AppConfig.DNS_SB_ADDRESSES
        hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES

        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) {
            val userHostsMap = userHosts?.split(",").orEmpty()
                .filter { it.isNotBlank() && it.contains(":") }
                .associate {
                    // Use limit = 2 to split only at the first colon.
                    // This ensures that IPv6 addresses (which contain multiple colons)
                    // are preserved entirely in the second part.
                    val parts = it.split(":", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }
            hosts.putAll(userHostsMap)
        }

        return hosts
    }

    private fun buildDnsCnModeFromRoutingRules(configContext: CoreConfigContext, servers: ArrayList<Any>, domesticDns: List<String>): List<String> {
        val cnRegionFilter = { domain: String ->
            domain.startsWith("geosite:") && (domain.endsWith("-cn") || domain.endsWith("@cn"))
                    || domain == AppConfig.GEOSITE_CN
        }
        val isCnRoutingMode = configContext.routingDomainRules
            .asSequence()
            .filter { it.outboundTag == AppConfig.TAG_DIRECT }
            .flatMap { it.domain.asSequence() }
            .any { it == AppConfig.GEOSITE_CN }

        if (!isCnRoutingMode) {
            return emptyList()
        }

        val geoipCn = arrayListOf(AppConfig.GEOIP_CN)
        val cnDomains = configContext.routingDomainRules
            .asSequence()
            .filter { it.outboundTag == AppConfig.TAG_DIRECT }
            .flatMap { it.domain.asSequence() }
            .filter { cnRegionFilter(it) }
            .toList()
        if (cnDomains.isEmpty()) {
            return emptyList()
        }

        val cnDomesticDnsTags = mutableListOf<String>()
        domesticDns.forEachIndexed { index, address ->
            val cnDomesticDnsTag = "${AppConfig.TAG_DOMESTIC_DNS}_cn_expect_${index}"
            servers.add(
                V2rayConfig.DnsBean.ServersBean(
                    address = address,
                    domains = cnDomains,
                    expectIPs = geoipCn,
                    skipFallback = true,
                    tag = cnDomesticDnsTag
                )
            )
            cnDomesticDnsTags.add(cnDomesticDnsTag)
        }
        return cnDomesticDnsTags
    }

    private fun buildDnsFromRoutingRules(
        configContext: CoreConfigContext,
        servers: ArrayList<Any>,
        remoteDns: List<String>,
        domesticDns: List<String>,
    ): MutableList<String> {
        val domesticDnsTags = mutableListOf<String>()
        configContext.routingDomainRules.forEachIndexed { ruleIndex, rule ->
            when (rule.outboundTag) {
                AppConfig.TAG_DIRECT -> {
                    domesticDns.forEachIndexed { dnsIndex, address ->
                        val tag = "${AppConfig.TAG_DOMESTIC_DNS}_${ruleIndex}_$dnsIndex"
                        servers.add(
                            V2rayConfig.DnsBean.ServersBean(
                                address = address,
                                domains = rule.domain,
                                skipFallback = true,
                                tag = tag
                            )
                        )
                        domesticDnsTags.add(tag)
                    }
                }

                AppConfig.TAG_BLOCKED -> Unit
                else -> {
                    servers.add(
                        V2rayConfig.DnsBean.ServersBean(
                            address = remoteDns.first(),
                            domains = rule.domain,
                        )
                    )
                }
            }
        }
        return domesticDnsTags
    }

    //endregion


    //region outbound related functions


    /**
     * Resolve outbound domains to IPs and write resolved hosts to DNS map.
     */
    private fun resolveOutboundDomainsToHosts(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "1") {
            return
        }

        val proxyOutboundList = v2rayConfig.getAllProxyOutbound()
        val dns = v2rayConfig.dns ?: return
        val newHosts = dns.hosts?.toMutableMap() ?: mutableMapOf()
        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true

        for (item in proxyOutboundList) {
            val domain = item.getServerAddress()
            if (domain.isNullOrEmpty()) {
                continue
            }

            // WARP's WireGuard endpoint is UDP and the working WARP-in-WARP
            // profile uses the IPv4 ingress. Avoid selecting an IPv6 endpoint
            // on networks where IPv6 is advertised but not actually routable.
            if (item.protocol.equals(EConfigType.WIREGUARD.name, ignoreCase = true)) {
                val sockopt = item.ensureSockopt()
                sockopt.domainStrategy = "UseIPv4"
                sockopt.happyEyeballs = null
                // ensureSockopt() creates StreamSettingsBean with the global
                // default network (tcp). WireGuard is UDP-based; leaving that
                // default on the outer hop breaks WARP-in-WARP return traffic.
                item.streamSettings?.network = null
                continue
            }

            if (newHosts.containsKey(domain)) {
                item.ensureSockopt().domainStrategy = "UseIP"
                item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                    prioritizeIPv6 = preferIpv6,
                    interleave = 2
                )
                continue
            }

            val resolvedIps = HttpUtil.resolveHostToIP(domain, preferIpv6)
            if (resolvedIps.isNullOrEmpty()) {
                continue
            }

            item.ensureSockopt().domainStrategy = "UseIP"
            item.ensureSockopt().happyEyeballs = V2rayConfig.OutboundBean.StreamSettingsBean.HappyEyeballsBean(
                prioritizeIPv6 = preferIpv6,
                interleave = 2
            )
            newHosts[domain] = if (resolvedIps.size == 1) {
                resolvedIps[0]
            } else {
                resolvedIps
            }
        }

        dns.hosts = newHosts
    }

    /**
     * Convert one profile object into one outbound object.
     */
    private fun convertProfile2Outbound(profileItem: ProfileItem): V2rayConfig.OutboundBean? {
        return CoreOutboundBuilder.convert(profileItem)
    }

    //endregion


    //region routing related functions


    /**
     * Merge probe settings from all balancer strategies into the runtime config.
     */
    private fun applyObservability(v2rayConfig: V2rayConfig, strategies: List<BalancerStrategy>) {
        val allObsSelectors = strategies
            .mapNotNull { it.observatory?.subjectSelector }
            .flatten()
            .distinct()
        val obsTemplate = strategies.firstNotNullOfOrNull { it.observatory }
        if (obsTemplate != null && allObsSelectors.isNotEmpty()) {
            v2rayConfig.observatory = V2rayConfig.ObservatoryObject(
                subjectSelector = allObsSelectors,
                probeUrl = obsTemplate.probeUrl,
                probeInterval = obsTemplate.probeInterval,
                enableConcurrency = obsTemplate.enableConcurrency
            )
        }

        val allBurstSelectors = strategies
            .mapNotNull { it.burstObservatory?.subjectSelector }
            .flatten()
            .distinct()
        val burstTemplate = strategies.firstNotNullOfOrNull { it.burstObservatory }
        if (burstTemplate != null && allBurstSelectors.isNotEmpty()) {
            v2rayConfig.burstObservatory = V2rayConfig.BurstObservatoryObject(
                subjectSelector = allBurstSelectors,
                pingConfig = burstTemplate.pingConfig
            )
        }
    }

    /**
     * Configure routing domain strategy and append enabled user rules.
     */
    private fun configureRouting(
        configContext: CoreConfigContext,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {

        v2rayConfig.routing.domainStrategy =
            MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY)
                ?: "AsIs"

        val rulesetItems = MmkvManager.decodeRoutingRulesets()
        rulesetItems?.forEach { key ->
            appendRoutingUserRule(configContext, key, v2rayConfig, policyGroupBalancerTags)
        }
    }

    /**
     * Convert one rule item and append it to routing rules.
     */
    private fun appendRoutingUserRule(
        configContext: CoreConfigContext,
        item: RulesetItem?,
        v2rayConfig: V2rayConfig,
        policyGroupBalancerTags: Map<String, String>
    ) {
        val context = configContext.context
        if (item == null || !item.enabled) {
            return
        }

        val rule = JsonUtil.fromJson(JsonUtil.toJson(item), V2rayConfig.RoutingBean.RulesBean::class.java) ?: return

        // Replace specific geoip rules with ext versions
        rule.ip?.let { ipList ->
            val updatedIpList = ArrayList<String>()
            ipList.forEach { ip ->
                when (ip) {
                    AppConfig.GEOIP_CN -> updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:cn")
                    AppConfig.GEOIP_PRIVATE -> updatedIpList.add("ext:${AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT}:private")
                    else -> updatedIpList.add(ip)
                }
            }
            rule.ip = updatedIpList
        }

        if (SettingsManager.canUseProcessRouting()) {
            // Convert process package names to UIDs
            rule.process?.let { processList ->
                if (processList.isNotEmpty()) {
                    val uids = PackageUidResolver.packageNamesToUids(context, processList)
                    rule.process = uids.ifEmpty { null }
                }
            }
        } else {
            rule.process = null
        }

        val outboundTag = rule.outboundTag

        // Route rules targeting a custom policy-group tag should hit its balancer.
        policyGroupBalancerTags[outboundTag]?.let { balancerTag ->
            rule.outboundTag = null
            rule.balancerTag = balancerTag
        }

        // If the outbound tag is a custom one that failed to inject, fall back to proxy
        if (!outboundTag.isNullOrBlank()
            && outboundTag !in policyGroupBalancerTags
            && outboundTag !in AppConfig.BUILTIN_OUTBOUND_TAGS
            && v2rayConfig.outbounds.none { it.tag == outboundTag }
        ) {
            LogUtil.w(AppConfig.TAG, "Outbound tag '$outboundTag' not found, falling back to '${AppConfig.TAG_PROXY}'")
            rule.outboundTag = AppConfig.TAG_PROXY
        }

        v2rayConfig.routing.rules.add(rule)
    }


    /**
     * Build balancer and probe settings from one policy-group strategy value.
     */
    private fun buildBalancerStrategy(
        strategyType: BalancerStrategyType,
        selector: List<String>,
        balancerTag: String = AppConfig.TAG_BALANCER,
        fallbackTag: String? = null,
    ): BalancerStrategy {
        val probeUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL) ?: AppConfig.DELAY_TEST_URL
        val leastPingInterval = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL)
        val leastLoadInterval = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL)
        val leastLoadMethod = MmkvManager.decodeSettingsString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD)
        val leastLoadSampling = decodeObservatorySampling()
        val leastLoadTimeout = decodeObservatoryDuration(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT)
        val balancer = V2rayConfig.RoutingBean.BalancerBean(
            tag = balancerTag,
            selector = selector,
            fallbackTag = fallbackTag,
            strategy = V2rayConfig.RoutingBean.StrategyObject(type = strategyType.policyGroupType)
        )
        val observatory = if (strategyType.requiresObservatory || fallbackTag != null) {
            V2rayConfig.ObservatoryObject(
                subjectSelector = selector,
                probeUrl = probeUrl,
                probeInterval = leastPingInterval,
                enableConcurrency = true
            )
        } else null
        val burstObservatory = if (strategyType.requiresBurstObservatory) {
            V2rayConfig.BurstObservatoryObject(
                subjectSelector = selector,
                pingConfig = V2rayConfig.BurstObservatoryObject.PingConfigObject(
                    destination = probeUrl,
                    httpMethod = leastLoadMethod,
                    interval = leastLoadInterval,
                    sampling = leastLoadSampling,
                    timeout = leastLoadTimeout
                )
            )
        } else null
        return BalancerStrategy(balancer, observatory, burstObservatory)
    }

    private fun decodeObservatoryDuration(key: String, default: String): String {
        val value = MmkvManager.decodeSettingsString(key)?.trim()
        return if (!value.isNullOrEmpty() && AppConfig.OBSERVATORY_DURATION_PATTERN.matches(value)) {
            value
        } else {
            default
        }
    }

    private fun decodeObservatorySampling(): Int {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING)
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING.toInt()
    }

    /**
     * Carry balancer data plus optional probe settings for later merge.
     */
    private data class BalancerStrategy(
        val balancer: V2rayConfig.RoutingBean.BalancerBean,
        val observatory: V2rayConfig.ObservatoryObject? = null,
        val burstObservatory: V2rayConfig.BurstObservatoryObject? = null,
    )

    //endregion
}
