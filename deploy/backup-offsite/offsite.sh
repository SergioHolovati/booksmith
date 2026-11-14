#!/bin/sh
# Envia ao R2, criptografados com a chave PÚBLICA do age ($AGE_RECIPIENT; a privada fica só com a dona do projeto):
#   - cada dump novo do banco (/backups/postgres/*.dump) -> r2:$R2_BUCKET/postgres/<nome>.dump.age
#   - uma vez por dia, o espelho das imagens (/backups/media) -> r2:$R2_BUCKET/media/media-<data>.tar.age
# Os nomes nunca se repetem (o bucket tem trava contra apagar/sobrescrever) e a limpeza é a regra de 30 dias do R2.
# Opcional: HEARTBEAT_URL (Healthchecks.io / UptimeRobot heartbeat) recebe um ping a cada rodada que deu certo.
set -u

: "${AGE_RECIPIENT:?defina AGE_RECIPIENT}"
: "${R2_BUCKET:?defina R2_BUCKET}"
export RCLONE_CONFIG_R2_TYPE=s3
export RCLONE_CONFIG_R2_PROVIDER=Cloudflare
export RCLONE_CONFIG_R2_ACCESS_KEY_ID="${R2_ACCESS_KEY_ID:?}"
export RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:?}"
export RCLONE_CONFIG_R2_ENDPOINT="${R2_ENDPOINT:?}"
export RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true

STATE=/backups/.offsite
mkdir -p "$STATE" /tmp/offsite

upload() {  # $1 arquivo local, $2 destino no bucket
  age -r "$AGE_RECIPIENT" -o /tmp/offsite/part.age "$1" \
    && rclone copyto --s3-no-check-bucket /tmp/offsite/part.age "r2:$R2_BUCKET/$2" \
    && rm -f /tmp/offsite/part.age
}

while true; do
  ok=true
  for dump in $(ls -1 /backups/postgres/*.dump 2>/dev/null); do
    name=$(basename "$dump")
    [ -f "$STATE/$name" ] && continue
    if upload "$dump" "postgres/$name.age"; then
      touch "$STATE/$name"; echo "R2 ok: postgres/$name.age"
    else
      ok=false; echo "R2 FALHOU: $name"
    fi
  done
  find "$STATE" -type f -mtime +40 -delete 2>/dev/null

  today=$(date +%Y%m%d)
  if [ -d /backups/media ] && [ ! -f "$STATE/media-$today" ]; then
    if tar -cf /tmp/offsite/media.tar -C /backups media && upload /tmp/offsite/media.tar "media/media-$today.tar.age"; then
      touch "$STATE/media-$today"; echo "R2 ok: media/media-$today.tar.age"
    else
      ok=false; echo "R2 FALHOU: imagens $today"
    fi
    rm -f /tmp/offsite/media.tar
  fi

  if [ "$ok" = true ] && [ -n "${HEARTBEAT_URL:-}" ]; then
    wget -q -O /dev/null "$HEARTBEAT_URL" || echo "heartbeat não respondeu"
  fi
  sleep 3600
done
