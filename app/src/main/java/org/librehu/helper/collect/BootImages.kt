package org.librehu.helper.collect

import android.content.Context
import org.librehu.helper.R
import org.librehu.helper.dump.Dumps
import org.librehu.helper.report.Report

/** Kernel (boot), recovery, dtbo, vbmeta… images in the report (root), for a TWRP or kernel port. Off by default. */
object BootImages : Collector {
    override val id = "images"
    override val title = R.string.c_images
    override val summary = R.string.c_images_summary
    override val default = false

    override fun collect(
        context: Context,
        r: Report,
    ) {
        Dumps.parts(null).filter { Dumps.BOOT_IMAGES.matches(it.name.lowercase()) }.forEach { p ->
            val named = p.copy(name = "images/${p.name}")
            Dumps.image(r, named)
        }
    }
}
