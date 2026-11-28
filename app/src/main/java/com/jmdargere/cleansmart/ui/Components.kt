package com.jmdargere.cleansmart.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import com.jmdargere.cleansmart.Tr

import android.content.Context
import android.content.Intent
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.animateFloat
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

/** Couleur de chaque categorie (comme les grandes cartes de Mail Simple / Photo Simple). */
fun couleurDe(c: com.jmdargere.cleansmart.Cat): Color = when (c) {
    com.jmdargere.cleansmart.Cat.DUPLICATES -> Bleu
    com.jmdargere.cleansmart.Cat.SIMILAR -> Pourpre
    com.jmdargere.cleansmart.Cat.BLURRY -> Orange
    com.jmdargere.cleansmart.Cat.SCREENS -> Turquoise
    com.jmdargere.cleansmart.Cat.BIG_VIDEOS -> Rouge
    com.jmdargere.cleansmart.Cat.WHATSAPP -> Vert
    com.jmdargere.cleansmart.Cat.BIGGEST -> Color(0xFF455A64)
}

/** Grande tuile de couleur : icone dans un rond blanc, titre, nombre et taille. */
@Composable
fun CatTile(emoji: String, title: String, sub: String, value: String, badge: String, color: Color,
            modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.height(150.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 22.sp)
                }
                Spacer(Modifier.weight(1f))
                if (badge.isNotEmpty()) Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(8.dp)).padding(6.dp, 2.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 2, lineHeight = 18.sp)
            Text(if (value.isEmpty()) sub else "${sub} · ${value}", color = Color(0xE6FFFFFF), fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Coordonnees de l'auteur (comme dans Mail Simple et Photo Simple). */
object Contact {
    const val INSTAGRAM = "@jean_michel_dargere"
    const val MAIL = "dargerejm@gmail.com"
    const val TELEPHONE = "06 13 37 10 42"
    const val WHATSAPP = "https://wa.me/33613371042"
}

/** Trois gros boutons : Instagram, mail, WhatsApp / appel. */
@Composable
fun BoutonsContact() {
    val ctx = LocalContext.current
    fun ouvrir(i: Intent) { runCatching { ctx.startActivity(i) } }
    BigButton("📸 Instagram  ${Contact.INSTAGRAM}", color = Color(0xFFC13584)) {
        ouvrir(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/jean_michel_dargere/")))
    }
    Spacer(Modifier.height(8.dp))
    BigButton("✉️ ${Contact.MAIL}", color = Bleu) {
        ouvrir(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:${Contact.MAIL}"))
            .putExtra(Intent.EXTRA_SUBJECT, "Clean Smart"))
    }
    Spacer(Modifier.height(8.dp))
    BigButton("💬 WhatsApp  ${Contact.TELEPHONE}", color = Color(0xFF25D366)) {
        ouvrir(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Contact.WHATSAPP)))
    }
    Spacer(Modifier.height(8.dp))
    BigButton("📞 " + Tr.t("Appeler") + "  ${Contact.TELEPHONE}", color = Vert) {
        ouvrir(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:0613371042")))
    }
}

/** Fenetre « Version complete / Une question ? » avec les coordonnees. */
@Composable
fun ContactDialog(titre: String, texte: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Carte)) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(titre, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Bleu, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(texte, color = Gris, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
                Spacer(Modifier.height(14.dp))
                BoutonsContact()
                TextButton(onClick = onClose) { Text(Tr.t("Fermer"), color = Gris) }
            }
        }
    }
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
            Text(Tr.t("utilisé"), color = Gris, fontSize = 12.sp)
        }
    }
}

/** "1 min 05 s" */
private fun duree(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s} s" else Tr.t("%1 min %2 s", s / 60, "%02d".format(s % 60))
}

/**
 * Fenetre de travail (bloquante), comme dans Mail Simple et Photo Simple :
 * le sablier, la barre, le pourcentage, le temps passe et le temps restant.
 */
@Composable
fun BusyDialog(b: Busy) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val passe = now - b.start
    val reste = if (b.pct in 3..99) passe * (100 - b.pct) / b.pct else -1L
    // l'ecran reste allume pendant le travail (le telephone ne se met pas en veille)
    val vue = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        vue.keepScreenOn = true
        onDispose { vue.keepScreenOn = false }
    }

    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Carte)) {
            Column(Modifier.padding(22.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(b.title, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = Bleu, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Sablier(b.pct / 100f)
                Spacer(Modifier.height(10.dp))
                Text("${b.pct} %", fontWeight = FontWeight.Black, fontSize = 34.sp, color = sableCouleur(b.pct / 100f))
                Text(b.step.ifEmpty { Tr.t("Préparation…") }, color = Gris, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { b.pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(99.dp)),
                    color = sableCouleur(b.pct / 100f), trackColor = Carte2,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(Tr.t("Temps passé"), color = Gris, fontSize = 12.sp)
                        Text(duree(passe), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Texte)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(Tr.t("Temps restant"), color = Gris, fontSize = 12.sp)
                        Text(if (reste >= 0) "≈ ${duree(reste)}" else Tr.t("calcul…"), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Texte)
                    }
                }
            }
        }
    }
}

