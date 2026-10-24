package org.librehu.helper.collect

import android.content.Context
import androidx.annotation.StringRes
import org.librehu.helper.report.Report

/** One section of the report (checkbox in the app). */
interface Collector {
    val id: String

    @get:StringRes
    val title: Int

    @get:StringRes
    val summary: Int

    /** Checked by default. */
    val default: Boolean get() = true

    fun collect(
        context: Context,
        r: Report,
    )
}

object Collectors {
    val ALL: List<Collector> = listOf(SystemInfo)
}
