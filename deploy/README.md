# Booksmith em produção: roteiro do servidor

Um VPS (Hostinger KVM 2, Ubuntu 24.04, São Paulo) rodando tudo em Docker Compose. Só o Caddy (80/443) fica
exposto; o resto fala pela rede interna. O que cada arquivo faz:

| Arquivo | Para quê |
|---|---|
| `compose.prod.yml` | Os contêineres de produção: Caddy + app, API, Postgres, Redis, MinIO, corretor, backups |
| `setup-server.sh` | Prepara o servidor recém-criado (uma vez): atualizações, swap, Docker, SSH só por chave, firewall, fail2ban |
| `deploy.sh` | O deploy no servidor: backup, troca dos fontes, build, subida, saúde, volta atrás |
| `env.prod.example` | Modelo do `/opt/booksmith/.env` (segredos de produção) |
| `../scripts/deploy.ps1` | O comando do notebook que empacota o código commitado, envia e roda o `deploy.sh` |
| `../../booksmith-web/Dockerfile` e `deploy/Caddyfile` | A imagem do app: build do Angular servido pelo Caddy, com HTTPS automático e o proxy da API |

## 1. Primeiro acesso (uma vez)

1. No notebook já existe a chave `~/.ssh/booksmith_vps` (privada) e `~/.ssh/booksmith_vps.pub` (pública).
2. No painel da Hostinger, cadastre a chave pública no VPS (Configurações → Chaves SSH) **ou** cole no terminal do
   painel: `mkdir -p ~/.ssh && echo '<chave pública>' >> ~/.ssh/authorized_keys`.
3. Do notebook, confira o acesso: `ssh -i ~/.ssh/booksmith_vps root@<ip>`.
4. Mande e rode o preparo (como root):
   ```
   scp -i ~/.ssh/booksmith_vps deploy/setup-server.sh root@<ip>:/root/
   ssh -i ~/.ssh/booksmith_vps root@<ip> "sed -i 's/\r$//' setup-server.sh && bash setup-server.sh '<chave pública>'"
   ```
   Depois dele, o SSH só aceita a chave (senha desligada). Teste numa segunda janela antes de fechar a primeira.
5. Crie o `/opt/booksmith/.env` a partir do `env.prod.example`, preenchido, e `chmod 600 /opt/booksmith/.env`.
   O arquivo é editado **no servidor** (`nano /opt/booksmith/.env`); nenhum valor passa pelo chat nem pelo Git.
6. Grave o endereço em `booksmith-data\servidor.txt` (fora do Git): `root@<ip>`.

## 2. Deploy (toda vez)

No notebook, com tudo commitado e com push feito:

```
.\scripts\deploy.ps1
```

O que acontece: backup do banco → fontes novos → build das imagens → `up -d` (o Flyway aplica as migrações) →
espera a API ficar saudável (até 3 minutos) → se não ficar, volta às imagens anteriores. A API fica fora por
cerca de 30 segundos na troca. O primeiro deploy demora mais (baixa as imagens e compila tudo no servidor).

Se uma migração nova quebrou a volta atrás, restaure o backup feito no passo 1 do `deploy.sh`
(`/opt/booksmith/backups/postgres/antes-deploy-<data>.dump`):
`docker exec -i booksmith-postgres-1 pg_restore -U booksmith -d booksmith --clean --if-exists < arquivo.dump`.

## 3. No dia a dia

- Logs: `docker logs -f booksmith-api-1` (ou `caddy`, `postgres`…). Contêineres: `docker compose --env-file /opt/booksmith/.env -f /opt/booksmith/src/booksmith/deploy/compose.prod.yml ps`.
- Promover administrador: `docker exec -it booksmith-postgres-1 psql -U booksmith -c "update identity.users set admin = true where email = '...'"`.
- Trocar um segredo: editar o `.env`, depois `docker compose ... up -d api` (a API reinicia). Trocar o `JWT_SECRET` derruba todas as sessões (botão de pânico).
- Backups locais: `/opt/booksmith/backups/postgres` (a cada 6 h, 30 dias) e `/opt/booksmith/backups/media` (espelho diário). A cópia fora do servidor (R2 + `age` + `rclone`) entra em seguida, conforme a seção 9.4 do levantamento.

## 4. Atrás do Caddy

A API roda com `server.forward-headers-strategy=native` no perfil `prod`: o IP real vem do `X-Forwarded-For`
que o Caddy manda, e só de proxies da rede interna do Docker. Sem isso, o limite por IP trataria todo mundo como
o IP do Caddy. **Se o Cloudflare entrar na frente**, o Caddy precisa de `trusted_proxies` com as faixas do
Cloudflare e `CF-Connecting-IP`, senão o IP visto passa a ser o do Cloudflare.

## 5. Google e e-mail em produção

- Credencial OAuth **nova** no Google Cloud (a de desenvolvimento aponta para localhost), com os redirecionamentos
  `https://<site>/login/oauth2/code/google` e `https://<site>/oauth2/drive/callback`; usuários de teste cadastrados
  enquanto o app estiver em modo Teste.
- Resend: domínio verificado com SPF, DKIM e DMARC; até lá, `MAIL_FROM=Booksmith <onboarding@resend.dev>` só
  entrega para o dono da conta.
