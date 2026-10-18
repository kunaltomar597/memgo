package com.remu.app.feature.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.core.content.res.ResourcesCompat
import com.remu.app.R
import java.io.FileOutputStream

/**
 * Prints the counter standee (docs/DESIGN.md section 11): two A5 cards on one A4 sheet, the
 * verbatim Hindi line in Noto Sans Devanagari Bold 40pt over the English line in Inter SemiBold
 * 36pt, "5%" in maroon, optional shop name at 20pt, nothing else. Drawn straight into the print
 * system's file; no copy is kept on the phone.
 */
object StandeePrinter {
    fun print(context: Context, shopName: String?) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val attrs = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
        pm.print("Remu standee", Adapter(context.applicationContext, shopName), attrs)
    }

    private class Adapter(private val context: Context, private val shopName: String?) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder("remu-standee.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true)
        }

        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            val doc = PdfDocument()
            try {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
                drawHalf(page.canvas, 0f)
                drawHalf(page.canvas, 421f)
                doc.finishPage(page)
                FileOutputStream(dest.fileDescriptor).use { doc.writeTo(it) }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(null)
            } finally {
                doc.close()
            }
        }

        private fun drawHalf(canvas: android.graphics.Canvas, top: Float) {
            canvas.drawColor(Color.parseColor("#FBF7F2"))
            val hiFont = ResourcesCompat.getFont(context, R.font.noto_sans_devanagari_bold) ?: Typeface.DEFAULT_BOLD
            val enFont = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
            var y = top + 48f
            shopName?.takeIf { it.isNotBlank() }?.let {
                y += drawText(canvas, it, enFont, 20f, y)
                y += 16f
            }
            y += drawText(canvas, context.getString(R.string.standee_hi), hiFont, 40f, y)
            y += 20f
            drawText(canvas, context.getString(R.string.standee_en), enFont, 36f, y)
        }

        private fun drawText(canvas: android.graphics.Canvas, text: String, font: Typeface, size: Float, y: Float): Float {
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = size; color = Color.parseColor("#1C1917") }
            val span = SpannableString(text)
            val i = text.indexOf("5%")
            if (i >= 0) span.setSpan(ForegroundColorSpan(Color.parseColor("#7A1F2E")), i, i + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val layout = StaticLayout.Builder.obtain(span, 0, span.length, paint, 595 - 96).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            canvas.save()
            canvas.translate(48f, y)
            layout.draw(canvas)
            canvas.restore()
            return layout.height.toFloat()
        }
    }
}
