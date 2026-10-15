package cz.magicheroes.tournamentmanager.export

import android.content.ContentResolver
import android.net.Uri
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

object PdfExporter {
    fun write(contentResolver: ContentResolver, uri: Uri, title: String, lines: List<String>) {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = android.graphics.Color.rgb(35, 31, 45)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18f
            isFakeBoldText = true
            color = android.graphics.Color.rgb(71, 48, 112)
        }
        val pageWidth = 595
        val pageHeight = 842
        val margin = 42f
        val lineHeight = 17f
        val maxTextWidth = pageWidth - margin * 2
        val expanded = mutableListOf<String>()
        lines.forEach { raw ->
            if (raw.isEmpty()) {
                expanded += ""
            } else {
                var current = ""
                raw.split(Regex("\\s+")).forEach { word ->
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (paint.measureText(candidate) > maxTextWidth && current.isNotEmpty()) {
                        expanded += current
                        current = word
                    } else current = candidate
                }
                if (current.isNotEmpty()) expanded += current
            }
        }

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        canvas.drawText(title, margin, 48f, titlePaint)
        var y = 78f
        expanded.forEach { line ->
            if (y > pageHeight - margin) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = margin
            }
            if (line.isNotEmpty()) canvas.drawText(line, margin, y, paint)
            y += lineHeight
        }
        document.finishPage(page)
        contentResolver.openOutputStream(uri, "w").use { output: OutputStream? ->
            requireNotNull(output) { "Soubor PDF se nepodařilo otevřít pro zápis." }
            document.writeTo(output)
        }
        document.close()
    }
}
