package com.v2ray.ang.ui.main

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.DnsFullConfigCodec
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.PingNgDiagnostics
import com.v2ray.ang.core.PingNgDesyncTuner
import com.v2ray.ang.core.PingNgDesyncTuner.SearchFamily
import com.v2ray.ang.core.WarpEndpointTester
import com.v2ray.ang.core.WarpMasqueBridge
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.core.WarpWireGuardEndpointTester
import com.v2ray.ang.core.WarpProfileKeyProvisioner
import com.v2ray.ang.core.AwgEndpointScanState
import com.v2ray.ang.core.AwgEndpointScanManager
import com.v2ray.ang.core.AwgWarpConfig
import com.v2ray.ang.core.EndpointScannerCli
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.fmt.OpenVpnProfileCodec
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.DefaultConfigurationSeeder
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.OpenVpnCredentialStore
import com.v2ray.ang.handler.OpenVpnCredentials
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
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
import com.v2ray.ang.ui.server.ServerOpenVpnActivity
import com.v2ray.ang.ui.main.OpenVpnCredentialDialog
import com.v2ray.ang.ui.server.WarpInWarpActivity
import com.v2ray.ang.ui.server.WarpMasqueActivity
import com.v2ray.ang.ui.server.MasterDnsActivity
import com.v2ray.ang.ui.server.ServerSoarActivity
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.core.SoarBridge
import com.v2ray.ang.core.WarpWireGuardConfig
import com.v2ray.ang.service.DesyncSearchKeepAliveService
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import java.io.File

class MainActivity : HelperBaseComponentActivity() {

