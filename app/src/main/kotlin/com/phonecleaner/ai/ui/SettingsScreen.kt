package com.phonecleaner.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecleaner.ai.MainViewModel
import com.phonecleaner.ai.R
import com.phonecleaner.ai.ai.ClaudeModel
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val dns by vm.dnsHost.collectAsStateWithLifecycle()
    val keyTest by vm.keyTest.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }
    var dnsInput by remember(dns) { mutableStateOf(dns) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionCard(stringResource(R.string.settings_api_key)) {
            BodyText(stringResource(R.string.settings_api_key_info))
            BodyText(stringResource(if (hasKey) R.string.settings_key_saved else R.string.settings_key_missing))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("sk-ant-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveApiKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) {
                    Text(stringResource(R.string.save))
                }
                if (hasKey) {
                    OutlinedButton(onClick = { vm.testApiKey() }) { Text(stringResource(R.string.settings_test_key)) }
                    OutlinedButton(onClick = { vm.saveApiKey("") }) { Text(stringResource(R.string.delete)) }
                }
            }
            keyTest?.let {
                BodyText(
                    if (it == MainViewModel.KEY_UNREADABLE) stringResource(R.string.key_unreadable)
                    else stringResource(R.string.settings_test_result, it)
                )
            }
        }
        SectionCard(stringResource(R.string.settings_model)) {
            BodyText(stringResource(R.string.settings_model_info))
            ClaudeModel.entries.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = m == model, onClick = { vm.setModel(m) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = m == model, onClick = { vm.setModel(m) })
                    Column {
                        Text(m.title)
                        Text(
                            String.format(Locale.US, "$%.2f / $%.2f per 1M tokens", m.inPrice, m.outPrice),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        SectionCard(stringResource(R.string.settings_dns)) {
            BodyText(stringResource(R.string.settings_dns_info))
            OutlinedTextField(
                value = dnsInput,
                onValueChange = { dnsInput = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(onClick = { vm.setDnsHost(dnsInput) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.save))
            }
        }
        SectionCard(stringResource(R.string.settings_cache)) {
            BodyText(stringResource(R.string.settings_cache_info))
            OutlinedButton(onClick = { vm.clearAnalysisCache() }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_cache_clear))
            }
        }
        BodyText(stringResource(R.string.settings_language_info))
    }
}
