package com.phonecleaner.ai.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phonecleaner.ai.BuildConfig
import com.phonecleaner.ai.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Text(stringResource(R.string.home_intro), style = MaterialTheme.typography.bodyLarge)
            Text(
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                Modifier.padding(top = 16.dp)
            )
            Text(stringResource(R.string.version_fmt, BuildConfig.VERSION_NAME), Modifier.padding(top = 8.dp))
        }
    }
}
