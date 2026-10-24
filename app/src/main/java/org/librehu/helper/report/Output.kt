package org.librehu.helper.report

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the files go: a folder picked by the user (USB key…), or the app's folder of the shared storage. */
sealed interface Target {
    data class Folder(
        val tree: Uri,
    ) : Target

    data object AppFolder : Target
}

/** Creates files (and sub folders) in a [Target]. */
class Output private constructor(
    private val context: Context,
    /** Document of the folder, for a picked folder. */
    private val doc: Uri?,
    /** The folder, for the app's folder. */
    private val dir: File?,
    /** Readable place, for the user. */
    val where: String,
) {
    fun create(
        name: String,
        mime: String = "application/zip",
    ): Pair<OutputStream, String> {
        if (dir != null) {
            val f = File(dir, name)
            return f.outputStream() to f.path
        }
        val created =
            DocumentsContract.createDocument(context.contentResolver, doc!!, mime, name)
                ?: throw IllegalStateException("cannot create $name")
        val out = context.contentResolver.openOutputStream(created) ?: throw IllegalStateException("cannot write $name")
        return out to "$where/$name"
    }

    fun folder(name: String): Output {
        if (dir != null) return Output(context, null, File(dir, name).apply { mkdirs() }, "${dir.path}/$name")
        val created =
            DocumentsContract.createDocument(context.contentResolver, doc!!, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IllegalStateException("cannot create $name")
        return Output(context, created, null, "$where/$name")
    }

    companion object {
        fun of(
            context: Context,
            target: Target,
        ): Output =
            when (target) {
                is Target.Folder -> {
                    val doc = DocumentsContract.buildDocumentUriUsingTree(target.tree, DocumentsContract.getTreeDocumentId(target.tree))
                    val label = target.tree.lastPathSegment?.substringAfter(':')?.ifEmpty { null } ?: "…"
                    Output(context, doc, null, label)
                }
                Target.AppFolder -> {
                    val d = File(context.getExternalFilesDir(null) ?: context.filesDir, "diag").apply { mkdirs() }
                    Output(context, null, d, d.path)
                }
            }

        /** `LibreHU-<what>-<model>-<date>`. */
        fun stamp(what: String): String {
            val model = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(32)
            return "LibreHU-$what-$model-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}"
        }
    }
}
