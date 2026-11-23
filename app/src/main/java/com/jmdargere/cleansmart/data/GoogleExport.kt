package com.jmdargere.cleansmart.data

import com.jmdargere.cleansmart.Tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Bilan d'un fichier ZIP traite (ou du total). */
data class ExportStats(
    val photos: Int = 0,
    val videos: Int = 0,
    val doublons: Int = 0,
    val rafales: Int = 0,
    val mails: Int = 0,
    val octets: Long = 0,
    val zips: Int = 0,
    val erreurs: Int = 0,
    /** Le ZIP est abime ou incomplet (telechargement coupe) : a retelecharger. */
    val abime: Boolean = false,
    /** Le disque (ou le telephone) est plein : arret propre. */
    val plein: Boolean = false,
) {
    /** Tout s'est bien passe : on peut supprimer le ZIP du telephone sans risque. */
    val sansProbleme get() = erreurs == 0 && !abime && !plein

    operator fun plus(o: ExportStats) = ExportStats(photos + o.photos, videos + o.videos, doublons + o.doublons,
        rafales + o.rafales, mails + o.mails, octets + o.octets, zips + o.zips, erreurs + o.erreurs)
}

/** Un disque branche (cle USB, disque dur, carte SD) et sa place libre. */
data class Disque(val nom: String, val libre: Long, val total: Long, val amovible: Boolean)

/** Etat d'un fichier de l'export Google (001, 002...). */
enum class EtatZip { A_FAIRE, OK, A_REPRENDRE }

/** D'ou viennent les photos : Google Photos (Google Takeout) ou iCloud Photos (export Apple). */
enum class SourceExport(val prefs: String, val empreintes: String, val dossierPhotos: String, val nom: String) {
    GOOGLE("google_photos", "google_photos_hashes.txt", "Google Photos", "Google Photos"),
    ICLOUD("icloud_photos", "icloud_photos_hashes.txt", "iCloud Photos", "iCloud Photos"),
}

/**
 * « Quitter Google Photos » : on lit l'export officiel Google Takeout (fichiers ZIP de 2 Go),
 * un fichier a la fois, et on copie sur le disque choisi :
 *
 *   <disque>/Google Photos/2019/2019-07-14_IMG_1234.jpg     (range par annee)
 *   <disque>/Mails Gmail/2019/2019-07-14_Objet.eml           (si l'export contient Gmail)
 *   <disque>/Mails Gmail/PDF/...                              (tous les PDF reunis)
 *
 * - les doublons (meme contenu, deja copie) ne sont pas recopies ;
 * - dans une rafale, seule la photo la plus nette est gardee ;
 * - chaque fichier copie est RELU et compare a l'original avant de compter comme sauvegarde ;
 * - l'empreinte de chaque fichier verifie est memorisee tout de suite : une reprise ne recopie rien.
 * Rien n'est jamais supprime dans Google Photos par l'appli.
 */
class GoogleExport(private val ctx: Context, val source: SourceExport = SourceExport.GOOGLE) {

    private val cr = ctx.contentResolver
    private val hashFile = File(ctx.filesDir, source.empreintes)
    private val prefs = ctx.getSharedPreferences(source.prefs, Context.MODE_PRIVATE)

