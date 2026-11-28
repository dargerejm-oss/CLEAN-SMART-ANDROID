package com.jmdargere.cleansmart.data

import com.jmdargere.cleansmart.Tr

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Un groupe de fichiers inutiles.
 * [sur] = vrai : fichiers jetables qui se recreent tout seuls (supprimes directement, coches par defaut).
 * [sur] = faux : a verifier (decoches par defaut) ; ils passent par la corbeille Clean Smart (7 jours).
 */
data class GroupeInutile(
    val cle: String,
    val emoji: String,
    val titre: String,
    val aide: String,
    val fichiers: List<File>,
    val taille: Long,
    val sur: Boolean,
)

/**
 * « Fichiers inutiles » (version 2.0, repris de Nettoyage Pro) : fichiers temporaires, miniatures,
 * dossiers cache, dossiers vides, fichiers d'installation .apk, restes d'applis desinstallees, vieux telechargements.
 * Ne touche jamais aux photos, videos, musiques, ni a la corbeille de la Galerie, ni aux telechargements en cours.
 * Necessite « Acces a tous les fichiers » (le meme que le mode « Tout automatique »).
 */
class Inutiles(private val ctx: Context) {

    private val racine: File = Environment.getExternalStorageDirectory()
    val corbeille = File(racine, ".CleanSmart_corbeille")

    private val JOUR = 86_400_000L
    private val maintenant get() = System.currentTimeMillis()

    /** Dossiers d'Android qu'on ne supprime jamais, meme vides. */
    private val PROTEGES = setOf("android", "dcim", "pictures", "download", "downloads", "music", "movies", "documents", "alarms",
        "notifications", "podcasts", "ringtones", "recordings", "audiobooks", "screenshots", "camera", ".cleansmart_corbeille")
    private val MEDIA = Regex("\\.(jpe?g|png|heic|heif|webp|gif|bmp|dng|mp4|mov|3gp|mkv|webm|mp3|m4a|aac|flac|ogg|wav|opus)$", RegexOption.IGNORE_CASE)
    private val TEMP = Regex("\\.(tmp|temp|log|bak|dmp|chk|old)$", RegexOption.IGNORE_CASE)
    private val TELECH_INACHEVE = Regex("\\.(crdownload|part|partial)$", RegexOption.IGNORE_CASE)
    private val DOCS = Regex("\\.(pdf|docx?|xlsx?|pptx?|odt|ods|txt|epub|zip|rar|7z|csv)$", RegexOption.IGNORE_CASE)

    fun acces(): Boolean = Environment.isExternalStorageManager()

    // ------------------------------------------------------------------ recherche

