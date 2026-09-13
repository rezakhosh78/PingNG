package com.v2ray.ang.ui.main

import com.v2ray.ang.core.PingNgDesyncTuner
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

/** The latest verified Find Desync result set for each configuration. */
data class SavedDesyncResult(
    val label: String = "",
    val arguments: String = "",
    val delayMillis: Long = -1L,
    val method: String = "",
)

data class SavedDesyncHistory(
    val savedAt: Long = 0L,
    val family: String = "ALL",
    val advanced: Boolean = true,
    val results: List<SavedDesyncResult> = emptyList(),
)

data class DesyncSearchHistoryStore(
    val byGuid: Map<String, SavedDesyncHistory> = emptyMap(),
)

object DesyncSearchHistory {
    private const val STORAGE_KEY = "PINGNG_DESYNC_SEARCH_HISTORY_V1"

    @Synchronized
    fun load(
        guid: String,
        family: PingNgDesyncTuner.SearchFamily = PingNgDesyncTuner.SearchFamily.ALL,
        advanced: Boolean = true,
    ): List<SavedDesyncResult> {
        val store = readStore().byGuid
        val exact = store[storageKey(guid, family, advanced)]
        if (exact != null) return exact.results

        // Read the old v1 entry once as a compatibility fallback. New searches
        // are always stored per category and per Advanced switch.
        return store[guid]?.results.orEmpty().filter { result ->
            family == PingNgDesyncTuner.SearchFamily.ALL || result.method == family.method
        }
    }

    /** Returns every saved result for this exact configuration, regardless of category. */
    @Synchronized
    fun loadAll(guid: String): List<SavedDesyncResult> {
        if (guid.isBlank()) return emptyList()
        val prefix = "$guid|"
        return readStore().byGuid
            .filterKeys { key -> key == guid || key.startsWith(prefix) }
            .values
            .flatMap { it.results }
            .distinctBy { it.arguments }
            .sortedBy { it.delayMillis }
    }

    /** Removes every saved category for this exact configuration. */
    @Synchronized
    fun clear(guid: String) {
        if (guid.isBlank()) return
        val prefix = "$guid|"
        val store = readStore().byGuid.toMutableMap()
        val keys = store.keys.filter { key -> key == guid || key.startsWith(prefix) }
        if (keys.isNotEmpty()) {
            keys.forEach(store::remove)
            MmkvManager.encodeSettings(STORAGE_KEY, JsonUtil.toJson(DesyncSearchHistoryStore(store)))
        }
    }

    @Synchronized
    fun save(
        guid: String,
        results: List<Pair<PingNgDesyncTuner.Candidate, Long>>,
        family: PingNgDesyncTuner.SearchFamily = PingNgDesyncTuner.SearchFamily.ALL,
        advanced: Boolean = true,
    ) {
        if (guid.isBlank() || results.isEmpty()) return
        val store = readStore().byGuid.toMutableMap()
        store[storageKey(guid, family, advanced)] = SavedDesyncHistory(
            savedAt = System.currentTimeMillis(),
            family = family.name,
            advanced = advanced,
            results = results.map { (candidate, delay) ->
                SavedDesyncResult(
                    label = candidate.label,
                    arguments = candidate.arguments,
                    delayMillis = delay,
                    method = candidate.method,
                )
            },
        )
        // Keep storage bounded if a user has accumulated many deleted profiles.
        val entries = store.entries.toList()
        val bounded = entries
            .drop((entries.size - 80).coerceAtLeast(0))
            .associate { entry -> entry.key to entry.value }
        MmkvManager.encodeSettings(STORAGE_KEY, JsonUtil.toJson(DesyncSearchHistoryStore(bounded)))
    }

    private fun storageKey(
        guid: String,
        family: PingNgDesyncTuner.SearchFamily,
        advanced: Boolean,
    ): String = "$guid|${family.name}|${if (advanced) "advanced" else "basic"}"

    private fun readStore(): DesyncSearchHistoryStore {
        val json = MmkvManager.decodeSettingsString(STORAGE_KEY).orEmpty()
        if (json.isBlank()) return DesyncSearchHistoryStore()
        return JsonUtil.fromJsonSafe(json, DesyncSearchHistoryStore::class.java)
            ?: DesyncSearchHistoryStore()
    }
}

fun SavedDesyncResult.toCandidateAndDelay(): Pair<PingNgDesyncTuner.Candidate, Long> =
    PingNgDesyncTuner.Candidate(label, arguments, method) to delayMillis
