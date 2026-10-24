package org.librehu.helper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.librehu.helper.R

private enum class Tab(
    val title: Int,
    val icon: ImageVector,
) {
    REPORT(R.string.tab_report, Icons.Default.Description),
    COMPAT(R.string.tab_compat, Icons.Default.Checklist),
    HARDWARE(R.string.tab_hardware, Icons.Default.Memory),
    APKS(R.string.tab_apks, Icons.Default.Android),
    DUMP(R.string.tab_dump, Icons.Default.Storage),
    GUIDES(R.string.tab_guides, Icons.AutoMirrored.Filled.MenuBook),
}

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(CarColors.Background)) {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.app_name), color = CarColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 10.dp))
            Tab.entries.forEach { t -> Pill(stringResource(t.title), selected = t.ordinal == tab, icon = t.icon) { tab = t.ordinal } }
        }
        Box(Modifier.weight(1f)) {
            when (Tab.entries[tab]) {
                Tab.REPORT -> ReportScreen()
                Tab.COMPAT -> CompatScreen()
                Tab.HARDWARE -> HardwareScreen()
                Tab.APKS -> ApksScreen()
                Tab.DUMP -> DumpScreen()
                Tab.GUIDES -> GuidesScreen()
            }
        }
    }
}

/** Scrolling page of a tab. */
@Composable
fun Page(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
