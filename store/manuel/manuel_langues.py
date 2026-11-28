"""
Mode d'emploi PDF dans les 14 autres langues (es pt de it th hi zh ar id ja ko tr vi el).
La structure (icones, couleurs) vient de make_manual.TEXTS['en'] ; les textes traduits sont dans
store/manuel/textes/<code>.txt (lignes « numero<TAB>texte », \\n pour un retour a la ligne).
  python store/manuel/manuel_langues.py numeros   -> ecrit textes/numeros_en.txt (le texte anglais numerote)
  python store/manuel/manuel_langues.py [codes]   -> fabrique app/src/main/assets/manuel_<code>.pdf
Rendu : page HTML imprimee par Microsoft Edge (sans fenetre), pour que l'arabe, l'hindi et le thai soient bien formes.
"""
import base64
import html
import io
import re
import os
import subprocess
import sys
import tempfile

ICI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ICI)
import make_manual as mm  # noqa: E402
from PIL import Image  # noqa: E402

LANGUES = ["es", "pt", "de", "it", "th", "hi", "zh", "ar", "id", "ja", "ko", "tr", "vi", "el"]
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
COUL = {"vert": "#2E7D32", "turquoise": "#00838F", "bleu": "#1E4FB4", "rouge": "#C62828",
        "violet": "#6A1B9A", "orange": "#EF6C00", "indigo": "#5B4BE0"}
POLICE = {"th": "'Leelawadee UI'", "hi": "'Nirmala UI'", "zh": "'Microsoft YaHei'", "ja": "'Yu Gothic UI','Meiryo'",
          "ko": "'Malgun Gothic'"}

CSS = """
@page { size: 148mm 210mm; margin: 0 }
* { box-sizing: border-box }
body { margin:0; font-family: FONT, 'Segoe UI', sans-serif; color:#1E1E2E; font-size:13.5pt; line-height:1.35 }
.ei { height:1.05em; vertical-align:-0.15em }
.big { width:22mm; margin-top:6.5mm } .cov { width:11mm; margin:0 2.5mm } .ri { width:60%; vertical-align:middle } .bi { width:11mm; flex:none }
.cover { height:210mm; background:#0F0F1E; color:#fff; text-align:center; padding-top:30mm; overflow:hidden }
.logo { width:35mm; height:35mm; margin:0 auto; background:#6A5CFF; border-radius:9mm; font-size:22mm; line-height:35mm }
.cover h1 { font-size:34pt; margin:8mm 0 2mm; letter-spacing:1px }
.cover .sub { color:#B9B2FF; font-size:18pt }
.cover .tag { font-size:14pt; margin-top:12mm }
.cover .ic { font-size:13mm; margin-top:28mm; letter-spacing:5mm }
.cover .cr { color:#A3A3C7; font-size:9.5pt; margin-top:14mm }
.pg { page-break-before:always; padding:0 10.5mm 10mm }
.top { background:#6A5CFF; color:#fff; font-weight:700; font-size:17pt; padding:3mm 10.5mm; margin:0 -10.5mm 6mm }
h2 { font-size:19pt; margin:0 0 4mm }
.card { border-radius:5mm; color:#fff; padding:3.5mm 4mm; margin-bottom:3mm; display:flex; gap:4mm; align-items:center; break-inside:avoid }
.rond { flex:none; width:15mm; height:15mm; border-radius:50%; background:#fff; text-align:center; font-size:8.5mm; line-height:15mm }
.card b { display:block; font-size:13.5pt } .card span { font-size:11.5pt }
.ban { border-radius:5mm; color:#fff; padding:4mm; display:flex; gap:4mm; align-items:center; font-weight:700; font-size:15pt; margin-bottom:5mm; text-transform:uppercase; break-inside:avoid }
.ban .e { font-size:11mm }
.intro { margin:0 0 4mm }
.st { display:flex; gap:4mm; margin-bottom:3.5mm; break-inside:avoid }
.num { flex:none; width:10mm; height:10mm; border-radius:50%; color:#fff; font-weight:700; text-align:center; line-height:10mm }
.it { display:flex; gap:4mm; margin-bottom:3.5mm; break-inside:avoid }
.it .rond { width:11mm; height:11mm; line-height:11mm; font-size:6.5mm; background:#F6F5FF }
.it b { display:block } .it span { color:#6B6B80; font-size:12pt }
.opt { margin-bottom:4mm; break-inside:avoid }
.opt .bar { background:#1E4FB4; color:#fff; font-weight:700; border-radius:3mm; padding:1.5mm 3mm; font-size:12pt }
.opt p { margin:1.5mm 1mm 0; font-size:11.5pt } .opt .k { color:#1F7A4D }
.q { color:#EF6C00; font-weight:700; font-size:14pt; margin-top:4mm }
.contact { background:#2E7D32; color:#fff; border-radius:5mm; padding:4mm 5mm; margin-top:6mm; break-inside:avoid }
"""


def chaines(t):
    """Liste ordonnee des textes a traduire (meme ordre pour toutes les langues)."""
    out = [t["subtitle"], t["tag"], t["credit"], t["footer"], t["how_title"]]
    for _, _, a, b in t["how"]:
        out += [a, b]
    for _, _, titre, intro, parts in t["sections"]:
        out += [titre, intro]
        for p in parts:
            out += [p[1]] if p[0] == "step" else [p[2], p[3]]
    out.append(t["options_title"])
    for _, a, b, c in t["options"]:
        out += [a, b, c]
    out += [t["contact"], t["faq_title"]]
    for q, r in t["faq"]:
        out += [q, r]
    return out


