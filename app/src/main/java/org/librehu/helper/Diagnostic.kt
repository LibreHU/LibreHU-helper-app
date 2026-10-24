package org.librehu.helper

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.librehu.helper.collect.Collectors
import org.librehu.helper.report.Redactor
import org.librehu.helper.report.Report
import org.librehu.helper.root.Su
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs the chosen sections into a report zip, in the background; [state] for the UI. */
object Diagnostic {
    sealed interface State {
        data object Idle : State

        data class Running(
            val step: String,
            val section: Int,
            val sections: Int,
        ) : State

        data class Done(
            val where: String,
            val entries: Int,
            val bytes: Long,
        ) : State

        data class Failed(
            val message: String,
        ) : State
    }

    /** Where the zip goes: a folder picked by the user (USB key…), or the app's folder of the shared storage. */
    sealed interface Target {
        data class Folder(
            val tree: Uri,
        ) : Target

        data object AppFolder : Target
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _root = MutableStateFlow<Su.Root?>(null)
    val root: StateFlow<Su.Root?> = _root.asStateFlow()

    private val _rootScanning = MutableStateFlow(false)
    val rootScanning: StateFlow<Boolean> = _rootScanning.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    fun start(
        context: Context,
        target: Target,
        sections: Set<String>,
        anonymise: Boolean,
    ) {
        if (_state.value is State.Running) return
        val app = context.applicationContext
        val chosen = Collectors.ALL.filter { it.id in sections }
        _state.value = State.Running(app.getString(R.string.step_root), 0, chosen.size)
        scope.launch {
            _state.value =
                try {
                    run(app, target, chosen, anonymise)
                } catch (e: Exception) {
                    State.Failed(e.message ?: e.javaClass.simpleName)
                }
        }
    }

    private fun run(
        app: Context,
        target: Target,
        chosen: List<org.librehu.helper.collect.Collector>,
        anonymise: Boolean,
    ): State {
        if (!Su.scanned) _root.value = Su.scan(app)
        val name = fileName()
        val (out, where) = open(app, target, name)
        val redactor = if (anonymise) Redactor.of(app) else null
        var section = 0
        val report = Report(out, redactor) { step -> _state.value = State.Running(step, section, chosen.size) }
        report.use { r ->
            r.text("README.txt", readme(app, chosen.map { app.getString(it.title) }, anonymise))
            r.text("root.txt", Su.report())
            for (c in chosen) {
                section++
                _state.value = State.Running(app.getString(c.title), section, chosen.size)
                try {
                    c.collect(app, r)
                } catch (e: Exception) {
                    r.skipped(c.id, "error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        return State.Done(where, report.count, report.bytes)
    }

    private fun open(
        app: Context,
        target: Target,
        name: String,
    ): Pair<OutputStream, String> =
        when (target) {
            is Target.Folder -> {
                val dir = DocumentsContract.buildDocumentUriUsingTree(target.tree, DocumentsContract.getTreeDocumentId(target.tree))
                val doc =
                    DocumentsContract.createDocument(app.contentResolver, dir, "application/zip", name)
                        ?: throw IllegalStateException(app.getString(R.string.error_create))
                val out = app.contentResolver.openOutputStream(doc) ?: throw IllegalStateException(app.getString(R.string.error_create))
                out to name
            }
            Target.AppFolder -> {
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "diag").apply { mkdirs() }
                val f = File(dir, name)
                f.outputStream() to f.path
            }
        }

    private fun fileName(): String {
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(32)
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return "LibreHU-diag-$model-$date.zip"
    }

    private fun readme(
        app: Context,
        sections: List<String>,
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
            appendLine("sections: ${sections.joinToString()}")
            appendLine()
            appendLine("manifest.json lists every file with its size and SHA-256, and what was left out and why.")
        }
}
