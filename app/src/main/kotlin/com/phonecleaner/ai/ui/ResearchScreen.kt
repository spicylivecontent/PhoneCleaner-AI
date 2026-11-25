package com.phonecleaner.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.R
import com.phonecleaner.ai.Screen
import com.phonecleaner.ai.ai.ClaudeException
import com.phonecleaner.ai.ai.Usage
import java.util.Locale

private fun usd(v: Double) = String.format(Locale.US, "$%.2f", v)

@Composable
fun ResearchScreen(vm: MainViewModel) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val state by vm.research.collectAsStateWithLifecycle()
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val s = scan

    // Keep the screen on while requests are running so the phone does not suspend the app.
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (s == null) {
            Text(stringResource(R.string.research_need_scan))
            return@Column
        }
        val candidates = vm.researchCandidates(s)
        val locked = s.apps.count { it.installed && it.locked }
        val cached = candidates.count { it.pkg in analyses }
        val todo = candidates.size - cached
        val batches = (todo + MainViewModel.BATCH_SIZE - 1) / MainViewModel.BATCH_SIZE
        val estimate = Usage.TYPICAL_BATCH.costUsd(model) * batches

        Text(stringResource(R.string.research_intro))
        SectionCard(stringResource(R.string.research_plan)) {
            BodyText(stringResource(R.string.research_plan_fmt, locked, cached, todo, batches))
            BodyText(stringResource(R.string.research_model_fmt, model.title))
            BodyText(stringResource(R.string.research_estimate_fmt, usd(estimate * 0.5), usd(estimate * 2)))
        }
        if (!hasKey) {
            Text(stringResource(R.string.research_need_key), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { vm.go(Screen.SETTINGS) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.settings))
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startResearch() }, enabled = hasKey && !state.running && todo > 0) {
                Text(stringResource(if (cached > 0 && todo > 0) R.string.research_continue else R.string.research_start))
            }
            OutlinedButton(onClick = { vm.cancelResearch() }, enabled = state.running) {
                Text(stringResource(R.string.cancel))
            }
        }
        if (state.running || state.batchesDone > 0 || state.error != null) {
            SectionCard(stringResource(R.string.research_progress)) {
                if (state.batchesTotal > 0) {
                    LinearProgressIndicator(
                        progress = { state.batchesDone / state.batchesTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                BodyText(
                    stringResource(
                        R.string.research_progress_fmt,
                        state.batchesDone, state.batchesTotal, state.packagesDone,
                    )
                )
                BodyText(
                    stringResource(
                        R.string.research_cost_fmt,
                        usd(state.usage.costUsd(model)), state.usage.inputTokens,
                        state.usage.outputTokens, state.usage.searches,
                    )
                )
                if (state.running) BodyText(stringResource(R.string.research_keep_open))
                if (state.batchesFailed > 0) BodyText(stringResource(R.string.research_failed_fmt, state.batchesFailed))
                if (state.finished) BodyText(stringResource(R.string.research_done))
                state.error?.let {
                    val msg = if (it == MainViewModel.KEY_UNREADABLE) stringResource(R.string.key_unreadable) else when (state.errorKind) {
                        ClaudeException.Kind.BAD_KEY -> stringResource(R.string.research_err_key, it)
                        ClaudeException.Kind.NETWORK -> stringResource(R.string.research_err_network, it)
                        else -> stringResource(R.string.error_fmt, it)
                    }
                    Text(msg, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                    BodyText(stringResource(R.string.research_resume_hint))
                }
            }
        }
        if (todo == 0 && candidates.isNotEmpty()) {
            BodyText(stringResource(R.string.research_all_cached))
        }
    }
}
