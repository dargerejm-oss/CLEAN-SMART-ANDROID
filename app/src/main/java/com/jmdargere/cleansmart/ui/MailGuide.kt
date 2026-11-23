package com.jmdargere.cleansmart.ui

import com.jmdargere.cleansmart.Tr

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.data.Sender
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ouvre le lien de desabonnement d'une newsletter (page web, ou mail pre-rempli). */
fun desabonner(ctx: Context, s: Sender) {
    val u = s.unsubscribe
    if (u.isEmpty()) return
    val i = if (u.startsWith("mailto:")) Intent(Intent.ACTION_SENDTO, Uri.parse(u)) else Intent(Intent.ACTION_VIEW, Uri.parse(u))
    runCatching { ctx.startActivity(i) }
}

/**
 * Menage guide des mails, comme pour les photos :
 * 1 menage sur, 2 tri par expediteur, 3 mettre a l'abri l'important, 4 liberer la boite.
 */
@Composable
fun MailGuide(vm: AppViewModel, ask: (Confirm) -> Unit, openTri: () -> Unit, chooseFolder: () -> Unit) {
    val r = vm.mailResult
    val d = vm.mailGuideDone
    val safe = vm.mailSafeSenders
    val prochaine = when {
        r == null -> 0
        1 !in d && (safe.isNotEmpty() || r.doublons.isNotEmpty()) -> 1
        2 !in d -> 2
        3 !in d -> 3
        4 !in d -> 4
        else -> 5
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)) {
        item {
            Text(Tr.t("Trier d'abord, mettre l'important à l'abri, puis vider la boîte. Les mails de ta banque, de l'administration ou de santé ne sont jamais jetés."),
                color = Gris, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 8.dp))
            if (r == null) EtapeCarte(0, "🔍", Tr.t("Avant tout : analyser"), Tr.t("Clean Smart lit les 3 000 derniers mails (rien n'est supprimé)."),
                Bleu, true, false, Tr.t("Analyser ma boîte mail")) { vm.analyseMail() }

            val nbSafe = safe.sumOf { it.count } + (r?.doublons?.size ?: 0)
            EtapeCarte(1, "🧹", Tr.t("Le ménage sûr"),
                if (r == null) Tr.t("Newsletters, pubs, notifications et mails en double.")
                else Tr.t("%1 : newsletters et pubs (%2 expéditeurs), notifications, mails en double.", plural(nbSafe, "mail"), safe.count { it.newsletter }),
                Rouge, prochaine == 1, 1 in d || (r != null && nbSafe == 0), Tr.t("Tout mettre à la corbeille")) {
                ask(Confirm(Tr.t("Faire le ménage sûr ?"),
                    Tr.t("Les newsletters, pubs, notifications et mails en double vont dans la corbeille de ta messagerie (toute la boîte de réception).\n\nLes mails importants ne sont jamais touchés."),
                    Tr.t("Corbeille")) { vm.mailGuideClean() })
            }
            if (r != null && safe.any { it.unsubscribe.isNotEmpty() } && 1 !in d) {
                Text(Tr.t("💡 Astuce : désabonne-toi pour ne plus les recevoir (bouton dans la liste « Qui t'écrit le plus »)."),
                    color = Gris, fontSize = 13.sp, modifier = Modifier.padding(8.dp, 0.dp))
            }
            EtapeCarte(2, "👆", Tr.t("Je trie par expéditeur"),
                Tr.t("Une carte par expéditeur : glisse à gauche pour jeter TOUS ses mails, à droite pour le garder."),
                Pourpre, prochaine == 2, 2 in d, Tr.t("Trier les expéditeurs")) { vm.mailGuideReview(); openTri() }
            EtapeCarte(3, "🛡️", Tr.t("Je mets l'important à l'abri"),
                if (!vm.backupReady()) Tr.t("Choisis d'abord un dossier de sauvegarde (carte SD, clé USB, Drive).")
                else Tr.t("Banque, administration, santé, factures et tous les mails avec un PDF : sauvegardés, PDF réunis, archive index.html sur le PC."),
                Bleu, prochaine == 3, 3 in d,
                if (vm.backupReady()) Tr.t("Mettre à l'abri") else Tr.t("Choisir un dossier"),
                if (vm.backupReady()) ({ vm.mailGuideSave() }) else chooseFolder)
            EtapeCarte(4, "📤", Tr.t("Je libère la boîte"),
                Tr.t("%1les mails de plus de %2 ans vont à la corbeille (sauf les importants).", if (r != null) plural(r.oldCount, Tr.t("vieux mail"), Tr.t("vieux mails")) + " · " + fmtBytes(r.oldBytes) + " · " else "", vm.settings.oldMailYears),
                Vert, prochaine == 4, 4 in d, Tr.t("Libérer la boîte")) {
                ask(Confirm(Tr.t("Libérer la boîte mail ?"), Tr.t("Les mails de plus de %1 ans vont dans la corbeille de ta messagerie. Les importants sont gardés.", vm.settings.oldMailYears),
                    Tr.t("Corbeille")) { vm.mailGuideFree() })
            }
            if (prochaine == 5) {
                Gap()
                CatTile("🎉", Tr.t("Boîte mail en ordre !"), Tr.t("L'important est à l'abri, le reste est parti"), "", "", Vert, Modifier.fillMaxWidth()) {}
            }
        }
    }
}

