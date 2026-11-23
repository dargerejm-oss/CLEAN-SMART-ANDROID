"""Extraction / remplacement des textes francais de Clean Smart -> Tr.t("...", args).

usage :
  python i18n.py extract <src_dir> <out.json>
  python i18n.py apply   <src_dir>
"""
import json, os, re, sys

BASE = "app/src/main/java/com/jmdargere/cleansmart"
FILES = {  # fichier -> mode ("all" = tout, "lines" = seulement les lignes qui matchent LINE_ONLY)
    "ui/App.kt": "all", "ui/GooglePhotos.kt": "all", "ui/MailGuide.kt": "all", "ui/TriRapide.kt": "all",
    "ui/Components.kt": "all", "ui/Manual.kt": "all", "ui/Bienvenue.kt": "all",
    "AppViewModel.kt": "all", "data/Night.kt": "all",
    "data/MailCleaner.kt": "lines", "data/GoogleExport.kt": "lines",
    "data/MediaScanner.kt": "lines", "data/Backup.kt": "lines",
}
LINE_ONLY = re.compile(r'(return "|IllegalStateException\(|MailException\(|progress\(|fun resume)')
SKIP_LINE = re.compile(r'(getSharedPreferences|putString\(|getString\(|putInt\(|getInt\(|getBoolean\(|putBoolean\(|putLong\(|getLong\(|'
                       r'putStringSet|getStringSet|Regex\(|SimpleDateFormat\(|note ==|Uri\.parse\(|startsWith\(|endsWith\(|'
                       r'label = "|MimeTypes|createFile\(|dossier\(|findFile\(|Tr\.t\("[^"]*"\)\s*$|@file|package |import )')
ENUM_LINE = re.compile(r'^\s*[A-Z][A-Z_]+\("')
ACCENT = re.compile(r'[À-ÿœŒ’«»…€]')
LETTER = re.compile(r'[A-Za-zÀ-ÿ]')


def is_ui(static):
    s = static.strip()
    if not LETTER.search(s):
        return False
    if "://" in s or s.startswith("http") or re.fullmatch(r'[a-z]+/[a-z0-9.+*-]+', s):
        return False
    if re.fullmatch(r'%[0-9a-z]+', s):
        return False
    if " " in s or ACCENT.search(s) or re.search(r'[^\x00-\x7f]', s):
        return True
    if s[:1].isupper() and len(s) > 1:
        return True
    return False


def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$", "'": "'"}.get(n, n))
            i += 2
        else:
            out.append(c); i += 1
    return "".join(out)


