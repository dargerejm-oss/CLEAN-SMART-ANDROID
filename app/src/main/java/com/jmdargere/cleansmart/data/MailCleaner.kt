package com.jmdargere.cleansmart.data

import com.jmdargere.cleansmart.Tr

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

data class Sender(
    val email: String, val name: String, val count: Int, val bytes: Long, val newsletter: Boolean, val last: Long,
    /** Banque, administration, sante... : jamais mis a la corbeille par Clean Smart. */
    val important: Boolean = false,
    /** Notifications automatiques (no-reply, reseaux sociaux...). */
    val notif: Boolean = false,
    /** Lien de desabonnement (https:// ou mailto:), vide s'il n'y en a pas. */
    val unsubscribe: String = "",
)
data class BigMail(val from: String, val subject: String, val size: Long, val date: Long, val uid: Long = 0)

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
    /** Lecture seule : factures et papiers, colis, voyages (les plus recents). */
    val factures: List<BigMail> = emptyList(),
    val colis: List<BigMail> = emptyList(),
    val voyages: List<BigMail> = emptyList(),
    /** Mails identiques recus a quelques secondes d'ecart (UID des copies a jeter). */
    val doublons: List<Long> = emptyList(),
    /** Place utilisee / disponible sur le serveur (0 si la messagerie ne la donne pas). */
    val quotaUsed: Long = 0,
    val quotaMax: Long = 0,
    /** UID des mails a mettre a l'abri : expediteurs importants et factures (les plus recents). */
    val aGarder: List<Long> = emptyList(),
)

/** Reconnait les mails importants et les classe (factures, colis, voyages). */
object MailTri {
    private val IMPORTANT = listOf(
        "gouv.fr", "impots", "ameli", "caf.fr", "urssaf", "francetravail", "pole-emploi", "ants.gouv", "service-public",
        "banque", "bank", "credit", "bnpparibas", "societegenerale", "labanquepostale", "lcl.fr", "boursorama",
        "caisse-epargne", "creditmutuel", "cic.fr", "hellobank", "fortuneo", "revolut", "paypal", "notaire",
        "mutuelle", "assurance", "doctolib", "hopital", "chu-", "edf.fr", "engie", "trésor", "tresor",
    )
    private val FACTURE = Regex("(?i)facture|invoice|re[çc]u de paiement|avis d'[ée]ch[ée]ance|relev[ée]|quittance|bulletin de (salaire|paie)|avis d'imp[oô]t")
    private val COLIS = Regex("(?i)colis|livraison|exp[ée]di|suivi de (commande|votre)|tracking|colissimo|chronopost|mondial relay|ups |dhl|dpd|gls|votre commande")
    private val VOYAGE = Regex("(?i)r[ée]servation|billet|booking|e-ticket|boarding|embarquement|vol |flight|sncf|ouigo|airbnb|h[ôo]tel|itin[ée]raire|check-in")

    private val NOTIF = listOf("noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "ne-pas-repondre",
        "nepasrepondre", "notification", "notify", "alert", "facebookmail", "linkedin", "instagram", "twitter", "x.com",
        "tiktok", "pinterest", "mailer-daemon")

    fun notif(email: String) = NOTIF.any { it in email.lowercase() }

    /** Extrait le meilleur lien de l'en-tete List-Unsubscribe (https d'abord, sinon mailto). */
    fun unsubscribe(header: String?): String {
        if (header.isNullOrBlank()) return ""
        val links = Regex("<([^>]+)>").findAll(header).map { it.groupValues[1].trim() }.toList()
        return links.firstOrNull { it.startsWith("https://") } ?: links.firstOrNull { it.startsWith("mailto:") } ?: ""
    }

    fun important(email: String, name: String): Boolean {
        val s = (email + " " + name).lowercase()
        return IMPORTANT.any { it in s } || Regles.garder(email)
    }

