package com.jmdargere.cleansmart.ui

import com.jmdargere.cleansmart.Tr

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.data.Appli
import com.jmdargere.cleansmart.data.fmtBytes

/**
 * Gestionnaire d'applis (2.0) : 2 listes seulement — celles qu'on n'ouvre plus, les plus grosses.
 * Toucher une appli : la desinstaller (Android demande confirmation) ou ouvrir sa page pour vider le cache.
 */
@Composable
fun Applis(vm: AppViewModel) {
    val ctx = LocalContext.current
    var acces by remember { mutableStateOf(vm.applisAcces()) }
    var filtre by rememberSaveable { mutableStateOf(0) }       // 0 = pas ouvertes depuis 3 mois, 1 = les plus grosses
    var choisie by remember { mutableStateOf<Appli?>(null) }
    val vie = LocalLifecycleOwner.current
    DisposableEffect(vie) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val a = vm.applisAcces()
                if (a != acces || vm.applis != null) { acces = a; vm.rafraichirApplis() }
            }
        }
        vie.lifecycle.addObserver(o)
        onDispose { vie.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(Unit) { if (vm.applis == null) vm.chargerApplis() }

    val troisMois = System.currentTimeMillis() - 90L * 86_400_000
    val toutes = vm.applis ?: emptyList()
    val liste = if (filtre == 0 && acces) toutes.filter { it.dernierUsage in 1 until troisMois || (it.dernierUsage == 0L && it.installee < troisMois) }
                else toutes

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)) {
        item {
            if (!acces) {
                BoxCard {
                    Text(Tr.t("🔓 Une autorisation pour en savoir plus"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(Tr.t("Avec « Accès aux données d'utilisation », Clean Smart voit la vraie place de chaque appli et depuis quand tu ne l'as pas ouverte. Rien n'est envoyé nulle part."),
                        color = Texte, fontSize = 14.sp, lineHeight = 20.sp)
                    Gap(8)
                    BigButton(Tr.t("🔓 Autoriser"), color = Vert) {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                            .recoverCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    listOf(Tr.t("😴 Pas ouvertes depuis 3 mois"), Tr.t("📦 Les plus grosses")).forEachIndexed { i, l ->
                        FilterChip(selected = filtre == i, onClick = { filtre = i }, label = { Text(l) }, modifier = Modifier.padding(end = 6.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet, selectedLabelColor = Color.White))
                    }
                }
            }
            if (vm.applis != null) Text(Tr.t("%1 · %2 au total. Touche une appli pour la désinstaller ou vider son cache.",
                plural(liste.size, Tr.t("appli")), fmtBytes(liste.sumOf { it.taille })), color = Gris, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
            if (vm.applis != null && liste.isEmpty()) BoxCard { Text(Tr.t("🎉 Aucune appli oubliée : tu les utilises toutes !"), color = Gris) }
        }
        items(liste, key = { it.pkg }) { a -> LigneAppli(a, acces) { choisie = a } }
    }

    choisie?.let { a ->
        AlertDialog(
            onDismissRequest = { choisie = null }, containerColor = Carte,
            title = { Text(a.nom, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(Tr.t("Place prise : %1", fmtBytes(a.taille)) + "\n" + derniereFois(a, acces), color = Gris, fontSize = 14.sp)
                    Gap(10)
                    BigButton(Tr.t("🗑️ Désinstaller"), color = Rouge) {
                        choisie = null
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))) }
                    }
                    Gap(6)
                    BigButton(Tr.t("🧊 Vider son cache"), color = Carte2) {
                        choisie = null
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))) }
                    }
                    Text(Tr.t("Sur la page qui s'ouvre : Stockage › « Vider le cache ». Android le demande, Clean Smart ne peut pas le faire à ta place."),
                        color = Gris, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = { TextButton(onClick = { choisie = null }) { Text(Tr.t("Fermer"), color = Violet2) } },
        )
    }
}

@Composable
private fun LigneAppli(a: Appli, acces: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icone = remember(a.pkg) { runCatching { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Carte, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icone != null) Image(icone, null, Modifier.size(40.dp)) else Text("📱", fontSize = 30.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.nom, fontWeight = FontWeight.Bold, color = Texte, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(derniereFois(a, acces), color = Gris, fontSize = 12.sp)
        }
        Text(fmtBytes(a.taille), fontWeight = FontWeight.Bold, color = Violet2)
    }
}

private fun derniereFois(a: Appli, acces: Boolean): String {
    if (!acces) return ""
    if (a.dernierUsage <= 0) return Tr.t("Pas ouverte depuis plus d'un an")
    val jours = ((System.currentTimeMillis() - a.dernierUsage) / 86_400_000).toInt()
    return if (jours <= 0) Tr.t("Ouverte aujourd'hui") else Tr.t("Ouverte il y a %1", plural(jours, Tr.t("jour")))
}

private fun plural(n: Int, mot: String) = com.jmdargere.cleansmart.data.plural(n, mot)
