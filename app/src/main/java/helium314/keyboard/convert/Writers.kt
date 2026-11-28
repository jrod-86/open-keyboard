// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ---------------------------------------------------------------- helpers

internal fun esc(s: String) = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
        else -> if (c.code < 0x20 && c != '\n' && c != '\t') append(' ') else append(c)
    }
}

/** A zip file where "mimetype" (OpenDocument, ePub) is stored first and uncompressed, as those formats require. */
internal class Zip(out: OutputStream) {
    private val z = ZipOutputStream(out)
    fun stored(name: String, data: ByteArray) {
        val e = ZipEntry(name); e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
        e.crc = CRC32().apply { update(data) }.value
        z.putNextEntry(e); z.write(data); z.closeEntry()
    }
    fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
    fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))
    fun close() = z.close()
}

/** Picture size in pixels without decoding it. */
internal fun imageSize(bytes: ByteArray): Pair<Int, Int> {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    return if (o.outWidth > 0) o.outWidth to o.outHeight else 400 to 300
}

/** Decodes a picture (any type Android knows, turned the right way up), no bigger than [maxSide] pixels. */
internal fun decodeImage(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val big = maxOf(info.size.width, info.size.height)
        if (big > maxSide) dec.setTargetSize(info.size.width * maxSide / big, info.size.height * maxSide / big)
    }
}.getOrNull()

/** Pictures that other programs may not read (WebP, GIF, BMP) become PNG or JPEG. */
internal fun portableImage(img: Block.Image): Pair<ByteArray, String>? {
    if (img.mime == "image/png" || img.mime == "image/jpeg") return img.bytes to img.mime
    val bmp = decodeImage(img.bytes, 2400) ?: return null
    val out = ByteArrayOutputStream()
    val png = bmp.hasAlpha()
    bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray() to (if (png) "image/png" else "image/jpeg")
}

private fun bulletText(p: Block.Para) = p.bullet?.let { if (it.isBlank()) "" else "$it " }.orEmpty()

// ---------------------------------------------------------------- PDF (our own page layout, real selectable text)

object PdfWriter {
    class Page(val w: Int, val h: Int)
    val A4 = Page(595, 842)
    val LETTER = Page(612, 792)

    fun write(doc: Doc, out: OutputStream, size: Page) {
        val wide = doc.tables.any { t -> (t.rows.maxOfOrNull { it.size } ?: 0) > 6 }
        val page = if (wide) Page(size.h, size.w) else size
        val margin = 48f
        val pdf = PdfDocument()
        var pageNo = 0
        var current: PdfDocument.Page? = null
        var y = margin
        val bottom = page.h - margin
        val left = margin
        val width = page.w - 2 * margin

        fun newPage(): Canvas {
            current?.let { pdf.finishPage(it) }
            pageNo++
            current = pdf.startPage(PdfDocument.PageInfo.Builder(page.w, page.h, pageNo).create())
            y = margin
            return current!!.canvas
        }
        var canvas = newPage()
        var pageHasContent = false

        fun ensure(h: Float) { if (y + h > bottom && pageHasContent) { canvas = newPage(); pageHasContent = false } }

        fun drawLayout(layout: StaticLayout, x: Float) {
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var last = line
                while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= bottom - y) last++
                if (layout.getLineBottom(last) - top > bottom - y && pageHasContent) { canvas = newPage(); pageHasContent = false; continue }
                val h = layout.getLineBottom(last) - top
                canvas.save()
                canvas.clipRect(x - 2, y, x + width + 2, y + h)
                canvas.translate(x, y - top)
                layout.draw(canvas)
                canvas.restore()
                y += h; pageHasContent = true
                line = last + 1
                if (line < layout.lineCount) { canvas = newPage(); pageHasContent = false }
            }
        }

        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        for ((idx, b) in doc.blocks.withIndex()) when (b) {
            is Block.Heading -> {
                val sizes = floatArrayOf(22f, 18f, 15f, 13f, 12f, 11f)
                val p = TextPaint(body).apply { textSize = sizes[(b.level - 1).coerceIn(0, 5)]; typeface = Typeface.DEFAULT_BOLD }
                val l = layout(b.runs, p, width.toInt(), 0)
                if (pageHasContent) y += p.textSize * 0.7f
                ensure(minOf(l.height.toFloat(), p.textSize * 3)) // keep a heading with what follows
                drawLayout(l, left)
                y += p.textSize * 0.35f
            }
            is Block.Para -> {
                val indent = (b.list.coerceAtMost(6) * 18f) + if (b.quote) 18f else 0f
                val runs = if (b.bullet != null && b.runs.isNotEmpty()) listOf(Run(bulletText(b))) + b.runs else b.runs
                if (runs.isEmpty()) { y += body.textSize; continue }
                val p = if (b.code) TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 9.5f } else body
                val l = layout(runs, p, (width - indent).toInt(), b.align)
                if (b.quote) { val top = y; drawLayout(l, left + indent); canvas.drawRect(left + 4, top, left + 6, y, Paint().apply { color = Color.LTGRAY }) }
                else drawLayout(l, left + indent)
                val next = doc.blocks.getOrNull(idx + 1)
                y += if (b.list > 0 && next is Block.Para && next.list > 0) 3f else 7f
            }
            is Block.Rule -> { ensure(12f); canvas.drawLine(left, y + 5, left + width, y + 5, Paint().apply { color = Color.GRAY; strokeWidth = 0.7f }); y += 12f; pageHasContent = true }
            is Block.PageBreak -> if (pageHasContent) { canvas = newPage(); pageHasContent = false }
            is Block.Image -> {
                val bmp = decodeImage(b.bytes, 1600) ?: continue
                var w = bmp.width * 0.75f; var h = bmp.height * 0.75f // pixels at 96 dpi -> points
                if (w > width) { h *= width / w; w = width }
                val maxH = bottom - margin
                if (h > maxH) { w *= maxH / h; h = maxH }
                ensure(h)
                canvas.drawBitmap(bmp, null, android.graphics.RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
                y += h + 8; pageHasContent = true
            }
            is Block.Table -> {
                if (b.name != null && doc.tables.size > 1) {
                    val l = layout(runsOf(b.name), TextPaint(body).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD }, width.toInt(), 0)
                    if (pageHasContent) y += 10f
                    ensure(l.height + 30f); drawLayout(l, left); y += 4f
                }
                val cols = b.rows.maxOfOrNull { it.size } ?: 0
                if (cols == 0) continue
                val cell = TextPaint(body).apply { textSize = if (cols > 8) 7.5f else if (cols > 5) 8.5f else 9.5f }
                val head = TextPaint(cell).apply { typeface = Typeface.DEFAULT_BOLD }
                // column widths follow how much text each column has
                val weight = FloatArray(cols) { c -> (b.rows.take(200).maxOfOrNull { r -> r.getOrNull(c)?.plain()?.lines()?.maxOfOrNull { it.length } ?: 0 } ?: 0).coerceIn(3, 40).toFloat() }
                val total = weight.sum()
                val colW = FloatArray(cols) { weight[it] / total * width }
                val pad = 3f
                val line = Paint().apply { color = Color.rgb(170, 170, 170); strokeWidth = 0.5f; style = Paint.Style.STROKE }
                val shade = Paint().apply { color = Color.rgb(236, 239, 245) }
                for ((ri, row) in b.rows.withIndex()) {
                    val isHead = b.header && ri == 0
                    val layouts = (0 until cols).map { c -> layout(row.getOrNull(c).orEmpty(), if (isHead) head else cell, (colW[c] - 2 * pad).toInt().coerceAtLeast(10), 0) }
                    val h = (layouts.maxOf { it.height } + 2 * pad).coerceAtMost(bottom - margin)
                    ensure(h)
                    var x = left
                    if (isHead) canvas.drawRect(left, y, left + width, y + h, shade)
                    for (c in 0 until cols) {
                        canvas.save(); canvas.clipRect(x, y, x + colW[c], y + h); canvas.translate(x + pad, y + pad); layouts[c].draw(canvas); canvas.restore()
                        canvas.drawRect(x, y, x + colW[c], y + h, line)
                        x += colW[c]
                    }
                    y += h; pageHasContent = true
                }
                y += 10f
            }
        }
        current?.let { pdf.finishPage(it) }
        pdf.writeTo(out)
        pdf.close()
    }

    internal fun layout(runs: List<Run>, paint: TextPaint, width: Int, align: Int): StaticLayout {
        val sb = SpannableStringBuilder()
        for (r in runs) {
            val start = sb.length
            sb.append(r.text.replace('\t', ' ').replace("\u000B", "\n"))
            val end = sb.length
            if (start == end) continue
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            if (r.b && r.i) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, f)
            else if (r.b) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, f)
            else if (r.i) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, f)
            if (r.u || r.link != null) sb.setSpan(UnderlineSpan(), start, end, f)
            if (r.s) sb.setSpan(StrikethroughSpan(), start, end, f)
            if (r.mono) sb.setSpan(TypefaceSpan("monospace"), start, end, f)
            if (r.link != null) sb.setSpan(ForegroundColorSpan(Color.rgb(26, 90, 200)), start, end, f)
        }
        val a = when (align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        return StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width.coerceAtLeast(10)).setAlignment(a).setLineSpacing(0f, 1.15f).setIncludePad(false).build()
    }
}

