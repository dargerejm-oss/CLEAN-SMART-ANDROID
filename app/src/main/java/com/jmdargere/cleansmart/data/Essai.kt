package com.jmdargere.cleansmart.data

import android.content.Context
import com.jmdargere.cleansmart.BuildConfig

/**
 * Version d'essai : BuildConfig.ESSAI_JOURS = 30 pour l'APK "TEST 30 JOURS", 0 pour la version complete.
 * Le compte a rebours part du premier lancement. Si l'heure du telephone est reculee,
 * on garde la date la plus recente deja vue (on ne peut pas "rajeunir" l'essai).
 */
object Essai {

    private const val JOUR = 24L * 3600 * 1000

    val actif: Boolean get() = BuildConfig.ESSAI_JOURS > 0

    /** Jours restants (0 = termine). Toujours un grand nombre pour la version complete. */
    fun joursRestants(ctx: Context): Int {
        if (!actif) return Int.MAX_VALUE
        val p = ctx.getSharedPreferences("essai", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val debut = p.getLong("debut", 0L).takeIf { it > 0 } ?: now.also { p.edit().putLong("debut", it).apply() }
        val vu = maxOf(now, p.getLong("vu", 0L))
        p.edit().putLong("vu", vu).apply()
        val passes = ((vu - debut) / JOUR).toInt()
        return (BuildConfig.ESSAI_JOURS - passes).coerceAtLeast(0)
    }
}