def lire(code):
    d = {}
    with open(os.path.join(ICI, "textes", code + ".txt"), encoding="utf-8") as f:
        for ln in f:
            ln = ln.rstrip("\n")
            if not ln.strip():
                continue
            n, _, txt = ln.partition("\t")
            d[int(n)] = txt.replace("\\n", "\n")
    return d


EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿⬀-⯿←-⇿][️‍]*")
_IMG = {}


def img(em, cls="ei"):
    """Emoji -> petite image PNG (Segoe UI Emoji) integree : bien plus leger qu'une police emoji dans le PDF."""
    if em not in _IMG:
        im = Image.open(mm.emoji_png(em)).convert("RGBA")
        im.thumbnail((72, 72))
        b = io.BytesIO()
        im.save(b, "PNG", optimize=True)
        _IMG[em] = "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()
    return f'<img class="{cls}" src="{_IMG[em]}">'


def e(s):
    t = html.escape(s).replace("\n", "<br>")
    return EMOJI.sub(lambda m: img(m.group(0)), t)


def construire(code):
    en = mm.TEXTS["en"]
    src = chaines(en)
    tr = lire(code)
    manque = [i for i in range(len(src)) if src[i] and i not in tr]
    if manque:
        print(code, ": textes manquants", manque[:20])
        sys.exit(1)
    k = [0]

    def T():
        i = k[0]
        k[0] += 1
        return tr.get(i, src[i]) if src[i] else ""

    subtitle, tag, credit, _footer, how_title = T(), T(), T(), T(), T()
    rtl = code == "ar"
    css = CSS.replace("FONT", POLICE.get(code, "'Segoe UI'"))
    h = [f'<!doctype html><html lang="{code}" dir="{"rtl" if rtl else "ltr"}"><head><meta charset="utf-8">'
         f'<style>{css}</style></head><body>']
    h.append(f'<div class="cover"><div class="logo">{img("✨", "big")}</div><h1 dir="ltr">CLEAN SMART</h1><div class="sub">{e(subtitle)}</div>'
             f'<div class="tag">{e(tag)}</div><div class="ic">{"".join(img(x, "cov") for x in ["🧹", "📸", "✉️", "💾", "🛡️"])}</div><div class="cr">{e(credit)}</div></div>')
    top = '<div class="top" dir="ltr">' + img("✨") + ' CLEAN SMART</div>'
    h.append(f'<div class="pg">{top}<h2>{e(how_title)}</h2>')
    for em, col, _, _ in en["how"]:
        a, b = T(), T()
        h.append(f'<div class="card" style="background:{COUL[col]}"><div class="rond">{img(em, "ri")}</div><div><b>{e(a)}</b><span>{e(b)}</span></div></div>')
    h.append("</div>")
    for em, col, _, _, parts in en["sections"]:
        titre, intro = T(), T()
        h.append(f'<div class="pg">{top}<div class="ban" style="background:{COUL[col]}">{img(em, "bi")}<span>{e(titre)}</span></div>')
        if intro:
            h.append(f'<p class="intro">{e(intro)}</p>')
        n = 0
        for p in parts:
            if p[0] == "step":
                n += 1
                h.append(f'<div class="st"><div class="num" style="background:{COUL[col]}">{n}</div><div>{e(T())}</div></div>')
            else:
                a, b = T(), T()
                h.append(f'<div class="it"><div class="rond">{img(p[1], "ri")}</div><div><b>{e(a)}</b><span>{e(b)}</span></div></div>')
        h.append("</div>")
    h.append(f'<div class="pg">{top}<h2>{e(T())}</h2>')
    for em, _, _, _ in en["options"]:
        a, b, c = T(), T(), T()
        h.append(f'<div class="opt"><div class="bar">{img(em)} {e(a)}</div><p>{e(b)}</p><p class="k">{e(c)}</p></div>')
    h.append("</div>")
    contact = T()
    h.append(f'<div class="pg">{top}<h2>{e(T())}</h2>')
    for _ in en["faq"]:
        q, r = T(), T()
        h.append(f'<div class="q">{e(q)}</div><div>{e(r)}</div>')
    h.append(f'<div class="contact">{e(contact)}</div></div></body></html>')
    assert k[0] == len(src), (k[0], len(src))
    tmp = tempfile.mkdtemp(prefix="manuel_")
    page = os.path.join(tmp, f"manuel_{code}.html")
    with open(page, "w", encoding="utf-8") as f:
        f.write("".join(h))
    assets = os.path.normpath(os.path.join(ICI, "..", "..", "app", "src", "main", "assets"))
    out = os.path.join(assets, f"manuel_{code}.pdf")
    subprocess.run([EDGE, "--headless", "--disable-gpu", "--no-pdf-header-footer", f"--user-data-dir={tmp}\\p",
                    f"--print-to-pdf={out}", "file:///" + page.replace("\\", "/")], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)
    print("OK", out, os.path.getsize(out) // 1024, "Ko")


if __name__ == "__main__":
    if sys.argv[1:] == ["numeros"]:
        src = chaines(mm.TEXTS["en"])
        with open(os.path.join(ICI, "textes", "numeros_en.txt"), "w", encoding="utf-8") as f:
            for i, s in enumerate(src):
                if s:
                    f.write(f"{i}\t{s.replace(chr(10), chr(92) + 'n')}\n")
        print(len(src), "textes")
    else:
        for code in (sys.argv[1:] or LANGUES):
            construire(code)