    fun chercher(progress: (String, Int) -> Unit): List<GroupeInutile> {
        purgerCorbeille()
        val miniatures = ArrayList<File>(); val caches = ArrayList<File>(); val temp = ArrayList<File>()
        val apk = ArrayList<File>(); val vieux = ArrayList<File>(); val vides = ArrayList<File>()

        // 1. tout le stockage partage (sauf Android/, la corbeille Clean Smart et les fichiers caches de la Galerie)
        progress(Tr.t("Recherche des fichiers inutiles…"), 5)
        var vus = 0
        fun parcourir(d: File, profondeur: Int) {
            val enfants = d.listFiles() ?: return
            vus++
            if (vus % 200 == 0) progress(Tr.t("Recherche des fichiers inutiles…"), (5 + vus / 100).coerceAtMost(70))
            for (f in enfants) {
                val n = f.name; val bas = n.lowercase()
                if (f.isDirectory) {
                    if (profondeur == 0 && (bas == "android" || bas == ".cleansmart_corbeille")) continue
                    when {
                        bas == ".thumbnails" -> miniatures += fichiersDe(f)
                        bas in setOf("cache", ".cache", "caches", "tmp", ".tmp", "temp", ".temp") -> caches += fichiersDe(f).filterNot { MEDIA.containsMatchIn(it.name) }
                        else -> parcourir(f, profondeur + 1)
                    }
                } else {
                    if (n.startsWith(".trashed-") || n.startsWith(".pending-")) continue   // corbeille Galerie / en cours
                    val age = maintenant - f.lastModified()
                    when {
                        TEMP.containsMatchIn(n) && age > JOUR -> temp += f
                        TELECH_INACHEVE.containsMatchIn(n) && age > 7 * JOUR -> temp += f      // telechargement abandonne
                        bas.endsWith(".apk") -> apk += f
                        profondeur <= 1 && d.name.lowercase() in setOf("download", "downloads", "documents") &&
                                DOCS.containsMatchIn(n) && age > 180 * JOUR && !n.startsWith("takeout", true) -> vieux += f
                    }
                }
            }
        }
        parcourir(racine, 0)

        // 2. dossiers vides (en partant du fond : un dossier qui ne contient que des dossiers vides est vide aussi)
        progress(Tr.t("Dossiers vides…"), 75)
        fun estVide(d: File, profondeur: Int): Boolean {
            val enfants = d.listFiles() ?: return false
            var vide = true
            for (f in enfants) {
                if (f.isDirectory) {
                    if (profondeur == 0 && f.name.lowercase() in setOf("android", ".cleansmart_corbeille")) { vide = false; continue }
                    if (!estVide(f, profondeur + 1)) vide = false
                } else vide = false
            }
            if (vide && profondeur > 0 && d.name.lowercase() !in PROTEGES) vides += d
            return vide
        }
        estVide(racine, 0)
        // seulement les dossiers vides « du haut » (supprimer le parent supprime les enfants)
        val videsHaut = vides.filter { v -> vides.none { it != v && v.path.startsWith(it.path + File.separator) } }

        // 3. restes d'applis desinstallees (Android/media et Android/obb), pas touches depuis 90 jours
        progress(Tr.t("Restes d'applis désinstallées…"), 85)
        val restes = ArrayList<File>()
        val pm = ctx.packageManager
        for (sous in listOf("Android/media", "Android/obb")) {
            File(racine, sous).listFiles()?.forEach { d ->
                val pkg = d.name
                if (!d.isDirectory || !Regex("^[a-z][\\w]*(\\.[\\w]+)+$").matches(pkg) || pkg.startsWith("com.android") ||
                    pkg.startsWith("com.google.android") || pkg == ctx.packageName) return@forEach
                val installee = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrElse { it !is PackageManager.NameNotFoundException }
                if (!installee && maintenant - dernierChangement(d) > 90 * JOUR) restes += d
            }
        }

        // 4. fichiers .apk : ceux deja installes d'abord dans le groupe « sur »
        val (apkInstalles, apkAutres) = apk.partition { f ->
            val pkg = runCatching { pm.getPackageArchiveInfo(f.path, 0)?.packageName }.getOrNull()
            pkg != null && runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
        progress(Tr.t("Terminé"), 100)

        return listOfNotNull(
            groupe("temp", "🗒️", Tr.t("Fichiers temporaires"), Tr.t("Journaux, sauvegardes temporaires, téléchargements abandonnés. Ne servent plus."), temp, true),
            groupe("mini", "🖼️", Tr.t("Miniatures"), Tr.t("Petites copies des photos pour l'affichage. Elles se recréent toutes seules."), miniatures, true),
            groupe("cache", "🧊", Tr.t("Dossiers cache"), Tr.t("Fichiers de travail des applis, dans le stockage du téléphone. Ils se recréent si besoin."), caches, true),
            groupe("apkok", "📦", Tr.t("Fichiers d'installation déjà installés"), Tr.t("Les .apk d'applis déjà installées : ils ne servent plus."), apkInstalles, true),
            groupe("vides", "📂", Tr.t("Dossiers vides"), Tr.t("Ils ne contiennent rien."), videsHaut, true),
            groupe("apk", "📦", Tr.t("Autres fichiers d'installation (.apk)"), Tr.t("Des applis téléchargées mais pas installées. À vérifier."), apkAutres, false),
            groupe("restes", "👻", Tr.t("Restes d'applis désinstallées"), Tr.t("Dossiers laissés par des applis qui ne sont plus sur le téléphone. À vérifier."), restes, false),
            groupe("vieux", "📥", Tr.t("Vieux téléchargements (plus de 6 mois)"), Tr.t("Documents de Téléchargements jamais retouchés depuis 6 mois. À vérifier."), vieux, false),
        )
    }

    private fun groupe(cle: String, emoji: String, titre: String, aide: String, l: List<File>, sur: Boolean): GroupeInutile? {
        if (l.isEmpty()) return null
        val tri = l.distinctBy { it.path }.sortedByDescending { taille(it) }
        return GroupeInutile(cle, emoji, titre, aide, tri, tri.sumOf { taille(it) }, sur)
    }

    // ------------------------------------------------------------------ nettoyage

    /**
     * Groupes « surs » : supprimes directement. Groupes « a verifier » : deplaces dans la corbeille Clean Smart
     * (meme disque : instantane), videe toute seule apres 7 jours. Renvoie (nombre, octets liberes ou mis de cote).
     */
    fun nettoyer(groupes: List<GroupeInutile>, progress: (String, Int) -> Unit): Pair<Int, Long> {
        val lot = File(corbeille, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date()))
        val tous = groupes.flatMap { g -> g.fichiers.map { it to g.sur } }
        var n = 0; var octets = 0L
        tous.forEachIndexed { i, (f, sur) ->
            if (i % 50 == 0) progress(f.name, 100 * i / tous.size.coerceAtLeast(1))
            if (!f.exists() || !dansRacine(f)) return@forEachIndexed
            val t = taille(f)
            val ok = if (sur) f.deleteRecursively() else {
                val cible = File(lot, f.path.removePrefix(racine.path).trimStart('/'))
                cible.parentFile?.mkdirs(); f.renameTo(cible)
            }
            if (ok) { n++; octets += t }
        }
        return n to octets
    }

    /** Taille de la corbeille Clean Smart (fichiers mis de cote). */
    fun tailleCorbeille(): Long = if (corbeille.exists()) taille(corbeille) else 0L

    /** Remet tout ce qui est dans la corbeille Clean Smart a sa place. */
    fun remettre(): Int {
        var n = 0
        corbeille.listFiles()?.forEach { lot ->
            lot.walkBottomUp().filter { it.isFile || (it.isDirectory && it.listFiles()?.isEmpty() == true) }.forEach { f ->
                val dest = File(racine, f.path.removePrefix(lot.path).trimStart('/'))
                if (!dest.exists()) { dest.parentFile?.mkdirs(); if (f.renameTo(dest)) n++ }
            }
            lot.deleteRecursively()
        }
        return n
    }

    fun viderCorbeille(): Long { val t = tailleCorbeille(); corbeille.deleteRecursively(); return t }

    /** Les lots de plus de 7 jours sont supprimes pour de bon. */
    private fun purgerCorbeille() {
        corbeille.listFiles()?.forEach { if (maintenant - it.lastModified() > 7 * JOUR) it.deleteRecursively() }
    }

    // ------------------------------------------------------------------ outils

    private fun dansRacine(f: File) = f.canonicalPath.startsWith(racine.canonicalPath + File.separator)
    private fun fichiersDe(d: File): List<File> = d.walkTopDown().filter { it.isFile }.toList()
    private fun taille(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun dernierChangement(d: File): Long = d.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
}
