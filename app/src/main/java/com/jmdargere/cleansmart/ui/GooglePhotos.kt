package com.jmdargere.cleansmart.ui

import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural

private const val NB_ETAPES = 7

/**
 * Onglet « Quitter Google Photos » : une procedure pas a pas, un ecran par etape,
 * avec confirmations. Rien n'est jamais supprime dans Google Photos par l'appli.
 */
@Composable
fun GooglePhotos(vm: AppViewModel, ask: (Confirm) -> Unit) {
    val ctx = LocalContext.current
    val e = vm.gpEtape
    var conseils by remember { mutableStateOf(false) }

    // choix du dossier sur la cle / le disque
    val destLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.gpSetDest(uri)
        }
    }
    // choix d'un fichier ZIP de l'export Google
    var zipFini by remember { mutableStateOf<Uri?>(null) }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.gpTraiter(uri) { zipFini = it }
    }
    fun choisirZip() {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val niveau = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charge = bm?.isCharging ?: true
        val lancer = { zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        if (niveau < 30 && !charge) ask(Confirm("Batterie faible ($niveau %)",
            "Branche le chargeur : un fichier peut prendre 10 à 30 minutes.\n\nSi le téléphone s'éteint, rien n'est perdu : il suffira de relancer le même fichier.",
            "Continuer quand même", danger = false) { lancer() })
        else lancer()
    }
    fun ouvrir(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)) {
        item {
            // les etapes, en points
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center) {
                for (i in 1..NB_ETAPES) {
                    Box(Modifier.padding(4.dp).size(if (i == e) 14.dp else 10.dp)
                        .background(if (i < e) Vert else if (i == e) Bleu else Carte2, CircleShape))
                }
            }
            Text("Étape $e sur $NB_ETAPES", color = Gris, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Gap()

            when (e) {
                // ------------------------------------------------------------ 1. bienvenue
                1 -> {
                    Bandeau("📦", "Quitter Google Photos", Rouge)
                    Para("Tu vas copier TOUTES tes photos Google Photos sur une clé USB ou un disque dur, rangées par année.")
                    Puce("🧬", "Les doublons ne sont pas copiés.")
                    Puce("🎞️", "Dans les rafales, seule la meilleure photo est gardée.")
                    Puce("✅", "Chaque photo copiée est relue pour vérifier qu'elle est intacte.")
                    Puce("✉️", "Tes mails Gmail aussi, si tu le veux.")
                    Puce("🛡️", "Rien n'est effacé de Google Photos par l'appli. Tu décideras tout à la fin.")
                    Para("Il faut : une clé ou un disque, un adaptateur USB-C, le Wi-Fi, et un peu de patience (Google prépare l'export en quelques heures ou quelques jours).")
                    Suivant("C'est parti") { vm.gpAller(2) }
                }
                // ------------------------------------------------------------ 2. combien de place
                2 -> {
                    Bandeau("📏", "Combien de place faut-il ?", Bleu)
                    Para("Regarde la place utilisée par Google Photos dans ton compte Google.")
                    BigButton("Voir ma place Google", color = Bleu) { ouvrir("https://one.google.com/storage") }
                    Gap()
                    var saisie by rememberSaveable { mutableStateOf(if (vm.gpTaille > 0) vm.gpTaille.toString() else "") }
                    OutlinedTextField(saisie, { saisie = it.filter { c -> c.isDigit() }.take(5); saisie.toIntOrNull()?.let(vm::gpSetTaille) },
                        label = { Text("Taille de Google Photos (en Go)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    if (vm.gpTaille > 0) {
                        Gap()
                        BoxCard {
                            Text("Prévois au moins", color = Gris, fontSize = 14.sp)
                            Text(fmtBytes(vm.google.placeNecessaire()), fontSize = 32.sp, fontWeight = FontWeight.Black, color = Bleu)
                            Text("sur la clé ou le disque (avec 10 % de marge). Les doublons et rafales écartés feront gagner de la place.",
                                color = Gris, fontSize = 13.sp)
                            Gap(6)
                            Text("📱 Sur le téléphone : seulement 2 à 3 Go libres suffisent (un fichier à la fois). Libre maintenant : ${fmtBytes(vm.google.libreTelephone())}",
                                color = if (vm.google.libreTelephone() < 3L * (1L shl 30)) Orange else Vert, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Suivant("Suivant") { vm.gpAller(3) }
                    Precedent { vm.gpAller(1) }
                }
                // ------------------------------------------------------------ 3. brancher le disque
                3 -> {
                    Bandeau("💾", "Brancher la clé ou le disque", Pourpre)
                    Para("Branche ta clé USB ou ton disque dur sur le téléphone (avec un adaptateur USB-C si besoin), puis choisis un dossier dessus.")
                    val disques = remember(vm.gpDest) { vm.google.disques() }
                    BoxCard {
                        Text("Disques branchés", fontWeight = FontWeight.Bold)
                        disques.forEach { d ->
                            InfoRow(if (d.amovible) "💾" else "📱", d.nom, "${fmtBytes(d.libre)} libres sur ${fmtBytes(d.total)}")
                        }
                        if (disques.none { it.amovible }) Text("Aucune clé ni disque détecté pour l'instant.", color = Orange, fontSize = 13.sp)
                    }
                    BigButton(if (vm.gpDest == "Aucun dossier choisi") "📁 Choisir le dossier sur le disque" else "📁 Changer de dossier",
                        color = Bleu) { destLauncher.launch(null) }
                    if (vm.gpDest != "Aucun dossier choisi") {
                        val libre = vm.google.libreDestination()
                        val besoin = vm.google.placeNecessaire()
                        Gap()
                        BoxCard {
                            InfoRow("📁", vm.gpDest, if (libre != null) "${fmtBytes(libre)} libres" else "Place libre inconnue")
                            if (libre != null && besoin > 0) {
                                if (libre >= besoin) Text("✅ Assez de place (il faut environ ${fmtBytes(besoin)}).", color = Vert, fontWeight = FontWeight.Bold)
                                else Text("⚠️ Pas assez de place : il faut environ ${fmtBytes(besoin)}. Prends un disque plus grand, ou continue et change de disque en cours de route.",
                                    color = Rouge, fontWeight = FontWeight.Bold)
                            }
                        }
                        Suivant("Suivant") { vm.gpAller(4) }
                    }
                    Precedent { vm.gpAller(2) }
                }
                // ------------------------------------------------------------ 4. demander l'export a Google
                4 -> {
                    Bandeau("📨", "Demander l'export à Google", Orange)
                    Para("Google prépare une copie de toutes tes photos. Sur la page qui s'ouvre :")
                    Etape(1, "Touche « Tout désélectionner ».")
                    Etape(2, "Coche « Google Photos » (et « Mail » si tu veux aussi tes mails).")
                    Etape(3, "« Étape suivante » : Envoyer le lien par e-mail, Exporter une fois.")
                    Etape(4, "Type de fichier : .zip — Taille : 2 Go (TRÈS important : un petit fichier à la fois).")
                    Etape(5, "« Créer une exportation ». Google t'enverra un mail quand c'est prêt.")
                    BigButton("Ouvrir Google Takeout", color = Orange) { ouvrir("https://takeout.google.com/") }
                    Suivant("J'ai demandé l'export") { vm.gpAller(5) }
                    Precedent { vm.gpAller(3) }
                }
                // ------------------------------------------------------------ 5. traiter les fichiers un par un
                5 -> {
                    Bandeau("⚙️", "Copier les fichiers un par un", Vert)
                    Para("Quand le mail de Google arrive :")
                    Etape(1, "Télécharge UN seul fichier ZIP (le 1er) sur le téléphone, en Wi-Fi.")
                    Etape(2, "Touche « Traiter un fichier » et choisis-le dans Téléchargements.")
                    Etape(3, "Quand c'est TERMINÉ, supprime-le du téléphone, puis passe au suivant.")
                    val t = vm.gpTotal
                    BoxCard {
                        Text("Déjà fait", fontWeight = FontWeight.Bold)
                        Text("${plural(t.zips, "fichier traité", "fichiers traités")} · ${plural(t.photos, "photo")} · ${plural(t.videos, "vidéo")}",
                            color = Texte, fontSize = 15.sp)
                        Text("${plural(t.doublons, "doublon écarté", "doublons écartés")} · ${plural(t.rafales, "photo de rafale écartée", "photos de rafale écartées")} · ${fmtBytes(t.octets)} copiés",
                            color = Gris, fontSize = 13.sp)
                    }
                    vm.gpDernier?.let { (nom, s) ->
                        BoxCard {
                            Text(if (s.sansProbleme) "✅ $nom : terminé et vérifié" else "⚠️ $nom : à reprendre",
                                fontWeight = FontWeight.Bold, color = if (s.sansProbleme) Vert else Rouge)
                            Text("${plural(s.photos, "photo")}, ${plural(s.videos, "vidéo")}, ${plural(s.mails, "mail")} copiés · " +
                                    "${plural(s.doublons, "doublon")} et ${plural(s.rafales, "rafale")} écartés", color = Gris, fontSize = 13.sp)
                            when {
                                s.plein -> Text("Le disque ou le téléphone est plein : libère de la place (ou change de disque à l'étape 3), puis relance CE fichier. Rien ne sera recopié en double.",
                                    color = Rouge, fontSize = 13.sp)
                                s.abime -> Text("Le fichier est abîmé ou incomplet : retélécharge-le depuis le mail de Google, puis relance-le. Ce qui est déjà copié est gardé.",
                                    color = Rouge, fontSize = 13.sp)
                                s.erreurs > 0 -> Text("${plural(s.erreurs, "fichier n'a", "fichiers n'ont")} pas pu être copié(s) correctement : relance ce même fichier pour réessayer.",
                                    color = Rouge, fontSize = 13.sp)
                            }
                            // on ne propose de supprimer le ZIP que si TOUT est copie et verifie
                            val z = zipFini
                            if (s.sansProbleme && z != null) {
                                Gap(6)
                                OutlinedButton(onClick = {
                                    ask(Confirm("Supprimer ce fichier du téléphone ?",
                                        "Tout son contenu est copié et vérifié sur le disque. Le supprimer fait de la place pour le suivant.\n\nTes photos restent aussi dans Google Photos.",
                                        "Supprimer") { vm.gpSupprimerZip(z); zipFini = null })
                                }) { Text("🗑️ Supprimer ce ZIP du téléphone", color = Rouge) }
                            }
                        }
                    }
                    BigButton("📂 Traiter un fichier ZIP", color = Vert) { choisirZip() }
                    if (vm.gpTotal.zips > 0) {
                        Suivant("J'ai traité TOUS les fichiers") {
                            ask(Confirm("Tous les fichiers sont traités ?",
                                "Vérifie dans le mail de Google que tu as bien traité chaque fichier (001, 002, 003…).", "Oui, c'est fini", danger = false) { vm.gpAller(6) })
                        }
                    }
                    Precedent { vm.gpAller(4) }
                }
                // ------------------------------------------------------------ 6. rapport final
                6 -> {
                    val t = vm.gpTotal
                    Bandeau("🎉", "Tes photos sont à l'abri", Vert)
                    BoxCard {
                        InfoRow("📸", "${fmtNum(t.photos)} photos et ${fmtNum(t.videos)} vidéos", "copiées et vérifiées, rangées par année")
                        InfoRow("🧬", "${fmtNum(t.doublons)} doublons", "pas recopiés")
                        InfoRow("🎞️", "${fmtNum(t.rafales)} photos de rafale", "écartées (la meilleure est gardée)")
                        if (t.mails > 0) InfoRow("✉️", "${fmtNum(t.mails)} mails", "rangés par année, PDF réunis, index.html")
                        InfoRow("💾", fmtBytes(t.octets), "sur ${vm.gpDest}")
                        if (t.erreurs > 0) Text("⚠️ ${plural(t.erreurs, "fichier")} en erreur : relance les ZIP concernés avant d'aller plus loin.",
                            color = Rouge, fontWeight = FontWeight.Bold)
                    }
                    Para("Sur le PC, ouvre le disque : dossier « Google Photos », un dossier par année. Les mails sont dans « Mails Gmail » (ouvre index.html).")
                    Suivant("Suivant") { vm.gpAller(7) }
                    Precedent { vm.gpAller(5) }
                }
                // ------------------------------------------------------------ 7. liberer Google Photos (optionnel)
                else -> {
                    Bandeau("☁️", "Libérer Google Photos (si tu veux)", Rouge)
                    Para("C'est facultatif. Google n'autorise aucune appli à effacer tes photos : si tu veux libérer ton compte, c'est toi qui le fais, dans Google Photos.")
                    var verifie by rememberSaveable { mutableStateOf(false) }
                    var copie2 by rememberSaveable { mutableStateOf(false) }
                    BoxCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(verifie, { verifie = it }, colors = CheckboxDefaults.colors(checkedColor = Vert))
                            Text("J'ai ouvert le disque sur un PC et je vois bien mes photos.", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(copie2, { copie2 = it }, colors = CheckboxDefaults.colors(checkedColor = Vert))
                            Text("Conseillé : j'ai fait une 2e copie (autre disque ou PC).", fontSize = 14.sp)
                        }
                    }
                    if (verifie) {
                        Para("Dans Google Photos : appuie longuement sur une photo, coche une journée entière, puis « Supprimer ». La corbeille de Google garde tout 60 jours.")
                        BigButton("Ouvrir Google Photos", color = Rouge) { ouvrir("https://photos.google.com/") }
                    } else {
                        Text("🛡️ Coche d'abord la première case : on ne libère jamais avant d'avoir vérifié la copie.",
                            color = Vert, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Gap()
                    TextButton(onClick = {
                        ask(Confirm("Recommencer depuis le début ?",
                            "Pour un autre compte ou un autre disque. Les fichiers déjà copiés sur le disque ne sont pas touchés.", "Recommencer") { vm.gpRecommencer() })
                    }) { Text("Recommencer avec un autre compte", color = Gris) }
                    Precedent { vm.gpAller(6) }
                }
            }
            Gap()
            OutlinedButton(onClick = { conseils = true }, modifier = Modifier.fillMaxWidth()) {
                Text("💡 Si ça bloque : les conseils", color = Texte)
            }
        }
    }

    if (conseils) AlertDialog(
        onDismissRequest = { conseils = false },
        containerColor = Carte,
        title = { Text("💡 Si ça bloque", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(Modifier.height(420.dp)) {
                vm.google.conseils.forEach { (ico, txt) ->
                    item { Row(Modifier.padding(vertical = 6.dp)) { Text(ico, fontSize = 20.sp); RowGap(10); Text(txt, color = Texte, fontSize = 14.sp) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { conseils = false }) { Text("Compris") } },
    )

    vm.gpProbleme?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.gpProbleme = null },
            containerColor = Carte,
            title = { Text("⚠️ On ne commence pas", fontWeight = FontWeight.Bold) },
            text = { Text(p, color = Texte) },
            confirmButton = { TextButton(onClick = { vm.gpProbleme = null }) { Text("Compris") } },
            dismissButton = { TextButton(onClick = { vm.gpProbleme = null; conseils = true }) { Text("Voir les conseils") } },
        )
    }
}

@Composable
private fun Bandeau(emoji: String, titre: String, c: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(c, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 26.sp) }
        RowGap(14)
        Text(titre, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
    }
}

@Composable
private fun Para(t: String) = Text(t, color = Texte, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(vertical = 8.dp))

@Composable
private fun Puce(e: String, t: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(e, fontSize = 20.sp); RowGap(10); Text(t, color = Texte, fontSize = 15.sp)
    }
}

@Composable
private fun Etape(n: Int, t: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Bleu, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, fontWeight = FontWeight.Black)
        }
        RowGap(12)
        Text(t, color = Texte, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun Suivant(t: String, onClick: () -> Unit) {
    Gap(12)
    BigButton("$t  ›", color = Vert, onClick = onClick)
}

@Composable
private fun Precedent(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("‹ Étape précédente", color = Gris) }
}
