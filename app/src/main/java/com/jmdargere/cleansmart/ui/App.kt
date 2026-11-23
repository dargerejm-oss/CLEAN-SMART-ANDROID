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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.jmdargere.cleansmart.data.Essai
import com.jmdargere.cleansmart.data.Provider
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural

/** Les ecrans de l'appli (navigation simple, sans bibliotheque). */
private enum class Ecran { ACCUEIL, GUIDE, CATEGORIE, TRI, MAILS, MAIL_GUIDE, MAIL_TRI, MAILS_SAUVER, MAILS_CORBEILLE, OPTIONS, MANUEL }

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

    var depuisGuide by rememberSaveable { mutableStateOf(false) }
    fun retour() {
        ecran = when {
            ecran == Ecran.TRI && depuisGuide -> Ecran.GUIDE
            ecran == Ecran.MAIL_TRI -> Ecran.MAIL_GUIDE
            ecran == Ecran.MAILS_SAUVER || ecran == Ecran.MAILS_CORBEILLE || ecran == Ecran.MAIL_GUIDE -> Ecran.MAILS
            else -> Ecran.ACCUEIL
        }
        if (ecran != Ecran.TRI) depuisGuide = false
    }
    BackHandler(enabled = ecran != Ecran.ACCUEIL) { retour() }

    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    // --- Autorisations : photos (obligatoire) + contacts (facultatif) ---
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(ctx, mediaPerms())) vm.analyse(granted(ctx, contactPerms))
        else vm.message = "Sans l'accès aux photos, Clean Smart ne peut rien analyser."
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
            "Vider la corbeille ?",
            "${plural(n, "élément")} (${fmtBytes(vm.trash.sumOf { it.size })}) seront supprimés DÉFINITIVEMENT pour libérer la place tout de suite.\n\nIls ne pourront plus être récupérés.",
            "Vider",
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
            "Libérer ${fmtBytes(l.sumOf { it.size })} ?",
            "${plural(l.size, "photo ou vidéo", "photos ou vidéos")} de plus de ${vm.settings.keepMonths} mois " +
                    "sont déjà à l'abri dans ta sauvegarde (même nom, même taille).\n\nElles vont dans la corbeille de la Galerie (récupérables 30 jours). Les plus récentes restent sur le téléphone.",
            "Libérer",
        ) { vm.prepareFreeSpace { s -> trashLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
    }

    // --- PDF souvenir : envoi a un proche une fois cree ---
    fun souvenir(periode: Int) = vm.makeSouvenir(periode) { uri ->
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, "Envoyer le PDF souvenir")) }
    }

    // --- Compression des videos, puis proposition de jeter les originaux ---
    fun compresser(items: List<com.jmdargere.cleansmart.data.MediaItem>) {
        val videos = items.filter { it.video }
        if (videos.isEmpty()) return
        confirm = Confirm(
            "Compresser ${plural(videos.size, "vidéo")} ?",
            "Chaque vidéo est refaite en 720p, souvent 3 à 5 fois plus légère, et enregistrée dans Films / Clean Smart. " +
                    "L'original n'est pas touché : je te proposerai ensuite de le mettre à la corbeille.\n\nCela peut prendre quelques minutes.",
            "Compresser", danger = false,
        ) {
            vm.compressVideos(videos) { originaux, gain ->
                confirm = Confirm(
                    "Jeter les originaux ?",
                    "${plural(originaux.size, "vidéo compressée", "vidéos compressées")} avec succès. En mettant les originaux à la corbeille, tu gagnes ${fmtBytes(gain)}.",
                    "Corbeille",
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
        runCatching { ctx.startActivity(Intent.createChooser(send, "Envoyer ${plural(l.size, "fichier")}")) }
        if (items.size > 20) vm.message = "Seuls les 20 premiers fichiers sont envoyés."
    }

    fun nettoyer() {
        val n = vm.selected.size
        val save = vm.settings.backupBeforeDelete && vm.backupReady()
        confirm = Confirm(
            "Nettoyer ${plural(n, "élément")} ?",
            "${fmtBytes(vm.selectedBytes)} vont aller dans la corbeille de la Galerie (récupérables 30 jours)." +
                    if (save) "\n\nUne copie est d'abord faite dans ton dossier de sauvegarde." else "",
            "Nettoyer",
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
            vm.message = "📁 Dossier de sauvegarde enregistré"
        }
    }

    val showCleanBar = (ecran == Ecran.ACCUEIL || ecran == Ecran.CATEGORIE) && vm.selected.isNotEmpty()

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
                        Ecran.ACCUEIL -> "CLEAN SMART"
                        Ecran.GUIDE -> "🧭 Ménage guidé"
                        Ecran.CATEGORIE -> cat.emoji + " " + cat.title
                        Ecran.TRI -> "👆 Tri rapide"
                        Ecran.MAILS -> "✉️ Mails"
                        Ecran.MAIL_GUIDE -> "🧭 Ménage des mails"
                        Ecran.MAIL_TRI -> "👆 Tri des expéditeurs"
                        Ecran.MAILS_SAUVER -> "💾 Sauver des mails"
                        Ecran.MAILS_CORBEILLE -> "↩️ Remettre des mails"
                        Ecran.OPTIONS -> "⚙️ Options"
                        Ecran.MANUEL -> "📖 Mode d'emploi"
                    }, fontWeight = FontWeight.ExtraBold)
                },
                navigationIcon = {
                    if (ecran != Ecran.ACCUEIL) TextButton(onClick = { retour() }) { Text("‹ Retour", color = Color.White, fontWeight = FontWeight.Bold) }
                },
                actions = {
                    if (ecran == Ecran.ACCUEIL) TextButton(onClick = { ecran = Ecran.OPTIONS }) { Text("⚙️", fontSize = 22.sp) }
                },
            )
        },
        bottomBar = {
            if (showCleanBar) {
                Box(Modifier.fillMaxWidth().background(Fond).navigationBarsPadding().padding(16.dp, 8.dp)) {
                    BigButton("🧹 Nettoyer · ${fmtNum(vm.selected.size)} · ${fmtBytes(vm.selectedBytes)}", color = Rouge) { nettoyer() }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (ecran) {
                Ecran.ACCUEIL -> Accueil(vm, ::analyser, joursEssai, ::viderCorbeille, ::annuler, ::souvenir,
                    openCat = { cat = it; ecran = Ecran.CATEGORIE },
                    openGuide = { ecran = Ecran.GUIDE },
                    openTri = { if (granted(ctx, mediaPerms())) ecran = Ecran.TRI else permLauncher.launch(mediaPerms() + contactPerms) },
                    openMail = { ecran = Ecran.MAILS },
                    openManual = { ecran = Ecran.MANUEL },
                    chooseFolder = { folderLauncher.launch(null) },
                    ask = { confirm = it })
                Ecran.CATEGORIE -> Categorie(vm, cat, envoyer = ::envoyer, proteger = ::proteger, compresser = ::compresser)
                Ecran.GUIDE -> MenageGuide(vm,
                    analyser = ::analyser,
                    nettoyerSur = {
                        val l = vm.safeItems
                        confirm = Confirm(
                            "Faire le ménage sûr ?",
                            "${plural(l.size, "photo")} (${fmtBytes(l.sumOf { it.size })}) : doublons en trop, rafales en trop, photos noires ou blanches, captures de plus d'un mois.\n\nElles vont dans la corbeille de la Galerie (30 jours pour les récupérer).",
                            "Nettoyer",
                        ) { vm.guideClean { s -> trashLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
                    },
                    verifier = { vm.guideReview(); depuisGuide = true; ecran = Ecran.TRI },
                    chooseFolder = { folderLauncher.launch(null) })
                Ecran.TRI -> TriRapide(vm, onKeep = {
                    vm.prepareSwipeKeep { s -> protectLauncher.launch(IntentSenderRequest.Builder(s).build()) }
                }) {
                    confirm = Confirm(
                        "Jeter ${plural(vm.swipeTrash.size, "photo")} ?",
                        "${fmtBytes(vm.swipeTrash.sumOf { it.size })} vont dans la corbeille de la Galerie (récupérables 30 jours).",
                        "Corbeille",
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
            }
        }
    }

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
    openTri: () -> Unit,
    openMail: () -> Unit,
    openManual: () -> Unit,
    chooseFolder: () -> Unit,
    ask: (Confirm) -> Unit,
) {
    val m = vm.media
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp)) {
        if (Essai.actif) item {
            Text("🎁 Version d'essai · ${plural(joursEssai, "jour restant", "jours restants")}",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(if (joursEssai <= 5) Rouge else Violet, RoundedCornerShape(12.dp)).padding(10.dp))
        }
        item {
            BoxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StorageRing(vm.storage.ratio)
                    RowGap(16)
                    Column(Modifier.weight(1f)) {
                        Text("Stockage du téléphone", color = Gris, fontSize = 13.sp)
                        Text("${fmtBytes(vm.storage.used)} / ${fmtBytes(vm.storage.total)}", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                        Text("${fmtBytes(vm.storage.free)} disponibles", color = Gris, fontSize = 13.sp)
                        if (vm.freed > 0) Text("🎉 ${fmtBytes(vm.freed)} libérés au total", color = Vert, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (vm.lastTrashed.isNotEmpty()) {
                OutlinedButton(onClick = annuler, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp)) {
                    Text("↩️ J'ai fait une bêtise : remettre les ${plural(vm.lastTrashed.size, "derniers fichiers", "derniers fichiers")}",
                        color = Rouge, fontWeight = FontWeight.Bold)
                }
            }
            if (vm.trash.isNotEmpty()) {
                BoxCard {
                    InfoRow("🗑️", "Corbeille : ${plural(vm.trash.size, "élément")}",
                        "Ils prennent encore de la place pendant 30 jours", fmtBytes(vm.trash.sumOf { it.size }))
                    Gap(4)
                    BigButton("Vider la corbeille maintenant", color = Carte2, onClick = viderCorbeille)
                }
            }
            Gap()
            // le Menage guide : la facon la plus simple de tout faire, dans le bon ordre
            CatTile("🧭", "Ménage guidé", "Trier, garder, sauvegarder, libérer",
                "4 étapes", "CONSEILLÉ", Vert, Modifier.fillMaxWidth(), onClick = openGuide)
            Gap()
            BigButton(if (m == null) "🔍 Analyser mon téléphone" else "🔄 Analyser à nouveau", color = Bleu, onClick = analyser)
            Gap()
        }

        if (m == null) {
            item {
                BoxCard {
                    Text("Clean Smart trouve en un clic :", fontWeight = FontWeight.Bold)
                    Gap(6)
                    Text("🧬 les doublons   🎞️ les photos ratées en rafale\n📱 les captures d'écran   🎬 les grosses vidéos\n💬 les médias WhatsApp   👥 les contacts en double\n✉️ les newsletters et gros mails",
                        color = Gris, fontSize = 14.sp, lineHeight = 22.sp)
                    Gap(6)
                    Text("Rien n'est supprimé sans ton accord : tout passe par la corbeille.", color = Gris, fontSize = 13.sp)
                }
            }
        } else {
            item {
                // chaque fichier compte une seule fois (une photo peut etre dans 2 categories)
                val total = Cat.entries.filter { it != Cat.BIGGEST }
                    .flatMap { vm.removable(it) }.distinctBy { it.key }.sumOf { it.size }
                BoxCard {
                    Text("Espace à récupérer", color = Gris, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(fmtBytes(total), fontSize = 36.sp, fontWeight = FontWeight.Black, color = Violet2,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text("${fmtNum(m.photoCount)} photos · ${fmtNum(m.videoCount)} vidéos analysées", color = Gris, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                SectionTitle("PHOTOS ET VIDÉOS")
                val visibles = Cat.entries.filter { vm.removable(it).isNotEmpty() }
                if (visibles.isEmpty()) BoxCard { Text("🎉 Rien à nettoyer, tout est propre !", color = Gris) }
                // grandes tuiles de couleur, deux par ligne
                visibles.chunked(2).forEach { ligne ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ligne.forEach { c ->
                            val r = vm.removable(c)
                            val sel = r.count { it.key in vm.selected }
                            CatTile(c.emoji, c.title, plural(r.size, "élément"), fmtBytes(r.sumOf { it.size }),
                                if (sel > 0) "✓ $sel choisi" + (if (sel > 1) "s" else "") else "",
                                couleurDe(c), Modifier.weight(1f)) { openCat(c) }
                        }
                        if (ligne.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            BoxCard(onClick = openTri) {
                InfoRow("👆", "Tri rapide", "Une photo à la fois : glisse à gauche pour jeter, à droite pour garder", onClick = openTri)
            }
        }

        // Plus d'outils : replie par defaut pour garder l'accueil simple
        item {
            var ouvert by rememberSaveable { mutableStateOf(false) }
            var choixPdf by remember { mutableStateOf(false) }
            BoxCard {
                InfoRow("🧰", "Plus d'outils", if (ouvert) "" else "Libérer la place, PDF souvenir…", onClick = { ouvert = !ouvert })
                if (ouvert) {
                    InfoRow("🧹", "Libérer la place",
                        "Met à la corbeille les photos de plus de ${vm.settings.keepMonths} mois déjà sauvegardées",
                        onClick = { vm.findFreeSpace() })
                    InfoRow("📕", "PDF souvenir", "Les plus belles photos du mois ou de l'année, à envoyer à un proche",
                        onClick = { choixPdf = true })
                    InfoRow("🗜️", "Réduire mes vidéos", "Ouvre « Grosses vidéos », coche-les puis « Compresser »",
                        onClick = { if (vm.media != null) openCat(Cat.BIG_VIDEOS) else analyser() })
                }
            }
            if (choixPdf) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { choixPdf = false },
                    containerColor = Carte,
                    title = { Text("PDF souvenir", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            listOf("Ce mois-ci", "Le mois dernier", "Cette année").forEachIndexed { i, l ->
                                BigButton(l, color = Carte2) { choixPdf = false; souvenir(i) }
                                Gap(6)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { choixPdf = false }) { Text("Annuler", color = Gris) } },
                )
            }
        }

        // Contacts en double
        vm.contacts?.let { c ->
            item {
                SectionTitle("CONTACTS")
                BoxCard {
                    if (c.duplicates == 0) InfoRow("👥", "Aucun contact en double", "sur ${fmtNum(c.total)} contacts")
                    else {
                        InfoRow("👥", plural(c.duplicates, "contact en double", "contacts en double"),
                            c.groups.take(3).joinToString(", ") { it.name.ifEmpty { it.number } } + if (c.groups.size > 3) "…" else "")
                        Gap()
                        BigButton("Supprimer les doublons") {
                            ask(Confirm("Supprimer ${plural(c.duplicates, "doublon")} ?",
                                "Le contact d'origine est gardé." +
                                        (if (vm.settings.backupBeforeDelete && vm.backupReady()) "\n\nLes copies sont d'abord exportées en fichier .vcf dans ton dossier de sauvegarde." else "") +
                                        "\n\nAttention : la suppression est synchronisée avec ton compte (Google…).",
                                "Supprimer") { vm.deleteDuplicateContacts() })
                        }
                    }
                }
            }
        }

        // Mails
        item {
            SectionTitle("BOÎTE MAIL")
            BoxCard(onClick = openMail) {
                val a = vm.mailAccount
                val r = vm.mailResult
                when {
                    !a.ready -> InfoRow("✉️", "Connecter ma boîte mail", "Gmail, Outlook, Yahoo, Orange, Free…", onClick = openMail)
                    r == null -> InfoRow("✉️", a.email, "Toucher pour analyser", onClick = openMail)
                    else -> InfoRow("✉️", a.email, "${fmtNum(r.newsletters)} newsletters · ${fmtNum(r.bigCount)} gros mails", fmtBytes(r.bigBytes), onClick = openMail)
                }
            }
        }

        // Sauvegarde
        item {
            SectionTitle("SAUVEGARDE")
            BoxCard {
                val ok = vm.backupReady()
                InfoRow("💾", if (ok) vm.backupFolderLabel() else "Aucun dossier de sauvegarde",
                    if (ok) "Carte SD, clé USB, Drive… choisis où tu veux" else "Choisis une carte SD, une clé USB ou Google Drive")
                Gap()
                if (ok) BigButton("💾 Sauvegarder toutes mes photos") { vm.backupEverything() }
                else BigButton("📁 Choisir un dossier", onClick = chooseFolder)
            }
        }

        item {
            Gap()
            OutlinedButton(onClick = openManual, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("📖 Mode d'emploi", color = Texte)
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
        else -> 5
    }

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)) {
        item {
            Text("Trier d'abord, garder le meilleur, sauvegarder seulement les bonnes photos, puis libérer la place.",
                color = Gris, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 8.dp))
            if (m == null) {
                EtapeCarte(0, "🔍", "Avant tout : analyser", "Clean Smart regarde toutes tes photos (rien n'est supprimé).",
                    Bleu, prochaine == 0, false, "Analyser mon téléphone", analyser)
            }
            EtapeCarte(1, "🧹", "Le ménage sûr",
                if (m == null) "Doublons, rafales en trop, photos noires ou blanches, vieilles captures."
                else "${plural(safe.size, "photo")} · ${fmtBytes(safe.sumOf { it.size })} : doublons, rafales en trop, photos noires ou blanches, captures de plus d'un mois. Les meilleures sont gardées.",
                Rouge, prochaine == 1, 1 in d || (m != null && safe.isEmpty()), "Tout nettoyer", nettoyerSur)
            EtapeCarte(2, "👆", "Je vérifie le douteux",
                if (m == null) "Les photos un peu floues, WhatsApp, captures récentes : c'est toi qui décides."
                else "${plural(review.size, "photo")} à regarder une par une. Glisse à gauche pour jeter, à droite pour garder (elle devient favori ⭐).",
                Pourpre, prochaine == 2, 2 in d || (m != null && review.isEmpty()), "Trier en glissant", verifier)
            EtapeCarte(3, "💾", "Je mets à l'abri les bonnes",
                if (!vm.backupReady()) "Choisis d'abord un dossier : carte SD, clé USB ou Google Drive."
                else "Seulement les bonnes photos : jamais les doublons, rafales en trop ou ratées. Les favoris vont aussi dans « Mes meilleures ».",
                Bleu, prochaine == 3, 3 in d,
                if (vm.backupReady()) "Sauvegarder mes bonnes photos" else "Choisir un dossier",
                if (vm.backupReady()) ({ vm.backupEverything() }) else chooseFolder)
            EtapeCarte(4, "📤", "Je libère la place",
                "Les photos de plus de ${vm.settings.keepMonths} mois déjà sauvegardées quittent le téléphone (corbeille, 30 jours).",
                Vert, prochaine == 4, 4 in d, "Libérer la place") { vm.findFreeSpace() }
            if (prochaine == 5) {
                Gap()
                CatTile("🎉", "Ménage terminé !", "Ton téléphone est propre et tes photos sont à l'abri", "", "", Vert,
                    Modifier.fillMaxWidth()) {}
            }
        }
    }
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
                    Text(if (faite) "✓" else if (n == 0) emoji else "$n", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                RowGap(12)
                Column(Modifier.weight(1f)) {
                    Text("$emoji  $titre", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (faite) Vert else Texte)
                    if (faite) Text("Fait ✔", color = Vert, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
        Text("Clean Smart", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Gap(16)
        Text("Ta période d'essai de 30 jours est terminée.", fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Gap(10)
        Text("Merci d'avoir essayé Clean Smart ! Installe la version complète pour continuer à nettoyer ton téléphone. " +
                "Tes réglages et ta boîte mail seront conservés.",
            color = Gris, textAlign = TextAlign.Center, lineHeight = 22.sp)
        Gap(10)
        Text("Les fichiers déjà mis à la corbeille restent récupérables dans la Galerie.", color = Gris, fontSize = 13.sp, textAlign = TextAlign.Center)
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
                Text("Touche une image pour la cocher · appui long pour la voir en grand", color = Gris, fontSize = 12.sp)
                Gap()
                Row {
                    OutlinedButton(onClick = { vm.selectAll(cat, true) }) { Text("Tout cocher", color = Texte) }
                    RowGap()
                    OutlinedButton(onClick = { vm.selectAll(cat, false) }) { Text("Tout décocher", color = Texte) }
                }
                if (choisis.isNotEmpty()) {
                    Gap(4)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        OutlinedButton(onClick = { envoyer(choisis) }) { Text("📤 Envoyer (${choisis.size})", color = Texte) }
                        RowGap()
                        OutlinedButton(onClick = { proteger(choisis) }) { Text("🛡️ Protéger (${choisis.size})", color = Texte) }
                        val nbVideos = choisis.count { it.video }
                        if (nbVideos > 0) {
                            RowGap()
                            OutlinedButton(onClick = { compresser(choisis) }) { Text("🗜️ Compresser ($nbVideos)", color = Texte) }
                        }
                    }
                }
                Gap(4)
            }
        }
        if (groups.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("🎉 Plus rien ici.", color = Gris, modifier = Modifier.padding(24.dp)) }
        }
        if (cat.grouped) {
            groups.forEachIndexed { gi, g ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "g$gi") {
                    Text("Groupe ${gi + 1} · ${g.items.size} photos", color = Gris, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                items(g.items, key = { "g$gi-" + it.key }) { m ->
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
                    Text("Ta messagerie", fontWeight = FontWeight.Bold)
                    Gap()
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Provider.entries.forEach { p ->
                            FilterChip(selected = prov == p, onClick = { prov = p; if (p != Provider.AUTRE) host = p.host },
                                label = { Text(p.label) }, modifier = Modifier.padding(end = 6.dp),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet, selectedLabelColor = Color.White))
                        }
                    }
                    if (prov == Provider.AUTRE) {
                        OutlinedTextField(host, { host = it }, label = { Text("Serveur IMAP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(email, { email = it }, label = { Text("Adresse mail") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it },
                        label = { Text(if (a.hasPassword) "Mot de passe (vide = garder l'actuel)" else "Mot de passe d'application") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Gap(12)
                    BigButton("Connecter") {
                        vm.connectMail(prov, if (prov == Provider.AUTRE) host else prov.host, email, pass) { edit = false; pass = "" }
                    }
                    if (a.ready) TextButton(onClick = { edit = false }) { Text("Annuler", color = Gris) }
                }
                BoxCard {
                    Text("💡 Aide", fontWeight = FontWeight.Bold)
                    Gap(4)
                    Text(prov.help, color = Gris, fontSize = 14.sp, lineHeight = 20.sp)
                    Provider.APP_PASSWORD_URL[prov]?.let { url ->
                        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
                            Text("Ouvrir la page du mot de passe d'application", color = Violet2)
                        }
                    }
                    Text("🔒 Ton mot de passe reste chiffré sur ton téléphone. Il n'est envoyé qu'au serveur de ta messagerie.", color = Gris, fontSize = 12.sp)
                }
            }
        } else {
            item {
                CatTile("🧭", "Ménage guidé des mails", "Trier, mettre l'important à l'abri, vider la boîte",
                    "4 étapes", "CONSEILLÉ", Vert, Modifier.fillMaxWidth().padding(vertical = 6.dp), onClick = openGuide)
                BoxCard {
                    InfoRow("✉️", a.email, a.host)
                    Gap()
                    BigButton(if (vm.mailResult == null) "🔍 Analyser ma boîte mail" else "🔄 Analyser à nouveau") { vm.analyseMail() }
                    Text("Analyse des 3 000 mails les plus récents de la boîte de réception.", color = Gris, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
                }
                BoxCard {
                    InfoRow("💾", "Sauvegarder des mails", "Choisis tes mails : ils sont gardés avec leurs pièces jointes, les PDF dans un dossier à part", onClick = openSave)
                    InfoRow("↩️", "Remettre des mails", "Récupère des mails mis à la corbeille de ta messagerie", onClick = openTrash)
                }
            }
            vm.mailResult?.let { r ->
                item {
                    SectionTitle("RÉSULTAT")
                    BoxCard {
                        Text("${fmtNum(r.total)} mails dans la boîte · ${fmtBytes(r.bytes)} analysés", color = Gris, fontSize = 13.sp)
                        if (r.quotaMax > 0) {
                            val ratio = (r.quotaUsed.toFloat() / r.quotaMax).coerceIn(0f, 1f)
                            Gap(6)
                            Text("Boîte mail remplie à ${(ratio * 100).toInt()} % (${fmtBytes(r.quotaUsed)} / ${fmtBytes(r.quotaMax)})",
                                fontWeight = FontWeight.Bold, color = if (ratio > 0.85f) Rouge else Texte, fontSize = 14.sp)
                            androidx.compose.material3.LinearProgressIndicator(progress = { ratio },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = if (ratio > 0.85f) Rouge else Violet, trackColor = Carte2)
                        }
                        Gap()
                        Text("🛡️ Les mails de ta banque, de l'administration ou de santé ne sont jamais mis à la corbeille.",
                            color = Vert, fontSize = 12.sp)
                        Gap(6)
                        MailAction("📰", "Newsletters et pubs", plural(r.newsletters, "mail"), r.newsletters > 0) {
                            val n = r.senders.count { it.newsletter }
                            ask(Confirm("Jeter les newsletters ?",
                                "Tous les mails de $n expéditeur(s) de newsletters vont dans la corbeille de ta messagerie (toute la boîte de réception, pas seulement les mails analysés).\n\nTu peux les récupérer depuis la corbeille.",
                                "Corbeille") { vm.trashNewsletters() })
                        }
                        MailAction("📎", "Gros mails (+ 5 Mo)", "${plural(r.bigCount, "mail")} · ${fmtBytes(r.bigBytes)}", r.bigCount > 0) {
                            ask(Confirm("Jeter les gros mails ?", "Les mails de plus de 5 Mo vont dans la corbeille de ta messagerie.", "Corbeille") { vm.trashBigMails() })
                        }
                        MailAction("🕰️", "Mails de plus de 2 ans", "${plural(r.oldCount, "mail")} · ${fmtBytes(r.oldBytes)}", r.oldCount > 0) {
                            ask(Confirm("Jeter les vieux mails ?", "Les mails de plus de 2 ans vont dans la corbeille de ta messagerie.", "Corbeille") { vm.trashOldMails() })
                        }
                        MailAction("👯", "Mails en double", "Reçus plusieurs fois à quelques secondes d'écart : ${plural(r.doublons.size, "copie")}", r.doublons.isNotEmpty()) {
                            ask(Confirm("Jeter les copies ?", "${plural(r.doublons.size, "copie")} en double vont dans la corbeille. Le premier exemplaire de chaque mail est gardé.", "Corbeille") { vm.trashDoublons() })
                        }
                    }
                    // Lecture seule : retrouver vite l'important
                    if (r.factures.isNotEmpty() || r.colis.isNotEmpty() || r.voyages.isNotEmpty()) {
                        SectionTitle("À RETROUVER VITE")
                        BoxCard {
                            ListeMails("🧾", "Factures et papiers", r.factures)
                            ListeMails("📦", "Colis et livraisons", r.colis)
                            ListeMails("✈️", "Voyages et réservations", r.voyages)
                        }
                    }
                    if (r.senders.isNotEmpty()) {
                        SectionTitle("QUI T'ÉCRIT LE PLUS")
                        BoxCard {
                            r.senders.take(15).forEach { s ->
                                InfoRow(when { s.important -> "🛡️"; s.newsletter -> "📰"; s.notif -> "🔔"; else -> "👤" },
                                    s.name.ifEmpty { s.email }, s.email, fmtNum(s.count))
                                if (s.unsubscribe.isNotEmpty() && !s.important) {
                                    TextButton(onClick = { desabonner(ctx, s) }, modifier = Modifier.padding(start = 50.dp)) {
                                        Text("🚫 Se désabonner", color = Rouge, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Gap()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { edit = true }) { Text("Changer de compte", color = Violet2) }
                    TextButton(onClick = {
                        ask(Confirm("Oublier ce compte ?", "L'adresse et le mot de passe seront effacés du téléphone.", "Oublier") { vm.forgetMail() })
                    }) { Text("Oublier", color = Rouge) }
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
    InfoRow(emoji, titre, if (ouvert) "" else "Toucher pour voir", fmtNum(mails.size), onClick = { ouvert = !ouvert })
    if (ouvert) mails.take(30).forEach { m ->
        Column(Modifier.fillMaxWidth().padding(start = 58.dp, bottom = 8.dp)) {
            Text(m.subject.ifEmpty { "(sans objet)" }, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
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
                Text("Les 200 derniers mails de la corbeille de ta messagerie. Coche ceux à remettre dans ta boîte de réception.",
                    color = Gris, fontSize = 14.sp, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 8.dp))
            }
            if (list != null && list.isEmpty()) item { Text("La corbeille est vide.", color = Gris, modifier = Modifier.padding(16.dp)) }
            items((list ?: emptyList()).size) { i ->
                val m = list!![i]
                val on = m.uid in vm.mailTrashSel
                BoxCard(onClick = { vm.toggleMailTrash(m.uid) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { vm.toggleMailTrash(m.uid) },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Violet))
                        Column(Modifier.weight(1f)) {
                            Text(m.subject.ifEmpty { "(sans objet)" }, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${m.from} · ${com.jmdargere.cleansmart.data.fmtDate(m.date)}", color = Gris, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (vm.mailTrashSel.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                BigButton("↩️ Remettre ${plural(vm.mailTrashSel.size, "mail")} dans ma boîte") { vm.restoreMails() }
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
                    Text("Chaque mail choisi est enregistré dans ton dossier de sauvegarde :", color = Gris, fontSize = 14.sp)
                    Gap(6)
                    Text("📁 CleanSmart / Mails : le mail complet (.eml)\n📕 CleanSmart / PDF : tous les PDF réunis\n📎 CleanSmart / Pieces jointes : les autres fichiers",
                        fontSize = 14.sp, lineHeight = 22.sp)
                    if (!vm.backupReady()) {
                        Gap()
                        BigButton("📁 Choisir le dossier de sauvegarde", onClick = chooseFolder)
                    }
                    Gap()
                    BigButton(if (list == null) "🔍 Chercher les mails avec pièces jointes" else "🔄 Chercher à nouveau", color = Carte2) {
                        vm.findMailAttachments()
                    }
                }
            }
            if (list != null) {
                item {
                    Text("${plural(list.size, "mail")} avec pièces jointes (dont ${fmtNum(list.count { it.hasPdf })} avec PDF)",
                        color = Gris, fontSize = 13.sp, modifier = Modifier.padding(4.dp, 10.dp, 4.dp, 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        OutlinedButton(onClick = { vm.selectMailFiles("pdf") }) { Text("📕 Cocher les PDF", color = Texte) }
                        RowGap()
                        OutlinedButton(onClick = { vm.selectMailFiles("all") }) { Text("Tout cocher", color = Texte) }
                        RowGap()
                        OutlinedButton(onClick = { vm.selectMailFiles("none") }) { Text("Rien", color = Texte) }
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
                                Text(m.subject.ifEmpty { "(sans objet)" }, fontWeight = FontWeight.Bold, maxLines = 1,
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
                BigButton("💾 Sauvegarder ${plural(vm.mailFilesSel.size, "mail")}") {
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
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Rouge)) { Text("Corbeille") }
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
            SectionTitle("SAUVEGARDE")
            BoxCard {
                InfoRow("📁", vm.backupFolderLabel(), if (vm.backupReady()) "Dossier de sauvegarde" else "Carte SD, clé USB, Google Drive…")
                Gap()
                BigButton(if (vm.backupReady()) "Changer de dossier" else "Choisir un dossier", onClick = chooseFolder)
                Gap()
                OptionSwitch("Sauvegarder avant de supprimer",
                    "Désactivé par défaut. Si tu l'actives, les photos (et les contacts en .vcf) sont copiés dans le dossier avant chaque nettoyage, par sécurité.", s.backupBeforeDelete) {
                    vm.updateSettings(s.copy(backupBeforeDelete = it))
                }
                if (vm.backupReady()) {
                    Gap()
                    BigButton("💾 Sauvegarder toutes mes photos", color = Carte2) { vm.backupEverything() }
                    Text("Seules les nouvelles photos sont copiées à chaque fois.", color = Gris, fontSize = 12.sp)
                }
            }
            SectionTitle("LA NUIT")
            BoxCard {
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                fun demanderNotif() {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                OptionSwitch("Sauvegarde automatique chaque nuit",
                    "Vers 2 h, les nouvelles photos sont copiées dans ton dossier de sauvegarde. Une notification te dit si ça n'a pas marché.",
                    s.nightBackup) {
                    if (it) demanderNotif()
                    vm.updateSettings(s.copy(nightBackup = it))
                }
                OptionSwitch("Alertes et rapport du mois",
                    "Préviens-moi si le téléphone n'a presque plus de place (moins de 1 Go), et un petit bilan le 1er du mois.",
                    s.alerts) {
                    if (it) demanderNotif()
                    vm.updateSettings(s.copy(alerts = it))
                }
            }
            SectionTitle("LIBÉRER LA PLACE")
            BoxCard {
                Text("Garder sur le téléphone les photos des derniers…", fontWeight = FontWeight.Bold)
                Gap(6)
                val mois = listOf(1, 3, 6, 12)
                ChipsChoix(listOf("1 mois", "3 mois", "6 mois", "12 mois"), mois.indexOf(s.keepMonths).coerceAtLeast(0)) {
                    vm.updateSettings(s.copy(keepMonths = mois[it]))
                }
                Text("Les plus anciennes ne sont proposées à la corbeille que si elles sont déjà dans ta sauvegarde.", color = Gris, fontSize = 12.sp)
            }
            SectionTitle("PROTECTION")
            BoxCard {
                OptionSwitch("Protéger mes favoris",
                    "Les photos mises en favori (ou protégées avec le bouton 🛡️) ne sont jamais proposées", s.protectFavorites) {
                    vm.updateSettings(s.copy(protectFavorites = it))
                }
            }
            SectionTitle("RECHERCHE DES PHOTOS RATÉES")
            BoxCard {
                Text("Sévérité", fontWeight = FontWeight.Bold)
                Text("Prudente : moins de photos proposées. Stricte : davantage.", color = Gris, fontSize = 13.sp)
                Gap(6)
                ChipsChoix(listOf("Prudente", "Normale", "Stricte"), s.severity) { vm.updateSettings(s.copy(severity = it)) }
                Text("Relance l'analyse après un changement.", color = Gris, fontSize = 12.sp)
            }
            SectionTitle("AFFICHAGE")
            BoxCard {
                Text("Taille du texte", fontWeight = FontWeight.Bold)
                Gap(6)
                ChipsChoix(listOf("Normale", "Gros", "Très gros"), s.textSize) { vm.updateSettings(s.copy(textSize = it)) }
            }
            SectionTitle("AUTRE")
            BoxCard {
                InfoRow("🎉", "Espace libéré", "Depuis l'installation", fmtBytes(vm.freed))
                TextButton(onClick = { vm.resetFreed() }) { Text("Remettre le compteur à zéro", color = Gris) }
                InfoRow("📖", "Mode d'emploi", "Français / English", onClick = openManual)
                InfoRow("⚙️", "Réglages de stockage Android", "", onClick = {
                    runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                })
            }
            Gap()
            Text("Clean Smart ${BuildConfig.VERSION_NAME} — JM Dargère", color = Gris, fontSize = 12.sp,
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
