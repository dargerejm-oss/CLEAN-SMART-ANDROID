package com.jmdargere.cleansmart.data

import com.jmdargere.cleansmart.Tr

import android.content.Context
import android.content.SharedPreferences
import android.provider.ContactsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Regles par expediteur (2.0), choisies dans « Qui t'écrit le plus » :
 *  - 🗑️ toujours jeter  : ses mails partent a la corbeille chaque nuit, tout seuls ;
 *  - 🛡️ toujours garder : jamais mis a la corbeille, ranges dans « Protégés / Gardes » ;
 *  - 🔔 me prevenir     : une notification des qu'il ecrit (verifie toutes les 15 minutes).
 */
object Regles {
    private var prefs: SharedPreferences? = null
    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("mail_regles", Context.MODE_PRIVATE) }

    private fun lire(cle: String): Set<String> = prefs?.getStringSet(cle, emptySet())?.toSet() ?: emptySet()
    private fun ecrire(cle: String, v: Set<String>) { prefs?.edit()?.putStringSet(cle, v)?.apply() }

    val jeter: Set<String> get() = lire("jeter")
    val gardes: Set<String> get() = lire("garder")
    val alertes: Set<String> get() = lire("alertes")

    fun garder(email: String) = email.lowercase() in gardes

    /** Une seule regle « jeter » ou « garder » par expediteur ; l'alerte se combine avec « garder ». */
    fun basculer(ctx: Context, cle: String, email: String) {
        init(ctx)
        val e = email.lowercase()
        val actuel = lire(cle)
        val ajout = e !in actuel
        ecrire(cle, if (ajout) actuel + e else actuel - e)
        if (ajout && cle == "jeter") { ecrire("garder", gardes - e); ecrire("alertes", alertes - e) }
        if (ajout && cle == "garder") ecrire("jeter", jeter - e)
        if (ajout && cle == "alertes") ecrire("jeter", jeter - e)
        if (cle == "alertes") AlertesMail.programmer(ctx)
    }

    /** Les adresses mail des contacts du telephone (pour « Protégés / Famille »). */
    fun emailsContacts(ctx: Context): Set<String> = runCatching {
        val out = HashSet<String>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.lowercase()?.trim()?.let { if (it.isNotEmpty()) out += it }
        }
        out
    }.getOrDefault(emptySet())
}

/** Verifie toutes les 15 minutes si un expediteur surveille a ecrit, et previent par une notification. */
object AlertesMail {
    private const val WORK = "clean_smart_alertes"

    fun programmer(ctx: Context) {
        Regles.init(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (Regles.alertes.isEmpty()) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Travail>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class Travail(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            Tr.init(ctx); Tr.choix = Tr.CODES.getOrNull(Settings.load(ctx).langue - 1)
            Regles.init(ctx)
            val mail = MailCleaner(ctx)
            if (!mail.account().ready || Regles.alertes.isEmpty()) return Result.success()
            val p = ctx.getSharedPreferences("mail_regles", Context.MODE_PRIVATE)
            return runCatching {
                val (dernier, nouveaux) = mail.nouveauxDe(Regles.alertes, p.getLong("dernierUid", 0))
                p.edit().putLong("dernierUid", dernier).apply()
                nouveaux.take(5).forEachIndexed { i, m ->
                    Night.notify(ctx, 100 + (m.uid % 1000).toInt() + i, Tr.t("🔔 Mail de %1", m.from), m.subject.ifEmpty { Tr.t("(sans objet)") })
                }
                Result.success()
            }.getOrElse { Result.retry() }
        }
    }
}
