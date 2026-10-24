package org.librehu.helper.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.helper.Diagnostic
import org.librehu.helper.R
import org.librehu.helper.report.Target

@Composable
fun Card(
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
fun Pill(
    label: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) CarColors.Accent else CarColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = (if (selected) CarColors.OnAccent else CarColors.Text).let { if (enabled) it else it.copy(alpha = 0.4f) }
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Hint(text: String) = Text(text, color = CarColors.TextDim, fontSize = 14.sp)

@Composable
fun Body(text: String) = Text(text, color = CarColors.Text, fontSize = 16.sp)

@Composable
fun Mono(text: String) = Text(text, color = CarColors.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)

@Composable
fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CarColors.Text, fontSize = 18.sp)
            if (hint != null) Hint(hint)
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
fun CheckRow(
    title: String,
    hint: String?,
    checked: Boolean,
    enabled: Boolean = true,
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
            Text(title, color = if (enabled) CarColors.Text else CarColors.TextDim, fontSize = 18.sp)
            if (hint != null) Hint(hint)
        }
    }
}

/** Colour of a check level / state. */
fun levelColor(level: Int): Color =
    when (level) {
        0 -> Color(0xFF81C995)
        1 -> CarColors.TextDim
        2 -> Color(0xFFFDD663)
        else -> Color(0xFFF28B82)
    }

/** State of the running (or last) task, shown under the tab's buttons. */
@Composable
fun TaskStatus(task: String) {
    val state by Diagnostic.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is Diagnostic.State.Running ->
            if (s.task == task) {
                Body(if (s.count > 0) stringResource(R.string.running_n, s.index, s.count) else stringResource(R.string.running))
                Mono(s.step)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = CarColors.Accent, trackColor = CarColors.SurfaceHigh)
            } else {
                Hint(stringResource(R.string.busy, s.task))
            }
        is Diagnostic.State.Done -> if (s.task == task) Text(s.message, color = CarColors.Accent, fontSize = 16.sp)
        is Diagnostic.State.Failed -> if (s.task == task) Body(stringResource(R.string.failed, s.message))
        Diagnostic.State.Idle -> {}
    }
}

/**
 * Folder picker (USB key or anywhere): calls [onTarget] with the folder, or with the app's folder on ROMs without
 * DocumentsUI.
 */
@Composable
fun rememberFolderPicker(onTarget: (Target) -> Unit): () -> Unit {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree != null) onTarget(Target.Folder(tree))
        }
    return {
        try {
            launcher.launch(null)
        } catch (_: ActivityNotFoundException) {
            onTarget(Target.AppFolder)
        }
    }
}
