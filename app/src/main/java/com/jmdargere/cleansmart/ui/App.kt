package com.jmdargere.cleansmart.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.BuildConfig
import com.jmdargere.cleansmart.Cat
import com.jmdargere.cleansmart.Tr
import com.jmdargere.cleansmart.data.Essai
import com.jmdargere.cleansmart.data.Provider
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural

/** Les ecrans de l'appli (navigation simple, sans bibliotheque). */
private enum class Ecran { ACCUEIL, GUIDE, GOOGLE, CATEGORIE, TRI, MAILS, MAIL_GUIDE, MAIL_TRI, MAILS_SAUVER, MAILS_CORBEILLE, OPTIONS, MANUEL, INUTILES, PHOTOS }

private fun mediaPerms(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private val contactPerms = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

private fun granted(ctx: Context, perms: Array<String>) =
    perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanSmartApp(vm: AppViewModel) {
    val ctx = LocalContext.current
    var ecran by rememberSaveable { mutableStateOf(Ecran.ACCUEIL) }
    var cat by rememberSaveable { mutableStateOf(Cat.DUPLICATES) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val snack = remember { SnackbarHostState() }
    val joursEssai = remember { Essai.joursRestants(ctx) }

    // Version d'essai terminee : plus d'acces au nettoyage
    if (Essai.actif && joursEssai == 0) { EssaiTermine(); return }

    // Accueil de bienvenue au tout premier lancement
    val prefsAccueil = remember { ctx.getSharedPreferences("accueil", Context.MODE_PRIVATE) }
    var bienvenueVue by remember { mutableStateOf(prefsAccueil.getBoolean("vue", false)) }
    if (!bienvenueVue) {
        Bienvenue { prefsAccueil.edit().putBoolean("vue", true).apply(); bienvenueVue = true }
        return
    }

    var depuisGuide by rememberSaveable { mutableStateOf(false) }
    fun retour() {
        ecran = when {
            ecran == Ecran.TRI && depuisGuide -> Ecran.GUIDE
            ecran == Ecran.TRI || ecran == Ecran.CATEGORIE -> Ecran.PHOTOS
            ecran == Ecran.MAIL_TRI -> Ecran.MAIL_GUIDE
            ecran == Ecran.MAILS_SAUVER || ecran == Ecran.MAILS_CORBEILLE || ecran == Ecran.MAIL_GUIDE -> Ecran.MAILS
            else -> Ecran.ACCUEIL
        }
        if (ecran != Ecran.TRI) depuisGuide = false
    }
    BackHandler(enabled = ecran != Ecran.ACCUEIL) { retour() }

    // bouton « Menage » du widget : on ouvre directement le Menage guide
    LaunchedEffect(vm.ouvrirGuide) { if (vm.ouvrirGuide) { ecran = Ecran.GUIDE; vm.ouvrirGuide = false } }

    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    // --- Autorisations : photos (obligatoire) + contacts (facultatif) ---
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(ctx, mediaPerms())) vm.analyse(granted(ctx, contactPerms))
        else vm.message = Tr.t("Sans l'accès aux photos, Clean Smart ne peut rien analyser.")
    }
    fun analyser() {
        if (granted(ctx, mediaPerms()) && granted(ctx, contactPerms)) vm.analyse(true)
        else permLauncher.launch(mediaPerms() + contactPerms)
    }

    // --- Corbeille Android (fenetre de confirmation du systeme) ---
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onTrashResult(it.resultCode == Activity.RESULT_OK)
    }
    // --- Vider la corbeille (suppression definitive, confirmee par Android) ---
    val emptyTrashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onEmptyTrashResult(it.resultCode == Activity.RESULT_OK)
    }
    fun viderCorbeille() {
        val n = vm.trash.size
        confirm = Confirm(
            Tr.t("Vider la corbeille ?"),
            Tr.t("%1 (%2) seront supprimés DÉFINITIVEMENT pour libérer la place tout de suite.\n\nIls ne pourront plus être récupérés.", plural(n, Tr.t("élément")), fmtBytes(vm.trash.sumOf { it.size })),
            Tr.t("Vider"),
        ) { vm.prepareEmptyTrash { s -> emptyTrashLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
    }
    LaunchedEffect(Unit) { if (granted(ctx, mediaPerms())) vm.refreshTrash() }

    // --- "J'ai fait une betise" : remettre la derniere mise a la corbeille ---
    val undoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onUndoResult(it.resultCode == Activity.RESULT_OK)
    }
    fun annuler() = vm.prepareUndo { s -> undoLauncher.launch(IntentSenderRequest.Builder(s).build()) }

    // --- Proteger des photos (favoris Android) ---
    val protectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onProtectResult(it.resultCode == Activity.RESULT_OK)
    }
    fun proteger(items: List<com.jmdargere.cleansmart.data.MediaItem>) =
        vm.prepareProtect(items) { s -> protectLauncher.launch(IntentSenderRequest.Builder(s).build()) }

    // --- Liberer la place : quand la liste est prete, on demande confirmation ---
    LaunchedEffect(vm.freeCandidates) {
        val l = vm.freeCandidates
        if (!l.isNullOrEmpty()) confirm = Confirm(
            Tr.t("Libérer %1 ?", fmtBytes(l.sumOf { it.size })),
            Tr.t("%1 de plus de %2 mois ", plural(l.size, Tr.t("photo ou vidéo"), Tr.t("photos ou vidéos")), vm.settings.keepMonths) +
                    Tr.t("sont déjà à l'abri dans ta sauvegarde (même nom, même taille).\n\nElles vont dans la corbeille de la Galerie (récupérables 30 jours). Les plus récentes restent sur le téléphone."),
            Tr.t("Libérer"),
        ) { vm.prepareFreeSpace { s -> trashLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
    }

    // --- PDF souvenir : envoi a un proche une fois cree ---
    fun souvenir(periode: Int) = vm.makeSouvenir(periode) { uri ->
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, Tr.t("Envoyer le PDF souvenir"))) }
    }

    // --- Compression des videos, puis proposition de jeter les originaux ---
    fun compresser(items: List<com.jmdargere.cleansmart.data.MediaItem>) {
        val videos = items.filter { it.video }
        if (videos.isEmpty()) return
        confirm = Confirm(
            Tr.t("Compresser %1 ?", plural(videos.size, Tr.t("vidéo"))),
            Tr.t("Chaque vidéo est refaite en 720p, souvent 3 à 5 fois plus légère, et enregistrée dans Films / Clean Smart. ") +
                    Tr.t("L'original n'est pas touché : je te proposerai ensuite de le mettre à la corbeille.\n\nCela peut prendre quelques minutes."),
            Tr.t("Compresser"), danger = false,
        ) {
            vm.compressVideos(videos) { originaux, gain ->
                confirm = Confirm(
                    Tr.t("Jeter les originaux ?"),
                    Tr.t("%1 avec succès. En mettant les originaux à la corbeille, tu gagnes %2.", plural(originaux.size, Tr.t("vidéo compressée"), Tr.t("vidéos compressées")), fmtBytes(gain)),
                    Tr.t("Corbeille"),
                ) { vm.trashOriginals(originaux) { s -> trashLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
            }
        }
    }

    // --- Envoyer des photos (WhatsApp, mail...) : 20 au maximum ---
    fun envoyer(items: List<com.jmdargere.cleansmart.data.MediaItem>) {
        val l = items.take(20)
        if (l.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (l.any { it.video }) "*/*" else "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(l.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, Tr.t("Envoyer %1", plural(l.size, "fichier")))) }
        if (items.size > 20) vm.message = Tr.t("Seuls les 20 premiers fichiers sont envoyés.")
    }

    fun nettoyer() {
        val n = vm.selected.size
        val save = vm.settings.backupBeforeDelete && vm.backupReady()
        confirm = Confirm(
            Tr.t("Nettoyer %1 ?", plural(n, Tr.t("élément"))),
            Tr.t("%1 vont aller dans la corbeille de la Galerie (récupérables 30 jours).", fmtBytes(vm.selectedBytes)) +
                    if (save) Tr.t("\n\nUne copie est d'abord faite dans ton dossier de sauvegarde.") else "",
            Tr.t("Nettoyer"),
        ) { vm.prepareTrash { sender -> trashLauncher.launch(IntentSenderRequest.Builder(sender).build()) } }
    }

    // --- Choix du dossier de sauvegarde ---
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.updateSettings(vm.settings.copy(backupUri = uri.toString()))
            vm.message = Tr.t("📁 Dossier de sauvegarde enregistré")
        }
    }

    val showCleanBar = (ecran == Ecran.PHOTOS || ecran == Ecran.CATEGORIE) && vm.selected.isNotEmpty()

    Scaffold(
        containerColor = Fond,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Bleu, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                ),
                title = {
                    Text(when (ecran) {
                        Ecran.ACCUEIL -> Tr.t("CLEAN SMART")
                        Ecran.GUIDE -> Tr.t("🧭 Ménage guidé")
                        Ecran.GOOGLE -> if (vm.gpSource == com.jmdargere.cleansmart.data.SourceExport.GOOGLE) Tr.t("📦 Quitter Google Photos") else Tr.t("🍏 Quitter iCloud Photos")
                        Ecran.CATEGORIE -> cat.emoji + " " + cat.title
                        Ecran.TRI -> Tr.t("👆 Tri rapide")
                        Ecran.MAILS -> Tr.t("✉️ Mails")
                        Ecran.MAIL_GUIDE -> Tr.t("🧭 Ménage des mails")
                        Ecran.MAIL_TRI -> Tr.t("👆 Tri des expéditeurs")
                        Ecran.MAILS_SAUVER -> Tr.t("💾 Sauver des mails")
                        Ecran.MAILS_CORBEILLE -> Tr.t("↩️ Remettre des mails")
                        Ecran.OPTIONS -> Tr.t("⚙️ Options")
                        Ecran.MANUEL -> Tr.t("📖 Mode d'emploi")
                        Ecran.INUTILES -> Tr.t("🧹 Fichiers inutiles")
                        Ecran.PHOTOS -> Tr.t("📸 Photos")
                    }, fontWeight = FontWeight.ExtraBold)
                },
                navigationIcon = {
                    if (ecran != Ecran.ACCUEIL) TextButton(onClick = { retour() }) { Text(Tr.t("‹ Retour"), color = Color.White, fontWeight = FontWeight.Bold) }
                },
                actions = {
                    if (ecran == Ecran.ACCUEIL) TextButton(onClick = { ecran = Ecran.OPTIONS }) { Text("⚙️", fontSize = 22.sp) }
                },
            )
        },
        bottomBar = {
            if (showCleanBar) {
                Box(Modifier.fillMaxWidth().background(Fond).navigationBarsPadding().padding(16.dp, 8.dp)) {
                    BigButton(Tr.t("🧹 Nettoyer · %1 · %2", fmtNum(vm.selected.size), fmtBytes(vm.selectedBytes)), color = Rouge) { nettoyer() }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (ecran) {
                Ecran.ACCUEIL -> Accueil(vm, ::analyser, joursEssai, ::viderCorbeille, ::annuler, ::souvenir,
                    openCat = { cat = it; ecran = Ecran.CATEGORIE },
                    openGuide = { ecran = Ecran.GUIDE },
                    openGoogle = { vm.gpUtiliser(com.jmdargere.cleansmart.data.SourceExport.GOOGLE); ecran = Ecran.GOOGLE },
                    openIcloud = { vm.gpUtiliser(com.jmdargere.cleansmart.data.SourceExport.ICLOUD); ecran = Ecran.GOOGLE },
                    openTri = { if (granted(ctx, mediaPerms())) ecran = Ecran.TRI else permLauncher.launch(mediaPerms() + contactPerms) },
                    openMail = { ecran = Ecran.MAILS },
                    openManual = { ecran = Ecran.MANUEL },
                    openInutiles = { ecran = Ecran.INUTILES },
                    openPhotos = { ecran = Ecran.PHOTOS },
                    chooseFolder = { folderLauncher.launch(null) },
                    ask = { confirm = it })
                Ecran.CATEGORIE -> Categorie(vm, cat, envoyer = ::envoyer, proteger = ::proteger, compresser = ::compresser)
                Ecran.GOOGLE -> GooglePhotos(vm, ask = { confirm = it })
                Ecran.GUIDE -> MenageGuide(vm,
                    analyser = ::analyser,
                    nettoyerSur = {
                        val l = vm.safeItems
                        confirm = Confirm(
                            Tr.t("Faire le ménage sûr ?"),
                            Tr.t("%1 (%2) : doublons en trop, rafales en trop, photos noires ou blanches, captures de plus d'un mois.\n\nElles vont dans la corbeille de la Galerie (30 jours pour les récupérer).", plural(l.size, "photo"), fmtBytes(l.sumOf { it.size })),
                            Tr.t("Nettoyer"),
                        ) { vm.guideClean { s -> trashLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
                    },
                    verifier = { vm.guideReview(); depuisGuide = true; ecran = Ecran.TRI },
                    chooseFolder = { folderLauncher.launch(null) },
                    openMails = { ecran = if (vm.mailAccount.ready) Ecran.MAIL_GUIDE else Ecran.MAILS })
                Ecran.TRI -> TriRapide(vm, onKeep = {
                    vm.prepareSwipeKeep { s -> protectLauncher.launch(IntentSenderRequest.Builder(s).build()) }
                }) {
                    confirm = Confirm(
                        Tr.t("Jeter %1 ?", plural(vm.swipeTrash.size, "photo")),
                        Tr.t("%1 vont dans la corbeille de la Galerie (récupérables 30 jours).", fmtBytes(vm.swipeTrash.sumOf { it.size })),
                        Tr.t("Corbeille"),
                    ) { vm.prepareSwipeTrash { s -> trashLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
                }
                Ecran.MAILS -> Mails(vm, ask = { confirm = it }, openSave = { ecran = Ecran.MAILS_SAUVER },
                    openTrash = { ecran = Ecran.MAILS_CORBEILLE; vm.loadMailTrash() },
                    openGuide = { ecran = Ecran.MAIL_GUIDE })
                Ecran.MAIL_GUIDE -> MailGuide(vm, ask = { confirm = it }, openTri = { ecran = Ecran.MAIL_TRI },
                    chooseFolder = { folderLauncher.launch(null) })
                Ecran.MAIL_TRI -> MailTri(vm, ask = { confirm = it })
                Ecran.MAILS_CORBEILLE -> MailsCorbeille(vm)
                Ecran.MAILS_SAUVER -> MailsSauver(vm, chooseFolder = { folderLauncher.launch(null) })
                Ecran.OPTIONS -> Options(vm, chooseFolder = { folderLauncher.launch(null) }, openManual = { ecran = Ecran.MANUEL })
                Ecran.MANUEL -> Manual()
                Ecran.INUTILES -> Inutiles(vm, ask = { confirm = it })
                Ecran.PHOTOS -> Photos(vm, ::analyser, ::souvenir,
                    openCat = { cat = it; ecran = Ecran.CATEGORIE },
                    openTri = { if (granted(ctx, mediaPerms())) ecran = Ecran.TRI else permLauncher.launch(mediaPerms() + contactPerms) },
                    ask = { confirm = it })
            }
        }
    }

    MailRevueDialog(vm)
    vm.busy?.let { BusyDialog(it) }
    vm.done?.let { DoneDialog(it) { vm.done = null } }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

// =====================================================================================
// ACCUEIL : stockage, un gros bouton, et le resultat en quelques cartes
// =====================================================================================

@Composable
private fun Accueil(
    vm: AppViewModel,
    analyser: () -> Unit,
    joursEssai: Int,
    viderCorbeille: () -> Unit,
    annuler: () -> Unit,
    souvenir: (Int) -> Unit,
    openCat: (Cat) -> Unit,
    openGuide: () -> Unit,
    openGoogle: () -> Unit,
    openIcloud: () -> Unit,
    openTri: () -> Unit,
    openMail: () -> Unit,
    openManual: () -> Unit,
    openInutiles: () -> Unit,
    openPhotos: () -> Unit,
    chooseFolder: () -> Unit,
    ask: (Confirm) -> Unit,
) {
    val m = vm.media
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp)) {
        if (vm.demo) item {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Pourpre, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(Tr.t("🎬 MODE DÉMONSTRATION : rien n'est touché sur le téléphone"), color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.changerDemo(false) }) { Text(Tr.t("Quitter"), color = Color.White, fontWeight = FontWeight.Black) }
            }
        }
        vm.nouvelleVersion?.let { v ->
            item {
                val c = LocalContext.current
                CatTile("🆕", Tr.t("Nouvelle version %1 disponible", v), Tr.t("Touche ici pour la télécharger, puis ouvre le fichier pour l'installer"),
                    "", "", Orange, Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.jmdargere.cleansmart.data.MiseAJour.lienApk))) }
                }
            }
        }
        if (Essai.actif) item {
            // bandeau de l'essai : le toucher ouvre les coordonnees pour obtenir la version complete
            var contact by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .background(if (joursEssai <= 5) Rouge else Violet, RoundedCornerShape(12.dp))
                .clickable { contact = true }.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Tr.t("🎁 Version d'essai · %1", plural(joursEssai, Tr.t("jour restant"), Tr.t("jours restants"))),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
                Text(Tr.t("Touche ici pour la version complète ou une question"), color = Color(0xE6FFFFFF), fontSize = 12.sp,
                    textAlign = TextAlign.Center)
            }
            if (contact) ContactDialog(Tr.t("Version complète"),
                Tr.t("Pour obtenir la version complète de Clean Smart, ou pour toute question, écris-moi :")) { contact = false }
        }
        item {
            BoxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StorageRing(vm.storage.ratio)
                    RowGap(16)
                    Column(Modifier.weight(1f)) {
                        Text(Tr.t("Stockage du téléphone"), color = Gris, fontSize = 13.sp)
                        Text("${fmtBytes(vm.storage.used)} / ${fmtBytes(vm.storage.total)}", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                        Text("${fmtBytes(vm.storage.free)} disponibles", color = Gris, fontSize = 13.sp)
                        if (vm.freed > 0) Text(Tr.t("🎉 %1 libérés au total", fmtBytes(vm.freed)), color = Vert, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        if (vm.mailJetes > 0) Text(Tr.t("✉️ %1 jetés · %2 libérés sur ta boîte mail", plural(vm.mailJetes, "mail"), fmtBytes(vm.mailLibere)),
                            color = Violet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (vm.lastTrashed.isNotEmpty()) {
                OutlinedButton(onClick = annuler, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp)) {
                    Text(Tr.t("↩️ J'ai fait une bêtise : remettre les %1", plural(vm.lastTrashed.size, Tr.t("derniers fichiers"), Tr.t("derniers fichiers"))),
                        color = Rouge, fontWeight = FontWeight.Bold)
                }
            }
            if (vm.trash.isNotEmpty()) {
                BoxCard {
                    InfoRow("🗑️", Tr.t("Corbeille : %1", plural(vm.trash.size, Tr.t("élément"))),
                        Tr.t("Ils prennent encore de la place pendant 30 jours"), fmtBytes(vm.trash.sumOf { it.size }))
                    Gap(4)
                    BigButton(Tr.t("Vider la corbeille maintenant"), color = Carte2, onClick = viderCorbeille)
                }
            }
            Gap()
            // le Menage complet : photos, telephone et mails, dans le bon ordre (le plus simple)
            CatTile("✨", Tr.t("Ménage complet"), Tr.t("Photos, téléphone et mails, étape par étape"),
                Tr.t("le plus simple"), Tr.t("CONSEILLÉ"), Vert, Modifier.fillMaxWidth(), onClick = openGuide)
            Gap(10)
            // ou une seule partie : 3 portes
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Porte("📸", Tr.t("Photos"), if (m == null) Tr.t("Doublons, rafales, floues") else Tr.t("%1 analysées", fmtNum(m.photoCount)),
                    Violet, Modifier.weight(1f), openPhotos)
                Porte("🧹", Tr.t("Téléphone"), Tr.t("Fichiers inutiles"), Turquoise, Modifier.weight(1f), openInutiles)
                Porte("✉️", Tr.t("Mails"), if (vm.mailAccount.ready) Tr.t("Trier, protéger") else Tr.t("Connecter"),
                    Pourpre, Modifier.weight(1f), openMail)
            }
            Gap(6)
            BoxCard {
                InfoRow("📦", Tr.t("Quitter Google Photos"), Tr.t("Toutes tes photos sur une clé ou un disque, par année, sans doublon"), onClick = openGoogle)
                InfoRow("🍏", Tr.t("Quitter iCloud Photos"), Tr.t("Tes photos d'iPhone sur une clé ou un disque, par année, sans doublon"), onClick = openIcloud)
            }
        }

        // Sauvegarde
        item {
            SectionTitle(Tr.t("SAUVEGARDE"))
            BoxCard {
                val ok = vm.backupReady()
                InfoRow("💾", if (ok) vm.backupFolderLabel() else Tr.t("Aucun dossier de sauvegarde"),
                    if (ok) Tr.t("Carte SD, clé USB, Drive… choisis où tu veux") else Tr.t("Choisis une carte SD, une clé USB ou Google Drive"))
                Gap()
                if (ok) BigButton(Tr.t("💾 Sauvegarder toutes mes photos")) { vm.backupEverything() }
                else BigButton(Tr.t("📁 Choisir un dossier"), onClick = chooseFolder)
            }
        }

        item {
            Gap()
            OutlinedButton(onClick = openManual, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(Tr.t("📖 Mode d'emploi"), color = Texte)
            }
        }
    }
}

