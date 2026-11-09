@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.felixchang67.diskandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.felixchang67.diskandroid.BuildConfig
import io.github.felixchang67.diskandroid.MainViewModel

/** 设备页上的全局操作。 */
class MainActions(
    val onCopyDiagnostics: () -> Unit,
    val onExport: () -> Unit,
    val onShare: () -> Unit,
)

@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbarHost.showSnackbar(it, withDismissAction = true) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportLogs(context, uri)
    }
    val actions = MainActions(
        onCopyDiagnostics = { vm.copyDiagnostics(context) },
        onExport = { exportLauncher.launch(vm.suggestedExportName()) },
        onShare = { vm.shareLogs(context) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DiskAndroid", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (maxWidth >= 840.dp) {
                // 平板横屏：左边设备，右边日志
                Row(Modifier.fillMaxSize()) {
                    DevicePane(vm, actions, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    LogPane(vm, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("设备") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("日志") })
                    }
                    if (tab == 0) {
                        DevicePane(vm, actions, Modifier.fillMaxSize())
                    } else {
                        LogPane(vm, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
