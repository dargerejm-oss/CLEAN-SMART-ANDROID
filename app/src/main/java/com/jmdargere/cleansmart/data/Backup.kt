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
/** Un mail sauvegarde (pour l'archive index.html). */
data class SavedMail(val date: Long, val from: String, val subject: String, val eml: String, val files: List<String>, val pdf: Int)

private val DANGER = listOf(".exe", ".apk", ".bat", ".cmd", ".scr", ".js", ".vbs", ".msi", ".jar", ".com")

/** Page de recherche de l'archive des mails (ouverte sur le PC, sans internet). */
private const val INDEX_HTML = """<!DOCTYPE html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Mes mails - Clean Smart</title>
<style>
body{font-family:system-ui,Segoe UI,Arial,sans-serif;background:#0f0f1e;color:#f3f3ff;margin:0;padding:20px}
h1{margin:0 0 4px}p.s{color:#a3a3c7;margin:0 0 16px}
input{width:100%;box-sizing:border-box;padding:14px 16px;font-size:18px;border-radius:12px;border:0;background:#1b1b33;color:#fff;margin-bottom:16px}
.m{background:#1b1b33;border-radius:14px;padding:12px 16px;margin:8px 0}
.m b{font-size:16px}.m .d{color:#a3a3c7;font-size:13px}
a{color:#9d93ff;text-decoration:none;margin-right:12px;font-size:14px}a:hover{text-decoration:underline}
</style></head><body>
<h1>✨ Mes mails sauvegardés</h1><p class="s" id="n"></p>
<input id="q" placeholder="Cherche un mot, un nom ou une pièce jointe…" autofocus>
<div id="l"></div>
<script>
const MAILS=/*DATA*/[];
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function show(){const q=document.getElementById('q').value.toLowerCase().trim();
const r=MAILS.filter(m=>!q||(m.subject+' '+m.from+' '+m.files.join(' ')).toLowerCase().includes(q));
document.getElementById('n').textContent=r.length+' mail(s) sur '+MAILS.length;
document.getElementById('l').innerHTML=r.slice(0,500).map(m=>'<div class="m"><b>'+esc(m.subject||'(sans objet)')+'</b><div class="d">'+esc(m.from)+' · '+new Date(m.date).toLocaleDateString('fr-FR')+'</div><div><a href="'+encodeURI(m.eml)+'">✉️ Ouvrir le mail</a>'+m.files.map(f=>'<a href="'+encodeURI(f)+'">'+(f.toLowerCase().endsWith('.pdf')?'📕 ':'📎 ')+esc(f.split('/').pop())+'</a>').join('')+'</div></div>').join('');}
document.getElementById('q').addEventListener('input',show);show();
</script></body></html>"""

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

    /**
     * Copie les photos/videos, rangees par mois. Les photos en favori (= les meilleures, protegees)
     * sont aussi reunies dans « Mes meilleures ». Retourne le nombre de fichiers copies
     * (les deja sauvegardes sont ignores).
     */
    fun saveMedia(tree: Uri, items: List<MediaItem>, progress: (String, Int) -> Unit): Int {
        val r = root(tree)
        val photos = dir(r, "Photos")
        val best by lazy { dir(r, "Mes meilleures").let { f -> f to listing(f) } }
        val month = SimpleDateFormat("yyyy-MM", Locale.FRANCE)
        // contenu de chaque dossier mensuel, lu une seule fois : nom -> taille
        val known = HashMap<String, Pair<DocumentFile, HashMap<String, Long>>>()
        var copied = 0
        items.forEachIndexed { i, it ->
            val m = month.format(Date(if (it.date > 0) it.date else System.currentTimeMillis()))
            val (folder, content) = known.getOrPut(m) { dir(photos, m).let { f -> f to listing(f) } }
            if (copyIfNeeded(folder, content, it)) copied++
            if (it.favorite) copyIfNeeded(best.first, best.second, it)
            progress("Sauvegarde ${i + 1} / ${items.size}", 100 * (i + 1) / items.size.coerceAtLeast(1))
        }
        return copied
    }

    private fun listing(f: DocumentFile): HashMap<String, Long> =
        HashMap<String, Long>().apply { f.listFiles().forEach { x -> put(x.name ?: "", x.length()) } }

    /** Copie le fichier s'il n'est pas deja la (meme nom, meme taille). */
    private fun copyIfNeeded(folder: DocumentFile, content: HashMap<String, Long>, it: MediaItem): Boolean {
        if (content[it.name] == it.size) return false
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
        return true
    }

    /**
     * Parmi [items], ceux qui sont DEJA dans la sauvegarde (meme nom, meme taille, au bon mois).
     * Sert a "Liberer la place" : on ne propose jamais de retirer une photo qui n'est pas a l'abri.
     */
    fun alreadySaved(tree: Uri, items: List<MediaItem>, progress: (String, Int) -> Unit): List<MediaItem> {
        val t = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val photos = t.findFile("CleanSmart")?.findFile("Photos") ?: return emptyList()
        val month = SimpleDateFormat("yyyy-MM", Locale.FRANCE)
        val known = HashMap<String, Map<String, Long>>()
        return items.filterIndexed { i, it ->
            if (i % 200 == 0) progress("Vérification de la sauvegarde", 100 * i / items.size.coerceAtLeast(1))
            val m = month.format(Date(if (it.date > 0) it.date else 0L))
            val content = known.getOrPut(m) {
                photos.findFile(m)?.listFiles()?.associate { f -> (f.name ?: "") to f.length() } ?: emptyMap()
            }
            content[it.name] == it.size
        }
    }

    /**
     * Sauvegarde un mail et ses pieces jointes :
     *   CleanSmart/Mails/2026-11/2026-11-22_Sujet.eml        (le mail complet, s'ouvre avec n'importe quelle messagerie)
     *   CleanSmart/PDF/2026-11-22_facture.pdf                (tous les PDF reunis dans un seul dossier)
     *   CleanSmart/Pieces jointes/2026-11-22_photo.jpg       (les autres pieces jointes)
     * Les fichiers dangereux (.exe, .apk...) ne sont jamais enregistres.
     */
    fun saveMail(tree: Uri, msg: Message): SavedMail {
        val r = root(tree)
        val date = msg.receivedDate ?: msg.sentDate ?: Date()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(date)
        val month = SimpleDateFormat("yyyy-MM", Locale.FRANCE).format(date)
        val ia = msg.from?.firstOrNull() as? javax.mail.internet.InternetAddress
        val from = ia?.personal ?: ia?.address ?: ""

        val mails = dir(dir(r, "Mails"), month)
        val emlName = "${day}_" + clean(msg.subject ?: "sans objet").take(60) + ".eml"
        if (mails.findFile(emlName) == null) {
            val f = mails.createFile("message/rfc822", emlName) ?: throw IllegalStateException("Impossible d'écrire $emlName")
            cr.openOutputStream(f.uri)?.use { msg.writeTo(it) }
        }

        val files = ArrayList<String>()
        var pdf = 0
        if (msg.isMimeType("multipart/*")) {
            val pdfDir by lazy { dir(r, "PDF") }
            val otherDir by lazy { dir(r, "Pieces jointes") }
            attachments(msg.content as Multipart) { p ->
                val name = "${day}_" + clean(p.fileName)
                if (DANGER.any { name.endsWith(it, true) }) return@attachments
                val isPdf = name.endsWith(".pdf", true) || p.isMimeType("application/pdf")
                val folder = if (isPdf) pdfDir else otherDir
                if (folder.findFile(name) == null) {
                    val mime = if (isPdf) "application/pdf" else "application/octet-stream"
                    val f = folder.createFile(mime, name) ?: throw IllegalStateException("Impossible d'écrire $name")
                    cr.openOutputStream(f.uri)?.use { out -> p.inputStream.use { it.copyTo(out) } }
                }
                files += (if (isPdf) "../PDF/" else "../Pieces jointes/") + name
                if (isPdf) pdf++
            }
        }
        return SavedMail(date.time, from, msg.subject ?: "", "$month/$emlName", files, pdf)
    }

    /**
     * Met a jour l'archive consultable sur le PC : CleanSmart/Mails/index.html
     * (double-clic, puis on tape un mot, un nom ou une piece jointe).
     */
    fun updateMailIndex(tree: Uri, nouveaux: List<SavedMail>) {
        val mailsDir = dir(root(tree), "Mails")
        // liste deja connue (index.json), completee des nouveaux mails
        val all = LinkedHashMap<String, org.json.JSONObject>()
        mailsDir.findFile("index.json")?.let { f ->
            runCatching {
                val arr = org.json.JSONArray(cr.openInputStream(f.uri)!!.bufferedReader().use { it.readText() })
                for (i in 0 until arr.length()) arr.getJSONObject(i).let { all[it.getString("eml")] = it }
            }
        }
        nouveaux.forEach { m ->
            all[m.eml] = org.json.JSONObject().put("date", m.date).put("from", m.from).put("subject", m.subject)
                .put("eml", m.eml).put("files", org.json.JSONArray(m.files))
        }
        val json = org.json.JSONArray(all.values.sortedByDescending { it.optLong("date") }).toString()
        write(mailsDir, "index.json", "application/json", json)
        // "</" protege la page si un objet de mail contient du code
        write(mailsDir, "index.html", "text/html", INDEX_HTML.replace("/*DATA*/[]", json.replace("</", "<\\/")))
    }

    private fun write(folder: DocumentFile, name: String, mime: String, text: String) {
        val f = folder.findFile(name) ?: folder.createFile(mime, name) ?: return
        cr.openOutputStream(f.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
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
