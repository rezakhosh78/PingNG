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
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.util.LogUtil
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Keeps Bepass's gomobile runtime isolated from Xray's own Go runtime. */
class BepassRuntimeService : Service() {
    private companion object {
        const val TAG = "PingNG-BepassRuntime"
        const val CHANNEL_ID = "pingng_bepass_runtime"
        const val NOTIFICATION_ID = 7302
        const val RUNTIME_ASSET_DIRECTORY = "pingng-bepass-runtime"
        const val RUNTIME_DIRECTORY_PREFIX = "pingng-bepass-runtime-"
        const val NATIVE_LIBRARY_NAME = "libpingng_bepass.so"
        const val GO_NATIVE_LIBRARY_NAME = "libgojni.so"
        const val GO_WRAPPER_CLASS = "com.pingng.bepass.Bepass"
        val runtimeSequence = AtomicLong(0L)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    @Volatile private var operationJob: Job? = null
    @Volatile private var runtimeClass: Class<*>? = null
    @Volatile private var runtimeLoader: ClassLoader? = null
    @Volatile private var runtimeDirectory: File? = null
    @Volatile private var boundUnderlyingNetwork: Network? = null
    @Volatile private var activeGuid: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        when (intent?.action) {
            AppConfig.BEPASS_RUNTIME_START -> {
                val guid = intent.getStringExtra(AppConfig.EXTRA_BEPASS_GUID).orEmpty()
                val configJson = intent.getStringExtra(AppConfig.EXTRA_BEPASS_CONFIG).orEmpty()
                if (guid.isBlank() || configJson.isBlank()) {
                    emit(guid, AppConfig.BEPASS_RUNTIME_FAILED, message = "Missing Bepass startup settings")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                } else {
                    enqueueOperation {
                        lifecycleMutex.withLock {
                            try {
                                stopRuntimeLocked()
                                bindToUnderlyingNetwork()
                                val loader = createRuntimeLoader()
                                runtimeLoader = loader
                                val wrapper = loader.loadClass(GO_WRAPPER_CLASS)
                                runtimeClass = wrapper
                                Class.forName("go.Seq", true, loader)
                                    .getMethod("setContext", android.content.Context::class.java)
                                    .invoke(null, applicationContext)
                                invokeWrapper("start", configJson)
                                coroutineContext.ensureActive()
                                activeGuid = guid
                                PingNgDiagnostics.record("Bepass runtime ready for profile $guid")
                                emit(guid, AppConfig.BEPASS_RUNTIME_READY, readBindPort(configJson))
                            } catch (error: Throwable) {
                                runCatching { stopRuntimeLocked() }
                                    .onFailure { cleanupError ->
                                        LogUtil.e(TAG, "Bepass startup cleanup failed for profile $guid", cleanupError)
                                        PingNgDiagnostics.record("Bepass startup cleanup failed for profile $guid", cleanupError)
                                    }
                                if (error is CancellationException) throw error
                                LogUtil.e(TAG, "Bepass start failed for profile $guid", error)
                                PingNgDiagnostics.record("Bepass runtime start failed for profile $guid", error)
                                emit(guid, AppConfig.BEPASS_RUNTIME_FAILED, message = error.message.orEmpty())
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf(startId)
                            }
                        }
                    }
                }
            }

            AppConfig.BEPASS_RUNTIME_STOP -> {
                val guid = intent.getStringExtra(AppConfig.EXTRA_BEPASS_GUID).orEmpty()
                enqueueOperation {
                    lifecycleMutex.withLock {
                        runCatching { stopRuntimeLocked() }
                            .onFailure { error ->
                                LogUtil.e(TAG, "Bepass stop failed for profile $guid", error)
                                PingNgDiagnostics.record("Bepass runtime stop failed for profile $guid", error)
                            }
                        emit(guid, AppConfig.BEPASS_RUNTIME_STOPPED)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }

            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    private fun enqueueOperation(operation: suspend () -> Unit) {
        val previous = operationJob
        operationJob = serviceScope.launch {
            previous?.cancelAndJoin()
            operation()
        }
    }

    private suspend fun stopRuntimeLocked() {
        runtimeClass?.let {
            runCatching { invokeWrapper("stop") }
                .onFailure { error ->
                    LogUtil.e(TAG, "Bepass Go wrapper stop failed", error)
                    PingNgDiagnostics.record("Bepass Go wrapper stop failed", error)
                }
        }
        runtimeClass = null
        runtimeLoader = null
        activeGuid = null
        boundUnderlyingNetwork = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
            }
        }
        runtimeDirectory = null
        cleanupRuntimeDirectories()
    }

    private fun bindToUnderlyingNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
            ?: error("Android network service is unavailable")
        val selected = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) return@mapNotNull null
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val transportScore = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                else -> 0
            }
            network to (if (validated) 100 else 0) + transportScore
        }.maxByOrNull { it.second }?.first ?: error("No underlying non-VPN network is available for Bepass")
        check(connectivity.bindProcessToNetwork(selected)) { "Could not bind Bepass to the underlying network" }
        boundUnderlyingNetwork = selected
        PingNgDiagnostics.record("Bepass runtime bound to underlying network $selected")
    }

    private fun createRuntimeLoader(): ClassLoader {
        cleanupRuntimeDirectories()
        val directory = File(
            codeCacheDir,
            "$RUNTIME_DIRECTORY_PREFIX${System.currentTimeMillis()}-${runtimeSequence.incrementAndGet()}",
        ).apply { check(mkdirs() || isDirectory) { "Could not create Bepass runtime cache" } }
        runtimeDirectory = directory

        val dexFiles = assets.list(RUNTIME_ASSET_DIRECTORY)
            ?.filter { it.endsWith(".dex", ignoreCase = true) }
            ?.sorted()
            .orEmpty()
        check(dexFiles.isNotEmpty()) { "Bepass runtime DEX assets are missing" }
        val copiedDexFiles = dexFiles.map { assetName ->
            val dexFile = File(directory, assetName)
            assets.open("$RUNTIME_ASSET_DIRECTORY/$assetName").use { input ->
                dexFile.outputStream().use { output -> input.copyTo(output) }
            }
            dexFile.setReadable(true, false)
            dexFile.setWritable(false, false)
            dexFile.setExecutable(false, false)
            check(!dexFile.canWrite()) { "Bepass runtime DEX is writable" }
            dexFile
        }

        val bundledLibrary = File(applicationInfo.nativeLibraryDir, NATIVE_LIBRARY_NAME)
        check(bundledLibrary.isFile) { "Bepass native library is missing for this ABI" }
        val nativeDirectory = File(directory, "lib").apply { check(mkdirs() || isDirectory) }
        val privateLibrary = File(nativeDirectory, GO_NATIVE_LIBRARY_NAME)
        bundledLibrary.copyTo(privateLibrary, overwrite = true)
        privateLibrary.setReadable(true, false)
        privateLibrary.setWritable(false, false)
        privateLibrary.setExecutable(true, false)

        return BepassDexClassLoader(
            copiedDexFiles.joinToString(File.pathSeparator) { it.absolutePath },
            directory.absolutePath,
            nativeDirectory.absolutePath,
            BepassRuntimeService::class.java.classLoader,
        )
    }

    private fun invokeWrapper(methodName: String, vararg args: Any?) {
        val wrapper = runtimeClass ?: return
        val method = wrapper.methods.firstOrNull { method ->
            method.name.equals(methodName, ignoreCase = true) &&
                Modifier.isStatic(method.modifiers) && method.parameterCount == args.size
        } ?: error("Bepass runtime method '$methodName' is missing")
        try {
            method.invoke(null, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    private fun cleanupRuntimeDirectories() {
        codeCacheDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(RUNTIME_DIRECTORY_PREFIX) }
            ?.forEach { directory -> runCatching { directory.deleteRecursively() } }
    }

    private fun emit(guid: String, state: String, port: Int = 0, message: String = "") {
        sendBroadcast(
            Intent(AppConfig.ACTION_BEPASS_RUNTIME)
                .setPackage(AppConfig.ANG_PACKAGE)
                .putExtra(AppConfig.EXTRA_BEPASS_GUID, guid)
                .putExtra(AppConfig.EXTRA_BEPASS_STATE, state)
                .putExtra(AppConfig.EXTRA_BEPASS_SOCKS_PORT, port)
                .putExtra(AppConfig.EXTRA_BEPASS_MESSAGE, message.take(240)),
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.bepass_service_status), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_pingng_status)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.bepass_service_status))
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun readBindPort(configJson: String): Int = runCatching {
        val bindAddress = com.google.gson.JsonParser.parseString(configJson).asJsonObject
            .get("BindAddress").asString
        bindAddress.substringAfterLast(':').toInt()
    }.getOrDefault(0)

    private class BepassDexClassLoader(
        dexPath: String,
        optimizedDirectory: String,
        librarySearchPath: String,
        parent: ClassLoader?,
    ) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
        private fun belongsToBepass(name: String): Boolean =
            name == "go" || name.startsWith("go.") ||
                name == "com.pingng.bepass" || name.startsWith("com.pingng.bepass.")

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            var loaded = findLoadedClass(name)
            if (loaded == null && belongsToBepass(name)) {
                loaded = runCatching { findClass(name) }.getOrNull()
            }
            if (loaded == null) loaded = super.loadClass(name, resolve)
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
