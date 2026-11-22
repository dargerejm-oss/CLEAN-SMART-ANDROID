package com.jmdargere.cleansmart.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.mail.Message
import javax.mail.Multipart

/**
 * Sauvegarde dans le dossier choisi par l'utilisateur (carte SD, cle USB, Google Drive...).
 *
 *   <dossier>/CleanSmart/Photos/2026-11/IMG_1234.jpg
 *   <dossier>/CleanSmart/Contacts/contacts_2026-11-22_1430.vcf
 *
 * Un fichier deja present (meme nom, meme taille) n'est pas recopie.
 */
class Backup(private val ctx: Context) {

    private val cr = ctx.contentResolver

    fun folderLabel(tree: Uri?): String {
        if (tree == null) return "Aucun dossier choisi"
        val d = DocumentFile.fromTreeUri(ctx, tree)
        return d?.name ?: tree.lastPathSegment ?: "Dossier choisi"
    }

    fun isUsable(tree: Uri?): Boolean = tree != null &&
            (DocumentFile.fromTreeUri(ctx, tree)?.let { it.exists() && it.canWrite() } ?: false)

    private fun dir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
        ?: throw IllegalStateException("Impossible de créer le dossier $name")

    private fun root(tree: Uri): DocumentFile {
        val t = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("Dossier de sauvegarde introuvable.")
        if (!t.canWrite()) throw IllegalStateException("Le dossier de sauvegarde n'est plus accessible. Choisis-le à nouveau.")
        return dir(t, "CleanSmart")
    }

    /** Copie les photos/videos. Retourne le nombre de fichiers copies (les deja sauvegardes sont ignores). */
    fun saveMedia(tree: Uri, items: List<MediaItem>, progress: (String, Int) -> Unit): Int {
        val photos = dir(root(tree), "Photos")
        val month = SimpleDateFormat("yyyy-MM", Locale.FRANCE)
        // contenu de chaque dossier mensuel, lu une seule fois : nom -> taille
        val known = HashMap<String, Pair<DocumentFile, HashMap<String, Long>>>()
        var copied = 0
        items.forEachIndexed { i, it ->
            val m = month.format(Date(if (it.date > 0) it.date else System.currentTimeMillis()))
            val (folder, content) = known.getOrPut(m) {
                val f = dir(photos, m)
                f to HashMap<String, Long>().apply { f.listFiles().forEach { x -> put(x.name ?: "", x.length()) } }
            }
            if (content[it.name] != it.size) {
                val mime = cr.getType(it.uri) ?: if (it.video) "video/mp4" else "image/jpeg"
                folder.findFile(it.name)?.delete()
                val dest = folder.createFile(mime, it.name) ?: throw IllegalStateException("Impossible d'écrire ${it.name}")
                cr.openInputStream(it.uri).use { input ->
                    cr.openOutputStream(dest.uri).use { out ->
                        if (input == null || out == null) throw IllegalStateException("Lecture impossible : ${it.name}")
                        input.copyTo(out, 256 * 1024)
                    }
                }
                content[dest.name ?: it.name] = it.size
                copied++
            }
            progress("Sauvegarde ${i + 1} / ${items.size}", 100 * (i + 1) / items.size.coerceAtLeast(1))
        }
        return copied
    }

    /**
     * Sauvegarde un mail et ses pieces jointes :
     *   CleanSmart/Mails/2026-11/2026-11-22_Sujet.eml        (le mail complet, s'ouvre avec n'importe quelle messagerie)
     *   CleanSmart/PDF/2026-11-22_facture.pdf                (tous les PDF reunis dans un seul dossier)
     *   CleanSmart/Pieces jointes/2026-11-22_photo.jpg       (les autres pieces jointes)
     * Retourne le nombre de PDF enregistres.
     */
    fun saveMail(tree: Uri, msg: Message): Int {
        val r = root(tree)
        val date = msg.receivedDate ?: msg.sentDate ?: Date()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(date)
        val month = SimpleDateFormat("yyyy-MM", Locale.FRANCE).format(date)

        val mails = dir(dir(r, "Mails"), month)
        val emlName = "${day}_" + clean(msg.subject ?: "sans objet").take(60) + ".eml"
        if (mails.findFile(emlName) == null) {
            val f = mails.createFile("message/rfc822", emlName) ?: throw IllegalStateException("Impossible d'écrire $emlName")
            cr.openOutputStream(f.uri)?.use { msg.writeTo(it) }
        }

        var pdf = 0
        if (msg.isMimeType("multipart/*")) {
            val pdfDir by lazy { dir(r, "PDF") }
            val otherDir by lazy { dir(r, "Pieces jointes") }
            attachments(msg.content as Multipart) { p ->
                val name = "${day}_" + clean(p.fileName)
                val isPdf = name.endsWith(".pdf", true) || p.isMimeType("application/pdf")
                val folder = if (isPdf) pdfDir else otherDir
                if (folder.findFile(name) == null) {
                    val mime = if (isPdf) "application/pdf" else "application/octet-stream"
                    val f = folder.createFile(mime, name) ?: throw IllegalStateException("Impossible d'écrire $name")
                    cr.openOutputStream(f.uri)?.use { out -> p.inputStream.use { it.copyTo(out) } }
                }
                if (isPdf) pdf++
            }
        }
        return pdf
    }

    /** Nom de fichier sans caracteres interdits. */
    private fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().ifEmpty { "sans_nom" }

    /** Exporte les contacts (cles lookup) dans un fichier .vcf. Retourne le nom du fichier. */
    fun saveContacts(tree: Uri, lookupKeys: List<String>): String? {
        if (lookupKeys.isEmpty()) return null
        val folder = dir(root(tree), "Contacts")
        val name = "contacts_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.FRANCE).format(Date()) + ".vcf"
        val dest = folder.createFile("text/x-vcard", name) ?: throw IllegalStateException("Impossible d'écrire $name")
        cr.openOutputStream(dest.uri).use { out ->
            if (out == null) throw IllegalStateException("Écriture impossible")
            lookupKeys.chunked(100).forEach { part ->
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI, Uri.encode(part.joinToString(":")))
                cr.openInputStream(uri)?.use { it.copyTo(out) }
            }
        }
        return dest.name
    }
}
