package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.PsiphonStatus
import com.v2ray.ang.dto.TorStatus

sealed class MainServiceEvent {
    data object StateRunning : MainServiceEvent()
    data object StateNotRunning : MainServiceEvent()
    data object StateStartSuccess : MainServiceEvent()
    data class StateStartFailure(val message: String) : MainServiceEvent()
    data object StateStopSuccess : MainServiceEvent()
    data class MeasureDelayResult(val result: ConnectionTestResult) : MainServiceEvent()
    data class MeasureConfigSuccess(val result: RealPingResult, val requestId: String = "") : MainServiceEvent()
    data class MeasureConfigNotify(val progress: String, val requestId: String = "") : MainServiceEvent()
    data class MasterDnsProgress(val guid: String, val completed: Int, val total: Int, val valid: Int = 0) : MainServiceEvent()
    data class MeasureConfigFinish(val finishedCount: String?, val requestId: String = "") : MainServiceEvent()
    data class MeasureConfigCancelled(val requestId: String = "") : MainServiceEvent()
    data class PsiphonStatusChanged(val status: PsiphonStatus) : MainServiceEvent()
    data class TorStatusChanged(val status: TorStatus) : MainServiceEvent()
    data class SubscriptionUpdated(val subscriptionId: String) : MainServiceEvent()
}
