package com.v2ray.ang.ui.perappproxy

/** Persists a changed app-proxy mode and requests a tunnel restart; unchanged values are no-ops. */
internal fun updatePerAppProxyPreference(
    currentValue: Boolean,
    newValue: Boolean,
    persist: () -> Unit,
    requestRestart: () -> Unit,
) {
    if (currentValue == newValue) return
    persist()
    requestRestart()
}
