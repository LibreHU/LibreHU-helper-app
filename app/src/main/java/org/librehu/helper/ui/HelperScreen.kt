package org.librehu.helper.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.helper.Diagnostic
import org.librehu.helper.R
import org.librehu.helper.collect.Collector
import org.librehu.helper.collect.Collectors
import org.librehu.helper.root.Su

@Composable
fun HelperScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("helper", Context.MODE_PRIVATE) }
    val state by Diagnostic.state.collectAsStateWithLifecycle()
    val root by Diagnostic.root.collectAsStateWithLifecycle()
    val scanning by Diagnostic.rootScanning.collectAsStateWithLifecycle()
    var sections by remember {
        mutableStateOf(
            Collectors.ALL.filter { prefs.getBoolean("section_${it.id}", it.default) }.map { it.id }.toSet(),
        )
    }
    var anonymise by remember { mutableStateOf(prefs.getBoolean("anonymise", true)) }
    var noPicker by remember { mutableStateOf(false) }
    val running = state is Diagnostic.State.Running

    fun export(target: Diagnostic.Target) = Diagnostic.start(context, target, sections, anonymise)

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree != null) export(Diagnostic.Target.Folder(tree))
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CarColors.Background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.app_name), color = CarColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.intro), color = CarColors.TextDim, fontSize = 15.sp)
        }
        item {
            Card(stringResource(R.string.root)) {
                val text =
                    when {
                        scanning -> stringResource(R.string.root_scanning)
                        root != null -> stringResource(R.string.root_ok, root.toString())
                        else -> stringResource(R.string.root_none)
                    }
                Text(text, color = CarColors.Text, fontSize = 16.sp)
                if (!scanning && Su.managers.isNotEmpty()) {
                    Text(stringResource(R.string.root_managers, Su.managers.joinToString()), color = CarColors.TextDim, fontSize = 14.sp)
                }
                if (!scanning && root == null) Text(stringResource(R.string.root_hint), color = CarColors.TextDim, fontSize = 14.sp)
                if (!scanning) {
                    Pill(stringResource(R.string.root_rescan), icon = Icons.Default.Refresh) {
                        Diagnostic.scanRoot(context.applicationContext, force = true)
                    }
                }
            }
        }
        item {
            Card(stringResource(R.string.sections)) {
                Text(stringResource(R.string.sections_hint), color = CarColors.TextDim, fontSize = 14.sp)
            }
        }
        items(Collectors.ALL, key = { it.id }) { c ->
            SectionRow(c, c.id in sections, enabled = !running) { on ->
                sections = if (on) sections + c.id else sections - c.id
                prefs.edit().putBoolean("section_${c.id}", on).apply()
            }
        }
        item {
            Card(stringResource(R.string.privacy)) {
                SwitchRow(stringResource(R.string.anonymise), stringResource(R.string.anonymise_hint), anonymise, enabled = !running) { on ->
                    anonymise = on
                    prefs.edit().putBoolean("anonymise", on).apply()
                }
            }
        }
        item {
            Card(stringResource(R.string.export)) {
                Text(stringResource(R.string.export_hint), color = CarColors.Text, fontSize = 16.sp)
                if (noPicker) Text(stringResource(R.string.export_no_picker), color = CarColors.TextDim, fontSize = 14.sp)
                when (val s = state) {
                    is Diagnostic.State.Running -> {
                        Text(stringResource(R.string.export_running, s.section, s.sections), color = CarColors.Text, fontSize = 16.sp)
                        Text(s.step, color = CarColors.TextDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent, trackColor = CarColors.SurfaceHigh)
                    }
                    is Diagnostic.State.Done ->
                        Text(stringResource(R.string.export_done, s.where, s.entries, s.bytes / 1024), color = CarColors.Accent, fontSize = 16.sp)
                    is Diagnostic.State.Failed ->
                        Text(stringResource(R.string.export_failed, s.message), color = CarColors.Text, fontSize = 16.sp)
                    Diagnostic.State.Idle -> {}
                }
                if (!running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(stringResource(R.string.export_folder), selected = true, icon = Icons.Default.FolderOpen) {
                            try {
                                picker.launch(null)
                            } catch (_: ActivityNotFoundException) {
                                // Some head unit ROMs have no DocumentsUI: the app's own folder then.
                                noPicker = true
                                export(Diagnostic.Target.AppFolder)
                            }
                        }
                        Pill(stringResource(R.string.export_app_folder), icon = Icons.Default.SaveAlt) {
                            export(Diagnostic.Target.AppFolder)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionRow(
    c: Collector,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CarColors.Surface)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = CarColors.Accent, checkmarkColor = CarColors.OnAccent),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(stringResource(c.title), color = CarColors.Text, fontSize = 18.sp)
            Text(stringResource(c.summary), color = CarColors.TextDim, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CarColors.Text, fontSize = 18.sp)
            if (hint != null) Text(hint, color = CarColors.TextDim, fontSize = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = CarColors.Accent, checkedThumbColor = CarColors.OnAccent),
        )
    }
}

@Composable
private fun Card(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CarColors.Surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = CarColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) CarColors.Accent else CarColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) CarColors.OnAccent else CarColors.Text
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
