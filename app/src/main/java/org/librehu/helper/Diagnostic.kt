package org.librehu.helper

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.librehu.helper.collect.Collector
import org.librehu.helper.collect.Collectors
import org.librehu.helper.compat.Compatibility
import org.librehu.helper.device.Device
import org.librehu.helper.device.Scatter
import org.librehu.helper.device.VendorApps
import org.librehu.helper.dump.Dumps
import org.librehu.helper.hw.Scans
import org.librehu.helper.report.Output
import org.librehu.helper.report.Redactor
import org.librehu.helper.report.Report
import org.librehu.helper.report.Target
import org.librehu.helper.root.Su

/** The app's background work, one long task at a time, with its state for the UI. */
object Diagnostic {
    sealed interface State {
        data object Idle : State

        data class Running(
            val task: String,
            val step: String,
            val index: Int,
            val count: Int,
        ) : State

        data class Done(
            val task: String,
            val message: String,
        ) : State

        data class Failed(
            val task: String,
            val message: String,
        ) : State
    }

    data class HardwareResult(
        val i2c: List<Scans.I2cBus>,
        val mfi: List<Scans.Mfi>,
        val uarts: List<Scans.Uart>,
        val ports: List<Scans.PortResult>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _root = MutableStateFlow<Su.Root?>(null)
    val root: StateFlow<Su.Root?> = _root.asStateFlow()

    private val _rootScanning = MutableStateFlow(false)
    val rootScanning: StateFlow<Boolean> = _rootScanning.asStateFlow()

    private val _compat = MutableStateFlow<List<Compatibility.Check>?>(null)
    val compat: StateFlow<List<Compatibility.Check>?> = _compat.asStateFlow()

    private val _vendorApps = MutableStateFlow<List<VendorApps.App>?>(null)
    val vendorApps: StateFlow<List<VendorApps.App>?> = _vendorApps.asStateFlow()

    private val _parts = MutableStateFlow<List<Dumps.Part>?>(null)
    val parts: StateFlow<List<Dumps.Part>?> = _parts.asStateFlow()

    private val _scatter = MutableStateFlow<Pair<String, Scatter.Info>?>(null)
    val scatter: StateFlow<Pair<String, Scatter.Info>?> = _scatter.asStateFlow()

    private val _hardware = MutableStateFlow<HardwareResult?>(null)
    val hardware: StateFlow<HardwareResult?> = _hardware.asStateFlow()

    private val _keys = MutableStateFlow<Scans.KeyCapture?>(null)
    val keys: StateFlow<Scans.KeyCapture?> = _keys.asStateFlow()

    val running get() = _state.value is State.Running

    /** Looks for root once ([force]: again, after allowing the app in a root manager). */
    fun scanRoot(
        context: Context,
        force: Boolean = false,
    ) {
        if (_rootScanning.value || (Su.scanned && !force)) return
        val app = context.applicationContext
        _rootScanning.value = true
        scope.launch {
            try {
                _root.value = Su.scan(app)
            } finally {
                _rootScanning.value = false
            }
        }
    }

    private fun ensureRoot(app: Context) {
        if (!Su.scanned) _root.value = Su.scan(app)
    }

    /** Runs [work] as the one long task, its result message (or error) left in [state]. */
    private fun task(
        context: Context,
        name: String,
        work: (Context, (String, Int, Int) -> Unit) -> String,
    ) {
        if (running) return
        val app = context.applicationContext
        _state.value = State.Running(name, app.getString(R.string.step_root), 0, 0)
        scope.launch {
            _state.value =
                try {
                    ensureRoot(app)
                    State.Done(name, work(app) { step, i, n -> _state.value = State.Running(name, step, i, n) })
                } catch (e: Exception) {
                    State.Failed(name, e.message ?: e.javaClass.simpleName)
                }
        }
    }

    // --- Report --------------------------------------------------------------------------------------------------

    fun report(
        context: Context,
        target: Target,
        sections: Set<String>,
        anonymise: Boolean,
    ) = task(context, context.getString(R.string.tab_report)) { app, step ->
        val chosen = Collectors.ALL.filter { it.id in sections }
        val (out, where) = Output.of(app, target).create(Output.stamp("diag") + ".zip")
        val redactor = if (anonymise) Redactor.of(app) else null
        var section = 0
        val report = Report(out, redactor) { s -> step(s, section, chosen.size) }
        report.use { r ->
            r.text("README.txt", readme(app, chosen, anonymise))
            r.text("root.txt", Su.report())
            for (c in chosen) {
                section++
                step(app.getString(c.title), section, chosen.size)
                try {
                    c.collect(app, r)
                } catch (e: Exception) {
                    r.skipped(c.id, "error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        app.getString(R.string.export_done, where, report.count, report.bytes / 1024)
    }

    private fun readme(
        app: Context,
        sections: List<Collector>,
        anonymise: Boolean,
    ): String =
        buildString {
            val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
            appendLine("LibreHU Helper $version")
            appendLine("https://github.com/LibreHU/LibreHU-helper-app")
            appendLine()
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android sdk ${Build.VERSION.SDK_INT}")
            appendLine("root: ${Su.get() ?: "none"}")
            appendLine("anonymised: ${if (anonymise) "yes (serials, IMEI, MAC addresses, Android ID, GPS positions)" else "NO"}")
            appendLine("sections: ${sections.joinToString { app.getString(it.title) }}")
            appendLine()
            appendLine("manifest.json lists every file with its size and SHA-256, and what was left out and why.")
        }

    // --- Compatibility ------------------------------------------------------------------------------------------

    fun checkCompat(context: Context) =
        task(context, context.getString(R.string.tab_compat)) { app, _ ->
            _compat.value = Compatibility.run(app)
            _vendorApps.value = VendorApps.find(app, holders())
            app.getString(R.string.compat_done)
        }

    private fun holders(): Set<String> = Device.serialPorts().flatMap { p -> p.holders.map { it.substringAfter(' ') } }.toSet()

    // --- Maker's APKs --------------------------------------------------------------------------------------------

    fun findVendorApps(context: Context) =
        task(context, context.getString(R.string.tab_apks)) { app, _ ->
            val list = VendorApps.find(app, holders())
            _vendorApps.value = list
            app.getString(R.string.apks_found, list.size)
        }

    fun backupApks(
        context: Context,
        target: Target,
        apps: List<VendorApps.App>,
    ) = task(context, context.getString(R.string.tab_apks)) { app, step ->
        app.getString(R.string.done_in, Dumps.apks(app, Output.of(app, target), apps, step))
    }

    // --- Full dump ------------------------------------------------------------------------------------------------

    fun loadScatter(
        context: Context,
        uri: Uri,
    ) = task(context, context.getString(R.string.tab_dump)) { app, _ ->
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw IllegalStateException("scatter")
        val info = Scatter.parse(text)
        _scatter.value = text to info
        _parts.value = Dumps.parts(info)
        app.getString(R.string.dump_scatter_loaded, info.entries.size, info.platform.ifEmpty { "?" })
    }

    fun listParts(context: Context) =
        task(context, context.getString(R.string.tab_dump)) { app, _ ->
            val list = Dumps.parts(_scatter.value?.second)
            _parts.value = list
            app.getString(R.string.dump_parts_found, list.size)
        }

    fun fullDump(
        context: Context,
        target: Target,
        chosen: List<Dumps.Part>,
    ) = task(context, context.getString(R.string.tab_dump)) { app, step ->
        if (Su.get() == null) throw IllegalStateException(app.getString(R.string.need_root))
        app.getString(R.string.done_in, Dumps.fullDump(app, Output.of(app, target), chosen, _scatter.value?.first, step))
    }

    // --- Hardware -------------------------------------------------------------------------------------------------

    fun scanHardware(
        context: Context,
        changeSpeedOfUsedPorts: Boolean,
    ) = task(context, context.getString(R.string.tab_hardware)) { app, step ->
        step("I²C", 1, 4)
        val i2c = Scans.i2cScan(app)
        step("MFi", 2, 4)
        val mfi = Scans.mfi(app)
        step("UART", 3, 4)
        val uarts = Scans.uartActivity()
        val ports = Scans.findProtocols(app, changeSpeedOfUsedPorts) { step(it, 4, 4) }
        _hardware.value = HardwareResult(i2c, mfi, uarts, ports)
        app.getString(R.string.hw_done, ports.count { it.confident })
    }

    /** Key capture on the best port found by [scanHardware] (or kernel events only). */
    fun captureKey(context: Context) =
        task(context, context.getString(R.string.hw_keys)) { app, step ->
            val best = _hardware.value?.ports?.firstOrNull { it.confident }
            val nested = best?.nested?.entries?.firstOrNull()
            step(app.getString(R.string.hw_keys_idle), 1, 2)
            val result =
                Scans.keyCapture(
                    app,
                    best?.port,
                    best?.baud ?: 0,
                    best?.best?.spec,
                    nested?.key,
                    nested?.value?.firstOrNull()?.spec,
                    idleMs = 3_000,
                    pressMs = 5_000,
                ) { step(app.getString(R.string.hw_keys_press), 2, 2) }
            _keys.value = result
            app.getString(R.string.hw_keys_done, result.frames.size, result.events.size)
        }
}
