package com.jmdargere.cleansmart

import android.content.Context
import java.util.Locale

/**
 * Traduction de l'interface en 16 langues (comme Photo Simple). Les textes sont ecrits en francais dans le code :
 *   Tr.t("Bonjour")                      -> "Hello", "Hola", "Γεια σας"...
 *   Tr.t("%1 photos trouvées", 12)       -> "12 photos found"     (%1, %2... = valeurs)
 * Anglais : TrEn.kt. Autres langues : assets/i18n/<code>.txt (lignes « francais<TAB>traduction », \n = retour a la ligne),
 * fabriques par store/i18n/gen_langues.py. Sans traduction : l'anglais, sinon le francais (jamais de texte vide).
 */
object Tr {

    val CODES = listOf("fr", "en", "es", "pt", "de", "it", "th", "hi", "zh", "ar", "id", "ja", "ko", "tr", "vi", "el")
    val NOMS = listOf("Français", "English", "Español", "Português", "Deutsch", "Italiano", "ไทย", "हिन्दी", "中文", "العربية",
        "Bahasa Indonesia", "日本語", "한국어", "Türkçe", "Tiếng Việt", "Ελληνικά")

    /** null = suivre la langue du telephone ; sinon un des CODES, choisi dans les options. */
    @Volatile var choix: String? = null

    private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }

    /** La langue affichee. Telephone dans une langue non prise en charge : anglais. */
    val langue: String
        get() = choix ?: Locale.getDefault().language.let { l -> if (l in CODES) l else if (l == "in") "id" else "en" }

    /** Vrai si l'interface n'est pas en francais (unites GB/MB, etc.). */
    val anglais: Boolean get() = langue != "fr"

    /** L'arabe s'ecrit de droite a gauche. */
    val rtl: Boolean get() = langue == "ar"

    /** Pour les nombres et les dates. */
    val locale: Locale get() = when (langue) { "fr" -> Locale.FRANCE; "en" -> Locale.UK; else -> Locale.forLanguageTag(langue) }

    private val tables = HashMap<String, Map<String, String>>()

    private fun table(code: String): Map<String, String> = synchronized(tables) {
        tables.getOrPut(code) {
            val m = HashMap<String, String>(1200)
            runCatching {
                ctx?.assets?.open("i18n/$code.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lignes ->
                    for (l in lignes) {
                        val i = l.indexOf('\t'); if (i <= 0) continue
                        val v = l.substring(i + 1).replace("\\n", "\n")
                        if (v.isNotBlank()) m[l.substring(0, i).replace("\\n", "\n")] = v
                    }
                }
            }
            m
        }
    }

    fun t(fr: String, vararg args: Any?): String {
        val l = langue
        var s = when (l) {
            "fr" -> fr
            "en" -> TrEn.map[fr] ?: fr
            else -> table(l)[fr] ?: TrEn.map[fr] ?: fr
        }
        args.forEachIndexed { i, a -> s = s.replace("%${i + 1}", a.toString()) }
        return s
    }
}
