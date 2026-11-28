package com.jmdargere.cleansmart.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.storage.StorageManager
import java.io.File

/** Une appli installee : nom, place prise, derniere ouverture (0 = inconnue). */
data class Appli(val pkg: String, val nom: String, val taille: Long, val dernierUsage: Long, val installee: Long)

/**
 * Gestionnaire d'applis (2.0, repris de Nettoyage Pro) : les applis les plus grosses et celles qu'on n'ouvre jamais.
 * Avec « Accès aux données d'utilisation » (une fois), Clean Smart connait la vraie taille (appli + donnees + cache)
 * et la date de derniere ouverture. La desinstallation est toujours confirmee par Android.
 */
class Applis(private val ctx: Context) {

    /** L'appli a-t-elle l'« Accès aux données d'utilisation » ? */
    fun accesUsage(): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun lister(): List<Appli> {
        val pm = ctx.packageManager
        val acces = accesUsage()
        val maintenant = System.currentTimeMillis()
        val usages = if (acces) runCatching {
            ctx.getSystemService(UsageStatsManager::class.java)
                .queryAndAggregateUsageStats(maintenant - 365L * 86_400_000, maintenant)
                .mapValues { it.value.lastTimeUsed }
        }.getOrDefault(emptyMap()) else emptyMap()
        val stats = if (acces) ctx.getSystemService(StorageStatsManager::class.java) else null
        return pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != ctx.packageName &&
                    pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { ai ->
                val taille = stats?.let { s ->
                    runCatching {
                        val st = s.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                        st.appBytes + st.dataBytes + st.cacheBytes
                    }.getOrNull()
                } ?: runCatching { File(ai.sourceDir).length() }.getOrDefault(0L)
                Appli(ai.packageName, pm.getApplicationLabel(ai).toString(), taille, usages[ai.packageName] ?: 0L,
                    runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0L))
            }
            .sortedByDescending { it.taille }
    }
}
