package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainImportMenuTest {

    @Test
    fun regularShareMenuContainsOnlyShareActions() {
        val expected = listOf(
            ServerMenuAction.ShareQRCode,
            ServerMenuAction.ShareClipboard,
            ServerMenuAction.ShareFullContent,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = false, includeManagementActions = false))
    }

    @Test
    fun regularMoreMenuContainsEveryActionInDisplayOrder() {
        assertEquals(
            ServerMenuAction.entries.filterNot { it == ServerMenuAction.ExportFullConfig },
            serverMenuActions(isComplexProfile = false, includeManagementActions = true),
        )
    }

    @Test
    fun complexShareMenuContainsOnlyFullContent() {
        assertEquals(
            listOf(ServerMenuAction.ShareFullContent),
            serverMenuActions(isComplexProfile = true, includeManagementActions = false),
        )
    }

    @Test
    fun complexMoreMenuRetainsManagementActions() {
        val expected = listOf(
            ServerMenuAction.ShareFullContent,
            ServerMenuAction.Edit,
            ServerMenuAction.Delete,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = true, includeManagementActions = true))
    }

    @Test
    fun importedAmneziaProfilesCanBeSharedAndDeletedButNotOpenedInWrongEditor() {
        val expected = listOf(
            ServerMenuAction.ShareFullContent,
            ServerMenuAction.Delete,
        )
        assertEquals(
            expected,
            serverMenuActions(
                isComplexProfile = true,
                includeManagementActions = true,
                isReadOnlyProfile = true,
            ),
        )
    }

    @Test
    fun openVpnShareMenuIncludesCompactLinkAndRawConfigOnly() {
        assertEquals(
            listOf(
                ServerMenuAction.ShareClipboard,
                ServerMenuAction.ShareFullContent,
            ),
            serverMenuActions(
                isComplexProfile = true,
                includeManagementActions = false,
                hasDedicatedProfileLink = true,
                keepFullContentShare = true,
                includeQRCodeShare = false,
            ),
        )
    }

    @Test
    fun dedicatedShareLinkCanReplaceRawContentForOtherComplexProfiles() {
        assertEquals(
            listOf(ServerMenuAction.ShareQRCode, ServerMenuAction.ShareClipboard),
            serverMenuActions(
                isComplexProfile = true,
                includeManagementActions = false,
                hasDedicatedProfileLink = true,
            ),
        )
    }

    @Test
    fun fullConfigExportIsShownOnlyWhenTheProfileSupportsIt() {
        assertEquals(
            listOf(
                ServerMenuAction.ShareQRCode,
                ServerMenuAction.ShareClipboard,
                ServerMenuAction.ExportFullConfig,
            ),
            serverMenuActions(
                isComplexProfile = true,
                includeManagementActions = false,
                hasDedicatedProfileLink = true,
                includeFullConfigExport = true,
            ),
        )
        assertTrue(ServerMenuAction.ExportFullConfig !in serverMenuActions(
            isComplexProfile = false,
            includeManagementActions = false,
        ))
    }
}
