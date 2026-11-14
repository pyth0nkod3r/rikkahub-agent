package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun SettingHarnessPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var watchdogTimeoutText by remember(settings.streamIdleTimeoutSeconds) {
        mutableStateOf(settings.streamIdleTimeoutSeconds.toString())
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_harness))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section 1: Resiliency & Model Layer
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_harness_section_resiliency)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_harness_fallback_model)) },
                        supportingContent = { Text(stringResource(R.string.setting_harness_fallback_model_desc)) },
                    )
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            ModelSelector(
                                modelId = settings.fallbackModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    val isCleared = model.modelId.isBlank()
                                    vm.updateSettings { current ->
                                        current.copy(fallbackModelId = if (isCleared) null else model.id)
                                    }
                                },
                            )
                        }
                    }

                    item(
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.setting_harness_watchdog_timeout_desc,
                                    settings.streamIdleTimeoutSeconds,
                                )
                            )
                        },
                        supportingContent = {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(
                                    text = if (settings.streamIdleTimeoutSeconds <= 0) {
                                        "Watchdog disabled (no idle timeout)"
                                    } else {
                                        "${settings.streamIdleTimeoutSeconds}s idle token threshold"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Slider(
                                    value = settings.streamIdleTimeoutSeconds.toFloat().coerceIn(0f, 300f),
                                    onValueChange = { newVal ->
                                        val rounded = (newVal / 5f).roundToInt() * 5
                                        watchdogTimeoutText = rounded.toString()
                                        vm.updateSettings { current ->
                                            current.copy(streamIdleTimeoutSeconds = rounded.coerceAtLeast(0))
                                        }
                                    },
                                    valueRange = 0f..300f,
                                    steps = 59,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = watchdogTimeoutText,
                                    onValueChange = { value ->
                                        watchdogTimeoutText = value.filter(Char::isDigit)
                                        watchdogTimeoutText.toIntOrNull()?.let { parsed ->
                                            val normalized = parsed.coerceAtLeast(0)
                                            if (normalized != settings.streamIdleTimeoutSeconds) {
                                                vm.updateSettings { current ->
                                                    current.copy(streamIdleTimeoutSeconds = normalized)
                                                }
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    label = { Text("Timeout in seconds (0 to disable)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .onFocusChanged { focusState ->
                                            if (!focusState.isFocused) {
                                                val normalized = watchdogTimeoutText.toIntOrNull()
                                                    ?.coerceAtLeast(0)
                                                    ?: settings.streamIdleTimeoutSeconds
                                                watchdogTimeoutText = normalized.toString()
                                                vm.updateSettings { current ->
                                                    if (current.streamIdleTimeoutSeconds == normalized) {
                                                        current
                                                    } else {
                                                        current.copy(streamIdleTimeoutSeconds = normalized)
                                                    }
                                                }
                                            }
                                        },
                                )
                            }
                        },
                    )

                    item(
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Refresh01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_harness_upstream_context_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_harness_upstream_context_desc)) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.CheckmarkCircle01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }
            }

            // Section 2: Sandboxing & Sub-Agent Isolation
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_harness_section_sandboxing)) },
                ) {
                    item(
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.GitFork,
                                contentDescription = null,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_harness_worktree_isolation)) },
                        supportingContent = { Text(stringResource(R.string.setting_harness_worktree_isolation_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.subAgentWorktreeIsolation,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { it.copy(subAgentWorktreeIsolation = checked) }
                                },
                            )
                        },
                    )
                }
            }

            // Section 3: Tools & Context Budget
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_harness_section_tools)) },
                ) {
                    item(
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.setting_harness_tool_cap_desc,
                                    settings.maxToolsSoftCap,
                                )
                            )
                        },
                        supportingContent = {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(
                                    text = "${settings.maxToolsSoftCap} tools soft cap (128 recommended)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Slider(
                                    value = settings.maxToolsSoftCap.toFloat(),
                                    onValueChange = { newVal ->
                                        val rounded = (newVal / 8f).roundToInt() * 8
                                        vm.updateSettings { current ->
                                            current.copy(maxToolsSoftCap = rounded.coerceIn(16, 264))
                                        }
                                    },
                                    valueRange = 16f..264f,
                                    steps = 30,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )

                    item(
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Tools,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_harness_tool_truncation_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_harness_tool_truncation_desc)) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.CheckmarkCircle01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }
            }

            // Section 4: Context Compaction & SLM Aux Worker
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_harness_section_compaction)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_harness_extractive_fallback)) },
                        supportingContent = { Text(stringResource(R.string.setting_harness_extractive_fallback_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.enableExtractiveCompactionFallback,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { it.copy(enableExtractiveCompactionFallback = checked) }
                                },
                            )
                        },
                    )

                    item(
                        headlineContent = { Text(stringResource(R.string.setting_harness_local_slm_worker)) },
                        supportingContent = { Text(stringResource(R.string.setting_harness_local_slm_worker_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.enableLocalSlmAuxWorker,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { it.copy(enableLocalSlmAuxWorker = checked) }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
