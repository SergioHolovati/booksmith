# Booksmith

API Java com Spring Boot, PostgreSQL e Redis.

## Requisitos

- Java 25
- Docker e Docker Compose
- PowerShell ou terminal equivalente

## Subir tudo com Docker Compose

Execute na raiz do projeto, em `C:\personal_project\booksmith`:

```powershell
docker compose up --build
```

Servicos expostos:

- API: `http://localhost:8080`
- PostgreSQL: `localhost:5432`
- Redis: `localhost:6379`

Credenciais locais do PostgreSQL:

- Database: `booksmith`
- User: `booksmith`
- Password: `booksmith`

Para parar os containers:

```powershell
docker compose down
```

Para parar e remover os volumes locais do PostgreSQL e Redis:

```powershell
docker compose down -v
```

## Rodar a aplicacao localmente

Se quiser rodar a API pela IDE ou Maven, suba apenas as dependencias:

```powershell
docker compose up -d postgres redis
```

Depois execute dentro da pasta `app`:

```powershell
.\mvnw.cmd spring-boot:run
```

O profile padrao e `dev`.

## Variaveis de ambiente

A aplicacao ja possui defaults locais, mas aceita sobrescrita por variaveis:

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/booksmith"
$env:SPRING_DATASOURCE_USERNAME="booksmith"
$env:SPRING_DATASOURCE_PASSWORD="booksmith"
$env:SPRING_DATA_REDIS_HOST="localhost"
$env:SPRING_DATA_REDIS_PORT="6379"
$env:TOKEN_SECRET="token"
$env:EXPIRATION="86400000"
```

## Endpoints uteis

- Health check: `GET /actuator/health`
- Login: `POST /auth/login`

Para rotas diferentes de login, envie os headers:

```http
Authorization: Bearer <token>
X-Request-Trace-Id: <trace-id>
```

O `X-Request-Trace-Id` tambem e retornado no response e aparece nos logs como `requestTraceId : valor`.
