package com.jmdargere.cleansmart.ui

import com.jmdargere.cleansmart.Tr

import android.util.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.data.MediaItem
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtDate
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tri rapide : une photo a la fois. Glisser a gauche = a jeter, a droite = a garder.
 * Rien n'est supprime tant qu'on n'appuie pas sur le bouton rouge (puis corbeille Android).
 */
@Composable
fun TriRapide(vm: AppViewModel, onKeep: () -> Unit, onTrash: () -> Unit) {
    LaunchedEffect(Unit) { if (vm.swipeList == null) vm.loadSwipe() }
    val list = vm.swipeList ?: return
    val item = list.getOrNull(vm.swipeIndex)

    Column(Modifier.fillMaxSize().padding(16.dp, 4.dp, 16.dp, 12.dp)) {
        Text(
            "${fmtNum(minOf(vm.swipeIndex + 1, list.size))} / ${fmtNum(list.size)}  ·  " +
                    "${plural(vm.swipeTrash.size, Tr.t("à jeter"), Tr.t("à jeter"))} (${fmtBytes(vm.swipeTrash.sumOf { it.size })})",
            color = Gris, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Gap()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (item == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎉", fontSize = 56.sp)
                    Text(Tr.t("Tri terminé !"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(Tr.t("%1 à jeter", plural(vm.swipeTrash.size, "photo")), color = Gris)
                }
            } else {
                SwipeCard(item, onDecide = { jeter -> vm.swipe(jeter) })
            }
        }
        Gap()
        Text(Tr.t("⬅ Glisse à gauche pour jeter   ·   Glisse à droite pour garder ➡"), color = Gris, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Gap()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundBtn("✕", Rouge, enabled = item != null) { vm.swipe(true) }
            RoundBtn("↩", Gris, small = true, enabled = vm.swipeIndex > 0) { vm.swipeUndo() }
            RoundBtn("✓", Vert, enabled = item != null) { vm.swipe(false) }
        }
        // a la fin : proteger les gardees (favori) et jeter les autres
        if (item == null && vm.swipeKeep.any { !it.favorite }) {
            Gap()
            BigButton(Tr.t("⭐ Protéger mes %1", plural(vm.swipeKeep.count { !it.favorite }, Tr.t("photo gardée"), Tr.t("photos gardées"))),
                color = Vert, onClick = onKeep)
        }
        if (vm.swipeTrash.isNotEmpty()) {
            Gap()
            BigButton(Tr.t("🧹 Mettre %1 à la corbeille", plural(vm.swipeTrash.size, "photo")), color = Rouge, onClick = onTrash)
        }
    }
}

@Composable
private fun RoundBtn(t: String, c: Color, small: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape,
        modifier = Modifier.size(if (small) 54.dp else 70.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c, contentColor = Color.White)) {
        Text(t, fontSize = if (small) 20.sp else 28.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SwipeCard(item: MediaItem, onDecide: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val offset = remember(item.key) { Animatable(0f) }
    val bmp by produceState<ImageBitmap?>(null, item.key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (if (com.jmdargere.cleansmart.data.Demo.estDemo(item.uri)) com.jmdargere.cleansmart.data.Demo.image(item.key, 900)
                else ctx.contentResolver.loadThumbnail(item.uri, Size(1080, 1080), null)).asImageBitmap()
            }.getOrNull()
        }
    }
    val limit = 280f

    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer { rotationZ = offset.value / 40f }
            .clip(RoundedCornerShape(22.dp))
            .background(Carte)
            .pointerInput(item.key) {
                detectDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val v = offset.value
                            if (abs(v) > limit) {
                                offset.animateTo(if (v > 0) 1600f else -1600f)
                                onDecide(v < 0)
                            } else offset.animateTo(0f)
                        }
                    },
                ) { change, drag ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + drag.x) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        // indication pendant le glissement
        val v = offset.value
        if (abs(v) > 40f) {
            Text(if (v < 0) Tr.t("À JETER") else Tr.t("À GARDER"), fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(18.dp)
                    .graphicsLayer { alpha = (abs(v) / limit).coerceIn(0f, 1f) }
                    .background(if (v < 0) Rouge else Vert, RoundedCornerShape(12.dp)).padding(14.dp, 6.dp))
        }
        Text("${fmtDate(item.date)} · ${fmtBytes(item.size)}", color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(10.dp, 4.dp))
    }
}
