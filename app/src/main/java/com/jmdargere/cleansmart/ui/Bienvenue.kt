package com.jmdargere.cleansmart.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class Page(val emoji: String, val couleur: Color, val titre: String, val texte: String)

private val PAGES = listOf(
    Page("📱", Rouge, "Ton téléphone est plein ?",
        "Photos en double, rafales, photos ratées, grosses vidéos, mails inutiles… tout prend de la place."),
    Page("🧭", Vert, "Un bouton, et c'est trié",
        "Le Ménage guidé fait tout dans le bon ordre. Le bouton vert est toujours la prochaine étape."),
    Page("🛡️", Bleu, "Rien n'est perdu",
        "Tout passe par la corbeille : 30 jours pour récupérer. Et « J'ai fait une bêtise » annule en un geste."),
    Page("💾", Pourpre, "Tes photos à l'abri",
        "Seules les bonnes photos sont sauvegardées, sur une clé, un disque ou Google Drive. Même Google Photos peut être rapatrié."),
)

/** Accueil de bienvenue au tout premier lancement (comme les presentations de Mail Simple et Photo Simple). */
@Composable
fun Bienvenue(onFini: () -> Unit) {
    val etat = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Fond)) {
        Box(Modifier.fillMaxWidth().background(Bleu).statusBarsPadding().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            Text("CLEAN SMART", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        }
        HorizontalPager(etat, Modifier.weight(1f)) { i ->
            val p = PAGES[i]
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth().height(220.dp).background(p.couleur, RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center) { Text(p.emoji, fontSize = 110.sp) }
                Gap(28)
                Text(p.titre, color = p.couleur, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 34.sp)
                Gap(12)
                Text(p.texte, color = Texte, fontSize = 18.sp, textAlign = TextAlign.Center, lineHeight = 26.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center) {
            PAGES.indices.forEach { k ->
                Box(Modifier.padding(5.dp).size(if (k == etat.currentPage) 13.dp else 10.dp)
                    .background(if (k == etat.currentPage) PAGES[k].couleur else Color(0xFFC9CDD6), CircleShape))
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp, 0.dp, 20.dp, 16.dp)) {
            val derniere = etat.currentPage == PAGES.size - 1
            BigButton(if (derniere) "Commencer" else "Suivant", color = Vert) {
                if (derniere) onFini() else scope.launch { etat.animateScrollToPage(etat.currentPage + 1) }
            }
            if (!derniere) TextButton(onClick = onFini, modifier = Modifier.fillMaxWidth()) { Text("Passer", color = Gris) }
        }
    }
}
