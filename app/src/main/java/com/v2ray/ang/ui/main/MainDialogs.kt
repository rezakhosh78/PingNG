package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.dto.CheckUpdateResult
import java.io.File
import java.util.Locale
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.DeleteConfirmDialog

@Composable
fun MainDialogs(
    showDelAllConfirm: Boolean,
    onDismissDelAll: () -> Unit,
    onConfirmDelAll: () -> Unit,
    showDelDuplicateConfirm: Boolean,
    onDismissDelDuplicate: () -> Unit,
    onConfirmDelDuplicate: () -> Unit,
    showDelInvalidConfirm: Boolean,
    onDismissDelInvalid: () -> Unit,
    onConfirmDelInvalid: () -> Unit,
    showRemoveConfirm: ServerDeleteTarget?,
    onDismissRemove: () -> Unit,
    onConfirmRemove: (ServerDeleteTarget) -> Unit,
) {
    if (showDelAllConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_visible_profiles),
            onConfirm = onConfirmDelAll,
            onDismiss = onDismissDelAll
        )
    }
    if (showDelDuplicateConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_duplicate_profiles),
            onConfirm = onConfirmDelDuplicate,
            onDismiss = onDismissDelDuplicate
        )
    }
    if (showDelInvalidConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_invalid_profiles),
            onConfirm = onConfirmDelInvalid,
            onDismiss = onDismissDelInvalid
        )
    }
    if (showRemoveConfirm != null) {
        val target = showRemoveConfirm
        val message = if (target.displayName.isNotBlank()) {
            stringResource(R.string.confirm_delete_profile_named, target.displayName)
        } else {
            stringResource(R.string.confirm_delete_profile)
        }
        DeleteConfirmDialog(
            message = message,
            onConfirm = { onConfirmRemove(target) },
            onDismiss = onDismissRemove
        )
    }
}

@Composable
fun AppUpdateDialog(
    result: CheckUpdateResult?,
    downloadState: AppUpdateDownloadState,
    onDismiss: () -> Unit,
    onDownload: (CheckUpdateResult) -> Unit,
    onInstall: (File) -> Unit,
) {
    if (result == null) return

    val releaseNotes = result.releaseNotes.orEmpty().ifBlank {
        stringResource(R.string.update_release_notes_empty)
    }
    val scrollState = rememberScrollState()
    val installedFile = downloadState.downloadedFile

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.update_new_version_found, result.latestVersion.orEmpty()))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (downloadState.isDownloading || installedFile != null) 160.dp else 280.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (downloadState.isDownloading) {
                    val percent = downloadState.progressPercent
                    val progress = downloadState.progress
                    val progressText = if (percent != null && downloadState.totalBytes > 0L) {
                        stringResource(
                            R.string.update_download_progress,
                            percent,
                            formatUpdateBytes(downloadState.downloadedBytes),
                            formatUpdateBytes(downloadState.totalBytes),
                        )
                    } else {
                        stringResource(
                            R.string.update_downloaded_bytes,
                            formatUpdateBytes(downloadState.downloadedBytes),
                        )
                    }
                    Text(progressText, style = MaterialTheme.typography.bodyMedium)
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else if (installedFile != null) {
                    Text(
                        stringResource(R.string.update_download_complete),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            when {
                installedFile != null -> TextButton(onClick = { onInstall(installedFile) }) {
                    Text(stringResource(R.string.update_install))
                }
                downloadState.isDownloading -> TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.update_downloading))
                }
                else -> TextButton(onClick = { onDownload(result) }) {
                    Text(stringResource(R.string.update_now))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

private fun formatUpdateBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.coerceAtLeast(0L).toDouble()
    var unitIndex = 0
    while (size >= 1024.0 && unitIndex < units.lastIndex) {
        size /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${size.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }
}
