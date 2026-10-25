package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.ScanText
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ModelListState
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

private enum class ModelSettingTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    MODEL(HugeIcons.AiBrain01, R.string.setting_model_page_tab_model),
    PROMPT(HugeIcons.AiEditing, R.string.setting_model_page_tab_prompt);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { ModelSettingTab.entries.size }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = ModelSettingTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            // 导航条的位置留在列表的内边距里，内容可以滚到它下面
            val contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (ModelSettingTab.entries[page]) {
                    ModelSettingTab.MODEL -> ModelSettingsPage(
                        settings = settings,
                        vm = vm,
                        contentPadding = contentPadding,
                    )

                    ModelSettingTab.PROMPT -> PromptSettingsPage(
                        settings = settings,
                        vm = vm,
                        contentPadding = contentPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    // CardGroup 里放不了弹窗，各项的选择状态先在这里建好，弹窗统一放在列表后面
    val chatModel = rememberModelListState(
        modelId = settings.chatModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val fastModel = rememberModelListState(
        modelId = settings.fastModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val memoryModel = rememberModelListState(
        modelId = settings.memoryModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val translateModel = rememberModelListState(
        modelId = settings.translateModeId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val ocrModel = rememberModelListState(
        modelId = settings.ocrModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val compressModel = rememberModelListState(
        modelId = settings.compressModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.BubbleChat,
                    title = R.string.setting_model_page_chat_model,
                    description = R.string.setting_model_page_chat_model_desc,
                    state = chatModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.tertiaryContainer
            val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            // 标题和聊天建议都由快速模型生成，相关的选项跟它放在一组
            CardGroup {
                modelItem(
                    icon = HugeIcons.Zap,
                    title = R.string.setting_model_page_fast_model,
                    description = R.string.setting_model_page_fast_model_desc,
                    state = fastModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = settings.fastModelReasoningLevel,
                            onUpdateReasoningLevel = {
                                vm.updateSettings { latest -> latest.copy(fastModelReasoningLevel = it) }
                            },
                        )
                    },
                )
                switchItem(
                    checked = settings.enableAutoTitle,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableAutoTitle = it) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_enable_auto_title)) },
                )
                switchItem(
                    checked = settings.enableSuggestion,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableSuggestion = it) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                )
            }
        }
        item {
            // 没设置时用快速模型，配色和它保持一致
            val containerColor = MaterialTheme.colorScheme.tertiaryContainer
            val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            CardGroup {
                // 不设置时跟着快速模型走，所以要能清掉
                modelItem(
                    icon = HugeIcons.Brain02,
                    title = R.string.setting_model_page_memory_model,
                    description = R.string.setting_model_page_memory_model_desc,
                    state = memoryModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                    placeholder = R.string.setting_model_page_memory_model_follow_fast,
                    onClear = { vm.updateSettings { latest -> latest.copy(memoryModelId = null) } },
                )
                // 没设置时推理强度也跟着快速模型
                if (memoryModel.currentModel != null) {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                        trailingContent = {
                            ReasoningButton(
                                reasoningLevel = settings.memoryModelReasoningLevel,
                                onUpdateReasoningLevel = {
                                    vm.updateSettings { latest -> latest.copy(memoryModelReasoningLevel = it) }
                                },
                            )
                        },
                    )
                }
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.secondaryContainer
            val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.Translate,
                    title = R.string.setting_model_page_translate_model,
                    description = R.string.setting_model_page_translate_model_desc,
                    state = translateModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                modelItem(
                    icon = HugeIcons.ScanText,
                    title = R.string.setting_model_page_ocr_model,
                    description = R.string.setting_model_page_ocr_model_desc,
                    state = ocrModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                modelItem(
                    icon = HugeIcons.Package01,
                    title = R.string.setting_model_page_compress_model,
                    description = R.string.setting_model_page_compress_model_desc,
                    state = compressModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                    onClear = { vm.updateSettings { latest -> latest.copy(compressModelId = DEFAULT_AUTO_MODEL_ID) } },
                )
            }
        }
        item {
            AutoCompactionSettingItem(settings = settings, vm = vm)
        }
        item {
            ResponseStreamRetrySettingItem(settings = settings, vm = vm)
        }
    }

    ModelListSheet(state = chatModel) {
        vm.updateSettings { latest -> latest.copy(chatModelId = it.id) }
    }
    ModelListSheet(state = fastModel) {
        vm.updateSettings { latest -> latest.copy(fastModelId = it.id) }
    }
    ModelListSheet(state = memoryModel) {
        vm.updateSettings { latest -> latest.copy(memoryModelId = it.id) }
    }
    ModelListSheet(state = translateModel) {
        vm.updateSettings { latest -> latest.copy(translateModeId = it.id) }
    }
    ModelListSheet(state = ocrModel) {
        vm.updateSettings { latest -> latest.copy(ocrModelId = it.id) }
    }
    ModelListSheet(state = compressModel) {
        vm.updateSettings { latest -> latest.copy(compressModelId = it.id) }
    }
}