    private val MEDIA = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng", "raf", "cr2", "nef", "arw",
        "mp4", "mov", "3gp", "m4v", "mkv", "avi", "webm")
    private val VIDEO = setOf("mp4", "mov", "3gp", "m4v", "mkv", "avi", "webm")
    private val BUF = 1 shl 20    // morceaux de 1 Mo : bien plus rapide sur une cle USB

    // ------------------------------------------------------------------ etat conserve entre deux fichiers

    var destination: Uri?
        get() = prefs.getString("dest", null)?.let { Uri.parse(it) }
        set(v) { prefs.edit().putString("dest", v?.toString()).apply() }

    var tailleGoogleGo: Int
        get() = prefs.getInt("tailleGo", 0)
        set(v) { prefs.edit().putInt("tailleGo", v).apply() }

    /** Nombre de fichiers annonces dans le mail de Google (0 = pas encore indique). */
    var nbFichiers: Int
        get() = prefs.getInt("nbFichiers", 0)
        set(v) { prefs.edit().putInt("nbFichiers", v).apply() }

    var exportDemande: Boolean
        get() = prefs.getBoolean("exportDemande", false)
        set(v) { prefs.edit().putBoolean("exportDemande", v).apply() }

    var termine: Boolean
        get() = prefs.getBoolean("termine", false)
        set(v) { prefs.edit().putBoolean("termine", v).apply() }

    /** Etat de chaque fichier, par son numero (takeout-...-001.zip -> 1). */
    fun etats(): Map<Int, EtatZip> = (prefs.getString("etats", "") ?: "").split(';').mapNotNull {
        val p = it.split('=')
        if (p.size == 2) p[0].toIntOrNull()?.let { n -> n to (runCatching { EtatZip.valueOf(p[1]) }.getOrNull() ?: EtatZip.A_FAIRE) } else null
    }.toMap()

    private fun noterEtat(n: Int, e: EtatZip) {
        val m = etats().toMutableMap(); m[n] = e
        prefs.edit().putString("etats", m.entries.joinToString(";") { "${it.key}=${it.value.name}" }).apply()
    }

    /**
     * Le numero du fichier d'apres son nom, 0 si inconnu :
     *   Google : takeout-20261123T101010Z-003.zip -> 3
     *   Apple  : iCloud Photos Part 3 of 12.zip   -> 3
     */
    fun numero(nom: String): Int =
        Regex("Part (\\d+) of (\\d+)", RegexOption.IGNORE_CASE).find(nom)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("-(\\d{1,3})\\.zip$", RegexOption.IGNORE_CASE).find(nom)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Apple ecrit le nombre total dans le nom (Part 3 of 12) : on le reprend tout seul. */
    fun totalDansNom(nom: String): Int =
        Regex("Part (\\d+) of (\\d+)", RegexOption.IGNORE_CASE).find(nom)?.groupValues?.get(2)?.toIntOrNull() ?: 0

    fun total(): ExportStats = ExportStats(
        prefs.getInt("photos", 0), prefs.getInt("videos", 0), prefs.getInt("doublons", 0), prefs.getInt("rafales", 0),
        prefs.getInt("mails", 0), prefs.getLong("octets", 0), prefs.getInt("zips", 0), prefs.getInt("erreurs", 0))

    private fun ajouter(s: ExportStats) {
        val t = total() + s
        prefs.edit().putInt("photos", t.photos).putInt("videos", t.videos).putInt("doublons", t.doublons)
            .putInt("rafales", t.rafales).putInt("mails", t.mails).putLong("octets", t.octets)
            .putInt("zips", t.zips).putInt("erreurs", t.erreurs).apply()
    }

    /** Recommencer depuis le debut (nouveau compte ou nouveau disque). */
    fun remettreAZero() {
        prefs.edit().clear().apply()
        hashFile.delete()
    }

    // ------------------------------------------------------------------ place

    /** Place a prevoir : la taille Google Photos + 10 % de marge (les doublons ecartes en feront gagner). */
    fun placeNecessaire(): Long = (tailleGoogleGo * 1.1 * (1L shl 30)).toLong()

    /** Part de la phototheque deja a l'abri (0..1), d'apres la taille annoncee par Google. */
    fun avancement(): Float = if (tailleGoogleGo > 0) (total().octets.toFloat() / (tailleGoogleGo * (1L shl 30))).coerceIn(0f, 1f) else 0f

    /** Les disques branches (cle USB, disque dur, carte SD) et leur place libre. */
    fun disques(): List<Disque> {
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            runCatching {
                val st = StatFs(dir.path)
                Disque(v.getDescription(ctx), st.availableBytes, st.totalBytes, v.isRemovable)
            }.getOrNull()
        }
    }

    /** Place libre a l'endroit choisi (si Android sait la donner). */
    fun libreDestination(): Long? {
        val d = destination ?: return null
        return runCatching {
            val volume = DocumentsContract.getTreeDocumentId(d).substringBefore(':')
            val sm = ctx.getSystemService(StorageManager::class.java)
            val v = sm.storageVolumes.firstOrNull { (if (it.isPrimary) "primary" else it.uuid) == volume } ?: return null
            StatFs(v.directory!!.path).availableBytes
        }.getOrNull()
    }

    fun destinationOk(): Boolean = destination?.let { DocumentFile.fromTreeUri(ctx, it) }?.let { it.exists() && it.canWrite() } ?: false

    fun nomDestination(): String = destination?.let { DocumentFile.fromTreeUri(ctx, it)?.name } ?: "Aucun dossier choisi"

    /** Place libre sur le telephone. */
    fun libreTelephone(): Long = runCatching { StatFs(ctx.cacheDir.path).availableBytes }.getOrDefault(0L)

    fun tailleDe(uri: Uri): Long = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    fun nomDe(uri: Uri): String = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: "fichier.zip"

    /**
     * Verifications AVANT de commencer un ZIP. Retourne null si tout va bien,
     * sinon le probleme et ce qu'il faut faire (on ne commence pas).
     */
    fun verifierAvant(zip: Uri): String? {
        if (destination == null) return Tr.t("Choisis d'abord le dossier sur ta clé ou ton disque (étape « Le disque »).")
        if (!destinationOk())
            return Tr.t("Le disque n'est pas accessible.\n\n• Vérifie qu'il est bien branché (adaptateur USB-C).\n• Dans l'étape « Le disque », choisis le dossier à nouveau.")
        if (libreTelephone() < 300L * 1024 * 1024)
            return Tr.t("Le téléphone est presque plein (il faut au moins 300 Mo libres pour travailler).\n\n• Vide la corbeille de la Galerie.\n• Supprime les fichiers ZIP déjà traités (Téléchargements).\n• Fais le Ménage guidé de Clean Smart.\n\nRien n'a été touché.")
        val taille = tailleDe(zip)
        val libre = libreDestination()
        if (libre != null && taille > 0 && libre < taille + 200L * 1024 * 1024)
            return Tr.t("Le disque n'a plus assez de place : il reste %1, il faut environ %2 pour ce fichier.\n\n• Libère de la place sur le disque, ou\n• branche un disque plus grand et choisis-le dans l'étape « Le disque » (on reprendra là où on en est).\n\nRien n'a été touché.", fmtBytes(libre), fmtBytes(taille))
        return null
    }

    // ------------------------------------------------------------------ traitement d'un ZIP

    private class Rafale(val doc: DocumentFile, val date: Long, val hash: Long, val nettete: Long)

    /** Tout ce qu'il faut pour traiter les fichiers d'un ZIP, quelle que soit la facon de le lire. */
    private inner class Lot(val racine: DocumentFile, val dates: Map<String, Long>) {
        val vus = chargerHashes()
        val photosDir: DocumentFile by lazy { dossier(racine, source.dossierPhotos) }
        /** dossier de chaque annee + les noms deja presents (garde en memoire : pas de relecture du dossier) */
        val annees = HashMap<String, Pair<DocumentFile, HashSet<String>>>()
        val rafales = ArrayList<Rafale>()
        val calcul = Executors.newSingleThreadExecutor()   // la nettete se calcule pendant la copie suivante
        val enAttente = ArrayList<Future<*>>()
        val tmpN = AtomicInteger()
        var photos = 0; var videos = 0; var doublons = 0; var mails = 0; var octets = 0L; var erreurs = 0
        var abime = false; var plein = false

        fun dossierAnnee(date: Long): Pair<DocumentFile, HashSet<String>> = annees.getOrPut(annee(date)) {
            val d = dossier(photosDir, annee(date))
            d to d.listFiles().mapNotNull { it.name }.toHashSet()
        }

        fun nomLibre(noms: HashSet<String>, nom: String): String {
            var n = nom
            if (n in noms) {
                val base = nom.substringBeforeLast('.'); val ext = nom.substringAfterLast('.', "")
                var k = 2
                while ("${base}_${k}.${ext}" in noms) k++
                n = "${base}_${k}.${ext}"
            }
            noms += n
            return n
        }

        /** Traite UN fichier du ZIP. Les erreurs remontent a l'appelant. */
        fun entree(chemin: String, input: InputStream, progress: (String) -> Unit) {
            val nom = chemin.substringAfterLast('/')
            val ext = nom.substringAfterLast('.', "").lowercase()
            var enCours: DocumentFile? = null      // fichier en cours d'ecriture (efface s'il est incomplet)
            var tmp: File? = null
            try {
                when {
                    ext in VIDEO -> {
                        progress(resume())
                        // les videos vont DIRECTEMENT sur le disque (pas de place prise sur le telephone)
                        val date = dateDe(dates, chemin, nom) ?: 0L
                        val (dir, noms) = dossierAnnee(date)
                        val doc = dir.createFile(mime(ext), nomLibre(noms, jour(date) + nom)) ?: throw IllegalStateException("Écriture impossible")
                        enCours = doc
                        val md = MessageDigest.getInstance("MD5")
                        var n = 0L
                        cr.openOutputStream(doc.uri)!!.use { o ->
                            val buf = ByteArray(BUF)
                            while (true) { val k = input.read(buf); if (k <= 0) break; md.update(buf, 0, k); o.write(buf, 0, k); n += k }
                        }
                        val h = hex(md.digest())
                        when {
                            h in vus -> { doc.delete(); noms -= doc.name ?: ""; doublons++ }
                            empreinte(doc.uri) != h -> { doc.delete(); erreurs++ }     // verification : on relit la copie
                            else -> { vus += h; memoriser(h); octets += n; videos++ }
                        }
                        enCours = null
                    }
                    ext in MEDIA -> {
                        progress(resume())
                        // une photo passe par une petite copie temporaire (date, nettete)
                        val t = File(ctx.cacheDir, "gp_${tmpN.incrementAndGet()}"); tmp = t
                        val md = MessageDigest.getInstance("MD5")
                        t.outputStream().use { o ->
                            val buf = ByteArray(BUF)
                            while (true) { val k = input.read(buf); if (k <= 0) break; md.update(buf, 0, k); o.write(buf, 0, k) }
                        }
                        val h = hex(md.digest())
                        if (h in vus) { doublons++; t.delete(); return }
                        val date = dateDe(dates, chemin, nom) ?: dateExif(t) ?: 0L
                        val (dir, noms) = dossierAnnee(date)
                        val doc = dir.createFile(mime(ext), nomLibre(noms, jour(date) + nom)) ?: throw IllegalStateException("Écriture impossible")
                        enCours = doc
                        cr.openOutputStream(doc.uri)!!.use { o -> t.inputStream().use { it.copyTo(o, BUF) } }
                        if (empreinte(doc.uri) != h) { doc.delete(); erreurs++; t.delete(); enCours = null; return }
                        enCours = null
                        vus += h; memoriser(h)          // memorise tout de suite : une reprise ne recopiera pas
                        octets += t.length()
                        photos++
                        // la nettete est calculee en arriere-plan (au plus 4 photos en attente)
                        while (enAttente.size >= 4) enAttente.removeAt(0).get()
                        tmp = null
                        enAttente += calcul.submit {
                            signature(t)?.let { (dh, net) -> synchronized(rafales) { rafales += Rafale(doc, date, dh, net) } }
                            t.delete()
                        }
                    }
                    ext == "mbox" -> mails += lireMbox(input, racine) { k -> progress(Tr.t("Mails Gmail : %1", k)) }
                }
            } catch (x: Exception) {
                // fichier a moitie ecrit : on l'efface, rien de faux ne reste sur le disque
                runCatching { enCours?.delete() }
                tmp?.delete()
                throw x
            }
        }

        fun resume() = Tr.t("Photos : %1 · vidéos : %2 · doublons écartés : %3", photos, videos, doublons)

        /** Gere une erreur : retourne true s'il faut arreter de lire ce ZIP. */
        fun erreur(x: Exception): Boolean = when {
            estPlein(x) -> { plein = true; true }
            x is java.util.zip.ZipException || x is java.io.EOFException -> { abime = true; true }
            else -> { erreurs++; false }
        }

        /** Rafales : dans chaque serie (photos a moins de 15 s, qui se ressemblent), on garde la plus nette. */
        fun finir(): ExportStats {
            enAttente.forEach { runCatching { it.get() } }
            calcul.shutdown()
            var ecartees = 0
            val triees = synchronized(rafales) { rafales.filter { it.date > 0 }.sortedBy { it.date } }
            var i = 0
            while (i < triees.size) {
                var j = i + 1
                while (j < triees.size && triees[j].date - triees[j - 1].date <= 15_000) j++
                if (j - i > 1) {
                    val groupes = ArrayList<MutableList<Rafale>>()
                    for (r in triees.subList(i, j)) {
                        val g = groupes.firstOrNull { java.lang.Long.bitCount(it[0].hash xor r.hash) <= 10 }
                        if (g != null) g += r else groupes += mutableListOf(r)
                    }
                    for (g in groupes) if (g.size > 1) {
                        val best = g.maxBy { it.nettete }
                        g.filter { it !== best }.forEach { if (it.doc.delete()) { ecartees++; photos-- } }
                    }
                }
                i = j
            }
            return ExportStats(photos, videos, doublons, ecartees, mails, octets, if (abime || plein) 0 else 1, erreurs, abime, plein)
        }
    }

    fun traiterZip(zip: Uri, progress: (String, Int) -> Unit): ExportStats {
        verifierAvant(zip)?.let { throw IllegalStateException(it) }
        val racine = DocumentFile.fromTreeUri(ctx, destination!!)!!
        val num = numero(nomDe(zip))

        // Methode rapide : on lit la table des matieres du ZIP, puis chaque fichier une seule fois
        val s = runCatching { traiterRapide(zip, racine, progress) }.getOrElse { x ->
            if (x is IllegalStateException && x.message?.startsWith("PLEIN") == true) throw x
            // ZIP sans table des matieres (telechargement coupe) : lecture pas a pas, plus tolerante
            traiterPasAPas(zip, racine, progress)
        }
        progress(Tr.t("Terminé"), 100)
        ajouter(s)
        if (num > 0) noterEtat(num, if (s.sansProbleme) EtatZip.OK else EtatZip.A_REPRENDRE)
        return s
    }

    private fun traiterRapide(zip: Uri, racine: DocumentFile, progress: (String, Int) -> Unit): ExportStats {
        val pfd = cr.openFileDescriptor(zip, "r") ?: throw IllegalStateException(Tr.t("Fichier illisible"))
        pfd.use {
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val zf = org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(ch).get()
                zf.use {
                    val toutes = java.util.Collections.list(zf.entries)
                    // 1. les dates (petits fichiers .json), lues directement
                    progress(Tr.t("Lecture des dates des photos…"), 2)
                    val dates = HashMap<String, Long>()
                    for (e in toutes) if (!e.isDirectory && estMeta(e.name)) runCatching {
                        zf.getInputStream(e).use { lireDate(e.name, it.readBytes(), dates) }
                    }
                    // 2. les photos, videos et mails, dans l'ordre du fichier (lecture continue = rapide)
                    val lot = Lot(racine, dates)
                    val total = toutes.sumOf { it.compressedSize.coerceAtLeast(0) }.coerceAtLeast(1)
                    var lu = 0L
                    for (e in java.util.Collections.list(zf.entriesInPhysicalOrder)) {
                        if (e.isDirectory || estMeta(e.name)) { lu += e.compressedSize.coerceAtLeast(0); continue }
                        val pct = (5 + 85 * lu / total).toInt().coerceIn(5, 90)
                        try {
                            zf.getInputStream(e).use { inp -> lot.entree(e.name, inp) { txt -> progress(txt, pct) } }
                        } catch (x: Exception) {
                            if (lot.erreur(x)) break
                        }
                        lu += e.compressedSize.coerceAtLeast(0)
                    }
                    progress(Tr.t("Rafales : on garde la meilleure…"), 92)
                    return lot.finir()
                }
            }
        }
    }

    /** Lecture pas a pas (ZIP incomplet ou abime) : on recupere tout ce qui est lisible. */
    private fun traiterPasAPas(zip: Uri, racine: DocumentFile, progress: (String, Int) -> Unit): ExportStats {
        val total = tailleDe(zip).coerceAtLeast(1)
        progress(Tr.t("Lecture des dates des photos…"), 1)
        val dates = HashMap<String, Long>()
        ZipInputStream(cr.openInputStream(zip)!!.buffered(BUF)).use { z ->
            while (true) {
                val e = runCatching { z.nextEntry }.getOrNull() ?: break
                if (!e.isDirectory && estMeta(e.name)) runCatching { lireDate(e.name, z.readBytes(), dates) }
            }
        }
        val lot = Lot(racine, dates)
        val lu = CountingStream(cr.openInputStream(zip)!!)
        ZipInputStream(lu.buffered(BUF)).use { z ->
            while (!lot.plein) {
                val suivant: java.util.zip.ZipEntry? = try { z.nextEntry } catch (x: Exception) { lot.abime = true; null }
                val e = suivant ?: break
                if (e.isDirectory) continue
                val pct = (5 + 85 * lu.count / total).toInt().coerceIn(5, 90)
                try {
                    lot.entree(e.name, z) { txt -> progress(txt, pct) }
                } catch (x: Exception) {
                    if (lot.erreur(x)) break
                }
            }
        }
        progress(Tr.t("Rafales : on garde la meilleure…"), 92)
        return lot.finir()
    }

    /** Fichier de description des photos : .json (Google) ou « Photo Details.csv » (Apple). */
    private fun estMeta(nom: String) = nom.endsWith(".json", true) ||
            (nom.endsWith(".csv", true) && nom.substringAfterLast('/').startsWith("Photo Details", true))

    private val dateApple = listOf(
        SimpleDateFormat("EEEE MMMM d,yyyy h:mm a z", Locale.US),
        SimpleDateFormat("EEEE MMMM d, yyyy h:mm a z", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
    )

    private fun lireDate(chemin: String, bytes: ByteArray, dates: HashMap<String, Long>) {
        if (chemin.endsWith(".csv", true)) { lireCsvApple(bytes, dates); return }
        val js = JSONObject(bytes.toString(Charsets.UTF_8))
        val titre = js.optString("title")
        val ts = js.optJSONObject("photoTakenTime")?.optString("timestamp")?.toLongOrNull()
        if (titre.isNotEmpty() && ts != null && ts > 0) {
            dates[titre.lowercase()] = ts * 1000
            dates[(chemin.substringBeforeLast('/') + "/" + titre).lowercase()] = ts * 1000
        }
    }

    /**
     * Export Apple : « Photo Details.csv » = une ligne par photo :
     * imgName,fileChecksum,favorite,hidden,deleted,originalCreationDate,viewCount,importDate
     */
    private fun lireCsvApple(bytes: ByteArray, dates: HashMap<String, Long>) {
        val lignes = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        if (lignes.isEmpty()) return
        val entete = decouper(lignes[0]).map { it.trim().lowercase() }
        val iNom = entete.indexOf("imgname").takeIf { it >= 0 } ?: 0
        val iDate = entete.indexOf("originalcreationdate").takeIf { it >= 0 } ?: return
        for (l in lignes.drop(1)) {
            val c = decouper(l)
            val nom = c.getOrNull(iNom)?.trim() ?: continue
            val txt = c.getOrNull(iDate)?.trim() ?: continue
            val ms = dateApple.firstNotNullOfOrNull { f -> runCatching { f.parse(txt)?.time }.getOrNull() } ?: continue
            dates[nom.lowercase()] = ms
        }
    }

    /** Decoupe une ligne CSV (les champs entre guillemets peuvent contenir des virgules). */
    private fun decouper(l: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var guil = false
        for (ch in l) {
            when {
                ch == '"' -> guil = !guil
                ch == ',' && !guil -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }

    /** Essaie de supprimer le ZIP du telephone une fois traite (pour faire de la place au suivant). */
    fun supprimerZip(zip: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(cr, zip) }.getOrDefault(false)

    private fun dateDe(dates: Map<String, Long>, chemin: String, nom: String): Long? =
        dates[chemin.lowercase()] ?: dates[(chemin.substringBeforeLast('/') + "/" + nom).lowercase()]
            ?: dates[nom.lowercase()] ?: dates[nom.replace(Regex("\\(\\d+\\)"), "").lowercase()]

    private fun annee(date: Long) = if (date > 0) SimpleDateFormat("yyyy", Locale.FRANCE).format(Date(date)) else "Sans date"
    private fun jour(date: Long) = if (date > 0) SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date(date)) + "_" else ""

    /** "No space left on device" : le disque ou le telephone est plein. */
    private fun estPlein(x: Throwable): Boolean {
        var t: Throwable? = x
        while (t != null) {
            val m = (t.message ?: "").lowercase()
            if ("enospc" in m || "no space" in m || "plus d'espace" in m) return true
            t = t.cause
        }
        return false
    }

    /** Conseils si ca bloque (affiches dans l'appli, traduits a chaque affichage). */
    val conseils: List<Pair<String, String>> get() = listOf(
        "📱" to "Téléphone plein : vide la corbeille de la Galerie, supprime les ZIP déjà traités, fais le Ménage guidé. Il faut au moins 300 Mo libres.",
        "📦" to (if (source == SourceExport.GOOGLE) "Dans Google Takeout, choisis des fichiers de 2 Go : on les traite un par un, le téléphone n'a jamais besoin de beaucoup de place."
                 else "Dans la demande Apple, choisis des fichiers de 1 ou 2 Go : on les traite un par un, le téléphone n'a jamais besoin de beaucoup de place."),
        "⬇️" to "Télécharge UN seul ZIP à la fois, en Wi-Fi. Traite-le, supprime-le, puis télécharge le suivant.",
        "💾" to "Disque plein : branche un disque plus grand et choisis-le dans l'étape « Le disque ». On reprend là où on en était, sans doublon.",
        "🔌" to "Disque non reconnu : utilise un adaptateur USB-C (OTG). Une clé formatée en exFAT marche partout (FAT32 refuse les fichiers de plus de 4 Go).",
        "🔋" to "Branche le chargeur pendant le traitement : un gros fichier peut prendre 10 à 30 minutes. L'écran reste allumé.",
        "⚠️" to "« Fichier abîmé » : le téléchargement a été coupé. Retélécharge ce ZIP depuis le mail de Google et relance-le : ce qui est déjà copié ne sera pas recopié.",
        "🔁" to "Le téléphone s'est éteint ou le disque s'est débranché ? Relance simplement le même ZIP : Clean Smart reprend sans rien perdre ni doubler.",
        "⏳" to (if (source == SourceExport.GOOGLE) "Le lien du mail de Google expire au bout d'environ une semaine : si c'est le cas, refais une demande d'export (rien n'est perdu, on continue sans doublon)."
                 else "Les fichiers d'Apple restent disponibles environ 2 semaines sur privacy.apple.com : si c'est passé, refais une demande (rien n'est perdu, on continue sans doublon)."),
    ).map { (e, t) -> e to com.jmdargere.cleansmart.Tr.t(t) }

    // ------------------------------------------------------------------ mails (export Gmail au format .mbox)

    private fun lireMbox(input: InputStream, racine: DocumentFile, progress: (Int) -> Unit): Int {
        val session = Session.getInstance(Properties())
        val mailsDir = dossier(racine, "Mails Gmail")
        val pdfDir by lazy { dossier(mailsDir, "PDF") }
        val pdfNoms by lazy { pdfDir.listFiles().mapNotNull { it.name }.toHashSet() }
        val annees = HashMap<String, Pair<DocumentFile, HashSet<String>>>()
        val index = ArrayList<SavedMail>()
        var n = 0
        val courant = ByteArrayOutputStream()

        fun libre(noms: HashSet<String>, nom: String): String {
            var r = nom
            if (r in noms) { val b = nom.substringBeforeLast('.'); val e = nom.substringAfterLast('.', ""); var k = 2; while ("${b}_${k}.${e}" in noms) k++; r = "${b}_${k}.${e}" }
            noms += r; return r
        }

        fun enregistrer() {
            if (courant.size() == 0) return
            runCatching {
                val msg = MimeMessage(session, ByteArrayInputStream(courant.toByteArray()))
                val d = msg.sentDate ?: msg.receivedDate ?: Date(0)
                val annee = if (d.time > 0) SimpleDateFormat("yyyy", Locale.FRANCE).format(d) else "Sans date"
                val jour = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(d)
                val (dir, noms) = annees.getOrPut(annee) { dossier(mailsDir, annee).let { it to it.listFiles().mapNotNull { f -> f.name }.toHashSet() } }
                val nom = libre(noms, "${jour}_" + propre(msg.subject ?: "sans objet").take(60) + ".eml")
                dir.createFile("message/rfc822", nom)?.let { f -> cr.openOutputStream(f.uri)?.use { courant.writeTo(it) } }
                val fichiers = ArrayList<String>()
                if (msg.isMimeType("multipart/*")) attachments(msg.content as Multipart) { p ->
                    val pn = "${jour}_" + propre(p.fileName)
                    if (pn.endsWith(".pdf", true) || p.isMimeType("application/pdf")) {
                        val cible = libre(pdfNoms, pn)
                        pdfDir.createFile("application/pdf", cible)?.let { f ->
                            cr.openOutputStream(f.uri)?.use { o -> p.inputStream.use { it.copyTo(o) } }
                        }
                        fichiers += "PDF/${cible}"
                    }
                }
                val ia = msg.from?.firstOrNull() as? InternetAddress
                index += SavedMail(d.time, ia?.personal ?: ia?.address ?: "", msg.subject ?: "", "${annee}/${nom}", fichiers, 0)
                n++
                if (n % 50 == 0) progress(n)
            }
            courant.reset()
        }

        // un fichier .mbox = des mails les uns a la suite des autres, chacun commence par une ligne "From "
        val br = input.bufferedReader(Charsets.ISO_8859_1)
        while (true) {
            val ligne = br.readLine() ?: break
            if (ligne.startsWith("From ")) { enregistrer(); continue }
            courant.write(ligne.toByteArray(Charsets.ISO_8859_1)); courant.write('\n'.code)
        }
        enregistrer()
        runCatching { ecrireIndexMails(mailsDir, index) }
        return n
    }

    /** Archive consultable sur le PC : Mails Gmail/index.html. */
    private fun ecrireIndexMails(dir: DocumentFile, mails: List<SavedMail>) {
        val arr = org.json.JSONArray()
        mails.sortedByDescending { it.date }.forEach { m ->
            arr.put(JSONObject().put("date", m.date).put("from", m.from).put("subject", m.subject)
                .put("eml", m.eml).put("files", org.json.JSONArray(m.files)))
        }
        val html = Backup.indexHtml(arr.toString())
        val f = dir.findFile("index.html") ?: dir.createFile("text/html", "index.html") ?: return
        cr.openOutputStream(f.uri, "wt")?.use { it.write(html.toByteArray(Charsets.UTF_8)) }
    }

    // ------------------------------------------------------------------ outils

    private fun dossier(parent: DocumentFile, nom: String): DocumentFile =
        parent.findFile(nom)?.takeIf { it.isDirectory } ?: parent.createDirectory(nom)
        ?: throw IllegalStateException(Tr.t("Impossible de créer le dossier %1", nom))

    private fun propre(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().ifEmpty { "sans_nom" }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun mime(ext: String) = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "heic", "heif" -> "image/heif"; "webp" -> "image/webp"
        "gif" -> "image/gif"; "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; else -> "application/octet-stream"
    }

    private fun dateExif(f: File): Long? = runCatching {
        val s = ExifInterface(f.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time
    }.getOrNull()

    private fun empreinte(uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("MD5")
        cr.openInputStream(uri)!!.use { i -> val b = ByteArray(BUF); while (true) { val n = i.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        hex(md.digest())
    }.getOrNull()

    /** Empreinte visuelle (dHash) et nettete d'une photo, a partir d'une petite version. */
    private fun signature(f: File): Pair<Long, Long>? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 256 && o.outHeight / (s * 2) >= 256) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) { h = h shl 1; if (gris(small.getPixel(x, y)) > gris(small.getPixel(x + 1, y))) h = h or 1L }
        val g = Bitmap.createScaledBitmap(bmp, 96, 96, true)
        val px = IntArray(96 * 96); g.getPixels(px, 0, 96, 0, 0, 96, 96)
        var sum = 0.0; var sum2 = 0.0; var c = 0
        for (y in 1 until 95) for (x in 1 until 95) {
            val lap = gris(px[(y - 1) * 96 + x]) + gris(px[(y + 1) * 96 + x]) + gris(px[y * 96 + x - 1]) + gris(px[y * 96 + x + 1]) - 4.0 * gris(px[y * 96 + x])
            sum += lap; sum2 += lap * lap; c++
        }
        small.recycle(); g.recycle(); bmp.recycle()
        val m = sum / c
        h to (sum2 / c - m * m).toLong()
    }.getOrNull()

    private fun gris(c: Int) = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000

    private fun chargerHashes(): HashSet<String> =
        if (hashFile.exists()) hashFile.readLines().filter { it.isNotBlank() }.toHashSet() else HashSet()

    /** Memorise l'empreinte d'un fichier copie et verifie (tout de suite, pour pouvoir reprendre apres une coupure). */
    @Synchronized
    private fun memoriser(h: String) = hashFile.appendText(h + "\n")

    /** Compte les octets lus (pour le pourcentage d'avancement). */
    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
