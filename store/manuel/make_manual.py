"""
Fabrique le mode d'emploi de Clean Smart en francais et en anglais :
    app/src/main/assets/manuel_fr.pdf
    app/src/main/assets/manuel_en.pdf

Usage :  python store/manuel/make_manual.py      (depuis le dossier CLEAN_SMART)
Besoin : pip install reportlab
"""
import os
from reportlab.lib.pagesizes import A5
from reportlab.lib.units import mm
from reportlab.lib.colors import HexColor, white
from reportlab.pdfgen import canvas

FOND = HexColor("#0F0F1E")
CARTE = HexColor("#1B1B33")
VIOLET = HexColor("#6A5CFF")
VIOLET2 = HexColor("#9D93FF")
GRIS = HexColor("#A3A3C7")
VERT = HexColor("#2ECC8F")

TEXTS = {
    "fr": {
        "title": "Clean Smart",
        "subtitle": "Mode d'emploi",
        "tag": "Libère de l'espace sur ton téléphone, simplement et sans risque.",
        "pages": [
            ("1. Analyser", [
                "Ouvre Clean Smart et appuie sur le gros bouton « Analyser mon téléphone ».",
                "La première fois, Android te demande l'accès aux photos et aux contacts : accepte.",
                "En quelques secondes, Clean Smart te montre l'espace que tu peux récupérer.",
                "Rien n'est supprimé pendant l'analyse.",
            ]),
            ("2. Ce que Clean Smart trouve", [
                "Doublons : copies identiques d'une même photo ou vidéo. L'original est gardé.",
                "Photos similaires : rafales prises à quelques secondes d'écart. La plus nette est gardée.",
                "Captures d'écran : souvent inutiles après coup.",
                "Grosses vidéos : plus de 100 Mo chacune.",
                "Médias WhatsApp : photos et vidéos reçues ou envoyées.",
                "Contacts en double : même nom et même numéro.",
            ]),
            ("3. Choisir et nettoyer", [
                "Touche une catégorie pour voir les miniatures.",
                "Touche une image pour la cocher ou la décocher. Appui long : la voir en grand.",
                "Les photos marquées « GARDÉE » sont celles que Clean Smart te conseille de garder.",
                "Appuie sur le bouton rouge « Nettoyer » en bas de l'écran.",
                "Android te demande une dernière confirmation.",
                "Les fichiers vont dans la corbeille de la Galerie : tu peux les récupérer pendant 30 jours.",
            ]),
            ("4. Photos ratées, tri rapide, corbeille", [
                "Photos ratées : floues, trop sombres, trop claires ou trop petites. Vérifie avant de cocher.",
                "Tri rapide : une photo à la fois. Glisse à gauche pour jeter, à droite pour garder.",
                "« J'ai fait une bêtise » remet dans la galerie les derniers fichiers mis à la corbeille.",
                "La corbeille prend encore de la place : « Vider la corbeille » la libère pour de bon.",
                "Dans une catégorie : « Envoyer » (WhatsApp, mail…) ou « Protéger » (la photo passe",
                "en favori et ne sera plus jamais proposée).",
            ]),
            ("5. La sauvegarde", [
                "Dans « Sauvegarde », choisis un dossier : carte SD, clé USB ou Google Drive.",
                "« Sauvegarder toutes mes photos » copie tes photos et vidéos, rangées par mois.",
                "Les fois suivantes, seules les nouvelles photos sont copiées.",
                "Option « Sauvegarder avant de supprimer » (activée par défaut) : avant chaque",
                "nettoyage, une copie est faite automatiquement. Les contacts sont exportés en .vcf.",
            ]),
            ("6. La boîte mail", [
                "Touche « Connecter ma boîte mail » et choisis ta messagerie.",
                "Gmail, Yahoo et iCloud demandent un MOT DE PASSE D'APPLICATION (pas ton mot de passe",
                "habituel). Clean Smart t'explique comment le créer et ouvre la bonne page.",
                "Analyse ta boîte puis, en un bouton : newsletters, gros mails (+ 5 Mo) ou mails de plus de 2 ans.",
                "Les mails vont dans la corbeille de ta messagerie : rien n'est effacé directement.",
                "Ton mot de passe reste chiffré sur ton téléphone.",
            ]),
            ("7. Sauvegarder des mails", [
                "Dans Mails, touche « Sauvegarder des mails ».",
                "Clean Smart cherche les mails qui ont des pièces jointes.",
                "Coche ceux à garder (bouton « Cocher les PDF » pour aller vite).",
                "Appuie sur « Sauvegarder » : dans ton dossier de sauvegarde, CleanSmart/Mails contient",
                "le mail complet (.eml), CleanSmart/PDF réunit tous les PDF, et CleanSmart/Pièces jointes",
                "les autres fichiers.",
            ]),
            ("8. Les options", [
                "Le bouton en haut à droite ouvre les options :",
                "- le dossier de sauvegarde, et sauvegarder avant de supprimer ;",
                "- protéger mes favoris : les photos en favori ne sont jamais proposées ;",
                "- la sévérité : prudente (moins de photos proposées), normale ou stricte ;",
                "- la taille du texte : normale, gros ou très gros.",
                "Tu y trouves aussi le compteur d'espace libéré et ce mode d'emploi.",
            ]),
        ],
        "safety": "Ta sécurité d'abord : pas de suppression sans ton accord, tout passe par une corbeille, "
                  "et aucune donnée n'est envoyée ailleurs que vers ta propre messagerie.",
        "footer": "Clean Smart - JM Dargère",
    },
    "en": {
        "title": "Clean Smart",
        "subtitle": "User guide",
        "tag": "Free up space on your phone, simply and safely.",
        "pages": [
            ("1. Scan", [
                "Open Clean Smart and tap the big \"Analyser mon téléphone\" (Scan my phone) button.",
                "The first time, Android asks for access to photos and contacts: allow it.",
                "Within seconds, Clean Smart shows how much space you can get back.",
                "Nothing is deleted during the scan.",
            ]),
            ("2. What Clean Smart finds", [
                "Duplicates: identical copies of a photo or video. The original is kept.",
                "Similar photos: bursts taken seconds apart. The sharpest one is kept.",
                "Screenshots: often useless afterwards.",
                "Large videos: over 100 MB each.",
                "WhatsApp media: photos and videos received or sent.",
                "Duplicate contacts: same name and same number.",
            ]),
            ("3. Choose and clean", [
                "Tap a category to see the thumbnails.",
                "Tap a picture to select or unselect it. Long press: view it full screen.",
                "Pictures marked \"GARDÉE\" (kept) are the ones Clean Smart advises you to keep.",
                "Tap the red \"Nettoyer\" (Clean) button at the bottom of the screen.",
                "Android asks for a final confirmation.",
                "Files go to the Gallery bin: you can restore them for 30 days.",
            ]),
            ("4. Failed photos, quick sort, bin", [
                "Failed photos: blurry, too dark, too bright or too small. Check before ticking.",
                "Quick sort: one photo at a time. Swipe left to discard, right to keep.",
                "\"J'ai fait une bêtise\" (I made a mistake) puts the last binned files back in the gallery.",
                "The bin still takes space: \"Vider la corbeille\" (Empty the bin) frees it for good.",
                "In a category: \"Envoyer\" (Send: WhatsApp, email…) or \"Protéger\" (Protect: the photo",
                "becomes a favourite and is never suggested again).",
            ]),
            ("5. Backup", [
                "In \"Sauvegarde\" (Backup), choose a folder: SD card, USB drive or Google Drive.",
                "\"Back up all my photos\" copies your photos and videos, sorted by month.",
                "Next time, only new photos are copied.",
                "\"Back up before deleting\" option (on by default): before each clean-up,",
                "a copy is made automatically. Contacts are exported as a .vcf file.",
            ]),
            ("6. Mailbox", [
                "Tap \"Connecter ma boîte mail\" (Connect my mailbox) and pick your provider.",
                "Gmail, Yahoo and iCloud require an APP PASSWORD (not your usual password).",
                "Clean Smart explains how to create one and opens the right page.",
                "Scan your inbox, then in one tap: newsletters, large emails (5 MB+) or emails older than 2 years.",
                "Emails go to your mailbox bin: nothing is erased directly.",
                "Your password stays encrypted on your phone.",
            ]),
            ("7. Save emails", [
                "In Mails, tap \"Sauvegarder des mails\" (Save emails).",
                "Clean Smart finds the emails that have attachments.",
                "Tick the ones to keep (\"Cocher les PDF\" button to go fast).",
                "Tap \"Sauvegarder\" (Save): in your backup folder, CleanSmart/Mails holds the full",
                "email (.eml), CleanSmart/PDF gathers all PDFs, and CleanSmart/Pieces jointes",
                "the other files.",
            ]),
            ("8. Options", [
                "The button at the top right opens the options:",
                "- the backup folder, and back up before deleting;",
                "- protect my favourites: favourite photos are never suggested;",
                "- severity: careful (fewer photos suggested), normal or strict;",
                "- text size: normal, large or very large.",
                "You will also find the freed-space counter and this guide.",
            ]),
        ],
        "safety": "Your safety first: nothing is deleted without your consent, everything goes through a bin, "
                  "and no data is sent anywhere except to your own mail provider.",
        "footer": "Clean Smart - JM Dargère",
    },
}

