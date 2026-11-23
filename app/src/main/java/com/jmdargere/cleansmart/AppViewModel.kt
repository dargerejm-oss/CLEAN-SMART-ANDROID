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
enum class Cat(val emoji: String, val title: String, val hint: String, val grouped: Boolean) {
    DUPLICATES("🧬", "Doublons", "Copies identiques — l'original est gardé", true),
    SIMILAR("🎞️", "Photos similaires", "Rafales — la plus nette est gardée", true),
    BLURRY("🌫️", "Photos floues ou ratées", "Floues, trop sombres ou trop claires — vérifie avant de cocher", false),
    SCREENS("📱", "Captures d'écran", "Souvent inutiles après coup", false),
    BIG_VIDEOS("🎬", "Grosses vidéos", "Plus de 100 Mo chacune", false),
    WHATSAPP("💬", "Médias WhatsApp", "Photos et vidéos reçues ou envoyées", false),
    BIGGEST("🐘", "Mes plus gros fichiers", "Les 30 photos et vidéos qui prennent le plus de place", false),
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

    /** Elements en attente de la confirmation Android pour la corbeille. */
    private var pendingTrash: List<MediaItem> = emptyList()

    init {
        Night.schedule(app, settings)
        refreshStorage()
        viewModelScope.launch { mailAccount = withContext(Dispatchers.IO) { mail.account() } }
    }

