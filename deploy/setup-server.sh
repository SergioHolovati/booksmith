#!/usr/bin/env bash
# Prepara um VPS Ubuntu 24.04 recém-criado para o Booksmith. Roda UMA vez, como root:
#   bash setup-server.sh "<chave pública SSH>"
# O que faz, nesta ordem: fuso horário, atualizações (e as automáticas de segurança), swap, Docker, rotação de
# logs do Docker, chave SSH + SSH só por chave (sem senha), firewall (22, 80, 443), fail2ban no SSH, pastas.
# Idempotente: pode rodar de novo sem estragar nada.
set -euo pipefail

PUBKEY="${1:?Passe a chave pública SSH entre aspas como primeiro argumento}"
case "$PUBKEY" in
  ssh-ed25519*|ssh-rsa*|ecdsa-*) ;;
  *) echo "Isso não parece uma chave pública SSH: $PUBKEY"; exit 1 ;;
esac

echo "== Fuso horário"
timedatectl set-timezone America/Sao_Paulo

echo "== Pacotes"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get -y -o Dpkg::Options::="--force-confold" upgrade
apt-get install -y --no-install-recommends ufw fail2ban unattended-upgrades ca-certificates curl git rsync tar
# Atualizações de segurança automáticas (só as de segurança; nada de versão nova por conta própria)
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF

echo "== Swap (2 GB): Java + corretor + banco cabem em 8 GB, mas o swap evita o OOM killer num pico"
if ! swapon --show | grep -q '^/swapfile'; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
sysctl -w vm.swappiness=10 >/dev/null
grep -q 'vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' >> /etc/sysctl.conf

echo "== Docker"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
docker compose version >/dev/null
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "5" },
  "live-restore": true
}
EOF
systemctl enable docker >/dev/null
systemctl restart docker

echo "== SSH: chave do notebook e nada de senha"
mkdir -p /root/.ssh
chmod 700 /root/.ssh
touch /root/.ssh/authorized_keys
grep -qF "$PUBKEY" /root/.ssh/authorized_keys || echo "$PUBKEY" >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys
cat > /etc/ssh/sshd_config.d/90-booksmith.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
PubkeyAuthentication yes
X11Forwarding no
MaxAuthTries 4
EOF
sshd -t
systemctl reload ssh || systemctl reload sshd

echo "== Firewall: só 22, 80 e 443"
ufw --force reset >/dev/null
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable

echo "== fail2ban no SSH"
cat > /etc/fail2ban/jail.local <<'EOF'
[DEFAULT]
bantime  = 1h
findtime = 10m
maxretry = 5

[sshd]
enabled = true
EOF
systemctl enable fail2ban >/dev/null
systemctl restart fail2ban

echo "== Pastas"
mkdir -p /opt/booksmith/src /opt/booksmith/backups/postgres /opt/booksmith/backups/media /opt/booksmith/releases/incoming
chmod 700 /opt/booksmith
if [ ! -f /opt/booksmith/.env ]; then
  echo
  echo "Falta o /opt/booksmith/.env: copie o deploy/env.prod.example preenchido para lá e rode: chmod 600 /opt/booksmith/.env"
fi

echo
echo "Servidor pronto. Teste o acesso por chave numa OUTRA janela antes de fechar esta:"
echo "  ssh -i ~/.ssh/booksmith_vps root@$(hostname -I | awk '{print $1}')"
