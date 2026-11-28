package com.jmdargere.cleansmart.data

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * Yeux fermes (2.0) : detecteur de visages de Google (ML Kit), integre a l'appli, sans Internet.
 * Utilise seulement dans les rafales, pour garder la photo ou tout le monde a les yeux ouverts.
 */
object Yeux {
    private val detecteur by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)   // probabilite yeux ouverts
                .setMinFaceSize(0.12f)
                .build()
        )
    }

    /**
     * Nombre de visages qui ont les yeux fermes sur la photo (0 = personne, ou pas de visage).
     * null si l'analyse est impossible. A appeler hors du fil principal.
     */
    fun fermes(bmp: Bitmap): Int? = runCatching {
        val visages = Tasks.await(detecteur.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS)
        visages.count { v ->
            val g = v.leftEyeOpenProbability; val d = v.rightEyeOpenProbability
            g != null && d != null && g < 0.3f && d < 0.3f      // les deux yeux fermes (pas un clin d'oeil de profil)
        }
    }.getOrNull()
}