    fun refreshStorage() {
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
            try { block(); ok = true } catch (e: Exception) { say(e.message ?: "Erreur inattendue") }
            finally {
                busy = null
                refreshStorage()
                if (ok && System.currentTimeMillis() - start > 2500) done = title
            }
        }
    }

    // ------------------------------------------------------------------ analyse

    fun analyse(withContacts: Boolean) = run("Analyse du téléphone") {
        val p = progress("Analyse du téléphone")
        val r = withContext(Dispatchers.IO) { scanner.scan(settings, p) }
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

    private fun trashItems(items: List<MediaItem>, onReady: (IntentSender) -> Unit) = run("Préparation") {
        if (items.isEmpty()) return@run
        val tree = settings.backupTree
        if (settings.backupBeforeDelete && backup.isUsable(tree)) {
            val n = withContext(Dispatchers.IO) { backup.saveMedia(tree!!, items, progress("Sauvegarde avant suppression")) }
            if (n > 0) say("${fmtNum(n)} fichier(s) sauvegardé(s)")
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
        if (!ok) { say("Rien n'a été supprimé."); return }
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
        say("✨ ${fmtBytes(bytes)} libérés (récupérables 30 jours dans la corbeille de la Galerie)")
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
        runCatching { MediaStore.createTrashRequest(ctx.contentResolver, lastTrashed, false) }
            .onSuccess { onReady(it.intentSender) }
            .onFailure { say("Impossible d'annuler : les fichiers ont peut-être déjà été supprimés définitivement.") }
    }

    fun onUndoResult(ok: Boolean) {
        if (!ok) return
        say("↩️ ${plural(lastTrashed.size, "fichier")} remis dans la galerie")
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
        runCatching { MediaStore.createFavoriteRequest(ctx.contentResolver, items.map { it.uri }, true) }
            .onSuccess { onReady(it.intentSender) }
            .onFailure { say(it.message ?: "Impossible de protéger ces photos") }
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
        say("🛡️ ${plural(keys.size, "photo protégée", "photos protégées")} (mises en favori)")
    }

    // ------------------------------------------------------------------ tri rapide (une photo a la fois)

    var swipeList by mutableStateOf<List<MediaItem>?>(null); private set
    var swipeIndex by mutableIntStateOf(0); private set
    var swipeTrash by mutableStateOf<List<MediaItem>>(emptyList()); private set
    /** Photos gardees au tri rapide (glissees a droite) : on propose de les mettre en favori. */
    var swipeKeep by mutableStateOf<List<MediaItem>>(emptyList()); private set

    fun loadSwipe() = run("Tri rapide") {
        swipeList = withContext(Dispatchers.IO) { scanner.recentPhotos(settings, 1000) }
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
        if (l.isEmpty()) { say("Elles sont déjà toutes en favori 👍"); return }
        prepareProtect(l, onReady)
    }

    // ------------------------------------------------------------------ corbeille Android

    /** Contenu de la corbeille (nombre, taille) : ces fichiers prennent encore de la place. */
    var trash by mutableStateOf<List<MediaItem>>(emptyList()); private set

    fun refreshTrash() {
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
            .onFailure { say(it.message ?: "Impossible de vider la corbeille") }
    }

    fun onEmptyTrashResult(ok: Boolean) {
        val bytes = trash.sumOf { it.size }
        if (ok) say("🗑️ Corbeille vidée : ${fmtBytes(bytes)} récupérés pour de bon")
        refreshTrash()
        refreshStorage()
    }

    private fun addFreed(b: Long) { freed += b; prefs.edit().putLong("freed", freed).apply() }
    fun resetFreed() { freed = 0; prefs.edit().putLong("freed", 0).apply() }

    // ------------------------------------------------------------------ contacts

    fun deleteDuplicateContacts() = run("Contacts en double") {
        val r = contacts ?: return@run
        val ids = r.groups.flatMap { it.remove }
        if (ids.isEmpty()) return@run
        val tree = settings.backupTree
        if (settings.backupBeforeDelete && backup.isUsable(tree)) {
            withContext(Dispatchers.IO) { backup.saveContacts(tree!!, contactsCleaner.lookupKeys(ids)) }
        }
        val n = withContext(Dispatchers.IO) { contactsCleaner.delete(ids) }
        contacts = withContext(Dispatchers.IO) { contactsCleaner.scan() }
        say("✅ ${fmtNum(n)} doublon(s) supprimé(s)")
    }

    // ------------------------------------------------------------------ mails

    fun connectMail(p: Provider, host: String, email: String, password: String, onOk: () -> Unit) = run("Connexion à la messagerie") {
        withContext(Dispatchers.IO) { mail.saveAndTest(p, host.ifBlank { p.host }, email, password) }
        mailAccount = withContext(Dispatchers.IO) { mail.account() }
        mailResult = null
        say("✅ Boîte mail connectée")
        onOk()
    }

    fun forgetMail() {
        mail.forget()
        mailAccount = MailAccount()
        mailResult = null
    }

    fun analyseMail() = run("Analyse de la boîte mail") {
        mailResult = withContext(Dispatchers.IO) { mail.scan(3000, settings, progress("Analyse de la boîte mail")) }
    }

    fun trashNewsletters() = mailResult?.senders?.filter { it.newsletter }?.map { it.email }?.let {
        trashMail(MailCriteria.From(it))
    }

    fun trashDoublons() = mailResult?.doublons?.takeIf { it.isNotEmpty() }?.let { trashMail(MailCriteria.Uids(it)) }

    // corbeille de la messagerie : remettre des mails
    var mailTrash by mutableStateOf<List<com.jmdargere.cleansmart.data.TrashedMail>?>(null); private set
    var mailTrashSel by mutableStateOf(setOf<Long>()); private set

    fun loadMailTrash() = run("Corbeille de la messagerie") {
        mailTrash = withContext(Dispatchers.IO) { mail.listTrash() }
        mailTrashSel = emptySet()
    }

    fun toggleMailTrash(uid: Long) { mailTrashSel = if (uid in mailTrashSel) mailTrashSel - uid else mailTrashSel + uid }

    fun restoreMails() = run("Remettre des mails") {
        val n = withContext(Dispatchers.IO) { mail.restore(mailTrashSel.toList()) }
        say("↩️ ${plural(n, "mail remis", "mails remis")} dans la boîte de réception")
        mailTrash = withContext(Dispatchers.IO) { mail.listTrash() }
        mailTrashSel = emptySet()
    }

    fun trashBigMails() = trashMail(MailCriteria.Big(settings.bigMailMb))
    fun trashOldMails() = trashMail(MailCriteria.Old(settings.oldMailYears))

    private fun trashMail(c: MailCriteria) = run("Nettoyage de la boîte mail") {
        upd("Nettoyage de la boîte mail", "Déplacement vers la corbeille…", 50)
        val n = withContext(Dispatchers.IO) { mail.trash(c) }
        say("✨ ${fmtNum(n)} mails mis à la corbeille")
        mailResult = withContext(Dispatchers.IO) { mail.scan(3000, settings, progress("Mise à jour")) }
    }

    // ------------------------------------------------------------------ menage guide des mails (4 etapes)

    var mailGuideDone by mutableStateOf(setOf<Int>()); private set

    /** Etape 1 : newsletters et notifications (jamais les expediteurs importants). */
    val mailSafeSenders: List<com.jmdargere.cleansmart.data.Sender>
        get() = mailResult?.senders?.filter { (it.newsletter || it.notif) && !it.important } ?: emptyList()

    fun mailGuideClean() = run("Ménage sûr de la boîte mail") {
        val r = mailResult ?: return@run
        upd("Ménage sûr de la boîte mail", "Newsletters et notifications…", 30)
        var n = withContext(Dispatchers.IO) { mail.trash(MailCriteria.From(mailSafeSenders.map { it.email })) }
        if (r.doublons.isNotEmpty()) {
            upd("Ménage sûr de la boîte mail", "Mails en double…", 70)
            n += withContext(Dispatchers.IO) { mail.trash(MailCriteria.Uids(r.doublons)) }
        }
        mailGuideDone = mailGuideDone + 1
        say("✨ ${fmtNum(n)} mails mis à la corbeille")
        mailResult = withContext(Dispatchers.IO) { mail.scan(3000, settings, progress("Mise à jour")) }
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

    fun mailSwipeApply() = run("Tri des expéditeurs") {
        upd("Tri des expéditeurs", "Mise à la corbeille…", 40)
        val n = withContext(Dispatchers.IO) { mail.trash(MailCriteria.From(mailSwipeTrash.map { it.email })) }
        mailSwipeTrash = emptyList()
        mailGuideDone = mailGuideDone + 2
        say("✨ ${fmtNum(n)} mails mis à la corbeille")
        mailResult = withContext(Dispatchers.IO) { mail.scan(3000, settings, progress("Mise à jour")) }
    }

    /** Etape 3 : mettre a l'abri l'important (banque, administration, sante, factures) et tous les mails avec un PDF. */
    fun mailGuideSave() = run("Mettre à l'abri l'important") {
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say("Choisis d'abord un dossier de sauvegarde (Options)."); return@run }
        val r = mailResult ?: return@run
        val avecPdf = withContext(Dispatchers.IO) {
            mail.listWithAttachments(3000, progress("Recherche des mails avec PDF")).filter { it.hasPdf }.map { it.uid }
        }
        val uids = (r.aGarder + avecPdf).distinct()
        val saved = ArrayList<com.jmdargere.cleansmart.data.SavedMail>()
        withContext(Dispatchers.IO) {
            mail.forEachMail(uids, progress("Sauvegarde des mails importants")) { m -> saved += backup.saveMail(tree!!, m) }
            backup.updateMailIndex(tree!!, saved)
        }
        mailGuideDone = mailGuideDone + 3
        say("✅ ${fmtNum(saved.size)} mails importants à l'abri · ${fmtNum(saved.sumOf { it.pdf })} PDF réunis · archive index.html à jour")
    }

    /** Etape 4 : liberer la boite (vieux mails, jamais les importants). */
    fun mailGuideFree() = run("Libérer la boîte mail") {
        upd("Libérer la boîte mail", "Vieux mails vers la corbeille…", 50)
        val n = withContext(Dispatchers.IO) { mail.trash(MailCriteria.Old(settings.oldMailYears)) }
        mailGuideDone = mailGuideDone + 4
        say("✨ ${fmtNum(n)} vieux mails mis à la corbeille (les importants sont gardés)")
        mailResult = withContext(Dispatchers.IO) { mail.scan(3000, settings, progress("Mise à jour")) }
    }

    // ------------------------------------------------------------------ sauvegarde de mails choisis

    var mailFiles by mutableStateOf<List<MailWithFiles>?>(null); private set
    var mailFilesSel by mutableStateOf(setOf<Long>()); private set

    fun findMailAttachments() = run("Mails avec pièces jointes") {
        mailFiles = withContext(Dispatchers.IO) { mail.listWithAttachments(3000, progress("Mails avec pièces jointes")) }
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

    fun saveSelectedMails() = run("Sauvegarde des mails") {
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say("Choisis d'abord un dossier de sauvegarde."); return@run }
        val uids = mailFilesSel.toList()
        val saved = ArrayList<com.jmdargere.cleansmart.data.SavedMail>()
        withContext(Dispatchers.IO) {
            mail.forEachMail(uids, progress("Sauvegarde des mails")) { m -> saved += backup.saveMail(tree!!, m) }
            backup.updateMailIndex(tree!!, saved)
        }
        mailFilesSel = emptySet()
        say("✅ ${fmtNum(saved.size)} mail(s) sauvegardé(s) · ${fmtNum(saved.sumOf { it.pdf })} PDF · archive CleanSmart/Mails/index.html à jour")
    }

    // ------------------------------------------------------------------ sauvegarde & options

    fun backupFolderLabel(): String = backup.folderLabel(settings.backupTree)
    fun backupReady(): Boolean = backup.isUsable(settings.backupTree)

    /**
     * Sauvegarde INTELLIGENTE : seulement les bonnes photos. Les doublons en trop, les rafales en trop,
     * les photos ratees, les captures d'ecran et les photos jetees au tri rapide ne sont pas sauvegardees.
     * Les favoris vont en plus dans « Mes meilleures ».
     */
    fun backupEverything() = run("Sauvegarde de mes bonnes photos") {
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say("Choisis d'abord un dossier de sauvegarde (Options)."); return@run }
        if (media == null) {
            // on trie d'abord, pour ne pas sauvegarder les doublons et les photos ratees
            media = withContext(Dispatchers.IO) { scanner.scan(settings, progress("Tri avant la sauvegarde")) }
        }
        val skip = discardKeys()
        saveExcluded(skip)
        val (n, ignored) = withContext(Dispatchers.IO) {
            val all = scanner.allMedia()
            val keep = all.filter { it.key !in skip }
            backup.saveMedia(tree!!, keep, progress("Sauvegarde de mes bonnes photos")) to (all.size - keep.size)
        }
        guideDone = guideDone + 3
        prefs.edit().putLong("lastBackup", System.currentTimeMillis()).apply()
        say((if (n == 0) "Tout était déjà sauvegardé 👍" else "✅ ${fmtNum(n)} nouvelle(s) photo(s) sauvegardée(s)") +
                if (ignored > 0) " · ${fmtNum(ignored)} doublons, rafales ou ratées non sauvegardés" else "")
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
        settings = s
        Settings.save(ctx, s)
        Night.schedule(ctx, s)
    }

    // ------------------------------------------------------------------ liberer la place (photos deja sauvegardees)

    /** Photos/videos de plus de N mois, deja presentes dans la sauvegarde : proposees a la corbeille. */
    var freeCandidates by mutableStateOf<List<MediaItem>?>(null); private set

    fun findFreeSpace() = run("Libérer la place") {
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say("Choisis d'abord un dossier de sauvegarde et fais une sauvegarde."); return@run }
        val limit = System.currentTimeMillis() - settings.keepMonths * 30L * 24 * 3600 * 1000
        freeCandidates = withContext(Dispatchers.IO) {
            val old = scanner.allMedia().filter { it.date in 1 until limit && !(settings.protectFavorites && it.favorite) }
            backup.alreadySaved(tree!!, old, progress("Libérer la place"))
        }
        if (freeCandidates.isNullOrEmpty()) say("Rien à libérer : aucune photo ancienne n'est encore dans la sauvegarde. Lance d'abord « Sauvegarder toutes mes photos ».")
    }

    fun prepareFreeSpace(onReady: (IntentSender) -> Unit) {
        val l = freeCandidates ?: return
        freeCandidates = null
        pendingGuideStep = 4
        trashItems(l, onReady)
    }

    fun cancelFreeSpace() { freeCandidates = null }

    // ------------------------------------------------------------------ PDF souvenir

    fun makeSouvenir(periode: Int, onDone: (android.net.Uri) -> Unit) = run("PDF souvenir") {
        upd("PDF souvenir", "Choix des plus belles photos…", 20)
        val uri = withContext(Dispatchers.IO) { Souvenir.make(ctx, periode, settings) }
        if (uri == null) say("Pas de photos pour cette période.") else { say("📕 PDF enregistré dans Téléchargements / Clean Smart"); onDone(uri) }
    }

    // ------------------------------------------------------------------ compression des videos

    /** Compresse les videos choisies en 720p, puis propose les originaux a la corbeille. */
    fun compressVideos(items: List<MediaItem>, onOriginals: (List<MediaItem>, Long) -> Unit) = run("Compression des vidéos") {
        val done = ArrayList<MediaItem>()
        var gain = 0L
        items.filter { it.video }.forEachIndexed { i, v ->
            val label = "Vidéo ${i + 1} / ${items.count { it.video }}"
            val newSize = runCatching {
                VideoCompressor.compress(ctx, v) { pct -> upd("Compression des vidéos", label, pct) }
            }.getOrElse { e -> say("${v.name} : ${e.message ?: "compression impossible"}"); -1L }
            if (newSize in 1 until (v.size * 0.9).toLong()) { done += v; gain += v.size - newSize }
        }
        if (done.isEmpty()) say("Aucune vidéo n'a pu être réduite davantage.")
        else onOriginals(done, gain)
    }

    fun trashOriginals(items: List<MediaItem>, onReady: (IntentSender) -> Unit) = trashItems(items, onReady)
}
