package com.v2ray.ang.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rkh.vpn.service.SimorghPublicVpnService
import com.v2ray.ang.AngApplication
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.RealPingWorkerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** PingNG controls around the SIMORGH RKh-MSP engine and address privacy boundary. */
object MspManager {
    const val MODE_DIRECT = "direct"
    const val MODE_CLOUDFLARE = "cloudflare"
    private const val APP_PREFS = "msp_pingng_state"
    private const val ENGINE_PREFS = "simorgh_public_state"
    private const val MAX_CACHED_ADDRESSES = 300
    private const val DIRECT_PROXY_SOCKS_PORT = 9990
    private const val DIRECT_PROXY_HTTP_PORT = 9991
    private const val DEFAULT_MAX_SCAN_IPS = 33000
    private const val MIN_SCAN_WORKERS = 1
    private const val MAX_SCAN_WORKERS = 1000
    private const val SCAN_TIMEOUT_MS = 40 * 60 * 1000L

    private val ipv4Literal = Regex(
        "^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}" +
            "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )
    private val directMonitors = ConcurrentHashMap<String, Job>()
    private data class AddressSnapshot(val guid: String, val mode: String, val addresses: List<String>)
    private val addressSnapshots = ConcurrentHashMap<String, AddressSnapshot>()

    data class ScannerSettings(
        val selectedIsps: List<String>,
        val selectedSnis: List<String>,
        val scanPort: Int,
        val maxScanIps: Int,
        val scanSpeed: String,
        val scanWorkers: Int,
        val manualCleanMode: Boolean,
        val manualCleanIps: String,
        val manualRangeMode: Boolean,
        val manualRanges: String,
        val proxyProtocol: String,
        val routeStrategy: String,
    )

    /** Raw addresses are exposed only to the MSP editor, never to the main screen or logs. */
    data class AddressRow(val label: String, val address: String, val delayMillis: Long?, val selectionToken: String)

    data class ScanSnapshot(
        val scanned: Int = 0,
        val total: Int = 0,
        val found: Int = 0,
        val running: Boolean = false,
        val complete: Boolean = false,
        val status: String = "",
    )

    private fun appPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)

