package org.librehu.helper.report

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import org.librehu.helper.root.Shell

/**
 * Anonymisation of the text files of a report: this unit's serial numbers, IMEI, Wi-Fi / Bluetooth addresses and
 * Android ID (read from its properties), any MAC address, and GPS positions (NMEA sentences, Android `Location[...]`,
 * `lat=` / `lon=`). Binary dumps (partition images, APKs) are not changed.
 */
class Redactor(
    secrets: Collection<String>,
) {
    private val secrets =
        secrets
            .map { it.trim() }
            .filter { it.length >= MIN_SECRET && it.lowercase() !in TRIVIAL }
            .distinct()
            .sortedByDescending { it.length }

    fun apply(line: String): String {
        var t = line
        for (s in secrets) if (t.contains(s)) t = t.replace(s, MASK)
        t =
            MAC.replace(t) { m ->
                val v = m.value.lowercase()
                if (v.all { it == '0' || it == ':' || it == '-' } || v == "ff:ff:ff:ff:ff:ff") m.value else "xx:xx:xx:xx:xx:xx"
            }
        t = NMEA.replace(t) { m -> "${m.groupValues[1]},$MASK" }
        t = LOCATION.replace(t) { m -> "Location[${m.groupValues[1]} $MASK" }
        t = LAT_LON.replace(t) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MASK" }
        return t
    }

    companion object {
        const val MASK = "<redacted>"
        private const val MIN_SECRET = 6
        private val TRIVIAL = setOf("unknown", "000000", "0123456789abcdef", "123456789abcdef")
        private val MAC = Regex("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])")
        private val NMEA = Regex("(\\\$G[PNLAB](?:GGA|RMC|GLL|GNS)),\\S*")
        private val LOCATION = Regex("Location\\[(\\w+) -?\\d+\\.\\d+,-?\\d+\\.\\d+")
        private val LAT_LON = Regex("(?i)\\b(lat|latitude|lon|lng|longitude)(\\s*[=:]\\s*)-?\\d+\\.\\d+")

        /** Properties holding values of this unit only. */
        private val SECRET_PROPS = Regex("(?i)(serial|serialno|imei|meid|iccid|imsi|wifimac|wlan.?mac|btmac|bt.?addr|bdaddr|macaddr|\\.sn$|_sn$|uuid)")
        private val PROP = Regex("^\\[(.+?)]: \\[(.*)]$")

        @SuppressLint("HardwareIds")
        fun of(context: Context): Redactor {
            val values = ArrayList<String>()
            Shell.text("getprop").lineSequence().forEach { line ->
                val m = PROP.find(line.trim()) ?: return@forEach
                if (SECRET_PROPS.containsMatchIn(m.groupValues[1])) values += m.groupValues[2]
            }
            runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()?.let { values += it }
            runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()?.let { values += it }
            runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull()?.let { values += it }
            return Redactor(values)
        }
    }
}
