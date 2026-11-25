package com.phonecleaner.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.R
import com.phonecleaner.ai.data.ChangeEntity
import com.phonecleaner.ai.ops.Ops

@Composable
fun changeTitle(c: ChangeEntity): String = when (c.kind) {
    Ops.UNINSTALL -> stringResource(R.string.change_uninstalled, c.label)
    Ops.DISABLE -> stringResource(R.string.change_disabled, c.label)
    Ops.SETTING -> stringResource(R.string.change_setting, c.label, c.originalValue, c.newValue)
    else -> stringResource(R.string.change_appop, c.label, c.detail, c.originalValue, c.newValue)
}

@Composable
fun RestoreScreen(vm: MainViewModel) {
    val changes by vm.changes.collectAsStateWithLifecycle()
    val active = changes.filter { !it.reverted }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.restore_intro))
            Button(
                onClick = { vm.planRevert(active) },
                enabled = active.isNotEmpty(),
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(stringResource(R.string.restore_all_fmt, active.size)) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(active, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(changeTitle(c))
                        Text(
                            c.target + " · " + formatTime(c.time),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { vm.planRevert(listOf(c)) }) { Text(stringResource(R.string.restore_one)) }
                }
                HorizontalDivider()
            }
            if (active.isEmpty()) item { Text(stringResource(R.string.restore_empty), Modifier.padding(16.dp)) }
        }
    }
}

@Composable
fun LogScreen(vm: MainViewModel) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        if (logs.isEmpty()) item { Text(stringResource(R.string.log_empty), Modifier.padding(16.dp)) }
        items(logs, key = { it.id }) { l ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    formatTime(l.time) + if (l.ok) "" else "  ✗",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (l.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (l.command != "#") Text("$ " + l.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (l.result.isNotBlank()) Text(l.result, style = MaterialTheme.typography.bodySmall, maxLines = 6)
            }
            HorizontalDivider()
        }
    }
}
