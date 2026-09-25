package com.v2ray.ang.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dalvik.system.DexClassLoader
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.dto.PsiphonStatus
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the Psiphon gomobile runtime in its own Android process.
 *
 * Xray and Psiphon both contain a gomobile `go.Seq`/`libgojni` runtime. Loading
 * both in the Xray process makes Android bind duplicate JNI symbols to the
 * wrong Go runtime and ends in `fatal error: unknown caller pc`. Keeping this
 * service in :PingNGPsiphon gives Psiphon a clean process and leaves only its
 * local SOCKS listener shared through 127.0.0.1.
 */
class PsiphonRuntimeService : Service() {
    private companion object {
        const val TAG = "PingNG-PsiphonRuntime"
        const val CHANNEL_ID = "pingng_psiphon_runtime"
        const val NOTIFICATION_ID = 7301
        const val RUNTIME_DEX_ASSET = "pingng_psiphon_runtime.dex"
        const val RUNTIME_DEX_FILE = "pingng_psiphon_runtime_ro_v3.dex"
        const val RUNTIME_DIR_PREFIX = "pingng-psiphon-runtime-"
        const val CONFIG_ASSET = "pingng_psiphon_config.json"
        const val ENTRIES_ASSET = "pingng_psiphon_server_entries.txt"
        const val NATIVE_NAME = "libpingng_psiphon.so"

        // Psiphon is always chained through PingNG's local HTTP upstream.
        // QUIC and in-proxy transports cannot be used through that hop, so
        // this is the same protocol set used by the reference chained mode.
        val CHAINED_TUNNEL_PROTOCOLS = listOf(
            "SSH",
            "OSSH",
            "TLS-OSSH",
            "UNFRONTED-MEEK-OSSH",
            "UNFRONTED-MEEK-HTTPS-OSSH",
            "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
            "SHADOWSOCKS-OSSH",
            "FRONTED-MEEK-OSSH",
            "FRONTED-MEEK-CDN-OSSH",
            "FRONTED-MEEK-HTTP-OSSH",
            "FRONTED-MEEK-CDN-HTTP-OSSH",
        )
        val CDN_TUNNEL_PROTOCOLS = listOf(
            "FRONTED-MEEK-CDN-OSSH",
            "FRONTED-MEEK-CDN-HTTP-OSSH",
        )
    }

