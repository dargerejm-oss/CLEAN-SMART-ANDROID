package com.jmdargere.cleansmart

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.widget.RemoteViews
import com.jmdargere.cleansmart.data.fmtBytes

/**
 * Widget de l'ecran d'accueil du telephone : la place libre en un coup d'oeil,
 * et un bouton qui ouvre directement le Menage guide.
 */
class Widget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, vue(ctx)) }
    }

    companion object {
        const val OUVRIR_GUIDE = "ouvrir_guide"

        fun vue(ctx: Context): RemoteViews {
            Tr.init(ctx); Tr.choix = Tr.CODES.getOrNull(com.jmdargere.cleansmart.data.Settings.load(ctx).langue - 1)
            val st = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            val total = st?.totalBytes ?: 0L
            val libre = st?.availableBytes ?: 0L
            val pct = if (total > 0) ((total - libre) * 100 / total).toInt() else 0
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_libre, fmtBytes(libre))
            v.setTextViewText(R.id.w_detail, Tr.t("libres · %1 % utilisé", pct))
            v.setProgressBar(R.id.w_barre, 100, pct, false)
            v.setTextViewText(R.id.w_bouton, Tr.t("🧭 Ménage"))
            val i = Intent(ctx, MainActivity::class.java).putExtra(OUVRIR_GUIDE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_racine, pi)
            v.setOnClickPendingIntent(R.id.w_bouton, pi)
            return v
        }

        /** Met a jour le widget (apres un nettoyage par exemple). */
        fun rafraichir(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, vue(ctx)) }
        }
    }
}
