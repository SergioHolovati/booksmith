"""Origem das dependências: proteção contra pacote falso ("slopsquatting", nome inventado por IA e registrado por
golpistas, e "typosquatting", nome quase igual ao verdadeiro).

Confere:
  1. npm: todo pacote do package-lock.json veio do registro oficial e tem hash de integridade;
  2. Maven: o pom.xml não aponta para repositórios fora do Maven Central;
  3. pip: as versões dos testes estão fixadas (==);
  4. dependências DIRETAS (as que alguém escolheu, onde o golpe entra): existem no registro oficial, têm
     histórico (versões e idade) e não são novas demais. Pacote recém-criado e com uma versão só é o padrão
     do golpe.

Uso: python dependencias-origem.py <pom.xml> <package.json> <package-lock.json> <requirements.txt>
Sai com código 1 se algo estiver fora do esperado.
"""
import datetime
import json
import re
import sys
import time
import urllib.error
import urllib.request

MIN_AGE_DAYS = 180       # pacote direto com menos de 6 meses de existência: conferir à mão antes de aceitar
MIN_VERSIONS = 5


class NotFound(Exception):
    """O registro respondeu que o pacote não existe (404): sinal de nome inventado."""


def fetch(url):
    """Baixa com até 4 tentativas. 404 vira NotFound; falha de rede persistente sobe como erro de rede."""
    request = urllib.request.Request(url, headers={"User-Agent": "booksmith-saude/1.0"})
    for attempt in range(4):
        try:
            return urllib.request.urlopen(request, timeout=60).read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise NotFound(url) from e
            if attempt == 3:
                raise
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == 3:
                raise
        time.sleep(2 * (attempt + 1))


def get_json(url):
    return json.loads(fetch(url))


def failure(kind, name, error):
    if isinstance(error, NotFound):
        return f"{kind} {name}: NÃO EXISTE no registro oficial (possível nome inventado)"
    return f"{kind} {name}: não consegui conferir agora (rede: {error}); rode de novo"


def days_since(iso):
    when = datetime.datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return (datetime.datetime.now(datetime.timezone.utc) - when).days


def main():
    pom_path, pkg_path, lock_path, req_path = sys.argv[1:5]
    problems, notes = [], []

    # 1. npm: origem e integridade de tudo o que está no lock
    lock = json.load(open(lock_path, encoding="utf-8"))
    entries = [(k, v) for k, v in lock.get("packages", {}).items() if k]
    for key, info in entries:
        resolved = info.get("resolved")
        if info.get("link"):
            continue
        if resolved and not resolved.startswith("https://registry.npmjs.org/"):
            problems.append(f"npm {key}: baixado de fora do registro oficial ({resolved})")
        if resolved and not info.get("integrity"):
            problems.append(f"npm {key}: sem hash de integridade")
    notes.append(f"npm: {len(entries)} pacotes no lock, todos conferidos quanto à origem e ao hash")

    # 2. Maven: só o Central
    pom = open(pom_path, encoding="utf-8").read()
    if re.search(r"<(plugin)?[rR]epositor(y|ies)>", pom):
        problems.append("Maven: o pom.xml declara repositório próprio; dependências devem vir só do Maven Central")

    # 3. pip: versões fixadas
    for line in open(req_path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "==" not in line:
            problems.append(f"pip: versão não fixada em '{line}'")

    # 4. Dependências diretas: existem, têm histórico e não são novas demais
    pkg = json.load(open(pkg_path, encoding="utf-8"))
    direct_npm = sorted({**pkg.get("dependencies", {}), **pkg.get("devDependencies", {})})
    for name in direct_npm:
        try:
            meta = get_json(f"https://registry.npmjs.org/{name.replace('/', '%2F')}")
        except Exception as e:
            problems.append(failure("npm", name, e))
            continue
        age = days_since(meta["time"]["created"])
        versions = len(meta.get("versions", {}))
        if age < MIN_AGE_DAYS or versions < MIN_VERSIONS:
            problems.append(f"npm {name}: existe há {age} dias, {versions} versões (conferir à mão antes de aceitar)")
    notes.append(f"npm: {len(direct_npm)} dependências diretas existem, com histórico")

    deps = re.findall(r"<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>", pom)
    for group, artifact in deps:
        base = f"https://repo1.maven.org/maven2/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
        try:
            meta = fetch(base).decode()
        except Exception as e:
            problems.append(failure("Maven", f"{group}:{artifact}", e))
            continue
        versions = len(re.findall("<version>", meta))
        if versions < MIN_VERSIONS:
            problems.append(f"Maven {group}:{artifact}: só {versions} versões no Central (conferir à mão)")
    notes.append(f"Maven: {len(deps)} dependências diretas existem no Central, com histórico")

    for line in open(req_path, encoding="utf-8"):
        m = re.match(r"\s*([A-Za-z0-9_.\-]+)==", line)
        if not m:
            continue
        try:
            meta = get_json(f"https://pypi.org/pypi/{m.group(1)}/json")
        except Exception as e:
            problems.append(failure("PyPI", m.group(1), e))
            continue
        uploads = [f["upload_time_iso_8601"] for files in meta["releases"].values() for f in files]
        if not uploads or days_since(min(uploads)) < MIN_AGE_DAYS or len(meta["releases"]) < MIN_VERSIONS:
            problems.append(f"PyPI {m.group(1)}: recente ou com poucas versões (conferir à mão)")
    notes.append("PyPI: dependências dos testes existem, com histórico")

    for n in notes:
        print(n)
    for p in problems:
        print("  PROBLEMA", p)
    print(f"Origem das dependências: {len(problems)} problemas")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
