package com.jmdargere.cleansmart.ui

import com.jmdargere.cleansmart.Tr

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jmdargere.cleansmart.AppViewModel
import com.jmdargere.cleansmart.data.Regles
import com.jmdargere.cleansmart.data.Sender
import com.jmdargere.cleansmart.data.fmtBytes
import com.jmdargere.cleansmart.data.fmtDate
import com.jmdargere.cleansmart.data.fmtNum
import com.jmdargere.cleansmart.data.plural

/**
 * Relire avant de jeter (2.0) : la liste des mails qui vont partir (expediteur, objet, date, taille).
 * Tout est coche ; on decoche ceux a garder, puis on valide. Rien ne part sans cet ecran.
 */
@Composable
fun MailRevueDialog(vm: AppViewModel) {
    val r = vm.mailRevue ?: return
    Dialog(onDismissRequest = { vm.revueFermer() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Fond)) {
            Column(Modifier.fillMaxWidth().background(Bleu).padding(16.dp, 14.dp)) {
                Text(r.titre, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                val choisis = r.mails.filter { it.uid in r.coches }
                Text(Tr.t("%1 sur %2 choisis · %3", fmtNum(choisis.size), fmtNum(r.mails.size), fmtBytes(choisis.sumOf { it.size })),
                    color = androidx.compose.ui.graphics.Color(0xE6FFFFFF), fontSize = 14.sp)
            }
            Text(if (r.spam) Tr.t("Les spams vont dans la corbeille de ta messagerie. Décoche un mail s'il n'est pas un spam.")
                 else Tr.t("Décoche les mails que tu veux garder. Les mails de ta banque, des impôts, de santé et les expéditeurs « toujours garder » ne sont jamais dans cette liste. Tout va dans la corbeille de ta messagerie (récupérable)."),
                color = Gris, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.revueTout(true) }) { Text(Tr.t("☑️ Tout cocher"), color = Violet2) }
                TextButton(onClick = { vm.revueTout(false) }) { Text(Tr.t("⬜ Tout décocher"), color = Violet2) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 8.dp)) {
                items(r.mails.take(500), key = { it.uid }) { m ->
                    val on = m.uid in r.coches
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(if (on) Rouge.copy(alpha = 0.06f) else Carte, RoundedCornerShape(12.dp))
                        .clickable { vm.revueCocher(m.uid) }.padding(4.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { vm.revueCocher(m.uid) }, colors = CheckboxDefaults.colors(checkedColor = Rouge))
                        Column(Modifier.weight(1f)) {
                            Text(m.from.ifEmpty { "?" }, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Texte)
                            Text(m.subject.ifEmpty { Tr.t("(sans objet)") }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Texte)
                            Text("${fmtDate(m.date)} · ${fmtBytes(m.size)}", color = Gris, fontSize = 11.sp)
                        }
                    }
                }
                if (r.mails.size > 500) item {
                    Text(Tr.t("… et %1 autres mails du même genre (cochés).", fmtNum(r.mails.size - 500)), color = Gris, fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }
            Column(Modifier.fillMaxWidth().background(Carte).padding(16.dp, 10.dp)) {
                BigButton(Tr.t("🗑️ Mettre %1 à la corbeille", plural(r.coches.size, "mail")), color = Rouge, enabled = r.coches.isNotEmpty()) { vm.revueValider() }
                TextButton(onClick = { vm.revueFermer() }, modifier = Modifier.fillMaxWidth()) { Text(Tr.t("Annuler, ne rien jeter"), color = Gris) }
            }
        }
    }
}

