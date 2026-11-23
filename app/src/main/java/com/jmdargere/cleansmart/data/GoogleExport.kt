package com.jmdargere.cleansmart.data

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
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
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
 * - chaque fichier copie est RELU et compare a l'original avant de compter comme sauvegarde.
 * Rien n'est jamais supprime dans Google Photos par l'appli.
 */
class GoogleExport(private val ctx: Context) {

    private val cr = ctx.contentResolver
    private val hashFile = File(ctx.filesDir, "google_photos_hashes.txt")
    private val prefs = ctx.getSharedPreferences("google_photos", Context.MODE_PRIVATE)

    private val MEDIA = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng", "raf", "cr2", "nef", "arw",
        "mp4", "mov", "3gp", "m4v", "mkv", "avi", "webm")
    private val VIDEO = setOf("mp4", "mov", "3gp", "m4v", "mkv", "avi", "webm")

    // ------------------------------------------------------------------ etat conserve entre deux fichiers

    var destination: Uri?
        get() = prefs.getString("dest", null)?.let { Uri.parse(it) }
        set(v) { prefs.edit().putString("dest", v?.toString()).apply() }

    var tailleGoogleGo: Int
        get() = prefs.getInt("tailleGo", 0)
        set(v) { prefs.edit().putInt("tailleGo", v).apply() }

    var etape: Int
        get() = prefs.getInt("etape", 1)
        set(v) { prefs.edit().putInt("etape", v).apply() }

    fun total(): ExportStats = ExportStats(
        prefs.getInt("photos", 0), prefs.getInt("videos", 0), prefs.getInt("doublons", 0), prefs.getInt("rafales", 0),
        prefs.getInt("mails", 0), prefs.getLong("octets", 0), prefs.getInt("zips", 0), prefs.getInt("erreurs", 0))

    private fun ajouter(s: ExportStats) {
        val t = total() + s
        prefs.edit().putInt("photos", t.photos).putInt("videos", t.videos).putInt("doublons", t.doublons)
            .putInt("rafales", t.rafales).putInt("mails", t.mails).putLong("octets", t.octets)
            .putInt("zips", t.zips).putInt("erreurs", t.erreurs).apply()
    }

    /** Recommencer depuis le debut (nouvelle destination). */
    fun remettreAZero() {
        prefs.edit().clear().apply()
        hashFile.delete()
    }

    // ------------------------------------------------------------------ place

    /** Place a prevoir : la taille Google Photos + 10 % de marge (les doublons ecartes en feront gagner). */
    fun placeNecessaire(): Long = (tailleGoogleGo * 1.1 * (1L shl 30)).toLong()

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
            val docId = DocumentsContract.getTreeDocumentId(d)
            val volume = docId.substringBefore(':')
            val sm = ctx.getSystemService(StorageManager::class.java)
            val v = sm.storageVolumes.firstOrNull { (if (it.isPrimary) "primary" else it.uuid) == volume } ?: return null
            StatFs(v.directory!!.path).availableBytes
        }.getOrNull()
    }

    fun nomDestination(): String = destination?.let { DocumentFile.fromTreeUri(ctx, it)?.name } ?: "Aucun dossier choisi"

    // ------------------------------------------------------------------ traitement d'un ZIP

    private class Rafale(val doc: DocumentFile, val date: Long, val hash: Long, val nettete: Long)

    fun tailleDe(uri: Uri): Long = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    fun nomDe(uri: Uri): String = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: "fichier.zip"

    /** Place libre sur le telephone. */
    fun libreTelephone(): Long = runCatching { StatFs(ctx.cacheDir.path).availableBytes }.getOrDefault(0L)

    /**
     * Verifications AVANT de commencer un ZIP. Retourne null si tout va bien,
     * sinon le probleme et ce qu'il faut faire (on ne commence pas).
     */
    fun verifierAvant(zip: Uri): String? {
        val dest = destination ?: return "Choisis d'abord le dossier sur ta clé ou ton disque (étape 3)."
        val racine = DocumentFile.fromTreeUri(ctx, dest)
        if (racine == null || !racine.exists() || !racine.canWrite())
            return "Le disque n'est pas accessible.\n\n• Vérifie qu'il est bien branché (adaptateur USB-C).\n• Retourne à l'étape 3 et choisis le dossier à nouveau."
        if (libreTelephone() < 300L * 1024 * 1024)
            return "Le téléphone est presque plein (il faut au moins 300 Mo libres pour travailler).\n\n• Vide la corbeille de la Galerie.\n• Supprime les fichiers ZIP déjà traités (Téléchargements).\n• Fais le Ménage guidé de Clean Smart.\n\nRien n'a été touché."
        val taille = tailleDe(zip)
        val libre = libreDestination()
        if (libre != null && taille > 0 && libre < taille + 200L * 1024 * 1024)
            return "Le disque n'a plus assez de place : il reste ${fmtBytes(libre)}, il faut environ ${fmtBytes(taille)} pour ce fichier.\n\n• Libère de la place sur le disque, ou\n• branche un disque plus grand et choisis-le à l'étape 3 (on reprendra là où on en est).\n\nRien n'a été touché."
        return null
    }

    fun traiterZip(zip: Uri, progress: (String, Int) -> Unit): ExportStats {
        verifierAvant(zip)?.let { throw IllegalStateException(it) }
        val dest = destination!!
        val racine = DocumentFile.fromTreeUri(ctx, dest)!!
        val total = tailleDe(zip).coerceAtLeast(1)

        // 1er passage : les dates de prise de vue (fichiers .json de Google)
        progress("Lecture des dates des photos…", 1)
        val dates = HashMap<String, Long>()
        ZipInputStream(cr.openInputStream(zip)!!.buffered(1 shl 16)).use { z ->
            while (true) {
                val e = runCatching { z.nextEntry }.getOrNull() ?: break   // ZIP abime : on garde ce qu'on a lu
                if (!e.isDirectory && e.name.endsWith(".json", true)) {
                    runCatching {
                        val js = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                        val titre = js.optString("title")
                        val ts = js.optJSONObject("photoTakenTime")?.optString("timestamp")?.toLongOrNull()
                        if (titre.isNotEmpty() && ts != null && ts > 0) {
                            dates[titre.lowercase()] = ts * 1000
                            dates[(e.name.substringBeforeLast('/') + "/" + titre).lowercase()] = ts * 1000
                        }
                    }
                }
            }
        }

        // 2e passage : copie des photos, videos et mails
        val vus = chargerHashes()
        val photosDir by lazy { dossier(racine, "Google Photos") }
        val annees = HashMap<String, DocumentFile>()
        val rafales = ArrayList<Rafale>()
        var photos = 0; var videos = 0; var doublons = 0; var mails = 0; var octets = 0L; var erreurs = 0
        var abime = false; var plein = false
        val lu = CountingStream(cr.openInputStream(zip)!!)
        ZipInputStream(lu.buffered(1 shl 16)).use { z ->
            while (!plein) {
                val suivant: java.util.zip.ZipEntry? = try { z.nextEntry } catch (x: Exception) { abime = true; null }
                val e = suivant ?: break
                if (e.isDirectory) continue
                val nom = e.name.substringAfterLast('/')
                val ext = nom.substringAfterLast('.', "").lowercase()
                val pct = (5 + 85 * lu.count / total).toInt().coerceIn(5, 90)
                var enCours: DocumentFile? = null      // fichier en cours d'ecriture (efface s'il est incomplet)
                val tmp = File(ctx.cacheDir, "gp_tmp")
                try {
                    when {
                        ext in VIDEO -> {
                            // les videos vont DIRECTEMENT sur le disque (pas de place prise sur le telephone)
                            progress("Photos : $photos · vidéos : $videos · doublons écartés : $doublons", pct)
                            val date = dateDe(dates, e.name, nom) ?: 0L
                            val dir = annees.getOrPut(annee(date)) { dossier(photosDir, annee(date)) }
                            val doc = dir.createFile(mime(ext), libre(dir, jour(date) + nom)) ?: throw IllegalStateException("Écriture impossible")
                            enCours = doc
                            val md = MessageDigest.getInstance("MD5")
                            var n = 0L
                            cr.openOutputStream(doc.uri)!!.use { o ->
                                val buf = ByteArray(1 shl 16)
                                while (true) { val k = z.read(buf); if (k <= 0) break; md.update(buf, 0, k); o.write(buf, 0, k); n += k }
                            }
                            val h = md.digest().joinToString("") { "%02x".format(it) }
                            when {
                                h in vus -> { doc.delete(); doublons++ }
                                empreinte(doc.uri) != h -> { doc.delete(); erreurs++ }     // verification : on relit la copie
                                else -> { vus += h; memoriser(h); octets += n; videos++ }
                            }
                            enCours = null
                        }
                        ext in MEDIA -> {
                            progress("Photos : $photos · vidéos : $videos · doublons écartés : $doublons", pct)
                            // une photo passe par une petite copie temporaire (pour la date et la nettete)
                            val md = MessageDigest.getInstance("MD5")
                            tmp.outputStream().use { o ->
                                val buf = ByteArray(1 shl 16)
                                while (true) { val k = z.read(buf); if (k <= 0) break; md.update(buf, 0, k); o.write(buf, 0, k) }
                            }
                            val h = md.digest().joinToString("") { "%02x".format(it) }
                            if (h in vus) { doublons++; tmp.delete(); continue }
                            val date = dateDe(dates, e.name, nom) ?: dateExif(tmp) ?: 0L
                            val dir = annees.getOrPut(annee(date)) { dossier(photosDir, annee(date)) }
                            val doc = dir.createFile(mime(ext), libre(dir, jour(date) + nom)) ?: throw IllegalStateException("Écriture impossible")
                            enCours = doc
                            cr.openOutputStream(doc.uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                            if (empreinte(doc.uri) != h) { doc.delete(); erreurs++; tmp.delete(); enCours = null; continue }
                            enCours = null
                            vus += h; memoriser(h)          // memorise tout de suite : une reprise ne recopiera pas
                            octets += tmp.length()
                            photos++
                            signature(tmp)?.let { (dh, net) -> rafales += Rafale(doc, date, dh, net) }
                            tmp.delete()
                        }
                        ext == "mbox" -> mails += lireMbox(z, racine) { k -> progress("Mails Gmail : $k", pct) }
                    }
                } catch (x: Exception) {
                    // fichier a moitie ecrit : on l'efface, rien de faux ne reste sur le disque
                    runCatching { enCours?.delete() }
                    tmp.delete()
                    if (estPlein(x)) plein = true
                    else if (x is java.util.zip.ZipException || x is java.io.EOFException) { abime = true; break }
                    else erreurs++
                }
            }
        }

        // rafales : dans chaque serie (photos a moins de 15 s, qui se ressemblent), on garde la plus nette
        progress("Rafales : on garde la meilleure…", 92)
        var rafEcartees = 0
        val triees = rafales.filter { it.date > 0 }.sortedBy { it.date }
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
                    g.filter { it !== best }.forEach { if (it.doc.delete()) { rafEcartees++; photos-- } }
                }
            }
            i = j
        }

        progress("Terminé", 100)
        val s = ExportStats(photos, videos, doublons, rafEcartees, mails, octets, if (abime || plein) 0 else 1, erreurs, abime, plein)
        ajouter(s)
        return s
    }

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

    /** Conseils si ca bloque (affiches dans l'appli). */
    val conseils = listOf(
        "📱" to "Téléphone plein : vide la corbeille de la Galerie, supprime les ZIP déjà traités, fais le Ménage guidé. Il faut au moins 300 Mo libres.",
        "📦" to "Dans Google Takeout, choisis des fichiers de 2 Go : on les traite un par un, le téléphone n'a jamais besoin de beaucoup de place.",
        "⬇️" to "Télécharge UN seul ZIP à la fois, en Wi-Fi. Traite-le, supprime-le, puis télécharge le suivant.",
        "💾" to "Disque plein : branche un disque plus grand et choisis-le à l'étape 3. On reprend là où on en était, sans doublon.",
        "🔌" to "Disque non reconnu : utilise un adaptateur USB-C (OTG). Une clé formatée en exFAT ou FAT32 marche partout.",
        "🔋" to "Branche le chargeur pendant le traitement : un gros fichier peut prendre 10 à 30 minutes. L'écran reste allumé.",
        "⚠️" to "« Fichier abîmé » : le téléchargement a été coupé. Retélécharge ce ZIP depuis le mail de Google et relance-le : ce qui est déjà copié ne sera pas recopié.",
        "🔁" to "Le téléphone s'est éteint ou le disque s'est débranché ? Relance simplement le même ZIP : Clean Smart reprend sans rien perdre ni doubler.",
    )

    /** Essaie de supprimer le ZIP du telephone une fois traite (pour faire de la place au suivant). */
    fun supprimerZip(zip: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(cr, zip) }.getOrDefault(false)

    // ------------------------------------------------------------------ mails (export Gmail au format .mbox)

    private fun lireMbox(input: InputStream, racine: DocumentFile, progress: (Int) -> Unit): Int {
        val session = Session.getInstance(Properties())
        val mailsDir = dossier(racine, "Mails Gmail")
        val pdfDir by lazy { dossier(mailsDir, "PDF") }
        val annees = HashMap<String, DocumentFile>()
        val index = ArrayList<SavedMail>()
        var n = 0
        val courant = ByteArrayOutputStream()

        fun enregistrer() {
            if (courant.size() == 0) return
            runCatching {
                val msg = MimeMessage(session, ByteArrayInputStream(courant.toByteArray()))
                val d = msg.sentDate ?: msg.receivedDate ?: Date(0)
                val annee = if (d.time > 0) SimpleDateFormat("yyyy", Locale.FRANCE).format(d) else "Sans date"
                val jour = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(d)
                val dir = annees.getOrPut(annee) { dossier(mailsDir, annee) }
                val nom = libre(dir, "${jour}_" + propre(msg.subject ?: "sans objet").take(60) + ".eml")
                dir.createFile("message/rfc822", nom)?.let { f -> cr.openOutputStream(f.uri)?.use { courant.writeTo(it) } }
                val fichiers = ArrayList<String>()
                if (msg.isMimeType("multipart/*")) attachments(msg.content as Multipart) { p ->
                    val pn = "${jour}_" + propre(p.fileName)
                    if (pn.endsWith(".pdf", true) || p.isMimeType("application/pdf")) {
                        val cible = libre(pdfDir, pn)
                        pdfDir.createFile("application/pdf", cible)?.let { f ->
                            cr.openOutputStream(f.uri)?.use { o -> p.inputStream.use { it.copyTo(o) } }
                        }
                        fichiers += "PDF/$cible"
                    }
                }
                val ia = msg.from?.firstOrNull() as? InternetAddress
                index += SavedMail(d.time, ia?.personal ?: ia?.address ?: "", msg.subject ?: "", "$annee/$nom", fichiers, 0)
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
        ?: throw IllegalStateException("Impossible de créer le dossier $nom")

    /** Un nom de fichier libre dans le dossier (ajoute _2, _3... si besoin). */
    private fun libre(dir: DocumentFile, nom: String): String {
        if (dir.findFile(nom) == null) return nom
        val base = nom.substringBeforeLast('.')
        val ext = nom.substringAfterLast('.', "")
        var k = 2
        while (dir.findFile("${base}_$k.$ext") != null) k++
        return "${base}_$k.$ext"
    }

    private fun propre(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().ifEmpty { "sans_nom" }

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
        cr.openInputStream(uri)!!.use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
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
    private fun memoriser(h: String) = hashFile.appendText(h + "\n")

    /** Compte les octets lus (pour le pourcentage d'avancement). */
    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
