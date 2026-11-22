package com.jmdargere.cleansmart.ui

import android.content.Context
import android.content.Intent
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jmdargere.cleansmart.Busy
import com.jmdargere.cleansmart.data.MediaItem
import com.jmdargere.cleansmart.data.fmtBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Carte arrondie standard. */
@Composable
fun BoxCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = modifier.fillMaxWidth().padding(vertical = 6.dp)
    Card(
        modifier = if (onClick != null) m.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick) else m,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Carte),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

/** Gros bouton pleine largeur. */
@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = Violet, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

/** Ligne : pastille emoji + titre/sous-titre + valeur a droite. */
@Composable
fun InfoRow(emoji: String, title: String, sub: String, value: String = "", onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Carte2), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = Gris, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value.isNotEmpty()) Text(value, fontWeight = FontWeight.ExtraBold, color = Violet2, fontSize = 16.sp)
        if (onClick != null) Text("  ›", color = Gris, fontSize = 22.sp)
    }
}

/** Anneau de stockage. */
@Composable
fun StorageRing(ratio: Float) {
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxSize(), color = Carte2, strokeWidth = 12.dp)
        CircularProgressIndicator(
            progress = { ratio }, modifier = Modifier.fillMaxSize(),
            color = if (ratio > 0.9f) Rouge else Violet, strokeWidth = 12.dp, strokeCap = StrokeCap.Round,
            trackColor = Color.Transparent,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(ratio * 100).toInt()} %", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text("utilisé", color = Gris, fontSize = 12.sp)
        }
    }
}

/** Fenetre de progression (bloquante) pendant un travail long. */
@Composable
fun BusyDialog(b: Busy) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Carte)) {
            Column(Modifier.padding(22.dp).fillMaxWidth()) {
                Text(b.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text(b.step.ifEmpty { "Préparation…" }, color = Gris, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { b.pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(99.dp)),
                    color = Violet, trackColor = Carte2,
                )
                Text("${b.pct} %", color = Gris, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.End)
            }
        }
    }
}

/** Demande de confirmation. */
data class Confirm(val title: String, val text: String, val ok: String, val danger: Boolean = true, val action: () -> Unit)

@Composable
fun ConfirmDialog(c: Confirm, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Carte,
        title = { Text(c.title, fontWeight = FontWeight.Bold) },
        text = { Text(c.text, color = Gris) },
        confirmButton = {
            Button(onClick = { onClose(); c.action() },
                colors = ButtonDefaults.buttonColors(containerColor = if (c.danger) Rouge else Violet)) { Text(c.ok) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler", color = Gris) } },
    )
}

// ---------------------------------------------------------------- miniatures

object Thumbs {
    private val cache = LruCache<String, ImageBitmap>(500)
    fun cached(key: String): ImageBitmap? = cache.get(key)
    suspend fun load(ctx: Context, item: MediaItem): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.loadThumbnail(item.uri, Size(240, 240), null).asImageBitmap()
                .also { cache.put(item.key, it) }
        }.getOrNull()
    }
}

fun openInGallery(ctx: Context, item: MediaItem) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumb(item: MediaItem, selected: Boolean, keep: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(item.key) { mutableStateOf(Thumbs.cached(item.key)) }
    LaunchedEffect(item.key) { if (bmp == null) bmp = Thumbs.load(ctx, item) }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Carte2)
            .then(if (selected) Modifier.border(3.dp, Rouge, RoundedCornerShape(10.dp)) else Modifier)
            .combinedClickable(onClick = { if (!keep) onToggle() else openInGallery(ctx, item) },
                onLongClick = { openInGallery(ctx, item) }),
    ) {
        bmp?.let {
            Image(it, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (selected) 0.55f else 1f))
        }
        if (item.video || item.size > 20L * 1024 * 1024) {
            Text((if (item.video) "▶ " else "") + fmtBytes(item.size), fontSize = 10.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        if (!keep && item.note.isNotEmpty()) {
            Text(item.note, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A1A00),
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color(0xFFFFB547), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        if (keep) {
            Text("GARDÉE", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF002211),
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Vert, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        } else {
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).clip(CircleShape)
                    .background(if (selected) Rouge else Color(0x66000000))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
fun SectionTitle(t: String) {
    Text(t, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Gris, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp))
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun RowGap(w: Int = 8) = Spacer(Modifier.width(w.dp))

val Centered = Arrangement.Center
