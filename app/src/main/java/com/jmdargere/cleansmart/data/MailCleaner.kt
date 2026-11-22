package com.jmdargere.cleansmart.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sun.mail.imap.IMAPFolder
import java.net.UnknownHostException
import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SizeTerm

data class MailAccount(val email: String = "", val host: String = "", val provider: String = "", val hasPassword: Boolean = false) {
    val ready get() = email.isNotBlank() && host.isNotBlank() && hasPassword
}

data class Sender(val email: String, val name: String, val count: Int, val bytes: Long, val newsletter: Boolean, val last: Long)
data class BigMail(val from: String, val subject: String, val size: Long, val date: Long)

data class MailResult(
    val total: Int,
    val scanned: Int,
    val bytes: Long,
    val newsletters: Int,
    val bigCount: Int,
    val bigBytes: Long,
    val oldCount: Int,
    val oldBytes: Long,
    val senders: List<Sender>,
    val bigList: List<BigMail>,
)

/** Un mail qui a des pieces jointes (pour la sauvegarde). */
data class MailWithFiles(val uid: Long, val from: String, val subject: String, val date: Long, val size: Long, val files: List<String>) {
    val hasPdf get() = files.any { it.endsWith(".pdf", true) }
}

sealed class MailCriteria {
    data class From(val emails: List<String>) : MailCriteria()
    data class Big(val minMb: Int) : MailCriteria()
    data class Old(val years: Int) : MailCriteria()
}

class MailException(msg: String) : Exception(msg)

/** Parcourt un mail et appelle [onFile] pour chaque piece jointe (partie avec un nom de fichier). */
fun attachments(mp: Multipart, onFile: (Part) -> Unit) {
    for (i in 0 until mp.count) {
        val p = mp.getBodyPart(i)
        val content = if (p.isMimeType("multipart/*")) runCatching { p.content }.getOrNull() else null
        when {
            content is Multipart -> attachments(content, onFile)
            !p.fileName.isNullOrBlank() -> onFile(p)
        }
    }
}

enum class Provider(val label: String, val host: String, val help: String) {
    GMAIL("Gmail", "imap.gmail.com",
        "Gmail exige un MOT DE PASSE D'APPLICATION (16 lettres) :\n1. Active la validation en 2 étapes sur ton compte Google.\n2. Ouvre myaccount.google.com/apppasswords\n3. Crée un mot de passe nommé « Clean Smart » et colle-le ici."),
    OUTLOOK("Outlook / Hotmail", "outlook.office365.com",
        "Si la connexion est refusée, crée un mot de passe d'application dans la sécurité du compte Microsoft (validation en 2 étapes requise)."),
    YAHOO("Yahoo", "imap.mail.yahoo.com", "Sécurité du compte → Générer un mot de passe d'application, puis colle-le ici."),
    ICLOUD("iCloud", "imap.mail.me.com", "Sur account.apple.com → Mots de passe pour applications → en créer un."),
    ORANGE("Orange", "imap.orange.fr", "Utilise ton adresse et ton mot de passe Orange."),
    FREE("Free", "imap.free.fr", "Utilise ton adresse et ton mot de passe Free."),
    SFR("SFR", "imap.sfr.fr", "Utilise ton adresse et ton mot de passe SFR."),
    LAPOSTE("La Poste", "imap.laposte.net", "Utilise ton adresse et ton mot de passe La Poste."),
    AUTRE("Autre", "", "Indique le serveur IMAP de ta messagerie (port 993, SSL).");

    companion object {
        val APP_PASSWORD_URL = mapOf(GMAIL to "https://myaccount.google.com/apppasswords", ICLOUD to "https://account.apple.com")
    }
}

/**
 * Nettoyage de la boite mail par IMAP. Les mails sont DEPLACES dans la corbeille
 * du compte (jamais effaces definitivement). Mot de passe chiffre sur le telephone.
 */
class MailCleaner(private val ctx: Context) {