    private var desyncTunerJob: Job? = null
    private var warpEndpointTestJob: Job? = null
    private var warpKeyProvisionJob: Job? = null
    private var pendingInstallFile: File? = null
    private var pendingMasqueRegistrationGuid: String? = null
    private var pendingDnsFullConfigExportGuid: String? = null
    private var openVpnCredentialPrompt by mutableStateOf<OpenVpnCredentialPrompt?>(null)
    private val masqueRegistrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent ?: return
            val key = message.getIntExtra("key", 0)
            if (key != AppConfig.MSG_MASQUE_REGISTRATION_READY &&
                key != AppConfig.MSG_MASQUE_REGISTRATION_FAILED
            ) return
            val guid = MasqueRegistrationMessagePolicy.registrationGuid(
                key = key,
                content = message.extras?.get("content"),
            ) ?: return
            if (guid != pendingMasqueRegistrationGuid) return
            pendingMasqueRegistrationGuid = null
            AwgEndpointScanState.clear(guid)
            if (mainViewModel.uiState.value.selectedGuid != guid) {
                mainViewModel.setServiceStartPending(false)
                return
            }
            when (key) {
                AppConfig.MSG_MASQUE_REGISTRATION_READY -> {
                    mainViewModel.reloadServerList()
                    startV2Ray()
                }
                AppConfig.MSG_MASQUE_REGISTRATION_FAILED -> {
                    mainViewModel.setServiceStartPending(false)
                    toast("MASQUE/H2 registration failed")
                }
            }
        }
    }

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
                mainViewModel.setServiceStartPending(false)
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

    private val fullDnsConfigExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val guid = pendingDnsFullConfigExportGuid
            pendingDnsFullConfigExportGuid = null
            if (uri == null || guid.isNullOrBlank()) return@registerForActivityResult
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile == null || !DnsFullConfigCodec.isExportableProfile(profile)) {
                toast("This profile cannot be exported as a MasterDNS or SoarDNS config")
                return@registerForActivityResult
            }
            val contents = runCatching { DnsFullConfigCodec.encode(profile) }.getOrElse { error ->
                toast("Could not prepare profile export: ${error.message.orEmpty()}")
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(contents)
                    } ?: error("Could not open the selected file")
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { toastSuccess(R.string.toast_success) }
                        .onFailure { toast("Could not save full config: ${it.message.orEmpty()}") }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDnsFullConfigExportGuid = savedInstanceState?.getString(STATE_PENDING_DNS_EXPORT_GUID)
        ContextCompat.registerReceiver(this, masqueRegistrationReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
        mainViewModel.onAction(MainAction.Initialize)
        mainViewModel.checkForAppUpdate()
        seedDefaultConfigurations()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_DNS_EXPORT_GUID, pendingDnsFullConfigExportGuid)
        super.onSaveInstanceState(outState)
    }

    private fun seedDefaultConfigurations() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    DefaultConfigurationSeeder.seedIfNeeded()
                }
            }.getOrElse { error ->
                LogUtil.e(AppConfig.TAG, "Failed to seed default configurations", error)
                return@launch
            }

            if (result.groupsChanged) {
                mainViewModel.onAction(MainAction.RefreshGroups)
            }

            result.serverlessSubscriptionToFetch?.let { subscription ->
                lifecycleScope.launch(Dispatchers.IO) {
                    DefaultConfigurationSeeder.updateServerless(subscription)
                    withContext(Dispatchers.Main) {
                        mainViewModel.onAction(MainAction.RefreshGroups)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(masqueRegistrationReceiver)
        super.onDestroy()
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        val openVpnIdentityPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val guid = openVpnCredentialPrompt?.guid
            if (uri != null && !guid.isNullOrBlank()) importOpenVpnIdentity(uri, guid)
        }
        MainScreen(
            mainViewModel = mainViewModel,
            onInstallAppUpdate = ::requestInstallAppUpdate,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.StopService -> stopServiceFromButton()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    MainAction.ImportOpenVPN -> importOpenVpn()
                    MainAction.ImportAmneziaWG -> addAmneziaWg()
                    MainAction.AddServerLess -> addServerLessSubscription()
                    MainAction.AddDnstt -> profileEditorLauncher.launch(Intent(this, MasterDnsActivity::class.java).apply {
                        putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
                        putExtra("dnsEngine", MasterDnsBridge.DNSTT)
                    })
                    MainAction.AddMasterDns -> profileEditorLauncher.launch(Intent(this, MasterDnsActivity::class.java).apply {
                        putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
                    })
                    MainAction.AddSoar -> profileEditorLauncher.launch(Intent(this, ServerSoarActivity::class.java).apply {
                        putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
                    })
                    MainAction.AddAwgWarp -> addAwgWarp()
                    MainAction.AddWarpMasque -> addWarpMasque()
                    MainAction.AddWarpWireGuard -> addWarpWireGuard()
                    MainAction.AddWarpInWarp -> addWarpInWarp()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditGroup -> {
                        if (action.groupId.isNotBlank() && MmkvManager.decodeSubscription(action.groupId) != null) {
                            startActivity(Intent(this, com.v2ray.ang.ui.subscription.SubEditActivity::class.java)
                                .putExtra("subId", action.groupId))
                        }
                    }
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.SaveDesync -> saveDesync(action)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    is MainAction.ExportFullConfig -> exportFullDnsConfig(action.guid)
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )
        openVpnCredentialPrompt?.let { prompt ->
            OpenVpnCredentialDialog(
                profileGuid = prompt.guid,
                requirements = prompt.requirements,
                initialCredentials = prompt.credentials,
                onConnect = { credentials -> saveCredentialsAndConnect(prompt.guid, credentials) },
                onChooseCertificate = { openVpnIdentityPicker.launch(arrayOf("*/*")) },
                onDismiss = {
                    openVpnCredentialPrompt = null
                    mainViewModel.setServiceStartPending(false)
                },
            )
        }
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

    private fun exportFullDnsConfig(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid)
        if (profile == null || !DnsFullConfigCodec.isExportableProfile(profile)) {
            toast("This profile cannot be exported as a MasterDNS or SoarDNS config")
            return
        }
        pendingDnsFullConfigExportGuid = guid
        fullDnsConfigExportLauncher.launch(DnsFullConfigCodec.suggestedFileName(profile))
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Logs -> Intent(this, com.v2ray.ang.ui.logcat.LogcatActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
        }
        settingsActivityLauncher.launch(intent)
    }

    override fun onResume() {
        super.onResume()
        pendingInstallFile?.let { file ->
            pendingInstallFile = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                installApk(file)
            }
        }
        mainViewModel.reloadServerList()
    }

    private fun requestInstallAppUpdate(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallFile = file
            toast(R.string.update_install_permission)
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                pendingInstallFile = null
                toastError(R.string.update_download_failed)
            }
        } else {
            installApk(file)
        }
    }

    private fun installApk(file: File) {
        val apkPackageName = getApkPackageName(file)
        if (apkPackageName != packageName) {
            LogUtil.w(AppConfig.TAG, "Downloaded update package does not match the installed app")
            toastError(R.string.update_incompatible_apk)
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.cache",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        }
            .onFailure { toastError(R.string.update_download_failed) }
    }

    private fun getApkPackageName(file: File): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0L),
            )?.packageName
        } else {
            @Suppress("DEPRECATION")
            val packageInfo = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            packageInfo?.packageName
        }
    }

    private fun stopServiceFromButton() {
        pendingMasqueRegistrationGuid = null
        openVpnCredentialPrompt = null
        val state = mainViewModel.uiState.value
        mainViewModel.setServiceStartPending(false)
        mainViewModel.cancelAllPing()

        val awgScan = AwgEndpointScanState.state.value
        if (warpKeyProvisionJob?.isActive == true) warpKeyProvisionJob?.cancel()
        warpKeyProvisionJob = null
        if (warpEndpointTestJob?.isActive == true) warpEndpointTestJob?.cancel()
        AwgEndpointScanManager.cancel()
        EndpointScannerCli.cancelActiveScans()
        mainViewModel.clearEndpointSearchProgress()
        if (awgScan.running) AwgEndpointScanState.clear(awgScan.guid)
        if (desyncTunerJob?.isActive == true) desyncTunerJob?.cancel()
        stopDesyncSearchKeepAlive()

        state.selectedGuid?.let(MmkvManager::decodeServerConfig)
            ?.takeIf { it.configType == EConfigType.WARP }
            ?.let { WarpMasqueBridge.cancelStartup() }
        state.selectedGuid?.let(MmkvManager::decodeServerConfig)
            ?.takeIf(MasterDnsBridge::isProfile)
            ?.let { MasterDnsBridge.cancelStartup() }
        state.selectedGuid?.let(MmkvManager::decodeServerConfig)
            ?.takeIf(SoarBridge::isProfile)
            ?.let { SoarBridge.cancelStartup() }

        LauncherManager.stopService(this)
    }

    private fun handleFabAction() {
        if (warpKeyProvisionJob?.isActive == true) {
            stopServiceFromButton()
            toast(R.string.warp_key_generation_canceled)
            return
        }
        if (warpEndpointTestJob?.isActive == true) {
            val awgScan = AwgEndpointScanState.state.value
            warpEndpointTestJob?.cancel()
            AwgEndpointScanManager.cancel()
            EndpointScannerCli.cancelActiveScans()
            mainViewModel.setServiceStartPending(false)
            mainViewModel.clearEndpointSearchProgress()
            if (awgScan.running) AwgEndpointScanState.clear(awgScan.guid)
            // Endpoint verification may have already started the core/VPN.
            // Cancel the Android service too; cancelling the UI coroutine
            // alone leaves the native probe and VPN foreground service alive.
            stopServiceFromButton()
            toast("Endpoint scan canceled")
            return
        }
        if (AwgEndpointScanManager.isRunning) {
            AwgEndpointScanManager.cancel()
            mainViewModel.uiState.value.selectedGuid?.let { guid ->
                MmkvManager.decodeServerConfig(guid)?.takeIf(AwgWarpConfig::isProfile)?.let { profile ->
                    MmkvManager.encodeServerConfig(guid, profile.copy(awgSkipAutoScanOnce = true))
                }
            }
        }
        if (desyncTunerJob?.isActive == true) {
            stopServiceFromButton()
            return
        }
        val state = mainViewModel.uiState.value
        if (state.isRunning || state.isStarting ||
            state.psiphonStates[state.selectedGuid] == com.v2ray.ang.dto.PsiphonStatus.CONNECTING ||
            state.torStates[state.selectedGuid] == com.v2ray.ang.dto.TorStatus.CONNECTING
        ) {
            stopServiceFromButton()
        } else {
            val selected = state.selectedGuid?.let { MmkvManager.decodeServerConfig(it) }
            mainViewModel.setServiceStartPending(
                pending = true,
                searchingWarp = selected?.configType == EConfigType.WARP,
            )
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        val selectedProfile = mainViewModel.uiState.value.selectedGuid
            ?.let(MmkvManager::decodeServerConfig)
        if (selectedProfile?.configType == EConfigType.OPENVPN) {
            val guid = mainViewModel.uiState.value.selectedGuid ?: return
            lifecycleScope.launch {
                val storedCredentials = withContext(Dispatchers.IO) {
                    OpenVpnCredentialStore.load(applicationContext, guid)
                }
                val embeddedCredentials = withContext(Dispatchers.Default) {
                    OpenVpnProfileCodec.inlineUsernamePasswordCredentials(
                        selectedProfile.openVpnConfig.orEmpty(),
                    )
                }
                val stored = storedCredentials ?: OpenVpnCredentials()
                val credentials = stored.copy(
                    username = stored.username.ifBlank { embeddedCredentials?.username.orEmpty() },
                    password = stored.password.ifBlank { embeddedCredentials?.password.orEmpty() },
                )
                val requirements = OpenVpnProfileCodec.credentialRequirements(
                    selectedProfile.openVpnConfig.orEmpty(),
                    credentials.privateKeyPem,
                    credentials.username,
                    credentials.password,
                )
                if (mainViewModel.uiState.value.selectedGuid != guid) {
                    mainViewModel.setServiceStartPending(false)
                    return@launch
                }
                if (missingOpenVpnCredentials(requirements, credentials)) {
                    openVpnCredentialPrompt = OpenVpnCredentialPrompt(guid, requirements, credentials)
                } else {
                    requestVpnPermissionForCurrentProfile()
                }
            }
            return
        }
        val requiresAndroidVpn = selectedProfile?.configType == EConfigType.AMNEZIAWG ||
            selectedProfile?.configType == EConfigType.OPENVPN
        LogUtil.i(
            AppConfig.TAG,
            "Start button pressed; selected=${!mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()}, " +
                "vpnMode=${SettingsManager.isVpnMode()}, requiresAndroidVpn=$requiresAndroidVpn"
        )
        if (!SettingsManager.isVpnMode() && !requiresAndroidVpn) {
            startV2Ray()
            return
        }
        requestVpnPermissionForCurrentProfile()
    }

    private fun requestVpnPermissionForCurrentProfile() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            LogUtil.i(AppConfig.TAG, "VPN permission is already available")
            startV2Ray()
        } else {
            LogUtil.i(AppConfig.TAG, "Requesting Android VPN permission")
            requestVpnPermission.launch(intent)
        }
    }

    private fun saveCredentialsAndConnect(guid: String, credentials: OpenVpnCredentials) {
        val profile = MmkvManager.decodeServerConfig(guid)
        if (profile?.configType != EConfigType.OPENVPN ||
            mainViewModel.uiState.value.selectedGuid != guid
        ) {
            openVpnCredentialPrompt = null
            mainViewModel.setServiceStartPending(false)
            return
        }
        val requirements = OpenVpnProfileCodec.credentialRequirements(
            profile.openVpnConfig.orEmpty(),
            credentials.privateKeyPem,
            credentials.username,
            credentials.password,
        )
        if (missingOpenVpnCredentials(requirements, credentials)) {
            toast(R.string.openvpn_credentials_required)
            return
        }

        lifecycleScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    OpenVpnCredentialStore.save(applicationContext, guid, credentials)
                }
            }
            if (saved.isFailure) {
                saved.exceptionOrNull()?.let { error ->
                    LogUtil.e(AppConfig.TAG, "Failed to store OpenVPN credentials for profile $guid", error)
                }
                toastError(R.string.toast_failure)
                return@launch
            }
            openVpnCredentialPrompt = null
            requestVpnPermissionForCurrentProfile()
        }
    }

    private fun missingOpenVpnCredentials(
        requirements: OpenVpnProfileCodec.CredentialRequirements,
        credentials: OpenVpnCredentials,
    ): Boolean =
        (requirements.usernamePassword &&
            (credentials.username.isBlank() || credentials.password.isBlank())) ||
            (requirements.privateKeyPassphrase && credentials.privateKeyPassphrase.isBlank()) ||
            (requirements.externalPki && !credentials.disableClientCertificate &&
                !hasOpenVpnPemIdentity(credentials) && credentials.externalPkiAlias.isBlank())

    private fun hasOpenVpnPemIdentity(credentials: OpenVpnCredentials): Boolean =
        credentials.clientCertificatePem.isNotBlank() && credentials.privateKeyPem.isNotBlank()

    private fun importOpenVpnIdentity(uri: Uri, guid: String) {
        lifecycleScope.launch {
            val material = try {
                val content = withContext(Dispatchers.IO) {
                    readBoundedText(uri, 128 * 1024, R.string.openvpn_identity_file_invalid)
                }
                OpenVpnProfileCodec.readClientIdentityMaterial(content)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                toast(error.message ?: getString(R.string.openvpn_identity_file_invalid))
                return@launch
            }
            if (material.certificatePem.isBlank() && material.privateKeyPem.isBlank()) {
                toast(R.string.openvpn_identity_file_invalid)
                return@launch
            }
            val prompt = openVpnCredentialPrompt?.takeIf { it.guid == guid } ?: return@launch
            val credentials = prompt.credentials.copy(
                clientCertificatePem = material.certificatePem.ifBlank {
                    prompt.credentials.clientCertificatePem
                },
                privateKeyPem = material.privateKeyPem.ifBlank {
                    prompt.credentials.privateKeyPem
                },
            ).let { updated ->
                if (hasOpenVpnPemIdentity(updated)) updated.copy(externalPkiAlias = "") else updated
            }
            openVpnCredentialPrompt = prompt.copy(credentials = credentials)
            toast(R.string.openvpn_identity_imported)
        }
    }

    private fun readBoundedText(uri: Uri, maxCharacters: Int, errorMessageRes: Int): String {
        val input = contentResolver.openInputStream(uri) ?: error(getString(errorMessageRes))
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8_192)
            while (true) {
                val remaining = maxCharacters + 1 - text.length
                if (remaining <= 0) error(getString(errorMessageRes))
                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                text.append(buffer, 0, count)
            }
            require(text.length <= maxCharacters) { getString(errorMessageRes) }
            text.toString()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray(skipWarpKeyCheck: Boolean = false) {
        val guid = mainViewModel.uiState.value.selectedGuid
        if (guid.isNullOrEmpty()) {
            LogUtil.w(AppConfig.TAG, "Start canceled because no configuration is selected")
            mainViewModel.setServiceStartPending(false)
            toast(R.string.title_file_chooser)
            return
        }
        val selected = MmkvManager.decodeServerConfig(guid)
        if (selected?.configType == EConfigType.OPENVPN) {
            startV2RayNow()
            return
        }
        if (!skipWarpKeyCheck && WarpProfileKeyProvisioner.supports(selected)) {
            if (warpKeyProvisionJob?.isActive == true) return
            warpKeyProvisionJob = lifecycleScope.launch {
                val ownJob = currentCoroutineContext()[Job]
                try {
                    val missingKey = withContext(Dispatchers.IO) {
                        WarpProfileKeyProvisioner.needsProvisioning(guid, selected)
                    }
                    if (missingKey) {
                        AwgEndpointScanState.begin(guid, getString(R.string.warp_key_generation_progress))
                    }
                    val changed = if (missingKey) {
                        WarpProfileKeyProvisioner.provisionIfNeeded(applicationContext, guid)
                    } else false
                    if (mainViewModel.uiState.value.selectedGuid != guid) {
                        AwgEndpointScanState.clear(guid)
                        mainViewModel.setServiceStartPending(false)
                        return@launch
                    }
                    if (changed) {
                        mainViewModel.reloadServerList()
                    }
                    if (missingKey) AwgEndpointScanState.clear(guid)
                    warpKeyProvisionJob = null
                    startV2Ray(skipWarpKeyCheck = true)
                } catch (cancelled: CancellationException) {
                    AwgEndpointScanState.clear(guid)
                    mainViewModel.setServiceStartPending(false)
                    throw cancelled
                } catch (error: Throwable) {
                    AwgEndpointScanState.fail(guid, getString(R.string.warp_key_generation_failed))
                    mainViewModel.setServiceStartPending(false)
                    PingNgDiagnostics.record("WARP account key generation failed", error)
                    LogUtil.e(AppConfig.TAG, "WARP account key generation failed", error)
                    toast(R.string.warp_key_generation_failed)
                } finally {
                    if (warpKeyProvisionJob === ownJob) warpKeyProvisionJob = null
                }
            }
            return
        }
        if (AwgWarpConfig.isProfile(selected)) {
            if (selected?.autoScanEndpoint == false && selected.awgSkipAutoScanOnce == true) {
                MmkvManager.encodeServerConfig(guid, selected.copy(awgSkipAutoScanOnce = false))
                AwgEndpointScanState.clear(guid)
                startV2RayNow()
                return
            }
            if (selected?.autoScanEndpoint == false) {
                AwgEndpointScanState.clear(guid)
                startV2RayNow()
                return
            }
            if (warpEndpointTestJob?.isActive == true) return
            warpEndpointTestJob = lifecycleScope.launch {
                try {
                    val profile = MmkvManager.decodeServerConfig(guid)
                        ?: throw IllegalStateException("WARP AWG configuration was not found")
                    AwgEndpointScanManager.start(this@MainActivity, guid, profile, false).await()
                    mainViewModel.reloadServerList()
                    startV2RayNow()
                } catch (error: CancellationException) {
                    val state = AwgEndpointScanState.state.value
                    if (state.guid == guid) AwgEndpointScanState.clear(guid)
                    mainViewModel.setServiceStartPending(false)
                    throw error
                } catch (error: Throwable) {
                    val safeError = "Endpoint scan failed"
                    AwgEndpointScanState.fail(guid, safeError)
                    mainViewModel.setServiceStartPending(false)
                    toast("Endpoint scan failed")
                } finally {
                    warpEndpointTestJob = null
                }
            }
            return
        }
        if (WarpWireGuardConfig.isProfile(selected)) {
            if (selected?.autoScanEndpoint == false ||
                (selected?.autoScanEndpoint == null &&
                    WarpWireGuardConfig.normalizeMode(selected?.warpEndpointTestMode) ==
                        WarpWireGuardConfig.ENDPOINT_MODE_CUSTOM)
            ) {
                AwgEndpointScanState.clear(guid)
                startV2RayNow()
                return
            }
            if (warpEndpointTestJob?.isActive == true) return
            warpEndpointTestJob = lifecycleScope.launch {
                try {
                    val found = withContext(Dispatchers.IO) {
                        WarpWireGuardEndpointTester.testAndSelect(applicationContext, guid)
                    }
                    if (!found) {
                        mainViewModel.setServiceStartPending(false)
                        mainViewModel.clearEndpointSearchProgress()
                        toast("No working WARP WireGuard endpoint was found")
                    } else {
                        mainViewModel.reloadServerList()
                        startV2RayNow()
                    }
                } catch (error: CancellationException) {
                    mainViewModel.setServiceStartPending(false)
                    mainViewModel.clearEndpointSearchProgress()
                    throw error
                } catch (error: Throwable) {
                    mainViewModel.setServiceStartPending(false)
                    mainViewModel.clearEndpointSearchProgress()
                    LogUtil.e(AppConfig.TAG, "WARP WireGuard endpoint test failed", error)
                    AwgEndpointScanState.fail(guid, "WARP WireGuard endpoint scan failed")
                    toast("WARP WireGuard endpoint test failed")
                } finally {
                    warpEndpointTestJob = null
                }
            }
            return
        }
        if (selected != null && WarpPlusConfig.isDescription(selected.description)) {
            if (selected.autoScanEndpoint == false ||
                (selected.autoScanEndpoint == null &&
                    WarpPlusConfig.normalizeEndpointTestMode(selected.warpEndpointTestMode) ==
                        WarpPlusConfig.ENDPOINT_MODE_CUSTOM)
            ) {
                AwgEndpointScanState.clear(guid)
                startV2RayNow()
                return
            }
            if (warpEndpointTestJob?.isActive == true) return
            warpEndpointTestJob = lifecycleScope.launch {
                try {
                    val selectedEndpoint = withContext(Dispatchers.IO) {
                        WarpEndpointTester.testAndSelect(applicationContext, guid)
                    }
                    if (!selectedEndpoint) {
                        mainViewModel.setServiceStartPending(false)
                        mainViewModel.clearEndpointSearchProgress()
                        toast("No working WARP Plus endpoint was found")
                    } else {
                        // The tester persists the selected pair on the parent profile.
                        // Refresh the visible row before the service starts so the
                        // chosen endpoint is immediately visible on the main page.
                        mainViewModel.reloadServerList()
                        startV2RayNow()
                    }
                } catch (error: CancellationException) {
                    mainViewModel.setServiceStartPending(false)
                    mainViewModel.clearEndpointSearchProgress()
                    throw error
                } catch (error: Throwable) {
                    mainViewModel.setServiceStartPending(false)
                    mainViewModel.clearEndpointSearchProgress()
                    LogUtil.e(AppConfig.TAG, "WARP Plus endpoint test failed", error)
                    AwgEndpointScanState.fail(guid, "WARP Plus endpoint scan failed")
                    toast("WARP Plus endpoint test failed")
                } finally {
                    warpEndpointTestJob = null
                }
            }
            return
        }
        if (selected != null && selected.configType == EConfigType.WARP &&
            WarpMasqueConfig.isDescription(selected.description)
        ) {
            if (!com.v2ray.ang.core.WarpRegistrationProxy.hasMasqueRegistrationReady(
                    applicationContext, guid, selected)) {
                if (pendingMasqueRegistrationGuid == guid) return
                pendingMasqueRegistrationGuid = guid
                AwgEndpointScanState.begin(guid, "Obtaining MASQUE/H2 authorization…")
                try {
                    LauncherManager.prepareMasqueRegistration(this, guid)
                } catch (error: Throwable) {
                    pendingMasqueRegistrationGuid = null
                    AwgEndpointScanState.clear(guid)
                    mainViewModel.setServiceStartPending(false)
                    PingNgDiagnostics.record("MASQUE/H2 registration service failed to start", error)
                    toast("MASQUE/H2 registration failed")
                }
                return
            }
            if (!WarpMasqueConfig.shouldAutoScan(selected)) {
                AwgEndpointScanState.clear(guid)
                startV2RayNow()
                return
            }
            if (warpEndpointTestJob?.isActive == true) return
            MmkvManager.encodeServerConfig(guid, selected.copy(warpMasqueScanPreparedOnce = false))
            warpEndpointTestJob = lifecycleScope.launch {
                try {
                    val candidates = withContext(Dispatchers.IO) {
                        EndpointScannerCli.scanMasqueH2Endpoints(
                            applicationContext,
                            guid,
                            WarpMasqueConfig.normalizeSni(selected.warpMasqueSni),
                            com.v2ray.ang.core.EndpointScanSettings.load(guid),
                        )
                    }
                    val endpoint = candidates.first().endpoint
                    val (host, port) = AmneziaWgFmt.splitEndpoint(endpoint)
                    val endpointHost = host?.takeIf(String::isNotBlank)
                        ?: error("MASQUE/H2 endpoint scan returned an invalid host")
                    val endpointPort = port?.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: error("MASQUE/H2 endpoint scan returned an invalid port")
                    val current = MmkvManager.decodeServerConfig(guid) ?: selected
                    MmkvManager.encodeServerConfig(guid, current.copy(
                        warpMasqueSelectedEndpoint = endpoint,
                        warpMasquePrimaryEndpoint = endpointHost,
                        warpMasqueEndpointPort = endpointPort,
                        warpMasqueEndpointMode = WarpMasqueConfig.ENDPOINT_MODE_AUTO,
                        autoScanEndpoint = true,
                        warpMasqueScanPreparedOnce = true,
                        awgEndpointCandidates = EndpointScannerCli.encodeCandidates(candidates),
                        server = endpointHost,
                        serverPort = endpointPort.toString(),
                    ))
                    mainViewModel.reloadServerList()
                    startV2RayNow()
                } catch (cancelled: CancellationException) {
                    AwgEndpointScanState.clear(guid)
                    mainViewModel.setServiceStartPending(false)
                    throw cancelled
                } catch (error: Throwable) {
                    AwgEndpointScanState.fail(guid, "MASQUE/H2 endpoint scan failed")
                    PingNgDiagnostics.record("MASQUE/H2 endpoint scan failed", error)
                    mainViewModel.setServiceStartPending(false)
                    toast("MASQUE/H2 endpoint scan failed")
                } finally {
                    warpEndpointTestJob = null
                }
            }
            return
        }
        startV2RayNow()
    }

    private fun startV2RayNow() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            mainViewModel.setServiceStartPending(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        if (!LauncherManager.startService(this)) {
            mainViewModel.setServiceStartPending(false)
        }
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

    private fun addWarpInWarp() {
        val intent = Intent(this, WarpInWarpActivity::class.java).apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun addWarpMasque() {
        val intent = Intent(this, WarpMasqueActivity::class.java).apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun addWarpWireGuard() {
        val intent = Intent(this, ServerWireguardActivity::class.java).apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
            putExtra("warpWireGuard", true)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun addAmneziaWg() {
        val intent = Intent(this, ServerWireguardActivity::class.java).apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
            putExtra("amneziaWg", true)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun addAwgWarp() {
        val intent = Intent(this, ServerWireguardActivity::class.java).apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
            putExtra("awgWarp", true)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
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

    private fun importConfigLocal(forceAmneziaWg: Boolean = false) {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    }
                    withContext(Dispatchers.Main) {
                        if (content != null) {
                            mainViewModel.onAction(
                                MainAction.ImportBatchConfig(content, forceAmneziaWg)
                            )
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
                }
            }
        }
    }

    private fun importOpenVpn() {
        launchFileChooser("*/*") { uri ->
            if (uri == null) return@launchFileChooser
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    val fileName = contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        } else null
                    }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }

                    withContext(Dispatchers.Main) {
                        if (content.isNullOrBlank() || !OpenVpnProfileCodec.looksLikeOpenVpn(content)) {
                            toast(R.string.openvpn_import_invalid)
                            return@withContext
                        }
                        profileEditorLauncher.launch(
                            Intent(this@MainActivity, ServerOpenVpnActivity::class.java).apply {
                                putExtra(ServerOpenVpnActivity.EXTRA_CONFIG_TEXT, content)
                                putExtra(ServerOpenVpnActivity.EXTRA_FILE_NAME, fileName)
                                putExtra(
                                    ServerOpenVpnActivity.EXTRA_SUBSCRIPTION_ID,
                                    mainViewModel.uiState.value.selectedGroupId,
                                )
                            }
                        )
                    }
                } catch (error: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import OpenVPN profile", error)
                    withContext(Dispatchers.Main) { toast(R.string.openvpn_import_invalid) }
                }
            }
        }
    }

    private fun addServerLessSubscription() {
        val existing = MmkvManager.decodeSubscriptions().firstOrNull {
            it.subscription.url == AppConfig.SERVERLESS_SUBSCRIPTION_URL
        }
        val subscriptionId = existing?.guid ?: AppConfig.SERVERLESS_SUBSCRIPTION_ID
        val subscription = existing?.subscription ?: SubscriptionItem(
            remarks = AppConfig.SERVERLESS_SUBSCRIPTION_REMARKS,
            url = AppConfig.SERVERLESS_SUBSCRIPTION_URL,
            enabled = true,
            autoUpdate = false,
        )

        if (existing == null) {
            MmkvManager.encodeSubscription(subscriptionId, subscription)
            mainViewModel.onAction(MainAction.RefreshGroups)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.updateConfigViaSub(
                SubscriptionCache(subscriptionId, subscription)
            )
            withContext(Dispatchers.Main) {
                mainViewModel.onAction(MainAction.RefreshGroups)
                if (result.successCount > 0) {
                    toastSuccess(R.string.toast_serverless_subscription_added)
                } else {
                    toastError(R.string.toast_serverless_subscription_failed)
                }
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> if (isWarpInWarpProfile(guid, profile)) {
                WarpInWarpActivity::class.java
            } else {
                ServerProxyChainActivity::class.java
            }
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> when {
                SoarBridge.isProfile(profile) -> ServerSoarActivity::class.java
                MasterDnsBridge.isProfile(profile) -> MasterDnsActivity::class.java
                else -> ServerSocksActivity::class.java
            }
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.AMNEZIAWG -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            EConfigType.OPENVPN -> ServerOpenVpnActivity::class.java
            EConfigType.WARP -> WarpMasqueActivity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
            putExtra("warpWireGuard", WarpWireGuardConfig.isProfile(profile))
        }
        profileEditorLauncher.launch(intent)
    }

    private fun isWarpInWarpProfile(guid: String, profile: ProfileItem): Boolean {
        // The marker is the durable identity of generated WARP Plus profiles.
        // The old managedBy-only check misclassified profiles after import,
        // backup restore, or older builds and opened the generic Proxy Chain
        // editor instead of the WARP Plus editor.
        if (WarpPlusConfig.isDescription(profile.description)) return true

        return profile.proxyChainProfiles.orEmpty().split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { SettingsManager.getServerViaRemarks(it) }
            .count { it.managedBy == guid && it.configType == EConfigType.WIREGUARD } >= 2
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

    companion object {
        private const val STATE_PENDING_DNS_EXPORT_GUID = "pending_dns_full_config_export_guid"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private data class OpenVpnCredentialPrompt(
    val guid: String,
    val requirements: OpenVpnProfileCodec.CredentialRequirements,
    val credentials: OpenVpnCredentials,
)
