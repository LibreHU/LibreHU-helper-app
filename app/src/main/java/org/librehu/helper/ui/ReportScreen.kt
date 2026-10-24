package org.librehu.helper.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.runtime.Composable
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
import org.librehu.helper.collect.Collectors
import org.librehu.helper.report.Target
import org.librehu.helper.root.Su

@Composable
fun ReportScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("helper", Context.MODE_PRIVATE) }
    val state by Diagnostic.state.collectAsStateWithLifecycle()
    val root by Diagnostic.root.collectAsStateWithLifecycle()
    val scanning by Diagnostic.rootScanning.collectAsStateWithLifecycle()
    var sections by remember {
        mutableStateOf(Collectors.ALL.filter { prefs.getBoolean("section_${it.id}", it.default) }.map { it.id }.toSet())
    }
    var anonymise by remember { mutableStateOf(prefs.getBoolean("anonymise", true)) }
    val running = state is Diagnostic.State.Running
    val task = stringResource(R.string.tab_report)

    fun export(target: Target) = Diagnostic.report(context, target, sections, anonymise)

    val pick = rememberFolderPicker(::export)

    Page {
        item { Hint(stringResource(R.string.intro)) }
        item {
            Card(stringResource(R.string.root)) {
                Body(
                    when {
                        scanning -> stringResource(R.string.root_scanning)
                        root != null -> stringResource(R.string.root_ok, root.toString())
                        else -> stringResource(R.string.root_none)
                    },
                )
                if (!scanning && Su.managers.isNotEmpty()) Hint(stringResource(R.string.root_managers, Su.managers.joinToString()))
                if (!scanning && root == null) Hint(stringResource(R.string.root_hint))
                if (!scanning) {
                    Pill(stringResource(R.string.root_rescan), icon = Icons.Default.Refresh) { Diagnostic.scanRoot(context, force = true) }
                }
            }
        }
        item { Card(stringResource(R.string.sections)) { Hint(stringResource(R.string.sections_hint)) } }
        items(Collectors.ALL, key = { it.id }) { c ->
            CheckRow(stringResource(c.title), stringResource(c.summary), c.id in sections, enabled = !running) { on ->
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
                Body(stringResource(R.string.export_hint))
                TaskStatus(task)
                if (!running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(stringResource(R.string.export_folder), selected = true, icon = Icons.Default.FolderOpen, onClick = pick)
                        Pill(stringResource(R.string.export_app_folder), icon = Icons.Default.SaveAlt) { export(Target.AppFolder) }
                    }
                    Hint(stringResource(R.string.export_app_folder_hint))
                }
            }
        }
    }
}
