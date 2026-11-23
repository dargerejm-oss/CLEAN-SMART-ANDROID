package com.jmdargere.cleansmart.ui

import com.jmdargere.cleansmart.Tr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.data.EtatZip
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural

/**
 * Onglet « Quitter Google Photos » : UN SEUL tableau de bord.
 * En haut le circuit (Google -> telephone -> disque) et la jauge ; dessous les 5 etapes :
 * celles qui sont faites sont repliees (✅), celle a faire est ouverte avec un seul bouton vert,
 * les suivantes sont grisees. On revient 3 jours plus tard : on sait tout de suite ou on en est.
 * Rien n'est jamais supprime dans Google Photos par l'appli.
 */
@Composable
fun GooglePhotos(vm: AppViewModel, ask: (Confirm) -> Unit) {
    val ctx = LocalContext.current
    var conseils by remember { mutableStateOf(false) }
    var ouverte by rememberSaveable { mutableIntStateOf(0) }   // etape ouverte a la main (0 = celle a faire)

    // la notification de fin de fichier
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        vm.gpRafraichirDisque()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val destLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.gpSetDest(uri)
            ouverte = 0
        }
    }
    var zipFini by remember { mutableStateOf<Uri?>(null) }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val num = vm.google.numero(vm.google.nomDe(uri))
        if (num > 0 && vm.gpEtats[num] == EtatZip.OK) {
            ask(Confirm(Tr.t("Fichier %1 déjà fait", "%03d".format(num)),
                Tr.t("Ce fichier est déjà copié et vérifié. Le refaire ne crée aucun doublon, mais c'est inutile.\n\nTu voulais sans doute prendre le suivant (%1).", "%03d".format(num + 1)),
                Tr.t("Le refaire quand même"), danger = false) { vm.gpTraiter(uri) { zipFini = it } })
        } else vm.gpTraiter(uri) { zipFini = it }
    }
    fun choisirZip() {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val niveau = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charge = bm?.isCharging ?: true
        val lancer = { zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        if (niveau < 30 && !charge) ask(Confirm(Tr.t("Batterie faible (%1 %)", niveau),
            Tr.t("Branche le chargeur : un fichier peut prendre 10 à 30 minutes.\n\nSi le téléphone s'éteint, rien n'est perdu : il suffira de relancer le même fichier."),
            Tr.t("Continuer quand même"), danger = false) { lancer() })
        else lancer()
    }
    fun ouvrir(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    // Google Photos ou iCloud Photos : memes etapes, textes et liens differents
    val g = vm.gpSource == com.jmdargere.cleansmart.data.SourceExport.GOOGLE
    val qui = if (g) Tr.t("Google") else Tr.t("Apple")

    // ----- ou en est-on ?
    val faites = setOfNotNull(
        1.takeIf { vm.gpTaille > 0 },
        2.takeIf { vm.gpDestOk },
        3.takeIf { vm.gpDemande },
        4.takeIf { vm.gpTermine },
    )
    val maintenant = (1..5).first { it !in faites }
    val affichee = if (ouverte != 0) ouverte else maintenant

    LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp)) {
        item {
            Circuit(phase = when { maintenant <= 3 -> 0; maintenant == 4 -> 1; else -> 2 }, origine = if (g) Tr.t("Google") else "iCloud")
            Gap(8)
            // la jauge
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Carte),
                elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(vm.gpAvancement * 100).toInt()} %", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Vert)
                        Spacer(Modifier.width(10.dp))
                        Text(Tr.t("de ta photothèque est à l'abri"), color = Gris, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    LinearProgressIndicator(progress = { vm.gpAvancement }, color = Vert, trackColor = Carte2,
                        modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(99.dp)))
                    Gap(6)
                    val t = vm.gpTotal
                    Text("${fmtBytes(t.octets)}${if (vm.gpTaille > 0) Tr.t(" sur environ ${fmtBytes(t.octets)} Go", vm.gpTaille) else ""} · " +
                            Tr.t("%1 photos · %2 vidéos · %3 doublons et rafales écartés", fmtNum(t.photos), fmtNum(t.videos), fmtNum(t.doublons + t.rafales)),
                        color = Gris, fontSize = 12.sp)
                }
            }
            Gap(12)

            // ---------------------------------------------------------------- 1. la place
            EtapeGP(1, "📏", Tr.t("Combien de place faut-il ?"),
                if (vm.gpTaille > 0) Tr.t("%1 Go · prévois %2", vm.gpTaille, fmtBytes(vm.google.placeNecessaire())) else Tr.t("Regarder la taille de %1", vm.gpSource.nom),
                faites, maintenant, affichee, { ouverte = if (affichee == 1) -1 else 1 }) {
                if (g) {
                    Txt(Tr.t("Ouvre ta page Google et regarde la place utilisée par Google Photos. Écris-la ici (en Go)."))
                    BigButton(Tr.t("Voir ma place Google"), color = Bleu) { ouvrir("https://one.google.com/storage") }
                } else {
                    Txt(Tr.t("Sur l'iPhone : Réglages › ton nom › iCloud : la place utilisée par « Photos » s'affiche. Ou regarde sur icloud.com. Écris-la ici (en Go)."))
                    BigButton(Tr.t("Voir ma place iCloud"), color = Bleu) { ouvrir("https://www.icloud.com/settings/") }
                }
                Gap(8)
                var saisie by rememberSaveable(vm.gpSource) { mutableStateOf(if (vm.gpTaille > 0) vm.gpTaille.toString() else "") }
                OutlinedTextField(saisie, { saisie = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(Tr.t("Taille de %1 (Go)", vm.gpSource.nom)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                saisie.toIntOrNull()?.takeIf { it > 0 }?.let { go ->
                    Gap(6)
                    Encadre(Tr.t("Prévois au moins %1 sur la clé ou le disque (10 % de marge). ", fmtBytes((go * 1.1 * (1L shl 30)).toLong())) +
                            Tr.t("Sur le téléphone, 2 à 3 Go libres suffisent : un seul fichier à la fois. Libre maintenant : %1.", fmtBytes(vm.google.libreTelephone())), Bleu)
                    BoutonVert(Tr.t("C'est noté")) { vm.gpSetTaille(go); ouverte = 0 }
                }
            }

            // ---------------------------------------------------------------- 2. le disque
            EtapeGP(2, "💾", Tr.t("Brancher la clé ou le disque"),
                if (vm.gpDestOk) "${vm.gpDest} · ${vm.google.libreDestination()?.let { fmtBytes(it) + " libres" } ?: ""}"
                else Tr.t("Clé USB ou disque dur, avec un adaptateur USB-C"),
                faites, maintenant, affichee, { ouverte = if (affichee == 2) -1 else 2 }) {
                Txt(Tr.t("Branche ta clé ou ton disque sur le téléphone, puis choisis (ou crée) un dossier dessus."))
                val disques = remember(vm.gpDest, affichee) { vm.google.disques() }
                disques.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Carte2, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (d.amovible) "💾" else "📱", fontSize = 22.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.nom, fontWeight = FontWeight.Bold, color = Texte)
                            Text(Tr.t("%1 libres sur %2", fmtBytes(d.libre), fmtBytes(d.total)), color = Gris, fontSize = 12.sp)
                        }
                    }
                }
                if (disques.none { it.amovible }) Encadre(Tr.t("Aucune clé ni disque détecté. Branche-le (adaptateur USB-C), puis rouvre cette étape."), Orange)
                val libre = vm.google.libreDestination()
                val besoin = vm.google.placeNecessaire()
                if (vm.gpDestOk && libre != null && besoin > 0) {
                    if (libre >= besoin) Encadre(Tr.t("✅ Assez de place sur « %1 ».", vm.gpDest), Vert)
                    else Encadre(Tr.t("⚠️ Il faut environ %1, il reste %2. Tu peux commencer : quand il sera plein, tu brancheras un autre disque et on continuera sans doublon.", fmtBytes(besoin), fmtBytes(libre)), Orange)
                }
                BoutonVert(if (vm.gpDestOk) Tr.t("Changer de dossier") else Tr.t("Choisir le dossier sur le disque")) { destLauncher.launch(null) }
            }

            // ---------------------------------------------------------------- 3. demander l'export
            EtapeGP(3, "📨", Tr.t("Demander la copie à %1", qui),
                if (vm.gpDemande) Tr.t("Demandée · attends le mail de %1", qui) else if (g) Tr.t("Sur Google Takeout, en 1 minute") else Tr.t("Sur privacy.apple.com, en 2 minutes"),
                faites, maintenant, affichee, { ouverte = if (affichee == 3) -1 else 3 }) {
                Txt(Tr.t("%1 prépare une copie de toutes tes photos. Fais exactement comme sur ce modèle :", qui))
                if (g) {
                    TakeoutModele()
                    BigButton(Tr.t("Ouvrir Google Takeout"), color = Orange) { ouvrir("https://takeout.google.com/") }
                    Encadre(Tr.t("Google envoie un mail quand c'est prêt : en quelques heures ou quelques jours. Tu peux fermer l'appli en attendant."), Bleu)
                } else {
                    AppleModele()
                    BigButton(Tr.t("Ouvrir privacy.apple.com"), color = Orange) { ouvrir("https://privacy.apple.com/") }
                    Encadre(Tr.t("Apple vérifie la demande puis envoie un mail, en général sous 3 à 7 jours. Les fichiers se téléchargent ensuite sur privacy.apple.com (connecte-toi depuis le téléphone)."), Bleu)
                }
                BoutonVert(Tr.t("J'ai demandé la copie")) { vm.gpSetDemande(true); ouverte = 0 }
            }

            // ---------------------------------------------------------------- 4. les fichiers un par un
            EtapeGP(4, "⚙️", Tr.t("Copier les fichiers un par un"),
                if (vm.gpTermine) "${plural(vm.gpEtats.count { it.value == EtatZip.OK }, Tr.t("fichier copié et vérifié"), Tr.t("fichiers copiés et vérifiés"))}"
                else if (vm.gpNb > 0) Tr.t("%1 sur %2 fichiers faits", vm.gpEtats.count { it.value == EtatZip.OK }, vm.gpNb) else Tr.t("Quand le mail de %1 est arrivé", qui),
                faites, maintenant, affichee, { ouverte = if (affichee == 4) -1 else 4 }) {
                // combien de fichiers ?
                var nb by rememberSaveable(vm.gpSource) { mutableStateOf(if (vm.gpNb > 0) vm.gpNb.toString() else "") }
                Txt(if (g) Tr.t("Dans le mail de Google, compte les boutons « Télécharger » : c'est le nombre de fichiers.")
                    else Tr.t("Sur privacy.apple.com, les fichiers s'appellent « iCloud Photos Part 1 of 12 » : le dernier nombre (ici 12) est le nombre de fichiers. Clean Smart le lit aussi tout seul."))
                OutlinedTextField(nb, { nb = it.filter { c -> c.isDigit() }.take(3); nb.toIntOrNull()?.let(vm::gpSetNb) },
                    label = { Text(Tr.t("Nombre de fichiers dans le mail")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Gap(8)
                // la liste des fichiers
                if (vm.gpNb > 0) {
                    val prochain = (1..vm.gpNb).firstOrNull { vm.gpEtats[it] != EtatZip.OK }
                    Column(Modifier.fillMaxWidth().background(Carte2, RoundedCornerShape(14.dp)).padding(10.dp)) {
                        for (i in 1..vm.gpNb) {
                            val e = vm.gpEtats[i]
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("%03d".format(i), fontWeight = FontWeight.Black, color = Texte, modifier = Modifier.width(44.dp))
                                Text(when {
                                    e == EtatZip.OK -> Tr.t("✅ copié et vérifié")
                                    e == EtatZip.A_REPRENDRE -> Tr.t("⚠️ à relancer")
                                    i == prochain -> Tr.t("👉 à télécharger puis traiter")
                                    else -> Tr.t("⏳ plus tard")
                                }, color = when { e == EtatZip.OK -> Vert; e == EtatZip.A_REPRENDRE -> Rouge; i == prochain -> Bleu; else -> Gris },
                                    fontWeight = if (i == prochain) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                            }
                        }
                    }
                    Gap(8)
                    if (prochain != null) {
                        Encadre(if (g) Tr.t("👉 Maintenant : dans le mail de Google, télécharge le fichier %1 (en Wi-Fi). Puis touche le bouton vert et choisis-le dans Téléchargements.", "%03d".format(prochain))
                            else Tr.t("👉 Maintenant : sur privacy.apple.com, télécharge « Part %1 » (en Wi-Fi). Puis touche le bouton vert et choisis-le dans Téléchargements.", prochain), Bleu)
                    }
                }
                // le dernier resultat
                vm.gpDernier?.let { (nom, s) ->
                    Encadre(
                        (if (s.sansProbleme) Tr.t("✅ %1 : terminé et vérifié.\n", nom) else Tr.t("⚠️ %1 : à relancer.\n", nom)) +
                                Tr.t("%1, %2, %3 copiés · %4 et %5 écartés.", plural(s.photos, "photo"), plural(s.videos, Tr.t("vidéo")), plural(s.mails, "mail"), plural(s.doublons, "doublon"), plural(s.rafales, "rafale")) +
                                when {
                                    s.plein -> Tr.t("\n\nLe disque ou le téléphone est plein : libère de la place (ou change de disque à l'étape 2), puis relance CE fichier. Rien ne sera recopié en double.")
                                    s.abime -> Tr.t("\n\nLe fichier est abîmé ou incomplet : retélécharge-le depuis le mail de Google, puis relance-le. Ce qui est déjà copié est gardé.")
                                    s.erreurs > 0 -> Tr.t("\n\n%1 pas pu être copié(s) : relance ce même fichier.", plural(s.erreurs, Tr.t("fichier n'a"), Tr.t("fichiers n'ont")))
                                    else -> ""
                                }, if (s.sansProbleme) Vert else Rouge)
                    val z = zipFini
                    if (s.sansProbleme && z != null) {
                        OutlinedButton(onClick = {
                            ask(Confirm(Tr.t("Supprimer ce fichier du téléphone ?"),
                                Tr.t("Tout son contenu est copié et vérifié sur le disque. Le supprimer fait de la place pour le suivant.\n\nTes photos restent aussi dans Google Photos."),
                                Tr.t("Supprimer")) { vm.gpSupprimerZip(z); zipFini = null })
                        }, modifier = Modifier.fillMaxWidth()) { Text(Tr.t("🗑️ Supprimer ce ZIP du téléphone (conseillé)"), color = Rouge) }
                    }
                }
                BoutonVert(Tr.t("📂 Traiter un fichier ZIP")) { choisirZip() }
                val tousFaits = vm.gpNb > 0 && (1..vm.gpNb).all { vm.gpEtats[it] == EtatZip.OK }
                if (tousFaits) BigButton(Tr.t("🎉 Tous les fichiers sont faits"), color = Bleu) { vm.gpSetTermine(true); ouverte = 0 }
                else if (vm.gpTotal.zips > 0) TextButton(onClick = {
                    ask(Confirm(Tr.t("Tous les fichiers sont traités ?"),
                        Tr.t("Vérifie dans le mail de Google que chaque fichier (001, 002, 003…) a bien été traité."), Tr.t("Oui, c'est fini"), danger = false) {
                        vm.gpSetTermine(true); ouverte = 0
                    })
                }, modifier = Modifier.fillMaxWidth()) { Text(Tr.t("J'ai traité tous les fichiers"), color = Gris) }
            }

            // ---------------------------------------------------------------- 5. bilan et liberer
            EtapeGP(5, "🎉", Tr.t("Vérifier, puis libérer %1 (si tu veux)", if (g) Tr.t("Google") else "iCloud"),
                Tr.t("Le bilan, et comment libérer ton compte %1", if (g) Tr.t("Google") else "iCloud"),
                faites, maintenant, affichee, { ouverte = if (affichee == 5) -1 else 5 }) {
                val t = vm.gpTotal
                Encadre(Tr.t("📸 %1 photos et %2 vidéos copiées et vérifiées, rangées par année\n", fmtNum(t.photos), fmtNum(t.videos)) +
                        Tr.t("🧬 %1 doublons et 🎞️ %2 photos de rafale écartés\n", fmtNum(t.doublons), fmtNum(t.rafales)) +
                        (if (t.mails > 0) Tr.t("✉️ %1 mails (dossier « Mails Gmail », ouvre index.html)\n", fmtNum(t.mails)) else "") +
                        Tr.t("💾 %1 sur « %2 »", fmtBytes(t.octets), vm.gpDest), Vert)
                if (t.erreurs > 0) Encadre(Tr.t("⚠️ %1 en erreur : relance les ZIP marqués « à relancer » avant d'aller plus loin.", plural(t.erreurs, "fichier")), Rouge)
                Txt(Tr.t("Libérer %1 est facultatif. %2 n'autorise aucune appli à effacer tes photos : c'est toi qui le fais, quand tu es sûr.", vm.gpSource.nom, qui))
                var verifie by rememberSaveable { mutableStateOf(false) }
                var copie2 by rememberSaveable { mutableStateOf(false) }
                Coche(verifie, Tr.t("J'ai ouvert le disque sur un PC et je vois bien mes photos, année par année.")) { verifie = it }
                Coche(copie2, Tr.t("Conseillé : j'ai fait une 2e copie (autre disque ou PC).")) { copie2 = it }
                if (verifie) {
                    if (g) {
                        Txt(Tr.t("Dans Google Photos : appuie longuement sur une photo, coche des journées entières, puis « Supprimer ». La corbeille de Google garde tout 60 jours."))
                        BigButton(Tr.t("Ouvrir Google Photos"), color = Rouge) { ouvrir("https://photos.google.com/") }
                    } else {
                        Txt(Tr.t("Sur l'iPhone ou sur icloud.com/photos : sélectionne des photos puis « Supprimer ». L'album « Supprimés récemment » les garde 30 jours."))
                        BigButton(Tr.t("Ouvrir iCloud Photos"), color = Rouge) { ouvrir("https://www.icloud.com/photos/") }
                    }
                } else Encadre(Tr.t("🛡️ On ne libère jamais %1 avant d'avoir vérifié la copie : coche d'abord la première case.", if (g) Tr.t("Google") else "iCloud"), Vert)
                TextButton(onClick = {
                    ask(Confirm(Tr.t("Recommencer depuis le début ?"),
                        Tr.t("Pour un autre compte ou un autre disque. Les fichiers déjà copiés sur le disque ne sont pas touchés."), Tr.t("Recommencer")) { vm.gpRecommencer() })
                }, modifier = Modifier.fillMaxWidth()) { Text(Tr.t("Recommencer avec un autre compte"), color = Gris) }
            }

            Gap(10)
            OutlinedButton(onClick = { conseils = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(Tr.t("💡 Si ça bloque : tous les conseils"), color = Texte, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (conseils) AlertDialog(
        onDismissRequest = { conseils = false },
        containerColor = Carte,
        title = { Text(Tr.t("💡 Si ça bloque"), fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(Modifier.height(440.dp)) {
                vm.google.conseils.forEach { (ico, txt) ->
                    item { Row(Modifier.padding(vertical = 6.dp)) { Text(ico, fontSize = 20.sp); Spacer(Modifier.width(10.dp)); Text(txt, color = Texte, fontSize = 14.sp) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { conseils = false }) { Text(Tr.t("Compris")) } },
    )

    vm.gpProbleme?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.gpProbleme = null },
            containerColor = Carte,
            title = { Text(Tr.t("⚠️ On ne commence pas"), fontWeight = FontWeight.Bold) },
            text = { Text(p, color = Texte) },
            confirmButton = { TextButton(onClick = { vm.gpProbleme = null }) { Text(Tr.t("Compris")) } },
            dismissButton = { TextButton(onClick = { vm.gpProbleme = null; conseils = true }) { Text(Tr.t("Voir les conseils")) } },
        )
    }
}

// ============================================================================== morceaux d'interface

/** Le circuit : ☁️ Google -> 📱 telephone -> 💾 disque, avec un point qui avance sur le trajet en cours. */
@Composable
private fun Circuit(phase: Int, origine: String) {
    val anim = rememberInfiniteTransition(label = "circuit")
    val t by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "point")
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Bleu), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 18.dp)) {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val y = size.height * 0.42f
                val x1 = size.width * 0.13f; val x2 = size.width * 0.5f; val x3 = size.width * 0.87f
                val pointille = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                val actif = Color.White; val inactif = Color(0x66FFFFFF)
                drawLine(if (phase >= 1) actif else inactif, Offset(x1 + 40, y), Offset(x2 - 40, y), 6f, pathEffect = if (phase == 0) pointille else null)
                drawLine(if (phase >= 2) actif else inactif, Offset(x2 + 40, y), Offset(x3 - 40, y), 6f, pathEffect = if (phase == 1) pointille else null)
                // le point qui avance sur le trajet en cours
                val (a, b) = if (phase == 0) (x1 + 40) to (x2 - 40) else (x2 + 40) to (x3 - 40)
                if (phase < 2) drawCircle(Color(0xFFFFD54F), 9f, Offset(a + (b - a) * t, y))
            }
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Lieu("☁️", origine, phase == 0)
                Lieu("📱", Tr.t("Téléphone"), phase == 1)
                Lieu("💾", Tr.t("Disque"), phase == 2)
            }
        }
    }
}

