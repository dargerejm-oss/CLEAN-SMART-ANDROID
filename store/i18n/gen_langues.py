"""Fabrique les tables de traduction de l'appli (16 langues) : app/src/main/assets/i18n/<code>.txt

Source : store/i18n/langues/cles.json (liste des textes francais, ordre fixe)
         store/i18n/langues/<code>.txt (une ligne par texte : « numero<TAB>traduction », \\n = retour a la ligne ;
         une ligne absente = l'anglais est affiche, puis le francais).
Verifie que chaque traduction garde les memes %1, %2... que le francais.

usage : python store/i18n/gen_langues.py
"""
import json, os, re, sys

ICI = os.path.dirname(os.path.abspath(__file__))
RACINE = os.path.normpath(os.path.join(ICI, "..", ".."))
SORTIE = os.path.join(RACINE, "app", "src", "main", "assets", "i18n")
cles = json.load(open(os.path.join(ICI, "langues", "cles.json"), encoding="utf-8"))
PH = re.compile(r"%\d")

os.makedirs(SORTIE, exist_ok=True)
erreurs = 0
for f in sorted(os.listdir(os.path.join(ICI, "langues"))):
    if not f.endswith(".txt"):
        continue
    code = f[:-4]
    lignes = []
    for n, l in enumerate(open(os.path.join(ICI, "langues", f), encoding="utf-8").read().split("\n"), 1):
        if not l.strip():
            continue
        num, _, tr = l.partition("\t")
        try:
            k = cles[int(num)]
        except (ValueError, IndexError):
            print(f"{code}:{n} numero invalide : {num!r}"); erreurs += 1; continue
        if sorted(PH.findall(k)) != sorted(PH.findall(tr.replace("\\n", "\n"))):
            print(f"{code}:{n} [{num}] %N different : {k!r} -> {tr!r}"); erreurs += 1
        lignes.append(k.replace("\n", "\\n") + "\t" + tr)
    open(os.path.join(SORTIE, code + ".txt"), "w", encoding="utf-8", newline="\n").write("\n".join(lignes) + "\n")
    print(f"{code} : {len(lignes)} / {len(cles)} textes")
sys.exit(1 if erreurs else 0)
