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
 * Writes the consent export (CSV, PDF and their detached signatures) to `cache/exports` and opens
 * the share sheet through a FileProvider. These files hold consent records only, never an image;
 * the folder is emptied before each export.
 */
class AndroidExportShare(private val context: Context) : ExportShare {
    override fun share(files: List<ExportedFile>, title: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
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
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
