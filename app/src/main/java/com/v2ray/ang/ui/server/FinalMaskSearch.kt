package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.ui.compose.FormDropdownField

data class FinalMaskCandidate(val title: String, val json: String)

data class SavedFinalMaskResult(
    val title: String = "",
    val json: String = "",
    val delayMillis: Long = -1L,
)

data class FinalMaskHistoryStore(
    val byGuid: Map<String, List<SavedFinalMaskResult>> = emptyMap(),
)

object FinalMaskSearch {
    private const val STORAGE_KEY = "PINGNG_FINAL_MASK_SEARCH_HISTORY_V1"

    fun candidates(): List<FinalMaskCandidate> {
        // DPI-focused, conservative multi-split values: 8 x 8 x 6 = 384.
        // Each entry is an actual length array, not a single split number.
        val firstLengths = listOf(
            "0,104,1", "0,94,1", "0,109,1", "0,114,1",
            "0,64,1", "0,80,1", "1,104,1", "0,128,1",
        )
        val secondLengths = listOf(
            "114,1", "104,1", "109,1", "94,1",
            "80,1", "64,1", "32,1", "128,1",
        )
        val timings = listOf(
            "0" to "1", "0" to "2", "1" to "1",
            "1" to "2", "0,1" to "1", "1" to "1,2",
        )
        val result = mutableListOf<FinalMaskCandidate>()
        firstLengths.forEachIndexed { a, firstLength ->
            secondLengths.forEachIndexed { b, secondLength ->
                timings.forEach { (firstDelay, secondDelay) ->
                    fun array(raw: String) = raw.split(',').joinToString(",") { "\"$it\"" }
                    result += FinalMaskCandidate(
                        "TLS [$firstLength] > [$secondLength] d$firstDelay/$secondDelay",
                        """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":[${array(firstLength)}],"delays":[${array(firstDelay)}],"maxSplit":"0"}},{"type":"fragment","settings":{"packets":"1-1","lengths":[${array(secondLength)}],"delays":[${array(secondDelay)}],"maxSplit":"11"}}]}""",
                    )
                }
            }
        }
        return result.distinctBy { it.json }
    }

    @Synchronized fun load(guid: String): List<SavedFinalMaskResult> = readStore().byGuid[guid].orEmpty()

    @Synchronized fun clear(guid: String) {
        if (guid.isBlank()) return
        val byGuid = readStore().byGuid.toMutableMap()
        if (byGuid.remove(guid) != null) {
            MmkvManager.encodeSettings(STORAGE_KEY, JsonUtil.toJson(FinalMaskHistoryStore(byGuid)))
        }
    }

    @Synchronized fun save(guid: String, result: SavedFinalMaskResult) {
        if (guid.isBlank() || result.json.isBlank() || result.delayMillis < 0L) return
        val byGuid = readStore().byGuid.toMutableMap()
        byGuid[guid] = (byGuid[guid].orEmpty() + result)
            .distinctBy { it.json }
            .sortedBy { it.delayMillis }
            .take(100)
        val bounded = byGuid.toList().takeLast(80).toMap()
        MmkvManager.encodeSettings(STORAGE_KEY, JsonUtil.toJson(FinalMaskHistoryStore(bounded)))
    }

    private fun readStore(): FinalMaskHistoryStore = JsonUtil.fromJsonSafe(
        MmkvManager.decodeSettingsString(STORAGE_KEY).orEmpty(),
        FinalMaskHistoryStore::class.java,
    ) ?: FinalMaskHistoryStore()
}

@Composable
fun FinalMaskSearchDialog(
    guid: String,
    onSearch: (List<FinalMaskCandidate>, (FinalMaskCandidate, Long) -> Unit, (Int) -> Unit, () -> Unit) -> Unit,
    onCancelSearch: () -> Unit,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember { FinalMaskSearch.candidates() }
    var history by remember(guid) { mutableStateOf(FinalMaskSearch.load(guid)) }
    var searching by remember { mutableStateOf(false) }
    var testedProfiles by remember { mutableStateOf(0) }
    var totalProfiles by remember { mutableStateOf(0) }
    var maxProfiles by remember { mutableStateOf(384) }
    val shown = history.sortedBy { it.delayMillis }
    val countOptions = listOf(32, 64, 128, 256, 384)

    AlertDialog(
        onDismissRequest = { if (!searching) onDismiss() },
        title = { Text("Find Final Mask") },
        text = {
            Column {
                FormDropdownField(
                    label = "Profile count",
                    value = maxProfiles.toString(),
                    options = countOptions.map(Int::toString),
                    onValueChange = { maxProfiles = it.toIntOrNull() ?: maxProfiles },
                    enabled = !searching,
                    supportingText = "Maximum: ${candidates.size}",
                )
                if (searching) {
                    Text(
                        "Tested $testedProfiles of $totalProfiles profiles",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { result ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${result.delayMillis} ms • ${result.title}", Modifier.weight(1f))
                            TextButton(onClick = {
                                onCancelSearch()
                                onApply(result.json)
                                onDismiss()
                            }) { Text("Apply") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !searching, onClick = {
                    searching = true
                    testedProfiles = 0
                    totalProfiles = maxProfiles.coerceAtMost(candidates.size)
                    history = emptyList()
                    FinalMaskSearch.clear(guid)
                    onSearch(candidates.shuffled().take(maxProfiles), { candidate, delay ->
                        val result = SavedFinalMaskResult(candidate.title, candidate.json, delay)
                        FinalMaskSearch.save(guid, result)
                        history = (history + result).distinctBy { it.json }.sortedBy { it.delayMillis }
                    }, { tested ->
                        testedProfiles = tested.coerceIn(0, totalProfiles)
                    }, { searching = false })
                }) { Text(if (searching) "Testing..." else "Search") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (searching) {
                    onCancelSearch()
                } else {
                    onDismiss()
                }
            }) { Text("Cancel") }
        },
    )
}
