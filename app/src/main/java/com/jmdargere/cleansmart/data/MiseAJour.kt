package com.jmdargere.cleansmart.data

import android.content.Context
import com.jmdargere.cleansmart.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise a jour en un geste : une fois par jour, l'appli regarde sur GitHub le numero de la derniere version
 * (petit fichier version.json publie avec l'APK). Aucune donnee personnelle n'est envoyee.
 * Si une version plus recente existe, un bandeau propose de la telecharger directement.
 */
object MiseAJour {

    private const val DEPOT = "https://github.com/dargerejm-oss/CLEAN-SMART-ANDROID/releases/latest/download/"

    /** Le lien direct de la bonne version (complete ou essai 30 jours). */
    val lienApk: String get() = DEPOT + if (Essai.actif) "CLEAN_SMART_TEST_30_JOURS.apk" else "CLEAN_SMART.apk"

    /** Retourne le nom de la nouvelle version (ex. "1.8"), ou null si on est a jour. */
    fun verifier(ctx: Context, forcer: Boolean = false): String? {
        val p = ctx.getSharedPreferences("mise_a_jour", Context.MODE_PRIVATE)
        val maintenant = System.currentTimeMillis()
        if (!forcer && maintenant - p.getLong("dernier", 0) < 24L * 3600 * 1000) {
            return p.getString("nouvelle", null)?.takeIf { p.getInt("code", 0) > BuildConfig.VERSION_CODE }
        }
        return runCatching {
            val c = URL(DEPOT + "version.json").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.instanceFollowRedirects = true
            val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val code = js.optInt("versionCode")
            val nom = js.optString("versionName")
            p.edit().putLong("dernier", maintenant).putInt("code", code).putString("nouvelle", nom).apply()
            nom.takeIf { code > BuildConfig.VERSION_CODE }
        }.getOrNull()
    }
}
