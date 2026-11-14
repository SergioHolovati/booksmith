#!/usr/bin/env bash
# Grava no /opt/booksmith/.env as linhas CHAVE=valor lidas da entrada padrão (uma por linha), substituindo a chave
# se já existir. Os valores chegam pelo túnel SSH e não aparecem em argumentos, logs nem na tela:
#   grep -E '^(GOOGLE_CLIENT_ID|...)=' .env | ssh root@servidor bash /opt/booksmith/apply-env-lines.sh
# Mostra só quantas chaves foram gravadas e quantos PREENCHER restam.
set -euo pipefail
ENV_FILE=/opt/booksmith/.env
[ -f "$ENV_FILE" ] || { echo "Falta $ENV_FILE"; exit 1; }
umask 077
count=0
while IFS= read -r line; do
  line="${line%$'\r'}"
  [ -z "$line" ] && continue
  key="${line%%=*}"
  tmp=$(mktemp)
  grep -v "^${key}=" "$ENV_FILE" > "$tmp" || true
  printf '%s\n' "$line" >> "$tmp"
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
  count=$((count + 1))
done
chmod 600 "$ENV_FILE"
echo "gravadas: $count; ainda PREENCHER: $(grep -c PREENCHER "$ENV_FILE" || true)"