    private val BANQUE = listOf("banque", "bank", "credit", "bnpparibas", "societegenerale", "labanquepostale", "lcl.fr", "boursorama",
        "caisse-epargne", "creditmutuel", "cic.fr", "hellobank", "fortuneo", "revolut", "paypal")
    private val ADMIN = listOf("gouv.fr", "impots", "caf.fr", "urssaf", "francetravail", "pole-emploi", "ants.gouv", "service-public",
        "trésor", "tresor", "notaire", "mairie")
    private val SANTE = listOf("ameli", "mutuelle", "doctolib", "hopital", "chu-", "pharmacie", "laboratoire", "clinique")
    private val MAISON = listOf("assurance", "edf.fr", "engie", "totalenergies", "veolia", "suez", "orange.fr", "sfr.fr", "free.fr", "bouygues")

    /** Dossier de la boite protegee : Banque, Impots - Administration, Sante, Maison (null = pas important). */
    fun categorie(email: String, name: String): String? {
        val s = (email + " " + name).lowercase()
        return when {
            BANQUE.any { it in s } -> "Banque"
            ADMIN.any { it in s } -> "Impots - Administration"
            SANTE.any { it in s } -> "Sante"
            MAISON.any { it in s } && !s.contains("newsletter") -> "Maison"
            else -> null
        }
    }
    fun facture(subject: String) = FACTURE.containsMatchIn(subject)
    fun colis(subject: String) = COLIS.containsMatchIn(subject)
    fun voyage(subject: String) = VOYAGE.containsMatchIn(subject)
}

/** Un mail qui a des pieces jointes (pour la sauvegarde). */
data class MailWithFiles(val uid: Long, val from: String, val subject: String, val date: Long, val size: Long, val files: List<String>) {
    val hasPdf get() = files.any { it.endsWith(".pdf", true) }
}

sealed class MailCriteria {
    data class From(val emails: List<String>) : MailCriteria()
    data class Big(val minMb: Int) : MailCriteria()
    data class Old(val years: Int) : MailCriteria()
    /** Des mails precis (par leur numero UID), ex. les doublons. */
    data class Uids(val uids: List<Long>) : MailCriteria()
    /** Plusieurs criteres a la fois (ex. newsletters + doublons). */
    data class Tous(val liste: List<MailCriteria>) : MailCriteria()
    /** Tout le dossier Spam / Courrier indesirable. */
    object Spam : MailCriteria()
}

/** Ce qui va partir, a relire avant de valider (2.0). */
data class MailRevue(val titre: String, val critere: MailCriteria, val mails: List<BigMail>, val coches: Set<Long>, val spam: Boolean)

/** Un mail de la corbeille, pour le remettre dans la boite. */
data class TrashedMail(val uid: Long, val from: String, val subject: String, val date: Long)

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

enum class Provider(private val labelFr: String, val host: String, private val helpFr: String) {
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

