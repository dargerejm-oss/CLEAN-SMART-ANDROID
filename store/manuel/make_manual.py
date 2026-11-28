"""
Fabrique le mode d'emploi illustre de Clean Smart, en francais et en anglais :
    app/src/main/assets/manuel_fr.pdf
    app/src/main/assets/manuel_en.pdf

Usage :  python store/manuel/make_manual.py      (depuis le dossier CLEAN_SMART, sous Windows)
Besoin : pip install reportlab pillow
         polices Windows : Segoe UI (texte) et Segoe UI Emoji (icones en couleur)
"""
import os
import tempfile

from PIL import Image, ImageDraw, ImageFont
from reportlab.lib.colors import HexColor, white
from reportlab.lib.pagesizes import A5
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

FONTS = r"C:\Windows\Fonts"
pdfmetrics.registerFont(TTFont("UI", os.path.join(FONTS, "segoeui.ttf")))
pdfmetrics.registerFont(TTFont("UIB", os.path.join(FONTS, "segoeuib.ttf")))
pdfmetrics.registerFont(TTFont("UISB", os.path.join(FONTS, "seguisb.ttf")))
EMOJI_FONT = os.path.join(FONTS, "seguiemj.ttf")

W, H = A5                      # 420 x 595 points
M = 30                         # marge
VIOLET = HexColor("#6A5CFF")
NUIT = HexColor("#0F0F1E")
ENCRE = HexColor("#1E1E2E")
GRIS = HexColor("#6B6B80")
FOND = HexColor("#F6F5FF")
VERT_TXT = HexColor("#1F7A4D")
COUL = {
    "vert": HexColor("#2E7D32"), "turquoise": HexColor("#00838F"), "bleu": HexColor("#1E4FB4"), "rouge": HexColor("#C62828"),
    "violet": HexColor("#6A1B9A"), "orange": HexColor("#EF6C00"), "indigo": HexColor("#5B4BE0"),
}

# ---------------------------------------------------------------- icones (emoji en couleur)

_EMOJI_DIR = tempfile.mkdtemp(prefix="cs_emoji_")
_EMOJI_CACHE = {}


