package com.phonecleaner.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.R
import com.phonecleaner.ai.Screen
import com.phonecleaner.ai.ops.Ops
import com.phonecleaner.ai.shizuku.ShizukuState

@Composable
private fun Option(checked: Boolean, onChange: (Boolean) -> Unit, title: String, info: String, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun OptimizeScreen(vm: MainViewModel) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val changes by vm.changes.collectAsStateWithLifecycle()
    val dns by vm.dnsHost.collectAsStateWithLifecycle()
    val shizuku by vm.shizukuState.collectAsStateWithLifecycle()
    var animations by rememberSaveable { mutableStateOf(false) }
    var useDns by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf(false) }
    var background by rememberSaveable { mutableStateOf(false) }

    val settings = scan?.settings.orEmpty()
    fun cur(key: String) = settings["global $key"] ?: "?"
    val flagged = remember(rows) { vm.flaggedApps() }
    val active = changes.filter { !it.reverted && (it.kind == Ops.SETTING || it.kind == Ops.APPOP) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.optimize_intro))
        if (shizuku != ShizukuState.READY) {
            Text(stringResource(R.string.need_shizuku), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
        }
        SectionCard(stringResource(R.string.optimize_choose)) {
            Option(
                animations, { animations = it },
                stringResource(R.string.opt_animations),
                stringResource(
                    R.string.opt_animations_info,
                    cur("window_animation_scale"), cur("transition_animation_scale"), cur("animator_duration_scale"),
                ),
            )
            Option(
                useDns, { useDns = it },
                stringResource(R.string.opt_dns, dns),
                stringResource(R.string.opt_dns_info, cur("private_dns_mode"), cur("private_dns_specifier")),
            )
            Option(
                overlay, { overlay = it },
                stringResource(R.string.opt_overlay),
                stringResource(R.string.opt_overlay_info, flagged.count { it.overlay }),
                enabled = flagged.any { it.overlay },
            )
            Option(
                background, { background = it },
                stringResource(R.string.opt_background),
                stringResource(R.string.opt_background_info, flagged.size),
                enabled = flagged.isNotEmpty(),
            )
            Button(
                onClick = { vm.planOptimize(animations, useDns, overlay, background) },
                enabled = shizuku == ShizukuState.READY && (animations || useDns || overlay || background),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.optimize_review)) }
        }
        SectionCard(stringResource(R.string.optimize_flagged, flagged.size)) {
            if (flagged.isEmpty()) BodyText(stringResource(R.string.optimize_flagged_none))
            flagged.take(60).forEach { BodyText("• ${it.label}  (${it.pkg})") }
        }
        SectionCard(stringResource(R.string.optimize_active, active.size)) {
            if (active.isEmpty()) BodyText("–")
            active.take(30).forEach { BodyText("• " + changeTitle(it)) }
            if (active.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { vm.planRevert(active) }) { Text(stringResource(R.string.optimize_undo_all)) }
                }
                OutlinedButton(onClick = { vm.go(Screen.RESTORE) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.step_restore))
                }
            }
        }
    }
}