    private val starting = AtomicBoolean(false)
    private val runtimeSequence = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var tunnel: Any? = null
    @Volatile private var activeGuid: String? = null
    @Volatile private var runtimeLoader: ClassLoader? = null
    @Volatile private var localSocksPort: Int = 0
    @Volatile private var boundUnderlyingNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        when (intent?.action) {
            AppConfig.PSIPHON_RUNTIME_START -> {
                val guid = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_GUID).orEmpty()
                val region = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_REGION).orEmpty()
                val mode = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_MODE).orEmpty().ifBlank { "auto" }
                val cdnIps = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_CDN_IPS).orEmpty()
                val cdnSni = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_CDN_SNI).orEmpty()
                val cdnSets = intent.getStringExtra(AppConfig.EXTRA_PSIPHON_CDN_SETS).orEmpty()
                val upstreamPort = intent.getIntExtra(AppConfig.EXTRA_PSIPHON_UPSTREAM_PORT, 0)
                if (guid.isNotBlank() && upstreamPort in 1..65535) {
                    startRuntime(guid, region, mode, cdnIps, cdnSni, cdnSets, upstreamPort)
                } else {
                    emit(PsiphonStatus.FAILED, guid, message = "Invalid Psiphon startup parameters")
                    stopSelf(startId)
                }
            }
            AppConfig.PSIPHON_RUNTIME_STOP -> {
                stopRuntime()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRuntime()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRuntime(
        guid: String,
        region: String,
        mode: String,
        cdnIps: String,
        cdnSni: String,
        cdnSets: String,
        upstreamPort: Int,
    ) {
        // A stop/start can arrive before Android has delivered onDestroy for
        // the previous foreground-service instance. Tear down that stale
        // runtime first so the second connection is not silently ignored.
        if (tunnel != null || starting.get()) {
            stopRuntime()
            starting.set(false)
        }
        if (!starting.compareAndSet(false, true)) return
        activeGuid = guid
        emit(PsiphonStatus.CONNECTING, guid)
        Thread {
            try {
                startInternal(guid, region, mode, cdnIps, cdnSni, cdnSets, upstreamPort)
            } catch (error: Throwable) {
                val cause = unwrapInvocation(error)
                LogUtil.e(TAG, "Psiphon start failed", cause)
                PingNgDiagnostics.record("Psiphon runtime start failed", cause)
                tunnel = null
                emit(PsiphonStatus.FAILED, guid, message = cause.message.orEmpty())
                stopSelf()
            } finally {
                starting.set(false)
            }
        }.apply { name = "PingNG-Psiphon-runtime-start" }.start()
    }

    private fun startInternal(
        guid: String,
        region: String,
        mode: String,
        cdnIps: String,
        cdnSni: String,
        cdnSets: String,
        upstreamPort: Int,
    ) {
        // The Android VPN is created after Psiphon reports CONNECTED. Without
        // an explicit process binding, Android changes the default network of
        // this process to the newly-created VPN at that exact moment. The
        // embedded Psiphon runtime then receives NetworkChanged while its Go
        // callback is being entered and the bundled gomobile runtime crashes
        // (the log shows _cgoexp_*NetworkChanged followed by
        // `fatal error: unknown caller pc`). Keep this process on the real
        // Wi-Fi/cellular network for its lifetime; its upstream is still the
        // local Xray HTTP listener on 127.0.0.1.
        bindToUnderlyingNetwork()
        // Android associates a loaded native library with both its absolute
        // path and its ClassLoader. Reusing the old libgojni.so path after a
        // stop makes the next Psiphon ClassLoader fail with
        // `already opened by ClassLoader`. Use a fresh private directory for
        // every run; the old classloader can then finish shutting down while
        // the new one loads a different absolute path.
        cleanupStaleRuntimeDirectories()
        val runtimeDir = File(
            codeCacheDir,
            "$RUNTIME_DIR_PREFIX${System.currentTimeMillis()}-${runtimeSequence.incrementAndGet()}",
        ).apply { mkdirs() }
        val dexFile = materializeReadOnlyRuntimeDex(runtimeDir)
        val nativeDir = materializeRuntimeNativeLibrary(runtimeDir)
        val loader = PsiphonDexClassLoader(
            dexFile.absolutePath,
            runtimeDir.absolutePath,
            nativeDir.absolutePath,
            PsiphonRuntimeService::class.java.classLoader,
        )
        runtimeLoader = loader

        Class.forName("go.Seq", true, loader)
            .getMethod("setContext", android.content.Context::class.java)
            .invoke(null, applicationContext)
        Class.forName("psi.Psi", true, loader)
        PingNgDiagnostics.record("Psiphon isolated runtime initialized")

        val tunnelClass = loader.loadClass("ca.psiphon.PsiphonTunnel")
        val hostClass = loader.loadClass("ca.psiphon.PsiphonTunnel\$HostService")
        val config = buildConfig(region, mode, cdnIps, cdnSni, cdnSets, upstreamPort)
        val entries = assets.open(ENTRIES_ASSET).bufferedReader().use { it.readText() }
        val host = Proxy.newProxyInstance(
            loader,
            arrayOf(hostClass),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getContext" -> this
                    "getPsiphonConfig" -> config
                    "loadLibrary" -> null
                    "bindToDevice" -> null
                    "onListeningSocksProxyPort" -> {
                        val port = (args?.firstOrNull() as? Int ?: 0)
                        if (port in 1..65535) {
                            localSocksPort = port
                            PingNgDiagnostics.record("Psiphon SOCKS ready on 127.0.0.1:$port")
                        }
                        null
                    }
                    "onConnected" -> {
                        // The official wrapper reports onConnected from the
                        // Tunnels notice. Resolve the SOCKS port once more on a
                        // worker so the status is never marked connected with
                        // port 0 during the callback ordering race.
                        Thread {
                            repeat(40) {
                                if (activeGuid != guid || tunnel == null) return@Thread
                                val port = instanceSocksPort()
                                if (port in 1..65535) {
                                    localSocksPort = port
                                    LogUtil.i(TAG, "Psiphon is working; SOCKS=127.0.0.1:$port")
                                    PingNgDiagnostics.record("Psiphon is working; SOCKS=127.0.0.1:$port")
                                    emit(PsiphonStatus.CONNECTED, guid, port = port)
                                    return@Thread
                                }
                                Thread.sleep(250L)
                            }
                            PingNgDiagnostics.record("Psiphon connected without a usable SOCKS port")
                            emit(PsiphonStatus.FAILED, guid, message = "SOCKS port unavailable")
                        }.apply { name = "PingNG-Psiphon-connected" }.start()
                        null
                    }
                    "onConnecting" -> {
                        PingNgDiagnostics.record("Psiphon tunnel is connecting")
                        null
                    }
                    "onAvailableEgressRegions" -> {
                        PingNgDiagnostics.record("Psiphon egress regions received")
                        null
                    }
                    "onClientRegion" -> {
                        PingNgDiagnostics.record("Psiphon client region: ${args?.firstOrNull()?.toString().orEmpty()}")
                        null
                    }
                    "onClientAddress" -> {
                        PingNgDiagnostics.record("Psiphon egress address received")
                        null
                    }
                    "onUpstreamProxyError" -> {
                        val message = args?.firstOrNull()?.toString().orEmpty()
                        LogUtil.w(TAG, "Psiphon upstream proxy error: $message")
                        PingNgDiagnostics.record("Psiphon upstream proxy error: $message")
                        null
                    }
                    "onExiting" -> {
                        emit(PsiphonStatus.STOPPED, guid)
                        null
                    }
                    "onDiagnosticMessage" -> {
                        LogUtil.d(TAG, args?.firstOrNull()?.toString().orEmpty())
                        null
                    }
                    else -> defaultValue(method)
                }
            },
        )

        val instance = tunnelClass.getMethod("newPsiphonTunnel", hostClass).invoke(null, host)
        instance.javaClass.getMethod("setVpnMode", Boolean::class.javaPrimitiveType!!).invoke(instance, false)
        tunnel = instance
        try {
            instance.javaClass.getMethod("startTunneling", String::class.java).invoke(instance, entries)
        } catch (error: Throwable) {
            tunnel = null
            throw unwrapInvocation(error)
        }

        Thread {
            repeat(60) {
                if (tunnel !== instance) return@Thread
                val port = runCatching {
                    instance.javaClass.getMethod("getLocalSocksProxyPort").invoke(instance) as Int
                }.getOrDefault(0)
                if (port in 1..65535) {
                    localSocksPort = port
                    return@Thread
                }
                Thread.sleep(250L)
            }
        }.apply { name = "PingNG-Psiphon-port" }.start()
    }

    @Synchronized
    private fun stopRuntime() {
        val active = tunnel
        tunnel = null
        localSocksPort = 0
        starting.set(false)
        if (active != null) {
            runCatching { active.javaClass.getMethod("stop").invoke(active) }
                .onFailure { LogUtil.e(TAG, "Psiphon stop failed", it) }
        }
        activeGuid?.let { emit(PsiphonStatus.STOPPED, it) }
        activeGuid = null
        runtimeLoader = null
        unbindUnderlyingNetwork()
    }

    /**
     * Pins only the Psiphon runtime process to a validated non-VPN network.
     * Xray remains responsible for routing the user's traffic through WARP;
     * this binding protects Psiphon's own control/data sockets from being
     * captured by the VPN that is created after Psiphon becomes ready.
     */
    private fun bindToUnderlyingNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            ) return@mapNotNull null
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val transportScore = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                else -> 0
            }
            network to (if (validated) 100 else 0) + transportScore
        }
        val selected = candidates.maxByOrNull { it.second }?.first
        if (selected == null) {
            PingNgDiagnostics.record("Psiphon runtime: no underlying non-VPN network to bind")
            return
        }
        if (runCatching { connectivity.bindProcessToNetwork(selected) }.getOrDefault(false)) {
            boundUnderlyingNetwork = selected
            PingNgDiagnostics.record("Psiphon runtime bound to underlying network: $selected")
        } else {
            PingNgDiagnostics.record("Psiphon runtime could not bind to underlying network: $selected")
        }
    }

    private fun unbindUnderlyingNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (boundUnderlyingNetwork == null) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity?.bindProcessToNetwork(null) }
            .onFailure { LogUtil.w(TAG, "Could not clear Psiphon network binding", it) }
        boundUnderlyingNetwork = null
    }

    private fun buildConfig(
        region: String,
        mode: String,
        cdnIps: String,
        cdnSni: String,
        cdnSets: String,
        upstreamPort: Int,
    ): String {
        val base = assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(base).asJsonObject
        // Psiphon creates its datastore with mkdir (not mkdirs) on some
        // versions. Create both levels first and provide the exact path so a
        // fresh install or an isolated process never falls back to its working
        // directory.
        val dataRoot = File(filesDir, "pingng-psiphon")
        check(dataRoot.mkdirs() || dataRoot.isDirectory) {
            "Unable to create Psiphon data root: ${dataRoot.absolutePath}"
        }
        val dataStore = File(dataRoot, "datastore")
        check(dataStore.mkdirs() || dataStore.isDirectory) {
            "Unable to create Psiphon datastore: ${dataStore.absolutePath}"
        }
        // Psiphon expects an ISO-3166 alpha-2 code. An empty value means
        // automatic/best region; the UI's display value "ANY" is not a valid
        // Psiphon region and would filter every server entry out.
        root.addProperty("EgressRegion", normalizedEgressRegion(region))
        root.addProperty("TunnelWholeDevice", 0)
        val configuredProxy = SettingsManager.getConnectHttpProxy()
        val upstreamProxyUrl = configuredProxy?.asHttpUrl() ?: "http://127.0.0.1:$upstreamPort"
        root.addProperty("UpstreamProxyUrl", upstreamProxyUrl)
        if (configuredProxy != null) {
            PingNgDiagnostics.record(
                "Connect through HTTP proxy: Psiphon upstream set to ${configuredProxy.host}:${configuredProxy.port}",
            )
        }
        root.addProperty("DataRootDirectory", dataRoot.absolutePath)
        root.addProperty("DataStoreDirectory", dataStore.absolutePath)
        root.addProperty("MigrateDataStoreDirectory", filesDir.absolutePath)
        val oslDirectory = File(filesDir, "osl")
        check(oslDirectory.mkdirs() || oslDirectory.isDirectory) {
            "Unable to create Psiphon OSL directory: ${oslDirectory.absolutePath}"
        }
        root.addProperty("MigrateObfuscatedServerListDownloadDirectory", oslDirectory.absolutePath)
        root.addProperty("MigrateRemoteServerListDownloadFilename", File(filesDir, "remote_server_list").absolutePath)
        root.addProperty("EstablishTunnelTimeoutSeconds", 0)
        // The embedded list is the only bundled source in PingNG. Psiphon
        // intentionally rejects non-allowlisted sources behind an upstream
        // proxy unless this switch is enabled.
        root.addProperty("UpstreamProxyAllowAllServerEntrySources", true)
        applyTunnelProtocolMode(root, mode)
        addCdnFrontingConfig(root, mode, cdnIps, cdnSni, cdnSets)
        root.addProperty("DeviceRegion", Locale.getDefault().country)
        root.addProperty("ClientPlatform", "Android_${Build.VERSION.RELEASE}_${packageName}".replace(Regex("[^\\w\\-.]"), "_"))
        root.addProperty("ClientAPILevel", Build.VERSION.SDK_INT)
        return root.toString()
    }

    /** Restricts Psiphon to the selected shape when it is chained upstream. */
    private fun applyTunnelProtocolMode(root: com.google.gson.JsonObject, mode: String) {
        val normalized = mode.trim().lowercase(Locale.ROOT)
        val protocols = when (normalized) {
            "cdn" -> CDN_TUNNEL_PROTOCOLS
            "direct" -> CHAINED_TUNNEL_PROTOCOLS.filterNot { it.startsWith("FRONTED-") }
            else -> CHAINED_TUNNEL_PROTOCOLS
        }
        root.add("LimitTunnelProtocols", JsonArray().also { values -> protocols.forEach(values::add) })
        if (normalized == "cdn" || normalized == "direct") {
            root.addProperty("DisableTactics", true)
        }
    }

    /** Mirrors PingNG's Psiphon fronting configuration using tunnel-core JSON. */
    private fun addCdnFrontingConfig(
        root: com.google.gson.JsonObject,
        mode: String,
        cdnIps: String,
        cdnSni: String,
        cdnSets: String,
    ) {
        if (mode.trim().equals("direct", ignoreCase = true)) return
        fun candidates(raw: String): List<String> = raw.split(',', ';', ' ', '\t', '\n', '\r')
            .map(String::trim).filter(String::isNotBlank)
        val addresses = candidates(cdnIps)
        val names = candidates(cdnSni)
        val sets = candidates(cdnSets)
        if (!mode.trim().equals("cdn", ignoreCase = true) && addresses.isEmpty() && names.isEmpty() && sets.isEmpty()) return
        if (addresses.isNotEmpty()) {
            val spec = com.google.gson.JsonObject().apply {
                add("IPCandidates", com.google.gson.JsonArray().also { values -> addresses.forEach(values::add) })
                if (names.isNotEmpty()) {
                    add("SNIServerNames", com.google.gson.JsonArray().also { values -> names.forEach(values::add) })
                }
            }
            root.add("FrontedMeekCDNScanSpec", spec)
        }
        // No custom edges means use all built-in lists. Named lists restrict the
        // built-in scan and are appended after custom edges, matching PingNG's reference behavior.
        if (addresses.isEmpty() || sets.isNotEmpty()) {
            root.addProperty("FrontedMeekCDNScanUseBuiltInSpec", true)
        }
        if (sets.isNotEmpty()) {
            root.add("FrontedMeekCDNScanBuiltInSets", com.google.gson.JsonArray().also { values -> sets.forEach(values::add) })
        }
        PingNgDiagnostics.record(
            "Psiphon CDN fronting enabled: customEdges=${addresses.size}, builtInSets=${sets.size}",
        )
    }

    private fun normalizedEgressRegion(region: String): String =
        region.trim().uppercase(Locale.ROOT).takeUnless { it.isBlank() || it == "ANY" || it == "AUTO" }.orEmpty()

    private fun instanceSocksPort(): Int = runCatching {
        tunnel?.javaClass?.getMethod("getLocalSocksProxyPort")?.invoke(tunnel) as Int
    }.getOrDefault(localSocksPort)

    private fun materializeReadOnlyRuntimeDex(runtimeDir: File): File {
        val dexFile = File(runtimeDir, RUNTIME_DEX_FILE)
        if (!dexFile.isFile || dexFile.length() < 1024) {
            assets.open(RUNTIME_DEX_ASSET).use { input ->
                dexFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dexFile.setReadable(true, false)
        dexFile.setWritable(false, false)
        dexFile.setExecutable(false, false)
        check(!dexFile.canWrite()) { "Psiphon runtime DEX is writable: ${dexFile.absolutePath}" }
        return dexFile
    }

    private fun materializeRuntimeNativeLibrary(runtimeDir: File): File {
        val source = File(applicationInfo.nativeLibraryDir, NATIVE_NAME)
        check(source.isFile) { "Psiphon native library not found: ${source.absolutePath}" }
        val nativeDir = File(runtimeDir, "lib").apply { mkdirs() }
        val target = File(nativeDir, "libgojni.so")
        if (!target.isFile || target.length() != source.length()) {
            source.copyTo(target, overwrite = true)
        }
        target.setReadable(true, false)
        target.setWritable(false, false)
        target.setExecutable(true, false)
        check(target.isFile && target.canRead()) { "Psiphon runtime library is not readable" }
        return nativeDir
    }

    private fun cleanupStaleRuntimeDirectories() {
        codeCacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith(RUNTIME_DIR_PREFIX) }
            ?.forEach { stale ->
                runCatching { stale.deleteRecursively() }
                    .onFailure { LogUtil.w(TAG, "Could not clean old Psiphon runtime cache", it) }
            }
    }

    private fun emit(state: String, guid: String?, port: Int = 0, message: String = "") {
        if (guid.isNullOrBlank()) return
        sendBroadcast(
            Intent(AppConfig.ACTION_PSIPHON_RUNTIME)
                .setPackage(packageName)
                .putExtra(AppConfig.EXTRA_PSIPHON_GUID, guid)
                .putExtra(AppConfig.EXTRA_PSIPHON_STATE, state)
                .putExtra(AppConfig.EXTRA_PSIPHON_SOCKS_PORT, port)
                .putExtra(AppConfig.EXTRA_PSIPHON_MESSAGE, message),
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Psiphon", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_pingng_status)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("Psiphon")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun defaultValue(method: Method): Any? = when (method.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private fun unwrapInvocation(error: Throwable): Throwable {
        val invocation = error as? java.lang.reflect.InvocationTargetException ?: return error
        return invocation.targetException?.let(::unwrapInvocation) ?: error
    }

    private class PsiphonDexClassLoader(
        dexPath: String,
        optimizedDirectory: String,
        librarySearchPath: String,
        parent: ClassLoader?,
    ) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
        private fun belongsToPsiphon(name: String): Boolean =
            name == "go" || name.startsWith("go.") ||
                name == "psi" || name.startsWith("psi.") ||
                name == "ca.psiphon" || name.startsWith("ca.psiphon.")

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            var loaded = findLoadedClass(name)
            if (loaded == null && belongsToPsiphon(name)) {
                loaded = runCatching { findClass(name) }.getOrNull()
            }
            if (loaded == null) loaded = super.loadClass(name, resolve)
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
