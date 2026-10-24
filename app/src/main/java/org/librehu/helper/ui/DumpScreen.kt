package org.librehu.helper.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.helper.Diagnostic
import org.librehu.helper.R
import org.librehu.helper.device.Device

@Composable
fun DumpScreen() {
    val context = LocalContext.current
    val parts by Diagnostic.parts.collectAsStateWithLifecycle()
    val scatter by Diagnostic.scatter.collectAsStateWithLifecycle()
    val state by Diagnostic.state.collectAsStateWithLifecycle()
    val root by Diagnostic.root.collectAsStateWithLifecycle()
    var unchecked by remember { mutableStateOf(setOf<String>()) }
    var platform by remember { mutableStateOf<Device.Platform?>(null) }
    val running = state is Diagnostic.State.Running
    val task = stringResource(R.string.tab_dump)
    LaunchedEffect(Unit) {
        platform = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Device.platform() }
        if (Diagnostic.parts.value == null && !Diagnostic.running) Diagnostic.listParts(context)
    }
    val dumpable = parts.orEmpty().filter { it.excluded == null && it.name !in unchecked }
    val total = dumpable.sumOf { maxOf(it.size, 0L) }

    val pick = rememberFolderPicker { target -> Diagnostic.fullDump(context, target, dumpable) }
    val scatterPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) Diagnostic.loadScatter(context, uri)
        }

    Page {
        item {
            Card(stringResource(R.string.dump_title)) {
                Hint(stringResource(R.string.dump_hint))
                if (root == null) Body(stringResource(R.string.need_root))
                if (platform?.mediatek == true) {
                    Body(stringResource(R.string.dump_mtk, platform?.label ?: ""))
                    Hint(stringResource(R.string.dump_scatter_hint))
                    scatter?.let { (_, info) -> Hint(stringResource(R.string.dump_scatter_loaded, info.entries.size, info.platform.ifEmpty { "?" })) }
                }
                Body(stringResource(R.string.dump_total, dumpable.size, Formatter.formatFileSize(context, total)))
                TaskStatus(task)
                if (!running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(stringResource(R.string.dump_start), selected = true, icon = Icons.Default.FolderOpen, enabled = root != null && dumpable.isNotEmpty(), onClick = pick)
                        if (platform?.mediatek == true) {
                            Pill(stringResource(R.string.dump_scatter), icon = Icons.Default.Description) { scatterPicker.launch(arrayOf("text/plain", "*/*")) }
                        }
                        Pill(stringResource(R.string.dump_refresh), icon = Icons.Default.Refresh) { Diagnostic.listParts(context) }
                    }
                }
            }
        }
        items(parts.orEmpty(), key = { it.name + it.device + it.offset }) { p ->
            val size = if (p.size > 0) Formatter.formatFileSize(context, p.size) else "?"
            val where = if (p.offset > 0) "${p.device} @ 0x%x".format(p.offset) else p.device
            val hint = "$size · $where" + (p.excluded?.let { "\n" + stringResource(R.string.dump_excluded, it) } ?: "")
            CheckRow(p.name, hint, p.excluded == null && p.name !in unchecked, enabled = !running && p.excluded == null) { on ->
                unchecked = if (on) unchecked - p.name else unchecked + p.name
            }
        }
    }
}
