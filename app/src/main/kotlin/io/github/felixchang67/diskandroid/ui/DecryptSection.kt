package io.github.felixchang67.diskandroid.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.felixchang67.diskandroid.ui.theme.StatusColors
import io.github.felixchang67.diskandroid.video.DecryptComponents
import kotlinx.coroutines.launch

/**
 * 解密组件（阶段 3）：DVD（libdvdcss）、蓝光（libaacs + KEYDB.cfg）。不随 App 提供，由你导入。
 * 只用于边读边播；不提供解密后导出，读成 ISO 仍是原始密文。
 */
@Composable
fun DecryptSection(decrypt: DecryptComponents, onMessage: (String) -> Unit) {
    val items by decrypt.items.collectAsStateWithLifecycle()
    val busy by decrypt.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<DecryptComponents.Kind?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = pending
        pending = null
        if (uri != null && kind != null) {
            scope.launch {
                val err = decrypt.import(kind, uri)
                onMessage(err?.let { "导入失败：$it" } ?: "已导入${kind.label}")
            }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("解密组件", style = MaterialTheme.typography.titleMedium)
            Text(
                "加密的商业 DVD（CSS）和蓝光（AACS）要用解密组件才能播放。组件不随 App 提供，由你自己导入；" +
                    "只用于边读边播，不提供解密后导出，读成 ISO 仍是原始密文。是否合法取决于你所在地的法律。\n" +
                    "蓝光还要你自己准备的 KEYDB.cfg：里面有这张盘的密钥（VUK）最好；没有的话要用处理密钥和主机证书向光驱认证，" +
                    "较新的光驱可能拒绝。不支持 4K UHD 蓝光（AACS 2.0）和 BD+。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DecryptComponents.Kind.entries.forEach { kind ->
                ComponentRow(kind, items[kind], busy == kind, onImport = {
                    pending = kind
                    picker.launch(arrayOf("*/*"))
                }, onRemove = {
                    decrypt.remove(kind)
                    onMessage("已删除${kind.label}")
                })
            }
        }
    }
}

@Composable
private fun ComponentRow(
    kind: DecryptComponents.Kind,
    item: DecryptComponents.Item?,
    busy: Boolean,
    onImport: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("${kind.label}：${kind.detail}", style = MaterialTheme.typography.bodyMedium)
            if (item == null) {
                Text("未导入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val date = DateFormat.getMediumDateFormat(context).format(item.modifiedMs)
                Text(
                    "✓ 已导入 · ${"%.1f".format(item.size / 1024.0)} KB · $date · SHA-256 ${item.sha256.take(12)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.ok,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            if (item != null) TextButton(onClick = onRemove) { Text("删除") }
            FilledTonalButton(onClick = onImport) { Text(if (item == null) "导入…" else "重新导入…") }
        }
    }
}