@Composable
private fun Lieu(e: String, nom: String, actif: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (actif) 62.dp else 52.dp).background(Color.White, CircleShape)
            .then(if (actif) Modifier.border(4.dp, Color(0xFFFFD54F), CircleShape) else Modifier),
            contentAlignment = Alignment.Center) { Text(e, fontSize = if (actif) 30.sp else 24.sp) }
        Gap(4)
        Text(nom, color = Color.White, fontWeight = if (actif) FontWeight.Black else FontWeight.Normal, fontSize = 13.sp)
    }
}

/** Une etape du tableau de bord : repliee si faite, ouverte si c'est la prochaine, grisee si plus tard. */
@Composable
private fun EtapeGP(
    n: Int, emoji: String, titre: String, resume: String,
    faites: Set<Int>, maintenant: Int, affichee: Int, onToggle: () -> Unit,
    contenu: @Composable () -> Unit,
) {
    val faite = n in faites
    val estMaintenant = n == maintenant
    val plusTard = !faite && !estMaintenant
    val ouverte = affichee == n
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (plusTard) Color(0xFFF0F2F7) else Carte),
        elevation = CardDefaults.cardElevation(if (estMaintenant) 6.dp else 1.dp),
        border = if (estMaintenant) BorderStroke(3.dp, Vert) else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !plusTard, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(when { faite -> Vert; estMaintenant -> Bleu; else -> Color(0xFFB8BECC) }, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(if (faite) "✓" else if (plusTard) "🔒" else "${n}", color = Color.White, fontSize = if (plusTard) 18.sp else 22.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${emoji}  ${titre}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                        color = when { faite -> Vert; plusTard -> Gris; else -> Texte })
                    Text(when { estMaintenant && !ouverte -> Tr.t("👉 À faire maintenant"); else -> resume }, color = Gris, fontSize = 13.sp)
                }
                if (estMaintenant) Text(Tr.t("MAINTENANT"), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.background(Vert, RoundedCornerShape(8.dp)).padding(8.dp, 3.dp))
                else if (faite) Text(if (ouverte) "▲" else Tr.t("Modifier ▼"), color = Gris, fontSize = 12.sp)
            }
            if (ouverte && !plusTard) {
                Gap(10)
                contenu()
            }
        }
    }
}

