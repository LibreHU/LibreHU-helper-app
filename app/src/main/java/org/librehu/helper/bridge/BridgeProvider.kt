package org.librehu.helper.bridge

import android.content.Context
import org.librehu.helper.Diagnostic
import org.librehu.helper.R
import org.librehu.helper.collect.Collectors
import org.librehu.helper.report.Target

/**
 * The diagnostic report for LibreHU's phone app (through LibreHU-service's bridge, [BridgeKitProvider]): its sections,
 * anonymisation, root check again, and the report written to the app's folder (Android/data/org.librehu.helper/files/diag,
 * then fetched with the phone's Files tab). Same labels and same preferences as the app's screen.
 */
class BridgeProvider : BridgeKitProvider() {
    override fun pages(context: Context): List<KitPage> {
        val prefs = context.getSharedPreferences("helper", Context.MODE_PRIVATE)
        val sections = Collectors.ALL.filter { prefs.getBoolean("section_${it.id}", it.default) }.map { it.id }.toSet()
        val anonymise = prefs.getBoolean("anonymise", true)
        val running = Diagnostic.state.value is Diagnostic.State.Running
        return listOf(
            page(context, "report", context.getString(R.string.tab_report), "report") {
                val root = Diagnostic.root.value
                info("root", s(R.string.root), if (root != null) context.getString(R.string.root_ok, root.toString()) else s(R.string.root_none))
                button("rescan", s(R.string.root_rescan)) { Diagnostic.scanRoot(context, force = true) }
                section(s(R.string.sections))
                for (c in Collectors.ALL) {
                    switch("section_${c.id}", s(c.title), c.id in sections, s(c.summary)) { v -> prefs.edit().putBoolean("section_${c.id}", v).apply() }
                }
                section(s(R.string.privacy))
                switch("anonymise", s(R.string.anonymise), anonymise, s(R.string.anonymise_hint)) { v -> prefs.edit().putBoolean("anonymise", v).apply() }
                section(s(R.string.export))
                if (!running) {
                    button("export", s(R.string.export_app_folder), hint = s(R.string.export_app_folder_hint)) {
                        Diagnostic.report(context, Target.AppFolder, sections, anonymise)
                    }
                }
            },
        )
    }
}