def emoji_png(e):
    """Dessine un emoji en couleur (Segoe UI Emoji) et renvoie le chemin d'une image PNG carree."""
    if e in _EMOJI_CACHE:
        return _EMOJI_CACHE[e]
    font = ImageFont.truetype(EMOJI_FONT, 109)
    img = Image.new("RGBA", (300, 200), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((20, 20), e, font=font, embedded_color=True)
    box = img.getbbox() or (0, 0, 1, 1)
    img = img.crop(box)
    side = max(img.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    path = os.path.join(_EMOJI_DIR, f"e{len(_EMOJI_CACHE)}.png")
    sq.save(path)
    _EMOJI_CACHE[e] = path
    return path


def icon(c, e, x, y, size):
    c.drawImage(emoji_png(e), x, y, size, size, mask="auto")


def icon_round(c, e, cx, cy, r, bg=white):
    c.setFillColor(bg)
    c.circle(cx, cy, r, stroke=0, fill=1)
    s = r * 1.25
    icon(c, e, cx - s / 2, cy - s / 2, s)


# ---------------------------------------------------------------- texte

def fr_spaces(text):
    """Espaces insecables avant « » ? ! : » et apres « (typographie francaise : pas de signe seul en debut de ligne)."""
    for a, b in ((" »", " »"), ("« ", "« "), (" ?", " ?"), (" !", " !"), (" :", " :")):
        text = text.replace(a, b)
    return text


def wrap(c, text, font, size, width):
    lines = []
    for para in fr_spaces(text).split("\n"):
        cur = ""
        for w in [x for x in para.split(" ") if x]:
            t = (cur + " " + w).strip()
            if c.stringWidth(t, font, size) <= width:
                cur = t
            else:
                if cur:
                    lines.append(cur)
                cur = w
        lines.append(cur)
    return lines


def text_block(c, text, x, y, width, font="UI", size=14.5, color=ENCRE, lead=1.32):
    """Ecrit un paragraphe et renvoie la nouvelle position y (sous le texte)."""
    c.setFillColor(color)
    c.setFont(font, size)
    for line in wrap(c, text, font, size, width):
        c.drawString(x, y - size, line)
        y -= size * lead
    return y


# ---------------------------------------------------------------- mise en page

class Doc:
    def __init__(self, path, t):
        self.c = canvas.Canvas(path, pagesize=A5)
        self.c.setTitle(f"Clean Smart - {t['subtitle']}")
        self.c.setAuthor("JM Dargère")
        self.t = t
        self.page = 0
        self.y = 0

    def new_page(self, header=True):
        if self.page:
            self.footer()
            self.c.showPage()
        self.page += 1
        c = self.c
        c.setFillColor(white)
        c.rect(0, 0, W, H, stroke=0, fill=1)
        if header:
            c.setFillColor(VIOLET)
            c.rect(0, H - 46, W, 46, stroke=0, fill=1)
            icon(c, "✨", M - 4, H - 37, 26)
            c.setFillColor(white)
            c.setFont("UIB", 20)
            c.drawString(M + 28, H - 31, "CLEAN SMART")
            self.y = H - 66
        else:
            self.y = H - M

    def footer(self):
        if self.page <= 1:
            return
        c = self.c
        c.setFillColor(GRIS)
        c.setFont("UI", 8.5)
        c.drawString(M, 16, self.t["footer"])
        c.drawRightString(W - M, 16, str(self.page))

    def need(self, h):
        if self.y - h < 40:
            self.new_page()

    def banner(self, e, title, color):
        """Grand bandeau de couleur avec icone : le titre de la page."""
        c = self.c
        lines = wrap(c, title.upper(), "UIB", 17, W - 2 * M - 80)
        h = max(52, 22 + len(lines) * 21)
        self.need(h + 10)
        c.setFillColor(COUL[color])
        c.roundRect(M, self.y - h, W - 2 * M, h, 14, stroke=0, fill=1)
        icon(c, e, M + 14, self.y - h / 2 - 17, 34)
        c.setFillColor(white)
        c.setFont("UIB", 17)
        ty = self.y - (h - len(lines) * 21) / 2 - 16
        for ln in lines:
            c.drawCentredString(W / 2 + 22, ty, ln)
            ty -= 21
        self.y -= h + 16

    def para(self, text, size=14.5, color=ENCRE, font="UI"):
        lines = wrap(self.c, text, font, size, W - 2 * M)
        self.need(len(lines) * size * 1.32 + 6)
        self.y = text_block(self.c, text, M, self.y, W - 2 * M, font, size, color) - 8

    def step(self, n, text, color):
        """Etape numerotee : rond de couleur + texte en gros."""
        c = self.c
        size = 14.5
        lines = wrap(c, text, "UI", size, W - 2 * M - 40)
        h = len(lines) * size * 1.32
        self.need(h + 12)
        c.setFillColor(COUL[color])
        c.circle(M + 14, self.y - 14, 14, stroke=0, fill=1)
        c.setFillColor(white)
        c.setFont("UIB", 14)
        c.drawCentredString(M + 14, self.y - 19, str(n))
        self.y = text_block(c, text, M + 40, self.y - 2, W - 2 * M - 40, "UI", size) - 12

    def item(self, e, title, text):
        """Ligne avec icone : un titre en gras et une explication."""
        c = self.c
        tl = wrap(c, title, "UIB", 14.5, W - 2 * M - 44)
        bl = wrap(c, text, "UI", 13, W - 2 * M - 44)
        h = len(tl) * 19 + len(bl) * 17 + 8
        self.need(h + 6)
        icon_round(c, e, M + 15, self.y - 15, 15, FOND)
        y = text_block(c, title, M + 42, self.y, W - 2 * M - 44, "UIB", 14.5, lead=1.3)
        y = text_block(c, text, M + 42, y, W - 2 * M - 44, "UI", 13, GRIS, lead=1.3)
        self.y = y - 10

    def option(self, e, title, text, know):
        """Annexe des options : bandeau bleu + explication + « A savoir »."""
        c = self.c
        bl = wrap(c, text, "UI", 12.5, W - 2 * M - 8)
        kl = wrap(c, know, "UI", 12, W - 2 * M - 8)
        self.need(34 + len(bl) * 16.5 + len(kl) * 16 + 14)
        c.setFillColor(COUL["bleu"])
        c.roundRect(M, self.y - 28, W - 2 * M, 28, 9, stroke=0, fill=1)
        icon(c, e, M + 8, self.y - 23, 18)
        c.setFillColor(white)
        c.setFont("UIB", 13)
        c.drawString(M + 34, self.y - 19, title)
        y = text_block(c, text, M + 4, self.y - 34, W - 2 * M - 8, "UI", 12.5, lead=1.3)
        y = text_block(c, know, M + 4, y, W - 2 * M - 8, "UI", 12, VERT_TXT, lead=1.3)
        self.y = y - 12

    def card(self, e, title, text, color):
        """Carte de couleur de la page « Comment ca marche ? »."""
        c = self.c
        tl = wrap(c, title, "UIB", 15, W - 2 * M - 78)
        bl = wrap(c, text, "UI", 12.5, W - 2 * M - 78)
        h = max(70, 18 + len(tl) * 19 + len(bl) * 16.5)
        self.need(h + 8)
        c.setFillColor(COUL[color])
        c.roundRect(M, self.y - h, W - 2 * M, h, 14, stroke=0, fill=1)
        icon_round(c, e, M + 32, self.y - h / 2, 22)
        y = text_block(c, title, M + 64, self.y - 8, W - 2 * M - 78, "UIB", 15, white, 1.27)
        text_block(c, text, M + 64, y, W - 2 * M - 78, "UI", 12.5, white, 1.32)
        self.y -= h + 9

    def save(self):
        self.footer()
        self.c.save()


def phone(c, x, y, w, h):
    """Petit dessin du telephone avec l'ecran d'accueil de Clean Smart."""
    c.setFillColor(HexColor("#2B2B3A"))
    c.roundRect(x, y, w, h, 16, stroke=0, fill=1)
    sx, sy, sw, sh = x + 6, y + 6, w - 12, h - 12
    c.setFillColor(HexColor("#F4F6FB"))
    c.roundRect(sx, sy, sw, sh, 12, stroke=0, fill=1)
    # bandeau bleu
    c.setFillColor(HexColor("#0D47A1"))
    c.roundRect(sx, sy + sh - 24, sw, 24, 12, stroke=0, fill=1)
    c.rect(sx, sy + sh - 24, sw, 12, stroke=0, fill=1)
    c.setFillColor(white)
    c.setFont("UIB", 8.5)
    c.drawCentredString(sx + sw / 2, sy + sh - 16, "CLEAN SMART")
    # anneau de stockage
    cx, cy, r = sx + sw / 2, sy + sh - 56, 20
    c.setStrokeColor(HexColor("#E9EDF7")); c.setLineWidth(6); c.circle(cx, cy, r, stroke=1, fill=0)
    c.setStrokeColor(HexColor("#1565C0")); c.arc(cx - r, cy - r, cx + r, cy + r, 90, -260)
    c.setFillColor(ENCRE); c.setFont("UIB", 9); c.drawCentredString(cx, cy - 3, "72 %")
    # tuile verte du menage guide
    c.setFillColor(HexColor("#2E7D32")); c.roundRect(sx + 8, sy + sh - 104, sw - 16, 22, 5, stroke=0, fill=1)
    c.setFillColor(white); c.setFont("UIB", 6.5); c.drawCentredString(sx + sw / 2, sy + sh - 96, "Ménage complet")
    # tuiles de couleur
    yy = sy + sh - 134
    for row in ((("🧬", "#1E4FB4"), ("🎞️", "#6A1B9A")), (("🌫️", "#EF6C00"), ("🎬", "#C62828"))):
        tw = (sw - 22) / 2
        for k, (e, col) in enumerate(row):
            tx = sx + 8 + k * (tw + 6)
            c.setFillColor(HexColor(col)); c.roundRect(tx, yy, tw, 24, 5, stroke=0, fill=1)
            c.setFillColor(white); c.circle(tx + 10, yy + 12, 7, stroke=0, fill=1)
            icon(c, e, tx + 4.5, yy + 6.5, 11)
        yy -= 30
    c.setFillColor(HexColor("#C62828")); c.roundRect(sx + 8, sy + 8, sw - 16, 16, 5, stroke=0, fill=1)
    c.setFillColor(white); c.setFont("UIB", 6.5); c.drawCentredString(sx + sw / 2, sy + 13, "Nettoyer")


# ---------------------------------------------------------------- contenu

TEXTS = {
    "fr": {
        "subtitle": "Mode d'emploi",
        "tag": "Plus de place sur ton téléphone,\nsimplement et sans risque.",
        "credit": "Créé par JM Dargère",
        "footer": "CLEAN SMART — Créé par JM Dargère",
        "how_title": "Comment ça marche ?",
        "how": [
            ("📱", "rouge", "1. Ton téléphone est plein ?", "Photos en double, photos ratées, vidéos énormes, mails inutiles… tout prend de la place."),
            ("🔍", "bleu", "2. Clean Smart cherche tout seul", "Un seul bouton. Pendant l'analyse, rien n'est supprimé."),
            ("✅", "violet", "3. Tu choisis", "Ce qui est sûr est déjà coché pour toi. Pour le reste, c'est toi qui décides."),
            ("🗑️", "orange", "4. Rien n'est perdu", "Tout passe par la corbeille : 30 jours pour récupérer une photo ou un mail."),
            ("💾", "vert", "5. Tout reste à l'abri", "Sauvegarde sur carte SD, clé USB ou Google Drive, et même toute seule la nuit."),
        ],
        "sections": [
            ("🧭", "vert", "Le ménage complet (conseillé)",
             "L'accueil a une grande tuile verte « Ménage complet » et 3 portes : 📸 Photos, 🧹 Téléphone, ✉️ Mails. Le ménage complet fait tout dans le bon ordre ; le bouton VERT est toujours la prochaine étape.",
             [("step", "Le ménage sûr : doublons et rafales en trop (la meilleure est gardée), photos noires ou blanches, vieilles captures. Un bouton."),
              ("step", "Je vérifie le douteux : photos un peu floues, WhatsApp… Glisse à gauche pour jeter, à droite pour garder (elle devient favori)."),
              ("step", "Je mets à l'abri : seules les BONNES photos sont sauvegardées. Les favoris vont aussi dans « Mes meilleures »."),
              ("step", "Je libère la place : les photos anciennes déjà sauvegardées quittent le téléphone."),
              ("step", "NOUVEAU 2.0 — Et tes mails : la place de ta boîte, les newsletters et gros mails, puis le ménage guidé des mails. Chez Google, mails et Google Photos partagent les mêmes 15 Go."),
              ("step", "Le bilan montre ce qui est libéré sur le téléphone ET sur la boîte mail. Dans les rafales, la photo où personne n'a les yeux fermés est gardée.")]),
            ("🧹", "turquoise", "Fichiers inutiles (NOUVEAU 2.0)",
             "Fichiers temporaires, miniatures, dossiers cache, dossiers vides, fichiers d'installation, restes d'applis désinstallées. Les photos, vidéos et musiques ne sont jamais touchées.",
             [("step", "Une seule fois : autorise « Accès à tous les fichiers ». La recherche démarre toute seule."),
              ("step", "Les groupes sûrs sont cochés : ils se recréent tout seuls ou ne servent plus. « Nettoyer » et c'est fini."),
              ("step", "Les groupes « à vérifier » (autres .apk, restes d'applis, vieux téléchargements) vont dans la corbeille Clean Smart : « Tout remettre » pendant 7 jours."),
              ("step", "Le cache des autres applis : Android l'interdit aux applis depuis Android 11. Le bouton ouvre l'écran « Libérer de l'espace » d'Android."),
              ("step", "📱 Mes applis : les plus grosses et celles que tu n'ouvres plus depuis 3 mois. Touche-en une pour la désinstaller ou vider son cache.")]),
            ("❤️", "vert", "Note de santé et ménage du dimanche (NOUVEAU 2.0)",
             "Sur l'accueil, une note de 0 à 100 résume l'état du téléphone : place libre, sauvegarde, photos à trier, fichiers inutiles, boîte mail. Elle dit aussi quoi faire pour gagner des points.",
             [("step", "Dans Options › Automatique, active « Ménage automatique du dimanche » : fichiers inutiles sûrs et spam partent tout seuls, une notification fait le bilan."),
              ("step", "Les photos ne partent jamais toutes seules : la notification dit combien sont à trier, tu touches et tu choisis."),
              ("step", "Options simplifiées : Sauvegarde, Automatique, Affichage. Le reste est dans « Réglages avancés ».")]),
            ("📦", "rouge", "Quitter Google Photos",
             "Toutes tes photos Google Photos sur une clé ou un disque dur, rangées par année, sans doublon. Un seul tableau de bord : l'étape « MAINTENANT » est ouverte, avec le bouton vert.",
             [("step", "La place : note la taille de Google Photos, Clean Smart calcule la place à prévoir."),
              ("step", "Le disque : branche la clé ou le disque (adaptateur USB-C) et choisis un dossier dessus."),
              ("step", "Demander la copie à Google : le modèle montre exactement quoi cocher (fichiers .zip de 2 Go)."),
              ("step", "Les fichiers : la liste 001, 002… te dit lequel télécharger. « Traiter un fichier », puis supprime-le. Une notification prévient à la fin."),
              ("step", "Chaque photo est rangée par année, les doublons et les rafales en trop sont écartés, tout est relu et vérifié. La jauge montre combien est à l'abri."),
              ("step", "Problème (téléphone ou disque plein, fichier abîmé) : rien n'est perdu, relance le même fichier. Bouton « Si ça bloque »."),
              ("step", "NOUVEAU 2.0 — « Tout automatique » : autorise une fois « Accès à tous les fichiers », démarre, puis touche simplement les liens du mail de Google. Chaque fichier est copié dès qu'il arrive dans Download, puis supprimé du téléphone une fois vérifié."),
              ("step", "Libérer Google : le bouton n'apparaît que si la copie est complète, que la sauvegarde du téléphone est coupée et que tu as regardé tes photos sur le disque. C'est toi qui effaces ; la corbeille de Google garde tout 60 jours."),
              ("step", "« Vérifier le disque » relit chaque photo copiée et signale celles qui seraient abîmées. Tous les 2 mois, une notification rappelle de demander une nouvelle copie : seules les nouvelles photos sont ajoutées.")]),
            ("🍏", "indigo", "Récupérer mes photos iCloud (ancien iPhone)",
             "Pour les photos d'iPhone : même tableau de bord que Google Photos, avec l'export officiel d'Apple.",
             [("step", "La place : sur l'iPhone, Réglages › ton nom › iCloud. Clean Smart calcule la place à prévoir."),
              ("step", "Le disque : branche la clé ou le disque et choisis un dossier dessus."),
              ("step", "Sur privacy.apple.com : « Demander une copie de tes données », coche « Photos iCloud », fichiers de 1 ou 2 Go."),
              ("step", "Apple envoie un mail sous quelques jours. Télécharge « Part 1 », traite-le, supprime-le, puis « Part 2 »…"),
              ("step", "Tout est rangé par année dans « iCloud Photos », sans doublon, la meilleure photo de chaque rafale gardée.")]),
            ("🔍", "vert", "Analyser mon téléphone",
             "C'est le gros bouton de l'accueil. Tu n'as rien à régler.",
             [("step", "Touche « Analyser mon téléphone »."),
              ("step", "La première fois, Android demande l'accès aux photos et aux contacts : accepte."),
              ("step", "Une barre montre l'avancement. Ensuite, Clean Smart affiche en gros l'espace que tu peux récupérer.")]),
            ("📸", "bleu", "Ce que Clean Smart trouve", "",
             [("item", "🧬", "Doublons", "Copies identiques. L'original est gardé."),
              ("item", "🎞️", "Photos similaires", "Rafales prises à quelques secondes. La plus nette est gardée."),
              ("item", "🌫️", "Photos ratées", "Floues, trop sombres, trop claires ou trop petites."),
              ("item", "📱", "Captures d'écran", "Souvent inutiles après coup."),
              ("item", "🎬", "Grosses vidéos", "Plus de 100 Mo chacune."),
              ("item", "💬", "Médias WhatsApp", "Photos et vidéos reçues ou envoyées."),
              ("item", "🐘", "Mes plus gros fichiers", "Les 30 photos et vidéos les plus lourdes."),
              ("item", "👥", "Contacts en double", "Même nom et même numéro.")]),
            ("🧹", "rouge", "Choisir et nettoyer", "",
             [("step", "Touche une catégorie pour voir les photos."),
              ("step", "Touche une photo pour la cocher. Appui long : tu la vois en grand."),
              ("step", "« GARDÉE » : c'est la photo que Clean Smart te conseille de garder."),
              ("step", "Appuie sur le bouton rouge « Nettoyer » en bas de l'écran."),
              ("step", "Android te demande une dernière confirmation. Les photos vont dans la corbeille : 30 jours pour les récupérer.")]),
            ("👆", "violet", "Le tri rapide",
             "Pour trier tes photos récentes une par une, sans réfléchir.",
             [("step", "Sur l'accueil, touche « Tri rapide »."),
              ("step", "Glisse la photo vers la GAUCHE pour la jeter, vers la DROITE pour la garder."),
              ("step", "Le petit bouton rond du milieu revient à la photo précédente."),
              ("step", "À la fin, le bouton rouge met les photos à jeter dans la corbeille.")]),
            ("🗑️", "orange", "La corbeille et « J'ai fait une bêtise »", "",
             [("step", "Les photos mises à la corbeille prennent encore de la place pendant 30 jours."),
              ("step", "« Vider la corbeille maintenant » libère la place tout de suite (attention : c'est définitif)."),
              ("step", "« J'ai fait une bêtise » remet dans la galerie les derniers fichiers jetés.")]),
            ("🛡️", "violet", "Protéger et envoyer", "Dans une catégorie, coche des photos puis :",
             [("item", "🛡️", "Protéger", "La photo passe en favori : elle ne sera plus jamais proposée."),
              ("item", "📤", "Envoyer", "Par WhatsApp, mail… (20 photos au maximum). Rien ne bouge du téléphone."),
              ("item", "🗜️", "Compresser", "Pour les vidéos : refaites en 720p, souvent 3 à 5 fois plus légères.")]),
            ("💾", "bleu", "La sauvegarde",
             "Tes photos à l'abri sur une carte SD, une clé USB ou Google Drive.",
             [("step", "Dans « Sauvegarde », touche « Choisir un dossier »."),
              ("step", "« Sauvegarder toutes mes photos » copie tes photos, rangées par mois."),
              ("step", "Les fois suivantes, seules les nouvelles photos sont copiées."),
              ("step", "Dans les Options, la « Sauvegarde automatique chaque nuit » le fait toute seule.")]),
            ("🧰", "vert", "Plus d'outils", "Sur l'accueil, touche « Plus d'outils » :",
             [("item", "🧹", "Libérer la place", "Les photos de plus de 3 mois DÉJÀ sauvegardées vont à la corbeille. Jamais une photo qui n'est pas à l'abri."),
              ("item", "📕", "PDF souvenir", "Les plus belles photos du mois ou de l'année dans un PDF, à envoyer à un proche."),
              ("item", "🗜️", "Réduire mes vidéos", "Ouvre « Grosses vidéos », coche-les puis « Compresser ».")]),
            ("✉️", "bleu", "Ma boîte mail",
             "Gmail, Outlook, Yahoo, iCloud, Orange, Free, SFR, La Poste…",
             [("step", "Touche « Connecter ma boîte mail » et choisis ta messagerie."),
              ("step", "Gmail, Yahoo et iCloud demandent un MOT DE PASSE D'APPLICATION. Clean Smart ouvre la bonne page."),
              ("step", "« Analyser ma boîte mail », puis un bouton pour : newsletters, gros mails, vieux mails, mails en double."),
              ("step", "Les mails vont dans la corbeille de ta messagerie. Ceux de ta banque, de l'administration ou de santé ne sont JAMAIS touchés.")]),
            ("🧭", "vert", "Le ménage guidé des mails",
             "Dans Mails, touche la grande tuile verte. Même principe : le bouton vert est la prochaine étape.",
             [("step", "Le ménage sûr : newsletters, pubs, notifications et mails en double vont à la corbeille."),
              ("step", "Je trie par expéditeur : une carte par expéditeur. À gauche, tous ses mails partent ; à droite, on le garde."),
              ("step", "Je mets l'important à l'abri : banque, administration, santé, factures et mails avec un PDF sont sauvegardés."),
              ("step", "Je libère la boîte : les vieux mails partent à la corbeille, jamais les importants."),
              ("step", "« Se désabonner » : pour ne plus recevoir une newsletter. Le bouton ouvre la page de désabonnement."),
              ("step", "NOUVEAU 2.0 — Avant de jeter, la liste des mails s'affiche (expéditeur, objet, date) : décoche ceux à garder, puis valide."),
              ("step", "Touche un expéditeur : 🗑️ toujours jeter (chaque nuit, tout seul), 🛡️ toujours garder, 🔔 me prévenir quand il écrit."),
              ("step", "Boîte protégée : banque, impôts, santé, maison et famille (tes contacts) sont rangés dans « Protégés » de ta messagerie, jamais jetés. Bouton « Spam » pour vider le courrier indésirable.")]),
            ("📎", "orange", "Sauver des mails et leurs pièces jointes", "",
             [("step", "Dans Mails, touche « Sauvegarder des mails »."),
              ("step", "Coche les mails à garder (« Cocher les PDF » pour aller vite)."),
              ("step", "Touche « Sauvegarder » : le mail complet, et tous les PDF réunis dans un dossier PDF."),
              ("step", "Sur le PC, ouvre CleanSmart/Mails/index.html : tape un mot, un nom ou un PDF pour tout retrouver.")]),
            ("↩️", "vert", "Remettre des mails", "",
             [("step", "Dans Mails, touche « Remettre des mails »."),
              ("step", "Coche les mails de la corbeille à récupérer."),
              ("step", "Touche « Remettre » : ils reviennent dans ta boîte de réception.")]),
            ("👥", "violet", "Contacts en double", "",
             [("step", "Après l'analyse, la carte « Contacts » montre les doublons (même nom, même numéro)."),
              ("step", "« Supprimer les doublons » garde le contact d'origine."),
              ("step", "Attention : la suppression est aussi faite sur ton compte Google.")]),
        ],
        "options_title": "Les options expliquées",
        "options": [
            ("🌙", "Sauvegarde automatique chaque nuit", "Vers 2 h, les nouvelles photos sont copiées dans ton dossier de sauvegarde.", "À savoir : une notification te prévient si ça n'a pas marché."),
            ("🔔", "Alertes et rapport du mois", "Alerte s'il reste moins de 1 Go, et un petit bilan le 1er du mois.", "À savoir : au plus une alerte tous les 3 jours."),
            ("💾", "Sauvegarder avant de supprimer", "Copie les photos (et les contacts) dans ton dossier avant chaque nettoyage.", "À savoir : désactivé par défaut, inutile pour les doublons et les photos ratées."),
            ("🛡️", "Protéger mes favoris", "Les photos en favori ne sont jamais proposées.", "À savoir : activé par défaut."),
            ("⚖️", "Sévérité", "Prudente : moins de photos proposées. Normale. Stricte : davantage.", "À savoir : relance l'analyse après un changement."),
            ("🧹", "Libérer la place", "Garder sur le téléphone les photos des 1, 3, 6 ou 12 derniers mois.", "À savoir : seules les photos déjà sauvegardées sont proposées."),
            ("🔠", "Taille du texte", "Normale, gros ou très gros.", "À savoir : il y a un peu plus à faire défiler."),
            ("🌍", "Langue", "Comme le téléphone, ou l'une des 16 langues (français, anglais, espagnol, grec, arabe, chinois…).", "À savoir : le changement s'applique tout de suite."),
            ("🎬", "Démonstration", "Montre l'appli avec de fausses photos, de faux mails et de faux contacts.", "À savoir : rien n'est touché sur le téléphone. Idéal pour présenter l'appli."),
            ("📲", "Widget", "Sur l'écran d'accueil du téléphone : appui long > Widgets > Clean Smart. Il montre la place libre et ouvre le Ménage guidé.", "À savoir : il se met à jour tout seul."),
        ],
        "contact": "Une question, ou la version complète ? Écris-moi : dargerejm@gmail.com — Instagram @jean_michel_dargere — WhatsApp 06 13 37 10 42",
        "faq_title": "Questions fréquentes",
        "faq": [
            ("Mes photos sont-elles supprimées tout de suite ?", "Non. Elles vont dans la corbeille de la Galerie : 30 jours pour les récupérer, ou « J'ai fait une bêtise »."),
            ("Mes photos partent-elles sur Internet ?", "Non. Tout se passe sur le téléphone. Seule la sauvegarde va où tu l'as choisi (carte SD, clé, Drive)."),
            ("Pourquoi un mot de passe d'application ?", "Gmail, Yahoo et iCloud l'exigent pour les applis de messagerie. Il reste chiffré sur ton téléphone."),
            ("La place ne revient pas après le nettoyage ?", "La corbeille garde les photos 30 jours. Touche « Vider la corbeille maintenant » sur l'accueil."),
            ("J'ai effacé ma copie sur le disque, plus rien n'est recopié ?", "Dans l'onglet Google Photos ou iCloud Photos, touche « Tout recommencer (j'ai effacé ma copie) » en bas de l'écran, puis relance tes fichiers ZIP."),
            ("Comment avoir la dernière version ?", "Quand une nouvelle version existe, un bandeau orange apparaît sur l'accueil : touche-le, puis ouvre le fichier téléchargé. Ou Options > « Rechercher une mise à jour »."),
        ],
    },
    "en": {
        "subtitle": "User guide",
        "tag": "More space on your phone,\nsimply and safely.",
        "credit": "Created by JM Dargère",
        "footer": "CLEAN SMART — Created by JM Dargère",
        "how_title": "How does it work?",
        "how": [
            ("📱", "rouge", "1. Is your phone full?", "Duplicate photos, failed shots, huge videos, useless emails… all take space."),
            ("🔍", "bleu", "2. Clean Smart searches for you", "One single button. Nothing is deleted during the scan."),
            ("✅", "violet", "3. You choose", "What is safe is already ticked for you. For the rest, you decide."),
            ("🗑️", "orange", "4. Nothing is lost", "Everything goes through the bin: 30 days to get a photo or an email back."),
            ("💾", "vert", "5. Everything stays safe", "Backup to an SD card, USB drive or Google Drive, even on its own at night."),
        ],
        "sections": [
            ("🧭", "vert", "Complete clean-up (recommended)",
             "The home screen has a big green \"Complete clean-up\" tile and 3 doors: 📸 Photos, 🧹 Phone, ✉️ Emails. The complete clean-up does everything in the right order; the GREEN button is always the next step.",
             [("step", "Safe clean-up: extra duplicates and bursts (the best one is kept), black or white photos, old screenshots. One button."),
              ("step", "Check the doubtful ones: slightly blurry photos, WhatsApp… Swipe left to discard, right to keep (it becomes a favourite)."),
              ("step", "Keep them safe: only GOOD photos are backed up. Favourites also go to \"Mes meilleures\" (My best)."),
              ("step", "Free up space: old photos already backed up leave the phone."),
              ("step", "NEW 2.0 — And your emails: your mailbox space, newsletters and large emails, then the guided mailbox clean-up. At Google, emails and Google Photos share the same 15 GB."),
              ("step", "The summary shows what is freed on the phone AND in the mailbox. In bursts, the photo where nobody has their eyes closed is kept.")]),
            ("🧹", "turquoise", "Junk files (NEW 2.0)",
             "Temporary files, thumbnails, cache folders, empty folders, installation files, leftovers of uninstalled apps. Photos, videos and music are never touched.",
             [("step", "Just once: allow \"All files access\". The search starts by itself."),
              ("step", "Safe groups are ticked: they are recreated automatically or no longer needed. \"Clean\" and you're done."),
              ("step", "\"To check\" groups (other .apk, app leftovers, old downloads) go to the Clean Smart bin: \"Put everything back\" for 7 days."),
              ("step", "Other apps' cache: Android forbids apps from clearing it since Android 11. The button opens Android's \"Free up space\" screen."),
              ("step", "📱 My apps: the biggest ones and those not opened for 3 months. Tap one to uninstall it or clear its cache.")]),
            ("❤️", "vert", "Health score and Sunday clean-up (NEW 2.0)",
             "On the home screen, a score from 0 to 100 sums up the phone: free space, backup, photos to sort, junk files, mailbox. It also tells you what to do to earn points.",
             [("step", "In Options › Automatic, turn on \"Automatic Sunday clean-up\": safe junk files and spam are removed automatically, a notification sums it up."),
              ("step", "Photos never leave on their own: the notification says how many to sort, you tap and choose."),
              ("step", "Simpler options: Backup, Automatic, Display. The rest is in \"Advanced settings\".")]),
            ("📦", "rouge", "Leave Google Photos",
             "All your Google Photos on a USB drive or hard disk, sorted by year, without duplicates. One single dashboard: the \"MAINTENANT\" (now) step is open, with the green button.",
             [("step", "Space: note the size of Google Photos, Clean Smart works out the space to plan."),
              ("step", "The disk: plug in the USB drive or disk (USB-C adapter) and choose a folder on it."),
              ("step", "Ask Google for a copy: the model shows exactly what to tick (.zip files of 2 GB)."),
              ("step", "The files: the 001, 002… list tells you which one to download. \"Traiter un fichier\", then delete it. A notification tells you when it is done."),
              ("step", "Each photo is sorted by year, duplicates and extra bursts are left out, everything is re-read and checked. The gauge shows how much is safe."),
              ("step", "Problem (phone or disk full, damaged file): nothing is lost, run the same file again. \"Si ça bloque\" button."),
              ("step", "NEW 2.0 — \"Fully automatic\": allow \"All files access\" once, start, then just tap the links in Google's email. Each file is copied as soon as it arrives in Download, then deleted from the phone once verified."),
              ("step", "Free up Google: the button only appears when the copy is complete, phone backup is off and you have checked your photos on the drive. You do the deleting; Google's bin keeps everything for 60 days."),
              ("step", "\"Check the drive\" re-reads every copied photo and reports any damaged one. Every 2 months, a notification reminds you to request a new copy: only new photos are added.")]),
            ("🍏", "indigo", "Get my iCloud photos (former iPhone)",
             "For iPhone photos: the same dashboard as Google Photos, with Apple's official export.",
             [("step", "Space: on the iPhone, Settings › your name › iCloud. Clean Smart works out the space to plan."),
              ("step", "The disk: plug in the USB drive or disk and choose a folder on it."),
              ("step", "On privacy.apple.com: \"Request a copy of your data\", tick \"iCloud Photos\", files of 1 or 2 GB."),
              ("step", "Apple sends an email within a few days. Download \"Part 1\", process it, delete it, then \"Part 2\"…"),
              ("step", "Everything is sorted by year in \"iCloud Photos\", without duplicates, the best photo of each burst kept.")]),
            ("🔍", "vert", "Scan my phone",
             "It is the big button on the home screen. Nothing to set up.",
             [("step", "Tap \"Analyser mon téléphone\" (Scan my phone)."),
              ("step", "The first time, Android asks for access to photos and contacts: allow it."),
              ("step", "A bar shows the progress. Then Clean Smart shows in big how much space you can get back.")]),
            ("📸", "bleu", "What Clean Smart finds", "",
             [("item", "🧬", "Duplicates", "Identical copies. The original is kept."),
              ("item", "🎞️", "Similar photos", "Bursts taken seconds apart. The sharpest one is kept."),
              ("item", "🌫️", "Failed photos", "Blurry, too dark, too bright or too small."),
              ("item", "📱", "Screenshots", "Often useless afterwards."),
              ("item", "🎬", "Large videos", "Over 100 MB each."),
              ("item", "💬", "WhatsApp media", "Photos and videos received or sent."),
              ("item", "🐘", "My biggest files", "The 30 heaviest photos and videos."),
              ("item", "👥", "Duplicate contacts", "Same name and same number.")]),
            ("🧹", "rouge", "Choose and clean", "",
             [("step", "Tap a category to see the photos."),
              ("step", "Tap a photo to tick it. Long press: view it full screen."),
              ("step", "\"GARDÉE\" (kept): the photo Clean Smart advises you to keep."),
              ("step", "Tap the red \"Nettoyer\" (Clean) button at the bottom."),
              ("step", "Android asks for a final confirmation. Photos go to the bin: 30 days to restore them.")]),
            ("👆", "violet", "Quick sort",
             "To sort your recent photos one by one, without thinking.",
             [("step", "On the home screen, tap \"Tri rapide\" (Quick sort)."),
              ("step", "Swipe the photo LEFT to discard it, RIGHT to keep it."),
              ("step", "The small round button in the middle goes back to the previous photo."),
              ("step", "At the end, the red button sends the discarded photos to the bin.")]),
            ("🗑️", "orange", "The bin and \"I made a mistake\"", "",
             [("step", "Photos in the bin still take space for 30 days."),
              ("step", "\"Vider la corbeille maintenant\" (Empty the bin now) frees the space right away (careful: it is final)."),
              ("step", "\"J'ai fait une bêtise\" (I made a mistake) puts the last discarded files back in the gallery.")]),
            ("🛡️", "violet", "Protect and send", "In a category, tick photos then:",
             [("item", "🛡️", "Protect", "The photo becomes a favourite: it will never be suggested again."),
              ("item", "📤", "Send", "By WhatsApp, email… (20 photos max). Nothing leaves the phone."),
              ("item", "🗜️", "Compress", "For videos: remade in 720p, often 3 to 5 times lighter.")]),
            ("💾", "bleu", "Backup",
             "Your photos safe on an SD card, a USB drive or Google Drive.",
             [("step", "In \"Sauvegarde\" (Backup), tap \"Choisir un dossier\" (Choose a folder)."),
              ("step", "\"Back up all my photos\" copies your photos, sorted by month."),
              ("step", "Next times, only new photos are copied."),
              ("step", "In Options, \"Automatic backup every night\" does it on its own.")]),
            ("🧰", "vert", "More tools", "On the home screen, tap \"Plus d'outils\" (More tools):",
             [("item", "🧹", "Free up space", "Photos older than 3 months ALREADY backed up go to the bin. Never a photo that is not safe."),
              ("item", "📕", "Souvenir PDF", "The best photos of the month or year in a PDF, to send to a loved one."),
              ("item", "🗜️", "Shrink my videos", "Open \"Grosses vidéos\" (Large videos), tick them, then \"Compresser\".")]),
            ("✉️", "bleu", "My mailbox",
             "Gmail, Outlook, Yahoo, iCloud, Orange, Free, SFR, La Poste…",
             [("step", "Tap \"Connecter ma boîte mail\" (Connect my mailbox) and choose your provider."),
              ("step", "Gmail, Yahoo and iCloud require an APP PASSWORD. Clean Smart opens the right page."),
              ("step", "Scan your mailbox, then one button for: newsletters, large emails, old emails, duplicate emails."),
              ("step", "Emails go to your mailbox bin. Those from your bank, government or health services are NEVER touched.")]),
            ("🧭", "vert", "Guided mailbox clean-up",
             "In Mails, tap the big green tile. Same idea: the green button is the next step.",
             [("step", "Safe clean-up: newsletters, ads, notifications and duplicate emails go to the bin."),
              ("step", "Sort by sender: one card per sender. Left, all their emails go; right, you keep them."),
              ("step", "Keep the important safe: bank, government, health, invoices and emails with a PDF are backed up."),
              ("step", "Free the mailbox: old emails go to the bin, never the important ones."),
              ("step", "\"Se désabonner\" (Unsubscribe): stop receiving a newsletter. The button opens the unsubscribe page."),
              ("step", "NEW 2.0 — Before deleting, the list of emails is shown (sender, subject, date): untick those to keep, then confirm."),
              ("step", "Tap a sender: 🗑️ always delete (every night, automatically), 🛡️ always keep, 🔔 notify me when they write."),
              ("step", "Protected box: bank, taxes, health, home and family (your contacts) are filed in \"Protected\" in your mailbox, never deleted. \"Spam\" button to empty junk mail.")]),
            ("📎", "orange", "Save emails and their attachments", "",
             [("step", "In Mails, tap \"Sauvegarder des mails\" (Save emails)."),
              ("step", "Tick the emails to keep (\"Cocher les PDF\" to go fast)."),
              ("step", "Tap \"Sauvegarder\": the full email, and all PDFs gathered in a PDF folder."),
              ("step", "On the PC, open CleanSmart/Mails/index.html: type a word, a name or a PDF to find everything.")]),
            ("↩️", "vert", "Restore emails", "",
             [("step", "In Mails, tap \"Remettre des mails\" (Restore emails)."),
              ("step", "Tick the emails in the bin you want back."),
              ("step", "Tap \"Remettre\": they come back to your inbox.")]),
            ("👥", "violet", "Duplicate contacts", "",
             [("step", "After the scan, the \"Contacts\" card shows duplicates (same name, same number)."),
              ("step", "\"Supprimer les doublons\" keeps the original contact."),
              ("step", "Careful: deletion is also done on your Google account.")]),
        ],
        "options_title": "Options explained",
        "options": [
            ("🌙", "Automatic backup every night", "Around 2 am, new photos are copied to your backup folder.", "Good to know: a notification tells you if it did not work."),
            ("🔔", "Alerts and monthly report", "Alert when less than 1 GB is left, and a short summary on the 1st of the month.", "Good to know: at most one alert every 3 days."),
            ("💾", "Back up before deleting", "Copies photos (and contacts) to your folder before each clean-up.", "Good to know: off by default, useless for duplicates and failed photos."),
            ("🛡️", "Protect my favourites", "Favourite photos are never suggested.", "Good to know: on by default."),
            ("⚖️", "Severity", "Careful: fewer photos suggested. Normal. Strict: more.", "Good to know: scan again after a change."),
            ("🧹", "Free up space", "Keep on the phone the photos of the last 1, 3, 6 or 12 months.", "Good to know: only photos already backed up are suggested."),
            ("🔠", "Text size", "Normal, large or very large.", "Good to know: a bit more scrolling."),
            ("🌍", "Language", "Same as phone, or one of 16 languages (French, English, Spanish, Greek, Arabic, Chinese…).", "Good to know: the change applies right away."),
            ("🎬", "Demo mode", "Shows the app with fake photos, emails and contacts.", "Good to know: nothing is touched on the phone. Ideal to present the app."),
            ("📲", "Widget", "On the phone's home screen: long press > Widgets > Clean Smart. It shows free space and opens the guided clean-up.", "Good to know: it updates on its own."),
        ],
        "contact": "A question, or the full version? Write to me: dargerejm@gmail.com — Instagram @jean_michel_dargere — WhatsApp +33 6 13 37 10 42",
        "faq_title": "Frequently asked questions",
        "faq": [
            ("Are my photos deleted right away?", "No. They go to the Gallery bin: 30 days to restore them, or \"I made a mistake\"."),
            ("Do my photos go on the Internet?", "No. Everything happens on the phone. Only the backup goes where you chose (SD card, USB, Drive)."),
            ("Why an app password?", "Gmail, Yahoo and iCloud require it for mail apps. It stays encrypted on your phone."),
            ("The space does not come back after cleaning?", "The bin keeps photos for 30 days. Tap \"Empty the bin now\" on the home screen."),
            ("I erased my copy on the disk, nothing is copied any more?", "In the Google Photos or iCloud Photos tab, tap \"Start over (I erased my copy)\" at the bottom of the screen, then run your ZIP files again."),
            ("How do I get the latest version?", "When a new version exists, an orange banner appears on the home screen: tap it, then open the downloaded file. Or Options > \"Rechercher une mise à jour\" (Check for updates)."),
        ],
    },
}


def build(lang, path):
    t = TEXTS[lang]
    d = Doc(path, t)
    c = d.c

    # ---- couverture
    d.new_page(header=False)
    c.setFillColor(NUIT)
    c.rect(0, 0, W, H, stroke=0, fill=1)
    c.setFillColor(VIOLET)
    c.roundRect(W / 2 - 50, H - 200, 100, 100, 26, stroke=0, fill=1)
    icon(c, "✨", W / 2 - 36, H - 186, 72)
    c.setFillColor(white)
    c.setFont("UIB", 38)
    c.drawCentredString(W / 2, H - 252, "CLEAN SMART")
    c.setFillColor(HexColor("#B9B2FF"))
    c.setFont("UI", 19)
    c.drawCentredString(W / 2, H - 280, t["subtitle"])
    c.setFillColor(white)
    c.setFont("UI", 15)
    y = H - 320
    for ln in t["tag"].split("\n"):
        c.drawCentredString(W / 2, y, ln)
        y -= 21
    for i, e in enumerate(["🧹", "📸", "✉️", "💾", "🛡️"]):
        icon(c, e, W / 2 - 130 + i * 56, 120, 40)
    c.setFillColor(HexColor("#A3A3C7"))
    c.setFont("UI", 10)
    c.drawCentredString(W / 2, 40, t["credit"])

    # ---- comment ca marche
    d.new_page()
    c.setFillColor(ENCRE)
    c.setFont("UIB", 22)
    c.drawString(M, d.y - 20, t["how_title"])
    d.y -= 38
    for e, col, title, text in t["how"]:
        d.card(e, title, text, col)

    # ---- une page par fonction (avec le dessin du telephone sur la premiere)
    for i, (e, col, title, intro, parts) in enumerate(t["sections"]):
        d.new_page()
        d.banner(e, title, col)
        if intro:
            d.para(intro)
        n = 0
        for p in parts:
            if p[0] == "step":
                n += 1
                d.step(n, p[1], col)
            else:
                d.item(p[1], p[2], p[3])
        if i == 0 and d.y > 230:
            phone(c, W / 2 - 65, 50, 130, d.y - 80 if d.y - 80 < 250 else 250)

    # ---- options
    d.new_page()
    c.setFillColor(ENCRE)
    c.setFont("UIB", 22)
    c.drawString(M, d.y - 20, t["options_title"])
    d.y -= 38
    for e, title, text, know in t["options"]:
        d.option(e, title, text, know)

    # ---- questions frequentes
    d.new_page()
    c.setFillColor(ENCRE)
    c.setFont("UIB", 22)
    c.drawString(M, d.y - 20, t["faq_title"])
    d.y -= 40
    for q, a in t["faq"]:
        lines = wrap(c, q, "UIB", 16, W - 2 * M)
        d.need(len(lines) * 21 + 60)
        d.y = text_block(c, q, M, d.y, W - 2 * M, "UIB", 16, COUL["orange"], 1.28) - 2
        d.y = text_block(c, a, M, d.y, W - 2 * M, "UI", 14, ENCRE, 1.32) - 16
    # encadre vert « Une question ? Ecris-moi » (comme dans Mail Simple / Photo Simple)
    contact = t["contact"]
    lignes = wrap(c, contact, "UI", 14, W - 2 * M - 32)
    h = 26 + len(lignes) * 19
    d.need(h + 10)
    c.setFillColor(COUL["vert"])
    c.roundRect(M, d.y - h, W - 2 * M, h, 14, stroke=0, fill=1)
    text_block(c, contact, M + 16, d.y - 12, W - 2 * M - 32, "UI", 14, white, 1.35)
    d.y -= h + 10
    d.save()


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    assets = os.path.normpath(os.path.join(here, "..", "..", "app", "src", "main", "assets"))
    os.makedirs(assets, exist_ok=True)
    for lang in ("fr", "en"):
        out = os.path.join(assets, f"manuel_{lang}.pdf")
        build(lang, out)
        print("OK", out)
