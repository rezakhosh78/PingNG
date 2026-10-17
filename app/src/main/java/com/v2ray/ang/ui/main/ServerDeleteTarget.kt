package com.v2ray.ang.ui.main

import androidx.compose.runtime.saveable.Saver

data class ServerDeleteTarget(val guid: String, val displayName: String) {
    companion object {
        val Saver: Saver<ServerDeleteTarget?, List<String>> =
            androidx.compose.runtime.saveable.Saver(
                save = { target -> target?.let { listOf(it.guid, it.displayName) } },
                restore = { values ->
                    if (values.size == 2) ServerDeleteTarget(values[0], values[1]) else null
                },
            )
    }
}