// ---------------------------------------------------------------- Pictures (PDF pages -> JPG/PNG, picture -> picture)

object ImageWriter {
    /** Every page of a PDF as a picture at about 150 dpi. Calls [save] for each page. */
    fun pdfPages(pdf: File, fmt: Fmt, save: (index: Int, count: Int, bytes: ByteArray) -> Unit) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount > 300) throw ConvertProblem("That PDF has ${r.pageCount} pages; pictures work for up to 300.")
                for (i in 0 until r.pageCount) r.openPage(i).use { page ->
                    val scale = 150f / 72f
                    val w = (page.width * scale).toInt().coerceIn(1, 3000); val h = (page.height * scale).toInt().coerceIn(1, 4200)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    save(i, r.pageCount, encode(bmp, fmt))
                    bmp.recycle()
                }
            }
        }
    }

    fun convert(bytes: ByteArray, fmt: Fmt): ByteArray {
        val bmp = decodeImage(bytes, 8000) ?: throw ConvertProblem("This picture couldn't be opened.")
        return encode(bmp, fmt).also { bmp.recycle() }
    }

    fun encode(src: Bitmap, fmt: Fmt): ByteArray {
        var bmp = src
        if (fmt == Fmt.JPG && src.hasAlpha()) { // JPG has no see-through parts: put it on white
            bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        }
        val out = ByteArrayOutputStream()
        when (fmt) {
            Fmt.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            Fmt.WEBP -> bmp.compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP, 90, out)
            else -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (bmp !== src) bmp.recycle()
        return out.toByteArray()
    }

    /** Photos into a PDF: one per page, page turned to suit the picture. */
    fun imagesToPdf(images: List<ByteArray>, out: OutputStream, size: PdfWriter.Page) {
        val pdf = PdfDocument()
        images.forEachIndexed { i, bytes ->
            val bmp = decodeImage(bytes, 2400) ?: throw ConvertProblem("One of the pictures couldn't be opened.")
            val land = bmp.width > bmp.height
            val pw = if (land) size.h else size.w; val ph = if (land) size.w else size.h
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val m = 24f
            val s = minOf((pw - 2 * m) / bmp.width, (ph - 2 * m) / bmp.height)
            val w = bmp.width * s; val h = bmp.height * s
            val x = (pw - w) / 2; val y = (ph - h) / 2
            page.canvas.drawBitmap(bmp, null, android.graphics.RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            bmp.recycle()
        }
        pdf.writeTo(out); pdf.close()
    }
}