/** Une des 3 portes de l'accueil : Photos, Telephone, Mails. */
@Composable
private fun Porte(emoji: String, titre: String, sous: String, couleur: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(128.dp).background(couleur, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(emoji, fontSize = 34.sp)
        Text(titre, color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, textAlign = TextAlign.Center)
        Text(sous, color = Color(0xE6FFFFFF), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp, maxLines = 2)
    }
}

// =====================================================================================
// PHOTOS : analyser, les categories a nettoyer, le tri rapide, les outils photos
// =====================================================================================

@Composable
private fun Photos(
    vm: AppViewModel,
    analyser: () -> Unit,
    souvenir: (Int) -> Unit,
    openCat: (Cat) -> Unit,
    openTri: () -> Unit,
    ask: (Confirm) -> Unit,
) {
    val m = vm.media
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)) {
        item {
            BigButton(if (m == null) Tr.t("🔍 Analyser mon téléphone") else Tr.t("🔄 Analyser à nouveau"), color = Bleu, onClick = analyser)
            Gap()
        }
        if (m == null) {
            item {
                BoxCard {
                    Text(Tr.t("Clean Smart trouve en un clic :"), fontWeight = FontWeight.Bold)
                    Gap(6)
                    Text(Tr.t("🧬 les doublons   🎞️ les photos ratées en rafale\n📱 les captures d'écran   🎬 les grosses vidéos\n💬 les médias WhatsApp   👥 les contacts en double\n✉️ les newsletters et gros mails"),
                        color = Gris, fontSize = 14.sp, lineHeight = 22.sp)
                    Gap(6)
                    Text(Tr.t("Rien n'est supprimé sans ton accord : tout passe par la corbeille."), color = Gris, fontSize = 13.sp)
                }
            }
        } else {
            item {
                // chaque fichier compte une seule fois (une photo peut etre dans 2 categories)
                val total = Cat.entries.filter { it != Cat.BIGGEST }
                    .flatMap { vm.removable(it) }.distinctBy { it.key }.sumOf { it.size }
                BoxCard {
                    Text(Tr.t("Espace à récupérer"), color = Gris, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(fmtBytes(total), fontSize = 36.sp, fontWeight = FontWeight.Black, color = Violet2,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(Tr.t("%1 photos · %2 vidéos analysées", fmtNum(m.photoCount), fmtNum(m.videoCount)), color = Gris, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                SectionTitle(Tr.t("PHOTOS ET VIDÉOS"))
                val visibles = Cat.entries.filter { vm.removable(it).isNotEmpty() }
                if (visibles.isEmpty()) BoxCard { Text(Tr.t("🎉 Rien à nettoyer, tout est propre !"), color = Gris) }
                // grandes tuiles de couleur, deux par ligne
                visibles.chunked(2).forEach { ligne ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ligne.forEach { c ->
                            val r = vm.removable(c)
                            val sel = r.count { it.key in vm.selected }
                            CatTile(c.emoji, c.title, plural(r.size, Tr.t("élément")), fmtBytes(r.sumOf { it.size }),
                                if (sel > 0) Tr.t("✓ %1 choisi", sel) + (if (sel > 1) "s" else "") else "",
                                couleurDe(c), Modifier.weight(1f)) { openCat(c) }
                        }
                        if (ligne.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            BoxCard(onClick = openTri) {
                InfoRow("👆", Tr.t("Tri rapide"), Tr.t("Une photo à la fois : glisse à gauche pour jeter, à droite pour garder"), onClick = openTri)
            }
        }

        // Plus d'outils : replie par defaut pour garder l'accueil simple
        item {
            var ouvert by rememberSaveable { mutableStateOf(false) }
            var choixPdf by remember { mutableStateOf(false) }
            BoxCard {
                InfoRow("🧰", Tr.t("Plus d'outils"), if (ouvert) "" else Tr.t("Libérer la place, PDF souvenir…"), onClick = { ouvert = !ouvert })
                if (ouvert) {
                    InfoRow("🧹", Tr.t("Libérer la place"),
                        Tr.t("Met à la corbeille les photos de plus de %1 mois déjà sauvegardées", vm.settings.keepMonths),
                        onClick = { vm.findFreeSpace() })
                    InfoRow("📕", Tr.t("PDF souvenir"), Tr.t("Les plus belles photos du mois ou de l'année, à envoyer à un proche"),
                        onClick = { choixPdf = true })
                    InfoRow("🗜️", Tr.t("Réduire mes vidéos"), Tr.t("Ouvre « Grosses vidéos », coche-les puis « Compresser »"),
                        onClick = { if (vm.media != null) openCat(Cat.BIG_VIDEOS) else analyser() })
                }
            }
            if (choixPdf) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { choixPdf = false },
                    containerColor = Carte,
                    title = { Text(Tr.t("PDF souvenir"), fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            listOf(Tr.t("Ce mois-ci"), Tr.t("Le mois dernier"), Tr.t("Cette année")).forEachIndexed { i, l ->
                                BigButton(l, color = Carte2) { choixPdf = false; souvenir(i) }
                                Gap(6)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { choixPdf = false }) { Text(Tr.t("Annuler"), color = Gris) } },
                )
            }
        }

        // Contacts en double
        vm.contacts?.let { c ->
            item {
                SectionTitle(Tr.t("CONTACTS"))
                BoxCard {
                    if (c.duplicates == 0) InfoRow("👥", Tr.t("Aucun contact en double"), Tr.t("sur %1 contacts", fmtNum(c.total)))
                    else {
                        InfoRow("👥", plural(c.duplicates, Tr.t("contact en double"), Tr.t("contacts en double")),
                            c.groups.take(3).joinToString(", ") { it.name.ifEmpty { it.number } } + if (c.groups.size > 3) "…" else "")
                        Gap()
                        BigButton(Tr.t("Supprimer les doublons")) {
                            ask(Confirm(Tr.t("Supprimer %1 ?", plural(c.duplicates, "doublon")),
                                Tr.t("Le contact d'origine est gardé.") +
                                        (if (vm.settings.backupBeforeDelete && vm.backupReady()) Tr.t("\n\nLes copies sont d'abord exportées en fichier .vcf dans ton dossier de sauvegarde.") else "") +
                                        Tr.t("\n\nAttention : la suppression est synchronisée avec ton compte (Google…)."),
                                Tr.t("Supprimer")) { vm.deleteDuplicateContacts() })
                        }
                    }
                }
            }
        }

    }
}

// =====================================================================================
// MENAGE GUIDE : 4 etapes dans le bon ordre, le bouton vert est toujours la prochaine
// =====================================================================================

@Composable
private fun MenageGuide(
    vm: AppViewModel,
    analyser: () -> Unit,
    nettoyerSur: () -> Unit,
    verifier: () -> Unit,
    chooseFolder: () -> Unit,
    openMails: () -> Unit,
) {
    val m = vm.media
    val safe = vm.safeItems
    val review = vm.reviewItems
    val d = vm.guideDone
    // la prochaine etape a faire (celle qui a le bouton vert)
    val prochaine = when {
        m == null -> 0
        1 !in d && safe.isNotEmpty() -> 1
        2 !in d && review.isNotEmpty() -> 2
        3 !in d -> 3
        4 !in d -> 4
        !mailFait(vm) -> 5
        else -> 6
    }

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)) {
        item {
            Text(Tr.t("Trier d'abord, garder le meilleur, sauvegarder seulement les bonnes photos, libérer la place, puis faire le ménage des mails."),
                color = Gris, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 8.dp))
            BilanMenage(vm)
            if (m == null) {
                EtapeCarte(0, "🔍", Tr.t("Avant tout : analyser"), Tr.t("Clean Smart regarde toutes tes photos (rien n'est supprimé)."),
                    Bleu, prochaine == 0, false, Tr.t("Analyser mon téléphone"), analyser)
            }
            EtapeCarte(1, "🧹", Tr.t("Le ménage sûr"),
                if (m == null) Tr.t("Doublons, rafales en trop, photos noires ou blanches, vieilles captures.")
                else Tr.t("%1 · %2 : doublons, rafales en trop, photos noires ou blanches, captures de plus d'un mois. Les meilleures sont gardées.", plural(safe.size, "photo"), fmtBytes(safe.sumOf { it.size })),
                Rouge, prochaine == 1, 1 in d || (m != null && safe.isEmpty()), Tr.t("Tout nettoyer"), nettoyerSur)
            EtapeCarte(2, "👆", Tr.t("Je vérifie le douteux"),
                if (m == null) Tr.t("Les photos un peu floues, WhatsApp, captures récentes : c'est toi qui décides.")
                else Tr.t("%1 à regarder une par une. Glisse à gauche pour jeter, à droite pour garder (elle devient favori ⭐).", plural(review.size, "photo")),
                Pourpre, prochaine == 2, 2 in d || (m != null && review.isEmpty()), Tr.t("Trier en glissant"), verifier)
            EtapeCarte(3, "💾", Tr.t("Je mets à l'abri les bonnes"),
                if (!vm.backupReady()) Tr.t("Choisis d'abord un dossier : carte SD, clé USB ou Google Drive.")
                else Tr.t("Seulement les bonnes photos : jamais les doublons, rafales en trop ou ratées. Les favoris vont aussi dans « Mes meilleures »."),
                Bleu, prochaine == 3, 3 in d,
                if (vm.backupReady()) Tr.t("Sauvegarder mes bonnes photos") else Tr.t("Choisir un dossier"),
                if (vm.backupReady()) ({ vm.backupEverything() }) else chooseFolder)
            EtapeCarte(4, "📤", Tr.t("Je libère la place"),
                Tr.t("Les photos de plus de %1 mois déjà sauvegardées quittent le téléphone (corbeille, 30 jours).", vm.settings.keepMonths),
                Vert, prochaine == 4, 4 in d, Tr.t("Libérer la place")) { vm.findFreeSpace() }
            // 5. les mails : ils sont sur le compte en ligne, pas sur le telephone
            val a = vm.mailAccount
            val r = vm.mailResult
            EtapeCarte(5, "✉️", Tr.t("Et tes mails"),
                when {
                    !a.ready -> Tr.t("Connecte ta boîte mail (Gmail, Outlook, Yahoo, Orange, Free…) : newsletters, gros mails et vieux mails à trier, les importants sont toujours gardés.")
                    r == null -> Tr.t("Analyse ta boîte « %1 » : newsletters, gros mails, vieux mails. Les importants sont toujours gardés.", a.email)
                    else -> (if (r.quotaMax > 0) Tr.t("Boîte remplie à %1 % (%2 / %3). ", (100 * r.quotaUsed / r.quotaMax).toInt(), fmtBytes(r.quotaUsed), fmtBytes(r.quotaMax)) else "") +
                            Tr.t("%1 newsletters, %2 gros mails (%3).", fmtNum(r.newsletters), fmtNum(r.bigCount), fmtBytes(r.bigBytes))
                } + (if (vm.mailGmail) Tr.t("\n💡 Chez Google, les mails et Google Photos partagent les mêmes 15 Go : faire le ménage des mails libère aussi de la place pour tes photos.") else ""),
                Violet, prochaine == 5, mailFait(vm),
                if (!a.ready) Tr.t("Connecter ma boîte mail") else Tr.t("Ménage guidé des mails"), openMails)
            if (prochaine == 6) {
                Gap()
                CatTile("🎉", Tr.t("Ménage terminé !"), Tr.t("Ton téléphone et ta boîte mail sont propres, tes photos sont à l'abri"), "", "", Vert,
                    Modifier.fillMaxWidth()) {}
            }
        }
    }
}

/** L'etape mails est faite quand le menage guide des mails a ete fait (au moins le menage sur ou la liberation). */
private fun mailFait(vm: AppViewModel) = 1 in vm.mailGuideDone || 4 in vm.mailGuideDone

/** Le bilan du menage : ce qui est libere sur le telephone, et sur la boite mail. */
@Composable
private fun BilanMenage(vm: AppViewModel) {
    if (vm.freed <= 0 && vm.mailJetes <= 0) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).background(Vert.copy(alpha = 0.10f), RoundedCornerShape(16.dp)).padding(12.dp)) {
            Text(Tr.t("📱 Téléphone"), color = Gris, fontSize = 13.sp)
            Text(fmtBytes(vm.freed), color = Vert, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(Tr.t("libérés"), color = Gris, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).background(Violet.copy(alpha = 0.10f), RoundedCornerShape(16.dp)).padding(12.dp)) {
            Text(Tr.t("✉️ Boîte mail"), color = Gris, fontSize = 13.sp)
            Text(fmtBytes(vm.mailLibere), color = Violet, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(Tr.t("%1 jetés", plural(vm.mailJetes, "mail")), color = Gris, fontSize = 12.sp)
        }
    }
    if (vm.mailJetes > 0) Text(Tr.t("La place des mails se libère quand la corbeille de ta messagerie se vide (en général sous 30 jours)."),
        color = Gris, fontSize = 12.sp, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 6.dp))
}

