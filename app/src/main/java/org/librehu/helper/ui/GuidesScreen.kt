package org.librehu.helper.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.helper.Diagnostic
import org.librehu.helper.R
import org.librehu.helper.device.VendorApps

/**
 * Guides: making LibreHU the head unit's service by disabling the maker's apps, without root (adb from a computer)
 * or with root, with the commands for the apps found on this unit; undoing it; LibreHU Launcher as home screen.
 * Commands from developer.android.com/tools/adb (pm disable-user, enable, list packages -d). Nothing is run by the
 * app: it stays read only.
 */
@Composable
fun GuidesScreen() {
    val context = LocalContext.current
    val apps by Diagnostic.vendorApps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (Diagnostic.vendorApps.value == null && !Diagnostic.running) Diagnostic.findVendorApps(context) }
    val replaceable = apps.orEmpty().filter { it.role != VendorApps.Role.OTHER && it.score >= 5 }.sortedBy { it.role.ordinal }

    Page {
        item {
            Card(stringResource(R.string.g_why_title)) {
                Body(stringResource(R.string.g_why))
                Hint(stringResource(R.string.g_readonly))
            }
        }
        item {
            Card(stringResource(R.string.g_order_title)) {
                listOf(R.string.g_order_1, R.string.g_order_2, R.string.g_order_3, R.string.g_order_4).forEach { Body("• " + stringResource(it)) }
            }
        }
        item {
            Card(stringResource(R.string.g_noroot_title)) {
                Body(stringResource(R.string.g_noroot))
                Mono("adb shell pm list packages -s")
                Mono("adb shell pm disable-user --user 0 <package>")
                Mono("adb shell pm list packages -d")
                Hint(stringResource(R.string.g_noroot_hint))
            }
        }
        item {
            Card(stringResource(R.string.g_root_title)) {
                Body(stringResource(R.string.g_root))
                Mono("su -c pm disable <package>")
                Mono("su -c pm list packages -d")
            }
        }
        item {
            Card(stringResource(R.string.g_undo_title)) {
                Body(stringResource(R.string.g_undo))
                Mono("adb shell pm enable <package>")
                Mono("su -c pm enable <package>")
                Hint(stringResource(R.string.g_undo_hint))
            }
        }
        item {
            Card(stringResource(R.string.g_found_title)) {
                if (apps == null) Hint(stringResource(R.string.running))
                if (apps != null && replaceable.isEmpty()) Hint(stringResource(R.string.g_found_none))
            }
        }
        items(replaceable, key = { it.pkg }) { a ->
            Card(a.label) {
                Hint("${a.pkg} · ${a.role.label}" + (a.role.librehu?.let { " → $it" } ?: "") + if (a.enabled) "" else " · " + stringResource(R.string.apks_disabled))
                Mono("adb shell pm disable-user --user 0 ${a.pkg}")
                Mono("su -c pm disable ${a.pkg}")
                Mono("adb shell pm enable ${a.pkg}")
            }
        }
        item {
            Card(stringResource(R.string.g_launcher_title)) {
                Body(stringResource(R.string.g_launcher))
            }
        }
        item { Hint(stringResource(R.string.g_source)) }
    }
}
