package org.librehu.helper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.helper.Diagnostic
import org.librehu.helper.R
import org.librehu.helper.compat.Compatibility

@Composable
fun CompatScreen() {
    val context = LocalContext.current
    val checks by Diagnostic.compat.collectAsStateWithLifecycle()
    val state by Diagnostic.state.collectAsStateWithLifecycle()
    val task = stringResource(R.string.tab_compat)
    LaunchedEffect(Unit) { if (Diagnostic.compat.value == null && !Diagnostic.running) Diagnostic.checkCompat(context) }

    Page {
        item {
            Card(stringResource(R.string.compat_title)) {
                Hint(stringResource(R.string.compat_hint))
                checks?.let { list ->
                    val worst = list.maxOfOrNull { it.level.ordinal } ?: 0
                    Text(
                        stringResource(
                            when (worst) {
                                Compatibility.Level.PROBLEM.ordinal -> R.string.compat_verdict_problem
                                Compatibility.Level.WARN.ordinal -> R.string.compat_verdict_warn
                                else -> R.string.compat_verdict_ok
                            },
                        ),
                        color = levelColor(worst),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                TaskStatus(task)
                if (state !is Diagnostic.State.Running) {
                    Pill(stringResource(R.string.compat_run), icon = Icons.Default.PlayArrow) { Diagnostic.checkCompat(context) }
                }
            }
        }
        items(checks.orEmpty()) { c -> CheckCard(c) }
        item {
            Card(stringResource(R.string.trouble_title)) {
                listOf(
                    R.string.trouble_mcu, R.string.trouble_keys, R.string.trouble_sound, R.string.trouble_bt,
                    R.string.trouble_boot, R.string.trouble_sleep,
                ).forEach { Body("• " + stringResource(it)) }
            }
        }
    }
}

@Composable
private fun CheckCard(c: Compatibility.Check) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CarColors.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp).size(12.dp).clip(CircleShape).background(levelColor(c.level.ordinal)))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.title, color = CarColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Body(c.detail)
            if (c.fix.isNotEmpty()) Text("→ " + c.fix, color = CarColors.Accent, fontSize = 15.sp)
        }
    }
}
