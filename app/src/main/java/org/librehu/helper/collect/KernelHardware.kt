package org.librehu.helper.collect

import android.content.Context
import org.librehu.helper.R
import org.librehu.helper.report.Report

/**
 * Kernel and hardware, read from /proc, /sys and Android's services (nothing is probed: the I²C / serial scans are
 * in the Hardware tab): kernel config, modules, device tree (text and binary, for dtc), input devices and key
 * layouts, I²C / USB / PCI devices known to the kernel, serial ports and their traffic counters, sound, video,
 * GPIO, CPU, display, audio / input / display / USB / Bluetooth services.
 */
object KernelHardware : Collector {
    override val id = "kernel"
    override val title = R.string.c_kernel
    override val summary = R.string.c_kernel_summary

    override fun collect(
        context: Context,
        r: Report,
    ) {
        r.command("kernel/config.txt", "zcat /proc/config.gz 2>/dev/null || gzip -dc /proc/config.gz 2>/dev/null || echo 'no /proc/config.gz'", root = true)
        r.command("kernel/modules.txt", "cat /proc/modules 2>/dev/null; echo; ls -l /vendor/lib/modules /system/lib/modules /lib/modules 2>/dev/null", root = true)
        r.binary("kernel/device-tree.dtb", "cat /sys/firmware/fdt", root = true, timeoutS = 60)
        r.command(
            "kernel/device-tree.txt",
            "cd /proc/device-tree 2>/dev/null && find . -type f | sort | while read f; do " +
                "printf '%s: ' \"\$f\"; head -c 256 \"\$f\" | tr '\\000' ' ' | tr -cd '[:print:] '; echo; done",
            root = true,
            timeoutS = 180,
        )
        r.command("hardware/input-devices.txt", "cat /proc/bus/input/devices; echo; getevent -lp 2>/dev/null", root = true)
        r.command(
            "hardware/keylayouts.txt",
            "for d in /vendor/usr/keylayout /system/usr/keylayout /odm/usr/keylayout /oem/usr/keylayout; do [ -d \$d ] && ls -l \$d; done; " +
                "for f in /vendor/usr/keylayout/*.kl /odm/usr/keylayout/*.kl /oem/usr/keylayout/*.kl; do [ -f \$f ] && { echo \"== \$f\"; cat \$f; }; done",
            root = true,
        )
        r.command(
            "hardware/i2c.txt",
            "ls -l /dev/i2c-* 2>/dev/null; echo; for d in /sys/bus/i2c/devices/*; do " +
                "echo \"\$(basename \$d) name=\$(cat \$d/name 2>/dev/null) driver=\$(basename \$(readlink \$d/driver) 2>/dev/null)\"; done",
            root = true,
        )
        r.command(
            "hardware/usb.txt",
            "lsusb 2>/dev/null; for d in /sys/bus/usb/devices/*; do [ -f \$d/idVendor ] && echo \"\$(basename \$d) " +
                "\$(cat \$d/idVendor):\$(cat \$d/idProduct) \$(cat \$d/manufacturer 2>/dev/null) \$(cat \$d/product 2>/dev/null) " +
                "class=\$(cat \$d/bDeviceClass 2>/dev/null) speed=\$(cat \$d/speed 2>/dev/null)\"; done; echo; dumpsys usb 2>/dev/null | head -n 200",
            root = true,
        )
        r.command(
            "hardware/pci.txt",
            "lspci 2>/dev/null; for d in /sys/bus/pci/devices/*; do [ -f \$d/vendor ] && echo \"\$(basename \$d) " +
                "\$(cat \$d/vendor):\$(cat \$d/device) class=\$(cat \$d/class) driver=\$(basename \$(readlink \$d/driver) 2>/dev/null)\"; done; " +
                "[ -d /sys/bus/pci ] || echo 'no PCI bus'",
            root = true,
        )
        r.command(
            "hardware/serial.txt",
            "ls -l /dev/tty* 2>/dev/null | grep -vE 'tty[0-9]+\$'; echo; cat /proc/tty/drivers; echo; " +
                "for f in /proc/tty/driver/*; do echo \"== \$f\"; cat \$f; done",
            root = true,
        )
        r.command(
            "hardware/sound.txt",
            "cat /proc/asound/cards /proc/asound/pcm 2>/dev/null; ls -l /dev/snd 2>/dev/null; echo; " +
                "for c in 0 1 2 3; do [ -d /proc/asound/card\$c ] && (tinymix -D \$c 2>/dev/null || true); done",
            root = true,
        )
        r.command(
            "hardware/video.txt",
            "ls -l /dev/video* /dev/media* 2>/dev/null; for d in /sys/class/video4linux/*; do echo \"\$(basename \$d): \$(cat \$d/name 2>/dev/null)\"; done",
            root = true,
        )
        r.command(
            "hardware/gpio.txt",
            "cat /sys/kernel/debug/gpio 2>/dev/null || echo 'no debugfs gpio'; ls /sys/class/gpio 2>/dev/null; " +
                "for c in /sys/class/gpio/gpiochip*; do echo \"\$(basename \$c) base=\$(cat \$c/base) ngpio=\$(cat \$c/ngpio) label=\$(cat \$c/label)\"; done",
            root = true,
        )
        r.command(
            "hardware/cpu.txt",
            "for c in /sys/devices/system/cpu/cpu[0-9]*; do echo \"\$(basename \$c) \$(cat \$c/cpufreq/scaling_governor 2>/dev/null) " +
                "min=\$(cat \$c/cpufreq/cpuinfo_min_freq 2>/dev/null) max=\$(cat \$c/cpufreq/cpuinfo_max_freq 2>/dev/null) " +
                "avail=\$(cat \$c/cpufreq/scaling_available_frequencies 2>/dev/null)\"; done; echo; " +
                "for t in /sys/class/thermal/thermal_zone*; do echo \"\$(basename \$t) \$(cat \$t/type) \$(cat \$t/temp)\"; done",
        )
        r.command(
            "hardware/display.txt",
            "cat /sys/class/graphics/fb0/modes /sys/class/graphics/fb0/virtual_size 2>/dev/null; wm size; wm density; echo; " +
                "dumpsys display 2>/dev/null | head -n 120",
            root = true,
        )
        r.command("services/audio.txt", "dumpsys audio 2>/dev/null; dumpsys media.audio_policy 2>/dev/null", root = true, timeoutS = 60)
        r.command("services/audio-hal.txt", "dumpsys media.audio_flinger 2>/dev/null", root = true, timeoutS = 60)
        r.command("services/input.txt", "dumpsys input 2>/dev/null", root = true, timeoutS = 60)
        r.command("services/bluetooth.txt", "dumpsys bluetooth_manager 2>/dev/null | head -n 400", root = true, timeoutS = 60)
        r.command("services/power.txt", "dumpsys power 2>/dev/null | head -n 200; dumpsys battery 2>/dev/null", root = true)
    }
}