/** Une etape du menage guide : numero, icone, explication, et le bouton (vert si c'est la prochaine). */
@Composable
internal fun EtapeCarte(
    n: Int, emoji: String, titre: String, texte: String, couleur: Color,
    prochaine: Boolean, faite: Boolean, bouton: String, onClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Carte),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(if (prochaine) 6.dp else 1.dp),
        border = if (prochaine) androidx.compose.foundation.BorderStroke(3.dp, Vert) else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(if (faite) Vert else couleur, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(if (faite) "✓" else if (n == 0) emoji else "${n}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                RowGap(12)
                Column(Modifier.weight(1f)) {
                    Text("${emoji}  ${titre}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (faite) Vert else Texte)
                    if (faite) Text(Tr.t("Fait ✔"), color = Vert, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Gap(6)
            Text(texte, color = Gris, fontSize = 14.sp, lineHeight = 20.sp)
            if (!faite) {
                Gap(10)
                BigButton(bouton, color = if (prochaine) Vert else Gris, onClick = onClick)
            }
        }
    }
}

// =====================================================================================
// ESSAI TERMINE
// =====================================================================================

@Composable
private fun EssaiTermine() {
    Column(
        Modifier.fillMaxSize().background(Fond).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✨", fontSize = 64.sp)
        Gap(12)
        Text(Tr.t("Clean Smart"), fontSize = 28.sp, fontWeight = FontWeight.Black)
        Gap(16)
        Text(Tr.t("Ta période d'essai de 30 jours est terminée."), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Gap(10)
        Text(Tr.t("Merci d'avoir essayé Clean Smart ! Installe la version complète pour continuer à nettoyer ton téléphone. ") +
                Tr.t("Tes réglages et ta boîte mail seront conservés."),
            color = Gris, textAlign = TextAlign.Center, lineHeight = 22.sp)
        Gap(10)
        Text(Tr.t("Les fichiers déjà mis à la corbeille restent récupérables dans la Galerie."), color = Gris, fontSize = 13.sp, textAlign = TextAlign.Center)
        Gap(20)
        Text(Tr.t("Pour obtenir la version complète, écris-moi :"), fontWeight = FontWeight.Bold, color = Texte, textAlign = TextAlign.Center)
        Gap(10)
        BoutonsContact()
    }
}

// =====================================================================================
// CATEGORIE : la grille de miniatures a cocher
// =====================================================================================

@Composable
private fun Categorie(
    vm: AppViewModel,
    cat: Cat,
    envoyer: (List<com.jmdargere.cleansmart.data.MediaItem>) -> Unit,
    proteger: (List<com.jmdargere.cleansmart.data.MediaItem>) -> Unit,
    compresser: (List<com.jmdargere.cleansmart.data.MediaItem>) -> Unit,
) {
    val groups = vm.groups(cat)
    val choisis = vm.selectedIn(cat)
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(cat.hint, color = Gris, fontSize = 14.sp)
                Text(Tr.t("Touche une image pour la cocher · appui long pour la voir en grand"), color = Gris, fontSize = 12.sp)
                Gap()
                Row {
                    OutlinedButton(onClick = { vm.selectAll(cat, true) }) { Text(Tr.t("Tout cocher"), color = Texte) }
                    RowGap()
                    OutlinedButton(onClick = { vm.selectAll(cat, false) }) { Text(Tr.t("Tout décocher"), color = Texte) }
                }
                if (choisis.isNotEmpty()) {
                    Gap(4)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        OutlinedButton(onClick = { envoyer(choisis) }) { Text(Tr.t("📤 Envoyer (%1)", choisis.size), color = Texte) }
                        RowGap()
                        OutlinedButton(onClick = { proteger(choisis) }) { Text(Tr.t("🛡️ Protéger (%1)", choisis.size), color = Texte) }
                        val nbVideos = choisis.count { it.video }
                        if (nbVideos > 0) {
                            RowGap()
                            OutlinedButton(onClick = { compresser(choisis) }) { Text(Tr.t("🗜️ Compresser (%1)", nbVideos), color = Texte) }
                        }
                    }
                }
                Gap(4)
            }
        }
        if (groups.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(Tr.t("🎉 Plus rien ici."), color = Gris, modifier = Modifier.padding(24.dp)) }
        }
        if (cat.grouped) {
            groups.forEachIndexed { gi, g ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "g${gi}") {
                    Text(Tr.t("Groupe %1 · %2 photos", gi + 1, g.items.size), color = Gris, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                items(g.items, key = { "g${gi}-" + it.key }) { m ->
                    Thumb(m, m.key in vm.selected, m.key == g.keep) { vm.toggle(m.key) }
                }
            }
        } else {
            items(groups.map { it.items[0] }, key = { it.key }) { m ->
                Thumb(m, m.key in vm.selected, false) { vm.toggle(m.key) }
            }
        }
    }
}

