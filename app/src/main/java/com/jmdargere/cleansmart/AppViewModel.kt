package com.jmdargere.cleansmart

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jmdargere.cleansmart.BuildConfig
import com.jmdargere.cleansmart.data.Backup
import com.jmdargere.cleansmart.data.ContactsCleaner
import com.jmdargere.cleansmart.data.ContactsResult
import com.jmdargere.cleansmart.data.MailAccount
import com.jmdargere.cleansmart.data.MailCleaner
import com.jmdargere.cleansmart.data.MailCriteria
import com.jmdargere.cleansmart.data.MailResult
import com.jmdargere.cleansmart.data.MailWithFiles
import com.jmdargere.cleansmart.data.MediaGroup
import com.jmdargere.cleansmart.data.MediaItem
import com.jmdargere.cleansmart.data.MediaResult
import com.jmdargere.cleansmart.data.MediaScanner
import com.jmdargere.cleansmart.data.Night
import com.jmdargere.cleansmart.data.Provider
import com.jmdargere.cleansmart.data.Souvenir
import com.jmdargere.cleansmart.data.VideoCompressor
import com.jmdargere.cleansmart.data.Settings
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Les categories de photos/videos proposees au nettoyage. */
enum class Cat(val emoji: String, private val titreFr: String, private val aideFr: String, val grouped: Boolean) {
    DUPLICATES("🧬", "Doublons", "Copies identiques — l'original est gardé", true),
    SIMILAR("🎞️", "Photos similaires", "Rafales — la plus nette est gardée", true),
    BLURRY("🌫️", "Photos floues ou ratées", "Floues, trop sombres ou trop claires — vérifie avant de cocher", false),
    SCREENS("📱", "Captures d'écran", "Souvent inutiles après coup", false),
    BIG_VIDEOS("🎬", "Grosses vidéos", "Plus de 100 Mo chacune", false),
    WHATSAPP("💬", "Médias WhatsApp", "Photos et vidéos reçues ou envoyées", false),
    BIGGEST("🐘", "Mes plus gros fichiers", "Les 30 photos et vidéos qui prennent le plus de place", false);

    /** Traduits a chaque affichage (le changement de langue s'applique tout de suite). */
    val title: String get() = Tr.t(titreFr)
    val hint: String get() = Tr.t(aideFr)
}

/** Travail en cours : affiche le sablier avec le temps passe et le temps restant estime. */
data class Busy(val title: String, val step: String = "", val pct: Int = 0, val start: Long = System.currentTimeMillis())
data class Storage(val total: Long = 0, val free: Long = 0) {
    val used get() = total - free
    val ratio get() = if (total > 0) used.toFloat() / total else 0f
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val scanner = MediaScanner(app)
    private val mail = MailCleaner(app)
    private val contactsCleaner = ContactsCleaner(app)
    private val backup = Backup(app)
    private val prefs = app.getSharedPreferences("compteur", Context.MODE_PRIVATE)

    var settings by mutableStateOf(Settings.load(app)); private set
    var storage by mutableStateOf(Storage()); private set
    var busy by mutableStateOf<Busy?>(null); private set
    var message by mutableStateOf<String?>(null)

    var media by mutableStateOf<MediaResult?>(null); private set
    var selected by mutableStateOf(setOf<String>()); private set
    var contacts by mutableStateOf<ContactsResult?>(null); private set
    var mailAccount by mutableStateOf(MailAccount()); private set
    var mailResult by mutableStateOf<MailResult?>(null); private set
    var freed by mutableLongStateOf(prefs.getLong("freed", 0)); private set

    // ------------------------------------------------------------------ mode demonstration

    /** Mode demonstration : fausses photos / mails / contacts, toutes les actions sont simulees. */
    var demo by mutableStateOf(prefs.getBoolean("demo", false)); private set

    fun changerDemo(on: Boolean) {
        demo = on
        prefs.edit().putBoolean("demo", on).apply()
        media = null; contacts = null; mailResult = null; selected = emptySet()
        swipeList = null; swipeTrash = emptyList(); swipeKeep = emptyList()
        guideDone = emptySet(); mailGuideDone = emptySet(); lastTrashed = emptyList()
        trash = emptyList()
        mailAccount = if (on) com.jmdargere.cleansmart.data.Demo.compte else mail.account()
        refreshStorage()
        if (!on) refreshTrash()
        say(if (on) Tr.t("🎬 Mode démonstration : rien n'est touché sur le téléphone") else Tr.t("Mode démonstration terminé"))
    }

    /** Petite progression factice pour la demonstration (le sablier tourne quelques secondes). */
    private suspend fun demoAttente(titre: String) {
        for (p in 0..100 step 10) { upd(titre, Tr.t("Démonstration…"), p); kotlinx.coroutines.delay(180) }
    }

    /** Elements en attente de la confirmation Android pour la corbeille. */
    private var pendingTrash: List<MediaItem> = emptyList()

    /** Le widget demande d'ouvrir directement le Menage guide. */
    var ouvrirGuide by mutableStateOf(false)

    /** Nom de la nouvelle version disponible sur GitHub (null = a jour). */
    var nouvelleVersion by mutableStateOf<String?>(null); private set