/**
 * Sablier repris de Nettoyage Pro : verre avec reflets, socles en bois, sable qui change de couleur avec
 * l'avancement (rouge → orange → jaune → vert), filet de sable qui coule, petit balancement a la fin.
 * Dessine dans un repere de 150 x 220 (comme le dessin d'origine), mis a l'echelle.
 */
@Composable
fun Sablier(progress: Float, largeur: androidx.compose.ui.unit.Dp = 96.dp) {
    val p = progress.coerceIn(0f, 1f)
    val anim = androidx.compose.animation.core.rememberInfiniteTransition(label = "sablier")
    val flot by anim.animateFloat(
        initialValue = 0f, targetValue = 7f, label = "flot",
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(350, easing = androidx.compose.animation.core.LinearEasing)),
    )
    // petit balancement quand c'est fini
    val balance = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(p >= 1f) {
        if (p >= 1f) repeat(2) {
            balance.animateTo(-8f, androidx.compose.animation.core.tween(300))
            balance.animateTo(8f, androidx.compose.animation.core.tween(400))
            balance.animateTo(0f, androidx.compose.animation.core.tween(300))
        }
    }
    val couleur = sableCouleur(p)
    androidx.compose.foundation.Canvas(
        Modifier.size(width = largeur, height = largeur * (220f / 150f))
            .graphicsLayer { rotationZ = balance.value }
    ) {
        val k = size.width / 150f
        fun X(v: Float) = v * k
        fun path(bloc: androidx.compose.ui.graphics.Path.() -> Unit) = androidx.compose.ui.graphics.Path().apply(bloc)
        val haut = path { // ampoule du haut
            moveTo(X(30f), X(28f)); lineTo(X(120f), X(28f))
            cubicTo(X(120f), X(70f), X(88f), X(92f), X(80f), X(110f)); lineTo(X(70f), X(110f))
            cubicTo(X(62f), X(92f), X(30f), X(70f), X(30f), X(28f)); close()
        }
        val bas = path { // ampoule du bas
            moveTo(X(70f), X(110f)); lineTo(X(80f), X(110f))
            cubicTo(X(88f), X(128f), X(120f), X(150f), X(120f), X(192f)); lineTo(X(30f), X(192f))
            cubicTo(X(30f), X(150f), X(62f), X(128f), X(70f), X(110f)); close()
        }
        val bois = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFA1672F), Color(0xFF7B4A1D), Color(0xFF4E2C0E)))
        val reflet = androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(Color.White.copy(alpha = .45f), Color.Transparent), center = Offset(X(55f), X(60f)), radius = X(120f))

        // ombre au sol
        drawOval(Color.Black.copy(alpha = .22f), topLeft = Offset(X(13f), X(205f)), size = androidx.compose.ui.geometry.Size(X(124f), X(14f)))
        // socle du bas
        drawRoundRect(bois, topLeft = Offset(X(16f), X(196f)), size = androidx.compose.ui.geometry.Size(X(118f), X(16f)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(X(6f)))

        // sable du haut : il descend
        val h = 82f * (1f - p)
        if (h > 0.5f) clipPath(haut) {
            drawRect(couleur, topLeft = Offset(X(20f), X(110f - h)), size = androidx.compose.ui.geometry.Size(X(110f), X(h)))
            drawRect(reflet, topLeft = Offset(X(20f), X(110f - h)), size = androidx.compose.ui.geometry.Size(X(110f), X(h)))
        }
        // sable du bas : un petit tas qui monte
        if (p > 0.005f) clipPath(bas) {
            val tas = path {
                moveTo(X(20f), X(192f)); lineTo(X(130f), X(192f)); lineTo(X(130f), X(192f - 58f * p))
                quadraticTo(X(100f), X(192f - 58f * p), X(75f), X(192f - 70f * p - 6f))
                quadraticTo(X(50f), X(192f - 58f * p), X(20f), X(192f - 58f * p)); close()
            }
            drawPath(tas, couleur)
            drawPath(tas, reflet)
        }
        // filet de sable qui coule (pointilles qui defilent)
        if (p > 0f && p < 1f) drawLine(couleur, Offset(X(75f), X(108f)), Offset(X(75f), X(192f - 70f * p - 6f)), strokeWidth = X(2.6f),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(X(3f), X(4f)), -X(flot)))

        // le verre (par-dessus) et ses reflets
        val verre = androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(Color.White.copy(alpha = .75f), Color(0x40CFE9F5), Color(0x2E9FD0E8), Color.White.copy(alpha = .6f)))
        drawPath(haut, verre); drawPath(bas, verre)
        val trait = androidx.compose.ui.graphics.drawscope.Stroke(width = X(2.5f))
        drawPath(haut, Color(0xFF8FB9CC), style = trait); drawPath(bas, Color(0xFF8FB9CC), style = trait)
        val rond = androidx.compose.ui.graphics.drawscope.Stroke(width = X(5f), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawPath(path { moveTo(X(40f), X(36f)); cubicTo(X(42f), X(66f), X(60f), X(84f), X(66f), X(104f)) }, Color.White.copy(alpha = .75f), style = rond)
        drawPath(path { moveTo(X(40f), X(184f)); cubicTo(X(42f), X(160f), X(52f), X(148f), X(58f), X(138f)) }, Color.White.copy(alpha = .45f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = X(4f), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        // socle du haut
        drawRoundRect(bois, topLeft = Offset(X(18f), X(14f)), size = androidx.compose.ui.geometry.Size(X(114f), X(16f)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(X(6f)))
        drawRoundRect(Color.White.copy(alpha = .25f), topLeft = Offset(X(18f), X(14f)), size = androidx.compose.ui.geometry.Size(X(114f), X(4f)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(X(2f)))
        drawRoundRect(Color.White.copy(alpha = .25f), topLeft = Offset(X(16f), X(196f)), size = androidx.compose.ui.geometry.Size(X(118f), X(4f)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(X(2f)))
    }
}

/** Couleur du sable selon l'avancement : rouge, orange, jaune, puis vert a la fin (comme Nettoyage Pro). */
fun sableCouleur(p: Float): Color {
    val etapes = listOf(0f to Color(0xFFE53935), 0.45f to Color(0xFFFB8C00), 0.75f to Color(0xFFFDD835), 1f to Color(0xFF43A047))
    for (i in 1 until etapes.size) if (p <= etapes[i].first) {
        val (a, ca) = etapes[i - 1]; val (b, cb) = etapes[i]
        return androidx.compose.ui.graphics.lerp(ca, cb, ((p - a) / (b - a)).coerceIn(0f, 1f))
    }
    return Color(0xFF43A047)
}

/** Grand bandeau vert « TERMINÉ » (comme Photo Simple). */
@Composable
fun DoneDialog(title: String, onClose: () -> Unit) {
    LaunchedEffect(title) { kotlinx.coroutines.delay(3000); onClose() }
    Dialog(onDismissRequest = onClose) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Vert),
            modifier = Modifier.clickable(onClick = onClose)) {
            Column(Modifier.padding(26.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✅", fontSize = 56.sp)
                Text(Tr.t("TERMINÉ"), fontWeight = FontWeight.Black, fontSize = 32.sp, color = Color.White)
                Text(title, color = Color(0xE6FFFFFF), fontSize = 15.sp, textAlign = TextAlign.Center)
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
        dismissButton = { TextButton(onClick = onClose) { Text(Tr.t("Annuler"), color = Gris) } },
    )
}

// ---------------------------------------------------------------- miniatures

object Thumbs {
    private val cache = LruCache<String, ImageBitmap>(500)
    fun cached(key: String): ImageBitmap? = cache.get(key)
    suspend fun load(ctx: Context, item: MediaItem): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            (if (com.jmdargere.cleansmart.data.Demo.estDemo(item.uri)) com.jmdargere.cleansmart.data.Demo.image(item.key, 240)
            else ctx.contentResolver.loadThumbnail(item.uri, Size(240, 240), null)).asImageBitmap()
                .also { cache.put(item.key, it) }
        }.getOrNull()
    }
}

fun openInGallery(ctx: Context, item: MediaItem) {
    if (com.jmdargere.cleansmart.data.Demo.estDemo(item.uri)) return
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
            Text(Tr.t(item.note), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A1A00),
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color(0xFFFFB547), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        if (keep) {
            Text(Tr.t("GARDÉE"), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF002211),
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
