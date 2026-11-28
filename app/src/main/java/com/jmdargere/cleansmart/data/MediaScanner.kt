package com.jmdargere.cleansmart.data

import com.jmdargere.cleansmart.Tr

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class MediaItem(
    val key: String,
    val uri: Uri,
    val name: String,
    val size: Long,
    val date: Long,
    val video: Boolean,
    val path: String,
    val bucket: String,
    val favorite: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    /** Petite etiquette affichee sur la miniature (ex. "Floue", "Trop sombre"). */
    val note: String = "",
)

/** Un groupe de fichiers semblables ; [keep] est celui que l'appli conseille de garder. */
data class MediaGroup(val items: List<MediaItem>, val keep: String)

data class MediaResult(
    val photoCount: Int,
    val photoBytes: Long,
    val videoCount: Int,
    val videoBytes: Long,
    val duplicates: List<MediaGroup>,
    val similar: List<MediaGroup>,
    val screens: List<MediaItem>,
    val bigVideos: List<MediaItem>,
    val whatsapp: List<MediaItem>,
    /** Photos floues, trop sombres ou trop claires (jamais pre-cochees). */
    val blurry: List<MediaItem> = emptyList(),
    /** Les 30 photos/videos les plus lourdes (pour information, jamais pre-cochees). */
    val biggest: List<MediaItem> = emptyList(),
)

/**
 * Analyse reelle des photos et videos (MediaStore). Ne supprime rien :
 * la mise a la corbeille passe par Android (MediaStore.createTrashRequest).
 */
class MediaScanner(ctx: Context) {

    private val cr: ContentResolver = ctx.contentResolver

    fun scan(s: Settings, progress: (String, Int) -> Unit): MediaResult {
        progress(Tr.t("Lecture de la galerie"), 2)
        val all = ArrayList<MediaItem>()
        load(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false, all)
        progress(Tr.t("Lecture des vidéos"), 8)
        load(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, all)

        // Les favoris ne sont jamais proposes si l'option est active
        val candidates = if (s.protectFavorites) all.filter { !it.favorite } else all

        val screens = candidates.filter { !it.video && isScreenshot(it) }.sortedBy { it.date }
        val whatsapp = if (s.includeWhatsapp)
            candidates.filter { it.path.contains("whatsapp", true) }.sortedByDescending { it.size } else emptyList()
        val bigLimit = s.bigVideoMb.toLong() * 1024 * 1024
        val bigVideos = candidates.filter { it.video && it.size >= bigLimit }.sortedByDescending { it.size }

        // --- Doublons exacts : meme taille, puis meme empreinte de contenu ---
        progress(Tr.t("Recherche des doublons"), 15)
        val bySize = all.filter { it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }
        val duplicates = ArrayList<MediaGroup>()
        val dupRemovable = HashSet<String>()
        bySize.forEachIndexed { i, g ->
            g.groupBy { contentHash(it) }
                .filterKeys { it != null }
                .values.filter { it.size > 1 }
                .forEach { same ->
                    // on garde un favori s'il y en a un, sinon le plus ancien (l'original)
                    val keep = same.firstOrNull { it.favorite } ?: same.minBy { it.date }
                    val removable = same.filter { it.key != keep.key && !(s.protectFavorites && it.favorite) }
                    if (removable.isNotEmpty()) {
                        duplicates += MediaGroup(listOf(keep) + removable, keep.key)
                        removable.forEach { dupRemovable += it.key }
                    }
                }
            if (i % 20 == 0) progress(Tr.t("Recherche des doublons"), 15 + 20 * i / bySize.size.coerceAtLeast(1))
        }

        // --- Photos similaires : rafales (quelques secondes d'ecart) qui se ressemblent ---
        progress(Tr.t("Recherche des photos similaires"), 35)
        val screenKeys = screens.map { it.key }.toHashSet()
        val photos = all.filter { !it.video && it.key !in screenKeys && it.key !in dupRemovable }.sortedBy { it.date }
        val burstMs = s.burstSeconds * 1000L
        val bursts = ArrayList<List<MediaItem>>()
        var cur = ArrayList<MediaItem>()
        for (it in photos) {
            if (cur.isNotEmpty() && it.date - cur.last().date > burstMs) {
                if (cur.size > 1) bursts += cur
                cur = ArrayList()
            }
            cur += it
        }
        if (cur.size > 1) bursts += cur

        val similar = ArrayList<MediaGroup>()
        bursts.forEachIndexed { bi, burst ->
            if (bi % 5 == 0) progress(Tr.t("Recherche des photos similaires"), 35 + 20 * bi / bursts.size.coerceAtLeast(1))
            val sig = HashMap<String, LongArray>()
            burst.forEach { item -> signature(item)?.let { sig[item.key] = it } }
            val groups = ArrayList<MutableList<MediaItem>>()
            for (item in burst) {
                val sg = sig[item.key] ?: continue
                val target = groups.firstOrNull {
                    java.lang.Long.bitCount(sig.getValue(it[0].key)[0] xor sg[0]) <= s.similarDistance
                }
                if (target != null) target += item else groups += mutableListOf(item)
            }
            for (g in groups) {
                if (g.size < 2) continue
                // yeux fermes : nombre de visages aux yeux fermes sur chaque photo de la rafale
                val fermes = g.associate { item -> item.key to (yeuxFermes(item) ?: 0) }
                // la meilleure = un favori, sinon celle ou personne n'a les yeux fermes, puis la plus nette, puis la plus lourde
                val best = g.firstOrNull { it.favorite }
                    ?: g.maxWith(compareBy<MediaItem>({ -fermes.getValue(it.key) }, { sig.getValue(it.key)[1] }, { it.size }))
                val removable = g.filter { it.key != best.key && !(s.protectFavorites && it.favorite) }
                    .map { if (fermes.getValue(it.key) > 0 && it.note.isEmpty()) it.copy(note = "Yeux fermés") else it }
                if (removable.isNotEmpty()) similar += MediaGroup(listOf(best) + removable, best.key)
            }
        }

        // --- Photos floues ou ratees (trop sombres / trop claires) ---
        progress(Tr.t("Recherche des photos floues"), 55)
        val blurry = findBlurry(
            candidates.filter { !it.video && it.key !in screenKeys && it.key !in dupRemovable }, s,
        ) { pct -> progress(Tr.t("Recherche des photos floues"), 55 + 43 * pct / 100) }

        progress(Tr.t("Terminé"), 100)
        return MediaResult(
            photoCount = all.count { !it.video },
            photoBytes = all.filter { !it.video }.sumOf { it.size },
            videoCount = all.count { it.video },
            videoBytes = all.filter { it.video }.sumOf { it.size },
            duplicates = duplicates,
            similar = similar,
            screens = screens,
            bigVideos = bigVideos,
            whatsapp = whatsapp,
            blurry = blurry,
            biggest = candidates.sortedByDescending { it.size }.take(30),
        )
    }