    init {
        // noms de pieces jointes accentues (=?UTF-8?...) correctement decodes
        System.setProperty("mail.mime.decodefilename", "true")
    }

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "mail_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun account(): MailAccount = try {
        MailAccount(
            email = prefs.getString("email", "") ?: "",
            host = prefs.getString("host", "") ?: "",
            provider = prefs.getString("provider", "") ?: "",
            hasPassword = !prefs.getString("password", "").isNullOrEmpty(),
        )
    } catch (e: Exception) { MailAccount() }

    fun forget() { runCatching { prefs.edit().clear().apply() } }

    /** Teste la connexion puis enregistre. Un mot de passe vide garde l'ancien. */
    fun saveAndTest(provider: Provider, host: String, email: String, password: String) {
        val pass = password.ifEmpty { prefs.getString("password", "") ?: "" }
        if (email.isBlank() || host.isBlank() || pass.isEmpty())
            throw MailException("Adresse, serveur et mot de passe sont obligatoires.")
        connect(host.trim(), email.trim(), pass).close()
        prefs.edit()
            .putString("email", email.trim())
            .putString("host", host.trim())
            .putString("provider", provider.name)
            .putString("password", pass)
            .apply()
    }

    private fun connect(): Store {
        val a = account()
        if (!a.ready) throw MailException("Aucune boîte mail connectée.")
        return connect(a.host, a.email, prefs.getString("password", "") ?: "")
    }

    private fun connect(host: String, email: String, pass: String): Store {
        val pr = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "60000")
        }
        val st = Session.getInstance(pr).getStore("imaps")
        try {
            st.connect(host, 993, email, pass)
        } catch (e: AuthenticationFailedException) {
            throw MailException("Identifiants refusés. Pour Gmail, Yahoo ou iCloud il faut un MOT DE PASSE D'APPLICATION, pas ton mot de passe habituel.")
        } catch (e: Exception) {
            var root: Throwable = e
            while (root.cause != null && root.cause !== root) root = root.cause!!
            if (root is UnknownHostException) throw MailException("Serveur introuvable : vérifie internet et le nom du serveur.")
            throw MailException("Connexion impossible : " + (e.message ?: e.javaClass.simpleName))
        }
        return st
    }

    fun scan(max: Int, s: Settings, progress: (String, Int) -> Unit): MailResult {
        progress("Connexion au serveur", 2)
        val st = connect()
        var inbox: Folder? = null
        try {
            inbox = st.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            val total = inbox.messageCount
            val start = maxOf(1, total - max + 1)
            val toRead = total - start + 1
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.SIZE)
                add("List-Unsubscribe")
            }
            val bigLimit = s.bigMailMb.toLong() * 1024 * 1024
            val oldLimit = System.currentTimeMillis() - s.oldMailYears * 365L * 24 * 3600 * 1000

            class Acc(val email: String, var name: String) { var count = 0; var bytes = 0L; var news = false; var last = 0L }
            val senders = HashMap<String, Acc>()
            val big = ArrayList<BigMail>()
            var bytes = 0L; var news = 0; var bigCount = 0; var bigBytes = 0L; var oldCount = 0; var oldBytes = 0L; var read = 0

            var a = total
            while (a >= start) {
                val b = maxOf(start, a - 299)
                val msgs = inbox.getMessages(b, a)
                inbox.fetch(msgs, fp)
                for (m in msgs) {
                    runCatching {
                        val ia = m.from?.firstOrNull() as? InternetAddress
                        val email = ia?.address?.lowercase() ?: ""
                        val name = ia?.personal ?: ""
                        val size = m.size.coerceAtLeast(0).toLong()
                        val date = (m.receivedDate ?: m.sentDate)?.time ?: 0L
                        val isNews = !m.getHeader("List-Unsubscribe").isNullOrEmpty()
                        bytes += size
                        if (isNews) news++
                        if (size >= bigLimit) {
                            bigCount++; bigBytes += size
                            if (big.size < 40) big += BigMail(name.ifEmpty { email }, m.subject ?: "", size, date)
                        }
                        if (date in 1 until oldLimit) { oldCount++; oldBytes += size }
                        if (email.isNotEmpty()) {
                            val acc = senders.getOrPut(email) { Acc(email, name) }
                            acc.count++; acc.bytes += size
                            if (isNews) acc.news = true
                            if (date > acc.last) acc.last = date
                            if (acc.name.isEmpty()) acc.name = name
                        }
                    }
                    read++
                }
                progress("Analyse des mails ($read / $toRead)", 5 + 90 * read / toRead.coerceAtLeast(1))
                a = b - 1
            }

            val top = senders.values.filter { it.count >= 2 }
                .sortedWith(compareByDescending<Acc> { it.count }.thenByDescending { it.bytes })
                .take(80)
                .map { Sender(it.email, it.name, it.count, it.bytes, it.news, it.last) }

            progress("Terminé", 100)
            return MailResult(total, read, bytes, news, bigCount, bigBytes, oldCount, oldBytes, top, big.sortedByDescending { it.size })
        } finally {
            runCatching { if (inbox?.isOpen == true) inbox.close(false) }
            runCatching { st.close() }
        }
    }

    /** Deplace dans la corbeille du compte les mails de la boite de reception qui correspondent. */
    fun trash(c: MailCriteria): Int {
        val st = connect()
        var inbox: IMAPFolder? = null
        try {
            val trash = findTrash(st) ?: throw MailException("Dossier Corbeille introuvable sur ce compte.")
            inbox = (st.getFolder("INBOX") as IMAPFolder).apply { open(Folder.READ_WRITE) }
            val found = LinkedHashSet<Message>()
            when (c) {
                is MailCriteria.From -> c.emails.filter { it.isNotBlank() }.forEach { found += inbox.search(FromStringTerm(it.trim())) }
                is MailCriteria.Big -> found += inbox.search(SizeTerm(ComparisonTerm.GT, c.minMb.coerceAtLeast(1) * 1024 * 1024))
                is MailCriteria.Old -> {
                    val before = Date(System.currentTimeMillis() - c.years.coerceAtLeast(1) * 365L * 24 * 3600 * 1000)
                    found += inbox.search(ReceivedDateTerm(ComparisonTerm.LT, before))
                }
            }
            val all = found.toTypedArray()
            var needExpunge = false
            all.toList().chunked(200).forEach { part ->
                val arr = part.toTypedArray()
                try {
                    inbox.moveMessages(arr, trash)
                } catch (e: Exception) {
                    // serveur sans MOVE : copie dans la corbeille puis retrait de la boite
                    inbox.copyMessages(arr, trash)
                    inbox.setFlags(arr, Flags(Flags.Flag.DELETED), true)
                    needExpunge = true
                }
            }
            if (needExpunge) {
                try { inbox.expunge(all) } catch (e: Exception) { inbox.expunge() }
            }
            return all.size
        } finally {
            runCatching { if (inbox?.isOpen == true) inbox.close(false) }
            runCatching { st.close() }
        }
    }

    // ---------- Sauvegarde de mails choisis ----------

    /** Les mails recents qui ont des pieces jointes (sans telecharger les fichiers). */
    fun listWithAttachments(max: Int, progress: (String, Int) -> Unit): List<MailWithFiles> {
        progress("Connexion au serveur", 2)
        val st = connect()
        var inbox: IMAPFolder? = null
        try {
            inbox = (st.getFolder("INBOX") as IMAPFolder).apply { open(Folder.READ_ONLY) }
            val total = inbox.messageCount
            val start = maxOf(1, total - max + 1)
            val toRead = total - start + 1
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.SIZE)
                add(FetchProfile.Item.CONTENT_INFO)   // structure du mail : noms des pieces jointes
                add(UIDFolder.FetchProfileItem.UID)
            }
            val out = ArrayList<MailWithFiles>()
            var read = 0
            var a = total
            while (a >= start) {
                val b = maxOf(start, a - 199)
                val msgs = inbox.getMessages(b, a)
                inbox.fetch(msgs, fp)
                for (m in msgs.reversed()) {
                    runCatching {
                        if (m.isMimeType("multipart/*")) {
                            val names = ArrayList<String>()
                            attachments(m.content as Multipart) { p -> names += p.fileName }
                            if (names.isNotEmpty()) {
                                val ia = m.from?.firstOrNull() as? InternetAddress
                                out += MailWithFiles(
                                    uid = inbox.getUID(m),
                                    from = ia?.personal ?: ia?.address ?: "",
                                    subject = m.subject ?: "",
                                    date = (m.receivedDate ?: m.sentDate)?.time ?: 0L,
                                    size = m.size.coerceAtLeast(0).toLong(),
                                    files = names,
                                )
                            }
                        }
                    }
                    read++
                }
                progress("Recherche des pièces jointes ($read / $toRead)", 5 + 90 * read / toRead.coerceAtLeast(1))
                a = b - 1
            }
            progress("Terminé", 100)
            return out
        } finally {
            runCatching { if (inbox?.isOpen == true) inbox.close(false) }
            runCatching { st.close() }
        }
    }

    /** Ouvre la boite et passe chaque mail choisi (par UID) a [block]. */
    fun forEachMail(uids: List<Long>, progress: (String, Int) -> Unit, block: (Message) -> Unit) {
        val st = connect()
        var inbox: IMAPFolder? = null
        try {
            inbox = (st.getFolder("INBOX") as IMAPFolder).apply { open(Folder.READ_ONLY) }
            uids.forEachIndexed { i, uid ->
                inbox.getMessageByUID(uid)?.let(block)
                progress("Sauvegarde du mail ${i + 1} / ${uids.size}", 100 * (i + 1) / uids.size.coerceAtLeast(1))
            }
        } finally {
            runCatching { if (inbox?.isOpen == true) inbox.close(false) }
            runCatching { st.close() }
        }
    }

    private fun findTrash(st: Store): Folder? {
        runCatching {
            for (f in st.defaultFolder.list("*")) {
                if (f is IMAPFolder && f.attributes.any { it.equals("\\Trash", true) }) return f
            }
        }
        val names = listOf("[Gmail]/Corbeille", "[Gmail]/Trash", "[Google Mail]/Trash", "Trash", "Corbeille",
            "Deleted Items", "Deleted Messages", "Éléments supprimés", "INBOX.Trash", "INBOX.Corbeille")
        for (n in names) runCatching { val f = st.getFolder(n); if (f.exists()) return f }
        return null
    }
}
