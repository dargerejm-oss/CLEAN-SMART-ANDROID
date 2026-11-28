package com.jmdargere.cleansmart.data

import android.content.Context
import android.net.Uri

/**
 * Les 3 seules options de l'appli (enregistrees sur le telephone).
 * Le reste est regle une fois pour toutes, pour rester simple.
 */
data class Settings(
    /** Dossier de sauvegarde choisi (carte SD, cle USB, Drive...) ou null. */
    val backupUri: String? = null,
    /** Sauvegarder automatiquement avant chaque suppression (desactive par defaut : inutile pour les doublons et photos ratees). */
    val backupBeforeDelete: Boolean = false,
    /** Ne jamais proposer les photos favorites (= photos protegees). */
    val protectFavorites: Boolean = true,
    /** Severite de la recherche : 0 = prudente, 1 = normale, 2 = stricte. */
    val severity: Int = 1,
    /** Gros caracteres : 0 = normal, 1 = gros, 2 = tres gros (mode senior). */
    val textSize: Int = 0,
    /** Langue : 0 = comme le telephone, 1 = francais, 2 = anglais. */
    val langue: Int = 0,
    /** Chaque nuit, copier les nouvelles photos dans le dossier de sauvegarde. */
    val nightBackup: Boolean = false,
    /** Alerte "telephone presque plein" et rapport du mois (notifications). */
    val alerts: Boolean = true,
    /** "Liberer la place" : garder sur le telephone les photos des N derniers mois. */
    val keepMonths: Int = 3,
    /** Menage automatique du dimanche (fichiers inutiles, spam, regles mails + rappel des photos a trier). */
    val sundayClean: Boolean = false,
) {
    val backupTree: Uri? get() = backupUri?.let { Uri.parse(it) }
    val fontScale: Float get() = when (textSize) { 1 -> 1.2f; 2 -> 1.4f; else -> 1f }

    // Seuils selon la severite (prudente / normale / stricte)
    val similarDistance get() = when (severity) { 0 -> 6; 2 -> 14; else -> 10 }
    val blurAbsolute get() = when (severity) { 0 -> 30.0; 2 -> 65.0; else -> 45.0 }
    val blurRelative get() = when (severity) { 0 -> 0.18; 2 -> 0.35; else -> 0.25 }
    val darkLimit get() = when (severity) { 0 -> 12.0; 2 -> 25.0; else -> 18.0 }
    val brightLimit get() = when (severity) { 0 -> 250.0; 2 -> 240.0; else -> 247.0 }
    /** Photo "trop petite" en dessous de ce nombre de pixels. */
    val smallPixels get() = when (severity) { 0 -> 200_000; 2 -> 1_000_000; else -> 500_000 }

    // Reglages fixes
    val includeWhatsapp get() = true
    val bigVideoMb get() = 100
    val burstSeconds get() = 15
    val bigMailMb get() = 5
    val oldMailYears get() = 2

    companion object {
        private const val P = "options"

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            return Settings(
                backupUri = p.getString("backupUri", null),
                backupBeforeDelete = p.getBoolean("backupBeforeDelete", false),
                protectFavorites = p.getBoolean("protectFavorites", true),
                severity = p.getInt("severity", 1),
                textSize = p.getInt("textSize", 0),
                langue = p.getInt("langue", 0),
                nightBackup = p.getBoolean("nightBackup", false),
                alerts = p.getBoolean("alerts", true),
                keepMonths = p.getInt("keepMonths", 3),
                sundayClean = p.getBoolean("sundayClean", false),
            )
        }

        fun save(ctx: Context, s: Settings) {
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                .putString("backupUri", s.backupUri)
                .putBoolean("backupBeforeDelete", s.backupBeforeDelete)
                .putBoolean("protectFavorites", s.protectFavorites)
                .putInt("severity", s.severity)
                .putInt("textSize", s.textSize)
                .putInt("langue", s.langue)
                .putBoolean("nightBackup", s.nightBackup)
                .putBoolean("alerts", s.alerts)
                .putInt("keepMonths", s.keepMonths)
                .putBoolean("sundayClean", s.sundayClean)
                .apply()
        }
    }
}
