package com.jmdargere.cleansmart.ui

import com.jmdargere.cleansmart.Tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum

/**
 * Note de sante du telephone (2.0) : un seul chiffre de 0 a 100 que tout le monde comprend,
 * et LE conseil qui fait gagner le plus de points. La toucher ouvre le Menage complet.
 */
@Composable
fun NoteSante(vm: AppViewModel, openGuide: () -> Unit) {
    val s = vm.sante
    val couleur = sableCouleur(s.note / 100f)
    BoxCard(onClick = openGuide) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(64.dp)) {
                    drawArc(Carte2, 0f, 360f, false, style = Stroke(8.dp.toPx()))
                    drawArc(couleur, -90f, 360f * s.note / 100f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                }
                Text("${s.note}", fontWeight = FontWeight.Black, fontSize = 20.sp, color = couleur)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(Tr.t("❤️ Santé du téléphone : %1 / 100", s.note), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Texte)
                Text(when {
                    s.note >= 80 -> Tr.t("En pleine forme")
                    s.note >= 55 -> Tr.t("Correct, mais peut mieux faire")
                    else -> Tr.t("Il a besoin d'un bon ménage")
                }, color = couleur, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(s.conseil, color = Gris, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

/**
 * Menage sur des photos : on VOIT les photos avant de les jeter (2.0). Toucher une photo la garde.
 */
@Composable
fun PhotoRevueDialog(vm: AppViewModel, fermer: () -> Unit, valider: () -> Unit) {
    val toutes = vm.safeTous
    val jetees = toutes.filter { it.key !in vm.safeGardees }
    Dialog(onDismissRequest = fermer, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Fond)) {
            Column(Modifier.fillMaxWidth().background(Bleu).padding(16.dp, 14.dp)) {
                Text(Tr.t("🧹 Le ménage sûr"), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                Text(Tr.t("%1 sur %2 · %3", fmtNum(jetees.size), fmtNum(toutes.size), fmtBytes(jetees.sumOf { it.size })),
                    color = Color(0xE6FFFFFF), fontSize = 14.sp)
            }
            Text(Tr.t("Doublons et rafales en trop (la meilleure photo est gardée), photos noires ou blanches, vieilles captures. Touche une photo pour la garder. Tout va dans la corbeille de la Galerie (30 jours)."),
                color = Gris, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp, 10.dp))
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f), contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(toutes, key = { it.key }) { m ->
                    Thumb(m, selected = m.key !in vm.safeGardees, keep = false) { vm.safeBasculer(m.key) }
                }
            }
            Column(Modifier.fillMaxWidth().background(Carte).padding(16.dp, 10.dp)) {
                BigButton(Tr.t("🗑️ Mettre %1 à la corbeille", plural(jetees.size, "photo")), color = Rouge, enabled = jetees.isNotEmpty(), onClick = valider)
                TextButton(onClick = fermer, modifier = Modifier.fillMaxWidth()) { Text(Tr.t("Annuler, ne rien jeter"), color = Gris) }
            }
        }
    }
}

private fun plural(n: Int, mot: String) = com.jmdargere.cleansmart.data.plural(n, mot)
