package com.phonecleaner.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.R
import com.phonecleaner.ai.scan.AppInfo
import com.phonecleaner.ai.scan.ScanResult
import com.phonecleaner.ai.shizuku.ShizukuState
import java.util.Locale

@Composable
fun ScanScreen(vm: MainViewModel) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val step by vm.scanStep.collectAsStateWithLifecycle()
    val error by vm.scanError.collectAsStateWithLifecycle()
    val shizuku by vm.shizukuState.collectAsStateWithLifecycle()
    val steps = stringArrayResource(R.array.scan_steps)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.scan_intro))
        if (shizuku != ShizukuState.READY) {
            Text(
                stringResource(R.string.need_shizuku),
                Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { vm.startScan() },
            enabled = !scanning && shizuku == ShizukuState.READY,
            modifier = Modifier.padding(top = 12.dp),
        ) { Text(stringResource(if (scan == null) R.string.scan_start else R.string.scan_again)) }
        if (scanning) {
            LinearProgressIndicator(
                progress = { (step + 1) / (steps.size + 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(steps.getOrElse(step) { "" }, Modifier.padding(top = 4.dp))
        }
        error?.let {
            Text(stringResource(R.string.error_fmt, it), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
        }
        scan?.let { if (!scanning) ScanSummary(it) }
    }
}

@Composable
private fun ScanSummary(s: ScanResult) {
    val apps = s.apps.filter { it.installed }
    val user = apps.filter { !it.isSystem }
    val unlocked = apps.filter { !it.locked }

    SectionCard(stringResource(R.string.scan_device)) {
        BodyText("${s.deviceModel}\n${s.osVersion} · Android ${s.androidVersion}\n${formatTime(s.time)}")
    }
    SectionCard(stringResource(R.string.scan_summary)) {
        BodyText(
            stringResource(
                R.string.scan_summary_fmt,
                apps.size, apps.size - user.size, user.size,
                apps.count { it.locked }, s.apps.count { !it.installed },
            )
        )
    }

    val suspicious = apps.filter { it.suspicious }
    SectionCard(
        stringResource(R.string.scan_suspicious, suspicious.size),
        container = if (suspicious.isEmpty()) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.errorContainer,
    ) {
        if (suspicious.isEmpty()) BodyText(stringResource(R.string.scan_suspicious_none))
        else {
            BodyText(stringResource(R.string.scan_suspicious_why))
            AppLines(suspicious)
        }
    }
    FlagCard(R.string.scan_admins, apps.filter { it.deviceAdmin })
    FlagCard(R.string.scan_accessibility, apps.filter { it.accessibility })
    FlagCard(R.string.scan_overlay, unlocked.filter { it.overlay })
    FlagCard(R.string.scan_sideloaded, user.filter { it.sideloaded })
    FlagCard(R.string.scan_no_icon, user.filter { !it.hasLauncherIcon })

    val topPerms = unlocked.filter { it.dangerousPerms.size >= 6 }.sortedByDescending { it.dangerousPerms.size }.take(10)
    if (topPerms.isNotEmpty()) SectionCard(stringResource(R.string.scan_many_perms)) {
        topPerms.forEach { BodyText("${it.label}: ${it.dangerousPerms.size}  (${it.dangerousPerms.take(6).joinToString { p -> p.lowercase() }}…)") }
    }
    val topStorage = apps.filter { it.storageBytes != null }.sortedByDescending { it.storageBytes }.take(8)
    if (topStorage.isNotEmpty()) SectionCard(stringResource(R.string.scan_top_storage)) {
        topStorage.forEach { BodyText("${it.label}: ${formatBytes(it.storageBytes!!)}") }
    }
    val topBattery = apps.filter { (it.batteryMah ?: 0.0) > 0.0 }.distinctBy { it.uid }.sortedByDescending { it.batteryMah }.take(8)
    if (topBattery.isNotEmpty()) SectionCard(stringResource(R.string.scan_top_battery)) {
        topBattery.forEach { BodyText("${it.label}: ${String.format(Locale.US, "%.0f mAh", it.batteryMah)}") }
    }
    val topData = apps.filter { (it.dataBytes ?: 0L) > 0L }.distinctBy { it.uid }.sortedByDescending { it.dataBytes }.take(8)
    if (topData.isNotEmpty()) SectionCard(stringResource(R.string.scan_top_data)) {
        topData.forEach { BodyText("${it.label}: ${formatBytes(it.dataBytes!!)}") }
    }
    SectionCard(stringResource(R.string.scan_settings)) {
        s.settings.forEach { (k, v) -> BodyText("${k.substringAfter(' ')} = $v") }
    }
    if (s.warnings.isNotEmpty()) SectionCard(stringResource(R.string.scan_warnings)) {
        s.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun FlagCard(title: Int, apps: List<AppInfo>) {
    SectionCard(stringResource(title, apps.size)) {
        if (apps.isEmpty()) BodyText("–") else AppLines(apps)
    }
}

@Composable
private fun AppLines(apps: List<AppInfo>) {
    apps.take(40).forEach {
        Text(it.label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
        Text(it.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (apps.size > 40) BodyText("+ ${apps.size - 40}")
}
