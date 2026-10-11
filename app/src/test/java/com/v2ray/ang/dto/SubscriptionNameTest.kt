package com.v2ray.ang.dto
import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.Assert.*
import org.junit.Test

class SubscriptionNameTest {
    @Test fun automaticNameTracksProvider() {
        val sub = SubscriptionItem(remarks = "Imported", url = "https://example.com/sub")
        sub.applyProviderTitle("Provider A")
        sub.applyProviderTitle("Provider B")
        assertEquals("Provider B", sub.remarks)
    }
    @Test fun manualNameSurvivesUpdatesAndUrlChanges() {
        val sub = SubscriptionItem(remarks = "Provider A", url = "https://example.com/sub")
        sub.applyProviderTitle("Provider A")
        sub.applyUserEdit("My name", "https://example.com/sub", "Provider A")
        sub.applyProviderTitle("Provider B")
        sub.applyUserEdit("My name", "https://example.com/new", "My name")
        sub.applyProviderTitle("Provider C")
        assertEquals("My name", sub.remarks)
        assertEquals("Provider C", sub.providerRemarks)
    }
    @Test fun removingUrlResetsAliasAndAutomaticNaming() {
        val sub = SubscriptionItem(remarks = "Mine", url = "https://example.com/sub", customRemarks = "Mine")
        sub.applyUserEdit("Mine", "", "Mine")
        assertEquals("Default", sub.remarks)
        assertNull(sub.customRemarks)
        assertNull(sub.providerRemarks)
        sub.applyUserEdit("Default", "https://example.com/sub", "Default")
        sub.applyProviderTitle("Provider")
        assertEquals("Provider", sub.remarks)
    }
    @Test fun SavingOtherSettingsDoesNotLockName() {
        val sub = SubscriptionItem(remarks = "Provider", url = "https://example.com/sub")
        sub.applyUserEdit("Provider", sub.url, "Provider")
        sub.applyProviderTitle("Updated")
        assertEquals("Updated", sub.remarks)
        sub.applyProviderTitle(null)
        assertEquals("Updated", sub.remarks)
    }
}