    /**
     * Mesure chaque photo sur une miniature (rapide, 4 en parallele) :
     *  - nettete = variance du laplacien : une photo floue n'a presque pas de contours nets ;
     *  - luminosite moyenne : presque noire (photo prise dans la poche) ou presque blanche.
     * Pour ne pas accuser a tort les photos douces (ciel, brume, portrait), une photo n'est
     * "floue" que si elle est tres en dessous du seuil ET parmi les moins nettes de la galerie.
     */
    private fun findBlurry(all: List<MediaItem>, s: Settings, progress: (Int) -> Unit): List<MediaItem> {
        if (all.isEmpty()) return emptyList()
        // trop petites : connues tout de suite par leur taille en pixels
        val small = all.filter { it.width > 0 && it.height > 0 && it.width.toLong() * it.height < s.smallPixels }
            .map { it.copy(note = "Trop petite") }
        val smallKeys = small.map { it.key }.toHashSet()
        val photos = all.filter { it.key !in smallKeys }
        if (photos.isEmpty()) return small
        val pool = Executors.newFixedThreadPool(4)
        val done = AtomicInteger(0)
        val results = try {
            pool.invokeAll(photos.map { item ->
                Callable {
                    val q = quality(item)
                    val n = done.incrementAndGet()
                    if (n % 50 == 0) progress(100 * n / photos.size)
                    item to q
                }
            }).mapNotNull { f -> runCatching { f.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
        val measured = results.mapNotNull { (item, q) -> q?.let { item to it } }
        if (measured.isEmpty()) return small

        // seuil relatif : une fraction de la nettete mediane de la galerie
        val sharpSorted = measured.map { it.second[0] }.sorted()
        val median = sharpSorted[sharpSorted.size / 2]
        val blurLimit = minOf(s.blurAbsolute, median * s.blurRelative)

        return (small + measured.mapNotNull { (item, q) ->
            val (sharp, mean) = q[0] to q[1]
            when {
                mean < s.darkLimit -> item.copy(note = "Trop sombre")
                mean > s.brightLimit -> item.copy(note = "Trop claire")
                sharp < blurLimit -> item.copy(note = "Floue")
                else -> null
            }
        }).sortedBy { it.date }
    }

    /** [0] = nettete (variance du laplacien), [1] = luminosite moyenne (0-255). */
    private fun quality(it: MediaItem): DoubleArray? {
        var bmp: Bitmap? = null
        return try {
            bmp = software(cr.loadThumbnail(it.uri, Size(320, 320), null))
            val w = bmp.width
            val h = bmp.height
            if (w < 16 || h < 16) return null
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val g = IntArray(w * h) { i -> gray(px[i]) }
            var sum = 0.0; var sum2 = 0.0; var lum = 0.0; var cnt = 0
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val c = y * w + x
                val lap = (g[c - w] + g[c + w] + g[c - 1] + g[c + 1] - 4 * g[c]).toDouble()
                sum += lap; sum2 += lap * lap; lum += g[c]; cnt++
            }
            val mean = sum / cnt
            doubleArrayOf(sum2 / cnt - mean * mean, lum / cnt)
        } catch (e: Exception) {
            null
        } finally {
            bmp?.recycle()
        }
    }


    /** Les photos les plus recentes, pour le tri rapide (favoris exclus si l'option est active). */
    fun recentPhotos(s: Settings, max: Int): List<MediaItem> {
        val all = ArrayList<MediaItem>()
        load(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false, all)
        return all.filter { !(s.protectFavorites && it.favorite) }.sortedByDescending { it.date }.take(max)
    }

    /** Photos et videos actuellement dans la corbeille Android (elles prennent encore de la place). */
    fun trashed(): List<MediaItem> {
        val all = ArrayList<MediaItem>()
        load(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false, all, trashed = true)
        load(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, all, trashed = true)
        return all
    }

    /** Toutes les photos et videos (pour la sauvegarde complete). */
    fun allMedia(): List<MediaItem> {
        val all = ArrayList<MediaItem>()
        load(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false, all)
        load(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, all)
        return all
    }

    private fun isScreenshot(it: MediaItem): Boolean {
        val p = it.path.lowercase()
        val n = it.name.lowercase()
        return p.contains("screenshot") || n.startsWith("screenshot") || p.contains("captures d")
    }

    private fun load(base: Uri, video: Boolean, out: MutableList<MediaItem>, trashed: Boolean = false) {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_TAKEN))
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        cr.query(base, cols, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val taken = c.getLong(3)
                out += MediaItem(
                    key = (if (video) "v" else "i") + id,
                    uri = ContentUris.withAppendedId(base, id),
                    name = c.getString(1) ?: "sans_nom",
                    size = c.getLong(2),
                    date = if (taken > 0) taken else c.getLong(4) * 1000L,
                    video = video,
                    path = c.getString(5) ?: "",
                    bucket = c.getString(6) ?: "",
                    favorite = c.getInt(7) == 1,
                    width = c.getInt(8),
                    height = c.getInt(9),
                )
            }
        }
    }

    /** Empreinte rapide : debut + fin du fichier + taille. */
    private fun contentHash(it: MediaItem): String? = try {
        cr.openInputStream(it.uri)?.use { input ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(64 * 1024)
            val limit = 1024L * 1024
            var read = 0L
            while (read < limit) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n); read += n
            }
            if (it.size > 3 * limit) {
                var skip = it.size - limit - read
                while (skip > 0) { val s = input.skip(skip); if (s <= 0) break; skip -= s }
                while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
            }
            md.update(it.size.toString().toByteArray())
            md.digest().joinToString("") { b -> "%02x".format(b) }
        }
    } catch (e: Exception) { null }

    /** Visages aux yeux fermes (sur une miniature de 640 px, suffisant pour les visages). */
    private fun yeuxFermes(it: MediaItem): Int? {
        var bmp: Bitmap? = null
        return try {
            bmp = software(cr.loadThumbnail(it.uri, Size(640, 640), null))
            Yeux.fermes(bmp)
        } catch (e: Exception) { null } finally { bmp?.recycle() }
    }

    /** [0] = empreinte visuelle (dHash 64 bits), [1] = nettete (variance du laplacien). */
    private fun signature(it: MediaItem): LongArray? {
        var bmp: Bitmap? = null
        return try {
            bmp = software(cr.loadThumbnail(it.uri, Size(256, 256), null))
            val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            var hash = 0L
            for (y in 0 until 8) for (x in 0 until 8) {
                hash = hash shl 1
                if (gray(small.getPixel(x, y)) > gray(small.getPixel(x + 1, y))) hash = hash or 1L
            }
            small.recycle()

            val g = Bitmap.createScaledBitmap(bmp, 96, 96, true)
            val px = IntArray(96 * 96)
            g.getPixels(px, 0, 96, 0, 0, 96, 96)
            g.recycle()
            var sum = 0.0; var sum2 = 0.0; var cnt = 0
            for (y in 1 until 95) for (x in 1 until 95) {
                val lap = gray(px[(y - 1) * 96 + x]) + gray(px[(y + 1) * 96 + x]) +
                        gray(px[y * 96 + x - 1]) + gray(px[y * 96 + x + 1]) - 4.0 * gray(px[y * 96 + x])
                sum += lap; sum2 += lap * lap; cnt++
            }
            val mean = sum / cnt
            longArrayOf(hash, (sum2 / cnt - mean * mean).toLong())
        } catch (e: Exception) {
            null
        } finally {
            bmp?.recycle()
        }
    }

    private fun software(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle() } else b

    private fun gray(c: Int): Int =
        (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
}
