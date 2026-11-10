# Booksmith API

Monólito modular em Spring Boot 4 + Spring Modulith, PostgreSQL, Redis e S3 (MinIO em desenvolvimento).
A definição completa está em [`../arquitetura-tecnica.md`](../arquitetura-tecnica.md).

## Módulos

| Módulo | Pacote | Responsabilidade |
|---|---|---|
| Common | `com.booksmith.common` | Erros (RFC 9457), rastreio de requisição, paginação, relógio |
| Identidade | `com.booksmith.identity` | Cadastro, login (e-mail/senha e Google), sessões, perfil de autor |
| Manuscrito | `com.booksmith.manuscript` | Livros e capítulos (conteúdo ProseMirror em `jsonb`), autosave com controle de versão |
| Mídia | `com.booksmith.media` | Armazenamento de imagens (capas, avatares) |
| Notificações | `com.booksmith.notification` | E-mails transacionais |

Cada módulo expõe só os subpacotes `api` e `events`; o resto é interno. O teste `ModularityTests`
falha o build se um módulo acessar o interior de outro.

## Requisitos

- Java 25
- Docker (Postgres, Redis e MinIO)

## Rodar localmente

```powershell
# 1. Segredos locais
Copy-Item .env.example .env   # e preencha GOOGLE_CLIENT_ID e GOOGLE_CLIENT_SECRET

# 2. Volumes dos dados (uma vez só; ficam fora do ciclo de vida do compose)
docker volume create booksmith_postgres-data
docker volume create booksmith_minio-data

# 3. Dependências (voltam sozinhas quando o Docker reinicia)
docker compose up -d

# 4. API (perfil dev é o padrão)
.\mvnw.cmd spring-boot:run
```

As migrações Flyway (`src/main/resources/db/migration`) criam o esquema na primeira execução.

Para subir a API também em container: `docker compose --profile app up --build`.

Para subir tudo de uma vez (Docker, API e app), mesmo depois de reiniciar o computador: `.\scripts\iniciar.ps1`.

Serviços: API `http://localhost:8090`, MinIO `http://localhost:9001` (console), Postgres `5432`, Redis `6379`.

Pelo celular, na mesma rede Wi-Fi: `http://IP-do-PC:4200` (veja o IP com `ipconfig`). O app faz proxy da API e das imagens; por isso, em desenvolvimento, as URLs de imagem são relativas (`/media/...`).

Em desenvolvimento os e-mails não são enviados: o conteúdo (código de verificação, link de senha) aparece no log.

## Dados locais e backups

O banco de desenvolvimento pode ter **livros escritos de verdade** durante os testes. Por isso:

| Proteção | Como |
|---|---|
| `docker compose down -v` não apaga dados | Postgres e MinIO usam volumes **externos** (`booksmith_postgres-data`, `booksmith_minio-data`) |
| Docker reiniciou | `restart: always`: os containers voltam sozinhos e a API se reconecta |
| Backup automático | `backup-db` (dump do banco) e `backup-media` (capas e avatares) ao subir e a cada 6 horas, em `..\booksmith-data\backups` (pasta do Windows, fora do Docker e do git). Banco: mantém 30 dias |
| Backup na hora | `.\scripts\backup.ps1 -Nome "antes-de-testar-x"` |
| Restaurar | `.\scripts\restore.ps1` lista os backups; `.\scripts\restore.ps1 -Arquivo <nome>` restaura (faz antes um backup do estado atual) |

Outra pasta de backup: defina `BOOKSMITH_BACKUP_DIR` (ex.: uma pasta sincronizada com OneDrive/Google Drive).

> Para zerar o banco de propósito, só com intenção explícita: `docker volume rm booksmith_postgres-data`
> (com os containers parados) e depois `docker volume create booksmith_postgres-data`.

## Testes

```powershell
.\mvnw.cmd test        # unitários + fronteiras dos módulos
```

```powershell
.\scripts\saude.ps1          # saúde do código: arquitetura, código limpo, segurança, dependências e segredos
.\scripts\saude.ps1 -Rapido  # o mesmo, sem rodar os testes
```

Detalhes e regras em `arquitetura-tecnica.md` §27.4. O `mvnw verify` já roda ArchUnit, PMD, duplicação e SpotBugs
e quebra o build em problema novo.

Os testes de integração (API e app juntos, em Robot Framework) ficam em [`../booksmith-tests`](../booksmith-tests).
O perfil `dev` usa limites de requisição folgados justamente para eles; staging e produção usam os limites
reais (`RATE_LIMIT_AUTH_PER_MINUTE`, padrão 20).

## API (v1)

Todas as rotas ficam em `/api/v1`. Erros seguem RFC 9457 (`application/problem+json`) com um `code` estável.

| Método | Rota | Descrição |
|---|---|---|
| POST | `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` | Sessão |
| POST | `/auth/verify-email`, `/auth/forgot-password`, `/auth/reset-password` | Conta |
| GET | `/oauth2/authorization/google` | Login com Google (fora de `/api/v1`) |
| GET | `/profile` | Usuário + perfil de autor |
| POST/PUT | `/profile/author` | Perfil público de autor |
| GET/POST | `/books` | Listar (com nº de capítulos e palavras) / criar |
| GET/PUT/DELETE | `/books/{id}` | Detalhe com capítulos / editar / excluir |
| POST | `/books/{id}/chapters` | Novo capítulo |
| PUT | `/books/{id}/chapters/order` | Reordenar capítulos |
| GET/PATCH/DELETE | `/chapters/{id}` | Capítulo com conteúdo / renomear / excluir |
| PUT | `/chapters/{id}/content` | Autosave; exige `If-Match: "<versão>"`, responde `409` em conflito |

Autenticação: `Authorization: Bearer <token>`. O header opcional `X-Request-Trace-Id` aparece nos logs.
