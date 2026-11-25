package com.phonecleaner.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.MainViewModel.Group
import com.phonecleaner.ai.R
import com.phonecleaner.ai.scan.LockReason

private fun groupTitle(g: Group) = when (g) {
    Group.MALWARE -> R.string.group_malware
    Group.ADS -> R.string.group_ads
    Group.SAFE -> R.string.group_safe
    Group.UNSURE -> R.string.group_unsure
    Group.LOCKED -> R.string.group_locked
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: MainViewModel) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(setOf(Group.LOCKED.name)) }
    var detail by remember { mutableStateOf<MainViewModel.Row?>(null) }

    val q = query.trim().lowercase()
    val filtered = if (q.isEmpty()) rows else rows.filter {
        it.app.label.lowercase().contains(q) || it.app.pkg.lowercase().contains(q) ||
            it.analysis?.whatItDoes?.lowercase()?.contains(q) == true
    }
    val grouped = filtered.groupBy { it.group }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.list_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.list_selected_fmt, selected.size), Modifier.weight(1f))
                TextButton(onClick = { vm.resetSelection() }) { Text(stringResource(R.string.list_reset)) }
                TextButton(onClick = { vm.uncheckAll() }) { Text(stringResource(R.string.list_uncheck_all)) }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            for (g in Group.entries) {
                val items = grouped[g].orEmpty()
                val isCollapsed = g.name in collapsed && q.isEmpty()
                item(key = "h-${g.name}") {
                    Surface(
                        color = if (g == Group.MALWARE && items.isNotEmpty()) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().clickable {
                            collapsed = if (g.name in collapsed) collapsed - g.name else collapsed + g.name
                        },
                    ) {
                        Text(
                            (if (isCollapsed) "▸ " else "▾ ") + stringResource(groupTitle(g)) + "  (${items.size})",
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (!isCollapsed) items(items, key = { it.app.pkg }) { row ->
                    AppRow(row, row.app.pkg in selected, onToggle = { vm.toggle(row.app.pkg, it) }, onClick = { detail = row })
                    HorizontalDivider()
                }
            }
        }
        ListBottomBar(vm, selected.size)
    }

    detail?.let { row ->
        ModalBottomSheet(onDismissRequest = { detail = null }) { DetailSheet(row) }
    }
}

@Composable
fun ListBottomBar(vm: MainViewModel, count: Int) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = { vm.planClean() }, enabled = count > 0, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.clean_button_fmt, count))
            }
            Text(stringResource(R.string.clean_button_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppRow(row: MainViewModel.Row, checked: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle, enabled = row.selectable)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(row.app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                row.app.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sub = row.analysis?.whatItDoes ?: when {
                row.app.locked -> stringResource(lockText(row.app.lockReason))
                else -> stringResource(R.string.list_not_analysed)
            }
            Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun lockText(r: LockReason?) = when (r) {
    LockReason.LAUNCHER -> R.string.lock_launcher
    LockReason.KEYBOARD -> R.string.lock_keyboard
    LockReason.DEFAULT_APP -> R.string.lock_default_app
    LockReason.PERSISTENT -> R.string.lock_persistent
    LockReason.RESOURCE_OVERLAY -> R.string.lock_overlay
    LockReason.SELF -> R.string.lock_self
    LockReason.UNKNOWN -> R.string.lock_unknown
    else -> R.string.lock_core
}

@Composable
private fun DetailSheet(row: MainViewModel.Row) {
    val app = row.app
    val a = row.analysis
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(app.label, style = MaterialTheme.typography.titleLarge)
        Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(groupTitle(row.group)), Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
        if (app.locked) BodyText(stringResource(lockText(app.lockReason)))
        if (a != null) {
            Label(R.string.detail_what); BodyText(a.whatItDoes)
            Label(R.string.detail_verdict)
            BodyText(
                stringResource(
                    when (a.verdict) {
                        "safe" -> R.string.verdict_safe
                        "caution" -> R.string.verdict_caution
                        else -> R.string.verdict_keep
                    }
                )
            )
            Label(R.string.detail_risk); BodyText(a.risk.ifBlank { "–" })
            if (a.isAdOrTracker) BodyText("• " + stringResource(R.string.flag_ads))
            if (a.suspectedMalware) BodyText("• " + stringResource(R.string.flag_malware))
        } else if (!app.locked) {
            BodyText(stringResource(R.string.list_not_analysed))
        }
        Label(R.string.detail_facts)
        val facts = buildList {
            add(stringResource(if (app.isSystem) R.string.fact_system else R.string.fact_user))
            add(stringResource(R.string.fact_installer, app.installer ?: "–"))
            if (app.sideloaded) add(stringResource(R.string.fact_sideloaded))
            if (!app.hasLauncherIcon) add(stringResource(R.string.fact_no_icon))
            if (app.overlay) add(stringResource(R.string.fact_overlay))
            if (app.deviceAdmin) add(stringResource(R.string.fact_admin))
            if (app.accessibility) add(stringResource(R.string.fact_accessibility))
            if (app.suspicious) add(stringResource(R.string.fact_suspicious))
            if (!app.enabled) add(stringResource(R.string.fact_disabled))
            app.storageBytes?.let { add(stringResource(R.string.fact_storage, formatBytes(it))) }
            app.dataBytes?.let { add(stringResource(R.string.fact_data, formatBytes(it))) }
            app.lastUsed?.let { add(stringResource(R.string.fact_last_used, it)) }
        }
        facts.forEach { BodyText("• $it") }
        if (app.dangerousPerms.isNotEmpty()) {
            Label(R.string.detail_perms)
            BodyText(app.dangerousPerms.joinToString(", ") { it.lowercase() })
        }
        val sources = a?.sources?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        if (a != null) {
            Label(R.string.detail_sources)
            if (sources.isEmpty()) BodyText(stringResource(R.string.detail_no_sources))
            sources.forEach { url ->
                Text(
                    url,
                    Modifier.padding(top = 6.dp).clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Label(id: Int) {
    Text(stringResource(id), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
