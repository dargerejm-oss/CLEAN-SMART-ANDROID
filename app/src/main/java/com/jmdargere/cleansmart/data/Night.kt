package com.jmdargere.cleansmart.data

import com.jmdargere.cleansmart.Tr

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jmdargere.cleansmart.MainActivity
import com.jmdargere.cleansmart.R
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Le travail de nuit (une fois par jour, vers 2 h du matin) :
 *  - sauvegarde automatique des nouvelles photos dans le dossier choisi (option) ;
 *  - alerte si le telephone n'a presque plus de place (au plus tous les 3 jours) ;
 *  - rapport du mois, le 1er de chaque mois.
 */
object Night {

    private const val WORK = "clean_smart_nuit"
    private const val CHANNEL = "clean_smart"
    private const val LOW_SPACE = 1L shl 30   // 1 Go

    /** Programme (ou arrete) le travail de nuit selon les options. */
    fun schedule(ctx: Context, s: Settings) {
        val wm = WorkManager.getInstance(ctx)
        if (!s.nightBackup && !s.alerts) { wm.cancelUniqueWork(WORK); return }
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 2); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val req = PeriodicWorkRequestBuilder<NightWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, Tr.t("Clean Smart"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cx_front)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun freeBytes(): Long = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    class NightWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            val s = Settings.load(ctx)
            val p = ctx.getSharedPreferences("nuit", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            // 1. Sauvegarde automatique
            if (s.nightBackup) {
                val backup = Backup(ctx)
                val tree = s.backupTree
                if (backup.isUsable(tree)) {
                    // seulement les bonnes photos : pas les doublons/rafales/ratees reperes a la derniere analyse,
                    // ni les captures d'ecran
                    val exclure = p.getStringSet("exclure", emptySet()) ?: emptySet()
                    val bonnes = MediaScanner(ctx).allMedia().filter {
                        it.key !in exclure && !it.path.contains("screenshot", true) && !it.name.startsWith("Screenshot", true)
                    }
                    val n = runCatching { backup.saveMedia(tree!!, bonnes) { _, _ -> } }.getOrDefault(-1)
                    p.edit().putLong("lastBackup", now).putInt("lastBackupCount", n).apply()
                    if (n > 0) notify(ctx, 1, Tr.t("💾 Sauvegarde de la nuit"), Tr.t("%1 copié(s) dans ton dossier de sauvegarde.", plural(n, Tr.t("nouveau fichier"), Tr.t("nouveaux fichiers"))))
                    if (n < 0) notify(ctx, 1, Tr.t("⚠️ Sauvegarde de la nuit"), Tr.t("La sauvegarde n'a pas pu se faire. Ouvre Clean Smart pour vérifier le dossier."))
                } else {
                    notify(ctx, 1, Tr.t("⚠️ Aucune sauvegarde cette nuit"), Tr.t("Le dossier de sauvegarde n'est pas accessible. Ouvre Clean Smart > Options pour le choisir."))
                }
            }

            if (s.alerts) {
                // 2. Telephone presque plein (au plus tous les 3 jours)
                val free = freeBytes()
                if (free < LOW_SPACE && now - p.getLong("lastLowAlert", 0) > 3 * 24 * 3600 * 1000L) {
                    p.edit().putLong("lastLowAlert", now).apply()
                    notify(ctx, 2, Tr.t("📱 Téléphone presque plein"), Tr.t("Il ne reste que %1. Ouvre Clean Smart pour libérer de la place en un clic.", fmtBytes(free)))
                }
                // 3. Rappel tous les 2 mois : une nouvelle copie Google / iCloud pour les nouvelles photos
                for (src in SourceExport.entries) {
                    val derniere = GoogleExport(ctx, src).derniereCopie
                    val cle = "rappelCopie_" + src.name
                    val deuxMois = 60L * 24 * 3600 * 1000
                    if (derniere > 0 && now - maxOf(derniere, p.getLong(cle, 0)) > deuxMois) {
                        p.edit().putLong(cle, now).apply()
                        if (src == SourceExport.GOOGLE)
                            notify(ctx, 4, Tr.t("📦 Ta nouvelle copie Google est peut-être prête"),
                                Tr.t("Tes photos des 2 derniers mois ne sont pas encore sur le disque. Demande une nouvelle copie sur Google Takeout, puis le mode automatique n'ajoutera que les nouvelles photos, sans doublon."))
                        else
                            notify(ctx, 5, Tr.t("🍏 Pense à une nouvelle copie iCloud"),
                                Tr.t("Tes photos des 2 derniers mois ne sont pas encore sur le disque. Demande une nouvelle copie à Apple : Clean Smart n'ajoutera que les nouvelles photos, sans doublon."))
                    }
                }
                // 4. Rapport du mois (le 1er)
                val cal = Calendar.getInstance()
                val month = cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
                if (cal.get(Calendar.DAY_OF_MONTH) == 1 && p.getInt("lastReport", 0) != month) {
                    p.edit().putInt("lastReport", month).apply()
                    val since = now - 31L * 24 * 3600 * 1000
                    val newPhotos = runCatching { MediaScanner(ctx).allMedia().count { it.date > since } }.getOrDefault(0)
                    val freed = ctx.getSharedPreferences("compteur", Context.MODE_PRIVATE).getLong("freed", 0)
                    notify(ctx, 3, Tr.t("📅 Ton rapport du mois"),
                        Tr.t("%1 ce mois-ci. ", plural(newPhotos, Tr.t("photo ou vidéo prise"), Tr.t("photos ou vidéos prises"))) +
                                Tr.t("Espace libéré depuis le début : %1. Une petite analyse ?", fmtBytes(freed)))
                }
            }
            return Result.success()
        }
    }
}
