package com.v2ray.ang.ui.perappproxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PerAppProxyPreferenceChangeTest {
    @Test
    fun changedModePersistsBeforeRequestingTunnelRestart() {
        val events = mutableListOf<String>()

        updatePerAppProxyPreference(
            currentValue = false,
            newValue = true,
            persist = { events += "persist" },
            requestRestart = { events += "restart" },
        )

        assertEquals(listOf("persist", "restart"), events)
    }

    @Test
    fun unchangedModeDoesNotPersistOrRequestRestart() {
        val events = mutableListOf<String>()

        updatePerAppProxyPreference(
            currentValue = true,
            newValue = true,
            persist = { events += "persist" },
            requestRestart = { events += "restart" },
        )

        assertEquals(emptyList<String>(), events)
    }
}
