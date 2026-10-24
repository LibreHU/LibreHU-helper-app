package org.librehu.helper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
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

@Composable
fun ApksScreen() {
    val context = LocalContext.current
    val apps by Diagnostic.vendorApps.collectAsStateWithLifecycle()
    val state by Diagnostic.state.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<Set<String>?>(null) }
    val running = state is Diagnostic.State.Running
    val task = stringResource(R.string.tab_apks)
    LaunchedEffect(Unit) { if (Diagnostic.vendorApps.value == null && !Diagnostic.running) Diagnostic.findVendorApps(context) }
    // Preselected: the apps with strong clues.
    val selected = chosen ?: apps.orEmpty().filter { it.score >= 5 }.map { it.pkg }.toSet()

    val pick = rememberFolderPicker { target -> Diagnostic.backupApks(context, target, apps.orEmpty().filter { it.pkg in selected }) }

    Page {
        item {
            Card(stringResource(R.string.apks_title)) {
                Hint(stringResource(R.string.apks_hint))
                TaskStatus(task)
                if (!running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(stringResource(R.string.apks_backup, selected.size), selected = true, icon = Icons.Default.FolderOpen, enabled = selected.isNotEmpty(), onClick = pick)
                        Pill(stringResource(R.string.apks_search), icon = Icons.Default.Search) { Diagnostic.findVendorApps(context) }
                    }
                }
            }
        }
        items(apps.orEmpty(), key = { it.pkg }) { a ->
            val hint =
                listOfNotNull(
                    a.pkg,
                    a.version,
                    a.role.label + (a.role.librehu?.let { " → $it" } ?: ""),
                    stringResource(R.string.apks_score, a.score, a.reasons.joinToString()),
                    if (a.enabled) null else stringResource(R.string.apks_disabled),
                ).joinToString("\n")
            CheckRow(a.label, hint, a.pkg in selected, enabled = !running) { on ->
                chosen = if (on) selected + a.pkg else selected - a.pkg
            }
        }
    }
}