@Composable
private fun ResponseStreamRetrySettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    var maxRetries by remember(settings.responseStreamMaxRetries) {
        mutableStateOf(settings.responseStreamMaxRetries.toString())
    }

    CardGroup {
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_model_page_response_stream_retries))
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setting_model_page_response_stream_retries_desc))
                    OutlinedTextField(
                        value = maxRetries,
                        onValueChange = { value ->
                            maxRetries = value.filter(Char::isDigit).take(2)
                            maxRetries.toIntOrNull()?.let { parsed ->
                                val normalized = parsed.coerceIn(0, 10)
                                if (normalized != settings.responseStreamMaxRetries) {
                                    vm.updateSettings { current ->
                                        current.copy(responseStreamMaxRetries = normalized)
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        label = {
                            Text(stringResource(R.string.setting_model_page_response_stream_retries_input))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val normalized = maxRetries.toIntOrNull()
                                        ?.coerceIn(0, 10)
                                        ?: settings.responseStreamMaxRetries
                                    maxRetries = normalized.toString()
                                    vm.updateSettings { current ->
                                        if (current.responseStreamMaxRetries == normalized) {
                                            current
                                        } else {
                                            current.copy(responseStreamMaxRetries = normalized)
                                        }
                                    }
                                }
                            },
                    )
                }
            },
        )
    }
}