/** Les choix pour un expediteur : toujours jeter, toujours garder, me prevenir, se desabonner. */
@Composable
fun ExpediteurDialog(vm: AppViewModel, s: Sender, fermer: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = vm.reglesVersion   // se met a jour quand une regle change
    val e = s.email.lowercase()
    AlertDialog(
        onDismissRequest = fermer, containerColor = Carte,
        title = {
            Column {
                Text(s.name.ifEmpty { s.email }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Tr.t("%1 · %2", plural(s.count, "mail"), fmtBytes(s.bytes)), color = Gris, fontSize = 13.sp)
            }
        },
        text = {
            Column {
                if (s.important) Text(Tr.t("🛡️ Expéditeur important (banque, administration, santé…) : ses mails ne sont jamais jetés."), color = Vert, fontSize = 13.sp)
                ChoixRegle("🗑️", Tr.t("Toujours jeter"), Tr.t("Ses mails partent à la corbeille chaque nuit, tout seuls"), e in Regles.jeter, !s.important) { vm.regle("jeter", e) }
                ChoixRegle("🛡️", Tr.t("Toujours garder"), Tr.t("Jamais jetés, rangés dans « Protégés »"), Regles.garder(e), true) { vm.regle("garder", e) }
                ChoixRegle("🔔", Tr.t("Me prévenir"), Tr.t("Une notification quand il t'écrit"), e in Regles.alertes, true) { vm.regle("alertes", e) }
                if (!s.important) {
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(onClick = { fermer(); vm.mailVoir(Tr.t("✉️ Mails de %1", s.name.ifEmpty { s.email }), com.jmdargere.cleansmart.data.MailCriteria.From(listOf(s.email))) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(Tr.t("👀 Voir et jeter ses mails maintenant"), color = Texte) }
                    if (s.unsubscribe.isNotEmpty()) TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.unsubscribe))) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(Tr.t("🚫 Se désabonner"), color = Rouge, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = { TextButton(onClick = fermer) { Text(Tr.t("Fermer"), color = Violet2) } },
    )
}

@Composable
private fun ChoixRegle(emoji: String, titre: String, aide: String, actif: Boolean, possible: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
        .background(if (actif) Vert.copy(alpha = 0.10f) else Carte2, RoundedCornerShape(12.dp))
        .clickable(enabled = possible, onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(titre, fontWeight = FontWeight.Bold, color = if (possible) Texte else Gris)
            Text(aide, color = Gris, fontSize = 12.sp)
        }
        Text(if (actif) "✅" else "", fontSize = 18.sp)
    }
}

/** Carte « Boîte protégée » + « Mes règles » + « Spam » sur l'ecran Mails. */
@Composable
fun MailsOutils(vm: AppViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.reglesVersion
    BoxCard {
        InfoRow("🛡️", Tr.t("Boîte protégée"), Tr.t("Banque, impôts, santé, maison, famille (tes contacts) : rangés à part, jamais jetés"))
        vm.mailProteges?.let { res ->
            Text(res.entries.joinToString("   ") { (k, n) -> "${dossierEmoji(k)} ${Tr.t(k.replace("Impots", "Impôts").replace("Sante", "Santé").replace("Gardes", "Gardés"))} $n" },
                color = Vert, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 50.dp, bottom = 6.dp))
        }
        Text(if (vm.mailGmail) Tr.t("Sur Gmail, ils gardent leur place dans la boîte et reçoivent un libellé « Protégés ». Refait chaque nuit.")
             else Tr.t("Ils sont rangés dans le dossier « Protégés » de ta messagerie. Refait chaque nuit."),
            color = Gris, fontSize = 12.sp, modifier = Modifier.padding(start = 50.dp))
        Spacer(Modifier.padding(4.dp))
        BigButton(Tr.t("🛡️ Ranger mes mails importants"), color = Vert) { vm.protegerMails() }
    }
    BoxCard {
        val r = Regles
        InfoRow("⚙️", Tr.t("Mes règles"), Tr.t("Touche un expéditeur dans « Qui t'écrit le plus » pour choisir"))
        Text(Tr.t("🗑️ Toujours jeter : %1   🛡️ Toujours garder : %2   🔔 Me prévenir : %3", r.jeter.size, r.gardes.size, r.alertes.size),
            color = Texte, fontSize = 13.sp, modifier = Modifier.padding(start = 50.dp))
        if (r.jeter.isNotEmpty()) TextButton(onClick = { vm.appliquerRegles() }, modifier = Modifier.padding(start = 42.dp)) {
            Text(Tr.t("Appliquer « toujours jeter » maintenant"), color = Violet2, fontWeight = FontWeight.Bold)
        }
        InfoRow("🚫", Tr.t("Spam"), Tr.t("Voir le courrier indésirable puis le mettre à la corbeille"), onClick = { vm.voirSpam() })
    }
}

private fun dossierEmoji(k: String) = when (k) {
    "Banque" -> "🏦"; "Impots - Administration" -> "🏛️"; "Sante" -> "🩺"; "Maison" -> "🏠"; "Famille" -> "👨‍👩‍👧"; else -> "⭐"
}