// ---------------------------------------------------------------- Word (.docx)

object DocxWriter {
    fun write(doc: Doc, out: OutputStream) {
        val rels = StringBuilder()
        val media = ArrayList<Pair<String, ByteArray>>()
        var relId = 10
        val body = StringBuilder()

        fun rPr(r: Run) = buildString {
            if (r.b || r.i || r.u || r.s || r.mono || r.link != null) {
                append("<w:rPr>")
                if (r.mono) append("<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/>")
                if (r.b) append("<w:b/>"); if (r.i) append("<w:i/>"); if (r.s) append("<w:strike/>")
                if (r.link != null) append("<w:color w:val=\"1A5AC8\"/>")
                if (r.u || r.link != null) append("<w:u w:val=\"single\"/>")
                append("</w:rPr>")
            }
        }
        fun runs(rs: List<Run>) = buildString {
            for (r in rs) {
                val parts = buildString {
                    append("<w:r>").append(rPr(r))
                    val pieces = r.text.split('\n')
                    pieces.forEachIndexed { k, piece ->
                        if (k > 0) append("<w:br/>")
                        piece.split('\t').forEachIndexed { t, s -> if (t > 0) append("<w:tab/>"); if (s.isNotEmpty()) append("<w:t xml:space=\"preserve\">").append(esc(s)).append("</w:t>") }
                    }
                    append("</w:r>")
                }
                val link = r.link
                if (link != null && (link.startsWith("http") || link.startsWith("mailto:"))) {
                    val id = "rId${relId++}"
                    rels.append("<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"${esc(link)}\" TargetMode=\"External\"/>")
                    append("<w:hyperlink r:id=\"$id\">").append(parts).append("</w:hyperlink>")
                } else append(parts)
            }
        }
        fun para(rs: List<Run>, style: String? = null, align: Int = 0, indent: Int = 0, hanging: Boolean = false) =
            buildString {
                append("<w:p><w:pPr>")
                if (style != null) append("<w:pStyle w:val=\"$style\"/>")
                if (indent > 0) append("<w:ind w:left=\"${indent * 360}\"" + (if (hanging) " w:hanging=\"300\"" else "") + "/>")
                when (align) { 1 -> append("<w:jc w:val=\"center\"/>"); 2 -> append("<w:jc w:val=\"right\"/>") }
                append("</w:pPr>").append(runs(rs)).append("</w:p>")
            }

        for (b in doc.blocks) when (b) {
            is Block.Heading -> body.append(para(b.runs, "Heading${b.level.coerceIn(1, 6)}"))
            is Block.Para -> {
                val rs = if (b.bullet != null && b.runs.isNotEmpty()) listOf(Run(bulletText(b).replace(" ", "\t"))) + b.runs else b.runs
                body.append(para(rs, if (b.code) "Code" else if (b.quote) "Quote" else null, b.align, b.list + if (b.quote) 1 else 0, b.list > 0))
            }
            is Block.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            is Block.Rule -> body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"999999\"/></w:pBdr></w:pPr></w:p>")
            is Block.Image -> {
                val (bytes, mime) = portableImage(b) ?: continue
                val name = "image${media.size + 1}." + (if (mime == "image/png") "png" else "jpeg")
                media.add(name to bytes)
                val id = "rId${relId++}"
                rels.append("<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>")
                val (pw, ph) = imageSize(bytes)
                var cx = pw * 9525L; var cy = ph * 9525L
                val max = 5_760_720L // 6.3 inch
                if (cx > max) { cy = cy * max / cx; cx = max }
                val n = media.size
                body.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Picture $n\"/>" +
                    "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
                    "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                    "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                    "<pic:blipFill><a:blip r:embed=\"$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                    "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                    "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
            }
            is Block.Table -> {
                if (b.name != null && doc.tables.size > 1) body.append(para(runsOf(b.name), "Heading2"))
                val cols = b.rows.maxOfOrNull { it.size } ?: 0
                if (cols == 0) continue
                val w = 9000 / cols
                body.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
                for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) body.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"A0A0A0\"/>")
                body.append("</w:tblBorders></w:tblPr><w:tblGrid>")
                repeat(cols) { body.append("<w:gridCol w:w=\"$w\"/>") }
                body.append("</w:tblGrid>")
                for ((ri, row) in b.rows.withIndex()) {
                    val head = b.header && ri == 0
                    body.append("<w:tr>").append(if (head) "<w:trPr><w:tblHeader/></w:trPr>" else "")
                    for (c in 0 until cols) {
                        val rs = row.getOrNull(c).orEmpty().map { if (head) it.copy(b = true) else it }
                        body.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>").append(if (head) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"ECEFF5\"/>" else "").append("</w:tcPr>")
                        body.append(para(rs)).append("</w:tc>")
                    }
                    body.append("</w:tr>")
                }
                body.append("</w:tbl>").append(para(emptyList()))
            }
        }

        val z = Zip(out)
        z.put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
        z.put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""")
        z.put("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(doc.title)}</dc:title><dc:creator>O-Board Converter</dc:creator></cp:coreProperties>""")
        z.put("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>$rels</Relationships>""")
        z.put("word/styles.xml", stylesXml())
        z.put("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""")
        for ((name, bytes) in media) z.put("word/media/$name", bytes)
        z.close()
    }

    private fun stylesXml(): String {
        val sizes = intArrayOf(36, 30, 26, 24, 22, 22)
        val headings = (1..6).joinToString("") { l ->
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$l\"><w:name w:val=\"heading $l\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"${if (l == 1) 360 else 240}\" w:after=\"120\"/><w:outlineLvl w:val=\"${l - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:color w:val=\"1F2A44\"/><w:sz w:val=\"${sizes[l - 1]}\"/></w:rPr></w:style>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-AU"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="140" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>$headings<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:rPr><w:i/><w:color w:val="555555"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0"/></w:pPr><w:rPr><w:rFonts w:ascii="Courier New" w:hAnsi="Courier New" w:cs="Courier New"/><w:sz w:val="19"/></w:rPr></w:style><w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblCellMar><w:left w:w="80" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style></w:styles>"""
    }
}