    fun verifierMiseAJour(forcer: Boolean) {
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) { com.jmdargere.cleansmart.data.MiseAJour.verifier(ctx, forcer) }
            nouvelleVersion = v
            if (forcer && v == null) say(Tr.t("👍 Tu as déjà la dernière version (%1)", BuildConfig.VERSION_NAME))
        }
    }

    init {
        Tr.choix = when (settings.langue) { 1 -> "fr"; 2 -> "en"; else -> null }
        com.jmdargere.cleansmart.data.Regles.init(app)
        com.jmdargere.cleansmart.data.AlertesMail.programmer(app)
        Night.schedule(app, settings)
        verifierMiseAJour(false)
        refreshStorage()
        viewModelScope.launch {
            mailAccount = if (demo) com.jmdargere.cleansmart.data.Demo.compte else withContext(Dispatchers.IO) { mail.account() }
        }
    }

    fun refreshStorage() {
        if (demo) { storage = Storage(128_000_000_000, 9_500_000_000 + freed); return }
        storage = runCatching {
            val st = StatFs(Environment.getDataDirectory().path)
            Storage(st.totalBytes, st.availableBytes)
        }.getOrDefault(Storage())
    }

    private fun say(t: String) { message = t }

    private fun progress(title: String): (String, Int) -> Unit = { step, pct ->
        viewModelScope.launch(Dispatchers.Main) { upd(title, step, pct) }
    }

    /** Met a jour la fenetre du sablier en gardant l'heure de depart (pour le temps passe / restant). */
    private fun upd(title: String, step: String, pct: Int) {
        val b = busy ?: return
        busy = b.copy(title = title, step = step, pct = pct.coerceIn(0, 100))
    }

    /** Bandeau vert « TERMINÉ » affiche a la fin d'un travail long. */
    var done by mutableStateOf<String?>(null)

    private fun run(title: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = Busy(title)
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            var ok = false
            try { block(); ok = true } catch (e: Exception) { say(e.message ?: Tr.t("Erreur inattendue")) }
            finally {
                busy = null
                refreshStorage()
                if (ok && System.currentTimeMillis() - start > 2500) done = title
            }
        }
    }

    // ------------------------------------------------------------------ analyse

    fun analyse(withContacts: Boolean) = run(Tr.t("Analyse du téléphone")) {
        val p = progress(Tr.t("Analyse du téléphone"))
        if (demo) demoAttente(Tr.t("Analyse du téléphone"))
        val r = if (demo) com.jmdargere.cleansmart.data.Demo.media() else withContext(Dispatchers.IO) { scanner.scan(settings, p) }
        if (demo) {
            media = r
            selected = (r.duplicates + r.similar).flatMap { g -> g.items.filter { it.key != g.keep }.map { it.key } }.toSet()
            contacts = com.jmdargere.cleansmart.data.Demo.contacts()
            return@run
        }
        media = r
        // pre-selection : seulement les copies et les photos ratees (jamais celle a garder)
        selected = (r.duplicates + r.similar).flatMap { g -> g.items.filter { it.key != g.keep }.map { it.key } }.toSet()
        if (withContacts) contacts = withContext(Dispatchers.IO) { runCatching { contactsCleaner.scan() }.getOrNull() }
        trash = withContext(Dispatchers.IO) { runCatching { scanner.trashed() }.getOrDefault(emptyList()) }
        saveExcluded(discardKeys())
    }

    fun groups(c: Cat): List<MediaGroup> = media?.let {
        when (c) {
            Cat.DUPLICATES -> it.duplicates
            Cat.SIMILAR -> it.similar
            Cat.BLURRY -> it.blurry.map { m -> MediaGroup(listOf(m), "") }
            Cat.SCREENS -> it.screens.map { m -> MediaGroup(listOf(m), "") }
            Cat.BIG_VIDEOS -> it.bigVideos.map { m -> MediaGroup(listOf(m), "") }
            Cat.WHATSAPP -> it.whatsapp.map { m -> MediaGroup(listOf(m), "") }
            Cat.BIGGEST -> it.biggest.map { m -> MediaGroup(listOf(m), "") }
        }
    } ?: emptyList()

    /** Ce qui peut partir dans une categorie (sans les elements a garder). */
    fun removable(c: Cat): List<MediaItem> = groups(c).flatMap { g -> g.items.filter { it.key != g.keep } }

    private fun itemByKey(): Map<String, MediaItem> =
        Cat.entries.flatMap { c -> groups(c).flatMap { it.items } }.associateBy { it.key }

    val selectedBytes: Long get() { val m = itemByKey(); return selected.sumOf { m[it]?.size ?: 0L } }

    fun toggle(key: String) { selected = if (key in selected) selected - key else selected + key }

    fun selectAll(c: Cat, on: Boolean) {
        val keys = removable(c).map { it.key }.toSet()
        selected = if (on) selected + keys else selected - keys
    }

    // ------------------------------------------------------------------ nettoyage photos

    /** Sauvegarde si besoin, puis demande a Android la mise a la corbeille de la selection. */
    fun prepareTrash(onReady: (IntentSender) -> Unit) {
        val m = itemByKey()
        trashItems(selected.mapNotNull { m[it] }, onReady)
    }

    private fun trashItems(items: List<MediaItem>, onReady: (IntentSender) -> Unit) {
        if (demo) { pendingTrash = items; onTrashResult(items.isNotEmpty()); return }
        trashItemsVrai(items, onReady)
    }

    private fun trashItemsVrai(items: List<MediaItem>, onReady: (IntentSender) -> Unit) = run(Tr.t("Préparation")) {
        if (items.isEmpty()) return@run
        val tree = settings.backupTree
        if (settings.backupBeforeDelete && backup.isUsable(tree)) {
            val n = withContext(Dispatchers.IO) { backup.saveMedia(tree!!, items, progress(Tr.t("Sauvegarde avant suppression"))) }
            if (n > 0) say(Tr.t("%1 fichier(s) sauvegardé(s)", fmtNum(n)))
        }
        pendingTrash = items
        val pi = MediaStore.createTrashRequest(ctx.contentResolver, items.map { it.uri }, true)
        onReady(pi.intentSender)
    }

    fun onTrashResult(ok: Boolean) {
        val gone = pendingTrash.map { it.key }.toSet()
        val bytes = pendingTrash.sumOf { it.size }
        val pendingTrashDone = pendingTrash
        pendingTrash = emptyList()
        if (!ok) { say(Tr.t("Rien n'a été supprimé.")); return }
        media = media?.let { r ->
            fun List<MediaItem>.clean() = filter { it.key !in gone }
            fun List<MediaGroup>.cleanG() = map { g -> g.copy(items = g.items.clean()) }
                .filter { g -> g.items.size > 1 && g.items.any { it.key == g.keep } }
            r.copy(duplicates = r.duplicates.cleanG(), similar = r.similar.cleanG(),
                screens = r.screens.clean(), bigVideos = r.bigVideos.clean(), whatsapp = r.whatsapp.clean(),
                blurry = r.blurry.clean(), biggest = r.biggest.clean())
        }
        selected = selected - gone
        saveLastTrashed(pendingTrashDone)
        swipeList?.let { l ->
            swipeIndex = (swipeIndex - l.take(swipeIndex).count { it.key in gone }).coerceAtLeast(0)
            swipeList = l.filter { it.key !in gone }
        }
        swipeTrash = swipeTrash.filter { it.key !in gone }
        if (pendingGuideStep != 0) { guideDone = guideDone + pendingGuideStep; pendingGuideStep = 0 }
        addFreed(bytes)
        refreshStorage()
        refreshTrash()
        say(Tr.t("✨ %1 libérés (récupérables 30 jours dans la corbeille de la Galerie)", fmtBytes(bytes)))
    }

    // ------------------------------------------------------------------ "J'ai fait une betise" (annuler)

    /** Les fichiers de la derniere mise a la corbeille (gardes meme si l'appli est fermee). */
    var lastTrashed by mutableStateOf(loadLastTrashed()); private set

    private fun loadLastTrashed(): List<android.net.Uri> =
        (prefs.getString("lastTrashed", "") ?: "").split('\n').filter { it.isNotBlank() }.map { android.net.Uri.parse(it) }

    private fun saveLastTrashed(items: List<MediaItem>) {
        lastTrashed = items.map { it.uri }
        prefs.edit().putString("lastTrashed", lastTrashed.joinToString("\n")).apply()
    }

    /** Remet dans la galerie les fichiers de la derniere mise a la corbeille. */
    fun prepareUndo(onReady: (IntentSender) -> Unit) {
        if (lastTrashed.isEmpty()) return
        if (demo) { onUndoResult(true); return }
        runCatching { MediaStore.createTrashRequest(ctx.contentResolver, lastTrashed, false) }
            .onSuccess { onReady(it.intentSender) }
            .onFailure { say(Tr.t("Impossible d'annuler : les fichiers ont peut-être déjà été supprimés définitivement.")) }
    }

    fun onUndoResult(ok: Boolean) {
        if (!ok) return
        say(Tr.t("↩️ %1 remis dans la galerie", plural(lastTrashed.size, "fichier")))
        lastTrashed = emptyList()
        prefs.edit().remove("lastTrashed").apply()
        refreshTrash()
        refreshStorage()
    }

    // ------------------------------------------------------------------ proteger des photos (= favoris Android)

    private var pendingProtect: List<MediaItem> = emptyList()

    fun selectedIn(c: Cat): List<MediaItem> = groups(c).flatMap { it.items }.filter { it.key in selected }.distinctBy { it.key }

    fun prepareProtect(items: List<MediaItem>, onReady: (IntentSender) -> Unit) {
        if (items.isEmpty()) return
        pendingProtect = items
        if (demo) { onProtectResult(true); return }
        runCatching { MediaStore.createFavoriteRequest(ctx.contentResolver, items.map { it.uri }, true) }
            .onSuccess { onReady(it.intentSender) }
            .onFailure { say(it.message ?: Tr.t("Impossible de protéger ces photos")) }
    }

    fun onProtectResult(ok: Boolean) {
        val keys = pendingProtect.map { it.key }.toSet()
        pendingProtect = emptyList()
        if (!ok) return
        selected = selected - keys
        // une photo protegee n'est plus jamais proposee
        if (settings.protectFavorites) media = media?.let { r ->
            fun List<MediaItem>.clean() = filter { it.key !in keys }
            fun List<MediaGroup>.cleanG() = map { g -> g.copy(items = g.items.filter { it.key == g.keep || it.key !in keys }) }
                .filter { it.items.size > 1 }
            r.copy(duplicates = r.duplicates.cleanG(), similar = r.similar.cleanG(), screens = r.screens.clean(),
                bigVideos = r.bigVideos.clean(), whatsapp = r.whatsapp.clean(), blurry = r.blurry.clean(), biggest = r.biggest.clean())
        }
        swipeKeep = swipeKeep.filter { it.key !in keys }
        say(Tr.t("🛡️ %1 (mises en favori)", plural(keys.size, Tr.t("photo protégée"), Tr.t("photos protégées"))))
    }

    // ------------------------------------------------------------------ tri rapide (une photo a la fois)

    var swipeList by mutableStateOf<List<MediaItem>?>(null); private set
    var swipeIndex by mutableIntStateOf(0); private set
    var swipeTrash by mutableStateOf<List<MediaItem>>(emptyList()); private set
    /** Photos gardees au tri rapide (glissees a droite) : on propose de les mettre en favori. */
    var swipeKeep by mutableStateOf<List<MediaItem>>(emptyList()); private set

    fun loadSwipe() = run(Tr.t("Tri rapide")) {
        swipeList = if (demo) com.jmdargere.cleansmart.data.Demo.recentes()
            else withContext(Dispatchers.IO) { scanner.recentPhotos(settings, 1000) }
        swipeIndex = 0
        swipeTrash = emptyList()
        swipeKeep = emptyList()
        pendingGuideStep = 0
    }

    /** jeter = true : la photo part dans la pile "a jeter" ; false : on la garde. */
    fun swipe(jeter: Boolean) {
        val it = swipeList?.getOrNull(swipeIndex) ?: return
        if (jeter) swipeTrash = swipeTrash + it else swipeKeep = swipeKeep + it
        swipeIndex++
        // menage guide : la verification est faite quand on a tout regarde
        if (pendingGuideStep == 2 && swipeIndex >= (swipeList?.size ?: 0)) guideDone = guideDone + 2
    }

    fun swipeUndo() {
        val l = swipeList ?: return
        if (swipeIndex == 0) return
        swipeIndex--
        val back = l[swipeIndex]
        swipeTrash = swipeTrash.filter { it.key != back.key }
        swipeKeep = swipeKeep.filter { it.key != back.key }
    }

    fun prepareSwipeTrash(onReady: (IntentSender) -> Unit) = trashItems(swipeTrash, onReady)

    /** Les photos gardees au tri rapide passent en favori : protegees et dans « Mes meilleures ». */
    fun prepareSwipeKeep(onReady: (IntentSender) -> Unit) {
        val l = swipeKeep.filter { !it.favorite }
        if (l.isEmpty()) { say(Tr.t("Elles sont déjà toutes en favori 👍")); return }
        prepareProtect(l, onReady)
    }

    // ------------------------------------------------------------------ corbeille Android

    /** Contenu de la corbeille (nombre, taille) : ces fichiers prennent encore de la place. */
    var trash by mutableStateOf<List<MediaItem>>(emptyList()); private set

    fun refreshTrash() {
        if (demo) return
        viewModelScope.launch {
            trash = withContext(Dispatchers.IO) { runCatching { scanner.trashed() }.getOrDefault(emptyList()) }
        }
    }

    /** Suppression definitive de la corbeille (Android demande confirmation). */
    fun prepareEmptyTrash(onReady: (IntentSender) -> Unit) {
        val items = trash
        if (items.isEmpty()) return
        runCatching { MediaStore.createDeleteRequest(ctx.contentResolver, items.map { it.uri }) }
            .onSuccess { onReady(it.intentSender) }
            .onFailure { say(it.message ?: Tr.t("Impossible de vider la corbeille")) }
    }

    fun onEmptyTrashResult(ok: Boolean) {
        val bytes = trash.sumOf { it.size }
        if (ok) say(Tr.t("🗑️ Corbeille vidée : %1 récupérés pour de bon", fmtBytes(bytes)))
        refreshTrash()
        refreshStorage()
    }

    // ------------------------------------------------------------------ bilan des mails (2.0)

    /** Mails mis a la corbeille et place liberee sur le compte mail, depuis le debut. */
    var mailJetes by mutableIntStateOf(prefs.getInt("mailJetes", 0)); private set
    var mailLibere by mutableLongStateOf(prefs.getLong("mailLibere", 0)); private set

    /** Met des mails a la corbeille du compte et les compte dans le bilan. */
    private suspend fun jeterMails(c: MailCriteria, dansSpam: Boolean = false): Int {
        val n = withContext(Dispatchers.IO) { mail.trash(c, dansSpam) }
        mailJetes += n; mailLibere += mail.derniersOctets
        prefs.edit().putInt("mailJetes", mailJetes).putLong("mailLibere", mailLibere).apply()
        return n
    }

    /** Compte Gmail : les mails et Google Photos partagent les memes 15 Go. */
    val mailGmail: Boolean get() = mailAccount.email.endsWith("@gmail.com", true) || mailAccount.email.endsWith("@googlemail.com", true)

    private fun addFreed(b: Long) { freed += b; prefs.edit().putLong("freed", freed).apply() }
    fun resetFreed() {
        freed = 0; mailJetes = 0; mailLibere = 0
        prefs.edit().putLong("freed", 0).putInt("mailJetes", 0).putLong("mailLibere", 0).apply()
    }

    // ------------------------------------------------------------------ contacts

    fun deleteDuplicateContacts() = run(Tr.t("Contacts en double")) {
        if (demo) { demoAttente(Tr.t("Contacts en double")); contacts = ContactsResult(642, emptyList()); say(Tr.t("✅ Doublons supprimés (démonstration)")); return@run }
        val r = contacts ?: return@run
        val ids = r.groups.flatMap { it.remove }
        if (ids.isEmpty()) return@run
        val tree = settings.backupTree
        if (settings.backupBeforeDelete && backup.isUsable(tree)) {
            withContext(Dispatchers.IO) { backup.saveContacts(tree!!, contactsCleaner.lookupKeys(ids)) }
        }
        val n = withContext(Dispatchers.IO) { contactsCleaner.delete(ids) }
        contacts = withContext(Dispatchers.IO) { contactsCleaner.scan() }
        say(Tr.t("✅ %1 doublon(s) supprimé(s)", fmtNum(n)))
    }

    // ------------------------------------------------------------------ mails

    fun connectMail(p: Provider, host: String, email: String, password: String, onOk: () -> Unit) = run(Tr.t("Connexion à la messagerie")) {
        if (demo) { mailAccount = com.jmdargere.cleansmart.data.Demo.compte; onOk(); return@run }
        withContext(Dispatchers.IO) { mail.saveAndTest(p, host.ifBlank { p.host }, email, password) }
        mailAccount = withContext(Dispatchers.IO) { mail.account() }
        mailResult = null
        say(Tr.t("✅ Boîte mail connectée"))
        onOk()
    }

    fun forgetMail() {
        mail.forget()
        mailAccount = MailAccount()
        mailResult = null
    }

    fun analyseMail() = run(Tr.t("Analyse de la boîte mail")) {
        if (demo) { demoAttente(Tr.t("Analyse de la boîte mail")); mailResult = com.jmdargere.cleansmart.data.Demo.mails(); return@run }
        mailResult = withContext(Dispatchers.IO) { mail.scan(3000, settings, progress(Tr.t("Analyse de la boîte mail"))) }
    }

    fun trashNewsletters() = mailResult?.senders?.filter { it.newsletter }?.map { it.email }?.let {
        mailVoir(Tr.t("📰 Newsletters et pubs"), MailCriteria.From(it))
    }

    fun trashDoublons() = mailResult?.doublons?.takeIf { it.isNotEmpty() }?.let { mailVoir(Tr.t("👯 Mails en double"), MailCriteria.Uids(it)) }

    // corbeille de la messagerie : remettre des mails
    var mailTrash by mutableStateOf<List<com.jmdargere.cleansmart.data.TrashedMail>?>(null); private set
    var mailTrashSel by mutableStateOf(setOf<Long>()); private set

    fun loadMailTrash() = run(Tr.t("Corbeille de la messagerie")) {
        if (demo) {
            mailTrash = listOf(com.jmdargere.cleansmart.data.TrashedMail(1, Tr.t("Boutique Mode"), Tr.t("Soldes d'hiver"), System.currentTimeMillis()),
                com.jmdargere.cleansmart.data.TrashedMail(2, Tr.t("Paul"), Tr.t("Photos du week-end"), System.currentTimeMillis() - 86_400_000))
            return@run
        }
        mailTrash = withContext(Dispatchers.IO) { mail.listTrash() }
        mailTrashSel = emptySet()
    }

    fun toggleMailTrash(uid: Long) { mailTrashSel = if (uid in mailTrashSel) mailTrashSel - uid else mailTrashSel + uid }

    fun restoreMails() = run(Tr.t("Remettre des mails")) {
        if (demo) { mailTrash = mailTrash?.filter { it.uid !in mailTrashSel }; mailTrashSel = emptySet(); say(Tr.t("↩️ Mails remis (démonstration)")); return@run }
        val n = withContext(Dispatchers.IO) { mail.restore(mailTrashSel.toList()) }
        say(Tr.t("↩️ %1 dans la boîte de réception", plural(n, Tr.t("mail remis"), Tr.t("mails remis"))))
        mailTrash = withContext(Dispatchers.IO) { mail.listTrash() }
        mailTrashSel = emptySet()
    }

    fun trashBigMails() = mailVoir(Tr.t("📎 Gros mails"), MailCriteria.Big(settings.bigMailMb))
    fun trashOldMails() = mailVoir(Tr.t("🕰️ Vieux mails"), MailCriteria.Old(settings.oldMailYears))

    private fun trashMail(c: MailCriteria) = run(Tr.t("Nettoyage de la boîte mail")) {
        upd(Tr.t("Nettoyage de la boîte mail"), Tr.t("Déplacement vers la corbeille…"), 50)
        val n = jeterMails(c)
        say(Tr.t("✨ %1 mails mis à la corbeille", fmtNum(n)))
        mailResult = withContext(Dispatchers.IO) { mail.scan(3000, settings, progress(Tr.t("Mise à jour"))) }
    }

    // ------------------------------------------------------------------ mails : relire avant de jeter (2.0)

    /** La liste a relire avant de valider (null = rien a relire). */
    var mailRevue by mutableStateOf<com.jmdargere.cleansmart.data.MailRevue?>(null); private set
    private var apresRevue: () -> Unit = {}

    /** Cherche les mails qui partiraient avec [c] et les montre : on decoche, puis on valide. */
    fun mailVoir(titre: String, c: MailCriteria, apres: () -> Unit = {}) = run(Tr.t("Recherche des mails")) {
        val liste = if (demo) com.jmdargere.cleansmart.data.Demo.revue()
                    else withContext(Dispatchers.IO) { mail.apercu(c) }
        if (liste.isEmpty()) { apres(); say(Tr.t("🎉 Aucun mail à jeter ici.")); return@run }
        apresRevue = apres
        mailRevue = com.jmdargere.cleansmart.data.MailRevue(titre, c, liste, liste.map { it.uid }.toSet(), c is MailCriteria.Spam)
    }

    fun revueCocher(uid: Long) { mailRevue = mailRevue?.let { r -> r.copy(coches = if (uid in r.coches) r.coches - uid else r.coches + uid) } }
    fun revueTout(oui: Boolean) { mailRevue = mailRevue?.let { r -> r.copy(coches = if (oui) r.mails.map { it.uid }.toSet() else emptySet()) } }
    fun revueFermer() { mailRevue = null }

    fun revueValider() = run(Tr.t("Nettoyage de la boîte mail")) {
        val r = mailRevue ?: return@run
        mailRevue = null
        if (demo) { demoAttente(Tr.t("Nettoyage de la boîte mail")); apresRevue(); say(Tr.t("✨ %1 mis à la corbeille (démonstration)", plural(r.coches.size, "mail"))); return@run }
        upd(Tr.t("Nettoyage de la boîte mail"), Tr.t("Déplacement vers la corbeille…"), 50)
        val n = jeterMails(MailCriteria.Uids(r.coches.toList()), dansSpam = r.spam)
        apresRevue()
        say(Tr.t("✨ %1 mis à la corbeille de ta messagerie (récupérables avec « Remettre des mails »)", plural(n, "mail")))
        mailResult = withContext(Dispatchers.IO) { runCatching { mail.scan(3000, settings, progress(Tr.t("Mise à jour"))) }.getOrDefault(mailResult) }
    }

    // ------------------------------------------------------------------ regles par expediteur, alertes, boite protegee, spam (2.0)

    /** Change a chaque modification des regles (pour mettre l'ecran a jour). */
    var reglesVersion by mutableIntStateOf(0); private set

    fun regle(cle: String, email: String) {
        com.jmdargere.cleansmart.data.Regles.basculer(ctx, cle, email)
        reglesVersion++
        val r = com.jmdargere.cleansmart.data.Regles
        say(when (cle) {
            "jeter" -> if (email.lowercase() in r.jeter) Tr.t("🗑️ Ses mails partiront à la corbeille chaque nuit") else Tr.t("Règle retirée")
            "garder" -> if (r.garder(email)) Tr.t("🛡️ Ses mails ne seront jamais jetés") else Tr.t("Règle retirée")
            else -> if (email.lowercase() in r.alertes) Tr.t("🔔 Tu seras prévenu quand il t'écrit") else Tr.t("Alerte retirée")
        })
    }

    /** Appliquer tout de suite les regles « toujours jeter » (sinon c'est fait chaque nuit). */
    fun appliquerRegles() = com.jmdargere.cleansmart.data.Regles.jeter.takeIf { it.isNotEmpty() }?.let {
        mailVoir(Tr.t("🗑️ Mes expéditeurs « toujours jeter »"), MailCriteria.From(it.toList()))
    }

    fun voirSpam() = mailVoir(Tr.t("🚫 Spam"), MailCriteria.Spam)

    /** Resultat du dernier rangement dans la boite protegee (dossier -> nombre de mails). */
    var mailProteges by mutableStateOf<Map<String, Int>?>(null); private set

    fun protegerMails() = run(Tr.t("Boîte protégée")) {
        if (demo) { demoAttente(Tr.t("Boîte protégée")); mailProteges = mapOf("Banque" to 214, "Impots - Administration" to 87, "Sante" to 52, "Famille" to 431); return@run }
        val contacts = withContext(Dispatchers.IO) { com.jmdargere.cleansmart.data.Regles.emailsContacts(ctx) }
        mailProteges = withContext(Dispatchers.IO) { mail.proteger(contacts, progress(Tr.t("Boîte protégée"))) }
        ctx.getSharedPreferences("mail_regles", Context.MODE_PRIVATE).edit().putBoolean("protegerAuto", true).apply()
        say(Tr.t("🛡️ %1 rangés dans le dossier « Protégés » de ta messagerie", plural(mailProteges!!.values.sum(), "mail")))
    }

    // ------------------------------------------------------------------ fichiers inutiles (2.0, repris de Nettoyage Pro)

    private val inutiles = com.jmdargere.cleansmart.data.Inutiles(app)
    var junk by mutableStateOf<List<com.jmdargere.cleansmart.data.GroupeInutile>?>(null); private set
    var junkChoix by mutableStateOf(setOf<String>())
    var junkCorbeille by mutableLongStateOf(0L); private set

    fun junkAcces() = inutiles.acces()

    fun junkChercher() = run(Tr.t("Fichiers inutiles")) {
        if (demo) {
            demoAttente(Tr.t("Fichiers inutiles"))
            val f = java.io.File("demo")
            junk = listOf(
                com.jmdargere.cleansmart.data.GroupeInutile("temp", "🗒️", Tr.t("Fichiers temporaires"), Tr.t("Journaux, sauvegardes temporaires, téléchargements abandonnés. Ne servent plus."), List(214) { f }, 412L shl 20, true),
                com.jmdargere.cleansmart.data.GroupeInutile("mini", "🖼️", Tr.t("Miniatures"), Tr.t("Petites copies des photos pour l'affichage. Elles se recréent toutes seules."), List(3120) { f }, 690L shl 20, true),
                com.jmdargere.cleansmart.data.GroupeInutile("vides", "📂", Tr.t("Dossiers vides"), Tr.t("Ils ne contiennent rien."), List(37) { f }, 0, true),
                com.jmdargere.cleansmart.data.GroupeInutile("apk", "📦", Tr.t("Autres fichiers d'installation (.apk)"), Tr.t("Des applis téléchargées mais pas installées. À vérifier."), List(4) { f }, 180L shl 20, false),
            )
            junkChoix = junk!!.filter { it.sur }.map { it.cle }.toSet()
            return@run
        }
        val r = withContext(Dispatchers.IO) { inutiles.chercher(progress(Tr.t("Fichiers inutiles"))) }
        junk = r
        junkChoix = r.filter { it.sur }.map { it.cle }.toSet()
        junkCorbeille = withContext(Dispatchers.IO) { inutiles.tailleCorbeille() }
    }

    fun junkNettoyer() = run(Tr.t("Nettoyage des fichiers inutiles")) {
        val choisis = junk.orEmpty().filter { it.cle in junkChoix }
        if (demo) { demoAttente(Tr.t("Nettoyage des fichiers inutiles")); junk = junk.orEmpty() - choisis.toSet(); say(Tr.t("✨ 1,1 Go libérés (démonstration)")); return@run }
        val (n, octets) = withContext(Dispatchers.IO) { inutiles.nettoyer(choisis, progress(Tr.t("Nettoyage des fichiers inutiles"))) }
        val libere = choisis.filter { it.sur }.sumOf { it.taille }.coerceAtMost(octets)
        addFreed(libere)
        junkCorbeille = withContext(Dispatchers.IO) { inutiles.tailleCorbeille() }
        junk = junk.orEmpty() - choisis.toSet()
        say(Tr.t("✨ %1 nettoyés · %2 libérés", plural(n, Tr.t("élément")), fmtBytes(libere)) +
            if (choisis.any { !it.sur }) Tr.t(" · les fichiers « à vérifier » sont dans la corbeille Clean Smart (7 jours)") else "")
    }

    fun junkRemettre() = run(Tr.t("Remettre les fichiers")) {
        val n = withContext(Dispatchers.IO) { inutiles.remettre() }
        junkCorbeille = 0
        say(Tr.t("↩️ %1 remis à leur place", plural(n, Tr.t("fichier"))))
    }

    fun junkViderCorbeille() = run(Tr.t("Vider la corbeille Clean Smart")) {
        val t = withContext(Dispatchers.IO) { inutiles.viderCorbeille() }
        addFreed(t); junkCorbeille = 0
        say(Tr.t("🗑️ Corbeille vidée : %1 récupérés pour de bon", fmtBytes(t)))
    }

    // ------------------------------------------------------------------ quitter Google Photos (export Takeout)

    /** Un suivi separe pour Google Photos et pour iCloud Photos. */
    private val exports = com.jmdargere.cleansmart.data.SourceExport.entries.associateWith { com.jmdargere.cleansmart.data.GoogleExport(app, it) }
    var google = exports.getValue(com.jmdargere.cleansmart.data.SourceExport.GOOGLE); private set
    var gpSource by mutableStateOf(com.jmdargere.cleansmart.data.SourceExport.GOOGLE); private set

    /** Choisit l'onglet : Google Photos ou iCloud Photos. */
    fun gpUtiliser(s: com.jmdargere.cleansmart.data.SourceExport) {
        if (s != gpSource) gpVerif = null
        google = exports.getValue(s); gpSource = s
        gpTotal = google.total(); gpTaille = google.tailleGoogleGo; gpDest = google.nomDestination(); gpDestOk = google.destinationOk()
        gpNb = google.nbFichiers; gpDemande = google.exportDemande; gpTermine = google.termine; gpEtats = google.etats(); gpDernier = null
    }

    var gpTotal by mutableStateOf(google.total()); private set
    var gpTaille by mutableIntStateOf(google.tailleGoogleGo); private set
    var gpDest by mutableStateOf(google.nomDestination()); private set
    var gpDestOk by mutableStateOf(google.destinationOk()); private set
    var gpNb by mutableIntStateOf(google.nbFichiers); private set
    var gpDemande by mutableStateOf(google.exportDemande); private set
    var gpTermine by mutableStateOf(google.termine); private set
    var gpEtats by mutableStateOf(google.etats()); private set
    var gpDernier by mutableStateOf<Pair<String, com.jmdargere.cleansmart.data.ExportStats>?>(null); private set

    /** Resultat de « Vérifier le disque » (null = pas encore fait). */
    var gpVerif by mutableStateOf<com.jmdargere.cleansmart.data.GoogleExport.Verification?>(null); private set

    fun gpVerifier() = run(Tr.t("Vérification du disque")) {
        if (demo) { demoAttente(Tr.t("Vérification du disque")); gpVerif = com.jmdargere.cleansmart.data.GoogleExport.Verification(8412, emptyList(), emptyList(), 0); return@run }
        gpVerif = withContext(Dispatchers.IO) { google.verifierDisque(progress(Tr.t("Vérification du disque"))) }
    }

    /** Relit l'etat de l'onglet (apres le mode automatique, qui travaille dans un service a part). */
    fun gpRafraichir() = gpUtiliser(gpSource)

    fun gpSetTaille(go: Int) { gpTaille = go; google.tailleGoogleGo = go }
    fun gpSetDest(uri: android.net.Uri) { google.destination = uri; gpDest = google.nomDestination(); gpDestOk = google.destinationOk() }
    fun gpRafraichirDisque() { gpDestOk = google.destinationOk() }
    fun gpSetNb(n: Int) { gpNb = n; google.nbFichiers = n }
    fun gpSetDemande(v: Boolean) { gpDemande = v; google.exportDemande = v }
    fun gpSetTermine(v: Boolean) { gpTermine = v; google.termine = v }

    /** Avancement global : part de la phototheque deja a l'abri (0..1). */
    val gpAvancement: Float get() = if (gpTaille > 0) (gpTotal.octets.toFloat() / (gpTaille * (1L shl 30))).coerceIn(0f, 1f) else 0f

    /** Probleme detecte avant de commencer (telephone plein, disque plein ou debranche...) : affiche avec les conseils. */
    var gpProbleme by mutableStateOf<String?>(null)

    /** Traite un fichier ZIP de l'export Google : rangement par annee, doublons, rafales, verification. */
    fun gpTraiter(zip: android.net.Uri, onFini: (android.net.Uri) -> Unit) = run(Tr.t("Fichier %1", gpSource.nom)) {
        if (demo) { say(Tr.t("En démonstration, aucun fichier n'est traité. Quitte la démonstration (Options) pour le faire vraiment.")); return@run }
        val pb = withContext(Dispatchers.IO) { google.verifierAvant(zip) }
        if (pb != null) { gpProbleme = pb; return@run }
        // la copie a ete effacee du disque : on oublie ce qui avait ete copie, et on recopie tout
        if (withContext(Dispatchers.IO) { google.copieEffacee() }) {
            withContext(Dispatchers.IO) { google.oublierCopies() }
            gpTotal = google.total(); gpEtats = google.etats(); gpTermine = false
            say(Tr.t("La copie sur le disque a été effacée : Clean Smart recopie tout depuis le début."))
        }
        val nom = google.nomDe(zip)
        upd(Tr.t("Fichier %1", gpSource.nom), Tr.t("Ouverture de %1…", nom), 0)
        val s = withContext(Dispatchers.IO) { google.traiterZip(zip, progress(nom)) }
        gpDernier = nom to s
        gpTotal = google.total()
        gpEtats = google.etats()
        val num = google.numero(nom)
        val annonce = google.totalDansNom(nom)          // Apple : « Part 3 of 12 » -> 12 fichiers
        if (maxOf(num, annonce) > gpNb) gpSetNb(maxOf(num, annonce))
        // notification : utile si le telephone est pose ou l'appli en arriere-plan
        Night.notify(ctx, 10,
            if (s.sansProbleme) Tr.t("✅ Fichier %1 terminé", if (num > 0) "%03d".format(num) else nom) else Tr.t("⚠️ Fichier %1 à reprendre", if (num > 0) "%03d".format(num) else nom),
            if (s.sansProbleme) Tr.t("%1 et %2 à l'abri. Tu peux le supprimer et télécharger le suivant.", plural(s.photos, "photo"), plural(s.videos, Tr.t("vidéo")))
            else Tr.t("Ouvre Clean Smart pour voir quoi faire. Rien n'est perdu."))
        onFini(zip)
    }

    fun gpSupprimerZip(zip: android.net.Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { google.supprimerZip(zip) }
            say(if (ok) Tr.t("🗑️ Fichier supprimé du téléphone : place libre pour le suivant")
                else Tr.t("Supprime ce fichier toi-même dans Téléchargements pour faire de la place."))
        }
    }

    /** « J'ai efface ma copie » : tout sera recopie, en gardant le disque et les reglages de l'onglet. */
    fun gpOublierCopies() {
        google.oublierCopies()
        gpTotal = google.total(); gpEtats = google.etats(); gpTermine = false; gpDernier = null
        say(Tr.t("C'est fait : le prochain fichier sera entièrement recopié."))
    }

    fun gpRecommencer() {
        google.remettreAZero()
        gpTotal = google.total(); gpTaille = 0; gpDest = google.nomDestination(); gpDestOk = false; gpDernier = null
        gpNb = 0; gpDemande = false; gpTermine = false; gpEtats = emptyMap()
    }

    // ------------------------------------------------------------------ menage guide des mails (4 etapes)

    var mailGuideDone by mutableStateOf(setOf<Int>()); private set

    /** Etape 1 : newsletters et notifications (jamais les expediteurs importants). */
    val mailSafeSenders: List<com.jmdargere.cleansmart.data.Sender>
        get() = mailResult?.senders?.filter { (it.newsletter || it.notif) && !it.important } ?: emptyList()

    fun mailGuideClean() {
        if (demo) { mailVoir(Tr.t("🧹 Ménage sûr"), MailCriteria.Uids(emptyList())) { mailGuideDone = mailGuideDone + 1 }; return }
        val r = mailResult ?: return
        mailVoir(Tr.t("🧹 Ménage sûr : newsletters, notifications, doublons"),
            MailCriteria.Tous(listOf(MailCriteria.From(mailSafeSenders.map { it.email }), MailCriteria.Uids(r.doublons)))) { mailGuideDone = mailGuideDone + 1 }
    }

    // Etape 2 : tri par expediteur, une carte a la fois (gauche = tout jeter, droite = garder)
    var mailSwipeList by mutableStateOf<List<com.jmdargere.cleansmart.data.Sender>>(emptyList()); private set
    var mailSwipeIndex by mutableIntStateOf(0); private set
    var mailSwipeTrash by mutableStateOf<List<com.jmdargere.cleansmart.data.Sender>>(emptyList()); private set

    fun mailGuideReview() {
        mailSwipeList = mailResult?.senders?.filter { !it.important && !it.newsletter && !it.notif } ?: emptyList()
        mailSwipeIndex = 0
        mailSwipeTrash = emptyList()
    }

    fun mailSwipe(jeter: Boolean) {
        val s = mailSwipeList.getOrNull(mailSwipeIndex) ?: return
        if (jeter) mailSwipeTrash = mailSwipeTrash + s
        mailSwipeIndex++
        if (mailSwipeIndex >= mailSwipeList.size && mailSwipeTrash.isEmpty()) mailGuideDone = mailGuideDone + 2
    }

    fun mailSwipeUndo() {
        if (mailSwipeIndex == 0) return
        mailSwipeIndex--
        val back = mailSwipeList[mailSwipeIndex]
        mailSwipeTrash = mailSwipeTrash.filter { it.email != back.email }
    }

    fun mailSwipeApply() {
        if (demo) { mailSwipeTrash = emptyList(); mailGuideDone = mailGuideDone + 2; say(Tr.t("✨ Mails mis à la corbeille (démonstration)")); return }
        mailVoir(Tr.t("👆 Les expéditeurs que tu as jetés"), MailCriteria.From(mailSwipeTrash.map { it.email })) {
            mailSwipeTrash = emptyList(); mailGuideDone = mailGuideDone + 2
        }
    }

    /** Etape 3 : mettre a l'abri l'important (banque, administration, sante, factures) et tous les mails avec un PDF. */
    fun mailGuideSave() = run(Tr.t("Mettre à l'abri l'important")) {
        if (demo) { demoAttente(Tr.t("Mettre à l'abri l'important")); mailGuideDone = mailGuideDone + 3; say(Tr.t("✅ 312 mails importants à l'abri (démonstration)")); return@run }
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say(Tr.t("Choisis d'abord un dossier de sauvegarde (Options).")); return@run }
        val r = mailResult ?: return@run
        val avecPdf = withContext(Dispatchers.IO) {
            mail.listWithAttachments(3000, progress(Tr.t("Recherche des mails avec PDF"))).filter { it.hasPdf }.map { it.uid }
        }
        val uids = (r.aGarder + avecPdf).distinct()
        val saved = ArrayList<com.jmdargere.cleansmart.data.SavedMail>()
        withContext(Dispatchers.IO) {
            mail.forEachMail(uids, progress(Tr.t("Sauvegarde des mails importants"))) { m -> saved += backup.saveMail(tree!!, m) }
            backup.updateMailIndex(tree!!, saved)
        }
        // et dans la messagerie : la boite protegee (Banque, Impots, Sante, Maison, Famille)
        runCatching {
            val contacts = withContext(Dispatchers.IO) { com.jmdargere.cleansmart.data.Regles.emailsContacts(ctx) }
            mailProteges = withContext(Dispatchers.IO) { mail.proteger(contacts, progress(Tr.t("Boîte protégée"))) }
            ctx.getSharedPreferences("mail_regles", Context.MODE_PRIVATE).edit().putBoolean("protegerAuto", true).apply()
        }
        mailGuideDone = mailGuideDone + 3
        say(Tr.t("✅ %1 mails importants à l'abri · %2 PDF réunis · archive index.html à jour", fmtNum(saved.size), fmtNum(saved.sumOf { it.pdf })))
    }

    /** Etape 4 : liberer la boite (vieux mails, jamais les importants). */
    fun mailGuideFree() {
        if (demo) { mailVoir(Tr.t("🕰️ Vieux mails"), MailCriteria.Uids(emptyList())) { mailGuideDone = mailGuideDone + 4 }; return }
        mailVoir(Tr.t("🕰️ Vieux mails (les importants sont gardés)"), MailCriteria.Old(settings.oldMailYears)) { mailGuideDone = mailGuideDone + 4 }
    }

    // ------------------------------------------------------------------ sauvegarde de mails choisis

    var mailFiles by mutableStateOf<List<MailWithFiles>?>(null); private set
    var mailFilesSel by mutableStateOf(setOf<Long>()); private set

    fun findMailAttachments() = run(Tr.t("Mails avec pièces jointes")) {
        if (demo) {
            mailFiles = listOf(com.jmdargere.cleansmart.data.MailWithFiles(1, Tr.t("Électricité"), Tr.t("Votre facture"), System.currentTimeMillis(), 300_000, listOf("facture.pdf")),
                com.jmdargere.cleansmart.data.MailWithFiles(2, Tr.t("Paul"), Tr.t("Photos du week-end"), System.currentTimeMillis(), 8_000_000, listOf("plage.jpg", "repas.jpg")))
            return@run
        }
        mailFiles = withContext(Dispatchers.IO) { mail.listWithAttachments(3000, progress(Tr.t("Mails avec pièces jointes"))) }
        mailFilesSel = emptySet()
    }

    fun toggleMailFile(uid: Long) { mailFilesSel = if (uid in mailFilesSel) mailFilesSel - uid else mailFilesSel + uid }

    /** Coche tout (ou seulement les mails avec un PDF), ou decoche tout. */
    fun selectMailFiles(mode: String) {
        val l = mailFiles ?: return
        mailFilesSel = when (mode) {
            "pdf" -> l.filter { it.hasPdf }.map { it.uid }.toSet()
            "all" -> l.map { it.uid }.toSet()
            else -> emptySet()
        }
    }

    fun saveSelectedMails() = run(Tr.t("Sauvegarde des mails")) {
        if (demo) { demoAttente(Tr.t("Sauvegarde des mails")); mailFilesSel = emptySet(); say(Tr.t("✅ Mails sauvegardés (démonstration)")); return@run }
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say(Tr.t("Choisis d'abord un dossier de sauvegarde.")); return@run }
        val uids = mailFilesSel.toList()
        val saved = ArrayList<com.jmdargere.cleansmart.data.SavedMail>()
        withContext(Dispatchers.IO) {
            mail.forEachMail(uids, progress(Tr.t("Sauvegarde des mails"))) { m -> saved += backup.saveMail(tree!!, m) }
            backup.updateMailIndex(tree!!, saved)
        }
        mailFilesSel = emptySet()
        say(Tr.t("✅ %1 mail(s) sauvegardé(s) · %2 PDF · archive CleanSmart/Mails/index.html à jour", fmtNum(saved.size), fmtNum(saved.sumOf { it.pdf })))
    }

    // ------------------------------------------------------------------ sauvegarde & options

    fun backupFolderLabel(): String = if (demo) Tr.t("Clé USB (démonstration)") else backup.folderLabel(settings.backupTree)
    fun backupReady(): Boolean = demo || backup.isUsable(settings.backupTree)

    /**
     * Sauvegarde INTELLIGENTE : seulement les bonnes photos. Les doublons en trop, les rafales en trop,
     * les photos ratees, les captures d'ecran et les photos jetees au tri rapide ne sont pas sauvegardees.
     * Les favoris vont en plus dans « Mes meilleures ».
     */
    fun backupEverything() = run(Tr.t("Sauvegarde de mes bonnes photos")) {
        if (demo) { demoAttente(Tr.t("Sauvegarde de mes bonnes photos")); guideDone = guideDone + 3; say(Tr.t("✅ 7 958 bonnes photos sauvegardées (démonstration)")); return@run }
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say(Tr.t("Choisis d'abord un dossier de sauvegarde (Options).")); return@run }
        if (media == null) {
            // on trie d'abord, pour ne pas sauvegarder les doublons et les photos ratees
            media = withContext(Dispatchers.IO) { scanner.scan(settings, progress(Tr.t("Tri avant la sauvegarde"))) }
        }
        val skip = discardKeys()
        saveExcluded(skip)
        val (n, ignored) = withContext(Dispatchers.IO) {
            val all = scanner.allMedia()
            val keep = all.filter { it.key !in skip }
            backup.saveMedia(tree!!, keep, progress(Tr.t("Sauvegarde de mes bonnes photos"))) to (all.size - keep.size)
        }
        guideDone = guideDone + 3
        prefs.edit().putLong("lastBackup", System.currentTimeMillis()).apply()
        say((if (n == 0) Tr.t("Tout était déjà sauvegardé 👍") else Tr.t("✅ %1 nouvelle(s) photo(s) sauvegardée(s)", fmtNum(n))) +
                if (ignored > 0) Tr.t(" · %1 doublons, rafales ou ratées non sauvegardés", fmtNum(ignored)) else "")
    }

    /** Ce qui ne merite pas d'etre sauvegarde (d'apres la derniere analyse et le tri rapide). */
    private fun discardKeys(): Set<String> {
        val r = media ?: return swipeTrash.map { it.key }.toSet()
        return (removable(Cat.DUPLICATES) + removable(Cat.SIMILAR) + r.blurry + r.screens + swipeTrash)
            .map { it.key }.toSet()
    }

    /** Memorise la liste pour la sauvegarde de nuit (qui ne refait pas toute l'analyse). */
    private fun saveExcluded(keys: Set<String>) {
        ctx.getSharedPreferences("nuit", Context.MODE_PRIVATE).edit().putStringSet("exclure", keys).apply()
    }

    // ------------------------------------------------------------------ menage guide (4 etapes)

    /** Etapes deja faites pendant cette session : 1 menage sur, 2 verification, 3 sauvegarde, 4 liberer. */
    var guideDone by mutableStateOf(setOf<Int>()); private set
    private var pendingGuideStep = 0

    /** Etape 1 : ce qui part sans hesiter (doublons, rafales en trop, photos noires/blanches, vieilles captures). */
    val safeItems: List<MediaItem>
        get() {
            val r = media ?: return emptyList()
            val oldScreens = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            return (removable(Cat.DUPLICATES) + removable(Cat.SIMILAR) +
                    r.blurry.filter { it.note == "Trop sombre" || it.note == "Trop claire" } +
                    r.screens.filter { it.date < oldScreens }).distinctBy { it.key }
        }

    /** Etape 2 : le douteux, a regarder soi-meme (photos floues ou petites, photos WhatsApp, captures recentes). */
    val reviewItems: List<MediaItem>
        get() {
            val r = media ?: return emptyList()
            val safe = safeItems.map { it.key }.toSet()
            return (r.blurry + r.whatsapp.filter { !it.video } + r.screens)
                .filter { it.key !in safe }.distinctBy { it.key }.sortedByDescending { it.date }
        }

    fun guideClean(onReady: (IntentSender) -> Unit) {
        pendingGuideStep = 1
        trashItems(safeItems, onReady)
    }

    fun guideReview() {
        swipeList = reviewItems
        swipeIndex = 0
        swipeTrash = emptyList()
        swipeKeep = emptyList()
        pendingGuideStep = 2
    }

    fun guideMark(step: Int) { guideDone = guideDone + step }

    fun updateSettings(s: Settings) {
        Tr.choix = when (s.langue) { 1 -> "fr"; 2 -> "en"; else -> null }
        settings = s
        Settings.save(ctx, s)
        Night.schedule(ctx, s)
    }

    // ------------------------------------------------------------------ liberer la place (photos deja sauvegardees)

    /** Photos/videos de plus de N mois, deja presentes dans la sauvegarde : proposees a la corbeille. */
    var freeCandidates by mutableStateOf<List<MediaItem>?>(null); private set

    fun findFreeSpace() = run(Tr.t("Libérer la place")) {
        if (demo) { demoAttente(Tr.t("Libérer la place")); guideDone = guideDone + 4; addFreed(6_400_000_000); say(Tr.t("✨ 6,4 Go libérés (démonstration)")); return@run }
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say(Tr.t("Choisis d'abord un dossier de sauvegarde et fais une sauvegarde.")); return@run }
        val limit = System.currentTimeMillis() - settings.keepMonths * 30L * 24 * 3600 * 1000
        freeCandidates = withContext(Dispatchers.IO) {
            val old = scanner.allMedia().filter { it.date in 1 until limit && !(settings.protectFavorites && it.favorite) }
            backup.alreadySaved(tree!!, old, progress(Tr.t("Libérer la place")))
        }
        if (freeCandidates.isNullOrEmpty()) say(Tr.t("Rien à libérer : aucune photo ancienne n'est encore dans la sauvegarde. Lance d'abord « Sauvegarder toutes mes photos »."))
    }

    fun prepareFreeSpace(onReady: (IntentSender) -> Unit) {
        val l = freeCandidates ?: return
        freeCandidates = null
        pendingGuideStep = 4
        trashItems(l, onReady)
    }

    fun cancelFreeSpace() { freeCandidates = null }

    // ------------------------------------------------------------------ PDF souvenir

    fun makeSouvenir(periode: Int, onDone: (android.net.Uri) -> Unit) = run(Tr.t("PDF souvenir")) {
        if (demo) { demoAttente(Tr.t("PDF souvenir")); say(Tr.t("📕 PDF souvenir créé (démonstration)")); return@run }
        upd(Tr.t("PDF souvenir"), Tr.t("Choix des plus belles photos…"), 20)
        val uri = withContext(Dispatchers.IO) { Souvenir.make(ctx, periode, settings) }
        if (uri == null) say(Tr.t("Pas de photos pour cette période.")) else { say(Tr.t("📕 PDF enregistré dans Téléchargements / Clean Smart")); onDone(uri) }
    }

    // ------------------------------------------------------------------ compression des videos

    /** Compresse les videos choisies en 720p, puis propose les originaux a la corbeille. */
    fun compressVideos(items: List<MediaItem>, onOriginals: (List<MediaItem>, Long) -> Unit) = run(Tr.t("Compression des vidéos")) {
        if (demo) { demoAttente(Tr.t("Compression des vidéos")); val v = items.filter { it.video }; onOriginals(v, (v.sumOf { it.size } * 0.75).toLong()); return@run }
        val done = ArrayList<MediaItem>()
        var gain = 0L
        items.filter { it.video }.forEachIndexed { i, v ->
            val label = "Vidéo ${i + 1} / ${items.count { it.video }}"
            val newSize = runCatching {
                VideoCompressor.compress(ctx, v) { pct -> upd(Tr.t("Compression des vidéos"), label, pct) }
            }.getOrElse { e -> say("${v.name} : ${e.message ?: Tr.t("compression impossible")}"); -1L }
            if (newSize in 1 until (v.size * 0.9).toLong()) { done += v; gain += v.size - newSize }
        }
        if (done.isEmpty()) say(Tr.t("Aucune vidéo n'a pu être réduite davantage."))
        else onOriginals(done, gain)
    }

    fun trashOriginals(items: List<MediaItem>, onReady: (IntentSender) -> Unit) = trashItems(items, onReady)
}