@Composable
private fun AutoCompactionSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    var threshold by remember(settings.autoCompactionThresholdPercent) {
        mutableFloatStateOf(settings.autoCompactionThresholdPercent.toFloat())
    }
    var tokenThreshold by remember(settings.autoCompactionThresholdTokensK) {
        mutableStateOf(settings.autoCompactionThresholdTokensK.toString())
    }
    var compactionTargetTokensK by remember(settings.contextCompactionTargetTokensK) {
        mutableStateOf(settings.contextCompactionTargetTokensK?.toString().orEmpty())
    }
    var keepRecentToolCalls by remember(settings.autoCompactionKeepRecentToolCalls) {
        mutableStateOf(settings.autoCompactionKeepRecentToolCalls.toString())
    }

    CardGroup {
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_model_page_enable_auto_compaction))
            },
            supportingContent = {
                Text(stringResource(R.string.setting_model_page_enable_auto_compaction_desc))
            },
            trailingContent = {
                Switch(
                    checked = settings.enableAutoCompaction,
                    onCheckedChange = {
                        vm.updateSettings { current -> current.copy(enableAutoCompaction = it) }
                    },
                )
            },
        )
        if (settings.enableAutoCompaction) {
            item(
                headlineContent = {
                    Text(stringResource(R.string.setting_model_page_auto_compaction_threshold))
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.setting_model_page_auto_compaction_threshold_desc))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val modes = listOf(
                                AutoCompactionThresholdMode.PERCENT to
                                    stringResource(R.string.setting_model_page_auto_compaction_mode_percent),
                                AutoCompactionThresholdMode.TOKENS to
                                    stringResource(R.string.setting_model_page_auto_compaction_mode_tokens),
                            )
                            modes.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    selected = settings.autoCompactionThresholdMode == mode,
                                    onClick = {
                                        if (settings.autoCompactionThresholdMode != mode) {
                                            vm.updateSettings { current ->
                                                current.copy(autoCompactionThresholdMode = mode)
                                            }
                                        }
                                    },
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        if (settings.autoCompactionThresholdMode == AutoCompactionThresholdMode.PERCENT) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Slider(
                                    value = threshold,
                                    onValueChange = { value ->
                                        threshold = (value / 5f).toInt().times(5).toFloat()
                                    },
                                    onValueChangeFinished = {
                                        vm.updateSettings { current ->
                                            current.copy(
                                                autoCompactionThresholdPercent = threshold.toInt()
                                            )
                                        }
                                    },
                                    valueRange = 5f..95f,
                                    steps = 17,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("${threshold.toInt()}%")
                            }
                        } else {
                            OutlinedTextField(
                                value = tokenThreshold,
                                onValueChange = { value ->
                                    tokenThreshold = value.filter(Char::isDigit).take(7)
                                    tokenThreshold.toIntOrNull()?.let { parsed ->
                                        val normalized = parsed.coerceIn(1, Int.MAX_VALUE / 1_000)
                                        if (normalized != settings.autoCompactionThresholdTokensK) {
                                            vm.updateSettings { current ->
                                                current.copy(autoCompactionThresholdTokensK = normalized)
                                            }
                                        }
                                    }
                                },
                                singleLine = true,
                                suffix = { Text("k") },
                                label = {
                                    Text(stringResource(R.string.setting_model_page_auto_compaction_tokens))
                                },
                                supportingText = {
                                    Text(stringResource(R.string.setting_model_page_auto_compaction_tokens_desc))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused) {
                                            val parsed = tokenThreshold.toIntOrNull()
                                            if (parsed == null) {
                                                tokenThreshold = settings.autoCompactionThresholdTokensK.toString()
                                            } else {
                                                val normalized = parsed.coerceIn(1, Int.MAX_VALUE / 1_000)
                                                tokenThreshold = normalized.toString()
                                                if (normalized != settings.autoCompactionThresholdTokensK) {
                                                    vm.updateSettings { current ->
                                                        current.copy(autoCompactionThresholdTokensK = normalized)
                                                    }
                                                }
                                            }
                                        }
                                    },
                            )
                        }
                    }
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.setting_model_page_auto_compaction_keep_tool_calls))
                },
                supportingContent = {
                    OutlinedTextField(
                        value = keepRecentToolCalls,
                        onValueChange = { value ->
                            keepRecentToolCalls = value.filter(Char::isDigit).take(4)
                            keepRecentToolCalls.toIntOrNull()?.let { parsed ->
                                val normalized = parsed.coerceIn(0, 1_000)
                                if (normalized != settings.autoCompactionKeepRecentToolCalls) {
                                    vm.updateSettings { current ->
                                        current.copy(autoCompactionKeepRecentToolCalls = normalized)
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.setting_model_page_auto_compaction_keep_tool_calls_desc
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val normalized = keepRecentToolCalls.toIntOrNull()
                                        ?.coerceIn(0, 1_000)
                                        ?: settings.autoCompactionKeepRecentToolCalls
                                    keepRecentToolCalls = normalized.toString()
                                    if (normalized != settings.autoCompactionKeepRecentToolCalls) {
                                        vm.updateSettings { current ->
                                            current.copy(autoCompactionKeepRecentToolCalls = normalized)
                                        }
                                    }
                                }
                            },
                    )
                },
            )
        }
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_model_page_context_compaction_target))
            },
            supportingContent = {
                OutlinedTextField(
                    value = compactionTargetTokensK,
                    onValueChange = { value ->
                        compactionTargetTokensK = value.filter(Char::isDigit).take(7)
                        val normalized = compactionTargetTokensK.toIntOrNull()
                            ?.coerceIn(1, Int.MAX_VALUE / 1_000)
                        if (normalized != settings.contextCompactionTargetTokensK) {
                            vm.updateSettings { current ->
                                current.copy(contextCompactionTargetTokensK = normalized)
                            }
                        }
                    },
                    singleLine = true,
                    suffix = { Text("k") },
                    supportingText = {
                        Text(stringResource(R.string.setting_model_page_context_compaction_target_desc))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                val normalized = compactionTargetTokensK.toIntOrNull()
                                    ?.coerceIn(1, Int.MAX_VALUE / 1_000)
                                val normalizedValue = normalized?.toString().orEmpty()
                                compactionTargetTokensK = normalizedValue
                                if (normalized != settings.contextCompactionTargetTokensK) {
                                    vm.updateSettings { current ->
                                        current.copy(contextCompactionTargetTokensK = normalized)
                                    }
                                }
                            }
                        },
                )
            },
        )
    }
}

// 一项默认模型：图标、用途说明，下面是显示当前模型的选择按钮
private fun CardGroupScope.modelItem(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    state: ModelListState,
    iconContainerColor: Color,
    iconContentColor: Color,
    @StringRes placeholder: Int = R.string.model_list_select_model,
    onClear: (() -> Unit)? = null,
) = formItem(
    headlineContent = { Text(stringResource(title)) },
    supportingContent = { Text(stringResource(description)) },
    leadingContent = {
        CardGroupIcon(icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
) {
    ModelField(
        model = state.currentModel,
        onClick = { state.open() },
        placeholder = stringResource(placeholder),
        onClear = onClear,
    )
}
