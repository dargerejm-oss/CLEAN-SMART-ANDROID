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
    /** Sauvegarder automatiquement avant chaque suppression. */
    val backupBeforeDelete: Boolean = true,
    /** Ne jamais proposer les photos favorites. */
    val protectFavorites: Boolean = true,
) {
    val backupTree: Uri? get() = backupUri?.let { Uri.parse(it) }

    // Reglages fixes
    val includeWhatsapp get() = true
    val bigVideoMb get() = 100
    val similarDistance get() = 10
    val burstSeconds get() = 15
    val bigMailMb get() = 5
    val oldMailYears get() = 2

    companion object {
        private const val P = "options"

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            return Settings(
                backupUri = p.getString("backupUri", null),
                backupBeforeDelete = p.getBoolean("backupBeforeDelete", true),
                protectFavorites = p.getBoolean("protectFavorites", true),
            )
        }

        fun save(ctx: Context, s: Settings) {
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                .putString("backupUri", s.backupUri)
                .putBoolean("backupBeforeDelete", s.backupBeforeDelete)
                .putBoolean("protectFavorites", s.protectFavorites)
                .apply()
        }
    }
}
