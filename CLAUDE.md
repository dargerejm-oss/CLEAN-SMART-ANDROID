# Consignes pour Claude — CLEAN SMART

> **RÈGLE N° 1 : ÉCRIRE UNIQUEMENT EN FRANÇAIS**, y compris les messages pendant le travail.

## L'appli
- Android natif : **Kotlin + Jetpack Compose** (pas de WebView). Package `com.jmdargere.cleansmart`, minSdk 30.
- Doit rester **très simple** : un gros bouton « Analyser », quelques cartes, un bouton « Nettoyer ».
  Seulement 3 options (dossier de sauvegarde, sauvegarder avant de supprimer, protéger les favoris).
- Jamais de suppression directe : photos → corbeille Android (`MediaStore.createTrashRequest`),
  mails → corbeille du compte (IMAP MOVE), contacts → export .vcf avant suppression si la sauvegarde est active.
- Code : `data/` (moteur : MediaScanner, MailCleaner, ContactsCleaner, Backup, Settings), `AppViewModel.kt`, `ui/` (écrans).

## Mode d'emploi
- PDF intégrés FR + EN dans `app/src/main/assets/`, affichés dans l'appli (écran `ui/Manual.kt`).
- Pour les modifier : éditer `store/manuel/make_manual.py` puis `python store/manuel/make_manual.py`.

## Publication
- GitHub construit l'APK et l'AAB à chaque envoi (`.github/workflows/build.yml`).
- Clé de signature privée dans les secrets GitHub `KEYSTORE_B64` et `KEYSTORE_PASSWORD` (jamais dans le dépôt).
- Augmenter `versionCode` et `versionName` dans `app/build.gradle` à chaque nouvelle version.
- Lien de mise à jour : https://github.com/dargerejm-oss/CLEAN-SMART-ANDROID/releases/latest/download/CLEAN_SMART.apk

## Style des réponses
- Expliquer simplement, sans jargon ; l'utilisateur teste sur son téléphone Android.
