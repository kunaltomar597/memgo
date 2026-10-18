package com.remu.app.feature.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** One signed export file to hand to the share sheet. */
class ExportedFile(val name: String, val mime: String, val bytes: ByteArray)

/** Hands export files to the Android share sheet. Faked in tests. */
fun interface ExportShare {
    fun share(files: List<ExportedFile>, title: String)
}

/**
 * The `cache/exports` folder that the FileProvider serves. Consent export files live there only
 * while the share sheet is open: they are deleted when it returns, and any left behind (the app
 * was killed meanwhile) are deleted on the next start once older than an hour.
 */
object ExportFiles {
    const val MAX_AGE_MS = 60 * 60 * 1000L

    fun dir(context: Context): File = File(context.cacheDir, "exports")

    /** Deletes every file in the folder. */
    fun clear(dir: File) { dir.listFiles()?.forEach { it.delete() } }

    /** Deletes files last modified more than [maxAgeMs] before [nowMs]; returns how many. */
    fun deleteOlderThan(dir: File, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_AGE_MS): Int {
        var n = 0
        dir.listFiles()?.forEach { f -> if (nowMs - f.lastModified() > maxAgeMs && f.delete()) n++ }
        return n
    }
}

/**
 * Writes the consent export (CSV, PDF and their detached signatures) to `cache/exports` and opens
 * the share sheet through a FileProvider. These files hold consent records only, never an image.
 * The folder is emptied before each export and again when the share sheet returns ([launcher] is
 * set by the visible activity and reports back through [onShareReturned]).
 */
class AndroidExportShare(private val context: Context) : ExportShare {
    /** Starts the chooser for a result; set by MainActivity while it is created. */
    @Volatile var launcher: ((Intent) -> Unit)? = null

    override fun share(files: List<ExportedFile>, title: String) {
        val dir = ExportFiles.dir(context).apply { mkdirs() }
        ExportFiles.clear(dir)
        val uris = files.map { f ->
            val out = File(dir, f.name)
            out.writeBytes(f.bytes)
            FileProvider.getUriForFile(context, context.packageName + ".exports", out)
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newRawUri(title, uris.first()).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title)
        val l = launcher
        if (l != null) l(chooser) else context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The share sheet returned: the files have been handed over, delete them. */
    fun onShareReturned() = ExportFiles.clear(ExportFiles.dir(context))
}