// ---------------------------------------------------------------- OpenDocument text (.odt)

object OdtWriter {
    fun write(doc: Doc, out: OutputStream) {
        val pics = ArrayList<Pair<String, ByteArray>>()
        val body = StringBuilder()
        fun span(r: Run): String {
            val st = "T" + (if (r.b) "b" else "") + (if (r.i) "i" else "") + (if (r.u || r.link != null) "u" else "") + (if (r.s) "s" else "") + (if (r.mono) "m" else "")
            val parts = r.text.split('\n').joinToString("<text:line-break/>") { line ->
                line.split('\t').joinToString("<text:tab/>") { esc(it).replace("  ", " <text:s/>") }
            }
            val inner = if (st == "T") parts else "<text:span text:style-name=\"$st\">$parts</text:span>"
            return if (r.link != null) "<text:a xlink:type=\"simple\" xlink:href=\"${esc(r.link)}\">$inner</text:a>" else inner
        }
        fun runs(rs: List<Run>) = rs.joinToString("") { span(it) }
        fun p(rs: List<Run>, style: String = "Standard") = "<text:p text:style-name=\"$style\">${runs(rs)}</text:p>"

        for (b in doc.blocks) when (b) {
            is Block.Heading -> body.append("<text:h text:style-name=\"H${b.level.coerceIn(1, 6)}\" text:outline-level=\"${b.level.coerceIn(1, 6)}\">${runs(b.runs)}</text:h>")
            is Block.Para -> {
                val style = when { b.code -> "Code"; b.quote -> "Quote"; b.list > 0 -> "L${b.list.coerceIn(1, 6)}"; b.align == 1 -> "Center"; b.align == 2 -> "Right"; else -> "Standard" }
                val rs = if (b.bullet != null && b.runs.isNotEmpty()) listOf(Run(bulletText(b))) + b.runs else b.runs
                body.append(p(rs, style))
            }
            is Block.PageBreak -> body.append("<text:p text:style-name=\"Break\"/>")
            is Block.Rule -> body.append("<text:p text:style-name=\"Rule\"/>")
            is Block.Image -> {
                val (bytes, mime) = portableImage(b) ?: continue
                val name = "Pictures/img${pics.size + 1}." + (if (mime == "image/png") "png" else "jpg")
                pics.add(name to bytes)
                val (pw, ph) = imageSize(bytes)
                var w = pw / 96.0 * 2.54; var h = ph / 96.0 * 2.54
                if (w > 17.0) { h *= 17.0 / w; w = 17.0 }
                body.append("<text:p text:style-name=\"Standard\"><draw:frame draw:name=\"img${pics.size}\" text:anchor-type=\"as-char\" svg:width=\"${"%.2f".format(java.util.Locale.ROOT, w)}cm\" svg:height=\"${"%.2f".format(java.util.Locale.ROOT, h)}cm\"><draw:image xlink:href=\"$name\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/></draw:frame></text:p>")
            }
            is Block.Table -> {
                if (b.name != null && doc.tables.size > 1) body.append("<text:h text:style-name=\"H2\" text:outline-level=\"2\">${esc(b.name)}</text:h>")
                val cols = b.rows.maxOfOrNull { it.size } ?: 0
                if (cols == 0) continue
                body.append("<table:table table:name=\"Table${body.length}\"><table:table-column table:number-columns-repeated=\"$cols\"/>")
                for ((ri, row) in b.rows.withIndex()) {
                    body.append("<table:table-row>")
                    for (c in 0 until cols) {
                        val rs = row.getOrNull(c).orEmpty().map { if (b.header && ri == 0) it.copy(b = true) else it }
                        body.append("<table:table-cell table:style-name=\"Cell\" office:value-type=\"string\">${p(rs)}</table:table-cell>")
                    }
                    body.append("</table:table-row>")
                }
                body.append("</table:table>")
            }
        }

        val textStyles = StringBuilder()
        for (mask in 1 until 32) {
            val name = "T" + (if (mask and 1 != 0) "b" else "") + (if (mask and 2 != 0) "i" else "") + (if (mask and 4 != 0) "u" else "") + (if (mask and 8 != 0) "s" else "") + (if (mask and 16 != 0) "m" else "")
            textStyles.append("<style:style style:name=\"$name\" style:family=\"text\"><style:text-properties")
            if (mask and 1 != 0) textStyles.append(" fo:font-weight=\"bold\" style:font-weight-asian=\"bold\" style:font-weight-complex=\"bold\"")
            if (mask and 2 != 0) textStyles.append(" fo:font-style=\"italic\" style:font-style-asian=\"italic\" style:font-style-complex=\"italic\"")
            if (mask and 4 != 0) textStyles.append(" style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"")
            if (mask and 8 != 0) textStyles.append(" style:text-line-through-style=\"solid\"")
            if (mask and 16 != 0) textStyles.append(" style:font-name=\"Liberation Mono\" fo:font-family=\"'Liberation Mono'\"")
            textStyles.append("/></style:style>")
        }
        val sizes = intArrayOf(18, 15, 13, 12, 11, 11)
        val paraStyles = StringBuilder()
        for (l in 1..6) paraStyles.append("<style:style style:name=\"H$l\" style:family=\"paragraph\" style:default-outline-level=\"$l\"><style:paragraph-properties fo:margin-top=\"${if (l == 1) "0.5" else "0.35"}cm\" fo:margin-bottom=\"0.2cm\" fo:keep-with-next=\"always\"/><style:text-properties fo:font-size=\"${sizes[l - 1]}pt\" fo:font-weight=\"bold\" fo:color=\"#1f2a44\"/></style:style>")
        for (l in 1..6) paraStyles.append("<style:style style:name=\"L$l\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:margin-left=\"${"%.2f".format(java.util.Locale.ROOT, l * 0.63)}cm\" fo:text-indent=\"-0.45cm\" fo:margin-bottom=\"0.1cm\"/></style:style>")
        paraStyles.append("<style:style style:name=\"Center\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:text-align=\"center\"/></style:style>")
        paraStyles.append("<style:style style:name=\"Right\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:text-align=\"end\"/></style:style>")
        paraStyles.append("<style:style style:name=\"Quote\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:margin-left=\"0.8cm\"/><style:text-properties fo:font-style=\"italic\" fo:color=\"#555555\"/></style:style>")
        paraStyles.append("<style:style style:name=\"Code\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:margin-bottom=\"0cm\"/><style:text-properties style:font-name=\"Liberation Mono\" fo:font-family=\"'Liberation Mono'\" fo:font-size=\"9.5pt\"/></style:style>")
        paraStyles.append("<style:style style:name=\"Break\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:break-before=\"page\"/></style:style>")
        paraStyles.append("<style:style style:name=\"Rule\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:border-bottom=\"0.5pt solid #999999\"/></style:style>")
        paraStyles.append("<style:style style:name=\"Cell\" style:family=\"table-cell\"><style:table-cell-properties fo:padding=\"0.08cm\" fo:border=\"0.5pt solid #a0a0a0\"/></style:style>")

        val ns = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
        val z = Zip(out)
        z.stored("mimetype", Fmt.ODT.mime.toByteArray())
        z.put("META-INF/manifest.xml", manifest(Fmt.ODT.mime, pics.map { it.first }))
        z.put("styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles $ns office:version=\"1.3\"><office:styles><style:default-style style:family=\"paragraph\"><style:paragraph-properties fo:margin-bottom=\"0.2cm\"/><style:text-properties fo:font-size=\"11pt\" fo:language=\"en\" fo:country=\"AU\"/></style:default-style><style:style style:name=\"Standard\" style:family=\"paragraph\"/></office:styles><office:automatic-styles><style:page-layout style:name=\"pm1\"><style:page-layout-properties fo:page-width=\"21cm\" fo:page-height=\"29.7cm\" fo:margin-top=\"2cm\" fo:margin-bottom=\"2cm\" fo:margin-left=\"2cm\" fo:margin-right=\"2cm\"/></style:page-layout></office:automatic-styles><office:master-styles><style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\"/></office:master-styles></office:document-styles>")
        z.put("meta.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-meta $ns office:version=\"1.3\"><office:meta><dc:title>${esc(doc.title)}</dc:title><meta:generator>O-Board Converter</meta:generator></office:meta></office:document-meta>")
        z.put("content.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $ns office:version=\"1.3\"><office:automatic-styles>$textStyles$paraStyles</office:automatic-styles><office:body><office:text>$body</office:text></office:body></office:document-content>")
        for ((n, bytes) in pics) z.put(n, bytes)
        z.close()
    }

    internal fun manifest(mime: String, files: List<String>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">")
        append("<manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.3\" manifest:media-type=\"$mime\"/>")
        for (f in listOf("content.xml", "styles.xml", "meta.xml")) append("<manifest:file-entry manifest:full-path=\"$f\" manifest:media-type=\"text/xml\"/>")
        for (f in files) append("<manifest:file-entry manifest:full-path=\"$f\" manifest:media-type=\"${if (f.endsWith(".png")) "image/png" else "image/jpeg"}\"/>")
        append("</manifest:manifest>")
    }
}