    val label: String get() = com.jmdargere.cleansmart.Tr.t(labelFr)
    val help: String get() = com.jmdargere.cleansmart.Tr.t(helpFr)

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
            throw MailException(Tr.t("Adresse, serveur et mot de passe sont obligatoires."))
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
        if (!a.ready) throw MailException(Tr.t("Aucune boîte mail connectée."))
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
            throw MailException(Tr.t("Identifiants refusés. Pour Gmail, Yahoo ou iCloud il faut un MOT DE PASSE D'APPLICATION, pas ton mot de passe habituel."))
        } catch (e: Exception) {
            var root: Throwable = e
            while (root.cause != null && root.cause !== root) root = root.cause!!
            if (root is UnknownHostException) throw MailException(Tr.t("Serveur introuvable : vérifie internet et le nom du serveur."))
            throw MailException(Tr.t("Connexion impossible : ") + (e.message ?: e.javaClass.simpleName))
        }
        return st
    }

    fun scan(max: Int, s: Settings, progress: (String, Int) -> Unit): MailResult {
        progress(Tr.t("Connexion au serveur"), 2)
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
                add(UIDFolder.FetchProfileItem.UID)
                add("List-Unsubscribe")
            }
            val factures = ArrayList<BigMail>()
            val colis = ArrayList<BigMail>()
            val voyages = ArrayList<BigMail>()
            // doublons : meme expediteur + meme objet + meme taille, recus a moins de 2 minutes d'ecart
            val vus = HashMap<String, Long>()
            val doublons = ArrayList<Long>()
            val bigLimit = s.bigMailMb.toLong() * 1024 * 1024
            val oldLimit = System.currentTimeMillis() - s.oldMailYears * 365L * 24 * 3600 * 1000

            class Acc(val email: String, var name: String) { var count = 0; var bytes = 0L; var news = false; var last = 0L; var unsub = "" }
            val aGarder = ArrayList<Long>()
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
                        val unsubHeader = m.getHeader("List-Unsubscribe")?.firstOrNull()
                        val isNews = !unsubHeader.isNullOrEmpty()
                        val subject = m.subject ?: ""
                        val uid = inbox.getUID(m)
                        val info = BigMail(name.ifEmpty { email }, subject, size, date, uid)
                        bytes += size
                        if (isNews) news++
                        if (size >= bigLimit) {
                            bigCount++; bigBytes += size
                            if (big.size < 40) big += info
                        }
                        // a mettre a l'abri : expediteurs importants et factures (les 400 plus recents)
                        if (aGarder.size < 400 && (MailTri.important(email, name) || (!isNews && MailTri.facture(subject)))) aGarder += uid
                        if (!isNews) {
                            if (factures.size < 50 && MailTri.facture(subject)) factures += info
                            else if (colis.size < 50 && MailTri.colis(subject)) colis += info
                            else if (voyages.size < 50 && MailTri.voyage(subject)) voyages += info
                        }
                        val cle = "${email}|${subject}|${size}"
                        val avant = vus[cle]
                        if (avant != null && kotlin.math.abs(avant - date) < 120_000) doublons += uid else vus[cle] = date
                        if (date in 1 until oldLimit) { oldCount++; oldBytes += size }
                        if (email.isNotEmpty()) {
                            val acc = senders.getOrPut(email) { Acc(email, name) }
                            acc.count++; acc.bytes += size
                            if (isNews) acc.news = true
                            if (acc.unsub.isEmpty()) acc.unsub = MailTri.unsubscribe(unsubHeader)
                            if (date > acc.last) acc.last = date
                            if (acc.name.isEmpty()) acc.name = name
                        }
                    }
                    read++
                }
                progress(Tr.t("Analyse des mails (%1 / %2)", read, toRead), 5 + 90 * read / toRead.coerceAtLeast(1))
                a = b - 1
            }

            val top = senders.values.filter { it.count >= 2 }
                .sortedWith(compareByDescending<Acc> { it.count }.thenByDescending { it.bytes })
                .take(80)
                .map {
                    val imp = MailTri.important(it.email, it.name)
                    Sender(it.email, it.name, it.count, it.bytes, it.news, it.last, imp,
                        notif = !imp && MailTri.notif(it.email), unsubscribe = it.unsub)
                }

            // place sur le serveur (si la messagerie la donne)
            var used = 0L; var maxQ = 0L
            runCatching {
                (st as? javax.mail.QuotaAwareStore)?.getQuota("INBOX")?.forEach { q ->
                    q.resources?.firstOrNull { it.name.equals("STORAGE", true) }?.let { r -> used = r.usage * 1024; maxQ = r.limit * 1024 }
                }
            }

            progress(Tr.t("Terminé"), 100)
            return MailResult(total, read, bytes, news, bigCount, bigBytes, oldCount, oldBytes, top, big.sortedByDescending { it.size },
                factures, colis, voyages, doublons, used, maxQ, aGarder)
        } finally {
            runCatching { if (inbox?.isOpen == true) inbox.close(false) }
            runCatching { st.close() }
        }
    }

    /** Taille des mails mis a la corbeille par le dernier appel a [trash] (pour le bilan « mails »). */
    @Volatile var derniersOctets = 0L; private set

    /** Deplace dans la corbeille du compte les mails de la boite de reception qui correspondent. */
    fun trash(c: MailCriteria, dansSpam: Boolean = false): Int {
        derniersOctets = 0L
        val st = connect()
        var inbox: IMAPFolder? = null
        try {
            val trash = findTrash(st) ?: throw MailException(Tr.t("Dossier Corbeille introuvable sur ce compte."))
            inbox = (((if (dansSpam || c is MailCriteria.Spam) findSpam(st) else st.getFolder("INBOX"))
                ?: throw MailException(Tr.t("Dossier Spam introuvable sur ce compte."))) as IMAPFolder).apply { open(Folder.READ_WRITE) }
            val all = trouver(inbox, c, spam = dansSpam || c is MailCriteria.Spam).toTypedArray()
            derniersOctets = all.sumOf { m -> runCatching { m.size.toLong().coerceAtLeast(0) }.getOrDefault(0L) }
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

    /**
     * Les mails qui correspondent a [c], SANS les importants (banque, administration, sante, « toujours garder »).
     * Dans le dossier Spam, tout peut partir.
     */
    private fun trouver(dossier: IMAPFolder, c: MailCriteria, spam: Boolean): List<Message> {
        val found = LinkedHashSet<Message>()
        fun ajouter(c: MailCriteria) {
            when (c) {
                is MailCriteria.From -> c.emails.filter { it.isNotBlank() }.forEach { found += dossier.search(FromStringTerm(it.trim())) }
                is MailCriteria.Big -> found += dossier.search(SizeTerm(ComparisonTerm.GT, c.minMb.coerceAtLeast(1) * 1024 * 1024))
                is MailCriteria.Old -> {
                    val before = Date(System.currentTimeMillis() - c.years.coerceAtLeast(1) * 365L * 24 * 3600 * 1000)
                    found += dossier.search(ReceivedDateTerm(ComparisonTerm.LT, before))
                }
                is MailCriteria.Uids -> if (c.uids.isNotEmpty()) found += dossier.getMessagesByUID(c.uids.toLongArray()).filterNotNull()
                is MailCriteria.Tous -> c.liste.forEach { ajouter(it) }
                is MailCriteria.Spam -> found += dossier.messages
            }
        }
        ajouter(c)
        if (spam) return found.toList()
        // les mails importants (banque, administration, sante, « toujours garder »...) ne sont JAMAIS mis a la corbeille
        return found.filterNot { m ->
            runCatching {
                val ia = m.from?.firstOrNull() as? InternetAddress
                MailTri.important(ia?.address ?: "", ia?.personal ?: "")
            }.getOrDefault(true)
        }
    }

    /** Ce qui partirait avec [c] : expediteur, objet, date, taille (pour le relire avant de valider). */
    fun apercu(c: MailCriteria, max: Int = 3000): List<BigMail> {
        val st = connect()
        var dossier: IMAPFolder? = null
        try {
            val spam = c is MailCriteria.Spam
            dossier = ((if (spam) findSpam(st) else st.getFolder("INBOX")) ?: return emptyList()) as IMAPFolder
            dossier.open(Folder.READ_ONLY)
            val msgs = trouver(dossier, c, spam).takeLast(max).toTypedArray()
            dossier.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.SIZE); add(UIDFolder.FetchProfileItem.UID) })
            return msgs.mapNotNull { m ->
                runCatching {
                    val ia = m.from?.firstOrNull() as? InternetAddress
                    BigMail(ia?.personal?.takeIf { it.isNotBlank() } ?: ia?.address ?: "", m.subject ?: "",
                        m.size.coerceAtLeast(0).toLong(), (m.receivedDate ?: m.sentDate)?.time ?: 0L, dossier.getUID(m))
                }.getOrNull()
            }.sortedByDescending { it.date }
        } finally {
            runCatching { if (dossier?.isOpen == true) dossier.close(false) }
            runCatching { st.close() }
        }
    }

    // ---------- Boite protegee (2.0) ----------

    /**
     * Range les mails importants dans « Protégés / Banque », « Protégés / Famille »... de la messagerie.
     * Gmail : simple libelle ajoute (le mail reste dans la boite de reception, aucune place en plus).
     * Autres messageries : le mail est deplace dans le dossier protege.
     * Famille = expediteurs presents dans les contacts du telephone. Renvoie le nombre de mails ranges par dossier.
     */
    fun proteger(contacts: Set<String>, progress: (String, Int) -> Unit): Map<String, Int> {
        progress(Tr.t("Connexion au serveur"), 2)
        val st = connect()
        var inbox: IMAPFolder? = null
        val gmail = account().host.contains("gmail", true)
        try {
            inbox = (st.getFolder("INBOX") as IMAPFolder).apply { open(Folder.READ_WRITE) }
            val total = inbox.messageCount
            val start = maxOf(1, total - 3000 + 1)
            val parDossier = HashMap<String, MutableList<Message>>()
            var a = total; var lu = 0
            while (a >= start) {
                val b = maxOf(start, a - 299)
                val msgs = inbox.getMessages(b, a)
                inbox.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add("List-Unsubscribe") })
                for (m in msgs) runCatching {
                    val ia = m.from?.firstOrNull() as? InternetAddress
                    val email = ia?.address?.lowercase() ?: return@runCatching
                    val nom = ia.personal ?: ""
                    val news = !m.getHeader("List-Unsubscribe").isNullOrEmpty()
                    val dossier = MailTri.categorie(email, nom)
                        ?: (if (!news && email in contacts) "Famille" else null)
                        ?: (if (Regles.garder(email)) "Gardes" else null)
                    if (dossier != null) parDossier.getOrPut(dossier) { ArrayList() } += m
                }
                lu += msgs.size
                progress(Tr.t("Recherche des mails importants (%1 / %2)", lu, total - start + 1), 5 + 80 * lu / (total - start + 1).coerceAtLeast(1))
                a = b - 1
            }
            val sep = runCatching { st.defaultFolder.separator }.getOrDefault('/')
            val racine = st.getFolder("Protégés")
            if (!racine.exists()) racine.create(Folder.HOLDS_FOLDERS or Folder.HOLDS_MESSAGES)
            val res = LinkedHashMap<String, Int>()
            parDossier.entries.forEachIndexed { i, (nom, msgs) ->
                progress(Tr.t("Rangement : %1", nom), 85 + 14 * i / parDossier.size.coerceAtLeast(1))
                val f = st.getFolder("Protégés$sep$nom")
                if (!f.exists()) f.create(Folder.HOLDS_MESSAGES)
                val arr = msgs.toTypedArray()
                if (gmail) inbox.copyMessages(arr, f)                       // Gmail : un libelle en plus
                else runCatching { inbox.moveMessages(arr, f) }.getOrElse {
                    inbox.copyMessages(arr, f); inbox.setFlags(arr, Flags(Flags.Flag.DELETED), true); inbox.expunge()
                }
                res[nom] = arr.size
            }
            progress(Tr.t("Terminé"), 100)
            return res
        } finally {
            runCatching { if (inbox?.isOpen == true) inbox.close(false) }
            runCatching { st.close() }
        }
    }

    // ---------- Alertes (2.0) ----------

    /** Les nouveaux mails des expediteurs surveilles depuis le dernier passage. Renvoie (nouvel UID max, mails). */
    fun nouveauxDe(surveilles: Set<String>, depuisUid: Long): Pair<Long, List<BigMail>> {
        val st = connect()
        var inbox: IMAPFolder? = null
        try {
            inbox = (st.getFolder("INBOX") as IMAPFolder).apply { open(Folder.READ_ONLY) }
            val prochain = inbox.uidNext
            if (depuisUid <= 0 || surveilles.isEmpty()) return (prochain - 1) to emptyList()
            val msgs = inbox.getMessagesByUID(depuisUid + 1, UIDFolder.LASTUID).filterNotNull().toTypedArray()
            inbox.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(UIDFolder.FetchProfileItem.UID) })
            val trouves = msgs.mapNotNull { m ->
                runCatching {
                    val ia = m.from?.firstOrNull() as? InternetAddress
                    val e = ia?.address?.lowercase() ?: return@runCatching null
                    if (inbox.getUID(m) <= depuisUid || e !in surveilles) null
                    else BigMail(ia.personal?.takeIf { it.isNotBlank() } ?: e, m.subject ?: "", 0, (m.receivedDate ?: m.sentDate)?.time ?: 0L, inbox.getUID(m))
                }.getOrNull()
            }
            return maxOf(depuisUid, prochain - 1) to trouves
        } finally {
            runCatching { if (inbox?.isOpen == true) inbox.close(false) }
            runCatching { st.close() }
        }
    }

    // ---------- Remettre des mails depuis la corbeille ----------

    /** Les 200 derniers mails de la corbeille du compte. */
    fun listTrash(): List<TrashedMail> {
        val st = connect()
        var trash: IMAPFolder? = null
        try {
            trash = (findTrash(st) ?: throw MailException(Tr.t("Dossier Corbeille introuvable sur ce compte."))) as IMAPFolder
            trash.open(Folder.READ_ONLY)
            val total = trash.messageCount
            if (total == 0) return emptyList()
            val msgs = trash.getMessages(maxOf(1, total - 199), total)
            trash.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(UIDFolder.FetchProfileItem.UID) })
            return msgs.reversed().mapNotNull { m ->
                runCatching {
                    val ia = m.from?.firstOrNull() as? InternetAddress
                    TrashedMail(trash.getUID(m), ia?.personal ?: ia?.address ?: "", m.subject ?: "",
                        (m.receivedDate ?: m.sentDate)?.time ?: 0L)
                }.getOrNull()
            }
        } finally {
            runCatching { if (trash?.isOpen == true) trash.close(false) }
            runCatching { st.close() }
        }
    }

    /** Remet dans la boite de reception les mails choisis de la corbeille. */
    fun restore(uids: List<Long>): Int {
        if (uids.isEmpty()) return 0
        val st = connect()
        var trash: IMAPFolder? = null
        try {
            trash = (findTrash(st) ?: throw MailException(Tr.t("Dossier Corbeille introuvable."))) as IMAPFolder
            trash.open(Folder.READ_WRITE)
            val inbox = st.getFolder("INBOX")
            val msgs = trash.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            try {
                trash.moveMessages(msgs, inbox)
            } catch (e: Exception) {
                trash.copyMessages(msgs, inbox)
                trash.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
                try { trash.expunge(msgs) } catch (x: Exception) { trash.expunge() }
            }
            return msgs.size
        } finally {
            runCatching { if (trash?.isOpen == true) trash.close(false) }
            runCatching { st.close() }
        }
    }

    // ---------- Sauvegarde de mails choisis ----------

    /** Les mails recents qui ont des pieces jointes (sans telecharger les fichiers). */
    fun listWithAttachments(max: Int, progress: (String, Int) -> Unit): List<MailWithFiles> {
        progress(Tr.t("Connexion au serveur"), 2)
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
                progress(Tr.t("Recherche des pièces jointes (%1 / %2)", read, toRead), 5 + 90 * read / toRead.coerceAtLeast(1))
                a = b - 1
            }
            progress(Tr.t("Terminé"), 100)
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
                progress(Tr.t("Sauvegarde du mail %1 / %2", i + 1, uids.size), 100 * (i + 1) / uids.size.coerceAtLeast(1))
            }
        } finally {
            runCatching { if (inbox?.isOpen == true) inbox.close(false) }
            runCatching { st.close() }
        }
    }

    /** Le dossier Spam / Courrier indesirable. */
    private fun findSpam(st: Store): Folder? {
        runCatching {
            for (f in st.defaultFolder.list("*")) {
                if (f is IMAPFolder && f.attributes.any { it.equals("\\Junk", true) }) return f
            }
        }
        val names = listOf("[Gmail]/Spam", "[Google Mail]/Spam", "Spam", "Junk", "Junk Email", "Courrier indésirable", "Courrier indesirable",
            "INBOX.Spam", "INBOX.Junk", "Bulk Mail", "QUARANTAINE")
        for (n in names) runCatching { val f = st.getFolder(n); if (f.exists()) return f }
        return null
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
