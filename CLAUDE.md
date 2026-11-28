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

## Version 2.0 (branche claude/v2-auto, en test, pas publiée)
- Accueil : tuile verte « Ménage complet » (photos → téléphone → mails) + 3 portes : 📸 Photos (`Ecran.PHOTOS`),
  🧹 Téléphone (`ui/Inutiles.kt`, `data/Inutiles.kt`), ✉️ Mails. Quitter Google/iCloud dans une carte plus discrète.
- Google/iCloud : mode « Tout automatique » (`data/AutoGoogle.kt`, service de premier plan, surveille Download, MANAGE_EXTERNAL_STORAGE),
  « Libérer Google » bloqué tant que la copie n'est pas complète, « Vérifier le disque », rappel tous les 2 mois (Night).
- Rafales : yeux fermés écartés (ML Kit face-detection, `data/Yeux.kt`). Sablier repris de Nettoyage Pro (`Sablier`).
- Mails : rien ne part sans la liste à relire (`MailRevueDialog`, `vm.mailVoir`), règles par expéditeur toujours jeter /
  toujours garder / me prévenir (`data/Regles.kt`, alertes toutes les 15 min), boîte protégée « Protégés/Banque… Famille »
  (Gmail = libellé, autres = déplacé), Spam vers la corbeille, bilan téléphone / boîte mail.
- Version PC à part : `Documents/CLEAN_SMART_PC` (Python/Tkinter → .exe, publiée en pc-1.3).

## Version 1.8
- Interface en anglais : les textes restent en français dans le code, enveloppés dans `Tr.t("...", args)` (`Tr.kt`) ;
  dictionnaire `TrEn.kt` généré. Pour un nouveau texte : `python store/i18n/i18n.py extract . cles.json`, ajouter la
  traduction dans `store/i18n/en.py`, puis `python store/i18n/gen_tren.py store/i18n/en.py cles.json app/src/main/java/com/jmdargere/cleansmart/TrEn.kt`
  (et `i18n.py apply .` pour envelopper les nouveaux textes). Option Langue : auto / français / anglais.
- Widget (`Widget.kt`, place libre + bouton Ménage), mode démonstration (`data/Demo.kt`, actions simulées),
  « Quitter iCloud Photos » (même moteur `GoogleExport` avec `SourceExport.ICLOUD`, dates du « Photo Details.csv »).

## Version 1.7
- Google Photos = un seul tableau de bord (circuit animé, jauge, 5 étapes repliables, liste des fichiers 001…,
  modèle de la page Takeout, notification de fin de fichier, fichier déjà fait détecté).
- Moteur Google : 1 seule lecture via la table des matières (commons-compress), repli pas à pas si ZIP incomplet,
  noms gardés en mémoire, morceaux de 1 Mo, netteté calculée en parallèle. Sauvegarde normale accélérée aussi.
- Accueil de bienvenue (4 pages) au 1er lancement ; mise à jour en un geste (`data/MiseAJour.kt` + `version.json` publié
  par le workflow, vérifié 1 fois par jour, bandeau orange sur l'accueil).
- À faire ensuite (demandé « fais tout ») : interface anglaise, « Quitter iCloud Photos », mode démo, widget.

## Version 1.6 : onglet « Quitter Google Photos » (`data/GoogleExport.kt`, `ui/GooglePhotos.kt`)
- Google n'autorise plus la lecture de toute la photothèque par une appli : on passe par Google Takeout (ZIP de 2 Go).
- 7 écrans : bienvenue, place nécessaire, disque, export Takeout, traitement ZIP par ZIP, rapport, libérer (manuel).
- Range par année (`Google Photos/AAAA/AAAA-MM-JJ_nom`), dates depuis les .json Google (sinon EXIF), doublons par MD5,
  rafales (15 s + dHash) : la plus nette gardée, chaque copie relue et comparée, Gmail .mbox → `Mails Gmail/AAAA` + PDF + index.html.
- Sécurités : vérifs avant (téléphone ≥ 300 Mo, place disque ≥ taille ZIP, disque accessible, batterie), vidéos copiées
  directement sur le disque, empreintes mémorisées une par une (reprise sans doublon), arrêt propre si plein, ZIP abîmé détecté,
  écran gardé allumé, ZIP supprimable seulement si tout est vérifié, écran « Si ça bloque ».
- Idées proposées non faites : interface anglaise, « Quitter iCloud Photos », mode démo, widget, yeux fermés, vérif mensuelle.

## Version 1.5
- Interface claire comme Mail Simple / Photo Simple : bandeau bleu, grandes tuiles de couleur, sablier avec
  temps passé / restant / %, bandeau vert « TERMINÉ ».
- Ménage guidé photos (4 étapes : ménage sûr, vérifier en glissant, sauvegarder SEULEMENT les bonnes + « Mes meilleures »,
  libérer) et Ménage guidé mails (ménage sûr, tri par expéditeur, mettre l'important à l'abri, libérer) + « Se désabonner ».
- La sauvegarde (manuelle et de nuit) ne copie jamais les doublons, rafales en trop, ratées, captures.
- « Sauvegarder avant de supprimer » désactivé par défaut.

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
