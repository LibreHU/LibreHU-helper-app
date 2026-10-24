package org.librehu.helper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Search
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
import org.librehu.helper.collect.HardwareScan

@Composable
fun HardwareScreen() {
    val context = LocalContext.current
    val hw by Diagnostic.hardware.collectAsStateWithLifecycle()
    val keys by Diagnostic.keys.collectAsStateWithLifecycle()
    val state by Diagnostic.state.collectAsStateWithLifecycle()
    var changeSpeed by remember { mutableStateOf(false) }
    val running = state is Diagnostic.State.Running
    val task = stringResource(R.string.tab_hardware)
    val keysTask = stringResource(R.string.hw_keys)

    Page {
        item {
            Card(stringResource(R.string.hw_title)) {
                Hint(stringResource(R.string.hw_hint))
                SwitchRow(stringResource(R.string.hw_change_speed), stringResource(R.string.hw_change_speed_hint), changeSpeed, enabled = !running) { changeSpeed = it }
                TaskStatus(task)
                if (!running) Pill(stringResource(R.string.hw_scan), selected = true, icon = Icons.Default.Search) { Diagnostic.scanHardware(context, changeSpeed) }
            }
        }
        hw?.let { r ->
            item {
                Card(stringResource(R.string.hw_protocols)) {
                    val confident = r.ports.filter { it.confident }
                    if (confident.isEmpty()) Body(stringResource(R.string.hw_protocols_none))
                    confident.forEach { p ->
                        val g = p.best ?: return@forEach
                        Body("${p.port} @ ${p.baud}: ${g.known ?: stringResource(R.string.hw_unknown)}")
                        Mono(HardwareScan.Text.guess(g))
                        p.nested.forEach { (cmd, list) ->
                            list.firstOrNull()?.let { n -> Body(stringResource(R.string.hw_nested, cmd, n.known ?: stringResource(R.string.hw_unknown))) }
                        }
                    }
                    Hint(stringResource(R.string.hw_profiles_hint))
                }
            }
            item { Card(stringResource(R.string.hw_mfi)) { Mono(HardwareScan.Text.mfi(r.mfi)) } }
            item {
                Card(stringResource(R.string.hw_uarts)) {
                    if (r.uarts.isEmpty()) Hint(stringResource(R.string.hw_uarts_none))
                    r.uarts.take(12).forEach { Mono("${it.port}  rx +${it.rxDelta}  tx +${it.txDelta}") }
                }
            }
            item { Card(stringResource(R.string.hw_ports)) { Mono(HardwareScan.Text.protocols(r.ports)) } }
            item { Card(stringResource(R.string.hw_i2c)) { Mono(HardwareScan.Text.i2c(r.i2c)) } }
        }
        item {
            Card(stringResource(R.string.hw_keys)) {
                Hint(stringResource(R.string.hw_keys_hint))
                TaskStatus(keysTask)
                if (!running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(stringResource(R.string.hw_keys_start), icon = Icons.Default.Keyboard) { Diagnostic.captureKey(context) }
                    }
                }
                keys?.let { k ->
                    Body(stringResource(R.string.hw_keys_frames))
                    if (k.frames.isEmpty()) Hint("—")
                    k.frames.take(20).forEach { Mono(it) }
                    Body(stringResource(R.string.hw_keys_events))
                    if (k.events.isEmpty()) Hint("—")
                    k.events.take(20).forEach { Mono(it) }
                }
            }
        }
    }
}
