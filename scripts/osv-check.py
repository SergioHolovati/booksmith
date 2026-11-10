"""Dependências com falha de segurança conhecida, pela base pública do OSV (osv.dev, do Google).

Lê as versões exatas que o projeto usa (Maven resolvido, package-lock.json do app, requirements dos testes) e
consulta a API em lote. Só manda nome e versão dos pacotes: nada do código nem dos dados.

Uso: python osv-check.py <maven-dependency-list> <package-lock.json> <requirements.txt> <aceitos.txt> [saida.txt]
Sai com código 1 se houver vulnerabilidade fora da lista de riscos aceitos (config/osv-aceitos.txt), ou se a
lista de dependências vier quase vazia (um "zero" que não leu nada não vale como aprovado).
"""
import json
import re
import sys
import urllib.request

API = "https://api.osv.dev/v1/querybatch"
MIN_PACKAGES = 100


def maven(path):
    pkgs = set()
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.search(r"([\w.\-]+):([\w.\-]+):jar(?::[\w\-]+)?:([\w.\-]+):(compile|runtime|test|provided)", line)
        if m:
            pkgs.add(("Maven", f"{m.group(1)}:{m.group(2)}", m.group(3)))
    return pkgs


def npm(path):
    lock = json.load(open(path, encoding="utf-8"))
    pkgs = set()
    for key, info in lock.get("packages", {}).items():
        if key and "version" in info:
            pkgs.add(("npm", key.split("node_modules/")[-1], info["version"]))
    return pkgs


def pypi(path):
    pkgs = set()
    for line in open(path, encoding="utf-8"):
        m = re.match(r"\s*([A-Za-z0-9_.\-]+)==([\w.\-]+)", line)
        if m:
            pkgs.add(("PyPI", m.group(1), m.group(2)))
    return pkgs


def accepted(path):
    keys = set()
    for line in open(path, encoding="utf-8"):
        key = line.split("|")[0].strip()
        if key and not key.startswith("#"):
            keys.add(key)
    return keys


def query(pkgs):
    pkgs = sorted(pkgs)
    found = []
    for start in range(0, len(pkgs), 500):
        batch = pkgs[start:start + 500]
        body = {"queries": [{"package": {"ecosystem": e, "name": n}, "version": v} for e, n, v in batch]}
        req = urllib.request.Request(API, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        results = json.load(urllib.request.urlopen(req, timeout=60))["results"]
        for (eco, name, version), result in zip(batch, results):
            for vuln in result.get("vulns", []):
                found.append((eco, name, version, vuln["id"]))
    return found


def main():
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    pkgs = maven(sys.argv[1]) | npm(sys.argv[2]) | pypi(sys.argv[3])
    ok = accepted(sys.argv[4])
    found = query(pkgs)
    new = [f for f in found if f"{f[0]} {f[1]}@{f[2]}" not in ok]
    lines = [f"OSV: {len(pkgs)} pacotes conferidos (Maven, npm, PyPI); {len(found)} vulnerabilidades conhecidas, "
             f"{len(found) - len(new)} em riscos aceitos, {len(new)} novas"]
    if len(pkgs) < MIN_PACKAGES:
        lines.append("ATENÇÃO: poucos pacotes lidos; a lista de dependências pode não ter sido gerada")
        new.append(("?", "lista de dependências", "?", "vazia"))
    for eco, name, version, vid in sorted(new):
        lines.append(f"  NOVA  {eco:6} {name}@{version}  {vid}  https://osv.dev/vulnerability/{vid}")
    text = "\n".join(lines)
    print(text)
    if len(sys.argv) > 5:
        open(sys.argv[5], "w", encoding="utf-8").write(text + "\n")
    sys.exit(1 if new else 0)


if __name__ == "__main__":
    main()
