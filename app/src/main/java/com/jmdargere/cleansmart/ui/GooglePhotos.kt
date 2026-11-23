package com.jmdargere.cleansmart.ui

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
            ask(Confirm("Fichier ${"%03d".format(num)} déjà fait",
                "Ce fichier est déjà copié et vérifié. Le refaire ne crée aucun doublon, mais c'est inutile.\n\nTu voulais sans doute prendre le suivant (${"%03d".format(num + 1)}).",
                "Le refaire quand même", danger = false) { vm.gpTraiter(uri) { zipFini = it } })
        } else vm.gpTraiter(uri) { zipFini = it }
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
    fun ouvrir(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

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
            Circuit(phase = when { maintenant <= 3 -> 0; maintenant == 4 -> 1; else -> 2 })
            Gap(8)
            // la jauge
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Carte),
                elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(vm.gpAvancement * 100).toInt()} %", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Vert)
                        Spacer(Modifier.width(10.dp))
                        Text("de ta photothèque est à l'abri", color = Gris, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    LinearProgressIndicator(progress = { vm.gpAvancement }, color = Vert, trackColor = Carte2,
                        modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(99.dp)))
                    Gap(6)
                    val t = vm.gpTotal
                    Text("${fmtBytes(t.octets)}${if (vm.gpTaille > 0) " sur environ ${vm.gpTaille} Go" else ""} · " +
                            "${fmtNum(t.photos)} photos · ${fmtNum(t.videos)} vidéos · ${fmtNum(t.doublons + t.rafales)} doublons et rafales écartés",
                        color = Gris, fontSize = 12.sp)
                }
            }
            Gap(12)

            // ---------------------------------------------------------------- 1. la place
            EtapeGP(1, "📏", "Combien de place faut-il ?",
                if (vm.gpTaille > 0) "${vm.gpTaille} Go dans Google · prévois ${fmtBytes(vm.google.placeNecessaire())}" else "Regarder la taille de Google Photos",
                faites, maintenant, affichee, { ouverte = if (affichee == 1) -1 else 1 }) {
                Txt("Ouvre ta page Google et regarde la place utilisée par Google Photos. Écris-la ici (en Go).")
                BigButton("Voir ma place Google", color = Bleu) { ouvrir("https://one.google.com/storage") }
                Gap(8)
                var saisie by rememberSaveable { mutableStateOf(if (vm.gpTaille > 0) vm.gpTaille.toString() else "") }
                OutlinedTextField(saisie, { saisie = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Taille de Google Photos (Go)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                saisie.toIntOrNull()?.takeIf { it > 0 }?.let { go ->
                    Gap(6)
                    Encadre("Prévois au moins ${fmtBytes((go * 1.1 * (1L shl 30)).toLong())} sur la clé ou le disque (10 % de marge). " +
                            "Sur le téléphone, 2 à 3 Go libres suffisent : un seul fichier à la fois. Libre maintenant : ${fmtBytes(vm.google.libreTelephone())}.", Bleu)
                    BoutonVert("C'est noté") { vm.gpSetTaille(go); ouverte = 0 }
                }
            }

            // ---------------------------------------------------------------- 2. le disque
            EtapeGP(2, "💾", "Brancher la clé ou le disque",
                if (vm.gpDestOk) "${vm.gpDest} · ${vm.google.libreDestination()?.let { fmtBytes(it) + " libres" } ?: ""}"
                else "Clé USB ou disque dur, avec un adaptateur USB-C",
                faites, maintenant, affichee, { ouverte = if (affichee == 2) -1 else 2 }) {
                Txt("Branche ta clé ou ton disque sur le téléphone, puis choisis (ou crée) un dossier dessus.")
                val disques = remember(vm.gpDest, affichee) { vm.google.disques() }
                disques.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Carte2, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (d.amovible) "💾" else "📱", fontSize = 22.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.nom, fontWeight = FontWeight.Bold, color = Texte)
                            Text("${fmtBytes(d.libre)} libres sur ${fmtBytes(d.total)}", color = Gris, fontSize = 12.sp)
                        }
                    }
                }
                if (disques.none { it.amovible }) Encadre("Aucune clé ni disque détecté. Branche-le (adaptateur USB-C), puis rouvre cette étape.", Orange)
                val libre = vm.google.libreDestination()
                val besoin = vm.google.placeNecessaire()
                if (vm.gpDestOk && libre != null && besoin > 0) {
                    if (libre >= besoin) Encadre("✅ Assez de place sur « ${vm.gpDest} ».", Vert)
                    else Encadre("⚠️ Il faut environ ${fmtBytes(besoin)}, il reste ${fmtBytes(libre)}. Tu peux commencer : quand il sera plein, tu brancheras un autre disque et on continuera sans doublon.", Orange)
                }
                BoutonVert(if (vm.gpDestOk) "Changer de dossier" else "Choisir le dossier sur le disque") { destLauncher.launch(null) }
            }

            // ---------------------------------------------------------------- 3. demander l'export
            EtapeGP(3, "📨", "Demander la copie à Google",
                if (vm.gpDemande) "Demandée · attends le mail de Google" else "Sur Google Takeout, en 1 minute",
                faites, maintenant, affichee, { ouverte = if (affichee == 3) -1 else 3 }) {
                Txt("Google prépare une copie de toutes tes photos. Fais exactement comme sur ce modèle :")
                TakeoutModele()
                BigButton("Ouvrir Google Takeout", color = Orange) { ouvrir("https://takeout.google.com/") }
                Encadre("Google envoie un mail quand c'est prêt : en quelques heures ou quelques jours. Tu peux fermer l'appli en attendant.", Bleu)
                BoutonVert("J'ai demandé la copie") { vm.gpSetDemande(true); ouverte = 0 }
            }

            // ---------------------------------------------------------------- 4. les fichiers un par un
            EtapeGP(4, "⚙️", "Copier les fichiers un par un",
                if (vm.gpTermine) "${plural(vm.gpEtats.count { it.value == EtatZip.OK }, "fichier copié et vérifié", "fichiers copiés et vérifiés")}"
                else if (vm.gpNb > 0) "${vm.gpEtats.count { it.value == EtatZip.OK }} sur ${vm.gpNb} fichiers faits" else "Quand le mail de Google est arrivé",
                faites, maintenant, affichee, { ouverte = if (affichee == 4) -1 else 4 }) {
                // combien de fichiers dans le mail ?
                var nb by rememberSaveable { mutableStateOf(if (vm.gpNb > 0) vm.gpNb.toString() else "") }
                Txt("Dans le mail de Google, compte les boutons « Télécharger » : c'est le nombre de fichiers.")
                OutlinedTextField(nb, { nb = it.filter { c -> c.isDigit() }.take(3); nb.toIntOrNull()?.let(vm::gpSetNb) },
                    label = { Text("Nombre de fichiers dans le mail") }, singleLine = true,
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
                                    e == EtatZip.OK -> "✅ copié et vérifié"
                                    e == EtatZip.A_REPRENDRE -> "⚠️ à relancer"
                                    i == prochain -> "👉 à télécharger puis traiter"
                                    else -> "⏳ plus tard"
                                }, color = when { e == EtatZip.OK -> Vert; e == EtatZip.A_REPRENDRE -> Rouge; i == prochain -> Bleu; else -> Gris },
                                    fontWeight = if (i == prochain) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                            }
                        }
                    }
                    Gap(8)
                    if (prochain != null) {
                        Encadre("👉 Maintenant : dans le mail de Google, télécharge le fichier ${"%03d".format(prochain)} (en Wi-Fi). Puis touche le bouton vert et choisis-le dans Téléchargements.", Bleu)
                    }
                }
                // le dernier resultat
                vm.gpDernier?.let { (nom, s) ->
                    Encadre(
                        (if (s.sansProbleme) "✅ $nom : terminé et vérifié.\n" else "⚠️ $nom : à relancer.\n") +
                                "${plural(s.photos, "photo")}, ${plural(s.videos, "vidéo")}, ${plural(s.mails, "mail")} copiés · ${plural(s.doublons, "doublon")} et ${plural(s.rafales, "rafale")} écartés." +
                                when {
                                    s.plein -> "\n\nLe disque ou le téléphone est plein : libère de la place (ou change de disque à l'étape 2), puis relance CE fichier. Rien ne sera recopié en double."
                                    s.abime -> "\n\nLe fichier est abîmé ou incomplet : retélécharge-le depuis le mail de Google, puis relance-le. Ce qui est déjà copié est gardé."
                                    s.erreurs > 0 -> "\n\n${plural(s.erreurs, "fichier n'a", "fichiers n'ont")} pas pu être copié(s) : relance ce même fichier."
                                    else -> ""
                                }, if (s.sansProbleme) Vert else Rouge)
                    val z = zipFini
                    if (s.sansProbleme && z != null) {
                        OutlinedButton(onClick = {
                            ask(Confirm("Supprimer ce fichier du téléphone ?",
                                "Tout son contenu est copié et vérifié sur le disque. Le supprimer fait de la place pour le suivant.\n\nTes photos restent aussi dans Google Photos.",
                                "Supprimer") { vm.gpSupprimerZip(z); zipFini = null })
                        }, modifier = Modifier.fillMaxWidth()) { Text("🗑️ Supprimer ce ZIP du téléphone (conseillé)", color = Rouge) }
                    }
                }
                BoutonVert("📂 Traiter un fichier ZIP") { choisirZip() }
                val tousFaits = vm.gpNb > 0 && (1..vm.gpNb).all { vm.gpEtats[it] == EtatZip.OK }
                if (tousFaits) BigButton("🎉 Tous les fichiers sont faits", color = Bleu) { vm.gpSetTermine(true); ouverte = 0 }
                else if (vm.gpTotal.zips > 0) TextButton(onClick = {
                    ask(Confirm("Tous les fichiers sont traités ?",
                        "Vérifie dans le mail de Google que chaque fichier (001, 002, 003…) a bien été traité.", "Oui, c'est fini", danger = false) {
                        vm.gpSetTermine(true); ouverte = 0
                    })
                }, modifier = Modifier.fillMaxWidth()) { Text("J'ai traité tous les fichiers", color = Gris) }
            }

            // ---------------------------------------------------------------- 5. bilan et liberer
            EtapeGP(5, "🎉", "Vérifier, puis libérer Google (si tu veux)",
                "Le bilan, et comment libérer ton compte Google",
                faites, maintenant, affichee, { ouverte = if (affichee == 5) -1 else 5 }) {
                val t = vm.gpTotal
                Encadre("📸 ${fmtNum(t.photos)} photos et ${fmtNum(t.videos)} vidéos copiées et vérifiées, rangées par année\n" +
                        "🧬 ${fmtNum(t.doublons)} doublons et 🎞️ ${fmtNum(t.rafales)} photos de rafale écartés\n" +
                        (if (t.mails > 0) "✉️ ${fmtNum(t.mails)} mails (dossier « Mails Gmail », ouvre index.html)\n" else "") +
                        "💾 ${fmtBytes(t.octets)} sur « ${vm.gpDest} »", Vert)
                if (t.erreurs > 0) Encadre("⚠️ ${plural(t.erreurs, "fichier")} en erreur : relance les ZIP marqués « à relancer » avant d'aller plus loin.", Rouge)
                Txt("Libérer Google Photos est facultatif. Google n'autorise aucune appli à effacer tes photos : c'est toi qui le fais, quand tu es sûr.")
                var verifie by rememberSaveable { mutableStateOf(false) }
                var copie2 by rememberSaveable { mutableStateOf(false) }
                Coche(verifie, "J'ai ouvert le disque sur un PC et je vois bien mes photos, année par année.") { verifie = it }
                Coche(copie2, "Conseillé : j'ai fait une 2e copie (autre disque ou PC).") { copie2 = it }
                if (verifie) {
                    Txt("Dans Google Photos : appuie longuement sur une photo, coche des journées entières, puis « Supprimer ». La corbeille de Google garde tout 60 jours.")
                    BigButton("Ouvrir Google Photos", color = Rouge) { ouvrir("https://photos.google.com/") }
                } else Encadre("🛡️ On ne libère jamais Google avant d'avoir vérifié la copie : coche d'abord la première case.", Vert)
                TextButton(onClick = {
                    ask(Confirm("Recommencer depuis le début ?",
                        "Pour un autre compte ou un autre disque. Les fichiers déjà copiés sur le disque ne sont pas touchés.", "Recommencer") { vm.gpRecommencer() })
                }, modifier = Modifier.fillMaxWidth()) { Text("Recommencer avec un autre compte", color = Gris) }
            }

            Gap(10)
            OutlinedButton(onClick = { conseils = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("💡 Si ça bloque : tous les conseils", color = Texte, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (conseils) AlertDialog(
        onDismissRequest = { conseils = false },
        containerColor = Carte,
        title = { Text("💡 Si ça bloque", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(Modifier.height(440.dp)) {
                vm.google.conseils.forEach { (ico, txt) ->
                    item { Row(Modifier.padding(vertical = 6.dp)) { Text(ico, fontSize = 20.sp); Spacer(Modifier.width(10.dp)); Text(txt, color = Texte, fontSize = 14.sp) } }
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

// ============================================================================== morceaux d'interface

/** Le circuit : ☁️ Google -> 📱 telephone -> 💾 disque, avec un point qui avance sur le trajet en cours. */
@Composable
private fun Circuit(phase: Int) {
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
                Lieu("☁️", "Google", phase == 0)
                Lieu("📱", "Téléphone", phase == 1)
                Lieu("💾", "Disque", phase == 2)
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
                    Text(if (faite) "✓" else if (plusTard) "🔒" else "$n", color = Color.White, fontSize = if (plusTard) 18.sp else 22.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("$emoji  $titre", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                        color = when { faite -> Vert; plusTard -> Gris; else -> Texte })
                    Text(when { estMaintenant && !ouverte -> "👉 À faire maintenant"; else -> resume }, color = Gris, fontSize = 13.sp)
                }
                if (estMaintenant) Text("MAINTENANT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.background(Vert, RoundedCornerShape(8.dp)).padding(8.dp, 3.dp))
                else if (faite) Text(if (ouverte) "▲" else "Modifier ▼", color = Gris, fontSize = 12.sp)
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
            Text("Google Takeout", fontWeight = FontWeight.Bold, color = Color(0xFF5F6368), fontSize = 15.sp)
            Gap(6)
            LigneModele(1, "Tout désélectionner", false, lien = true)
            LigneModele(2, "Google Photos", true)
            LigneModele(0, "Mail (facultatif, pour tes mails)", true, faible = true)
            LigneModele(0, "Drive, YouTube, Maps…", false, faible = true)
            Gap(4)
            Text("puis « Étape suivante » :", color = Color(0xFF5F6368), fontSize = 12.sp)
            LigneModele(3, "Envoyer le lien par e-mail · Exporter une fois", true)
            LigneModele(4, "Type : .zip   ·   Taille : 2 Go", true, important = true)
            LigneModele(5, "Créer une exportation", false, lien = true)
        }
    }
}

@Composable
private fun LigneModele(n: Int, txt: String, coche: Boolean, lien: Boolean = false, faible: Boolean = false, important: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
        .then(if (important) Modifier.background(Color(0xFFFFF3E0), RoundedCornerShape(8.dp)) else Modifier).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(if (n > 0) Rouge else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            if (n > 0) Text("$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
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
    BigButton("$t  ›", color = Vert, onClick = onClick)
}

@Composable
private fun Coche(v: Boolean, t: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!v) }) {
        Checkbox(v, onChange, colors = CheckboxDefaults.colors(checkedColor = Vert))
        Text(t, fontSize = 14.sp, color = Texte)
    }
}