    private fun enginePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)

    private fun profileKey(guid: String, suffix: String) = "profile:$guid:$suffix"

    fun isSupported(profile: ProfileItem?): Boolean {
        val item = profile ?: return false
        return item.configType == EConfigType.VLESS &&
            item.network.equals(NetworkType.WS.type, ignoreCase = true) &&
            item.security.equals("tls", ignoreCase = true)
    }

    fun mode(context: Context, guid: String): String =
        appPrefs(context).getString(profileKey(guid, "mode"), MODE_DIRECT)
            .orEmpty().let { if (it == MODE_CLOUDFLARE) MODE_CLOUDFLARE else MODE_DIRECT }

    fun setMode(context: Context, guid: String, mode: String) {
        if (guid.isBlank()) return
        appPrefs(context).edit()
            .putString(profileKey(guid, "mode"), if (mode == MODE_CLOUDFLARE) MODE_CLOUDFLARE else MODE_DIRECT)
            .commit()
    }

    fun scanBeforeConnect(context: Context, guid: String): Boolean =
        appPrefs(context).getBoolean(profileKey(guid, "${mode(context, guid)}:scan_before_connect"),
            appPrefs(context).getBoolean(profileKey(guid, "scan_before_connect"), true))

    fun pingBeforeConnect(context: Context, guid: String): Boolean =
        appPrefs(context).getBoolean(profileKey(guid, "${mode(context, guid)}:ping_before_connect"),
            appPrefs(context).getBoolean(profileKey(guid, "ping_before_connect"), false))

    fun setPingBeforeConnect(context: Context, guid: String, enabled: Boolean) {
        if (guid.isNotBlank()) appPrefs(context).edit()
            .putBoolean(profileKey(guid, "${mode(context, guid)}:ping_before_connect"), enabled).commit()
    }

    fun isPrivacyActive(context: Context): Boolean = appPrefs(context).getBoolean("privacy_active", false)

    fun shouldRedactLogs(context: Context): Boolean =
        isPrivacyActive(context) || scanInProgress(context) || hasActiveSession(context)

    fun hasActiveSession(context: Context): Boolean {
        if (!appPrefs(context).getString("runtime_guid", null).isNullOrBlank()) return true
        val engine = enginePrefs(context)
        return engine.getBoolean("connecting", false) ||
            (engine.getBoolean("connected", false) && engine.getString("activeMode", "") == "proxy")
    }

    fun setPrivacyActive(context: Context, active: Boolean) {
        val editor = appPrefs(context).edit().putBoolean("privacy_active", active)
        if (active) {
            val prefs = appPrefs(context)
            editor.putInt("privacy_generation", prefs.getInt("privacy_generation", 0) + 1)
        }
        editor.commit()
    }

    fun setScanBeforeConnect(context: Context, guid: String, enabled: Boolean) {
        if (guid.isBlank()) return
        appPrefs(context).edit().putBoolean(profileKey(guid, "${mode(context, guid)}:scan_before_connect"), enabled).commit()
    }

    fun loadScannerSettings(context: Context): ScannerSettings {
        val prefs = appPrefs(context)
        val selectedIsps = prefs.getString("selected_isps", null)
            ?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.distinctBy { it.lowercase(Locale.US) }?.toList()
            .orEmpty().ifEmpty { listOf(defaultIsp(context)) }
        val selectedSnis = prefs.getString("selected_snis", null)
            ?.lineSequence()?.flatMap { it.split(',', ';').asSequence() }
            ?.map { it.trim().lowercase(Locale.US) }?.filter(String::isNotBlank)?.distinct()?.toList()
            .orEmpty().ifEmpty { listOf("chatgpt.com") }
        val scanSpeed = prefs.getString("scan_speed", "normal").orEmpty()
            .takeIf { it in setOf("slow", "normal", "fast", "custom") } ?: "normal"
        val defaultWorkers = when (scanSpeed) {
            "slow" -> 48
            "fast" -> 400
            else -> 160
        }
        return ScannerSettings(
            selectedIsps = selectedIsps,
            selectedSnis = selectedSnis,
            scanPort = prefs.getInt("scan_port", 443).coerceIn(1, 65535),
            maxScanIps = prefs.getInt("max_scan_ips", 0).coerceIn(0, DEFAULT_MAX_SCAN_IPS),
            scanSpeed = scanSpeed,
            scanWorkers = prefs.getInt("scan_workers", defaultWorkers).coerceIn(MIN_SCAN_WORKERS, MAX_SCAN_WORKERS),
            manualCleanMode = prefs.getBoolean("manual_clean_mode", false),
            manualCleanIps = prefs.getString("manual_clean_ips", "").orEmpty(),
            manualRangeMode = prefs.getBoolean("manual_range_mode", false),
            manualRanges = prefs.getString("manual_ranges", "").orEmpty(),
            proxyProtocol = prefs.getString("proxy_protocol", "socks5").orEmpty().let { if (it == "http") "http" else "socks5" },
            routeStrategy = prefs.getString("route_strategy", "default").orEmpty().let(::normalizeRouteStrategy),
        )
    }

    fun saveScannerSettings(context: Context, settings: ScannerSettings) {
        val selectedIsps = settings.selectedIsps.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase(Locale.US) }
        val selectedSnis = settings.selectedSnis.flatMap { it.split(',', ';', '\n') }
            .map { it.trim().lowercase(Locale.US) }.filter(String::isNotBlank).distinct()
        appPrefs(context).edit()
            .putString("selected_isps", selectedIsps.joinToString("\n"))
            .putString("selected_snis", selectedSnis.joinToString("\n"))
            .putInt("scan_port", settings.scanPort.coerceIn(1, 65535))
            .putInt("max_scan_ips", settings.maxScanIps.coerceIn(0, DEFAULT_MAX_SCAN_IPS))
            .putString("scan_speed", settings.scanSpeed.takeIf { it in setOf("slow", "normal", "fast", "custom") } ?: "normal")
            .putInt("scan_workers", settings.scanWorkers.coerceIn(MIN_SCAN_WORKERS, MAX_SCAN_WORKERS))
            .putBoolean("manual_clean_mode", settings.manualCleanMode)
            .putString("manual_clean_ips", settings.manualCleanIps.trim())
            .putBoolean("manual_range_mode", settings.manualRangeMode)
            .putString("manual_ranges", settings.manualRanges.trim())
            .putString("proxy_protocol", if (settings.proxyProtocol == "http") "http" else "socks5")
            .putString("route_strategy", normalizeRouteStrategy(settings.routeStrategy))
            .commit()
    }

    fun availableIsps(context: Context): List<String> {
        val out = linkedSetOf<String>()
        runCatching {
            readShieldedRanges(context).lineSequence().drop(1).forEach { line ->
                csvColumns(line).getOrNull(6)?.trim()?.takeIf(String::isNotBlank)?.let(out::add)
            }
        }
        runCatching {
            context.assets.open("msp_custom_ranges.csv").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    if (line.isNotBlank() && !line.trimStart().startsWith("#")) {
                        csvColumns(line).getOrNull(6)?.trim()?.takeIf(String::isNotBlank)?.let(out::add)
                    }
                }
            }
        }
        if (out.none { it.equals("Auto", ignoreCase = true) }) out.add("Auto")
        return out.toList().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    fun availableSnis(context: Context): List<String> = runCatching {
        context.assets.open("msp_sni_catalog.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .distinct().toList()
        }
    }.getOrDefault(listOf("chatgpt.com"))

    private fun defaultIsp(context: Context): String {
        val options = availableIsps(context)
        return options.firstOrNull { it.equals("AbrArvan CDN and IaaS", ignoreCase = true) }
            ?: options.firstOrNull { !it.equals("Auto", ignoreCase = true) }
            ?: "Auto"
    }

    fun addressRows(context: Context, guid: String, cloudflare: Boolean, profile: ProfileItem? = null): List<AddressRow> {
        val selectedMode = if (cloudflare) MODE_CLOUDFLARE else MODE_DIRECT
        val ips = loadCandidates(context, guid, selectedMode)
        if (cloudflare && profile != null) ensurePingCacheForProfile(context, guid, profile)
        val pings = if (cloudflare) loadScanPings(context, guid, MODE_CLOUDFLARE) + loadRealPings(context, guid)
            else loadScanPings(context, guid, MODE_DIRECT)
        val snapshotId = UUID.randomUUID().toString()
        addressSnapshots[snapshotId] = AddressSnapshot(guid, selectedMode, ips)
        while (addressSnapshots.size > 64) {
            val candidate = addressSnapshots.keys.firstOrNull() ?: break
            addressSnapshots.remove(candidate)
        }
        return ips.withIndex()
            .sortedWith(compareBy<IndexedValue<String>> { entry ->
                pings[entry.value]?.takeIf { it >= 0L } ?: Long.MAX_VALUE
            }.thenBy { it.index })
            .mapIndexed { displayIndex, entry ->
                AddressRow("Address-${displayIndex + 1}", entry.value, pings[entry.value], "$snapshotId:${entry.index}")
            }
    }

    fun addressCount(context: Context, guid: String, mode: String): Int = loadCandidates(context, guid, mode).size

    fun preferredSavedCloudflareAddress(context: Context, guid: String, profile: ProfileItem): String? {
        ensurePingCacheForProfile(context, guid, profile)
        val candidates = loadCandidates(context, guid, MODE_CLOUDFLARE)
        val real = loadRealPings(context, guid)
        val scan = loadScanPings(context, guid, MODE_CLOUDFLARE)
        return candidates.filter { (real[it] ?: -1L) >= 0L }.minByOrNull { real[it] ?: Long.MAX_VALUE }
            ?: candidates.filter { (scan[it] ?: -1L) >= 0L }
                .minByOrNull { scan[it] ?: Long.MAX_VALUE }
            ?: candidates.firstOrNull()
    }

    fun clearSavedAddresses(context: Context, guid: String, selectedMode: String) {
        if (guid.isBlank()) return
        val mode = if (selectedMode == MODE_CLOUDFLARE) MODE_CLOUDFLARE else MODE_DIRECT
        val prefs = appPrefs(context)
        val ownerMatches = prefs.getString("scan_owner_guid", "") == guid &&
            prefs.getString("scan_owner_mode", "") == mode
        val key = profileKey(guid, "$mode:scan")
        val editor = prefs.edit()
            .remove(profileKey(guid, "$mode:addresses"))
            .remove(profileKey(guid, "$mode:scan_pings"))
            .putInt("$key:scanned", 0)
            .putInt("$key:total", 0)
            .putInt("$key:found", 0)
            .putBoolean("$key:complete", false)
        if (mode == MODE_CLOUDFLARE) editor.remove(profileKey(guid, "real_pings"))
        if (ownerMatches && !scanInProgress(context, guid, mode)) {
            editor.putString("scan_owner_guid", "").putString("scan_owner_mode", "")
        }
        editor.commit()
        addressSnapshots.entries.forEach { entry ->
            if (entry.value.guid == guid && entry.value.mode == mode) addressSnapshots.remove(entry.key, entry.value)
        }
    }

    fun firstCurrentFoundAddress(context: Context, guid: String, selectedMode: String): String? {
        return currentScanCandidates(context, guid, selectedMode).firstOrNull()
    }

    private fun currentScanCandidates(context: Context, guid: String, selectedMode: String): List<String> {
        val prefs = appPrefs(context)
        if (prefs.getString("scan_owner_guid", "") != guid || prefs.getString("scan_owner_mode", "") != selectedMode) return emptyList()
        return readEngineCandidates(context)
    }

    fun queueAddressConnection(context: Context, guid: String, selectionToken: String) {
        val parts = selectionToken.split(':', limit = 2)
        val snapshot = parts.firstOrNull()?.let(addressSnapshots::get)
            ?.takeIf { it.guid == guid && it.mode == mode(context, guid) } ?: return
        val address = parts.getOrNull(1)?.toIntOrNull()?.let { snapshot.addresses.getOrNull(it) } ?: return
        queueConnection(context, guid, address)
    }

    fun scanSnapshot(context: Context, guid: String, selectedMode: String): ScanSnapshot {
        val prefs = appPrefs(context)
        val mode = if (selectedMode == MODE_CLOUDFLARE) MODE_CLOUDFLARE else MODE_DIRECT
        val key = profileKey(guid, "$mode:scan")
        val owned = prefs.getString("scan_owner_guid", "") == guid &&
            prefs.getString("scan_owner_mode", "") == mode
        if (owned) {
            val engine = enginePrefs(context)
            val baseline = prefs.getInt("scan_baseline_generation", -1)
            val started = engine.getInt("scanGeneration", 0) != baseline
            if (started) {
                val foundNow = parseAddressText(engine.getString("scanFoundIps", "").orEmpty())
                val manual = loadScannerSettings(context).takeIf { it.manualCleanMode }
                val newCandidates = if (manual != null && engine.getBoolean("scanComplete", false))
                    parseAddressText(manual.manualCleanIps) else foundNow
                if (newCandidates.isNotEmpty()) {
                    val old = loadCandidates(context, guid, mode)
                    val merged = (newCandidates + old).distinct().take(MAX_CACHED_ADDRESSES)
                    if (merged != old) saveCandidates(context, guid, mode, merged)
                    if (mode == MODE_DIRECT || mode == MODE_CLOUDFLARE) {
                        val scanPings = loadScanPings(context, guid, mode) +
                            loadEnginePings(context).filterKeys { it in newCandidates }
                        saveScanPings(context, guid, mode, scanPings)
                    }
                }
                val scanned = maxOf(engine.getInt("scannedCount", 0), prefs.getInt("$key:scanned", 0)).coerceAtLeast(0)
                val total = maxOf(engine.getInt("totalCandidates", 0), prefs.getInt("$key:total", 0)).coerceAtLeast(0)
                val complete = engine.getBoolean("scanComplete", false)
                val stopped = complete && engine.getBoolean("scanStopped", false)
                val found = maxOf(newCandidates.size, prefs.getInt("$key:found", 0))
                prefs.edit().putInt("$key:scanned", scanned).putInt("$key:total", total)
                    .putInt("$key:found", found).putBoolean("$key:complete", complete).commit()
                return ScanSnapshot(scanned, total, found, !complete, complete,
                    if (stopped) "stopped" else if (complete) "complete" else if (total > 0) "scanning" else "starting")
            }
            return ScanSnapshot(running = true, status = "starting")
        }
        return ScanSnapshot(
            scanned = prefs.getInt("$key:scanned", 0),
            total = prefs.getInt("$key:total", 0),
            found = prefs.getInt("$key:found", loadCandidates(context, guid, mode).size),
            complete = prefs.getBoolean("$key:complete", false),
        )
    }

    fun scanInProgress(context: Context): Boolean {
        val prefs = appPrefs(context)
        if (prefs.getString("scan_owner_guid", "").isNullOrBlank()) return false
        if (System.currentTimeMillis() - prefs.getLong("scan_started_at", 0L) > SCAN_TIMEOUT_MS) return false
        val baseline = prefs.getInt("scan_baseline_generation", -1)
        val engine = enginePrefs(context)
        return engine.getInt("scanGeneration", 0) == baseline || !engine.getBoolean("scanComplete", false)
    }

    fun scanInProgress(context: Context, guid: String, selectedMode: String): Boolean {
        val prefs = appPrefs(context)
        val normalizedMode = if (selectedMode == MODE_CLOUDFLARE) MODE_CLOUDFLARE else MODE_DIRECT
        return prefs.getString("scan_owner_guid", "") == guid &&
            prefs.getString("scan_owner_mode", "") == normalizedMode &&
            scanInProgress(context)
    }

    fun scanSnapshotForProfile(context: Context, guid: String): ScanSnapshot? {
        val prefs = appPrefs(context)
        if (prefs.getString("scan_owner_guid", "") != guid) return null
        val ownerMode = prefs.getString("scan_owner_mode", "").orEmpty()
        if (ownerMode != MODE_DIRECT && ownerMode != MODE_CLOUDFLARE) return null
        return scanSnapshot(context, guid, ownerMode)
    }

    fun startScan(context: Context, guid: String, profile: ProfileItem, selectedMode: String) {
        require(isSupported(profile) && profile.mspEnabled) { "MSP requires an enabled VLESS WS/TLS profile" }
        if (scanInProgress(context, guid, selectedMode)) return
        val settings = loadScannerSettings(context)
        val effective = if (selectedMode == MODE_CLOUDFLARE) cloudflareScanSettings(settings, profile) else settings
        startManagedScan(context, guid, selectedMode, effective)
    }

    fun stopScan(context: Context, guid: String, selectedMode: String) {
        if (!scanInProgress(context, guid, selectedMode)) return
        runCatching {
            context.startService(Intent(context, SimorghPublicVpnService::class.java)
                .setAction(SimorghPublicVpnService.ACTION_STOP_SCAN))
        }
    }

    suspend fun awaitCurrentScan(context: Context, guid: String, selectedMode: String) {
        val prefs = appPrefs(context)
        require(prefs.getString("scan_owner_guid", "") == guid &&
            prefs.getString("scan_owner_mode", "") == selectedMode) { "MSP scan ownership changed" }
        awaitScanFinished(context, prefs.getInt("scan_baseline_generation", -1), guid, selectedMode)
        require(prefs.getString("scan_owner_guid", "") == guid &&
            prefs.getString("scan_owner_mode", "") == selectedMode) { "MSP scan ownership changed" }
        scanSnapshot(context, guid, selectedMode)
        if (selectedMode == MODE_CLOUDFLARE) stopProxyScanner(context)
    }

    /** Starts the configured scan and selects the connection behavior without mutating the VLESS address. */
    suspend fun prepareConnection(
        context: Context,
        guid: String,
        profile: ProfileItem,
        explicitCandidate: String? = null,
        onPingProgress: ((Int, Int) -> Unit)? = null,
    ) {
        require(isSupported(profile) && profile.mspEnabled) { "MSP is only available for enabled VLESS WS/TLS profiles" }
        ensurePingCacheForProfile(context, guid, profile)
        setPrivacyActive(context, true)
        val selectedMode = mode(context, guid)
        val autoScan = scanBeforeConnect(context, guid)
        val settings = loadScannerSettings(context)
        val scanOwner = appPrefs(context)
        val scanIsForThisMode = scanOwner.getString("scan_owner_guid", "") == guid &&
            scanOwner.getString("scan_owner_mode", "") == selectedMode
        check(!scanInProgress(context) || scanIsForThisMode) { "Another MSP scan is running" }
        scanSnapshot(context, guid, selectedMode)
        if (!explicitCandidate.isNullOrBlank()) {
            require(isValidIpv4(explicitCandidate) &&
                explicitCandidate in loadCandidates(context, guid, selectedMode)) { "Selected MSP address is invalid" }
            check(!scanInProgress(context) || scanInProgress(context, guid, selectedMode)) {
                "Another MSP scan is running"
            }
            val prefs = appPrefs(context)
            if (selectedMode == MODE_DIRECT) {
                if (pingBeforeConnect(context, guid)) {
                    pingDirectCandidates(context, guid, listOf(explicitCandidate), settings, onPingProgress)
                }
                val engine = enginePrefs(context)
                val activeDirectPool = appPrefs(context).getString("scan_owner_mode", "") == MODE_DIRECT &&
                    explicitCandidate in engineCachedCandidates(context)
                if (!engine.getBoolean("connected", false) || engine.getString("activeMode", "") != "proxy" || !activeDirectPool) {
                    startProxyFromSaved(context, loadCandidates(context, guid, MODE_DIRECT), settings)
                }
                selectDirectRoute(context, explicitCandidate)
                setRuntimeOverride(context, guid, MODE_DIRECT, "")
                if (scanInProgress(context) && prefs.getString("scan_owner_guid", "") == guid &&
                    prefs.getString("scan_owner_mode", "") == MODE_DIRECT
                ) launchDirectScanSnapshot(context.applicationContext, guid, prefs.getInt("scan_baseline_generation", -1))
            } else {
                val cached = loadRealPings(context, guid).toMutableMap()
                if (pingBeforeConnect(context, guid)) {
                    val ping = cached[explicitCandidate]?.takeIf { it >= 0L }
                        ?: pingCandidates(context, profile, listOf(explicitCandidate), onPingProgress, guid)[explicitCandidate]
                    cached[explicitCandidate] = ping ?: -1L
                    saveRealPings(context, guid, cached)
                    require(ping != null && ping >= 0L) { "The selected MSP address did not pass the VLESS real-delay check" }
                }
                stopProxyIfRunning(context)
                setRuntimeOverride(context, guid, MODE_CLOUDFLARE, explicitCandidate)
            }
            if (autoScan) prefs.edit().putBoolean("scan_consumed", true).commit()
            return
        }

        var candidates = loadCandidates(context, guid, selectedMode)
        if (selectedMode == MODE_DIRECT) {
            val prefs = appPrefs(context)
            val directScanActive = scanInProgress(context, guid, MODE_DIRECT)
            if (autoScan && directScanActive) {
                val previousScanGeneration = prefs.getInt("scan_baseline_generation", -1)
                val selected = awaitFirstUsableAddress(context, settings, ownerGuid = guid, ownerMode = MODE_DIRECT)
                scanSnapshot(context, guid, MODE_DIRECT)
                saveCandidateIfMissing(context, guid, MODE_DIRECT, selected)
                setRuntimeOverride(context, guid, MODE_DIRECT, "")
                launchDirectScanSnapshot(context.applicationContext, guid, previousScanGeneration)
                return
            }
            if (autoScan && !directScanActive) {
                val previousScanGeneration = startManagedScan(context, guid, MODE_DIRECT, settings)
                val selected = awaitFirstUsableAddress(context, settings, ownerGuid = guid, ownerMode = MODE_DIRECT)
                scanSnapshot(context, guid, MODE_DIRECT)
                saveCandidateIfMissing(context, guid, MODE_DIRECT, selected)
                setRuntimeOverride(context, guid, MODE_DIRECT, "")
                appPrefs(context).edit().putBoolean("scan_consumed", true).commit()
                launchDirectScanSnapshot(context.applicationContext, guid, previousScanGeneration)
                return
            }

            check(!scanInProgress(context) || directScanActive) { "Another MSP scan is running" }
            val activeDirectPool = directScanActive &&
                enginePrefs(context).getBoolean("connected", false) &&
                enginePrefs(context).getString("activeMode", "") == "proxy" &&
                candidates.any { it in engineCachedCandidates(context) }
            if (!activeDirectPool) {
                if (pingBeforeConnect(context, guid)) {
                    pingDirectCandidates(context, guid, candidates, settings, onPingProgress)
                }
                startProxyFromSaved(context, candidates, settings)
            }
            setRuntimeOverride(context, guid, MODE_DIRECT, "")
            if (directScanActive) {
                launchDirectScanSnapshot(context.applicationContext, guid, prefs.getInt("scan_baseline_generation", -1))
            }
            return
        }

        if (autoScan && scanInProgress(context, guid, MODE_CLOUDFLARE)) {
            val prefs = appPrefs(context)
            val previousScanGeneration = prefs.getInt("scan_baseline_generation", -1)
            awaitScanFinished(context, previousScanGeneration, guid, MODE_CLOUDFLARE)
            scanSnapshot(context, guid, MODE_CLOUDFLARE)
            val scannedCandidates = readEngineCandidates(context)
            if (scannedCandidates.isEmpty()) {
                stopProxyScanner(context)
                error("MSP did not find a usable address")
            }
            saveCandidates(context, guid, MODE_CLOUDFLARE, scannedCandidates)
            candidates = scannedCandidates
            stopProxyScanner(context)
            configureEngine(context, settings)
        } else if (autoScan) {
            val prefs = appPrefs(context)
            val previousScanGeneration = startManagedScan(context, guid, MODE_CLOUDFLARE, cloudflareScanSettings(settings, profile))
            awaitScanFinished(context, previousScanGeneration, guid, MODE_CLOUDFLARE)
            scanSnapshot(context, guid, MODE_CLOUDFLARE)
            val scannedCandidates = readEngineCandidates(context)
            if (scannedCandidates.isEmpty()) {
                stopProxyScanner(context)
                error("MSP did not find a usable address")
            }
            saveCandidates(context, guid, MODE_CLOUDFLARE, scannedCandidates)
            candidates = scannedCandidates
            stopProxyScanner(context)
            configureEngine(context, settings)
            prefs.edit().putBoolean("scan_consumed", true).commit()
        }
        if (candidates.isNotEmpty()) stopProxyIfRunning(context)
        if (candidates.isEmpty()) error("No saved MSP addresses. Enable scanning once before connecting.")
        var realPings = loadRealPings(context, guid)
        if (pingBeforeConnect(context, guid)) {
            val measured = pingCandidates(context, profile, candidates, onPingProgress, guid)
            realPings = realPings + measured
            saveRealPings(context, guid, realPings)
        }
        val best = candidates.mapNotNull { ip -> realPings[ip]?.takeIf { it >= 0L }?.let { ip to it } }
            .minByOrNull { it.second }?.first
            ?: if (!pingBeforeConnect(context, guid)) preferredSavedCloudflareAddress(context, guid, profile)
                ?: error("No saved MSP address is available")
            else error("No MSP address passed the VLESS real-delay check")
        setRuntimeOverride(context, guid, MODE_CLOUDFLARE, best)
        if (autoScan) appPrefs(context).edit().putBoolean("scan_consumed", true).commit()
    }

    /** Re-tests every saved address with an isolated clone of the selected VLESS profile. */
    suspend fun pingAll(context: Context, guid: String, profile: ProfileItem, onProgress: (Int, Int) -> Unit = { _, _ -> }): Map<String, Long> {
        require(isSupported(profile)) { "MSP ping needs a VLESS WS/TLS profile" }
        val keepPrivacyActive = isPrivacyActive(context)
        setPrivacyActive(context, true)
        return try {
            ensurePingCacheForProfile(context, guid, profile)
            val ips = loadCandidates(context, guid, MODE_CLOUDFLARE)
            if (ips.isEmpty()) error("No saved MSP addresses to ping")
            pingCandidates(context, profile, ips, onProgress, guid).also { saveRealPings(context, guid, it) }
        } finally {
            if (!keepPrivacyActive) setPrivacyActive(context, false)
        }
    }

    /** Checks the fresh MSP results for an automatic connection, leaving older saved results available in the editor. */
    suspend fun pingForConnection(
        context: Context,
        guid: String,
        profile: ProfileItem,
        freshScan: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Map<String, Long> {
        require(isSupported(profile)) { "MSP ping needs a VLESS WS/TLS profile" }
        val keepPrivacyActive = isPrivacyActive(context)
        setPrivacyActive(context, true)
        return try {
            ensurePingCacheForProfile(context, guid, profile)
            val ips = if (freshScan) {
                val saved = loadCandidates(context, guid, MODE_CLOUDFLARE)
                saved.ifEmpty { currentScanCandidates(context, guid, MODE_CLOUDFLARE) }
            } else loadCandidates(context, guid, MODE_CLOUDFLARE)
            if (ips.isEmpty()) error("MSP did not find a usable address")
            if (freshScan) saveCandidates(context, guid, MODE_CLOUDFLARE, ips)
            val cached = loadRealPings(context, guid)
            val missing = ips.filterNot { it in cached }
            val measured = if (missing.isEmpty()) emptyMap() else pingCandidates(context, profile, missing, onProgress, guid)
            val results = (cached + measured).filterKeys { it in ips }
            if (measured.isNotEmpty()) saveRealPings(context, guid, cached + measured)
            if (missing.isEmpty()) onProgress(ips.size, ips.size)
            results
        } finally {
            if (!keepPrivacyActive) setPrivacyActive(context, false)
        }
    }

    private suspend fun pingCandidates(
        context: Context,
        profile: ProfileItem,
        ips: List<String>,
        onProgress: ((Int, Int) -> Unit)?,
        liveGuid: String? = null,
    ): Map<String, Long> = withContext(Dispatchers.IO) {
        val all = linkedMapOf<String, Long>()
        val published = mutableMapOf<String, Long>()
        fun publish(ip: String, delay: Long): Unit = synchronized(published) {
            if (published.put(ip, delay) == null) {
                if (liveGuid != null) saveRealPings(context, liveGuid, loadRealPings(context, liveGuid) + (ip to delay))
                onProgress?.invoke(published.size, ips.size)
            }
        }
        onProgress?.invoke(0, ips.size)
        ips.filter(::isValidIpv4).distinct().chunked(12).forEach { batch ->
            currentCoroutineContext().let { if (!it.isActive) error("MSP ping cancelled") }
            val guidToIp = linkedMapOf<String, String>()
            batch.forEach { ip ->
                val temporaryGuid = "msp-probe-${UUID.randomUUID()}"
                val testProfile = profile.copy(
                    server = ip,
                    mspEnabled = false,
                    subscriptionId = "",
                    managedBy = null,
                )
                MmkvManager.encodeEphemeralServerConfig(temporaryGuid, testProfile)
                guidToIp[temporaryGuid] = ip
            }
            val tested = try {
                awaitRealPingBatch(context, guidToIp, ::publish)
            } finally {
                guidToIp.keys.forEach(MmkvManager::removeEphemeralServerConfig)
            }
            batch.forEach { ip -> all[ip] = tested[ip] ?: -1L }
            batch.forEach { publish(it, tested[it] ?: -1L) }
        }
        all
    }

    /** Direct addresses use MSP's TCP endpoint port, independently of VLESS real-delay results. */
    private suspend fun pingDirectCandidates(
        context: Context,
        guid: String,
        addresses: List<String>,
        settings: ScannerSettings,
        onProgress: ((Int, Int) -> Unit)?,
    ) = withContext(Dispatchers.IO) {
        val candidates = addresses.filter(::isValidIpv4).distinct()
        val results = loadScanPings(context, guid, MODE_DIRECT).toMutableMap()
        onProgress?.invoke(0, candidates.size)
        var completed = 0
        candidates.chunked(12).forEach { batch ->
            batch.map { ip -> async {
                val start = System.nanoTime()
                val delay = runCatching {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(ip, settings.scanPort), 1500)
                    }
                    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
                }.getOrDefault(-1L)
                ip to delay
            } }.awaitAll().forEach { (ip, delay) ->
                results[ip] = delay
                saveScanPings(context, guid, MODE_DIRECT, results)
                onProgress?.invoke(++completed, candidates.size)
            }
        }
    }

    private suspend fun awaitRealPingBatch(
        context: Context,
        guidToIp: Map<String, String>,
        publish: (String, Long) -> Unit,
    ): Map<String, Long> = suspendCancellableCoroutine { continuation ->
        val batchResult = ConcurrentHashMap<String, Long>()
        val finishOnce = AtomicBoolean(false)
        val worker = RealPingWorkerService(context, guidToIp.keys.toList()) { event ->
            when (event) {
                is RealPingEvent.Result -> guidToIp[event.guid]?.let { ip -> batchResult[ip] = event.delayMillis; publish(ip, event.delayMillis) }
                is RealPingEvent.Finish -> if (finishOnce.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(batchResult.toMap())
                }
                is RealPingEvent.Progress -> Unit
            }
        }
        continuation.invokeOnCancellation { worker.cancel() }
        worker.start()
    }

    private fun cloudflareScanSettings(settings: ScannerSettings, profile: ProfileItem): ScannerSettings {
        val scanSni = profile.sni?.trim()?.takeIf(String::isNotBlank)
            ?: profile.host?.trim()?.takeIf(String::isNotBlank)
            ?: profile.server?.trim()?.takeUnless(::isValidIpv4)
            ?: settings.selectedSnis.firstOrNull().orEmpty()
        return settings.copy(
            selectedSnis = listOf(scanSni),
            scanPort = profile.serverPort?.toIntOrNull()?.coerceIn(1, 65535) ?: 443,
        )
    }

    @Synchronized
    private fun startManagedScan(context: Context, guid: String, mode: String, settings: ScannerSettings): Int {
        check(!scanInProgress(context)) { "Another MSP scan is running" }
        directMonitors.values.forEach { it.cancel() }
        directMonitors.clear()
        clearSavedAddresses(context, guid, mode)
        val baseline = enginePrefs(context).getInt("scanGeneration", 0)
        appPrefs(context).edit()
            .putString("scan_owner_guid", guid)
            .putString("scan_owner_mode", mode)
            .putInt("scan_baseline_generation", baseline)
            .putLong("scan_started_at", System.currentTimeMillis())
            .putBoolean("scan_consumed", false)
            .putInt("${profileKey(guid, "$mode:scan")}:scanned", 0)
            .putInt("${profileKey(guid, "$mode:scan")}:total", 0)
            .putInt("${profileKey(guid, "$mode:scan")}:found", 0)
            .putBoolean("${profileKey(guid, "$mode:scan")}:complete", false)
            .commit()
        configureEngine(context, settings, seedCleanIps = emptyList())
        startProxyScanner(context)
        return baseline
    }

    private fun configureEngine(context: Context, settings: ScannerSettings, seedCleanIps: List<String>? = null) {
        val engine = enginePrefs(context)
        val isps = settings.selectedIsps.ifEmpty { listOf(defaultIsp(context)) }
        val snis = settings.selectedSnis.map { it.trim().lowercase(Locale.US) }.filter(String::isNotBlank).ifEmpty { listOf("chatgpt.com") }
        val editor = engine.edit()
            .putBoolean("scanStopRequested", false)
            .putBoolean("scanStopped", false)
            .putString("selectedIspsCsv", isps.joinToString("\n"))
            .putStringSet("selectedIsps", isps.toSet())
            .putString("selectedIsp", isps.first())
            .putString("selectedSnisCsv", snis.joinToString("\n"))
            .putStringSet("selectedSnis", snis.toSet())
            .putInt("selectedPort", settings.scanPort.coerceIn(1, 65535))
            .putInt("maxScanIps", settings.maxScanIps.coerceIn(0, DEFAULT_MAX_SCAN_IPS))
            .putString("scanSpeed", settings.scanSpeed)
            .putInt("scanWorkers", settings.scanWorkers.coerceIn(MIN_SCAN_WORKERS, MAX_SCAN_WORKERS))
            .putBoolean("manualIpMode", settings.manualCleanMode)
            .putString("manualIpsText", settings.manualCleanIps)
            .putBoolean("ispManualRangeMode", settings.manualRangeMode)
            .putString("ispManualRangeText", settings.manualRanges)
            .putString("selectedProxyProtocol", if (settings.proxyProtocol == "http") "http" else "socks5")
            .putString("routeStrategy", normalizeRouteStrategy(settings.routeStrategy))
        if (seedCleanIps != null) {
            editor.putString("savedCleanIps", seedCleanIps.filter(::isValidIpv4).distinct().joinToString("\n"))
                .putString("savedCleanIpPings", "")
                .putString("activeRouteIp", "")
                .putBoolean("manualRouteLock", false)
        }
        editor.commit()
    }

    private suspend fun selectDirectRoute(context: Context, ip: String) {
        enginePrefs(context).edit().remove("manualRouteSelectionAck").commit()
        context.startService(Intent(context, SimorghPublicVpnService::class.java)
            .setAction(SimorghPublicVpnService.ACTION_SELECT_ROUTE)
            .putExtra(SimorghPublicVpnService.EXTRA_SELECTED_ROUTE, ip))
        withTimeout(5_000L) {
            while (enginePrefs(context).getString("manualRouteSelectionAck", "") != ip) delay(50L)
        }
    }

    private fun startProxyScanner(context: Context) {
        val intent = Intent(context, SimorghPublicVpnService::class.java)
            .setAction(SimorghPublicVpnService.ACTION_START_PROXY)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopProxyScanner(context: Context) {
        runCatching {
            context.startService(
                Intent(context, SimorghPublicVpnService::class.java)
                    .setAction(SimorghPublicVpnService.ACTION_STOP)
                    .putExtra(SimorghPublicVpnService.EXTRA_PRE_CONNECT_RESET, true),
            )
        }
    }

    private fun stopProxyIfRunning(context: Context) {
        val prefs = enginePrefs(context)
        if (prefs.getBoolean("connecting", false) ||
            (prefs.getBoolean("connected", false) && prefs.getString("activeMode", "") == "proxy")
        ) {
            stopProxyScanner(context)
        }
    }

    private suspend fun startProxyFromSaved(context: Context, candidates: List<String>, settings: ScannerSettings) {
        val clean = candidates.filter(::isValidIpv4).distinct().take(MAX_CACHED_ADDRESSES)
        val temporary = settings.copy(
            manualCleanMode = true,
            manualCleanIps = clean.joinToString("\n"),
            manualRangeMode = false,
            manualRanges = "",
        )
        configureEngine(context, temporary, seedCleanIps = clean)
        startProxyScanner(context)
        withTimeout(30_000L) {
            while (true) {
                val prefs = enginePrefs(context)
                if (prefs.getBoolean("connected", false) && prefs.getString("activeMode", "") == "proxy") break
                val error = prefs.getString("lastError", "").orEmpty()
                if (error.isNotBlank() && !prefs.getBoolean("connecting", false)) error("MSP local proxy could not start")
                delay(150L)
            }
        }
        configureEngine(context, settings)
    }

    private suspend fun awaitFirstUsableAddress(
        context: Context,
        settings: ScannerSettings,
        requestedAddress: String? = null,
        ownerGuid: String? = null,
        ownerMode: String? = null,
    ): String = withTimeout(SCAN_TIMEOUT_MS) {
        while (true) {
            if (ownerGuid != null && ownerMode != null) {
                val ownerPrefs = appPrefs(context)
                require(ownerPrefs.getString("scan_owner_guid", "") == ownerGuid &&
                    ownerPrefs.getString("scan_owner_mode", "") == ownerMode) { "MSP scan ownership changed" }
            }
            val candidates = if (settings.manualCleanMode) parseAddressText(settings.manualCleanIps) else
                parseAddressText(enginePrefs(context).getString("scanFoundIps", "").orEmpty())
            val prefs = enginePrefs(context)
            val selected = candidates.firstOrNull { requestedAddress == null || it == requestedAddress }
            if (selected != null && (settings.manualCleanMode || selected in engineCachedCandidates(context)) &&
                prefs.getBoolean("connected", false) && prefs.getString("activeMode", "") == "proxy") {
                return@withTimeout selected
            }
            val total = prefs.getInt("totalCandidates", 0)
            val scanned = prefs.getInt("scannedCount", 0)
            if (total > 0 && scanned >= total && selected != null) return@withTimeout selected
            if (total > 0 && scanned >= total && selected == null) error("MSP did not find a usable address")
            if (prefs.getBoolean("scanStopped", false) && selected == null) {
                error("MSP scan stopped before a usable address was found")
            }
            val lastError = prefs.getString("lastError", "").orEmpty()
            if (lastError.isNotBlank() && !prefs.getBoolean("connecting", false) && !prefs.getBoolean("connected", false)) {
                error("MSP scan failed")
            }
            delay(200L)
        }
        error("MSP scan did not return an address")
    }

    suspend fun awaitDirectReady(context: Context, guid: String): String {
        require(appPrefs(context).getString("scan_owner_guid", "") == guid &&
            appPrefs(context).getString("scan_owner_mode", "") == MODE_DIRECT) { "No Direct scan is active" }
        return awaitFirstUsableAddress(context, loadScannerSettings(context), ownerGuid = guid, ownerMode = MODE_DIRECT).also {
            scanSnapshot(context, guid, MODE_DIRECT)
        }
    }

    private suspend fun awaitScanFinished(
        context: Context,
        previousScanGeneration: Int,
        ownerGuid: String? = null,
        ownerMode: String? = null,
    ) = withTimeout(SCAN_TIMEOUT_MS) {
        var scanStarted = false
        while (true) {
            if (ownerGuid != null && ownerMode != null) {
                val ownerPrefs = appPrefs(context)
                require(ownerPrefs.getString("scan_owner_guid", "") == ownerGuid &&
                    ownerPrefs.getString("scan_owner_mode", "") == ownerMode) { "MSP scan ownership changed" }
            }
            val prefs = enginePrefs(context)
            if (prefs.getInt("scanGeneration", 0) != previousScanGeneration) scanStarted = true
            if (scanStarted && prefs.getBoolean("scanComplete", false)) {
                if (prefs.getString("lastError", "").orEmpty().isNotBlank()) error("MSP scan failed")
                return@withTimeout
            }
            delay(250L)
        }
    }

    private fun launchDirectScanSnapshot(context: Context, guid: String, previousScanGeneration: Int) {
        val application = context.applicationContext as? AngApplication ?: return
        val monitor = application.applicationScope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                awaitScanFinished(context, previousScanGeneration, guid, MODE_DIRECT)
                scanSnapshot(context, guid, MODE_DIRECT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The editor or another MSP mode may take ownership of the shared scanner.
            } finally {
                if (self != null) directMonitors.remove(guid, self)
            }
        }
        directMonitors.put(guid, monitor)?.cancel()
        monitor.start()
    }

    private fun readEngineCandidates(context: Context): List<String> {
        val prefs = enginePrefs(context)
        return if (prefs.getBoolean("manualIpMode", false)) {
            parseAddressText(prefs.getString("manualIpsText", "").orEmpty())
        } else {
            parseAddressText(prefs.getString("scanFoundIps", "").orEmpty())
        }
    }

    private fun engineCachedCandidates(context: Context): List<String> {
        val prefs = enginePrefs(context)
        return (parseAddressText(prefs.getString("savedCleanIps", "").orEmpty()) +
            prefs.getString("activeRouteIp", "").orEmpty().trim().takeIf(::isValidIpv4).orEmpty())
            .filter(String::isNotBlank).distinct().take(MAX_CACHED_ADDRESSES)
    }

    private fun loadCandidates(context: Context, guid: String, mode: String): List<String> {
        val prefs = appPrefs(context)
        val migrationKey = profileKey(guid, "address_migration_done")
        if (!prefs.getBoolean(migrationKey, false)) {
            val legacy = prefs.getString(profileKey(guid, "addresses"), "").orEmpty()
            val legacyMode = MspManager.mode(context, guid)
            prefs.edit().putString(profileKey(guid, "$legacyMode:addresses"), legacy)
                .putBoolean(migrationKey, true).commit()
        }
        return parseAddressText(prefs.getString(profileKey(guid, "$mode:addresses"), "").orEmpty())
            .distinct().take(MAX_CACHED_ADDRESSES)
    }

    private fun saveCandidateIfMissing(context: Context, guid: String, mode: String, address: String) {
        val current = loadCandidates(context, guid, mode)
        if (address !in current) saveCandidates(context, guid, mode, current + address)
    }

    private fun saveCandidates(context: Context, guid: String, mode: String, addresses: List<String>) {
        val clean = addresses.filter(::isValidIpv4).distinct().take(MAX_CACHED_ADDRESSES)
        appPrefs(context).edit().putString(profileKey(guid, "$mode:addresses"), clean.joinToString("\n")).commit()
    }

    private fun loadEnginePings(context: Context): Map<String, Long> =
        enginePrefs(context).getString("savedCleanIpPings", "").orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('=', limit = 2)
            val ip = parts.firstOrNull()?.trim().orEmpty()
            val value = parts.getOrNull(1)?.toLongOrNull()
            if (isValidIpv4(ip) && value != null) ip to value else null
        }.toMap()

    private fun loadScanPings(context: Context, guid: String, mode: String): Map<String, Long> =
        appPrefs(context).getString(profileKey(guid, "$mode:scan_pings"), "").orEmpty()
            .lineSequence().mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                val ip = parts.firstOrNull()?.trim().orEmpty()
                val value = parts.getOrNull(1)?.toLongOrNull()
                if (isValidIpv4(ip) && value != null) ip to value else null
            }.toMap()

    private fun saveScanPings(context: Context, guid: String, mode: String, pings: Map<String, Long>) {
        appPrefs(context).edit().putString(profileKey(guid, "$mode:scan_pings"),
            pings.filterKeys(::isValidIpv4).entries.joinToString("\n") { "${it.key}=${it.value}" }).commit()
    }

    private fun loadRealPings(context: Context, guid: String): Map<String, Long> =
        appPrefs(context).getString(profileKey(guid, "real_pings"), "").orEmpty()
            .lineSequence().mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                val ip = parts.firstOrNull()?.trim().orEmpty()
                val ping = parts.getOrNull(1)?.toLongOrNull()
                if (isValidIpv4(ip) && ping != null) ip to ping else null
            }.toMap()

    private fun ensurePingCacheForProfile(context: Context, guid: String, profile: ProfileItem) {
        // Runtime exit IP/country and editor metadata change after connection.
        // Only transport inputs can invalidate a real-delay measurement.
        val signature = "v3:" + listOf(
            profile.serverPort, profile.password, profile.flow,
            profile.network, profile.host, profile.path, profile.security,
            profile.sni, profile.alpn, profile.fingerPrint, profile.insecure,
            profile.headerType, profile.cipherSuites, profile.echConfigList,
            profile.verifyPeerCertByName, profile.pinnedCA256,
        ).hashCode()
        val prefs = appPrefs(context)
        val key = profileKey(guid, "ping_signature")
        if (prefs.getString(key, null) != signature) {
            prefs.edit().putString(key, signature).remove(profileKey(guid, "real_pings")).commit()
        }
    }

    private fun saveRealPings(context: Context, guid: String, pings: Map<String, Long>) {
        val clean = pings.filterKeys(::isValidIpv4)
        appPrefs(context).edit()
            .putString(profileKey(guid, "real_pings"), clean.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .commit()
    }

    fun setRuntimeOverride(context: Context, guid: String, mode: String, address: String) {
        appPrefs(context).edit()
            .putString("runtime_guid", guid)
            .putString("runtime_mode", mode)
            .putString("runtime_address", address.takeIf(::isValidIpv4).orEmpty())
            .commit()
    }

    fun isDirectRuntimeActive(context: Context, guid: String): Boolean {
        if (guid.isBlank()) return false
        val app = appPrefs(context)
        return app.getString("runtime_guid", null) == guid &&
            app.getString("runtime_mode", MODE_DIRECT) == MODE_DIRECT
    }

    /** Keep MSP exclusive with Psiphon and Tor for legacy profiles with conflicting saved flags. */
    fun suppressSecondLayerForMsp(context: Context, guid: String, profile: ProfileItem): ProfileItem {
        val ownsRuntime = profile.mspEnabled && appPrefs(context).getString("runtime_guid", null) == guid
        return if (ownsRuntime && (profile.psiphonEnabled || profile.torEnabled)) {
            profile.copy(psiphonEnabled = false, torEnabled = false)
        } else profile
    }

    fun clearRuntimeOverride(context: Context, guid: String? = null) {
        val prefs = appPrefs(context)
        if (guid != null && prefs.getString("runtime_guid", null) != guid) return
        prefs.edit().remove("runtime_guid").remove("runtime_mode").remove("runtime_address").commit()
    }

    /** Runtime-only profile substitution. The persisted VLESS address and its editor remain untouched. */
    fun applyRuntimeProfile(context: Context, guid: String, profile: ProfileItem): ProfileItem {
        if (!profile.mspEnabled) return profile
        val prefs = appPrefs(context)
        if (prefs.getString("runtime_guid", null) != guid) return profile
        return when (prefs.getString("runtime_mode", MODE_DIRECT)) {
            MODE_CLOUDFLARE -> prefs.getString("runtime_address", "").orEmpty()
                .takeIf(::isValidIpv4)?.let { profile.copy(server = it, psiphonEnabled = false, torEnabled = false) }
                ?: profile.copy(psiphonEnabled = false, torEnabled = false)
            else -> {
                val protocol = appPrefs(context).getString("proxy_protocol", "socks5").orEmpty()
                val fallbackPort = if (protocol == "http") DIRECT_PROXY_HTTP_PORT else DIRECT_PROXY_SOCKS_PORT
                val port = enginePrefs(context).getInt("proxyPort", fallbackPort).takeIf { it in 1..65535 } ?: fallbackPort
                profile.copy(
                    configType = if (protocol == "http") EConfigType.HTTP else EConfigType.SOCKS,
                    server = "127.0.0.1",
                    serverPort = port.toString(),
                    password = null,
                    username = null,
                    method = null,
                    flow = null,
                    network = NetworkType.TCP.type,
                    security = null,
                    sni = null,
                    host = null,
                    path = null,
                    bepassEnabled = false,
                    psiphonEnabled = false,
                    torEnabled = false,
                    mspEnabled = false,
                )
            }
        }
    }

    fun stop(context: Context, guid: String? = null) {
        clearRuntimeOverride(context, guid)
        setPrivacyActive(context, true)
        val prefs = appPrefs(context)
        val generation = prefs.getInt("privacy_generation", 0)
        Handler(Looper.getMainLooper()).postDelayed({
            val current = appPrefs(context)
            if (current.getInt("privacy_generation", -1) == generation &&
                current.getString("runtime_guid", null).isNullOrBlank()
            ) {
                current.edit().putBoolean("privacy_active", false).commit()
            }
        }, 15_000L)
        if (guid == null) {
            directMonitors.values.forEach { it.cancel() }
            directMonitors.clear()
        } else {
            directMonitors.remove(guid)?.cancel()
        }
        stopProxyScanner(context)
    }

    fun pendingConnection(context: Context, guid: String): String? {
        val prefs = appPrefs(context)
        if (prefs.getString("pending_connect_guid", null) != guid) return null
        val address = prefs.getString("pending_connect_address", null)
        prefs.edit().remove("pending_connect_guid").remove("pending_connect_address").commit()
        return address?.takeIf(::isValidIpv4)
    }

    fun queueConnection(context: Context, guid: String, address: String? = null) {
        appPrefs(context).edit()
            .putString("pending_connect_guid", guid)
            .putString("pending_connect_address", address?.takeIf(::isValidIpv4))
            .commit()
    }

    private fun parseAddressText(text: String): List<String> = text
        .lineSequence().flatMap { it.split(',', ';', ' ', '\t').asSequence() }
        .map { it.trim() }.filter(::isValidIpv4).distinct().take(MAX_CACHED_ADDRESSES).toList()

    private fun isValidIpv4(value: String): Boolean = ipv4Literal.matches(value.trim())

    private fun normalizeRouteStrategy(raw: String): String = when (raw.lowercase(Locale.US).replace(' ', '_')) {
        "random" -> "random"
        "round_robin", "roundrobin" -> "round_robin"
        "least_loss", "leastloss" -> "least_loss"
        "lowest_latency", "lowestlatency" -> "lowest_latency"
        "hybrid", "hybrid_score", "hybridscore" -> "hybrid_score"
        else -> "default"
    }

    private fun readShieldedRanges(context: Context): String {
        val encoded = context.assets.open("rk_payload/p0.dat").use { it.readBytes() }
        val key = byteArrayOf(82, 75, 45, 83, 73, 77, 79, 82, 71, 72, 45, 65, 83, 83, 69, 84, 45, 83, 72, 73, 69, 76, 68, 45, 118, 50)
        return ByteArray(encoded.size) { index ->
            (encoded[index].toInt() xor key[index % key.size].toInt() xor ((index * 31 + 0x5D) and 0xFF)).toByte()
        }.toString(Charsets.UTF_8)
    }

    private fun csvColumns(line: String): List<String> {
        val columns = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                    current.append('"'); index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    columns += current.toString(); current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        columns += current.toString()
        return columns
    }
}