/** Tri par expediteur : une carte a la fois. */
@Composable
fun MailTri(vm: AppViewModel, ask: (Confirm) -> Unit) {
    val list = vm.mailSwipeList
    val s = list.getOrNull(vm.mailSwipeIndex)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp, 4.dp, 16.dp, 12.dp)) {
        Text(Tr.t("%1 / %2  ·  %3 à jeter", fmtNum(minOf(vm.mailSwipeIndex + 1, list.size)), fmtNum(list.size), plural(vm.mailSwipeTrash.size, Tr.t("expéditeur"))),
            color = Gris, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Gap()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (s == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎉", fontSize = 56.sp)
                    Text(Tr.t("Tri terminé !"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Texte)
                }
            } else {
                CarteExpediteur(s, onDecide = { jeter -> vm.mailSwipe(jeter) }, onUnsub = { desabonner(ctx, s) })
            }
        }
        Gap()
        Text(Tr.t("⬅ Glisse à gauche : tous ses mails à la corbeille   ·   Glisse à droite : on garde ➡"), color = Gris, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Gap()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Rond("✕", Rouge, enabled = s != null) { vm.mailSwipe(true) }
            Rond("↩", Gris, small = true, enabled = vm.mailSwipeIndex > 0) { vm.mailSwipeUndo() }
            Rond("✓", Vert, enabled = s != null) { vm.mailSwipe(false) }
        }
        if (vm.mailSwipeTrash.isNotEmpty()) {
            Gap()
            BigButton(Tr.t("🧹 Jeter les mails de %1", plural(vm.mailSwipeTrash.size, Tr.t("expéditeur"))), color = Rouge) {
                ask(Confirm(Tr.t("Jeter ces mails ?"),
                    Tr.t("Tous les mails de %1 (au moins %2 mails) vont dans la corbeille de ta messagerie.", plural(vm.mailSwipeTrash.size, Tr.t("expéditeur")), fmtNum(vm.mailSwipeTrash.sumOf { it.count })),
                    Tr.t("Corbeille")) { vm.mailSwipeApply() })
            }
        }
    }
}

@Composable
private fun Rond(t: String, c: Color, small: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape,
        modifier = Modifier.size(if (small) 54.dp else 70.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c, contentColor = Color.White)) {
        Text(t, fontSize = if (small) 20.sp else 28.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun CarteExpediteur(s: Sender, onDecide: (Boolean) -> Unit, onUnsub: () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember(s.email) { Animatable(0f) }
    val limit = 260f
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer { rotationZ = offset.value / 45f }
            .clip(RoundedCornerShape(24.dp))
            .background(Carte)
            .pointerInput(s.email) {
                detectDragGestures(onDragEnd = {
                    scope.launch {
                        val v = offset.value
                        if (abs(v) > limit) { offset.animateTo(if (v > 0) 1500f else -1500f); onDecide(v < 0) }
                        else offset.animateTo(0f)
                    }
                }) { change, drag -> change.consume(); scope.launch { offset.snapTo(offset.value + drag.x) } }
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(80.dp).background(Bleu, CircleShape), contentAlignment = Alignment.Center) {
                Text((s.name.ifEmpty { s.email }).take(1).uppercase(), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            }
            Gap(12)
            Text(s.name.ifEmpty { s.email }, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Texte, textAlign = TextAlign.Center)
            Text(s.email, fontSize = 13.sp, color = Gris, textAlign = TextAlign.Center)
            Gap(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pastille(Tr.t("✉️ %1 mails", fmtNum(s.count)))
                Pastille("💾 ${fmtBytes(s.bytes)}")
            }
            if (s.unsubscribe.isNotEmpty()) {
                Gap(14)
                androidx.compose.material3.OutlinedButton(onClick = onUnsub) { Text(Tr.t("🚫 Se désabonner"), color = Rouge, fontWeight = FontWeight.Bold) }
            }
        }
        val v = offset.value
        if (abs(v) > 40f) {
            Text(if (v < 0) Tr.t("À JETER") else Tr.t("À GARDER"), fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)
                    .graphicsLayer { alpha = (abs(v) / limit).coerceIn(0f, 1f) }
                    .background(if (v < 0) Rouge else Vert, RoundedCornerShape(12.dp)).padding(14.dp, 6.dp))
        }
    }
}

@Composable
private fun Pastille(t: String) {
    Text(t, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Texte,
        modifier = Modifier.background(Carte2, RoundedCornerShape(12.dp)).padding(12.dp, 6.dp))
}
