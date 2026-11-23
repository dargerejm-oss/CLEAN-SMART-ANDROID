package com.jmdargere.cleansmart

import java.util.Locale

/**
 * Traduction de l'interface. Les textes sont ecrits en francais dans le code ;
 * si le telephone n'est pas en francais (ou si l'option Langue = English), on affiche l'anglais.
 *   Tr.t("Bonjour")                      -> "Hello"
 *   Tr.t("%1 photos trouvées", 12)       -> "12 photos found"     (%1, %2... = valeurs)
 * Un texte sans traduction reste en francais (jamais de texte vide).
 */
object Tr {

    /** null = suivre la langue du telephone, "fr" ou "en" = choix force dans les options. */
    @Volatile var choix: String? = null

    val anglais: Boolean
        get() = when (choix) {
            "fr" -> false
            "en" -> true
            else -> Locale.getDefault().language != "fr"
        }

    fun t(fr: String, vararg args: Any?): String {
        var s = if (anglais) (TrEn.map[fr] ?: fr) else fr
        args.forEachIndexed { i, a -> s = s.replace("%${i + 1}", a.toString()) }
        return s
    }
}
