package com.phonecleaner.ai.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecleaner.ai.BuildConfig
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.R
import com.phonecleaner.ai.shizuku.ShizukuManager
import com.phonecleaner.ai.shizuku.ShizukuState

@Composable
fun HomeScreen(vm: MainViewModel) {
    LifecycleResumeEffect(Unit) {
        vm.refreshShizuku()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.home_intro), style = MaterialTheme.typography.bodyLarge)
        ShizukuCard(vm)
        HomeSteps(vm)
        Text(
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
                stringResource(R.string.version_fmt, BuildConfig.VERSION_NAME),
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun HomeSteps(vm: MainViewModel) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    StepButton(
        stringResource(R.string.step_scan),
        scan?.let { stringResource(R.string.scan_done_fmt, it.apps.size, formatTime(it.time)) }
            ?: stringResource(R.string.scan_none),
    ) { vm.go(com.phonecleaner.ai.Screen.SCAN) }
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val candidates = scan?.let { vm.researchCandidates(it) }.orEmpty()
    StepButton(
        stringResource(R.string.step_research),
        when {
            scan == null -> stringResource(R.string.research_need_scan)
            !hasKey -> stringResource(R.string.research_need_key)
            else -> stringResource(R.string.research_status_fmt, candidates.count { it.pkg in analyses }, candidates.size)
        },
        enabled = scan != null,
    ) { vm.go(com.phonecleaner.ai.Screen.RESEARCH) }
    HomeStepsMore(vm)
    StepButton(
        stringResource(R.string.settings),
        stringResource(if (hasKey) R.string.settings_key_saved else R.string.settings_key_missing),
    ) { vm.go(com.phonecleaner.ai.Screen.SETTINGS) }
}

@Composable
fun HomeStepsMore(vm: MainViewModel) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    StepButton(
        stringResource(R.string.step_list),
        if (scan == null) stringResource(R.string.research_need_scan)
        else stringResource(R.string.list_status_fmt, selected.size),
        enabled = scan != null,
    ) { vm.go(com.phonecleaner.ai.Screen.LIST) }
    HomeStepsTail(vm)
}

@Composable
fun HomeStepsTail(vm: MainViewModel) {
    val changes by vm.changes.collectAsStateWithLifecycle()
    HomeStepsOptimize(vm)
    StepButton(
        stringResource(R.string.step_restore),
        stringResource(R.string.restore_status_fmt, changes.count { !it.reverted }),
    ) { vm.go(com.phonecleaner.ai.Screen.RESTORE) }
    StepButton(stringResource(R.string.step_log), stringResource(R.string.log_subtitle)) {
        vm.go(com.phonecleaner.ai.Screen.LOG)
    }
}

@Composable
fun HomeStepsOptimize(vm: MainViewModel) {
}

@Composable
fun StepButton(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShizukuCard(vm: MainViewModel) {
    val state by vm.shizukuState.collectAsStateWithLifecycle()
    val uid by vm.shizukuUid.collectAsStateWithLifecycle()
    val test by vm.shellTest.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val ok = state == ShizukuState.READY
    SectionCard(
        title = stringResource(R.string.shizuku_title),
        container = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        val status = when (state) {
            ShizukuState.NOT_INSTALLED -> R.string.shizuku_not_installed
            ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running
            ShizukuState.NO_PERMISSION -> R.string.shizuku_no_permission
            ShizukuState.CONNECTING -> R.string.shizuku_connecting
            ShizukuState.READY -> R.string.shizuku_ready
        }
        BodyText(stringResource(status))
        if (ok) {
            val who = when (uid) {
                2000 -> stringResource(R.string.shizuku_uid_shell)
                0 -> stringResource(R.string.shizuku_uid_root)
                else -> "uid $uid"
            }
            BodyText(stringResource(R.string.shizuku_runs_as, who))
        }
        if (state == ShizukuState.NOT_INSTALLED || state == ShizukuState.NOT_RUNNING) {
            BodyText(stringResource(R.string.shizuku_howto))
        }
        if (state != ShizukuState.NOT_INSTALLED) {
            BodyText(stringResource(R.string.shizuku_xiaomi_note))
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ShizukuState.NOT_INSTALLED -> Button(onClick = {
                    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
                    runCatching { ctx.startActivity(i) }
                }) { Text(stringResource(R.string.shizuku_get)) }

                ShizukuState.NOT_RUNNING -> {
                    Button(onClick = {
                        val i = ctx.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
                        if (i != null) runCatching { ctx.startActivity(i) }
                    }) { Text(stringResource(R.string.shizuku_open)) }
                    OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    }) { Text(stringResource(R.string.open_dev_options)) }
                }

                ShizukuState.NO_PERMISSION -> Button(onClick = { vm.requestShizukuPermission() }) {
                    Text(stringResource(R.string.shizuku_grant))
                }

                ShizukuState.CONNECTING -> {}
                ShizukuState.READY -> OutlinedButton(onClick = { vm.testShell() }) {
                    Text(stringResource(R.string.shizuku_test))
                }
            }
            if (!ok) OutlinedButton(onClick = { vm.refreshShizuku() }) { Text(stringResource(R.string.refresh)) }
        }
        test?.let {
            Text(it, Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