// =====================================================================================
// MAILS : connexion puis 3 gros boutons
// =====================================================================================

@Composable
private fun Mails(vm: AppViewModel, ask: (Confirm) -> Unit, openSave: () -> Unit, openTrash: () -> Unit, openGuide: () -> Unit) {
    val a = vm.mailAccount
    var edit by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current

    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp)) {
        if (!a.ready || edit) {
            item {
                var prov by rememberSaveable { mutableStateOf(runCatching { Provider.valueOf(a.provider) }.getOrDefault(Provider.GMAIL)) }
                var host by rememberSaveable { mutableStateOf(a.host) }
                var email by rememberSaveable { mutableStateOf(a.email) }
                var pass by remember { mutableStateOf("") }

                BoxCard {
                    Text(Tr.t("Ta messagerie"), fontWeight = FontWeight.Bold)
                    Gap()
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Provider.entries.forEach { p ->
                            FilterChip(selected = prov == p, onClick = { prov = p; if (p != Provider.AUTRE) host = p.host },
                                label = { Text(p.label) }, modifier = Modifier.padding(end = 6.dp),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet, selectedLabelColor = Color.White))
                        }
                    }
                    if (prov == Provider.AUTRE) {
                        OutlinedTextField(host, { host = it }, label = { Text(Tr.t("Serveur IMAP")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(email, { email = it }, label = { Text(Tr.t("Adresse mail")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it },
                        label = { Text(if (a.hasPassword) Tr.t("Mot de passe (vide = garder l'actuel)") else Tr.t("Mot de passe d'application")) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Gap(12)
                    BigButton(Tr.t("Connecter")) {
                        vm.connectMail(prov, if (prov == Provider.AUTRE) host else prov.host, email, pass) { edit = false; pass = "" }
                    }
                    if (a.ready) TextButton(onClick = { edit = false }) { Text(Tr.t("Annuler"), color = Gris) }
                }
                BoxCard {
                    Text(Tr.t("💡 Aide"), fontWeight = FontWeight.Bold)
                    Gap(4)
                    Text(prov.help, color = Gris, fontSize = 14.sp, lineHeight = 20.sp)
                    Provider.APP_PASSWORD_URL[prov]?.let { url ->
                        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
                            Text(Tr.t("Ouvrir la page du mot de passe d'application"), color = Violet2)
                        }
                    }
                    Text(Tr.t("🔒 Ton mot de passe reste chiffré sur ton téléphone. Il n'est envoyé qu'au serveur de ta messagerie."), color = Gris, fontSize = 12.sp)
                }
            }
        } else {
            item {
                CatTile("🧭", Tr.t("Ménage guidé des mails"), Tr.t("Trier, mettre l'important à l'abri, vider la boîte"),
                    Tr.t("4 étapes"), Tr.t("CONSEILLÉ"), Vert, Modifier.fillMaxWidth().padding(vertical = 6.dp), onClick = openGuide)
                BoxCard {
                    InfoRow("✉️", a.email, a.host)
                    Gap()
                    BigButton(if (vm.mailResult == null) Tr.t("🔍 Analyser ma boîte mail") else Tr.t("🔄 Analyser à nouveau")) { vm.analyseMail() }
                    Text(Tr.t("Analyse des 3 000 mails les plus récents de la boîte de réception."), color = Gris, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
                }
                BoxCard {
                    InfoRow("💾", Tr.t("Sauvegarder des mails"), Tr.t("Choisis tes mails : ils sont gardés avec leurs pièces jointes, les PDF dans un dossier à part"), onClick = openSave)
                    InfoRow("↩️", Tr.t("Remettre des mails"), Tr.t("Récupère des mails mis à la corbeille de ta messagerie"), onClick = openTrash)
                }
                MailsOutils(vm)
            }
            vm.mailResult?.let { r ->
                item {
                    SectionTitle(Tr.t("RÉSULTAT"))
                    BoxCard {
                        Text(Tr.t("%1 mails dans la boîte · %2 analysés", fmtNum(r.total), fmtBytes(r.bytes)), color = Gris, fontSize = 13.sp)
                        if (r.quotaMax > 0) {
                            val ratio = (r.quotaUsed.toFloat() / r.quotaMax).coerceIn(0f, 1f)
                            Gap(6)
                            Text(Tr.t("Boîte mail remplie à %1 % (%2 / %3)", (ratio * 100).toInt(), fmtBytes(r.quotaUsed), fmtBytes(r.quotaMax)),
                                fontWeight = FontWeight.Bold, color = if (ratio > 0.85f) Rouge else Texte, fontSize = 14.sp)
                            androidx.compose.material3.LinearProgressIndicator(progress = { ratio },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = if (ratio > 0.85f) Rouge else Violet, trackColor = Carte2)
                        }
                        Gap()
                        Text(Tr.t("🛡️ Les mails de ta banque, de l'administration ou de santé ne sont jamais mis à la corbeille."),
                            color = Vert, fontSize = 12.sp)
                        Gap(6)
                        Text(Tr.t("👀 Chaque bouton montre d'abord la liste des mails : tu décoches ceux à garder, puis tu valides."),
                            color = Gris, fontSize = 12.sp)
                        MailAction("📰", Tr.t("Newsletters et pubs"), plural(r.newsletters, "mail"), r.newsletters > 0) { vm.trashNewsletters() }
                        MailAction("📎", Tr.t("Gros mails (+ 5 Mo)"), "${plural(r.bigCount, "mail")} · ${fmtBytes(r.bigBytes)}", r.bigCount > 0) { vm.trashBigMails() }
                        MailAction("🕰️", Tr.t("Mails de plus de 2 ans"), "${plural(r.oldCount, "mail")} · ${fmtBytes(r.oldBytes)}", r.oldCount > 0) { vm.trashOldMails() }
                        MailAction("👯", Tr.t("Mails en double"), Tr.t("Reçus plusieurs fois à quelques secondes d'écart : %1", plural(r.doublons.size, "copie")), r.doublons.isNotEmpty()) { vm.trashDoublons() }
                    }
                    // Lecture seule : retrouver vite l'important
                    if (r.factures.isNotEmpty() || r.colis.isNotEmpty() || r.voyages.isNotEmpty()) {
                        SectionTitle(Tr.t("À RETROUVER VITE"))
                        BoxCard {
                            ListeMails("🧾", Tr.t("Factures et papiers"), r.factures)
                            ListeMails("📦", Tr.t("Colis et livraisons"), r.colis)
                            ListeMails("✈️", Tr.t("Voyages et réservations"), r.voyages)
                        }
                    }
                    if (r.senders.isNotEmpty()) {
                        SectionTitle(Tr.t("QUI T'ÉCRIT LE PLUS"))
                        @Suppress("UNUSED_VARIABLE") val v = vm.reglesVersion
                        var choisi by remember { mutableStateOf<com.jmdargere.cleansmart.data.Sender?>(null) }
                        BoxCard {
                            Text(Tr.t("Touche un expéditeur : toujours jeter, toujours garder, me prévenir, se désabonner."), color = Gris, fontSize = 12.sp)
                            r.senders.take(25).forEach { s ->
                                val e = s.email.lowercase()
                                val regles = (if (e in com.jmdargere.cleansmart.data.Regles.jeter) "🗑️" else "") +
                                        (if (com.jmdargere.cleansmart.data.Regles.garder(e)) "🛡️" else "") +
                                        (if (e in com.jmdargere.cleansmart.data.Regles.alertes) "🔔" else "")
                                InfoRow(when { s.important -> "🛡️"; s.newsletter -> "📰"; s.notif -> "🔔"; else -> "👤" },
                                    s.name.ifEmpty { s.email }, (if (regles.isNotEmpty()) "$regles  " else "") + s.email, fmtNum(s.count),
                                    onClick = { choisi = s })
                            }
                        }
                        choisi?.let { s -> ExpediteurDialog(vm, s) { choisi = null } }
                    }
                }
            }
            item {
                Gap()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { edit = true }) { Text(Tr.t("Changer de compte"), color = Violet2) }
                    TextButton(onClick = {
                        ask(Confirm(Tr.t("Oublier ce compte ?"), Tr.t("L'adresse et le mot de passe seront effacés du téléphone."), Tr.t("Oublier")) { vm.forgetMail() })
                    }) { Text(Tr.t("Oublier"), color = Rouge) }
                }
            }
        }
    }
}

/** Une liste de mails depliable (lecture seule). */
@Composable
private fun ListeMails(emoji: String, titre: String, mails: List<com.jmdargere.cleansmart.data.BigMail>) {
    if (mails.isEmpty()) return
    var ouvert by rememberSaveable(titre) { mutableStateOf(false) }
    InfoRow(emoji, titre, if (ouvert) "" else Tr.t("Toucher pour voir"), fmtNum(mails.size), onClick = { ouvert = !ouvert })
    if (ouvert) mails.take(30).forEach { m ->
        Column(Modifier.fillMaxWidth().padding(start = 58.dp, bottom = 8.dp)) {
            Text(m.subject.ifEmpty { Tr.t("(sans objet)") }, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${m.from} · ${com.jmdargere.cleansmart.data.fmtDate(m.date)}", color = Gris, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// =====================================================================================
// REMETTRE DES MAILS : depuis la corbeille de la messagerie
// =====================================================================================

@Composable
private fun MailsCorbeille(vm: AppViewModel) {
    val list = vm.mailTrash
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 12.dp)) {
            item {
                Text(Tr.t("Les 200 derniers mails de la corbeille de ta messagerie. Coche ceux à remettre dans ta boîte de réception."),
                    color = Gris, fontSize = 14.sp, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 8.dp))
            }
            if (list != null && list.isEmpty()) item { Text(Tr.t("La corbeille est vide."), color = Gris, modifier = Modifier.padding(16.dp)) }
            items((list ?: emptyList()).size) { i ->
                val m = list!![i]
                val on = m.uid in vm.mailTrashSel
                BoxCard(onClick = { vm.toggleMailTrash(m.uid) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { vm.toggleMailTrash(m.uid) },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Violet))
                        Column(Modifier.weight(1f)) {
                            Text(m.subject.ifEmpty { Tr.t("(sans objet)") }, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${m.from} · ${com.jmdargere.cleansmart.data.fmtDate(m.date)}", color = Gris, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (vm.mailTrashSel.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                BigButton(Tr.t("↩️ Remettre %1 dans ma boîte", plural(vm.mailTrashSel.size, "mail"))) { vm.restoreMails() }
            }
        }
    }
}

// =====================================================================================
// SAUVER DES MAILS : choisir les mails, ils sont gardes avec leurs pieces jointes
// =====================================================================================

@Composable
private fun MailsSauver(vm: AppViewModel, chooseFolder: () -> Unit) {
    val list = vm.mailFiles
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 12.dp)) {
            item {
                BoxCard {
                    Text(Tr.t("Chaque mail choisi est enregistré dans ton dossier de sauvegarde :"), color = Gris, fontSize = 14.sp)
                    Gap(6)
                    Text(Tr.t("📁 CleanSmart / Mails : le mail complet (.eml)\n📕 CleanSmart / PDF : tous les PDF réunis\n📎 CleanSmart / Pieces jointes : les autres fichiers"),
                        fontSize = 14.sp, lineHeight = 22.sp)
                    if (!vm.backupReady()) {
                        Gap()
                        BigButton(Tr.t("📁 Choisir le dossier de sauvegarde"), onClick = chooseFolder)
                    }
                    Gap()
                    BigButton(if (list == null) Tr.t("🔍 Chercher les mails avec pièces jointes") else Tr.t("🔄 Chercher à nouveau"), color = Carte2) {
                        vm.findMailAttachments()
                    }
                }
            }
            if (list != null) {
                item {
                    Text(Tr.t("%1 avec pièces jointes (dont %2 avec PDF)", plural(list.size, "mail"), fmtNum(list.count { it.hasPdf })),
                        color = Gris, fontSize = 13.sp, modifier = Modifier.padding(4.dp, 10.dp, 4.dp, 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        OutlinedButton(onClick = { vm.selectMailFiles("pdf") }) { Text(Tr.t("📕 Cocher les PDF"), color = Texte) }
                        RowGap()
                        OutlinedButton(onClick = { vm.selectMailFiles("all") }) { Text(Tr.t("Tout cocher"), color = Texte) }
                        RowGap()
                        OutlinedButton(onClick = { vm.selectMailFiles("none") }) { Text(Tr.t("Rien"), color = Texte) }
                    }
                }
                items(list.size) { i ->
                    val m = list[i]
                    val on = m.uid in vm.mailFilesSel
                    BoxCard(onClick = { vm.toggleMailFile(m.uid) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { vm.toggleMailFile(m.uid) },
                                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Violet))
                            Column(Modifier.weight(1f)) {
                                Text(m.subject.ifEmpty { Tr.t("(sans objet)") }, fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${m.from} · ${com.jmdargere.cleansmart.data.fmtDate(m.date)}", color = Gris, fontSize = 12.sp, maxLines = 1)
                                Text(m.files.joinToString("  ") { (if (it.endsWith(".pdf", true)) "📕 " else "📎 ") + it },
                                    color = Violet2, fontSize = 12.sp, maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (vm.mailFilesSel.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                BigButton(Tr.t("💾 Sauvegarder %1", plural(vm.mailFilesSel.size, "mail"))) {
                    if (vm.backupReady()) vm.saveSelectedMails() else chooseFolder()
                }
            }
        }
    }
}

@Composable
private fun MailAction(emoji: String, title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { InfoRow(emoji, title, sub) }
        androidx.compose.material3.Button(onClick = onClick, enabled = enabled,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Rouge)) { Text(Tr.t("Corbeille")) }
    }
}

// =====================================================================================
// OPTIONS : seulement l'essentiel
// =====================================================================================

@Composable
private fun Options(vm: AppViewModel, chooseFolder: () -> Unit, openManual: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp)) {
        item {
            SectionTitle(Tr.t("SAUVEGARDE"))
            BoxCard {
                InfoRow("📁", vm.backupFolderLabel(), if (vm.backupReady()) Tr.t("Dossier de sauvegarde") else Tr.t("Carte SD, clé USB, Google Drive…"))
                Gap()
                BigButton(if (vm.backupReady()) Tr.t("Changer de dossier") else Tr.t("Choisir un dossier"), onClick = chooseFolder)
                Gap()
                OptionSwitch(Tr.t("Sauvegarder avant de supprimer"),
                    Tr.t("Désactivé par défaut. Si tu l'actives, les photos (et les contacts en .vcf) sont copiés dans le dossier avant chaque nettoyage, par sécurité."), s.backupBeforeDelete) {
                    vm.updateSettings(s.copy(backupBeforeDelete = it))
                }
                if (vm.backupReady()) {
                    Gap()
                    BigButton(Tr.t("💾 Sauvegarder toutes mes photos"), color = Carte2) { vm.backupEverything() }
                    Text(Tr.t("Seules les nouvelles photos sont copiées à chaque fois."), color = Gris, fontSize = 12.sp)
                }
            }
            SectionTitle(Tr.t("LA NUIT"))
            BoxCard {
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                fun demanderNotif() {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                OptionSwitch(Tr.t("Sauvegarde automatique chaque nuit"),
                    Tr.t("Vers 2 h, les nouvelles photos sont copiées dans ton dossier de sauvegarde. Une notification te dit si ça n'a pas marché."),
                    s.nightBackup) {
                    if (it) demanderNotif()
                    vm.updateSettings(s.copy(nightBackup = it))
                }
                OptionSwitch(Tr.t("Alertes et rapport du mois"),
                    Tr.t("Préviens-moi si le téléphone n'a presque plus de place (moins de 1 Go), et un petit bilan le 1er du mois."),
                    s.alerts) {
                    if (it) demanderNotif()
                    vm.updateSettings(s.copy(alerts = it))
                }
            }
            SectionTitle(Tr.t("LIBÉRER LA PLACE"))
            BoxCard {
                Text(Tr.t("Garder sur le téléphone les photos des derniers…"), fontWeight = FontWeight.Bold)
                Gap(6)
                val mois = listOf(1, 3, 6, 12)
                ChipsChoix(listOf(Tr.t("1 mois"), Tr.t("3 mois"), Tr.t("6 mois"), Tr.t("12 mois")), mois.indexOf(s.keepMonths).coerceAtLeast(0)) {
                    vm.updateSettings(s.copy(keepMonths = mois[it]))
                }
                Text(Tr.t("Les plus anciennes ne sont proposées à la corbeille que si elles sont déjà dans ta sauvegarde."), color = Gris, fontSize = 12.sp)
            }
            SectionTitle(Tr.t("PROTECTION"))
            BoxCard {
                OptionSwitch(Tr.t("Protéger mes favoris"),
                    Tr.t("Les photos mises en favori (ou protégées avec le bouton 🛡️) ne sont jamais proposées"), s.protectFavorites) {
                    vm.updateSettings(s.copy(protectFavorites = it))
                }
            }
            SectionTitle(Tr.t("RECHERCHE DES PHOTOS RATÉES"))
            BoxCard {
                Text(Tr.t("Sévérité"), fontWeight = FontWeight.Bold)
                Text(Tr.t("Prudente : moins de photos proposées. Stricte : davantage."), color = Gris, fontSize = 13.sp)
                Gap(6)
                ChipsChoix(listOf(Tr.t("Prudente"), Tr.t("Normale"), Tr.t("Stricte")), s.severity) { vm.updateSettings(s.copy(severity = it)) }
                Text(Tr.t("Relance l'analyse après un changement."), color = Gris, fontSize = 12.sp)
            }
            SectionTitle(Tr.t("AFFICHAGE"))
            BoxCard {
                Text(Tr.t("Taille du texte"), fontWeight = FontWeight.Bold)
                Gap(6)
                ChipsChoix(listOf(Tr.t("Normale"), Tr.t("Gros"), Tr.t("Très gros")), s.textSize) { vm.updateSettings(s.copy(textSize = it)) }
                Gap(12)
                Text("🌍 " + Tr.t("Langue") + " / Language", fontWeight = FontWeight.Bold)
                Gap(6)
                ChipsChoix(listOf(Tr.t("Comme le téléphone"), "Français", "English"), s.langue) { vm.updateSettings(s.copy(langue = it)) }
            }
            SectionTitle(Tr.t("AUTRE"))
            BoxCard {
                InfoRow("🎉", Tr.t("Espace libéré"), Tr.t("Depuis l'installation"), fmtBytes(vm.freed))
                TextButton(onClick = { vm.resetFreed() }) { Text(Tr.t("Remettre le compteur à zéro"), color = Gris) }
                InfoRow("📖", Tr.t("Mode d'emploi"), Tr.t("Français / English"), onClick = openManual)
                OptionSwitch(Tr.t("🎬 Démonstration"),
                    Tr.t("Montre l'appli avec de fausses photos, de faux mails et de faux contacts. Rien n'est touché sur le téléphone : idéal pour présenter Clean Smart."),
                    vm.demo) { vm.changerDemo(it) }
                InfoRow("🆕", Tr.t("Rechercher une mise à jour"), Tr.t("Version installée : %1", BuildConfig.VERSION_NAME),
                    onClick = { vm.verifierMiseAJour(true) })
            }
            SectionTitle(Tr.t("CONTACT"))
            BoxCard {
                Text(Tr.t("Une question, un souci, la version complète ? Écris-moi :"), color = Gris, fontSize = 14.sp)
                Gap(8)
                BoutonsContact()
            }
            BoxCard {
                InfoRow("⚙️", Tr.t("Réglages de stockage Android"), "", onClick = {
                    runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                })
            }
            Gap()
            Text(Tr.t("Clean Smart %1 — JM Dargère", BuildConfig.VERSION_NAME), color = Gris, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ChipsChoix(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        labels.forEachIndexed { i, l ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(l) },
                modifier = Modifier.padding(end = 6.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet, selectedLabelColor = Color.White))
        }
    }
}

@Composable
private fun OptionSwitch(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(sub, color = Gris, fontSize = 13.sp)
        }
        RowGap(12)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Violet))
    }
}
