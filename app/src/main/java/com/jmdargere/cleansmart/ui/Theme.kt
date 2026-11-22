package com.jmdargere.cleansmart.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Fond = Color(0xFF0F0F1E)
val Carte = Color(0xFF1B1B33)
val Carte2 = Color(0xFF26264A)
val Violet = Color(0xFF6A5CFF)
val Violet2 = Color(0xFF9D93FF)
val Vert = Color(0xFF2ECC8F)
val Rouge = Color(0xFFFF5C7A)
val Gris = Color(0xFFA3A3C7)

private val Scheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    secondary = Violet2,
    background = Fond,
    onBackground = Color(0xFFF3F3FF),
    surface = Carte,
    onSurface = Color(0xFFF3F3FF),
    surfaceVariant = Carte2,
    onSurfaceVariant = Gris,
    error = Rouge,
)

@Composable
fun CleanSmartTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
