#!/usr/bin/env bash
# Cria o /opt/booksmith/.env de produção NO SERVIDOR, com os segredos internos gerados na hora (nunca passam por
# chat, Git ou notebook). Só o que vem de fora (Google, Anthropic, Resend) fica para você preencher com o nano.
#   bash make-env.sh <SITE_ADDRESS> <ACME_EMAIL> <ALERT_EMAIL>
# Não sobrescreve um .env existente. Depois: nano /opt/booksmith/.env  (preencher as linhas marcadas com PREENCHER)
set -euo pipefail

SITE="${1:?Uso: make-env.sh <site, ex. srv123.hstgr.cloud> [e-mail do certificado] [e-mail dos alertas]}"
ACME="${2:-}"
ALERT="${3:-}"
ENV_FILE=/opt/booksmith/.env

if [ -f "$ENV_FILE" ]; then
  echo "$ENV_FILE já existe; não vou sobrescrever. Edite com: nano $ENV_FILE"
  exit 1
fi

gen() { openssl rand -base64 32 | tr -d '\n'; }
# Senhas sem caracteres que atrapalham em URLs e linhas de comando
pw() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 40; }

umask 077
cat > "$ENV_FILE" <<EOF
# Segredos de produção do Booksmith. Gerado em $(date -Iseconds) por deploy/make-env.sh.
# Guarde uma cópia no gerenciador de senhas. Nunca no Git.

# ---- Endereço ----
SITE_ADDRESS=$SITE
ACME_EMAIL=$ACME

# ---- Segredos da API (gerados) ----
JWT_SECRET=$(gen)
CRYPTO_KEY=$(gen)

# ---- Bancos e armazenamento (gerados) ----
POSTGRES_PASSWORD=$(pw)
REDIS_PASSWORD=$(pw)
MINIO_ROOT_USER=booksmith
MINIO_ROOT_PASSWORD=$(pw)
MEDIA_BUCKET=booksmith-media

# ---- Google (PREENCHER): credencial OAuth com os redirecionamentos
#   https://$SITE/login/oauth2/code/google  e  https://$SITE/oauth2/drive/callback
GOOGLE_CLIENT_ID=PREENCHER
GOOGLE_CLIENT_SECRET=PREENCHER

# ---- IA (PREENCHER) ----
ANTHROPIC_API_KEY=PREENCHER
AI_DAILY_LIMIT=3

# ---- E-mail (Resend) ----
# Sem domínio verificado no Resend: MAIL_ENABLED=false (os e-mails vão para o log) ou
# RESEND_API_KEY preenchida com MAIL_FROM=Booksmith <onboarding@resend.dev> (só entrega para o dono da conta).
MAIL_ENABLED=false
RESEND_API_KEY=PREENCHER
MAIL_FROM=Booksmith <onboarding@resend.dev>
ALERT_EMAIL=$ALERT

# ---- Regras do beta ----
INVITE_REQUIRED=true
MFA_REQUIRED=true
TERMS_VERSION=2026-11-13
PDF_PER_DAY=2
COVER_PDF_PER_DAY=5
EOF
chmod 600 "$ENV_FILE"
echo "Criado $ENV_FILE (chmod 600). Falta preencher: $(grep -c PREENCHER "$ENV_FILE") valores (Google, Anthropic, Resend)."
echo "Edite com: nano $ENV_FILE"
