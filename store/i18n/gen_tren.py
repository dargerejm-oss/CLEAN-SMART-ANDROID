import json, sys, importlib.util

spec = importlib.util.spec_from_file_location("en", sys.argv[1]); en = importlib.util.module_from_spec(spec); spec.loader.exec_module(en)
cles = json.load(open(sys.argv[2], encoding="utf-8"))
manque = [k for k in cles if k not in en.EN]
print("manquants:", len(manque))
for k in manque:
    print(json.dumps(k, ensure_ascii=False))


def kt(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n").replace("\t", "\\t") + '"'


lignes = ["package com.jmdargere.cleansmart", "",
          "/** Dictionnaire francais -> anglais de l'interface (genere par store/i18n). */",
          "object TrEn {",
          "    val map: Map<String, String> by lazy { HashMap<String, String>(1024).apply {"]
for fr, eng in sorted(en.EN.items()):
    lignes.append(f"        put({kt(fr)}, {kt(eng)})")
lignes += ["    } }", "}", ""]
open(sys.argv[3], "w", encoding="utf-8", newline="\n").write("\n".join(lignes))
print(len(en.EN), "traductions ->", sys.argv[3])
