@file:OptIn(ExperimentalLayoutApi::class)

package io.github.felixchang67.diskandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.felixchang67.diskandroid.MainViewModel
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.log.LogLevel
import io.github.felixchang67.diskandroid.ui.theme.StatusColors

@Composable
fun LogPane(vm: MainViewModel, modifier: Modifier = Modifier) {
    val version by vm.logVersion.collectAsStateWithLifecycle()
    var minLevel by rememberSaveable { mutableStateOf(LogLevel.VERBOSE) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    val records = remember(version, minLevel) { vm.logSnapshot().filter { it.level >= minLevel } }
    val listState = rememberLazyListState()

    LaunchedEffect(records.lastOrNull()?.seq, autoScroll) {
        if (autoScroll && records.isNotEmpty()) listState.scrollToItem(records.lastIndex)
    }

    Column(modifier) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LevelChip("全部", LogLevel.VERBOSE, minLevel) { minLevel = it }
            LevelChip("信息及以上", LogLevel.INFO, minLevel) { minLevel = it }
            LevelChip("警告及以上", LogLevel.WARN, minLevel) { minLevel = it }
            LevelChip("仅错误", LogLevel.ERROR, minLevel) { minLevel = it }
            FilterChip(selected = autoScroll, onClick = { autoScroll = !autoScroll }, label = { Text("自动滚动") })
            TextButton(onClick = { vm.clearLogView() }) { Text("清空显示") }
        }
        Text(
            "共 ${records.size} 条。完整日志一直保存在文件里，“清空显示”不会删除文件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(top = 4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(records, key = { it.seq }) { r ->
                Text(
                    LogFormat.text(r, maxDataBytes = 128),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = levelColor(r.level),
                )
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, level: LogLevel, current: LogLevel, onSelect: (LogLevel) -> Unit) {
    FilterChip(selected = current == level, onClick = { onSelect(level) }, label = { Text(label) })
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.VERBOSE, LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    LogLevel.WARN -> StatusColors.warn
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
}
