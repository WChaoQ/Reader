package com.reader.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("字号 ${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.fontSizeSp,
                onValueChange = viewModel::setFontSize,
                valueRange = 12f..32f,
                steps = 19,
            )
            Spacer(Modifier.height(16.dp))

            Text("行距 ${"%.1f".format(settings.lineSpacingMult)}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.lineSpacingMult,
                onValueChange = viewModel::setLineSpacing,
                valueRange = 1.0f..2.5f,
            )
            Spacer(Modifier.height(16.dp))

            Text("主题", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("day" to "日间", "night" to "夜间", "sepia" to "护眼").forEach { (id, label) ->
                    FilterChip(
                        selected = settings.theme == id,
                        onClick = { viewModel.setTheme(id) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("两端对齐", modifier = Modifier.weight(1f))
                Switch(checked = settings.justify, onCheckedChange = viewModel::setJustify)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("屏幕常亮", modifier = Modifier.weight(1f))
                Switch(checked = settings.keepScreenOn, onCheckedChange = viewModel::setKeepScreenOn)
            }
        }
    }
}
