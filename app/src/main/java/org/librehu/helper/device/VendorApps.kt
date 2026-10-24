package org.librehu.helper.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process

/**
 * The head unit maker's apps, found without a fixed list: package names differ from one maker and firmware to the
 * next, so a few confirmed prefixes are completed by clues (system app that is neither AOSP nor Google, place of the
 * APK, system uid, words of its name, holding a serial port). Each app gets a score and, when its name tells, a role
 * and the LibreHU app that takes it over.
 *
 * Confirmed prefixes: `com.jancar.` (Jancar, UJC201 dump), `com.autochips.` / `com.mediatek.` (Autochips / MediaTek
 * system apps), `com.syu.` (FYT / Joying: com.syu.ms, github.com/hvdwolf/FYTuis7862BinRepo), `android.microntek.` /
 * `com.microntek.` (Microntek MTC: github.com/f1xpl/MtcdTools).
 */
object VendorApps {
    enum class Role(
        val label: String,
        /** LibreHU app taking the role over, null when none does. */
        val librehu: String?,
    ) {
        MCU_SERVICE("MCU / car service", "LibreHU-service"),
        CANBUS("CAN box", "LibreHU-service"),
        BLUETOOTH("Bluetooth phone", "LibreHU Téléphone + LibreHU-service"),
        RADIO("Radio", "LibreHU FM"),
        WHEEL_KEYS("Steering wheel keys", "LibreHU Touches (BtnRemap)"),
        LAUNCHER("Launcher", "LibreHU Launcher"),
        CAMERA("Reverse camera / DVR", "LibreHU-service (caméra de recul)"),
        AVIN("AV input", "LibreHU-service (AUX)"),
        SETTINGS("Car settings", "LibreHU-service"),
        OTHER("Other", null),
    }

    data class App(
        val pkg: String,
        val label: String,
        val apk: String,
        val splits: List<String>,
        val version: String,
        val score: Int,
        val reasons: List<String>,
        val role: Role,
        val enabled: Boolean,
        val system: Boolean,
    )

    private val CONFIRMED = listOf("com.jancar.", "com.autochips.", "com.mediatek.", "com.syu.", "android.microntek.", "com.microntek.")
    private val STOCK = listOf("com.android.", "com.google.", "androidx.", "org.librehu.")
    private val KEYWORDS =
        Regex(
            "(?i)(canbus|\\bcan\\b|\\.can|mcu|ivi|carservice|car\\.service|\\.car\\b|radio|fmradio|\\bbt|bluetooth|" +
                "avin|aux|dvr|backcar|reverse|cam|steer|wheel|swc|launcher|settings|ms\\b|\\.ms$|services?$)",
        )

    /** Role from the package and label words. */
    private fun role(
        pkg: String,
        label: String,
    ): Role {
        val t = "$pkg $label".lowercase()
        return when {
            Regex("canbus|canservice|\\.can\\b|\\bcan\\b|canbox").containsMatchIn(t) -> Role.CANBUS
            Regex("steer|wheel|swc").containsMatchIn(t) -> Role.WHEEL_KEYS
            Regex("btservice|bluetooth|\\.bt\\b|phone|dialer").containsMatchIn(t) -> Role.BLUETOOTH
            Regex("radio|\\bfm\\b|dab").containsMatchIn(t) -> Role.RADIO
            Regex("launcher|home").containsMatchIn(t) -> Role.LAUNCHER
            Regex("hd2cam|camera|backcar|reverse|dvr|avm").containsMatchIn(t) -> Role.CAMERA
            Regex("avin|\\baux\\b").containsMatchIn(t) -> Role.AVIN
            Regex("com\\.jancar\\.services$|com\\.syu\\.ms$|microntek\\.service|mcu|ivi|carservice|car\\.service").containsMatchIn(t) -> Role.MCU_SERVICE
            Regex("settings").containsMatchIn(t) -> Role.SETTINGS
            else -> Role.OTHER
        }
    }

    /**
     * The maker's apps, best score first. [serialHolders]: process names holding a serial port (root), which mark
     * the MCU service.
     */
    fun find(
        context: Context,
        serialHolders: Set<String> = emptySet(),
    ): List<App> {
        val pm = context.packageManager
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        return packages.mapNotNull { score(pm, it, serialHolders) }.sortedWith(compareByDescending<App> { it.score }.thenBy { it.pkg })
    }

    private fun score(
        pm: PackageManager,
        p: PackageInfo,
        holders: Set<String>,
    ): App? {
        val ai = p.applicationInfo ?: return null
        val pkg = p.packageName
        if (pkg == "android" || STOCK.any { pkg.startsWith(it) }) return null
        val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
        val reasons = ArrayList<String>()
        var score = 0
        if (CONFIRMED.any { pkg.startsWith(it) }) {
            score += 5
            reasons += "known maker prefix"
        }
        if (system) {
            score += 2
            reasons += "system app"
        }
        val apk = ai.sourceDir.orEmpty()
        if (Regex("^/(vendor|oem|odm|product|system_ext|jancar|cust)/").containsMatchIn(apk) || apk.contains("/priv-app/")) {
            score += 1
            reasons += "in ${apk.substringBeforeLast('/')}"
        }
        if (ai.uid == Process.SYSTEM_UID) {
            score += 2
            reasons += "system uid"
        }
        if (KEYWORDS.containsMatchIn(pkg) || KEYWORDS.containsMatchIn(label)) {
            score += 1
            reasons += "name"
        }
        if (holders.any { it == pkg || pkg.startsWith(it) || it.startsWith(pkg.take(15)) }) {
            score += 4
            reasons += "holds a serial port"
        }
        // Only the maker's system apps, or apps with strong clues: not the user's own apps.
        if (!system && score < 5) return null
        if (score < 3) return null
        return App(
            pkg = pkg,
            label = label,
            apk = apk,
            splits = ai.splitSourceDirs?.toList().orEmpty(),
            version = "${p.versionName} (${p.longVersionCode})",
            score = score,
            reasons = reasons,
            role = role(pkg, label),
            enabled = ai.enabled,
            system = system,
        )
    }
}