W, H = A5


def wrap(c, text, font, size, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if c.stringWidth(t, font, size) <= width:
            cur = t
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def background(c):
    c.setFillColor(FOND)
    c.rect(0, 0, W, H, stroke=0, fill=1)


def sparkle(c, x, y, r, color):
    c.setFillColor(color)
    p = c.beginPath()
    k = r * 0.22
    p.moveTo(x, y + r); p.lineTo(x + k, y + k); p.lineTo(x + r, y); p.lineTo(x + k, y - k)
    p.lineTo(x, y - r); p.lineTo(x - k, y - k); p.lineTo(x - r, y); p.lineTo(x - k, y + k)
    p.close()
    c.drawPath(p, stroke=0, fill=1)


def footer(c, t, n):
    c.setFillColor(GRIS)
    c.setFont("Helvetica", 8)
    c.drawString(14 * mm, 8 * mm, t["footer"])
    c.drawRightString(W - 14 * mm, 8 * mm, str(n))


def build(lang, path):
    t = TEXTS[lang]
    c = canvas.Canvas(path, pagesize=A5)
    c.setTitle(f"{t['title']} - {t['subtitle']}")
    c.setAuthor("JM Dargère")

    # --- couverture ---
    background(c)
    c.setFillColor(VIOLET)
    c.roundRect(W / 2 - 22 * mm, H - 95 * mm, 44 * mm, 44 * mm, 10 * mm, stroke=0, fill=1)
    sparkle(c, W / 2, H - 73 * mm, 14 * mm, white)
    sparkle(c, W / 2 + 11 * mm, H - 62 * mm, 4.5 * mm, HexColor("#DDD8FF"))
    c.setFillColor(white)
    c.setFont("Helvetica-Bold", 30)
    c.drawCentredString(W / 2, H - 118 * mm, t["title"])
    c.setFillColor(VIOLET2)
    c.setFont("Helvetica", 16)
    c.drawCentredString(W / 2, H - 128 * mm, t["subtitle"])
    c.setFillColor(GRIS)
    c.setFont("Helvetica", 10)
    for i, line in enumerate(wrap(c, t["tag"], "Helvetica", 10, W - 40 * mm)):
        c.drawCentredString(W / 2, H - 142 * mm - i * 13, line)
    c.showPage()

    # --- pages ---
    for n, (title, lines) in enumerate(t["pages"], start=2):
        background(c)
        c.setFillColor(VIOLET)
        c.rect(0, H - 26 * mm, W, 26 * mm, stroke=0, fill=1)
        c.setFillColor(white)
        c.setFont("Helvetica-Bold", 18)
        c.drawString(14 * mm, H - 16 * mm, title)

        y = H - 40 * mm
        card_w = W - 24 * mm
        for line in lines:
            wl = wrap(c, line, "Helvetica", 11, card_w - 16 * mm)
            box_h = 8 * mm + len(wl) * 14
            c.setFillColor(CARTE)
            c.roundRect(12 * mm, y - box_h + 4 * mm, card_w, box_h, 4 * mm, stroke=0, fill=1)
            c.setFillColor(VIOLET2)
            c.circle(18 * mm, y - 1.5 * mm, 1.4 * mm, stroke=0, fill=1)
            c.setFillColor(white)
            c.setFont("Helvetica", 11)
            for i, l in enumerate(wl):
                c.drawString(22 * mm, y - 3 * mm - i * 14, l)
            y -= box_h + 3 * mm
        footer(c, t, n)
        c.showPage()

    # --- securite ---
    background(c)
    c.setFillColor(VERT)
    c.roundRect(12 * mm, H / 2 - 30 * mm, W - 24 * mm, 60 * mm, 6 * mm, stroke=0, fill=1)
    c.setFillColor(HexColor("#002211"))
    c.setFont("Helvetica-Bold", 12)
    for i, l in enumerate(wrap(c, t["safety"], "Helvetica-Bold", 12, W - 40 * mm)):
        c.drawCentredString(W / 2, H / 2 + 16 * mm - i * 16, l)
    footer(c, t, len(t["pages"]) + 2)
    c.showPage()
    c.save()


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    assets = os.path.normpath(os.path.join(here, "..", "..", "app", "src", "main", "assets"))
    os.makedirs(assets, exist_ok=True)
    for lang in ("fr", "en"):
        out = os.path.join(assets, f"manuel_{lang}.pdf")
        build(lang, out)
        print("OK", out)
