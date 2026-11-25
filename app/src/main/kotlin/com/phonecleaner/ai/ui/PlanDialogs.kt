package com.phonecleaner.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.R
import com.phonecleaner.ai.ops.Ops

/** Confirmation and progress dialogs shared by cleaning, optimisation and restore. */
@Composable
fun PlanDialogs(vm: MainViewModel) {
    val plan by vm.pendingPlan.collectAsStateWithLifecycle()
    val work by vm.work.collectAsStateWithLifecycle()

    plan?.let { p ->
        val removals = p.count { it.kind == Ops.UNINSTALL }
        AlertDialog(
            onDismissRequest = { vm.cancelPlan() },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.confirm_intro))
                    if (removals > 0) Text(stringResource(R.string.confirm_removals_fmt, removals), Modifier.padding(top = 8.dp))
                    if (p.size > removals) Text(stringResource(R.string.confirm_changes_fmt, p.size - removals), Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.confirm_commands), Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
                    p.forEach {
                        Text(it.label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(it.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        if (it.kind == Ops.UNINSTALL) Text(
                            stringResource(
                                if (it.detail == "user") R.string.confirm_fallback_user else R.string.confirm_fallback,
                                it.target,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = { vm.confirmPlan() }) { Text(stringResource(R.string.confirm_yes)) } },
            dismissButton = { TextButton(onClick = { vm.cancelPlan() }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val revert by vm.pendingRevert.collectAsStateWithLifecycle()
    revert?.let { list ->
        AlertDialog(
            onDismissRequest = { vm.cancelRevert() },
            title = { Text(stringResource(R.string.restore_all_title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.restore_all_text, list.size))
                    Text(stringResource(R.string.confirm_commands), Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
                    list.forEach {
                        Text(changeTitle(it), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(Ops.revertCommand(it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { Button(onClick = { vm.confirmRevert() }) { Text(stringResource(R.string.confirm_yes)) } },
            dismissButton = { TextButton(onClick = { vm.cancelRevert() }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (work.visible) {
        AlertDialog(
            onDismissRequest = { vm.dismissWork() },
            title = { Text(stringResource(if (work.restoring) R.string.work_restoring else R.string.work_title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (work.error == MainViewModel.ERR_SHIZUKU) {
                        Text(stringResource(R.string.need_shizuku), color = MaterialTheme.colorScheme.error)
                    } else if (work.error == MainViewModel.ERR_ROOT) {
                        Text(stringResource(R.string.err_root), color = MaterialTheme.colorScheme.error)
                    } else {
                        if (work.total > 0) LinearProgressIndicator(
                            progress = { work.done / work.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.work_progress_fmt, work.done, work.total, work.succeeded), Modifier.padding(top = 8.dp))
                        work.backupPath?.let {
                            Text(stringResource(R.string.work_backup_fmt, it), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        if (work.disabledInstead.isNotEmpty()) {
                            Text(stringResource(R.string.work_disabled), Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                            work.disabledInstead.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (work.failed.isNotEmpty()) {
                            Text(stringResource(R.string.work_failed), Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            work.failed.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            Text(stringResource(R.string.work_failed_hint), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        work.error?.let { Text(stringResource(R.string.error_fmt, it), color = MaterialTheme.colorScheme.error) }
                        if (!work.running && !work.restoring) {
                            Text(stringResource(R.string.work_undo_hint), Modifier.padding(top = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissWork() }, enabled = !work.running) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}
