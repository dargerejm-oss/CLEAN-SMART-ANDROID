package com.jmdargere.cleansmart.ui

import com.jmdargere.cleansmart.Tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Mode d'emploi integre : les PDF assets/manuel_fr.pdf et assets/manuel_en.pdf
 * sont affiches directement dans l'appli (pas besoin d'un lecteur PDF).
 */
@Composable
fun Manual() {
    val ctx = LocalContext.current
    var lang by rememberSaveable { mutableStateOf(if (com.jmdargere.cleansmart.Tr.anglais) "en" else "fr") }
    var pages by remember(lang) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var error by remember(lang) { mutableStateOf(false) }

    LaunchedEffect(lang) {
        val res = withContext(Dispatchers.IO) { runCatching { render(ctx, "manuel_${lang}.pdf") }.getOrNull() }
        if (res == null) error = true else pages = res
    }

    LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                listOf("fr" to Tr.t("🇫🇷 Français"), "en" to Tr.t("🇬🇧 English")).forEach { (code, label) ->
                    FilterChip(selected = lang == code, onClick = { lang = code }, label = { Text(label) },
                        modifier = Modifier.padding(horizontal = 6.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet, selectedLabelColor = Color.White))
                }
            }
        }
        if (error) item { Text(Tr.t("Mode d'emploi introuvable."), color = Gris, modifier = Modifier.padding(24.dp)) }
        itemsIndexed(pages) { _, p ->
            Column {
                Image(p, null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)))
            }
        }
    }
}

/** Copie le PDF des assets dans le cache (PdfRenderer a besoin d'un vrai fichier) puis dessine chaque page. */
private fun render(ctx: Context, asset: String): List<ImageBitmap> {
    val f = File(ctx.cacheDir, asset)
    ctx.assets.open(asset).use { input -> f.outputStream().use { input.copyTo(it) } }
    val width = (ctx.resources.displayMetrics.widthPixels).coerceIn(720, 1440)
    val out = ArrayList<ImageBitmap>()
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { r ->
            for (i in 0 until r.pageCount) {
                r.openPage(i).use { page ->
                    val h = width * page.height / page.width
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(AColor.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out += bmp.asImageBitmap()
                }
            }
        }
    }
    return out
}
