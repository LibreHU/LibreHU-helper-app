package org.librehu.helper.device

/**
 * MediaTek scatter file (SP Flash Tool `MTxxxx_Android_scatter.txt`, also used by Autochips firmwares): the partition
 * layout with each partition's region (EMMC_BOOT1, EMMC_USER…), start address and size. Both layouts are read: the
 * YAML-like v1.x / v2.x (`- partition_index:` blocks) and the old one (`NAME 0xADDRESS` lines).
 */
object Scatter {
    data class Entry(
        val name: String,
        val region: String,
        /** Address inside its region (physical_start_addr, else linear_start_addr). */
        val start: Long,
        /** 0 when the file gives none (often the last partition). */
        val size: Long,
        val download: Boolean,
    )

    data class Info(
        val platform: String,
        val storage: String,
        val entries: List<Entry>,
    )

    fun parse(text: String): Info {
        val lines = text.lines().map { it.trim() }
        return if (lines.any { it.startsWith("- partition_index") || it.startsWith("partition_name:") }) yaml(lines) else old(lines)
    }

    private fun yaml(lines: List<String>): Info {
        var platform = ""
        var storage = ""
        val entries = ArrayList<Entry>()
        var cur = HashMap<String, String>()

        fun flush() {
            val name = cur["partition_name"]
            if (name != null) {
                val start = hex(cur["physical_start_addr"]) ?: hex(cur["linear_start_addr"]) ?: 0L
                entries +=
                    Entry(
                        name = name,
                        region = cur["region"] ?: "EMMC_USER",
                        start = start,
                        size = hex(cur["partition_size"]) ?: 0L,
                        download = cur["is_download"]?.equals("true", ignoreCase = true) ?: true,
                    )
            }
            cur = HashMap()
        }

        for (l in lines) {
            if (l.startsWith("- partition_index")) flush()
            val key = l.removePrefix("- ").substringBefore(':', "").trim()
            val value = l.substringAfter(':', "").trim()
            if (key.isEmpty()) continue
            when (key) {
                "platform" -> if (platform.isEmpty()) platform = value
                "storage" -> if (storage.isEmpty()) storage = value
                else -> cur[key] = value
            }
        }
        flush()
        return Info(platform, storage, entries)
    }

    /** Old layout: `PRELOADER 0x0` then `{ }` blocks; sizes from the next start. */
    private fun old(lines: List<String>): Info {
        val starts =
            lines.mapNotNull { l ->
                val m = Regex("^([A-Za-z0-9_]+)\\s+(0x[0-9A-Fa-f]+)$").find(l) ?: return@mapNotNull null
                m.groupValues[1] to (hex(m.groupValues[2]) ?: return@mapNotNull null)
            }
        val entries =
            starts.mapIndexed { i, (name, start) ->
                val next = starts.getOrNull(i + 1)?.second
                Entry(name.lowercase(), "EMMC_USER", start, if (next != null && next > start) next - start else 0L, true)
            }
        return Info("", "", entries)
    }

    private fun hex(s: String?): Long? {
        val v = s?.trim()?.lowercase() ?: return null
        return if (v.startsWith("0x")) v.drop(2).toLongOrNull(16) else v.toLongOrNull()
    }
}
