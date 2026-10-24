package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.PsiphonStatus
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TorStatus
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.belongsToSubscriptionGroup
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.SubscriptionNoticeParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import com.v2ray.ang.extension.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

internal fun ConnectionTestResult.isTunnelReachableWithoutDelay(): Boolean =
    delayMillis < 0 && !ipAddress.isNullOrBlank()

/** Loads the visible group first, then warms other group pages in the background. */
internal object InitialGroupLoadPolicy {
    fun groupIds(groupIds: List<String>, selectedGroupId: String): List<String> =
        groupIds.firstOrNull { it == selectedGroupId }?.let { listOf(it) }.orEmpty()

    fun preloadGroupIds(groupIds: List<String>, selectedGroupId: String): List<String> {
        val selectedIndex = groupIds.indexOf(selectedGroupId)
        if (selectedIndex < 0) return groupIds

        return buildList {
            for (distance in 1 until groupIds.size) {
                groupIds.getOrNull(selectedIndex + distance)?.let(::add)
                groupIds.getOrNull(selectedIndex - distance)?.let(::add)
            }
        }
    }
}

data class AppUpdateDownloadState(
    val isDownloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val downloadedFile: File? = null,
) {
    val progress: Float?
        get() = totalBytes.takeIf { it > 0L }
            ?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }

    val progressPercent: Int?
        get() = progress?.let { (it * 100).toInt() }
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val groupPreloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _appUpdate = MutableStateFlow<CheckUpdateResult?>(null)
    val appUpdate: StateFlow<CheckUpdateResult?> = _appUpdate.asStateFlow()
    private var appUpdateCheckJob: Job? = null

    private val _appUpdateDownload = MutableStateFlow(AppUpdateDownloadState())
    val appUpdateDownload: StateFlow<AppUpdateDownloadState> = _appUpdateDownload.asStateFlow()
    private var appUpdateDownloadJob: Job? = null

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupPageFlows = ConcurrentHashMap<String, MutableStateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()
    private val psiphonReconnectStatusJobs = mutableMapOf<String, Job>()

    private var setupGroupJob: Job? = null
    private var groupPreloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var defaultTrafficUsageJob: Job? = null
    private val pingRequests = MainPingRequests()
    private class ActivePingTest(val request: MainPingRequests.Request) {
        @Volatile var finishing: Boolean = false
    }
    private val activePingTests = ConcurrentHashMap<String, ActivePingTest>()
    private val testResultFlushJobs = ConcurrentHashMap<String, Job>()
    private val pendingTestResults = mutableMapOf<String, LinkedHashMap<String, Long>>()
    private val pendingTestResultsLock = Any()

    private val initialPageReady = CompletableDeferred<Unit>()

    /** Silently checks the stable GitHub release so offline startup remains unaffected. */
    fun checkForAppUpdate() {
        if (_appUpdate.value != null || appUpdateCheckJob?.isActive == true) return
        appUpdateCheckJob = viewModelScope.launch(ioDispatcher) {
            val result = try {
                UpdateCheckerManager.checkForUpdate(includePreRelease = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Update checks are optional: an offline or blocked connection must not
                // surface an error or interfere with opening the main screen.
                LogUtil.d(AppConfig.TAG, "Automatic update check skipped: ${error.message.orEmpty()}")
                return@launch
            }

            val version = result.latestVersion ?: return@launch
            val dismissedVersion = MmkvManager.decodeSettingsString(
                AppConfig.PREF_DISMISSED_APP_UPDATE_VERSION
            )
            if (result.hasUpdate && version != dismissedVersion) {
                _appUpdate.value = result
            }
        }
    }

    fun dismissAppUpdate() {
        cancelAppUpdateDownload()
        _appUpdate.value?.latestVersion?.let { version ->
            MmkvManager.encodeSettings(AppConfig.PREF_DISMISSED_APP_UPDATE_VERSION, version)
        }
        _appUpdate.value = null
    }

    fun downloadAppUpdate(result: CheckUpdateResult) {
        val downloadUrl = result.downloadUrl ?: return
        if (appUpdateDownloadJob?.isActive == true ||
            _appUpdateDownload.value.downloadedFile != null
        ) return

        _appUpdateDownload.value = AppUpdateDownloadState(
            isDownloading = true,
            totalBytes = result.downloadSizeBytes?.takeIf { it > 0L } ?: -1L,
        )
        appUpdateDownloadJob = viewModelScope.launch(ioDispatcher) {
            try {
                val file = UpdateCheckerManager.downloadApk(
                    context = app,
                    downloadUrl = downloadUrl,
                    version = result.latestVersion.orEmpty(),
                    expectedSizeBytes = result.downloadSizeBytes,
                ) { downloadedBytes, totalBytes ->
                    _appUpdateDownload.value = AppUpdateDownloadState(
                        isDownloading = true,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    )
                }
                val fileLength = file.length()
                _appUpdateDownload.value = AppUpdateDownloadState(
                    downloadedBytes = fileLength,
                    totalBytes = fileLength,
                    downloadedFile = file,
                )
            } catch (error: CancellationException) {
                _appUpdateDownload.value = AppUpdateDownloadState()
                throw error
            } catch (error: Exception) {
                _appUpdateDownload.value = AppUpdateDownloadState()
                LogUtil.w(AppConfig.TAG, "App update download failed: ${error.message.orEmpty()}")
                toastError(R.string.update_download_failed)
            }
        }
    }

    private fun cancelAppUpdateDownload() {
        appUpdateDownloadJob?.cancel()
        appUpdateDownloadJob = null
        _appUpdateDownload.value = AppUpdateDownloadState()
    }

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        setupGroupTab()
        defaultTrafficUsageJob = viewModelScope.launch(ioDispatcher) {
            while (true) {
                updateDefaultTrafficUsage(dataSource.getDefaultTrafficUsedBytes())
                delay(2_000L)
            }
        }
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                val message = event.message.ifBlank {
                    getString(R.string.toast_services_failure)
                }
                toastError(message)
                updateRunningState(false)
            }

            MainServiceEvent.StateStopSuccess -> updateRunningState(false)
            is MainServiceEvent.MeasureDelayResult -> {
                updateConnectionTestServerRow(event.result)
                _uiState.update { it.copy(status = MainStatus.ConnectionTest(event.result)) }
            }

            is MainServiceEvent.PsiphonStatusChanged -> {
                updatePsiphonStatus(event.status)
            }

            is MainServiceEvent.TorStatusChanged -> {
                updateTorStatus(event.status)
            }

            is MainServiceEvent.SubscriptionUpdated -> {
                // The subscription service writes MMKV from a worker process. Reload
                // this group so its notice and server list become visible immediately.
                viewModelScope.launch(ioDispatcher) {
                    cacheMutex.withLock { groupDataCache.remove(event.subscriptionId) }
                    setupGroupTab(forceRefresh = true)
                }
            }

            is MainServiceEvent.MeasureConfigSuccess -> queueTestResult(event.result, event.requestId)

            is MainServiceEvent.MeasureConfigNotify -> {
                if (activePingTest(event.requestId)?.finishing == false) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                }
            }
            is MainServiceEvent.MasterDnsProgress -> {
                _uiState.update { state ->
                    if (state.isStarting && state.selectedGuid == event.guid) {
                        state.copy(status = MainStatus.MasterDnsProgress(event.completed, event.total, event.valid))
                    } else state
                }
            }
            is MainServiceEvent.SoarProgress -> {
                _uiState.update { state ->
                    if (state.isStarting && state.selectedGuid == event.guid) {
                        state.copy(status = MainStatus.SoarProgress(event.completed, event.total, event.valid))
                    } else state
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                onTestsFinished(event.requestId)
            }

            is MainServiceEvent.MeasureConfigCancelled -> onTestsCancelled(event.requestId)
        }
    }

    private fun queueTestResult(result: RealPingResult, requestId: String) {
        val test = activePingTest(requestId) ?: return
        if (test.finishing) return
        synchronized(pendingTestResultsLock) {
            pendingTestResults.getOrPut(requestId) { linkedMapOf() }[result.guid] = result.delayMillis
        }
        if (testResultFlushJobs[requestId]?.isActive == true) return

        testResultFlushJobs[requestId] = viewModelScope.launch {
            while (hasPendingTestResults(requestId)) {
                delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(test, requestId)
            }
            testResultFlushJobs.remove(requestId)
        }
    }

    private fun hasPendingTestResults(requestId: String): Boolean = synchronized(pendingTestResultsLock) {
        !pendingTestResults[requestId].isNullOrEmpty()
    }

    private suspend fun flushPendingTestResults(test: ActivePingTest, requestId: String) {
        if (!isPingTestActive(requestId)) return
        val groupId = test.request.groupId
        val updates = synchronized(pendingTestResultsLock) {
            pendingTestResults.remove(requestId).orEmpty().toMap()
        }
        if (updates.isEmpty()) return

        cacheMutex.withLock {
            if (!isPingTestActive(requestId)) return@withLock
            groupDataCache[groupId]?.let { cached ->
                groupDataCache[groupId] = applyTestDelayResults(cached, updates)
            }
        }
        if (!isPingTestActive(requestId)) return
        mutableServersForGroup(groupId).update { current ->
            applyTestDelayResults(current, updates)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJobs.values.forEach { it.cancel() }
        testResultFlushJobs.clear()
        synchronized(pendingTestResultsLock) { pendingTestResults.clear() }
    }

    private fun cancelPingRequest(requestId: String) {
        if (requestId.isBlank()) return
        activePingTests.remove(requestId)
        testResultFlushJobs.remove(requestId)?.cancel()
        synchronized(pendingTestResultsLock) { pendingTestResults.remove(requestId) }
        dataSource.sendMsg2TestService(
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG_CANCEL,
                requestId = requestId,
            ),
        )
    }

    private fun activePingTest(requestId: String): ActivePingTest? =
        if (requestId.isBlank()) null else activePingTests[requestId]

    private fun isPingTestActive(requestId: String): Boolean =
        pingRequests.get(requestId) != null && activePingTest(requestId) != null

    private fun updatePingTestingState(status: MainStatus? = null) {
        val activeGroups = pingRequests.activeGroupIds()
        _uiState.update { state ->
            state.copy(
                isTesting = activeGroups.isNotEmpty(),
                testingGroupIds = activeGroups,
                status = status ?: if (activeGroups.isNotEmpty()) MainStatus.Testing
                else if (state.isRunning) MainStatus.Connected else MainStatus.Disconnected,
            )
        }
    }

    /** Applies live exit information and standalone OpenVPN probe results to server rows. */
    private fun updateConnectionTestServerRow(result: ConnectionTestResult) {
        val guid = result.profileGuid?.takeIf(String::isNotBlank)
            ?: uiState.value.selectedGuid?.takeIf(String::isNotBlank)
            ?: return
        viewModelScope.launch(ioDispatcher) {
            val profile = dataSource.decodeServerConfig(guid) ?: return@launch

            if (!result.ipAddress.isNullOrBlank() || !result.countryCode.isNullOrBlank()) {
                cacheMutex.withLock {
                    groupDataCache.entries.forEach { entry ->
                        if (entry.value.any { it.guid == guid }) {
                            entry.setValue(
                                applyServerExitInfo(
                                    entry.value,
                                    guid,
                                    result.countryCode,
                                    result.ipAddress,
                                ),
                            )
                        }
                    }
                }
                groupPageFlows.values.forEach { flow ->
                    flow.update {
                        applyServerExitInfo(it, guid, result.countryCode, result.ipAddress)
                    }
                }
            }

            if (profile.configType != EConfigType.OPENVPN || result.profileGuid.isNullOrBlank()) return@launch

            dataSource.encodeServerTestDelayMillis(guid, result.delayMillis)
            val updates = mapOf(guid to result.delayMillis)
            cacheMutex.withLock {
                groupDataCache.entries.forEach { entry ->
                    if (entry.value.any { it.guid == guid }) {
                        entry.setValue(applyTestDelayResults(entry.value, updates))
                    }
                }
            }
            groupPageFlows.values.forEach { flow ->
                flow.update { current -> applyTestDelayResults(current, updates) }
            }
        }
    }

    /** Hides brief Psiphon tunnel handovers from the server-row status badge. */
    private fun updatePsiphonStatus(status: PsiphonStatus) {
        val guid = status.guid
        val previousState = _uiState.value.psiphonStates[guid]

        if (status.state == PsiphonStatus.CONNECTING && previousState == PsiphonStatus.CONNECTED) {
            // Psiphon can announce a short tunnel handover after the SOCKS
            // listener has already connected. Keep the green badge during
            // that brief transition; a real, longer outage still turns red.
            if (psiphonReconnectStatusJobs[guid]?.isActive == true) return
            val job = viewModelScope.launch {
                delay(5_000L)
                if (psiphonReconnectStatusJobs[guid] !== currentCoroutineContext()[Job]) return@launch
                psiphonReconnectStatusJobs.remove(guid)
                _uiState.update { current ->
                    if (current.psiphonStates[guid] != PsiphonStatus.CONNECTED) return@update current
                    current.copy(
                        psiphonStates = current.psiphonStates.toMutableMap().apply {
                            this[guid] = PsiphonStatus.CONNECTING
                        },
                    )
                }
            }
            psiphonReconnectStatusJobs[guid] = job
            return
        }

        psiphonReconnectStatusJobs.remove(guid)?.cancel()
        _uiState.update { current ->
            val states = current.psiphonStates.toMutableMap()
            when (status.state) {
                PsiphonStatus.STOPPED -> states.remove(guid)
                else -> states[guid] = status.state
            }
            current.copy(psiphonStates = states)
        }
    }

    private fun updateTorStatus(status: TorStatus) {
        _uiState.update { current ->
            val states = current.torStates.toMutableMap()
            when (status.state) {
                TorStatus.STOPPED -> states.remove(status.guid)
                else -> states[status.guid] = status.state
            }
            current.copy(torStates = states)
        }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        MainStatus.WarpSearching -> dataSource.getString(R.string.warp_endpoint_searching)
        is MainStatus.MasterDnsProgress -> "DNS: ${status.completed}/${status.total} | Valid: ${status.valid}"
        is MainStatus.SoarProgress -> "${status.completed}/${status.total} Valid: ${status.valid}"
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else if (result.isTunnelReachableWithoutDelay()) {
            // Delay URLs can be blocked while the tunnel itself is healthy. An
            // independently verified exit IP is positive tunnel evidence.
            dataSource.getString(R.string.connection_connected)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        val ip = result.ipAddress?.trim().orEmpty()
        return if (ip.isNotEmpty()) "$status\n$ip" else status
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupPageFlows.computeIfAbsent(groupId) { MutableStateFlow(emptyList()) }
            .asStateFlow()

    private fun mutableServersForGroup(groupId: String): MutableStateFlow<List<ServersCache>> =
        groupPageFlows.computeIfAbsent(groupId) { MutableStateFlow(emptyList()) }

    private fun currentServers(): List<ServersCache> =
        mutableServersForGroup(uiState.value.selectedGroupId).value

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            is MainAction.TestRealSubscription -> testSubscriptionRealPing(action.groupId)
            is MainAction.ConfigureDefaultTrafficGraph -> configureDefaultTrafficGraph(
                action.limitBytes,
                action.periodDays,
            )
            MainAction.ResetDefaultTrafficGraph -> resetDefaultTrafficGraph()
            is MainAction.ConfigureSubscriptionTrafficGraph -> configureSubscriptionTrafficGraph(
                action.subscriptionId,
                action.limitBytes,
                action.periodDays,
            )
            is MainAction.ResetSubscriptionTrafficGraph -> resetSubscriptionTrafficGraph(action.subscriptionId)
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> importConfigViaSub()
            is MainAction.UpdateSubscription -> importConfigViaSub(action.groupId)
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(
                action.configText,
                action.forceAmneziaWg,
            )
            is MainAction.LocateHandled -> consumeLocateTarget(action.target)
            is MainAction.ShareQRCode -> {
                viewModelScope.launch(defaultDispatcher) {
                    val bitmap = dataSource.share2QRCode(action.guid)
                    _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
                    if (bitmap == null) toastError(R.string.toast_failure)
                }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.StopService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            MainAction.ImportOpenVPN,
            MainAction.ImportAmneziaWG,
            MainAction.AddServerLess,
            MainAction.AddAwgWarp,
            MainAction.AddWarpMasque,
            MainAction.AddWarpWireGuard,
            MainAction.AddWarpInWarp,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.SaveDesync,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent,
            is MainAction.ExportFullConfig -> {
                // Handled by Activity via its onAction lambda
            }
            MainAction.AddMasterDns, MainAction.AddDnstt, MainAction.AddSoar -> Unit // The editor is opened by MainActivity.
        }
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    /** Keeps the connect button cancellable while the foreground service starts. */
    fun setServiceStartPending(pending: Boolean, searchingWarp: Boolean = false) {
        _uiState.update { state ->
            state.copy(
                isStarting = pending,
                status = when {
                    pending && searchingWarp -> MainStatus.WarpSearching
                    !pending && (state.status == MainStatus.WarpSearching ||
                        state.status is MainStatus.MasterDnsProgress || state.status is MainStatus.SoarProgress) -> MainStatus.Disconnected
                    else -> state.status
                },
            )
        }
    }

    fun setEndpointSearchProgress(progress: String) {
        _uiState.update { it.copy(status = MainStatus.TestProgress(progress)) }
    }

    fun clearEndpointSearchProgress() {
        _uiState.update { current ->
            if (current.status is MainStatus.TestProgress) {
                current.copy(status = if (current.isRunning) MainStatus.Connected else MainStatus.Disconnected)
            } else {
                current
            }
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(groupId: String, guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val belongsToGroup = if (groupId.isEmpty()) {
                profile.managedBy.isNullOrBlank()
            } else {
                belongsToSubscriptionGroup(profile, groupId)
            }
            if (!belongsToGroup) return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = visiblePingDelayMillis(profile, affiliation?.testDelayMillis ?: 0L)
            )
        }

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(groupId, dataSource.getRawServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock { groupDataCache[groupId] = servers }
            servers
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>): List<ServersCache> {
        val keyword = keywordFilter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    private fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        val visibleServers = applyKeywordFilter(servers)
        mutableServersForGroup(groupId).value = visibleServers
        _uiState.update { state ->
            val index = state.groups.indexOfFirst { it.id == groupId }
            if (index < 0 || state.groups[index].serverCount == visibleServers.size) {
                state
            } else {
                state.copy(
                    groups = state.groups.toMutableList().apply {
                        this[index] = this[index].copy(serverCount = visibleServers.size)
                    },
                )
            }
        }
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    fun allServerProfilesForDesyncSearch(): List<Pair<String, ProfileItem>> {
        // The “All” group is optional in settings, but the search dialog must
        // still see every stored profile. Otherwise a disabled All tab can
        // produce an empty plan and the UI displays “0 of 0”.
        val groupIds = LinkedHashSet<String>().apply {
            add("")
            dataSource.getSubscriptions().forEach { add(it.guid) }
        }
        return groupIds
            .flatMap { groupId ->
                dataSource.getServerGuidList(groupId).mapNotNull { guid ->
                    dataSource.decodeServerConfig(guid)?.let { guid to it }
                }
            }
            .distinctBy { it.first }
    }

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        groupPreloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                groupPreloadJob?.cancelAndJoin()
                selectedGroupLoadJob?.cancelAndJoin()
                groupPreloadJob = null
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val subscriptions = dataSource.getSubscriptions()

                fun subscriptionUsesLocalTrafficGraph(cache: SubscriptionCache): Boolean =
                    SubscriptionNoticeParser.isLocalTrafficGraphSubscription(cache.subscription.url)

                fun isBundledServerlessSubscription(cache: SubscriptionCache): Boolean =
                    cache.subscription.url.substringBefore('#') ==
                        AppConfig.SERVERLESS_SUBSCRIPTION_URL.substringBefore('#')

                fun subscriptionNotice(cache: SubscriptionCache): String? = when {
                    isBundledServerlessSubscription(cache) ->
                        dataSource.getString(R.string.subscription_serverless_default_notice)
                    else -> cache.subscription.notice
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: if (SubscriptionNoticeParser.isWorkerSubscription(cache.subscription.url)) {
                            dataSource.getString(R.string.subscription_worker_default_notice)
                        } else {
                            null
                        }
                }

                val allGroupNotice = subscriptions
                    .asSequence()
                    .filter { it.guid.isNotEmpty() }
                    .mapNotNull { cache ->
                        subscriptionNotice(cache)
                            ?.let { notice -> "📢 ${cache.subscription.remarks}\n$notice" }
                    }
                    .joinToString("\n\n")
                    .takeIf { it.isNotEmpty() }

                val groups = subscriptions.map { cache ->
                    val usesLocalTrafficGraph = cache.guid.isNotEmpty() &&
                        subscriptionUsesLocalTrafficGraph(cache)
                    GroupMapItem(
                        id = cache.guid,
                        remarks = cache.subscription.remarks,
                        notice = if (cache.guid.isEmpty()) {
                            allGroupNotice
                        } else {
                            subscriptionNotice(cache)
                        },
                        trafficTotalBytes = when {
                            cache.guid.isEmpty() -> -1
                            isDefaultSubscriptionGroup(cache.guid) -> dataSource.getDefaultTrafficLimitBytes()
                            usesLocalTrafficGraph -> dataSource.getLocalSubscriptionTrafficLimitBytes(cache.guid)
                            else -> cache.subscription.trafficTotalBytes
                        },
                        trafficUsedBytes = when {
                            cache.guid.isEmpty() -> -1
                            isDefaultSubscriptionGroup(cache.guid) -> dataSource.getDefaultTrafficUsedBytes()
                            usesLocalTrafficGraph -> dataSource.getLocalSubscriptionTrafficUsedBytes(cache.guid)
                            else -> cache.subscription.trafficUsedBytes
                        },
                        expirationEpochSeconds = when {
                            cache.guid.isEmpty() -> -1L
                            isDefaultSubscriptionGroup(cache.guid) -> 0L
                            else -> cache.subscription.expirationEpochSeconds
                        },
                        trafficTotalRequests = if (cache.guid.isEmpty() || usesLocalTrafficGraph) {
                            -1
                        } else {
                            cache.subscription.trafficTotalRequests
                        },
                        trafficUsedRequests = if (cache.guid.isEmpty() || usesLocalTrafficGraph) {
                            -1
                        } else {
                            cache.subscription.trafficUsedRequests
                        },
                        trafficPeriodDays = if (isDefaultSubscriptionGroup(cache.guid)) {
                            dataSource.getDefaultTrafficPeriodDays()
                        } else if (usesLocalTrafficGraph) {
                            dataSource.getLocalSubscriptionTrafficPeriodDays(cache.guid)
                        } else {
                            0
                        },
                        hasSubscriptionLink = cache.guid.isNotEmpty() && cache.subscription.url.isNotBlank(),
                        usesLocalTrafficGraph = usesLocalTrafficGraph,
                        supportUrl = if (cache.guid.isEmpty() || usesLocalTrafficGraph) {
                            null
                        } else {
                            cache.subscription.supportUrl?.takeIf { url -> url.isNotBlank() }
                                ?: cache.subscription.url.takeIf { url -> url.isNotBlank() }
                        },
                        serverCount = dataSource.getRawServerGuidList(cache.guid).size,
                    )
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupPageFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer()
                    )
                }
                groups.forEach { mutableServersForGroup(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                InitialGroupLoadPolicy.groupIds(groups.map { it.id }, selectedGroup).forEach { groupId ->
                    val servers = loadGroup(groupId, forceRefresh)
                    updateGroupUi(groupId, servers)
                }
                currentCoroutineContext().ensureActive()

                val groupsToPreload = InitialGroupLoadPolicy.preloadGroupIds(
                    groupIds = groups.map { it.id },
                    selectedGroupId = selectedGroup,
                )
                if (groupsToPreload.isNotEmpty()) {
                    groupPreloadJob = viewModelScope.launch {
                        coroutineScope {
                            groupsToPreload.map { groupId ->
                                async(groupPreloadDispatcher) {
                                    try {
                                        val servers = loadGroup(groupId)
                                        currentCoroutineContext().ensureActive()
                                        updateGroupUi(groupId, servers)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        LogUtil.e(AppConfig.TAG, "Failed to preload group: $groupId", error)
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    private fun importBatchConfig(configText: String, forceAmneziaWg: Boolean = false) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true, forceAmneziaWg,
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> setupGroupTab(forceRefresh = true)
                        else -> toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun importConfigViaSub(subscriptionId: String = uiState.value.selectedGroupId) {
        val subId = subscriptionId
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(dataSource.getString(R.string.title_update_config_count, result.configCount))

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        refreshSelectedGuid()
                    }
                    if (subId.isNotEmpty() || result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            val guids = currentServers().map { it.guid }
                            guids.forEach { dataSource.removeServer(it) }
                            guids.size
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    duplicates.forEach { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, duplicates.size))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServersForGroup(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            val snapshot = cacheMutex.withLock { groupDataCache.toMap() }
            ensureActive()
            snapshot.forEach { (groupId, servers) ->
                ensureActive()
                updateGroupUi(groupId, servers)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    fun moveServer(groupId: String, fromPosition: Int, toPosition: Int) {
        val servers = mutableServersForGroup(groupId).value.toMutableList()
        if (!servers.moveItem(fromPosition, toPosition)) return
        val guids = servers.map { it.guid }
        mutableServersForGroup(groupId).value = servers
        // A drag emits several moves; serialize writes so an older order cannot overwrite a newer one.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        serverOrderPersistenceJobs[groupId] = viewModelScope.launch(ioDispatcher) {
            previousPersistenceJob?.join()
            dataSource.encodeServerList(guids, groupId)
            cacheMutex.withLock { groupDataCache[groupId] = servers }
        }
    }

    // ---------- Testing ----------
    fun cancelAllPing() {
        cancelAllPingInternal(refreshPersistedResults = true)
    }

    private fun cancelAllPingInternal(refreshPersistedResults: Boolean) {
        val cancelled = pingRequests.clear()
        val groupIds = cancelled.map { it.groupId }.toSet()
        dataSource.cancelAllPing()
        activePingTests.clear()
        cancelPendingTestResults()
        updatePingTestingState()
        if (refreshPersistedResults && groupIds.isNotEmpty()) {
            viewModelScope.launch(ioDispatcher) {
                groupIds.forEach { groupId ->
                    cacheMutex.withLock { groupDataCache.remove(groupId) }
                    updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
                }
            }
        }
    }

    fun testAllRealPing(onlyTcp: Boolean = false) {
        testRealPingForGroup(uiState.value.selectedGroupId, onlyTcp)
    }

    fun testSubscriptionRealPing(subscriptionId: String) {
        if (subscriptionId.isNotBlank()) testRealPingForGroup(subscriptionId, onlyTcp = false)
    }

    private fun testRealPingForGroup(
        groupId: String,
        onlyTcp: Boolean,
        onlyGuids: List<String>? = null,
    ) {
        val replacement = pingRequests.replace(groupId)
        replacement.previous?.let { cancelPingRequest(it.id) }
        val request = replacement.current
        val test = ActivePingTest(request)
        activePingTests[request.id] = test
        updatePingTestingState(MainStatus.Testing)
        viewModelScope.launch(ioDispatcher) {
            try {
                // A test can be started from a neighboring pager page before its lazy
                // server list has loaded. Read the group on demand so the request never
                // silently disappears because its StateFlow still contains an empty list.
                val servers = loadGroup(groupId)
                if (!isPingTestActive(request.id)) return@launch
                updateGroupUi(groupId, servers)
                val serverGuids = onlyGuids ?: pingableServerGuids(
                    applyKeywordFilter(servers),
                    includeOpenVpnProfiles = !onlyTcp,
                )
                if (serverGuids.isEmpty()) {
                    pingRequests.complete(request.id)
                    activePingTests.remove(request.id)
                    updatePingTestingState()
                    return@launch
                }
                val resetGuids = serverGuids.toHashSet()
                mutableServersForGroup(groupId).update { current ->
                    clearTestDelayResults(current, resetGuids)
                }
                dataSource.clearAllTestDelayResults(serverGuids)
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        groupDataCache[groupId] = clearTestDelayResults(cached, resetGuids)
                    }
                }
                if (!isPingTestActive(request.id)) return@launch
                dataSource.sendMsg2TestService(
                    TestServiceMessage(
                        key = AppConfig.MSG_MEASURE_CONFIG_START,
                        subscriptionId = groupId,
                        // Use the same GUID snapshot that was cleared in the visible list.
                        // Re-reading a subscription here can test different rows after refresh.
                        serverGuids = serverGuids,
                        onlyTcp = onlyTcp,
                        requestId = request.id,
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to start real-delay test for group: $groupId", error)
                pingRequests.complete(request.id)
                activePingTests.remove(request.id)
                updatePingTestingState()
            }
        }
    }

    fun testCurrentServerRealPing() {
        val selectedGuid = uiState.value.selectedGuid ?: return
        val profile = dataSource.decodeServerConfig(selectedGuid) ?: return
        if (!canCheckCurrentConnection(profile)) return
        _uiState.update { it.copy(status = MainStatus.Testing) }
        dataSource.testCurrentServerRealPing()
    }

    private fun updateDefaultTrafficUsage(usedBytes: Long) {
        val normalizedBytes = usedBytes.coerceAtLeast(0L)
        val limitBytes = dataSource.getDefaultTrafficLimitBytes()
        val periodDays = dataSource.getDefaultTrafficPeriodDays()
        _uiState.update { state ->
            val groups = state.groups.map { group ->
                when {
                    isDefaultSubscriptionGroup(group.id) ->
                        group.withDefaultTrafficUsage(normalizedBytes, limitBytes, periodDays)
                    group.usesLocalTrafficGraph -> group.withLocalSubscriptionTrafficUsage(
                        usedBytes = dataSource.getLocalSubscriptionTrafficUsedBytes(group.id),
                        limitBytes = dataSource.getLocalSubscriptionTrafficLimitBytes(group.id),
                        periodDays = dataSource.getLocalSubscriptionTrafficPeriodDays(group.id),
                    )
                    else -> group
                }
            }
            if (groups == state.groups) state else state.copy(groups = groups)
        }
    }

    private fun configureDefaultTrafficGraph(limitBytes: Long, periodDays: Int) {
        val normalizedLimit = limitBytes.coerceAtLeast(0L)
        val normalizedPeriod = periodDays.coerceIn(0, 3650)
        viewModelScope.launch(ioDispatcher) {
            dataSource.setDefaultTrafficGraph(normalizedLimit, normalizedPeriod)
            val usedBytes = dataSource.getDefaultTrafficUsedBytes()
            _uiState.update { state ->
                val groups = state.groups.map {
                    it.withDefaultTrafficUsage(usedBytes, normalizedLimit, normalizedPeriod)
                }
                state.copy(groups = groups)
            }
        }
    }

    private fun resetDefaultTrafficGraph() {
        viewModelScope.launch(ioDispatcher) {
            dataSource.resetDefaultTrafficUsage()
            _uiState.update { state ->
                val groups = state.groups.map {
                    it.withDefaultTrafficUsage(
                        usedBytes = 0L,
                        limitBytes = dataSource.getDefaultTrafficLimitBytes(),
                        periodDays = dataSource.getDefaultTrafficPeriodDays(),
                    )
                }
                state.copy(groups = groups)
            }
        }
    }

    private fun configureSubscriptionTrafficGraph(subscriptionId: String, limitBytes: Long, periodDays: Int) {
        if (subscriptionId.isBlank() || uiState.value.groups.none {
                it.id == subscriptionId && it.usesLocalTrafficGraph
            }
        ) return
        val normalizedLimit = limitBytes.coerceAtLeast(0L)
        val normalizedPeriod = periodDays.coerceIn(0, 3650)
        viewModelScope.launch(ioDispatcher) {
            dataSource.setLocalSubscriptionTrafficGraph(subscriptionId, normalizedLimit, normalizedPeriod)
            val usedBytes = dataSource.getLocalSubscriptionTrafficUsedBytes(subscriptionId)
            _uiState.update { state ->
                val groups = state.groups.map { group ->
                    if (group.id == subscriptionId) {
                        group.withLocalSubscriptionTrafficUsage(usedBytes, normalizedLimit, normalizedPeriod)
                    } else {
                        group
                    }
                }
                state.copy(groups = groups)
            }
        }
    }

    private fun resetSubscriptionTrafficGraph(subscriptionId: String) {
        if (subscriptionId.isBlank() || uiState.value.groups.none {
                it.id == subscriptionId && it.usesLocalTrafficGraph
            }
        ) return
        viewModelScope.launch(ioDispatcher) {
            dataSource.resetLocalSubscriptionTrafficUsage(subscriptionId)
            _uiState.update { state ->
                val groups = state.groups.map { group ->
                    if (group.id == subscriptionId && group.usesLocalTrafficGraph) {
                        group.withLocalSubscriptionTrafficUsage(
                            usedBytes = 0L,
                            limitBytes = dataSource.getLocalSubscriptionTrafficLimitBytes(subscriptionId),
                            periodDays = dataSource.getLocalSubscriptionTrafficPeriodDays(subscriptionId),
                        )
                    } else {
                        group
                    }
                }
                state.copy(groups = groups)
            }
        }
    }

    /** Clears a previous result so the Desync tuner can await the next probe. */
    fun markDesyncTuningProbe() {
        _uiState.update { it.copy(status = MainStatus.Testing) }
    }

    private fun onTestsFinished(requestId: String) {
        val test = activePingTest(requestId) ?: return
        if (test.finishing) return
        test.finishing = true
        val groupId = test.request.groupId
        val scheduledFlush = testResultFlushJobs.remove(requestId)
        viewModelScope.launch(ioDispatcher) {
            scheduledFlush?.cancelAndJoin()
            if (!isPingTestActive(requestId)) return@launch
            flushPendingTestResults(test, requestId)
            if (!isPingTestActive(requestId)) return@launch
            synchronized(pendingTestResultsLock) { pendingTestResults.remove(requestId) }
            pingRequests.complete(requestId)
            activePingTests.remove(requestId)
            updatePingTestingState()
            cacheMutex.withLock { groupDataCache.remove(groupId) }
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    private fun onTestsCancelled(requestId: String) {
        val test = activePingTest(requestId) ?: return
        testResultFlushJobs.remove(requestId)?.cancel()
        synchronized(pendingTestResultsLock) { pendingTestResults.remove(requestId) }
        pingRequests.complete(requestId)
        activePingTests.remove(requestId)
        updatePingTestingState()
        val groupId = test.request.groupId
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.remove(groupId) }
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        val groupIndex =
            _uiState.value.groups.indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: return
        viewModelScope.launch(ioDispatcher) {
            val position =
                loadGroup(groupId).indexOfFirst { it.guid == selected }.takeIf { it >= 0 }
                    ?: return@launch
            _uiState.update {
                it.copy(locateTarget = LocateTarget(groupId, groupIndex, position))
            }
        }
    }

    fun getPosition(guid: String): Int = currentServers().indexOfFirst { it.guid == guid }

    private fun consumeLocateTarget(target: LocateTarget) {
        _uiState.update { state ->
            if (state.locateTarget == target) state.copy(locateTarget = null) else state
        }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isStarting = false,
                status = if (!clearTestingText && state.isTesting) state.status
                else if (running) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    override fun onCleared() {
        setupGroupJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        defaultTrafficUsageJob?.cancel()
        filterJob?.cancel()
        psiphonReconnectStatusJobs.values.forEach { it.cancel() }
        psiphonReconnectStatusJobs.clear()
        cancelAllPingInternal(refreshPersistedResults = false)
        dataSource.close()
        super.onCleared()
    }

    private companion object {
        const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
