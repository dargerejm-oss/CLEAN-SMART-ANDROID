package com.jmdargere.cleansmart.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * PDF souvenir : les plus belles photos d'un mois ou d'une annee, dans un PDF leger
 * a envoyer a un proche. Enregistre dans Telechargements / Clean Smart.
 *
 * periode : 0 = ce mois-ci, 1 = le mois dernier, 2 = cette annee
 */
object Souvenir {

    private const val MAX_PHOTOS = 24
    private const val W = 595   // A4 en points
    private const val H = 842

    fun make(ctx: Context, periode: Int, s: Settings): Uri? {
        val cal = Calendar.getInstance()
        val (start, end, titre) = when (periode) {
            1 -> {
                cal.add(Calendar.MONTH, -1)
                val a = debutMois(cal); cal.add(Calendar.MONTH, 1)
                Triple(a, debutMois(cal), moisTexte(a))
            }
            2 -> {
                val a = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_YEAR, 1); zero(this) }.timeInMillis
                Triple(a, System.currentTimeMillis() + 1, "Année ${cal.get(Calendar.YEAR)}")
            }
            else -> {
                val a = debutMois(cal)
                Triple(a, System.currentTimeMillis() + 1, moisTexte(a))
            }
        }

        val photos = MediaScanner(ctx).allMedia().filter {
            !it.video && it.date in start until end &&
                    !it.path.contains("screenshot", true) && !it.path.contains("whatsapp", true)
        }.sortedBy { it.date }
        if (photos.isEmpty()) return null

        // favoris d'abord, puis des photos reparties sur toute la periode (une par moment)
        val fav = photos.filter { it.favorite }
        val rest = photos.filter { !it.favorite }
        val step = (rest.size.toDouble() / (MAX_PHOTOS - fav.size).coerceAtLeast(1)).coerceAtLeast(1.0)
        val picked = (fav.take(MAX_PHOTOS) + rest.indices.step(step).map { rest[it] })
            .distinctBy { it.key }.take(MAX_PHOTOS).sortedBy { it.date }

        val doc = PdfDocument()
        try {
            // couverture
            doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create()).also { page ->
                val c = page.canvas
                c.drawColor(Color.rgb(15, 15, 30))
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
                p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 40f
                c.drawText(titre, W / 2f, H / 2f - 20, p)
                p.typeface = Typeface.DEFAULT; p.textSize = 16f; p.color = Color.rgb(163, 163, 199)
                c.drawText("${plural(picked.size, "photo")} · Clean Smart", W / 2f, H / 2f + 20, p)
                doc.finishPage(page)
            }
            // 6 photos par page (2 colonnes x 3 lignes)
            val fmt = SimpleDateFormat("d MMMM yyyy", Locale.FRANCE)
            picked.chunked(6).forEachIndexed { pi, chunk ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pi + 2).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                val cw = (W - 3 * 24f) / 2
                val ch = (H - 4 * 24f) / 3
                val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 10f }
                chunk.forEachIndexed { i, it ->
                    val x = 24f + (i % 2) * (cw + 24f)
                    val y = 24f + (i / 2) * (ch + 24f)
                    // une image "materielle" ne se dessine pas dans un PDF : copie classique
                    val bmp = runCatching {
                        val b = ctx.contentResolver.loadThumbnail(it.uri, Size(800, 800), null)
                        if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle() } else b
                    }.getOrNull()
                    if (bmp != null) {
                        c.drawBitmap(bmp, null, fit(bmp, RectF(x, y, x + cw, y + ch - 16)), Paint(Paint.FILTER_BITMAP_FLAG))
                        bmp.recycle()
                    }
                    c.drawText(fmt.format(Date(it.date)), x, y + ch - 2, txt)
                }
                doc.finishPage(page)
            }

            val name = "Souvenir_" + titre.replace(' ', '_') + ".pdf"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Clean Smart")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            return uri
        } finally {
            doc.close()
        }
    }

    private fun fit(b: Bitmap, box: RectF): RectF {
        val r = minOf(box.width() / b.width, box.height() / b.height)
        val w = b.width * r
        val h = b.height * r
        val l = box.left + (box.width() - w) / 2
        val t = box.top + (box.height() - h) / 2
        return RectF(l, t, l + w, t + h)
    }

    private fun zero(c: Calendar) {
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    }

    private fun debutMois(c: Calendar): Long =
        (c.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); zero(this) }.timeInMillis

    private fun moisTexte(ms: Long): String =
        SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(Date(ms)).replaceFirstChar { it.uppercase() }

    /** 0, 1.5, 3, 4.5... arrondis : indices repartis regulierement. */
    private fun IntRange.step(s: Double): List<Int> {
        val out = ArrayList<Int>()
        var x = first.toDouble()
        while (x <= last) { out += x.toInt(); x += s }
        return out
    }
}
