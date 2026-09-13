package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.VersionInfoBlock
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.util.Utils

class AboutActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        AboutScreen(
            onBackClick = { finish() },
            onDeveloperTripleTap = {
                startActivity(Intent(this, LogcatActivity::class.java))
            },
        )
    }
}

@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onDeveloperTripleTap: () -> Unit,
) {
    val context = LocalContext.current

    val libVersion = CoreNativeManager.getLibVersion()
    val versionText = "${BuildConfig.VERSION_NAME} ($libVersion)"
    val appIdText = "Powered By ReZa Kh"
    var developerTapCount by remember { mutableStateOf(0) }
    var lastDeveloperTapAt by remember { mutableStateOf(0L) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_about),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_source_code_24dp),
                title = stringResource(R.string.title_source_code),
                onClick = { Utils.openUri(context, AppConfig.APP_URL) }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_telegram_24dp),
                title = stringResource(R.string.title_tg_channel),
                onClick = { Utils.openUri(context, AppConfig.TG_CHANNEL_URL) }
            )
            VersionInfoBlock(
                versionText = versionText,
                appIdText = appIdText,
                onAppIdClick = {
                    val now = SystemClock.elapsedRealtime()
                    developerTapCount = if (now - lastDeveloperTapAt <= 1_500L) {
                        developerTapCount + 1
                    } else {
                        1
                    }
                    lastDeveloperTapAt = now
                    if (developerTapCount >= 3) {
                        developerTapCount = 0
                        onDeveloperTripleTap()
                    }
                },
            )
            NavigationBarsSpacer()
        }
    }
}
