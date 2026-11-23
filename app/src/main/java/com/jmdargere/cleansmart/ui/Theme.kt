package com.jmdargere.cleansmart.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Meme esprit que Mail Simple et Photo Simple : fond clair, bandeau bleu, grandes cartes de couleur.
val Fond = Color(0xFFF4F6FB)       // fond de l'appli
val Carte = Color(0xFFFFFFFF)      // cartes blanches
val Carte2 = Color(0xFFE9EDF7)     // pastilles, rails
val Texte = Color(0xFF1E2230)      // texte principal
val Gris = Color(0xFF5F6475)       // texte secondaire
val Bleu = Color(0xFF0D47A1)       // bandeau du haut
val Violet = Color(0xFF1565C0)     // couleur principale (boutons, selection)
val Violet2 = Color(0xFF1565C0)    // chiffres mis en avant
val Vert = Color(0xFF2E7D32)       // bouton de la prochaine etape, "gardee"
val Rouge = Color(0xFFC62828)      // nettoyer, jeter
val Orange = Color(0xFFEF6C00)
val Pourpre = Color(0xFF6A1B9A)
val Turquoise = Color(0xFF00838F)

private val Scheme = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    secondary = Vert,
    background = Fond,
    onBackground = Texte,
    surface = Carte,
    onSurface = Texte,
    surfaceVariant = Carte2,
    onSurfaceVariant = Gris,
    error = Rouge,
)

@Composable
fun CleanSmartTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
