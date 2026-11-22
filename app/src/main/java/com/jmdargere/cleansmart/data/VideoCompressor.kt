package com.jmdargere.cleansmart.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import androidx.media3.common.MediaItem as Media3Item

/**
 * Compression d'une video en 720p (H.264, ~4 Mbit/s) avec Media3 Transformer.
 * La nouvelle video est enregistree dans Films / Clean Smart. L'original n'est PAS touche ici :
 * l'appli propose ensuite de le mettre a la corbeille (recuperable 30 jours).
 * Retourne la taille du nouveau fichier.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoCompressor {

    suspend fun compress(ctx: Context, v: MediaItem, progress: (Int) -> Unit): Long {
        val out = File(ctx.cacheDir, "cs_${v.key}.mp4")
        out.delete()

        // Transformer doit etre utilise depuis le fil principal
        withContext(Dispatchers.Main) {
            var finished = false
            var error: Exception? = null
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(4_000_000).build())
                        .build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { finished = true }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error = exportException; finished = true
                    }
                })
                .build()
            val edited = EditedMediaItem.Builder(Media3Item.fromUri(v.uri))
                .setEffects(Effects(listOf(), listOf(Presentation.createForHeight(720))))
                .build()
            transformer.start(edited, out.path)
            val holder = ProgressHolder()
            while (!finished) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress)
                delay(500)
            }
            error?.let { throw IllegalStateException("compression impossible (${it.message ?: "erreur inconnue"})") }
        }

        val newSize = out.length()
        if (newSize <= 0 || newSize >= v.size * 0.9) { out.delete(); return newSize }

        // enregistrement dans la galerie (Films / Clean Smart)
        withContext(Dispatchers.IO) {
            val name = v.name.substringBeforeLast('.') + "_compressee.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Clean Smart")
                if (v.date > 0) put(MediaStore.Video.Media.DATE_TAKEN, v.date)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("enregistrement impossible")
            cr.openOutputStream(uri)?.use { o -> out.inputStream().use { it.copyTo(o) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            out.delete()
        }
        return newSize
    }
}