// ---------------------------------------------------------------- Web page, Markdown, plain text, RTF, eBook

object TextWriters {
    fun html(doc: Doc, imageSrc: (Block.Image) -> String? = { img -> portableImage(img)?.let { "data:${it.second};base64," + Base64.encodeToString(it.first, Base64.NO_WRAP) } }): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>${esc(doc.title)}</title>")
        sb.append("<style>body{font-family:system-ui,sans-serif;max-width:46em;margin:2em auto;padding:0 1em;line-height:1.5;color:#1b1b1f}img{max-width:100%}table{border-collapse:collapse;margin:1em 0}td,th{border:1px solid #aaa;padding:.3em .5em;vertical-align:top}th{background:#eceff5}blockquote{border-left:3px solid #ccc;margin-left:0;padding-left:1em;color:#555}pre{background:#f4f4f6;padding:.6em;overflow:auto}.pb{page-break-after:always;border:0}</style></head><body>\n")
        bodyHtml(doc, sb, imageSrc)
        sb.append("</body></html>\n")
        return sb.toString()
    }

    internal fun inlineHtml(rs: List<Run>) = rs.joinToString("") { r ->
        var t = esc(r.text).replace("\n", "<br>").replace("\t", "&emsp;")
        if (r.mono) t = "<code>$t</code>"; if (r.s) t = "<s>$t</s>"; if (r.u) t = "<u>$t</u>"; if (r.i) t = "<em>$t</em>"; if (r.b) t = "<strong>$t</strong>"
        if (r.link != null) t = "<a href=\"${esc(r.link)}\">$t</a>"
        t
    }

    internal fun bodyHtml(doc: Doc, sb: StringBuilder, imageSrc: (Block.Image) -> String?, xhtml: Boolean = false) {
        val br = if (xhtml) "<br/>" else "<br>"
        val openLists = ArrayList<String>() // "ul"/"ol" per level
        fun closeTo(level: Int) { while (openLists.size > level) sb.append("</li></${openLists.removeAt(openLists.size - 1)}>\n") }
        for (b in doc.blocks) {
            if (b !is Block.Para || b.list == 0) closeTo(0)
            when (b) {
                is Block.Heading -> sb.append("<h${b.level.coerceIn(1, 6)}>${inlineHtml(b.runs).replace("<br>", br)}</h${b.level.coerceIn(1, 6)}>\n")
                is Block.Para -> {
                    val text = inlineHtml(b.runs).replace("<br>", br)
                    if (b.list > 0) {
                        val tag = if (b.bullet?.firstOrNull()?.isLetterOrDigit() == true) "ol" else "ul"
                        if (b.bullet.isNullOrBlank() && openLists.size >= b.list) { sb.append(br).append(text); continue }
                        if (openLists.size == b.list && openLists.last() != tag) { closeTo(b.list - 1); openLists.add(tag); sb.append("<$tag><li>").append(text) }
                        else if (openLists.size == b.list) sb.append("</li>\n<li>").append(text)
                        else if (openLists.size > b.list) { closeTo(b.list); sb.append("</li>\n<li>").append(text) }
                        else { while (openLists.size < b.list) { openLists.add(tag); sb.append("<$tag><li>") }; sb.append(text) }
                        continue
                    }
                    val align = when (b.align) { 1 -> " style=\"text-align:center\""; 2 -> " style=\"text-align:right\""; else -> "" }
                    when {
                        b.code -> sb.append("<pre>${esc(b.runs.plain())}</pre>\n")
                        b.quote -> sb.append("<blockquote><p>$text</p></blockquote>\n")
                        b.runs.isEmpty() -> {}
                        else -> sb.append("<p$align>$text</p>\n")
                    }
                }
                is Block.Rule -> sb.append(if (xhtml) "<hr/>\n" else "<hr>\n")
                is Block.PageBreak -> sb.append(if (xhtml) "<hr class=\"pb\"/>\n" else "<hr class=\"pb\">\n")
                is Block.Image -> imageSrc(b)?.let { sb.append("<p><img src=\"$it\" alt=\"\"${if (xhtml) "/" else ""}></p>\n") }
                is Block.Table -> {
                    if (b.name != null && doc.tables.size > 1) sb.append("<h2>${esc(b.name)}</h2>\n")
                    sb.append("<table>\n")
                    for ((ri, row) in b.rows.withIndex()) {
                        val tag = if (b.header && ri == 0) "th" else "td"
                        sb.append("<tr>").append(row.joinToString("") { "<$tag>${inlineHtml(it).replace("<br>", br)}</$tag>" }).append("</tr>\n")
                    }
                    sb.append("</table>\n")
                }
            }
        }
        closeTo(0)
    }

    fun markdown(doc: Doc): String {
        val sb = StringBuilder()
        fun md(rs: List<Run>) = rs.joinToString("") { r ->
            var t = r.text.replace(Regex("([\\\\*_`\\[\\]#])"), "\\\\$1").replace("\n", "  \n")
            if (t.isBlank()) return@joinToString t
            val lead = t.takeWhile { it == ' ' }; val trail = t.takeLastWhile { it == ' ' }; t = t.trim()
            if (r.mono) t = "`" + r.text.trim() + "`"
            if (r.s) t = "~~$t~~"; if (r.i) t = "*$t*"; if (r.b) t = "**$t**"
            if (r.link != null) t = "[$t](${r.link})"
            lead + t + trail
        }
        var lastList = false
        var lastNumbered = false
        for (b in doc.blocks) {
            val numbered = b is Block.Para && b.bullet?.firstOrNull()?.isDigit() == true
            if (lastList && (!(b is Block.Para && b.list > 0) || (b.list == 1 && !b.bullet.isNullOrBlank() && numbered != lastNumbered))) sb.append("\n")
            if (b is Block.Para && b.list > 0 && !b.bullet.isNullOrBlank()) lastNumbered = numbered
            lastList = false
            when (b) {
                is Block.Heading -> sb.append("#".repeat(b.level.coerceIn(1, 6))).append(' ').append(md(b.runs).replace("  \n", " ")).append("\n\n")
                is Block.Para -> when {
                    b.list > 0 -> {
                        val mark = if (b.bullet?.firstOrNull()?.isDigit() == true) b.bullet.trimEnd(')').let { if (it.endsWith(".")) it else "$it." } else if (b.bullet.isNullOrBlank()) " " else "-"
                        sb.append("   ".repeat(b.list - 1)).append(mark).append(' ').append(md(b.runs)).append("\n"); lastList = true
                    }
                    b.code -> sb.append("```\n").append(b.runs.plain()).append("\n```\n\n")
                    b.quote -> sb.append("> ").append(md(b.runs)).append("\n\n")
                    b.runs.isEmpty() -> {}
                    else -> sb.append(md(b.runs)).append("\n\n")
                }
                is Block.Rule, is Block.PageBreak -> sb.append("---\n\n")
                is Block.Image -> sb.append("*(picture)*\n\n")
                is Block.Table -> {
                    if (b.name != null && doc.tables.size > 1) sb.append("## ").append(b.name).append("\n\n")
                    val cols = b.rows.maxOfOrNull { it.size } ?: 0
                    if (cols == 0) continue
                    fun row(r: List<List<Run>>) = "| " + (0 until cols).joinToString(" | ") { md(r.getOrNull(it).orEmpty()).replace("|", "\\|").replace("  \n", "<br>") } + " |\n"
                    sb.append(row(b.rows[0])).append("|" + " --- |".repeat(cols) + "\n")
                    b.rows.drop(1).forEach { sb.append(row(it)) }
                    sb.append("\n")
                }
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    fun text(doc: Doc): String {
        val sb = StringBuilder()
        var inList = false
        for (b in doc.blocks) {
          if (inList && !(b is Block.Para && b.list > 0)) sb.append("\n")
          inList = b is Block.Para && b.list > 0 && b.runs.isNotEmpty()
          when (b) {
            is Block.Heading -> sb.append(b.runs.plain()).append("\n\n")
            is Block.Para -> if (b.runs.isNotEmpty()) {
                if (b.list > 0) sb.append("    ".repeat(b.list - 1)).append(bulletText(b)).append(b.runs.plain()).append("\n")
                else sb.append(b.runs.plain()).append("\n\n")
            }
            is Block.Rule -> sb.append("----------\n\n")
            is Block.PageBreak -> sb.append("\n")
            is Block.Image -> sb.append("[picture]\n\n")
            is Block.Table -> {
                if (b.name != null && doc.tables.size > 1) sb.append(b.name).append("\n")
                b.rows.forEach { r -> sb.append(r.joinToString("\t") { it.plain().replace('\n', ' ') }).append("\n") }
                sb.append("\n")
            }
          }
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trimEnd() + "\n"
    }

    fun rtf(doc: Doc): String {
        val sb = StringBuilder("{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Calibri;}{\\f1\\fmodern Courier New;}}{\\colortbl;\\red26\\green90\\blue200;}\\viewkind4\\uc1\\pard\\f0\\fs22\n")
        fun t(s: String) = buildString {
            for (c in s) when {
                c == '\\' -> append("\\\\"); c == '{' -> append("\\{"); c == '}' -> append("\\}")
                c == '\n' -> append("\\line "); c == '\t' -> append("\\tab ")
                c.code > 127 -> append("\\u").append(c.code.toShort().toInt()).append('?')
                else -> append(c)
            }
        }
        fun runs(rs: List<Run>) = rs.joinToString("") { r ->
            "{" + (if (r.b) "\\b" else "") + (if (r.i) "\\i" else "") + (if (r.u || r.link != null) "\\ul" else "") + (if (r.s) "\\strike" else "") +
                (if (r.mono) "\\f1" else "") + (if (r.link != null) "\\cf1" else "") + " " + t(r.text) + "}"
        }
        for (b in doc.blocks) when (b) {
            is Block.Heading -> sb.append("\\pard\\sb240\\sa120\\keepn{\\b\\fs${intArrayOf(36, 30, 26, 24, 22, 22)[(b.level - 1).coerceIn(0, 5)]} ").append(runs(b.runs)).append("}\\par\n")
            is Block.Para -> {
                sb.append("\\pard\\sa140")
                if (b.list > 0) sb.append("\\li${b.list * 360}\\fi-300")
                if (b.quote) sb.append("\\li720\\i")
                when (b.align) { 1 -> sb.append("\\qc"); 2 -> sb.append("\\qr") }
                sb.append(' ')
                if (b.bullet != null && b.runs.isNotEmpty()) sb.append(t(bulletText(b).trimEnd())).append("\\tab ")
                sb.append(runs(b.runs.map { if (b.code) it.copy(mono = true) else it })).append("\\par\n")
            }
            is Block.PageBreak -> sb.append("\\page\n")
            is Block.Rule -> sb.append("\\pard\\brdrb\\brdrs\\brdrw10\\brsp20 \\par\n")
            is Block.Image -> {
                val (bytes, mime) = portableImage(b) ?: continue
                val (pw, ph) = imageSize(bytes)
                var w = pw * 15; var h = ph * 15 // twips at 96 dpi
                if (w > 9600) { h = h * 9600 / w; w = 9600 }
                sb.append("\\pard{\\pict").append(if (mime == "image/png") "\\pngblip" else "\\jpegblip").append("\\picw$pw\\pich$ph\\picwgoal$w\\pichgoal$h\n")
                for ((k, byte) in bytes.withIndex()) { sb.append("%02x".format(byte.toInt() and 0xff)); if (k % 64 == 63) sb.append('\n') }
                sb.append("}\\par\n")
            }
            is Block.Table -> {
                if (b.name != null && doc.tables.size > 1) sb.append("\\pard{\\b\\fs26 ").append(t(b.name)).append("}\\par\n")
                val cols = b.rows.maxOfOrNull { it.size } ?: 0
                if (cols == 0) continue
                val w = 9600 / cols
                for ((ri, row) in b.rows.withIndex()) {
                    sb.append("\\trowd\\trgaph80")
                    for (c in 1..cols) sb.append("\\clbrdrt\\brdrs\\clbrdrl\\brdrs\\clbrdrb\\brdrs\\clbrdrr\\brdrs\\cellx${c * w}")
                    sb.append("\n")
                    for (c in 0 until cols) sb.append("\\pard\\intbl ").append(runs(row.getOrNull(c).orEmpty().map { if (b.header && ri == 0) it.copy(b = true) else it })).append("\\cell\n")
                    sb.append("\\row\n")
                }
                sb.append("\\pard\\par\n")
            }
        }
        return sb.append("}\n").toString()
    }

    fun epub(doc: Doc, out: OutputStream) {
        val pics = ArrayList<Pair<String, ByteArray>>()
        val body = StringBuilder()
        bodyHtml(doc, body, { img -> portableImage(img)?.let { (bytes, mime) -> val n = "img${pics.size + 1}." + (if (mime == "image/png") "png" else "jpg"); pics.add(n to bytes); n } }, xhtml = true)
        val id = "urn:uuid:" + UUID.randomUUID()
        val z = Zip(out)
        z.stored("mimetype", "application/epub+zip".toByteArray())
        z.put("META-INF/container.xml", "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
        val items = pics.joinToString("") { (n, _) -> "<item id=\"${n.substringBefore('.')}\" href=\"$n\" media-type=\"${if (n.endsWith("png")) "image/png" else "image/jpeg"}\"/>" }
        z.put("OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">$id</dc:identifier><dc:title>${esc(doc.title)}</dc:title><dc:language>en</dc:language><meta property=\"dcterms:modified\">${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())}</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"text\" href=\"text.xhtml\" media-type=\"application/xhtml+xml\"/>$items</manifest><spine><itemref idref=\"text\"/></spine></package>")
        z.put("OEBPS/nav.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>${esc(doc.title)}</title></head><body><nav epub:type=\"toc\"><ol><li><a href=\"text.xhtml\">${esc(doc.title)}</a></li></ol></nav></body></html>")
        z.put("OEBPS/text.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>${esc(doc.title)}</title><style>img{max-width:100%}table{border-collapse:collapse}td,th{border:1px solid #999;padding:.2em .4em}</style></head><body>${body.toString().replace("&emsp;", "&#8195;")}</body></html>")
        for ((n, bytes) in pics) z.put("OEBPS/$n", bytes)
        z.close()
    }
}

// ---------------------------------------------------------------- Spreadsheets (.xlsx, .ods, .csv)

object SheetWriters {
    /** The tables to write: spreadsheets keep their sheets; a document without tables puts each paragraph on a row. */
    fun sheets(doc: Doc): List<Pair<String, List<List<String>>>> {
        val tables = doc.tables
        if (tables.isNotEmpty()) return tables.mapIndexed { i, t -> (t.name ?: "Sheet${i + 1}") to t.rows.map { r -> r.map { it.plain() } } }
        val rows = doc.blocks.mapNotNull { b -> when (b) { is Block.Heading -> listOf(b.runs.plain()); is Block.Para -> b.runs.takeIf { it.isNotEmpty() }?.let { listOf(it.plain()) }; else -> null } }
        return listOf("Sheet1" to rows)
    }

    private fun isNumber(s: String) = s.isNotEmpty() && s.length < 16 && Regex("-?(0|[1-9]\\d*)(\\.\\d+)?").matches(s)

    fun csv(rows: List<List<String>>): String = buildString {
        for (r in rows) {
            append(r.joinToString(",") { c -> if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + c.replace("\"", "\"\"") + "\"" else c })
            append("\r\n")
        }
    }

    fun xlsx(doc: Doc, out: OutputStream) {
        val sheets = sheets(doc)
        val used = HashSet<String>()
        val names = sheets.map { (n, _) ->
            var base = n.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(31).ifBlank { "Sheet" }
            var k = 2; val orig = base
            while (!used.add(base.lowercase())) base = orig.take(28) + " ${k++}"
            base
        }
        val z = Zip(out)
        z.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>")
        z.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        z.put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
        z.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
        z.put("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment wrapText=\"1\" vertical=\"top\"/></xf></cellXfs></styleSheet>")
        for ((i, s) in sheets.withIndex()) {
            val rows = s.second
            val cols = rows.maxOfOrNull { it.size } ?: 0
            val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            if (rows.isNotEmpty()) sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            if (cols > 0) {
                sb.append("<cols>")
                for (c in 0 until cols) {
                    val len = rows.take(300).maxOfOrNull { r -> r.getOrNull(c)?.lines()?.maxOfOrNull { it.length } ?: 0 } ?: 0
                    sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${(len + 2).coerceIn(8, 60)}\" customWidth=\"1\"/>")
                }
                sb.append("</cols>")
            }
            sb.append("<sheetData>")
            for ((ri, r) in rows.withIndex()) {
                sb.append("<row r=\"${ri + 1}\">")
                for ((ci, v) in r.withIndex()) {
                    if (v.isEmpty()) continue
                    val ref = colName(ci) + (ri + 1)
                    val style = if (ri == 0 && i < doc.tables.size && doc.tables[i].header) " s=\"1\"" else if ('\n' in v) " s=\"2\"" else ""
                    if (isNumber(v) && !(ri == 0 && style == " s=\"1\"")) sb.append("<c r=\"$ref\"$style><v>$v</v></c>")
                    else sb.append("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            z.put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
        }
        z.close()
    }

    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { n--; sb.insert(0, 'A' + n % 26); n /= 26 }; return sb.toString() }

    fun ods(doc: Doc, out: OutputStream) {
        val sheets = sheets(doc)
        val ns = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $ns office:version=\"1.3\"><office:automatic-styles><style:style style:name=\"Head\" style:family=\"table-cell\"><style:text-properties fo:font-weight=\"bold\"/></style:style></office:automatic-styles><office:body><office:spreadsheet>")
        for ((i, s) in sheets.withIndex()) {
            val cols = s.second.maxOfOrNull { it.size } ?: 1
            sb.append("<table:table table:name=\"${esc(s.first)}\"><table:table-column table:number-columns-repeated=\"${cols.coerceAtLeast(1)}\"/>")
            for ((ri, r) in s.second.withIndex()) {
                sb.append("<table:table-row>")
                for (v in r) {
                    val head = ri == 0 && i < doc.tables.size && doc.tables[i].header
                    val st = if (head) " table:style-name=\"Head\"" else ""
                    when {
                        v.isEmpty() -> sb.append("<table:table-cell$st/>")
                        isNumber(v) && !head -> sb.append("<table:table-cell office:value-type=\"float\" office:value=\"$v\"><text:p>$v</text:p></table:table-cell>")
                        else -> sb.append("<table:table-cell$st office:value-type=\"string\">" + v.split('\n').joinToString("") { "<text:p>${esc(it)}</text:p>" } + "</table:table-cell>")
                    }
                }
                if (r.isEmpty()) sb.append("<table:table-cell/>")
                sb.append("</table:table-row>")
            }
            if (s.second.isEmpty()) sb.append("<table:table-row><table:table-cell/></table:table-row>")
            sb.append("</table:table>")
        }
        sb.append("</office:spreadsheet></office:body></office:document-content>")
        val z = Zip(out)
        z.stored("mimetype", Fmt.ODS.mime.toByteArray())
        z.put("META-INF/manifest.xml", OdtWriter.manifest(Fmt.ODS.mime, emptyList()))
        z.put("styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles $ns office:version=\"1.3\"/>")
        z.put("meta.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-meta $ns office:version=\"1.3\"><office:meta><dc:title>${esc(doc.title)}</dc:title></office:meta></office:document-meta>")
        z.put("content.xml", sb.toString())
        z.close()
    }
}
