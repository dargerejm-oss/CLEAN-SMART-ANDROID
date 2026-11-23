package com.jmdargere.cleansmart.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri

/**
 * Mode demonstration : de fausses photos, de faux mails et de faux contacts,
 * pour montrer l'appli (clients, captures d'ecran) sans rien toucher sur le telephone.
 */
object Demo {

    private const val JOUR = 24L * 3600 * 1000
    private val maintenant = System.currentTimeMillis()

    fun estDemo(uri: Uri) = uri.scheme == "demo"

    private fun item(n: Int, taille: Long, ilYa: Long, video: Boolean = false, note: String = "", chemin: String = "DCIM/Camera/") =
        MediaItem("d$n", Uri.parse("demo://photo/$n"), if (video) "VID_$n.mp4" else "IMG_$n.jpg", taille,
            maintenant - ilYa, video, chemin, "Camera", false, 4000, 3000, note)

    fun media(): MediaResult {
        var n = 0
        val dup = (1..6).map { g ->
            val a = item(++n, 3_200_000, g * 40 * JOUR); val b = item(++n, 3_200_000, g * 40 * JOUR - 60_000)
            MediaGroup(listOf(a, b), a.key)
        }
        val sim = (1..5).map { g ->
            val l = (0..2).map { k -> item(++n, 4_100_000 + k * 50_000L, g * 25 * JOUR - k * 2000) }
            MediaGroup(l, l[1].key)
        }
        val notes = listOf("Floue", "Trop sombre", "Floue", "Trop claire", "Floue", "Trop petite", "Trop sombre", "Floue")
        val rat = notes.mapIndexed { i, s -> item(++n, 2_800_000, i * 30 * JOUR, note = s) }
        val cap = (1..10).map { item(++n, 900_000, it * 20 * JOUR, chemin = "Pictures/Screenshots/") }
        val vid = (1..4).map { item(++n, (5 - it) * 220_000_000L, it * 60 * JOUR, video = true) }
        val wa = (1..12).map { item(++n, if (it % 4 == 0) 24_000_000 else 220_000, it * 15 * JOUR, video = it % 4 == 0, chemin = "Android/media/com.whatsapp/WhatsApp/Media/") }
        val tout = dup.flatMap { it.items } + sim.flatMap { it.items } + rat + cap + vid + wa
        return MediaResult(8423, 31_200_000_000, 312, 27_800_000_000, dup, sim, cap, vid, wa, rat,
            tout.sortedByDescending { it.size }.take(30))
    }

    fun recentes(): List<MediaItem> = (1..20).map { item(500 + it, 3_000_000, it * JOUR) }

    fun contacts() = ContactsResult(642, listOf(
        ContactDup("Marie Dupont", "06 12 34 56 78", 1L, listOf(2L)),
        ContactDup("Garage Martin", "01 23 45 67 89", 3L, listOf(4L, 5L)),
        ContactDup("Docteur Leroy", "04 98 76 54 32", 6L, listOf(7L)),
    ))

    val compte = MailAccount("demo@exemple.fr", "imap.exemple.fr", "GMAIL", true)

    fun mails() = MailResult(
        total = 18452, scanned = 3000, bytes = 2_400_000_000, newsletters = 1240, bigCount = 37, bigBytes = 612_000_000,
        oldCount = 5210, oldBytes = 820_000_000,
        senders = listOf(
            Sender("news@boutique.exemple", "Boutique Mode", 412, 41_000_000, true, maintenant, unsubscribe = "https://exemple.fr/desabonner"),
            Sender("promo@voyages.exemple", "Voyages Promo", 265, 33_000_000, true, maintenant, unsubscribe = "https://exemple.fr/desabonner"),
            Sender("noreply@reseau.exemple", "Réseau social", 198, 12_000_000, false, maintenant, notif = true),
            Sender("contact@banque.exemple", "Ma banque", 64, 9_000_000, false, maintenant, important = true),
            Sender("paul@exemple.fr", "Paul", 58, 120_000_000, false, maintenant),
            Sender("club@sport.exemple", "Club de sport", 41, 6_000_000, false, maintenant),
        ),
        bigList = listOf(BigMail("Famille", "Photos des vacances", 24_000_000, maintenant - 400 * JOUR)),
        factures = listOf(BigMail("Électricité", "Votre facture de novembre", 300_000, maintenant - 5 * JOUR)),
        colis = listOf(BigMail("Livraison", "Votre colis arrive demain", 80_000, maintenant - 2 * JOUR)),
        voyages = listOf(BigMail("Train", "Votre billet Paris - Lyon", 150_000, maintenant - 20 * JOUR)),
        doublons = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9).map { it.toLong() },
        quotaUsed = 12_400_000_000, quotaMax = 15_000_000_000,
    )

    /** Une petite image de paysage, toujours la meme pour une meme photo. */
    fun image(cle: String, taille: Int): Bitmap {
        val h = cle.hashCode()
        val palettes = listOf(
            intArrayOf(0xFF64B5F6.toInt(), 0xFFFFF59D.toInt(), 0xFF43A047.toInt()),
            intArrayOf(0xFFFF8A65.toInt(), 0xFFFFE082.toInt(), 0xFF6D4C41.toInt()),
            intArrayOf(0xFF4FC3F7.toInt(), 0xFFFFFFFF.toInt(), 0xFF0277BD.toInt()),
            intArrayOf(0xFF9575CD.toInt(), 0xFFFFCC80.toInt(), 0xFF37474F.toInt()),
            intArrayOf(0xFF81D4FA.toInt(), 0xFFFFF176.toInt(), 0xFF8BC34A.toInt()),
        )
        val p = palettes[Math.floorMod(h, palettes.size)]
        val b = Bitmap.createBitmap(taille, taille, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, taille.toFloat(), p[0], p[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, taille.toFloat(), taille.toFloat(), paint)
        paint.shader = null
        paint.color = Color.argb(230, 255, 255, 255)
        c.drawCircle(taille * (0.25f + Math.floorMod(h shr 3, 50) / 100f), taille * 0.28f, taille * 0.1f, paint)
        paint.color = p[2]
        val m = Path().apply {
            moveTo(0f, taille * 0.8f)
            lineTo(taille * (0.3f + Math.floorMod(h shr 5, 20) / 100f), taille * 0.45f)
            lineTo(taille * 0.6f, taille * 0.7f); lineTo(taille * 0.8f, taille * 0.5f)
            lineTo(taille.toFloat(), taille * 0.75f); lineTo(taille.toFloat(), taille.toFloat()); lineTo(0f, taille.toFloat()); close()
        }
        c.drawPath(m, paint)
        return b
    }
}