/** Modele de la page Google Takeout : ce qu'il faut cocher, avec des reperes numerotes. */
@Composable
private fun TakeoutModele() {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFDADCE0)), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(Tr.t("Google Takeout"), fontWeight = FontWeight.Bold, color = Color(0xFF5F6368), fontSize = 15.sp)
            Gap(6)
            LigneModele(1, Tr.t("Tout désélectionner"), false, lien = true)
            LigneModele(2, Tr.t("Google Photos"), true)
            LigneModele(0, Tr.t("Mail (facultatif, pour tes mails)"), true, faible = true)
            LigneModele(0, Tr.t("Drive, YouTube, Maps…"), false, faible = true)
            Gap(4)
            Text(Tr.t("puis « Étape suivante » :"), color = Color(0xFF5F6368), fontSize = 12.sp)
            LigneModele(3, Tr.t("Envoyer le lien par e-mail · Exporter une fois"), true)
            LigneModele(4, Tr.t("Type : .zip   ·   Taille : 2 Go"), true, important = true)
            LigneModele(5, Tr.t("Créer une exportation"), false, lien = true)
        }
    }
}

/** Modele de la demande Apple (privacy.apple.com) : ce qu'il faut cocher. */
@Composable
private fun AppleModele() {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFDADCE0)), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(Tr.t("Apple · Données et confidentialité"), fontWeight = FontWeight.Bold, color = Color(0xFF1D1D1F), fontSize = 15.sp)
            Gap(6)
            LigneModele(1, Tr.t("Se connecter avec ton identifiant Apple"), false, lien = true)
            LigneModele(2, Tr.t("Demander une copie de tes données"), false, lien = true)
            LigneModele(3, Tr.t("Photos iCloud"), true)
            LigneModele(0, Tr.t("Les autres cases : inutile"), false, faible = true)
            LigneModele(4, Tr.t("Taille maximale des fichiers : 1 Go ou 2 Go"), true, important = true)
            LigneModele(5, Tr.t("Terminer la demande"), false, lien = true)
        }
    }
}

