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
import com.jmdargere.cleansmart.data.Provider
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

data class Busy(val title: String, val step: String = "", val pct: Int = 0)
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
        viewModelScope.launch(Dispatchers.Main) { busy = Busy(title, step, pct) }
    }

    private fun run(title: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = Busy(title)
        viewModelScope.launch {
            try { block() } catch (e: Exception) { say(e.message ?: "Erreur inattendue") }
            finally { busy = null; refreshStorage() }
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
        say("🛡️ ${plural(keys.size, "photo protégée", "photos protégées")} (mises en favori)")
    }

    // ------------------------------------------------------------------ tri rapide (une photo a la fois)

    var swipeList by mutableStateOf<List<MediaItem>?>(null); private set
    var swipeIndex by mutableIntStateOf(0); private set
    var swipeTrash by mutableStateOf<List<MediaItem>>(emptyList()); private set

    fun loadSwipe() = run("Tri rapide") {
        swipeList = withContext(Dispatchers.IO) { scanner.recentPhotos(settings, 1000) }
        swipeIndex = 0
        swipeTrash = emptyList()
    }

    /** jeter = true : la photo part dans la pile "a jeter" ; false : on la garde. */
    fun swipe(jeter: Boolean) {
        val it = swipeList?.getOrNull(swipeIndex) ?: return
        if (jeter) swipeTrash = swipeTrash + it
        swipeIndex++
    }

    fun swipeUndo() {
        val l = swipeList ?: return
        if (swipeIndex == 0) return
        swipeIndex--
        val back = l[swipeIndex]
        swipeTrash = swipeTrash.filter { it.key != back.key }
    }

    fun prepareSwipeTrash(onReady: (IntentSender) -> Unit) = trashItems(swipeTrash, onReady)

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

    fun trashBigMails() = trashMail(MailCriteria.Big(settings.bigMailMb))
    fun trashOldMails() = trashMail(MailCriteria.Old(settings.oldMailYears))

    private fun trashMail(c: MailCriteria) = run("Nettoyage de la boîte mail") {
        busy = Busy("Nettoyage de la boîte mail", "Déplacement vers la corbeille…", 50)
        val n = withContext(Dispatchers.IO) { mail.trash(c) }
        say("✨ ${fmtNum(n)} mails mis à la corbeille")
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
        var pdf = 0
        withContext(Dispatchers.IO) {
            mail.forEachMail(uids, progress("Sauvegarde des mails")) { m -> pdf += backup.saveMail(tree!!, m) }
        }
        mailFilesSel = emptySet()
        say("✅ ${fmtNum(uids.size)} mail(s) sauvegardé(s) · ${fmtNum(pdf)} PDF dans le dossier PDF")
    }

    // ------------------------------------------------------------------ sauvegarde & options

    fun backupFolderLabel(): String = backup.folderLabel(settings.backupTree)
    fun backupReady(): Boolean = backup.isUsable(settings.backupTree)

    fun backupEverything() = run("Sauvegarde complète") {
        val tree = settings.backupTree
        if (!backup.isUsable(tree)) { say("Choisis d'abord un dossier de sauvegarde (Options)."); return@run }
        val n = withContext(Dispatchers.IO) {
            val all = scanner.allMedia()
            backup.saveMedia(tree!!, all, progress("Sauvegarde complète"))
        }
        say(if (n == 0) "Tout était déjà sauvegardé 👍" else "✅ ${fmtNum(n)} nouveau(x) fichier(s) sauvegardé(s)")
    }

    fun updateSettings(s: Settings) { settings = s; Settings.save(ctx, s) }
}