class Parser:
    """Parcourt le code Kotlin et renvoie le texte transforme + la liste des cles."""

    def __init__(self, text, mode):
        self.t, self.i, self.mode, self.keys = text, 0, mode, []

    def line_of(self, pos):
        a = self.t.rfind("\n", 0, pos) + 1
        b = self.t.find("\n", pos)
        return self.t[a: b if b >= 0 else len(self.t)]

    def code(self, end_char=None):
        """Lit du code jusqu'a end_char (pour l'interieur de ${...}), renvoie le code transforme."""
        out, depth, t = [], 0, self.t
        while self.i < len(t):
            c = t[self.i]
            if end_char and c == "}" and depth == 0:
                return "".join(out)
            if c == "{": depth += 1
            if c == "}": depth -= 1
            if t.startswith("//", self.i):
                j = t.find("\n", self.i); j = len(t) if j < 0 else j
                out.append(t[self.i:j]); self.i = j; continue
            if t.startswith("/*", self.i):
                j = t.find("*/", self.i) + 2
                out.append(t[self.i:j]); self.i = j; continue
            if t.startswith('"""', self.i):
                j = t.find('"""', self.i + 3) + 3
                while j < len(t) and t[j] == '"': j += 1
                out.append(t[self.i:j]); self.i = j; continue
            if c == "'":
                m = re.match(r"'(\\.|[^'\\])'", t[self.i:])
                if m:
                    out.append(m.group(0)); self.i += len(m.group(0)); continue
            if c == '"':
                out.append(self.string()); continue
            out.append(c); self.i += 1
        return "".join(out)

    def string(self):
        start = self.i
        t = self.t
        self.i += 1
        parts, args, cur = [], [], []
        while True:
            c = t[self.i]
            if c == "\\":
                cur.append(t[self.i:self.i + 2]); self.i += 2; continue
            if c == '"':
                self.i += 1; break
            if c == "$" and t[self.i + 1] == "{":
                self.i += 2
                expr = self.code(end_char="}")
                self.i += 1
                args.append(expr.strip()); cur.append(f"%{len(args)}"); continue
            if c == "$" and re.match(r"[A-Za-z_]", t[self.i + 1]):
                m = re.match(r"[A-Za-z_][A-Za-z0-9_]*", t[self.i + 1:])
                args.append(m.group(0)); cur.append(f"%{len(args)}"); self.i += 1 + len(m.group(0)); continue
            cur.append(c); self.i += 1
        raw = "".join(cur)
        original = t[start:self.i]
        line = self.line_of(start)
        before = t[max(0, start - 6):start]
        # entrees d'enum (Cat, Provider) : traduites par des getters, on note juste la cle
        a, b = t.find("enum class Provider"), t.find("val label: String get()")
        if ENUM_LINE.match(line) or (0 <= a < start < b):
            if not args and is_ui(unescape(raw)):
                self.keys.append(unescape(raw))
            return self.rebuild(original, raw, args, translate=False)
        if before.endswith("Tr.t(") or SKIP_LINE.search(line):
            return self.rebuild(original, raw, args, translate=False)
        if self.mode == "lines" and not LINE_ONLY.search(line):
            return self.rebuild(original, raw, args, translate=False)
        static = re.sub(r"%\d+", "", raw)
        if not is_ui(unescape(static)):
            return self.rebuild(original, raw, args, translate=False)
        self.keys.append(unescape(raw))
        return self.rebuild(original, raw, args, translate=True)

    def rebuild(self, original, raw, args, translate):
        if not translate:
            if not args:
                return original
            # on remet le texte avec ses ${...} (les expressions ont peut-etre ete transformees)
            s = raw
            for k in range(len(args), 0, -1):
                s = s.replace(f"%{k}", "${" + args[k - 1] + "}")
            return '"' + s + '"'
        if args:
            return 'Tr.t("' + raw + '", ' + ", ".join(args) + ")"
        return 'Tr.t("' + raw + '")'


def process(path, mode, apply):
    text = open(path, encoding="utf-8").read()
    p = Parser(text, mode)
    out = p.code()
    if apply and out != text:
        if "import com.jmdargere.cleansmart.Tr" not in out and not path.replace("\\", "/").endswith(BASE + "/AppViewModel.kt") \
                and "package com.jmdargere.cleansmart\n" not in out:
            out = re.sub(r"(package [^\n]+\n)", r"\1\nimport com.jmdargere.cleansmart.Tr\n", out, count=1)
        open(path, "w", encoding="utf-8", newline="\n").write(out)
    return p.keys


if __name__ == "__main__":
    cmd, src = sys.argv[1], sys.argv[2]
    keys = []
    for f, mode in FILES.items():
        keys += process(os.path.join(src, BASE, f), mode, cmd == "apply")
    # mots passes a plural(...) : traduits a l'interieur de plural()
    for f in FILES:
        for m in re.finditer(r'plural\([^"\n]*?"([^"]+)"(?:\s*,\s*"([^"]+)")?', open(os.path.join(src, BASE, f), encoding="utf-8").read()):
            keys += [m.group(1), m.group(2) or (m.group(1) + "s")]
    # textes deja ecrits avec Tr.t("...") dans tout le code
    for root, _, names in os.walk(os.path.join(src, BASE)):
        for n in names:
            if n.endswith(".kt"):
                for m in re.finditer(r'Tr\.t\("((?:[^"\\]|\\.)*)"', open(os.path.join(root, n), encoding="utf-8").read()):
                    keys.append(unescape(m.group(1)))
    keys += ["Floue", "Trop sombre", "Trop claire", "Trop petite"]
    uniq = sorted(set(keys))
    if cmd == "extract":
        json.dump(uniq, open(sys.argv[3], "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(len(uniq), "textes")