@Composable
private fun LigneModele(n: Int, txt: String, coche: Boolean, lien: Boolean = false, faible: Boolean = false, important: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
        .then(if (important) Modifier.background(Color(0xFFFFF3E0), RoundedCornerShape(8.dp)) else Modifier).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(if (n > 0) Rouge else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            if (n > 0) Text("${n}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(8.dp))
        if (!lien) {
            Box(Modifier.size(18.dp).background(if (coche) Color(0xFF1A73E8) else Color.White, RoundedCornerShape(3.dp))
                .border(2.dp, if (coche) Color(0xFF1A73E8) else Color(0xFF9AA0A6), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center) { if (coche) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(8.dp))
        }
        Text(txt, color = if (lien) Color(0xFF1A73E8) else if (faible) Color(0xFF9AA0A6) else Color(0xFF202124),
            fontWeight = if (lien || important) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
    }
}

@Composable
private fun Txt(t: String) = Text(t, color = Texte, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(vertical = 6.dp))

@Composable
private fun Encadre(t: String, c: Color) {
    Text(t, color = Texte, fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(c.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.5.dp, c.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp))
}

@Composable
private fun BoutonVert(t: String, onClick: () -> Unit) {
    Gap(6)
    BigButton("${t}  ›", color = Vert, onClick = onClick)
}

@Composable
private fun Coche(v: Boolean, t: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!v) }) {
        Checkbox(v, onChange, colors = CheckboxDefaults.colors(checkedColor = Vert))
        Text(t, fontSize = 14.sp, color = Texte)
    }
}
