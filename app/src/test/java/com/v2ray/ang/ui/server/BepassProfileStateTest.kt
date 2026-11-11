package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BepassProfileStateTest {
    @Test
    fun savesAndRestoresEditableBepassSettings() {
        val settingsJson = """{"remoteDnsAddress":"https://dns.example/query","chunksBeforeSniMin":1200}"""
        val state = ServerUiState(
            configType = EConfigType.VLESS,
            bepassEnabled = true,
            bepassSettingsJson = settingsJson,
            network = "tcp",
            streamSecurity = "tls",
        )

        val saved = state.toProfileItem(ProfileItem.create(EConfigType.VLESS))
        val restored = ServerUiState.fromProfileItem(saved)

        assertTrue(saved.bepassEnabled)
        assertEquals(settingsJson, saved.bepassSettingsJson)
        assertTrue(restored.bepassEnabled)
        assertEquals(settingsJson, restored.bepassSettingsJson)
    }

    @Test
    fun disablesBepassWhenEditorTransportBecomesIncompatible() {
        val state = ServerUiState(
            configType = EConfigType.VLESS,
            bepassEnabled = true,
            network = "kcp",
            streamSecurity = "tls",
        )

        assertFalse(state.toProfileItem(ProfileItem.create(EConfigType.VLESS)).bepassEnabled)
    }
}
