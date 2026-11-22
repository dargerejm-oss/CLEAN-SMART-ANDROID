# Consignes pour Claude — CLEAN SMART

> **RÈGLE N° 1 : ÉCRIRE UNIQUEMENT EN FRANÇAIS**, y compris les messages pendant le travail.

## L'appli
- Android natif : **Kotlin + Jetpack Compose** (pas de WebView). Package `com.jmdargere.cleansmart`, minSdk 30.
- Doit rester **très simple** pour les clients : un gros bouton « Analyser », quelques cartes, un bouton « Nettoyer ».
  Les outils en plus vont dans la carte repliée « Plus d'outils » ; les options restent courtes et expliquées.
- Ne publier (git push) qu'à la fin d'un lot de travail, quand l'utilisateur le demande.
- Jamais de suppression directe : photos → corbeille Android (`MediaStore.createTrashRequest`),
  mails → corbeille du compte (IMAP MOVE), contacts → export .vcf avant suppression si la sauvegarde est active.
- Code : `data/` (moteur : MediaScanner, MailCleaner, ContactsCleaner, Backup, Settings), `AppViewModel.kt`, `ui/` (écrans).

## Fonctions (version 1.3)
- Photos : doublons, rafales, ratées (floues/sombres/claires/petites, sévérité réglable), captures, grosses vidéos,
  WhatsApp, plus gros fichiers, tri rapide (glisser), corbeille (vider), « J'ai fait une bêtise », envoyer, protéger
  (= favori Android), compression vidéo 720p (Media3), PDF souvenir, « Libérer la place » (photos > N mois déjà sauvegardées).
- Nuit (`data/Night.kt`, WorkManager) : sauvegarde auto, alerte < 1 Go, rapport du mois.
- Mails : newsletters, gros, vieux, doublons ; importants jamais touchés (`MailTri`) ; factures/colis/voyages (lecture
  seule) ; quota ; remettre depuis la corbeille ; sauvegarde de mails choisis + archive `CleanSmart/Mails/index.html`.
- Reprend l'esprit de Mail Simple et Photo Simple : l'essentiel à l'écran, le reste dans « Plus d'outils ».
- Pas encore repris : yeux fermés, RAW+JPEG, rapatrier une autre boîte, mail attendu, expéditeurs bloqués la nuit,
  sauvegarde Wi-Fi vers le PC (SMB).

## Mode d'emploi
- PDF intégrés FR + EN dans `app/src/main/assets/`, affichés dans l'appli (écran `ui/Manual.kt`).
- Pour les modifier : éditer `store/manuel/make_manual.py` puis `python store/manuel/make_manual.py`.

## Publication
- GitHub construit l'APK et l'AAB à chaque envoi (`.github/workflows/build.yml`).
- Clé de signature privée dans les secrets GitHub `KEYSTORE_B64` et `KEYSTORE_PASSWORD` (jamais dans le dépôt).
- Augmenter `versionCode` et `versionName` dans `app/build.gradle` à chaque nouvelle version.
- Deux versions (flavors Gradle) : `complet` → CLEAN_SMART.apk et `essai` (30 jours, `data/Essai.kt`) → CLEAN_SMART_TEST_30_JOURS.apk.
- Liens : https://github.com/dargerejm-oss/CLEAN-SMART-ANDROID/releases/latest/download/CLEAN_SMART.apk
  et https://github.com/dargerejm-oss/CLEAN-SMART-ANDROID/releases/latest/download/CLEAN_SMART_TEST_30_JOURS.apk

## Style des réponses
- Expliquer simplement, sans jargon ; l'utilisateur teste sur son téléphone Android.
