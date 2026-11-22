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
import com.jmdargere.cleansmart.data.Provider
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural

/** Les ecrans de l'appli (navigation simple, sans bibliotheque). */
private enum class Ecran { ACCUEIL, CATEGORIE, MAILS, MAILS_SAUVER, OPTIONS, MANUEL }

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

    BackHandler(enabled = ecran != Ecran.ACCUEIL) { ecran = if (ecran == Ecran.MAILS_SAUVER) Ecran.MAILS else Ecran.ACCUEIL }

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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Fond),
                title = {
                    Text(when (ecran) {
                        Ecran.ACCUEIL -> "✨ Clean Smart"
                        Ecran.CATEGORIE -> cat.emoji + " " + cat.title
                        Ecran.MAILS -> "✉️ Mails"
                        Ecran.MAILS_SAUVER -> "💾 Sauver des mails"
                        Ecran.OPTIONS -> "⚙️ Options"
                        Ecran.MANUEL -> "📖 Mode d'emploi"
                    }, fontWeight = FontWeight.ExtraBold)
                },
                navigationIcon = {
                    if (ecran != Ecran.ACCUEIL) TextButton(onClick = {
                        ecran = if (ecran == Ecran.MAILS_SAUVER) Ecran.MAILS else Ecran.ACCUEIL
                    }) { Text("‹ Retour", color = Violet2) }
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
                Ecran.ACCUEIL -> Accueil(vm, ::analyser,
                    openCat = { cat = it; ecran = Ecran.CATEGORIE },
                    openMail = { ecran = Ecran.MAILS },
                    openManual = { ecran = Ecran.MANUEL },
                    chooseFolder = { folderLauncher.launch(null) },
                    ask = { confirm = it })
                Ecran.CATEGORIE -> Categorie(vm, cat)
                Ecran.MAILS -> Mails(vm, ask = { confirm = it }, openSave = { ecran = Ecran.MAILS_SAUVER })
                Ecran.MAILS_SAUVER -> MailsSauver(vm, chooseFolder = { folderLauncher.launch(null) })
                Ecran.OPTIONS -> Options(vm, chooseFolder = { folderLauncher.launch(null) }, openManual = { ecran = Ecran.MANUEL })
                Ecran.MANUEL -> Manual()
            }
        }
    }

    vm.busy?.let { BusyDialog(it) }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

// =====================================================================================
// ACCUEIL : stockage, un gros bouton, et le resultat en quelques cartes
// =====================================================================================

@Composable
private fun Accueil(
    vm: AppViewModel,
    analyser: () -> Unit,
    openCat: (Cat) -> Unit,
    openMail: () -> Unit,
    openManual: () -> Unit,
    chooseFolder: () -> Unit,
    ask: (Confirm) -> Unit,
) {
    val m = vm.media
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp)) {
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
            Gap()
            BigButton(if (m == null) "🔍 Analyser mon téléphone" else "🔄 Analyser à nouveau", onClick = analyser)
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
                val total = Cat.entries.sumOf { c -> vm.removable(c).sumOf { it.size } }
                BoxCard {
                    Text("Espace à récupérer", color = Gris, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(fmtBytes(total), fontSize = 36.sp, fontWeight = FontWeight.Black, color = Violet2,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text("${fmtNum(m.photoCount)} photos · ${fmtNum(m.videoCount)} vidéos analysées", color = Gris, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                SectionTitle("PHOTOS ET VIDÉOS")
                BoxCard {
                    val visibles = Cat.entries.filter { vm.removable(it).isNotEmpty() }
                    if (visibles.isEmpty()) Text("🎉 Rien à nettoyer, tout est propre !", color = Gris)
                    visibles.forEach { c ->
                        val r = vm.removable(c)
                        val sel = r.count { it.key in vm.selected }
                        InfoRow(c.emoji, c.title,
                            plural(r.size, "élément") + if (sel > 0) " · $sel choisi" + (if (sel > 1) "s" else "") else "",
                            fmtBytes(r.sumOf { it.size })) { openCat(c) }
                    }
                }
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
                Text("📖 Mode d'emploi", color = Color.White)
            }
        }
    }
}

// =====================================================================================
// CATEGORIE : la grille de miniatures a cocher
// =====================================================================================

@Composable
private fun Categorie(vm: AppViewModel, cat: Cat) {
    val groups = vm.groups(cat)
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
                    OutlinedButton(onClick = { vm.selectAll(cat, true) }) { Text("Tout cocher", color = Color.White) }
                    RowGap()
                    OutlinedButton(onClick = { vm.selectAll(cat, false) }) { Text("Tout décocher", color = Color.White) }
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
private fun Mails(vm: AppViewModel, ask: (Confirm) -> Unit, openSave: () -> Unit) {
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
                BoxCard {
                    InfoRow("✉️", a.email, a.host)
                    Gap()
                    BigButton(if (vm.mailResult == null) "🔍 Analyser ma boîte mail" else "🔄 Analyser à nouveau") { vm.analyseMail() }
                    Text("Analyse des 3 000 mails les plus récents de la boîte de réception.", color = Gris, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
                }
                BoxCard(onClick = openSave) {
                    InfoRow("💾", "Sauvegarder des mails", "Choisis tes mails : ils sont gardés avec leurs pièces jointes, les PDF dans un dossier à part", onClick = openSave)
                }
            }
            vm.mailResult?.let { r ->
                item {
                    SectionTitle("RÉSULTAT")
                    BoxCard {
                        Text("${fmtNum(r.total)} mails dans la boîte · ${fmtBytes(r.bytes)} analysés", color = Gris, fontSize = 13.sp)
                        Gap()
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
                    }
                    if (r.senders.isNotEmpty()) {
                        SectionTitle("QUI T'ÉCRIT LE PLUS")
                        BoxCard {
                            r.senders.take(10).forEach { s ->
                                InfoRow(if (s.newsletter) "📰" else "👤", s.name.ifEmpty { s.email }, s.email, fmtNum(s.count))
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
                        OutlinedButton(onClick = { vm.selectMailFiles("pdf") }) { Text("📕 Cocher les PDF", color = Color.White) }
                        RowGap()
                        OutlinedButton(onClick = { vm.selectMailFiles("all") }) { Text("Tout cocher", color = Color.White) }
                        RowGap()
                        OutlinedButton(onClick = { vm.selectMailFiles("none") }) { Text("Rien", color = Color.White) }
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
                    "Copie les photos (et les contacts en .vcf) dans le dossier avant chaque nettoyage", s.backupBeforeDelete) {
                    vm.updateSettings(s.copy(backupBeforeDelete = it))
                }
                if (vm.backupReady()) {
                    Gap()
                    BigButton("💾 Sauvegarder toutes mes photos", color = Carte2) { vm.backupEverything() }
                    Text("Seules les nouvelles photos sont copiées à chaque fois.", color = Gris, fontSize = 12.sp)
                }
            }
            SectionTitle("PROTECTION")
            BoxCard {
                OptionSwitch("Protéger mes favoris", "Les photos mises en favori ne sont jamais proposées", s.protectFavorites) {
                    vm.updateSettings(s.copy(protectFavorites = it))
                }
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
