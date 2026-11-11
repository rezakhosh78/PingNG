package com.v2ray.ang.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Snowflake settings used by both the transport process and Xray's bootstrap routing. */
object TorSnowflakeConfig {
    const val TRANSPORT = "snowflake"
    const val BROKER_URL = "https://1098762253.rsc.cdn77.org/"
    const val FRONT_DOMAINS = "www.cdn77.com,www.phpmyadmin.net,cdn.zk.mk"
    const val ICE_SERVERS = "stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443"
    // Try more temporary peers in parallel so a dead/blocked WebRTC offer does not
    // force the next usable Snowflake proxy to wait through serial timeouts.
    const val MAX_PEERS = 8L

    val bootstrapHosts = listOf(
        "1098762253.rsc.cdn77.org",
        "www.cdn77.com",
        "www.phpmyadmin.net",
        "cdn.zk.mk",
        "stun.antisip.com",
        "stun.epygi.com",
        "stun.uls.co.za",
        "stun.voipgate.com",
        "stun.mixvoip.com",
        "stun.nextcloud.com",
        "stun.bethesda.net",
    )
}

/** Owns IPtProxy in a process isolated from the app's other gomobile runtime. */
class TorSnowflakeTransportService : Service() {
    companion object {
        private const val TAG = "PingNG-Snowflake"
        const val ACTION_START = "com.v2ray.ang.tor.snowflake.START"
        const val ACTION_READY = "com.v2ray.ang.tor.snowflake.READY"
        const val ACTION_FAILED = "com.v2ray.ang.tor.snowflake.FAILED"
        const val ACTION_EVENT = "com.v2ray.ang.tor.snowflake.EVENT"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_ERROR = "error"
        const val EXTRA_EVENT = "event"
        const val EXTRA_DETAIL = "detail"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val startInProgress = AtomicBoolean(false)
    @Volatile private var activeRunId: String? = null
    @Volatile private var activeAddress: String? = null
    @Volatile private var controller: Any? = null
    @Volatile private var snowflakeClassLoader: ClassLoader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTransport(intent.getStringExtra(EXTRA_RUN_ID).orEmpty(), startId)
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startTransport(runId: String, startId: Int) {
        if (runId.isBlank()) {
            sendFailure(runId, "Missing Snowflake run identifier")
            stopSelfResult(startId)
            return
        }
        if (activeRunId == runId && !activeAddress.isNullOrBlank()) {
            sendReady(runId, activeAddress!!)
            return
        }
        if (activeRunId != null && activeRunId != runId) {
            sendFailure(runId, "A Snowflake transport is already active")
            stopSelfResult(startId)
            return
        }
        if (!startInProgress.compareAndSet(false, true)) return
        activeRunId = runId
        executor.execute {
            try {
                val stateDirectory = File(noBackupFilesDir, "tor-snowflake").apply { mkdirs() }
                val nextController = createSnowflakeController(stateDirectory, runId)
                controller = nextController
                invokeControllerMethod(
                    nextController,
                    "setSnowflakeBrokerUrl",
                    arrayOf(String::class.java),
                    TorSnowflakeConfig.BROKER_URL,
                )
                invokeControllerMethod(
                    nextController,
                    "setSnowflakeFrontDomains",
                    arrayOf(String::class.java),
                    TorSnowflakeConfig.FRONT_DOMAINS,
                )
                invokeControllerMethod(
                    nextController,
                    "setSnowflakeIceServers",
                    arrayOf(String::class.java),
                    TorSnowflakeConfig.ICE_SERVERS,
                )
                invokeControllerMethod(
                    nextController,
                    "setSnowflakeMaxPeers",
                    arrayOf(java.lang.Long.TYPE),
                    TorSnowflakeConfig.MAX_PEERS,
                )
                invokeControllerMethod(
                    nextController,
                    "start",
                    arrayOf(String::class.java, String::class.java),
                    TorSnowflakeConfig.TRANSPORT,
                    "",
                )

                val address = (invokeControllerMethod(
                    nextController,
                    "localAddress",
                    arrayOf(String::class.java),
                    TorSnowflakeConfig.TRANSPORT,
                ) as? String)
                    ?.trim()
                    ?.removePrefix("socks5://")
                    ?.removePrefix("socks5h://")
                    .orEmpty()
                require(isLoopbackAddress(address)) { "Snowflake returned an invalid local address" }
                if (activeRunId != runId) return@execute
                activeAddress = address
                sendReady(runId, address)
            } catch (error: Throwable) {
                val cause = (error as? InvocationTargetException)?.targetException ?: error
                Log.e(TAG, "Unable to start Snowflake", cause)
                sendFailure(runId, cause.message.orEmpty().ifBlank { "Snowflake could not start" })
                stopSelf()
            } finally {
                startInProgress.set(false)
            }
        }
    }

    private fun createSnowflakeController(stateDirectory: File, runId: String): Any {
        val runtimeLoader = loadSnowflakeRuntime()
        // Gomobile code and its callbacks use the context class loader while
        // resolving generated Go proxy types in the isolated process.
        Thread.currentThread().contextClassLoader = runtimeLoader

        val eventInterface = runtimeLoader.loadClass("IPtProxy.OnTransportEvents")
        val events = Proxy.newProxyInstance(
            runtimeLoader,
            arrayOf(eventInterface),
            InvocationHandler { proxy, method, arguments ->
                val name = arguments?.getOrNull(0) as? String
                val error = arguments?.getOrNull(1) as? Throwable
                when (method.name) {
                    "connected" -> {
                        val transport = name.orEmpty().ifBlank { "Snowflake" }
                        Log.i(TAG, "$transport transport is active")
                        sendTransportEvent(runId, "connected", transport)
                        null
                    }
                    "error" -> {
                        val transport = name.orEmpty().ifBlank { "Snowflake" }
                        Log.w(TAG, "$transport transport reported an error", error)
                        sendTransportEvent(
                            runId,
                            "error",
                            "$transport: ${error?.message.orEmpty().ifBlank { "unspecified transport error" }}",
                        )
                        null
                    }
                    "stopped" -> {
                        val transport = name.orEmpty().ifBlank { "Snowflake" }
                        Log.w(TAG, "$transport transport stopped", error)
                        sendTransportEvent(
                            runId,
                            "stopped",
                            "$transport: ${error?.message.orEmpty().ifBlank { "no additional detail" }}",
                        )
                        null
                    }
                    "toString" -> "PingNG Snowflake transport events"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> null
                }
            },
        )

        val controllerClass = runtimeLoader.loadClass("IPtProxy.Controller")
        val constructor = controllerClass.getConstructor(
            String::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java,
            eventInterface,
        )
        return constructor.newInstance(
            stateDirectory.absolutePath,
            false,
            false,
            "WARN",
            events,
        )
    }

    private fun loadSnowflakeRuntime(): ClassLoader {
        snowflakeClassLoader?.let { return it }
        synchronized(this) {
            snowflakeClassLoader?.let { return it }

            val dexDirectory = File(noBackupFilesDir, "tor-snowflake-dex").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val dexAssets = assets.list("tor-snowflake-runtime")
                .orEmpty()
                .filter { it.endsWith(".dex", ignoreCase = true) }
                .sorted()
            check(dexAssets.isNotEmpty()) { "Snowflake runtime DEX is missing from the app" }
            val dexPaths = dexAssets.map { name ->
                val destination = File(dexDirectory, name)
                assets.open("tor-snowflake-runtime/$name").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                check(destination.setReadOnly()) { "Unable to protect Snowflake runtime DEX" }
                destination.absolutePath
            }

            val sourceNativeLibrary = File(applicationInfo.nativeLibraryDir, "libpingng_snowflake.so")
            check(sourceNativeLibrary.isFile) { "Snowflake native runtime is missing for this ABI" }
            val nativeDirectory = File(noBackupFilesDir, "tor-snowflake-libs").apply { mkdirs() }
            File(nativeDirectory, "libgojni.so").let { destination ->
                sourceNativeLibrary.copyTo(destination, overwrite = true)
                check(destination.setReadOnly()) { "Unable to protect Snowflake native runtime" }
            }
            val optimizedDirectory = File(codeCacheDir, "tor-snowflake-dex").apply { mkdirs() }
            val runtimeLoader = object : DexClassLoader(
                dexPaths.joinToString(File.pathSeparator),
                optimizedDirectory.absolutePath,
                nativeDirectory.absolutePath,
                this@TorSnowflakeTransportService.javaClass.classLoader,
            ) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    // This loader is not registered as parallel-capable; synchronize
                    // on the loader instance while checking/defining child-first classes.
                    synchronized(this) {
                        var loaded = findLoadedClass(name)
                        if (loaded == null && (name.startsWith("IPtProxy.") || name.startsWith("go."))) {
                            loaded = try {
                                findClass(name)
                            } catch (_: ClassNotFoundException) {
                                null
                            }
                        }
                        val resolved = loaded ?: super.loadClass(name, false)
                        if (resolve) resolveClass(resolved)
                        return resolved
                    }
                }
            }
            snowflakeClassLoader = runtimeLoader
            return runtimeLoader
        }
    }

    private fun invokeControllerMethod(
        target: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg arguments: Any?,
    ): Any? = target.javaClass.getMethod(name, *parameterTypes).invoke(target, *arguments)

    private fun isLoopbackAddress(address: String): Boolean {
        val separator = address.lastIndexOf(':')
        if (separator <= 0 || separator == address.lastIndex) return false
        val host = address.substring(0, separator).removePrefix("[").removeSuffix("]")
        val port = address.substring(separator + 1).toIntOrNull() ?: return false
        return host in setOf("127.0.0.1", "localhost", "::1") && port in 1..65535
    }

    private fun sendReady(runId: String, address: String) = sendEvent(ACTION_READY, runId) {
        putExtra(EXTRA_ADDRESS, address)
    }

    private fun sendFailure(runId: String, error: String) = sendEvent(ACTION_FAILED, runId) {
        putExtra(EXTRA_ERROR, error)
    }

    private inline fun sendEvent(action: String, runId: String, extras: Intent.() -> Unit) {
        sendBroadcast(Intent(action).setPackage(packageName).apply {
            putExtra(EXTRA_RUN_ID, runId)
            extras()
        })
    }

    private fun sendTransportEvent(runId: String, event: String, detail: String) {
        sendEvent(ACTION_EVENT, runId) {
            putExtra(EXTRA_EVENT, event)
            putExtra(EXTRA_DETAIL, detail)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        controller = null
        super.onDestroy()
        // Reset gomobile's process-global Go runtime before another tunnel starts.
        Process.killProcess(Process.myPid())
    }

}
