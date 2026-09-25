package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.PingNgDesyncTuner
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.PingNgDesyncTuner.SearchFamily
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

@Composable
fun DesyncSearchDialog(
    profiles: List<Pair<String, ProfileItem>>,
    fixedProfileGuid: String? = null,
    onStartSearch: (
        String,
        ProfileItem,
        SearchFamily,
        Boolean,
        Int,
        Int,
        (tested: Int, total: Int) -> Unit,
        (List<Pair<PingNgDesyncTuner.Candidate, Long>>) -> Unit,
        () -> Unit,
    ) -> Unit,
    onCancelSearch: () -> Unit,
    onApply: (String, ProfileItem, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // MainScreen refreshes its profile snapshots after a successful search.
    // Key this state by GUIDs, not by the newly-created List instance, so the
    // result dialog remains open and keeps its selected configuration.
    val profileGuids = profiles.map { it.first }
    val supported = remember(profileGuids) {
        profiles.filter { PingNgCompat.supportsNativeDesync(it.second) }
    }
    val isFixedProfile = fixedProfileGuid != null
    var selectedGuid by remember(supported, fixedProfileGuid) {
        mutableStateOf(
            fixedProfileGuid?.takeIf { guid -> supported.any { it.first == guid } }
                ?: supported.firstOrNull()?.first
        )
    }
    var searchFamily by remember(selectedGuid) { mutableStateOf(SearchFamily.ALL) }
    var includeAdvanced by remember(selectedGuid) { mutableStateOf(true) }
    val previousResults = remember(selectedGuid, searchFamily, includeAdvanced) {
        selectedGuid?.let { guid ->
            DesyncSearchHistory.loadAll(guid)
        }.orEmpty()
            .map { it.toCandidateAndDelay() }
            .distinctBy { it.first.arguments }
    }
    var results by remember(selectedGuid, searchFamily, includeAdvanced) { mutableStateOf(previousResults) }
    var showProfiles by remember(selectedGuid, searchFamily, includeAdvanced) { mutableStateOf(previousResults.isEmpty()) }
    var showingPrevious by remember(selectedGuid, searchFamily, includeAdvanced) { mutableStateOf(previousResults.isNotEmpty()) }
    var searching by remember { mutableStateOf(false) }
    var testedCount by remember(selectedGuid) { mutableStateOf(0) }
    var totalCount by remember(selectedGuid, searchFamily, includeAdvanced) {
        mutableStateOf(
                supported.firstOrNull { it.first == selectedGuid }
                    ?.second
                ?.let { PingNgDesyncTuner.generate(it, includeAdvanced, searchFamily) }
                ?.size
                ?.coerceAtLeast(1)
                ?: 1
        )
    }
    val selected = supported.firstOrNull { it.first == selectedGuid }
    val familyOptions = SearchFamily.values().map {
        it to stringResource(searchFamilyLabel(it))
    }
    val availableCount = remember(selectedGuid, includeAdvanced, searchFamily, selected?.second) {
        selected?.second?.let {
            PingNgDesyncTuner.generate(it, includeAdvanced, searchFamily).size
        } ?: 0
    }
    val countOptions = remember(availableCount) {
        (listOf(25, 50, 100, 250, 500, 1000, availableCount)
            .filter { it in 1..availableCount } + availableCount)
            .distinct()
            .sorted()
    }
    var requestedCount by remember(selectedGuid, includeAdvanced, searchFamily) {
        mutableStateOf(
            if (WarpMasqueConfig.isDescription(selected?.second?.description)) {
                availableCount.coerceIn(1, 25)
            } else availableCount.coerceAtLeast(1)
        )
    }
    var workerCount by remember(selectedGuid) { mutableStateOf(4) }
    val liveResultsState = rememberLazyListState()

    // Results are sorted by delay, so every live update can insert a better
    // candidate at index zero. Keep that best candidate visible instead of
    // letting LazyColumn retain/animate the previous bottom position.
    LaunchedEffect(results, searching) {
        if (searching && results.isNotEmpty()) {
            liveResultsState.scrollToItem(0)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!searching) onDismiss() },
        title = { Text(stringResource(R.string.pingng_desync_search_title)) },
        text = {
            Column {
                if (selected != null && !searching) {
                    FormDropdownField(
                        label = stringResource(R.string.pingng_desync_search_family),
                        value = stringResource(searchFamilyLabel(searchFamily)),
                        options = familyOptions.map { it.second },
                        onValueChange = { value ->
                            familyOptions.firstOrNull { it.second == value }
                                ?.let { searchFamily = it.first }
                        },
                        supportingText = stringResource(R.string.pingng_desync_search_family_hint),
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.pingng_desync_advanced_search),
                        summary = stringResource(R.string.pingng_desync_advanced_search_hint),
                        checked = includeAdvanced,
                        onCheckedChange = { includeAdvanced = it },
                    )
                    FormDropdownField(
                        label = stringResource(
                            R.string.pingng_desync_profile_count,
                            availableCount.coerceAtLeast(1),
                        ),
                        value = requestedCount.coerceAtMost(availableCount.coerceAtLeast(1)).toString(),
                        options = countOptions.map(Int::toString),
                        onValueChange = { requestedCount = it.toIntOrNull() ?: requestedCount },
                        supportingText = stringResource(R.string.pingng_desync_profile_count_hint),
                    )
                    FormDropdownField(
                        label = stringResource(R.string.pingng_desync_workers),
                        value = workerCount.toString(),
                        options = listOf(1, 2, 4, 8, 16, 20).map(Int::toString),
                        onValueChange = { workerCount = it.toIntOrNull() ?: workerCount },
                    )
                }
                if (selected == null) {
                    Text(stringResource(R.string.pingng_desync_no_supported_profiles))
                } else if (showProfiles && !searching) {
                    if (isFixedProfile) {
                        Text(selected?.second?.let { profile ->
                            profile.remarks.ifBlank { profile.server.orEmpty() }
                        }.orEmpty())
                    } else {
                        Text(stringResource(R.string.pingng_select_config_for_desync))
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(supported, key = { it.first }) { (guid, profile) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = guid == selectedGuid, onClick = { selectedGuid = guid })
                                    Text(profile.remarks.ifBlank { profile.server.orEmpty() })
                                }
                            }
                        }
                    }
                } else if (searching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(R.string.pingng_desync_searching))
                            Text(
                                stringResource(
                                    R.string.pingng_desync_search_progress,
                                    testedCount,
                                    totalCount.coerceAtLeast(1),
                                )
                            )
                        }
                    }
                    TextButton(onClick = {
                        onCancelSearch()
                        searching = false
                        showProfiles = false
                    }) {
                        Text(stringResource(R.string.pingng_desync_cancel_search))
                    }
                    if (results.isNotEmpty()) {
                        LazyColumn(
                            state = liveResultsState,
                            modifier = Modifier.heightIn(max = 220.dp),
                        ) {
                            items(results, key = { it.first.arguments }) { (candidate, delay) ->
                                Button(
                                    onClick = {
                                        onApply(selected.first, selected.second, candidate.arguments)
                                        // Apply first so the cancellation cleanup
                                        // cannot restore the old configuration.
                                        onCancelSearch()
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                ) {
                                    Text("${delay} ms  •  ${candidate.label}")
                                }
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.pingng_desync_choose_result))
                    if (testedCount > 0) {
                        Text(
                            stringResource(
                                R.string.pingng_desync_search_summary,
                                testedCount,
                                totalCount.coerceAtLeast(1),
                                results.size,
                            ),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    if (showingPrevious) {
                        Text(
                            stringResource(R.string.pingng_desync_previous_results),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    if (!isFixedProfile) {
                        TextButton(onClick = {
                            results = emptyList()
                            showProfiles = true
                            showingPrevious = false
                        }) {
                            Text(stringResource(R.string.pingng_select_config_for_desync))
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(results) { (candidate, delay) ->
                            Button(
                                onClick = { onApply(selected.first, selected.second, candidate.arguments); onDismiss() },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            ) { Text("${delay} ms  •  ${candidate.label}") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Keep this action visible after results have been loaded so the user
            // can run the search again for the same or another network state.
            if (!searching && selected != null) {
                Button(onClick = {
                    searching = true
                    testedCount = 0
                    totalCount = requestedCount.coerceIn(1, availableCount.coerceAtLeast(1))
                    results = emptyList()
                    showProfiles = false
                    showingPrevious = false
                    DesyncSearchHistory.clear(selected.first)
                    onStartSearch(
                        selected.first,
                        selected.second,
                        searchFamily,
                        includeAdvanced,
                        totalCount,
                        workerCount,
                        { tested, total ->
                            val safeTotal = maxOf(total, totalCount, tested, 1)
                            totalCount = safeTotal
                            testedCount = tested.coerceIn(0, safeTotal)
                        },
                        { updatedResults ->
                            results = updatedResults
                                .distinctBy { result -> result.first.arguments }
                                .sortedBy { result -> result.second }
                            showingPrevious = false
                            // Keep the dialog open while the runner continues;
                            // a separate completion callback ends the progress state.
                            showProfiles = false
                        },
                        {
                            showProfiles = false
                            showingPrevious = false
                            searching = false
                        },
                    )
                }) {
                    Text(
                        stringResource(
                            if (results.isNotEmpty()) {
                                R.string.pingng_desync_search_again
                            } else {
                                R.string.pingng_start_desync_search
                            }
                        )
                    )
                }
            }
        },
        dismissButton = {
            if (!searching) androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun searchFamilyLabel(family: SearchFamily): Int = when (family) {
    SearchFamily.SPLIT -> R.string.pingng_desync_family_split
    SearchFamily.DISORDER -> R.string.pingng_desync_family_disorder
    SearchFamily.FAKE_SNI -> R.string.pingng_desync_family_fake_sni
    SearchFamily.OUT_OF_BAND -> R.string.pingng_desync_family_out_of_band
    SearchFamily.DISORDER_OUT_OF_BAND -> R.string.pingng_desync_family_disorder_out_of_band
    SearchFamily.ALL -> R.string.pingng_desync_family_all
}
