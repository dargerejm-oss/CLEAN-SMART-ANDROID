package com.jmdargere.cleansmart.data

import com.jmdargere.cleansmart.MainActivity
import com.jmdargere.cleansmart.R
import com.jmdargere.cleansmart.Tr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ce que l'ecran affiche pendant le mode « Tout automatique » (mis a jour par [AutoService]).
 */
object AutoEtat {
    var actif by mutableStateOf(false)
    var texte by mutableStateOf("")
    var pct by mutableIntStateOf(0)
    var faits by mutableIntStateOf(0)
    var enCours by mutableIntStateOf(0)
    var lignes by mutableStateOf(listOf<Pair<String, String>>())
    var fin by mutableStateOf<String?>(null)
    var debut by mutableLongStateOf(0L)

    /** Reglages choisis avant de demarrer. */
    var supprimer = true
    var source = SourceExport.GOOGLE

    private val parNom = LinkedHashMap<String, String>()
    @Synchronized fun ligne(nom: String, etat: String) { parNom[nom] = etat; lignes = parNom.toList() }
    @Synchronized fun vider() { parNom.clear(); lignes = emptyList() }

    /** Le dossier ou Chrome et Gmail enregistrent les fichiers. */
    fun dossierDownload(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** L'appli peut-elle lire le dossier Download ? (« Accès à tous les fichiers ») */
    fun accesFichiers(): Boolean = Environment.isExternalStorageManager()

    fun demarrer(ctx: Context, source: SourceExport, supprimer: Boolean) {
        this.source = source; this.supprimer = supprimer
        ContextCompat.startForegroundService(ctx, Intent(ctx, AutoService::class.java))
    }

    fun arreter(ctx: Context) {
        ctx.startService(Intent(ctx, AutoService::class.java).setAction(AutoService.ARRETER))
    }
}

/**
 * Mode « Tout automatique » : surveille le dossier Download. Chaque fichier de l'export (takeout-….zip ou
 * « iCloud Photos Part … .zip ») est copie sur le disque des qu'il a fini de se telecharger, puis (si choisi)
 * supprime du telephone une fois verifie, pour faire de la place au suivant.
 * Service de premier plan : continue pendant que l'utilisateur est dans Gmail ou Chrome pour toucher les liens.
 * Rien n'est jamais supprime chez Google.
 */
class AutoService : Service() {

