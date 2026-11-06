package com.v2ray.ang.handler

import com.google.gson.JsonNull
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil

/**
 * Local edits made to a profile imported from a subscription.
 *
 * Subscription refreshes normally replace the profile payload and generate a
 * new GUID. Keeping the old and edited JSON lets us reapply only fields that
 * the user changed, while still accepting unrelated updates from the link.
 */
data class SubscriptionProfileOverride(
    val base: ProfileItem,
    val edited: ProfileItem,
)

data class SubscriptionProfileOverrideStore(
    val byGuid: Map<String, SubscriptionProfileOverride> = emptyMap(),
)

object SubscriptionProfileOverrides {
    private const val STORAGE_KEY = "PINGNG_SUBSCRIPTION_PROFILE_OVERRIDES_V1"

    @Synchronized
    fun save(guid: String, base: ProfileItem, edited: ProfileItem) {
        if (guid.isBlank()) return
        // Some editor paths receive a profile whose subscriptionId was not
        // populated by the caller, even though the edited profile still has
        // the subscription id. Do not silently drop the override in that
        // case; a subscription refresh would otherwise restore old values.
        val effectiveBase = if (base.subscriptionId.isBlank() && edited.subscriptionId.isNotBlank()) {
            base.copy(subscriptionId = edited.subscriptionId)
        } else {
            base
        }
        if (effectiveBase.subscriptionId.isBlank()) return
        val effectiveEdited = if (edited.subscriptionId.isBlank()) {
            edited.copy(subscriptionId = effectiveBase.subscriptionId)
        } else {
            edited
        }
        val store = read().byGuid.toMutableMap()
        // On the second edit, `base` is already the previously edited profile.
        // Keep comparing against the original subscription payload; otherwise
        // reopening and accepting the same values would delete the override.
        val originalBase = store[guid]?.base ?: effectiveBase
        if (JsonUtil.toJson(originalBase) == JsonUtil.toJson(effectiveEdited)) {
            store.remove(guid)
        } else {
            store[guid] = SubscriptionProfileOverride(originalBase, effectiveEdited)
        }
        write(store)
    }

    /** Applies only JSON fields changed by the user to a fresh subscription profile. */
    @Synchronized
    fun apply(oldGuid: String, incoming: ProfileItem): ProfileItem {
        val override = read().byGuid[oldGuid] ?: return incoming
        val base = JsonUtil.parseString(JsonUtil.toJson(override.base)) ?: return incoming
        val edited = JsonUtil.parseString(JsonUtil.toJson(override.edited)) ?: return incoming
        val fresh = JsonUtil.parseString(JsonUtil.toJson(incoming)) ?: return incoming

        // Subscription ownership belongs to the refreshed subscription
        // payload. Never let an old/local override clear or replace it.
        // In particular, the provider may change a node's address or port;
        // those fields must always come from the fresh subscription payload.
        val keys = (base.keySet() + edited.keySet()).toSet() - setOf(
            "subscriptionId",
            "server",
            "serverPort",
        )
        keys.forEach { key ->
            val before = base.get(key)?.toString()
            val after = edited.get(key)?.toString()
            if (before != after) {
                fresh.add(key, edited.get(key) ?: JsonNull.INSTANCE)
            }
        }
        return JsonUtil.fromJsonSafe(fresh.toString(), ProfileItem::class.java) ?: incoming
    }

    private fun read(): SubscriptionProfileOverrideStore {
        val json = MmkvManager.decodeSettingsString(STORAGE_KEY).orEmpty()
        if (json.isBlank()) return SubscriptionProfileOverrideStore()
        return JsonUtil.fromJsonSafe(json, SubscriptionProfileOverrideStore::class.java)
            ?: SubscriptionProfileOverrideStore()
    }

    private fun write(byGuid: Map<String, SubscriptionProfileOverride>) {
        // Prevent stale entries from growing without bound after subscriptions
        // are removed or recreated.
        val bounded = byGuid.entries
            .toList()
            .drop((byGuid.size - 300).coerceAtLeast(0))
            .associate { it.key to it.value }
        MmkvManager.encodeSettings(
            STORAGE_KEY,
            JsonUtil.toJson(SubscriptionProfileOverrideStore(bounded)),
        )
    }
}
