package com.jmdargere.cleansmart.ui

import com.jmdargere.cleansmart.Tr

import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum

/**
 * Tuile « Fichiers inutiles » (2.0) : un bouton « Chercher », des groupes a cocher, un bouton « Nettoyer ».
 * Les groupes surs sont coches ; les groupes « a verifier » passent par la corbeille Clean Smart (7 jours).
 * Le cache des autres applis : Android l'interdit aux applis, on ouvre l'ecran d'Android prevu pour ca.
 */
@Composable
fun Inutiles(vm: AppViewModel, ask: (Confirm) -> Unit, openApplis: () -> Unit) {
    val ctx = LocalContext.current
    var acces by remember { mutableStateOf(vm.demo || vm.junkAcces()) }
    val vie = LocalLifecycleOwner.current
    DisposableEffect(vie) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) acces = vm.demo || vm.junkAcces() }
        vie.lifecycle.addObserver(o)
        onDispose { vie.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(acces) { if (acces && vm.junk == null) vm.junkChercher() }

    LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp)) {
        item {
            BoxCard(onClick = openApplis) {
                InfoRow("📱", Tr.t("Mes applis"), Tr.t("Les plus grosses et celles que tu n'ouvres plus : les désinstaller en un geste"), onClick = openApplis)
            }
        }
        item {
            if (!acces) {
                BoxCard {
                    Text(Tr.t("🔓 Une autorisation, une seule fois"), fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Gap(6)
                    Text(Tr.t("Pour trouver les fichiers inutiles, Clean Smart doit pouvoir regarder le stockage du téléphone : autorise « Accès à tous les fichiers ». Rien n'est envoyé nulle part."),
                        color = Texte, fontSize = 15.sp, lineHeight = 21.sp)
                    Gap(10)
                    BigButton(Tr.t("🔓 Autoriser l'accès aux fichiers"), color = Vert) {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                        }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                    }
                }
            }
        }

        val groupes = vm.junk
        if (acces && groupes != null) {
            item {
                val choisis = groupes.filter { it.cle in vm.junkChoix }
                BoxCard {
                    Text(Tr.t("À nettoyer"), color = Gris, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(fmtBytes(choisis.sumOf { it.taille }), fontSize = 36.sp, fontWeight = FontWeight.Black, color = Violet2,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(Tr.t("Les photos, vidéos et musiques ne sont jamais touchées."), color = Gris, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                if (groupes.isEmpty()) BoxCard { Text(Tr.t("🎉 Aucun fichier inutile : le téléphone est propre !"), color = Gris) }
            }
            groupes.forEach { g ->
                item(key = g.cle) {
                    val coche = g.cle in vm.junkChoix
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .background(if (coche) Vert.copy(alpha = 0.07f) else Carte, RoundedCornerShape(16.dp))
                        .border(1.dp, if (g.sur) Carte2 else Orange.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { vm.junkChoix = if (coche) vm.junkChoix - g.cle else vm.junkChoix + g.cle }
                        .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(coche, { vm.junkChoix = if (it) vm.junkChoix + g.cle else vm.junkChoix - g.cle },
                            colors = CheckboxDefaults.colors(checkedColor = Vert))
                        Text(g.emoji, fontSize = 24.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.titre, fontWeight = FontWeight.Bold, color = Texte, fontSize = 15.sp)
                            Text(g.aide, color = Gris, fontSize = 12.sp, lineHeight = 16.sp)
                            Text(Tr.t("%1 · %2", plural(g.fichiers.size, Tr.t("élément")), fmtBytes(g.taille)) +
                                    (if (!g.sur) Tr.t(" · va dans la corbeille Clean Smart") else ""),
                                color = if (g.sur) Vert else Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (!g.sur && !vm.demo) Text(g.fichiers.take(4).joinToString("\n") { "• " + it.name }, color = Gris, fontSize = 11.sp)
                        }
                    }
                }
            }
            item {
                val choisis = groupes.filter { it.cle in vm.junkChoix }
                Gap(6)
                if (choisis.isNotEmpty()) BigButton(Tr.t("🧹 Nettoyer · %1", fmtBytes(choisis.sumOf { it.taille })), color = Rouge) {
                    val aVerifier = choisis.filter { !it.sur }
                    ask(Confirm(Tr.t("Nettoyer les fichiers inutiles ?"),
                        Tr.t("%1 seront supprimés : ils ne servent plus ou se recréent tout seuls.", plural(choisis.filter { it.sur }.sumOf { it.fichiers.size }, Tr.t("élément"))) +
                                (if (aVerifier.isNotEmpty()) Tr.t("\n\n%1 « à vérifier » iront dans la corbeille Clean Smart : tu pourras les remettre pendant 7 jours.", plural(aVerifier.sumOf { it.fichiers.size }, Tr.t("élément"))) else ""),
                        Tr.t("Nettoyer")) { vm.junkNettoyer() })
                }
                OutlinedButton(onClick = { vm.junkChercher() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    shape = RoundedCornerShape(14.dp)) { Text(Tr.t("🔄 Chercher à nouveau"), color = Texte) }
            }
        }

        // la corbeille Clean Smart (fichiers « a verifier » mis de cote)
        if (vm.junkCorbeille > 0) item {
            BoxCard {
                InfoRow("🗑️", Tr.t("Corbeille Clean Smart"), Tr.t("Fichiers mis de côté, vidée toute seule après 7 jours"), fmtBytes(vm.junkCorbeille))
                Gap(4)
                OutlinedButton(onClick = { vm.junkRemettre() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(Tr.t("↩️ Tout remettre à sa place"), color = Texte)
                }
                Gap(4)
                BigButton(Tr.t("Vider maintenant"), color = Carte2) {
                    ask(Confirm(Tr.t("Vider la corbeille Clean Smart ?"), Tr.t("%1 seront supprimés pour de bon.", fmtBytes(vm.junkCorbeille)), Tr.t("Vider")) { vm.junkViderCorbeille() })
                }
            }
        }

        // le cache des autres applis : seul Android peut le vider
        item {
            Gap(6)
            Column(Modifier.fillMaxWidth().background(Bleu.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .border(1.dp, Bleu.copy(alpha = 0.3f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                Text(Tr.t("⚙️ Cache des applis et fichiers système"), fontWeight = FontWeight.Black, fontSize = 16.sp, color = Bleu)
                Gap(4)
                Text(Tr.t("Depuis Android 11, aucune appli de nettoyage n'a le droit de vider le cache des autres applis ni les fichiers système. Android le fait lui-même : ce bouton ouvre son écran « Libérer de l'espace »."),
                    color = Texte, fontSize = 14.sp, lineHeight = 20.sp)
                Gap(8)
                BigButton(Tr.t("Ouvrir « Libérer de l'espace » d'Android"), color = Bleu) {
                    runCatching { ctx.startActivity(Intent(StorageManager.ACTION_MANAGE_STORAGE)) }
                        .recoverCatching { ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                }
                Text(Tr.t("Astuce : dans Paramètres › Applications, touche une appli (Chrome, Facebook…) › Stockage › « Vider le cache »."),
                    color = Gris, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun plural(n: Int, mot: String) = com.jmdargere.cleansmart.data.plural(n, mot)
@Suppress("unused") private fun nb(n: Int) = fmtNum(n)