    companion object {
        const val ARRETER = "com.jmdargere.cleansmart.AUTO_ARRETER"
        private const val CANAL = "clean_smart_auto"
        private const val ID = 11
        private const val FIN_SANS_NOUVEAU = 30 * 60_000L   // fin toute seule 30 min apres le dernier fichier
        private fun ARRET() = Tr.t("Arrêté. Les fichiers déjà copiés sont à l'abri ; les autres le seront la prochaine fois, sans doublon.")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var verrou: PowerManager.WakeLock? = null
    private var derniereNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ARRETER) {
            if (job?.isActive == true) {
                job?.cancel()                                  // s'arrete a la fin du fichier en cours (jamais au milieu)
                AutoEtat.texte = Tr.t("Arrêt après le fichier en cours…")
            } else finir(ARRET())
            return START_NOT_STICKY
        }
        premierPlan(Tr.t("Clean Smart attend les fichiers dans Download…"))
        if (job?.isActive != true) {
            verrou = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanSmart:auto").apply { acquire(6 * 3600_000L) }
            job = scope.launch { boucle() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { verrou?.takeIf { it.isHeld }?.release() }
        AutoEtat.actif = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ la surveillance

    private suspend fun boucle() {
        val ge = GoogleExport(applicationContext, AutoEtat.source)
        val dl = AutoEtat.dossierDownload()
        val motCle = if (AutoEtat.source == SourceExport.GOOGLE) "takeout" else "icloud"
        AutoEtat.apply { actif = true; fin = null; faits = 0; enCours = 0; pct = 0; debut = System.currentTimeMillis(); vider() }
        AutoEtat.texte = Tr.t("⏳ En attente du prochain fichier dans Download…")
        if (ge.copieEffacee()) ge.oublierCopies()      // la copie du disque a ete effacee : on recopie tout

        val suivi = HashMap<String, Pair<Long, Long>>()   // nom -> (taille, depuis quand)
        val abimes = HashMap<String, Long>()              // nom -> taille quand il etait abime
        val traites = HashSet<String>()
        var dernierNouveau = System.currentTimeMillis()
        try {
            while (currentCoroutineContext().isActive) {
                val tous = dl.listFiles()?.toList() ?: emptyList()
                // telechargements pas finis : Chrome et Gmail ecrivent d'abord un fichier « .pending-… » ou « .crdownload »
                var enCours = tous.count { f ->
                    val n = f.name.lowercase()
                    motCle in n && (n.startsWith(".pending") || n.endsWith(".crdownload") || n.endsWith(".part") || n.endsWith(".tmp"))
                }
                var pret: File? = null
                val maintenant = System.currentTimeMillis()
                for (f in tous.filter { it.isFile && ge.estFichierExport(it.name) }.sortedBy { it.name }) {
                    val n = f.name
                    if (n in traites) continue
                    val num = ge.numero(n)
                    if (num > 0 && ge.etats()[num] == EtatZip.OK) continue        // deja copie (par exemple a la main)
                    val taille = f.length()
                    if (taille == 0L || abimes[n] == taille) continue           // abime et pas retelecharge
                    val avant = suivi[n]
                    if (avant == null || avant.first != taille) { suivi[n] = taille to maintenant; enCours++; continue }
                    if (maintenant - avant.second < 8000) { enCours++; continue } // taille stable 8 s : telechargement fini
                    pret = f; break
                }
                AutoEtat.enCours = enCours

                if (pret != null) {
                    dernierNouveau = System.currentTimeMillis()
                    if (!copier(ge, pret, traites, abimes)) return
                    continue
                }
                AutoEtat.pct = 0
                AutoEtat.texte = if (enCours > 0) Tr.t("⬇️ %1 en cours : la copie commencera dès qu'il sera fini.", plural(enCours, Tr.t("téléchargement")))
                                 else Tr.t("⏳ En attente du prochain fichier dans Download. Touche les liens « Télécharger » du mail.")
                notifier(AutoEtat.texte)
                if (AutoEtat.faits > 0 && enCours == 0 && System.currentTimeMillis() - dernierNouveau > FIN_SANS_NOUVEAU) break
                delay(3000)
            }
            if (!currentCoroutineContext().isActive) { finir(ARRET()); return }
            val t = ge.total()
            finir(Tr.t("🎉 Terminé : %1 copiés et vérifiés. Au total sur le disque : %2 et %3.",
                plural(AutoEtat.faits, Tr.t("fichier")), plural(t.photos, "photo"), plural(t.videos, Tr.t("vidéo"))))
        } catch (x: CancellationException) {
            finir(ARRET()); throw x
        } catch (x: Exception) {
            finir(Tr.t("⚠️ La copie s'est arrêtée : %1\n\nRien n'est perdu : relance le mode automatique, on reprendra sans doublon.", x.message ?: ""))
        }
    }

    /** Copie un fichier. Renvoie false s'il faut tout arreter (disque plein, disque debranche…). */
    private fun copier(ge: GoogleExport, f: File, traites: HashSet<String>, abimes: HashMap<String, Long>): Boolean {
        val n = f.name
        val uri = Uri.fromFile(f)
        ge.verifierAvant(uri)?.let { pb ->
            AutoEtat.ligne(n, Tr.t("⚠️ pas commencé"))
            finir(pb); return false
        }
        AutoEtat.ligne(n, Tr.t("⏳ copie en cours…"))
        val s = ge.traiterZip(uri) { txt, pct ->
            AutoEtat.texte = "$n · $txt"; AutoEtat.pct = pct
            notifier(Tr.t("%1 : %2 %", n, pct))
        }
        traites += n
        when {
            s.plein -> {
                AutoEtat.ligne(n, Tr.t("⚠️ disque plein"))
                finir(Tr.t("Le disque (ou le téléphone) est plein : la copie s'est arrêtée proprement.\n\nBranche un disque plus grand, choisis-le à l'étape 2, puis relance le mode automatique : on continue sans doublon."))
                return false
            }
            s.abime -> {
                abimes[n] = f.length(); traites -= n          // retente tout seul s'il est retelecharge
                AutoEtat.ligne(n, Tr.t("⚠️ abîmé : retélécharge-le depuis le mail, il sera copié tout seul"))
            }
            s.sansProbleme -> {
                AutoEtat.faits++
                val supprime = AutoEtat.supprimer && ge.supprimerZip(uri)
                AutoEtat.ligne(n, if (supprime) Tr.t("✅ copié, vérifié, supprimé du téléphone") else Tr.t("✅ copié et vérifié"))
                Night.notify(this, 10, Tr.t("✅ %1 à l'abri", n), Tr.t("%1 et %2 copiées et vérifiées.", plural(s.photos, "photo"), plural(s.videos, Tr.t("vidéo"))))
            }
            else -> AutoEtat.ligne(n, Tr.t("⚠️ à relancer (%1)", plural(s.erreurs, Tr.t("erreur"))))
        }
        return true
    }

    // ------------------------------------------------------------------ fin et notifications

    private fun finir(message: String) {
        AutoEtat.fin = message
        AutoEtat.actif = false
        AutoEtat.pct = 0
        Night.notify(this, 12, Tr.t("Clean Smart : copie automatique"), message)
        runCatching { verrou?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(texte: String) = NotificationCompat.Builder(this, CANAL)
        .setSmallIcon(R.drawable.ic_cx_front)
        .setContentTitle(Tr.t("🤖 Copie automatique de tes photos"))
        .setContentText(texte)
        .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, AutoEtat.pct, AutoEtat.pct == 0)
        .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, Tr.t("Arrêter"), PendingIntent.getService(this, 2, Intent(this, AutoService::class.java).setAction(ARRETER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun premierPlan(texte: String) {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, Tr.t("Copie automatique"), NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, ID, notification(texte), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifier(texte: String) {
        val t = System.currentTimeMillis()
        if (t - derniereNotif < 2000) return
        derniereNotif = t
        runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification(texte)) }
    }
}
