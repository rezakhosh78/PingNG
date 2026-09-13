package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.core.PingNgDesyncTuner
import com.v2ray.ang.core.PingNgDesyncTuner.SearchFamily
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.service.DesyncSearchKeepAliveService
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : HelperBaseComponentActivity() {

    private var desyncTunerJob: Job? = null

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                LogUtil.i(AppConfig.TAG, "VPN permission granted")
                startV2Ray()
            } else {
                LogUtil.w(AppConfig.TAG, "VPN permission was denied or canceled")
            }
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onStart() {
        super.onStart()
        // Refresh subscription payloads, including provider notices, on every return
        // to the foreground. The service performs the work off the UI thread.
        SubscriptionUpdater.updateAllNow(this)
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.SaveDesync -> saveDesync(action)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (desyncTunerJob?.isActive == true) {
            cancelDesyncSearch()
            LauncherManager.stopService(this)
            return
        }
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
        } else {
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        LogUtil.i(
            AppConfig.TAG,
            "Start button pressed; selected=${!mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()}, " +
                "vpnMode=${SettingsManager.isVpnMode()}"
        )
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) {
            LogUtil.i(AppConfig.TAG, "VPN permission is already available")
            startV2Ray()
        } else {
            LogUtil.i(AppConfig.TAG, "Requesting Android VPN permission")
            requestVpnPermission.launch(intent)
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            LogUtil.w(AppConfig.TAG, "Start canceled because no configuration is selected")
            toast(R.string.title_file_chooser)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    }
                    withContext(Dispatchers.Main) {
                        if (content != null) {
                            mainViewModel.onAction(MainAction.ImportBatchConfig(content))
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
                }
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun saveDesync(action: MainAction.SaveDesync) {
        val updated = action.profile.copy(
            pingNgProfile = action.desyncProfile,
            pingNgDesyncArgs = action.customArgs.trim().ifBlank { null },
        )
        MmkvManager.encodeServerConfig(action.guid, updated)
        mainViewModel.onAction(MainAction.RefreshGroups)
        if (mainViewModel.uiState.value.isRunning &&
            mainViewModel.uiState.value.selectedGuid == action.guid
        ) {
            LauncherManager.restartService(this)
        }
        toastSuccess(R.string.toast_success)
    }

    private fun findBestDesync(
        guid: String,
        profile: ProfileItem,
        family: SearchFamily,
        advanced: Boolean,
        maxProfiles: Int,
        workers: Int,
        progress: (tested: Int, total: Int) -> Unit,
        report: (List<Pair<PingNgDesyncTuner.Candidate, Long>>) -> Unit,
    ) {
        if (!PingNgCompat.supportsNativeDesync(profile)) {
            toastError(R.string.pingng_desync_unsupported)
            return
        }
        if (desyncTunerJob?.isActive == true) {
            toast(R.string.pingng_desync_search_running)
            return
        }
        val original = MmkvManager.decodeServerConfig(guid) ?: return
        // Keep the UI total non-zero even if a future tuner change accidentally
        // returns an empty plan. A supported profile always has a safe fallback.
        val allCandidates = PingNgDesyncTuner.generate(
            profile,
            includeAdvanced = advanced,
            family = family,
        )
        val candidates = allCandidates
            .take(maxProfiles.coerceIn(1, allCandidates.size.coerceAtLeast(1)))
            .ifEmpty {
                listOf(
                    PingNgDesyncTuner.Candidate(
                        label = "default ${family.method.orEmpty()}".trim(),
                        arguments = PingNgCompat.buildCustomArguments(
                            PingNgCompat.CustomOptions(
                                method = family.method ?: PingNgCompat.METHOD_SPLIT
                            )
                        ),
                    )
                )
            }
        val safeWorkers = workers.coerceIn(1, 20)
        progress(0, candidates.size)
        toast(getString(R.string.pingng_desync_search_count, candidates.size))
        PingNgDiagnostics.record(
            "Desync search plan=${candidates.size}, family=${family.name}, " +
                "advanced=$advanced, workers=$safeWorkers"
        )
        // The worker setting controls the I/O scheduler. The native Desync
        // engine itself remains one process with global parameters, so its
        // candidate swaps stay serialized to prevent false results.
        keepDesyncSearchAlive()
        desyncTunerJob = (application as AngApplication).applicationScope.launch(
            Dispatchers.IO.limitedParallelism(safeWorkers)
        ) {
            val successful = mutableListOf<Pair<PingNgDesyncTuner.Candidate, Long>>()
            var ranked = emptyList<Pair<PingNgDesyncTuner.Candidate, Long>>()
            var wasRunning = false
            var bestConfigSaved = false
            try {
                // Stop the normal service once. The search owns a proxy-only Xray
                // service for all candidates, so VPN mode is never enabled per test.
                wasRunning = withContext(Dispatchers.Main) {
                    val running = mainViewModel.uiState.value.isRunning
                    if (mainViewModel.uiState.value.selectedGuid != guid) {
                        mainViewModel.updateSelectedGuid(guid)
                    }
                    LauncherManager.stopService(this@MainActivity)
                    running
                }
                val serviceStopped = awaitCoreStopped(5_000L)
                if (serviceStopped) kotlinx.coroutines.delay(700L)
                var proxyStarted = false
                if (serviceStopped) for (index in candidates.indices) {
                    val candidate = candidates[index]
                    ensureActive()
                    // Count an attempt when it starts, rather than waiting
                    // for a timeout/result. This prevents a long first probe
                    // from leaving the UI at “0 of N”.
                    withContext(Dispatchers.Main) { progress(index + 1, candidates.size) }
                    PingNgDiagnostics.record(
                        "Desync search candidate ${index + 1}/${candidates.size}: " +
                            "${candidate.method} | ${candidate.arguments}"
                    )
                    MmkvManager.encodeServerConfig(
                        guid,
                        original.copy(
                            pingNgProfile = PingNgCompat.PROFILE_CUSTOM,
                            pingNgDesyncArgs = candidate.arguments,
                        )
                    )

                    // Start/restart only the local proxy service. This is still a
                    // real Xray startup and the probe below goes through its SOCKS
                    // inbound, but it never creates an Android VPN interface.
                    withContext(Dispatchers.Main) {
                        mainViewModel.markDesyncTuningProbe()
                        if (proxyStarted) {
                            LauncherManager.reconfigureProxyOnlyService(this@MainActivity, guid)
                        } else {
                            LauncherManager.startProxyOnlyService(this@MainActivity, guid)
                            proxyStarted = true
                        }
                    }
                    if (index == 0 && !awaitCoreRunning(5_000L)) {
                        withContext(Dispatchers.Main) { progress(index + 1, candidates.size) }
                        break
                    }
                    if (index > 0) kotlinx.coroutines.delay(250L)
                    // The first start callback is not emitted again after an
                    // in-place reconfigure. Explicitly request a fresh real
                    // Xray/Desync probe for every candidate.
                    withContext(Dispatchers.Main) {
                        mainViewModel.markDesyncTuningProbe()
                        mainViewModel.testCurrentServerRealPing()
                    }
                    val result = awaitTuningResult(1_500L)
                    if (result != null && result.delayMillis >= 0L) {
                        successful += candidate to result.delayMillis
                        // Publish every successful probe immediately. The dialog keeps
                        // the list sorted, so a faster candidate moves to the top live.
                        withContext(Dispatchers.Main) {
                            report(successful.sortedBy { it.second })
                        }
                        PingNgDiagnostics.record(
                            "Desync search result: ${candidate.method} | " +
                                "${candidate.label} | ${result.delayMillis} ms"
                        )
                        // Continue through the user-selected plan so the
                        // displayed total is the actual number attempted,
                        // not an early-success threshold.
                    }

                    withContext(Dispatchers.Main) { progress(index + 1, candidates.size) }
                    // The next candidate is applied by rebuilding only the
                    // native listener on the same port; Xray stays connected.
                }

                ranked = successful.sortedBy { it.second }
                if (ranked.isNotEmpty()) {
                    DesyncSearchHistory.save(guid, ranked, family, advanced)
                    // Keep the best verified candidate as a visible Custom profile.
                    val best = ranked.first().first
                    MmkvManager.encodeServerConfig(
                        guid,
                        original.copy(
                            pingNgProfile = PingNgCompat.PROFILE_CUSTOM,
                            pingNgDesyncArgs = best.arguments,
                        )
                    )
                    bestConfigSaved = true
                }
            } finally {
                val wasCancelled = !currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    stopDesyncSearchKeepAlive()
                    // Never leave the temporary proxy-only service running.
                    // Restore the user's previous service state after search.
                    if (!bestConfigSaved) {
                        MmkvManager.encodeServerConfig(guid, original)
                    }
                    withContext(Dispatchers.Main) {
                        LauncherManager.stopService(this@MainActivity)
                    }
                    awaitCoreStopped(5_000L)
                    kotlinx.coroutines.delay(700L)
                    if (wasRunning) {
                        withContext(Dispatchers.Main) {
                            requestServiceStart()
                        }
                    }
                    // Cancellation must still return the successful candidates
                    // collected so far. Keep the original profile settings; the
                    // user can explicitly apply any partial result from the list.
                    if (wasCancelled) {
                        ranked = successful.sortedBy { it.second }
                        if (ranked.isNotEmpty()) {
                            DesyncSearchHistory.save(guid, ranked, family, advanced)
                        }
                        withContext(Dispatchers.Main) {
                            report(ranked)
                            if (ranked.isNotEmpty()) {
                                toast(getString(R.string.pingng_desync_search_results, ranked.size))
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (ranked.isNotEmpty()) {
                    mainViewModel.onAction(MainAction.RefreshGroups)
                    report(ranked)
                    toastSuccess(getString(R.string.pingng_desync_search_results, ranked.size))
                } else {
                    report(emptyList())
                    toastError(R.string.pingng_desync_search_failed)
                }
            }
        }
    }

    private fun cancelDesyncSearch() {
        desyncTunerJob?.cancel()
        stopDesyncSearchKeepAlive()
        LauncherManager.stopService(this)
    }

    private fun keepDesyncSearchAlive() {
        val intent = Intent(this, DesyncSearchKeepAliveService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to keep Desync search alive", it)
        }
    }

    private fun stopDesyncSearchKeepAlive() {
        runCatching { stopService(Intent(this, DesyncSearchKeepAliveService::class.java)) }
    }

    private fun applyDesync(guid: String, profile: ProfileItem, args: String) {
        MmkvManager.encodeServerConfig(
            guid,
            profile.copy(
                pingNgProfile = PingNgCompat.PROFILE_CUSTOM,
                pingNgDesyncArgs = args,
            )
        )
        mainViewModel.onAction(MainAction.RefreshGroups)
        if (mainViewModel.uiState.value.isRunning && mainViewModel.uiState.value.selectedGuid == guid) {
            LauncherManager.restartService(this)
        }
        toastSuccess(R.string.toast_success)
    }

    private suspend fun awaitTuningResult(timeoutMs: Long = 7_000L): com.v2ray.ang.dto.ConnectionTestResult? {
        return withTimeoutOrNull(timeoutMs) {
            mainViewModel.uiState
                .map { it.status }
                .filterIsInstance<MainStatus.ConnectionTest>()
                .first()
                .result
        }
    }

    private suspend fun awaitCoreRunning(timeoutMs: Long = 15_000L): Boolean =
        withTimeoutOrNull(timeoutMs) {
            mainViewModel.uiState
                .map { it.isRunning }
                .first { it }
        } != null

    private suspend fun awaitCoreStopped(timeoutMs: Long = 15_000L): Boolean =
        withTimeoutOrNull(timeoutMs) {
            mainViewModel.uiState
                .map { it.isRunning }
                .first { !it }
        } != null

    private suspend fun awaitCoreRestart(): Boolean {
        withTimeoutOrNull(7_000L) {
            mainViewModel.uiState
                .map { it.isRunning }
                .first { !it }
        }
        return awaitCoreRunning()
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
