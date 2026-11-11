package com.v2ray.ang.ui.main

import android.content.Context

internal fun String.localizeDigits(context: Context): String {
    if (context.resources.configuration.locales[0].language != "fa") return this
    return buildString(length) {
        this@localizeDigits.forEach { character ->
            append(
                if (character in '0'..'9') {
                    '۰' + (character - '0')
                } else {
                    character
                },
            )
        }
    }
}
