package com.phonecleaner.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.R
import com.phonecleaner.ai.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    BackHandler(enabled = screen != Screen.HOME) { vm.go(Screen.HOME) }
    val title = when (screen) {
        Screen.HOME -> R.string.app_name
        Screen.SCAN -> R.string.step_scan
        Screen.RESEARCH -> R.string.step_research
        Screen.LIST -> R.string.step_list
        Screen.OPTIMIZE -> R.string.step_optimize
        Screen.RESTORE -> R.string.step_restore
        Screen.LOG -> R.string.step_log
        Screen.SETTINGS -> R.string.settings
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        TextButton(onClick = { vm.go(Screen.HOME) }) { Text("←  " + stringResource(R.string.back)) }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.SCAN -> ScanScreen(vm)
                Screen.RESEARCH -> ResearchScreen(vm)
                Screen.LIST -> ListScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                else -> HomeScreen(vm)
            }
        }
    }
}
