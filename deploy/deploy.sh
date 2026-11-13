#!/usr/bin/env bash
# Deploy no servidor (chamado pelo scripts\deploy.ps1, que antes manda os fontes commitados para
# /opt/booksmith/releases/incoming/{booksmith.tar,booksmith-web.tar}). Nesta ordem:
#   1. backup do banco (se ele já estiver de pé);
#   2. guarda as imagens atuais para voltar atrás;
#   3. troca os fontes;
#   4. build e sobe (o Flyway aplica as migrações novas ao subir a API);
#   5. espera a API ficar saudável; se não ficar em 3 minutos, volta às imagens anteriores.
set -euo pipefail

ROOT=/opt/booksmith
SRC=$ROOT/src
ENV_FILE=$ROOT/.env
INCOMING=$ROOT/releases/incoming
STAMP=$(date +%Y%m%d-%H%M%S)

[ -f "$ENV_FILE" ] || { echo "Falta $ENV_FILE (modelo em deploy/env.prod.example)"; exit 1; }
[ -f "$INCOMING/booksmith.tar" ] && [ -f "$INCOMING/booksmith-web.tar" ] || { echo "Faltam os fontes em $INCOMING"; exit 1; }

compose() {
  docker compose --env-file "$ENV_FILE" -f "$SRC/booksmith/deploy/compose.prod.yml" "$@"
}

echo "== 1. Backup do banco"
if docker ps --format '{{.Names}}' | grep -qx 'booksmith-postgres-1'; then
  mkdir -p "$ROOT/backups/postgres"
  docker exec booksmith-postgres-1 pg_dump -U booksmith -Fc booksmith > "$ROOT/backups/postgres/antes-deploy-$STAMP.dump"
  echo "   $ROOT/backups/postgres/antes-deploy-$STAMP.dump"
else
  echo "   (banco ainda não está de pé: primeiro deploy)"
fi

echo "== 2. Imagens atuais guardadas como :prev"
for image in booksmith-api booksmith-web; do
  docker image inspect "$image:latest" >/dev/null 2>&1 && docker tag "$image:latest" "$image:prev" || true
done

echo "== 3. Fontes"
rm -rf "$SRC.new"
mkdir -p "$SRC.new/booksmith" "$SRC.new/booksmith-web"
tar -xf "$INCOMING/booksmith.tar" -C "$SRC.new/booksmith"
tar -xf "$INCOMING/booksmith-web.tar" -C "$SRC.new/booksmith-web"
rm -rf "$SRC.old"
[ -d "$SRC" ] && mv "$SRC" "$SRC.old"
mv "$SRC.new" "$SRC"
cp "$INCOMING/booksmith.tar" "$ROOT/releases/booksmith-$STAMP.tar"
cp "$INCOMING/booksmith-web.tar" "$ROOT/releases/booksmith-web-$STAMP.tar"
ls -1t "$ROOT"/releases/booksmith-*.tar 2>/dev/null | tail -n +6 | xargs -r rm -f
ls -1t "$ROOT"/releases/booksmith-web-*.tar 2>/dev/null | tail -n +6 | xargs -r rm -f

echo "== 4. Build e subida"
compose build --pull api caddy spellcheck
compose up -d --remove-orphans

echo "== 5. Saúde da API"
healthy=false
for _ in $(seq 1 36); do
  sleep 5
  status=$(docker inspect -f '{{.State.Health.Status}}' booksmith-api-1 2>/dev/null || echo "none")
  if [ "$status" = "healthy" ]; then healthy=true; break; fi
done

if [ "$healthy" != true ]; then
  echo "   A API não ficou saudável. Últimas linhas do log:"
  docker logs --tail 60 booksmith-api-1 || true
  if docker image inspect booksmith-api:prev >/dev/null 2>&1; then
    echo "== Voltando às imagens anteriores"
    docker tag booksmith-api:prev booksmith-api:latest
    docker image inspect booksmith-web:prev >/dev/null 2>&1 && docker tag booksmith-web:prev booksmith-web:latest
    [ -d "$SRC.old" ] && { rm -rf "$SRC.failed"; mv "$SRC" "$SRC.failed"; mv "$SRC.old" "$SRC"; }
    compose up -d --no-build
    echo "   Voltou. O banco pode ter migrações novas: se a volta não subir, restaure o backup do passo 1."
  fi
  exit 1
fi

docker image prune -f >/dev/null
echo "== Deploy ok ($STAMP)"
compose ps
