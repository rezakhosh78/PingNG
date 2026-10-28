package com.v2ray.ang.ui.server

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.compose.FormDropdownField

/** The visible WARP Core choice and the corresponding profile editor. */
internal object WarpCoreNavigation {
    const val MASQUE = "WARP MASQUE/H2"
    const val WIREGUARD = "WARP WireGuard"
    const val PLUS = "WARP IN WARP"
    const val AMNEZIA = "WARP AmneziaWG"
    const val SWITCH = "com.pingng.extra.WARP_CORE_SWITCH"
    const val SHOW_CORE = "com.pingng.extra.SHOW_WARP_CORE"
    const val REQUEST = 8403
    val choices = listOf(MASQUE, WIREGUARD, PLUS, AMNEZIA)

    fun intent(activity: Activity, core: String, guid: String, subscriptionId: String, running: Boolean): Intent {
        val target = when (core) {
            WIREGUARD, AMNEZIA -> ServerWireguardActivity::class.java
            PLUS -> WarpInWarpActivity::class.java
            else -> WarpMasqueActivity::class.java
        }
        return Intent(activity, target).apply {
            if (guid.isNotBlank()) putExtra("guid", guid)
            putExtra("subscriptionId", subscriptionId)
            putExtra("isRunning", running)
            putExtra(SWITCH, true)
            putExtra(SHOW_CORE, activity.intent.getBooleanExtra(SHOW_CORE, false))
            if (core == WIREGUARD) putExtra("warpWireGuard", true)
            if (core == AMNEZIA) putExtra("awgWarp", true)
        }
    }
}

@Composable
internal fun WarpCoreField(core: String, onSwitch: (String) -> Unit) {
    FormDropdownField(
        label = "Core",
        value = core,
        options = WarpCoreNavigation.choices,
        onValueChange = { if (it != core) onSwitch(it) },
    )
}
