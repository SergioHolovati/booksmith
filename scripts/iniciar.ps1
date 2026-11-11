<#
.SYNOPSIS
  Sobe o ambiente local inteiro do Booksmith: Docker (banco, imagens, backups), API (porta 8090) e app (porta 4200).
  Pode rodar de novo a qualquer momento: o que já está no ar é mantido.
.EXAMPLE
  .\scripts\iniciar.ps1
#>
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$project = Split-Path $root -Parent
$logs = Join-Path $project "booksmith-data"
New-Item -ItemType Directory -Force $logs | Out-Null

function Listening($port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

# 1. Docker: os containers do Booksmith voltam sozinhos quando o Docker sobe (restart: unless-stopped)
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Iniciando o Docker Desktop..."
    Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
}
Write-Host -NoNewline "Esperando o banco"
for ($i = 0; $i -lt 90; $i++) {
    $status = docker ps --filter "name=booksmith-postgres-1" --format "{{.Status}}" 2>$null
    if ($status -match "healthy") { break }
    if ($i -eq 20 -and -not $status) { docker compose -f (Join-Path $root "docker-compose.yml") up -d postgres redis minio backup-db backup-media spellcheck *> $null }
    Write-Host -NoNewline "."
    Start-Sleep -Seconds 3
}
Write-Host " ok"

# 2. API (Spring Boot) na 8090
if (Listening 8090) {
    Write-Host "API já está no ar (8090)."
} else {
    # JDK 25: o portátil em tools\jdk-25 (oficial, conferido) quando existe; senão o instalado
    $portable = Join-Path $project "tools\jdk-25"
    $env:JAVA_HOME = if (Test-Path (Join-Path $portable "bin\java.exe")) { $portable } else { "C:\Program Files\Java\latest\jdk-25" }
    if (-not $env:GOOGLE_CLIENT_ID) { $env:GOOGLE_CLIENT_ID = "dev-local" }
    if (-not $env:GOOGLE_CLIENT_SECRET) { $env:GOOGLE_CLIENT_SECRET = "dev-local" }
    Start-Process -FilePath (Join-Path $root "mvnw.cmd") -ArgumentList "-q", "-DskipTests", "spring-boot:run" `
        -WorkingDirectory $root -RedirectStandardOutput (Join-Path $logs "api.log") -WindowStyle Hidden
    Write-Host -NoNewline "Iniciando a API"
    for ($i = 0; $i -lt 60; $i++) {
        try { if ((Invoke-RestMethod "http://localhost:8090/actuator/health" -TimeoutSec 2).status -eq "UP") { break } } catch { }
        Write-Host -NoNewline "."
        Start-Sleep -Seconds 3
    }
    Write-Host " ok"
}

# 3. App (Angular) na 4200
if (Listening 4200) {
    Write-Host "App já está no ar (4200)."
} else {
    $web = Join-Path $project "booksmith-web"
    $log = Join-Path $logs "web.log"
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "npx ng serve --port 4200 --host 0.0.0.0 > `"$log`" 2>&1" `
        -WorkingDirectory $web -WindowStyle Hidden
    Write-Host -NoNewline "Iniciando o app"
    for ($i = 0; $i -lt 60; $i++) {
        if (Listening 4200) { break }
        Write-Host -NoNewline "."
        Start-Sleep -Seconds 3
    }
    Write-Host " ok"
}

Write-Host ""
Write-Host "Pronto: http://localhost:4200 (API em http://localhost:8090; logs em $logs)"
# No celular, no mesmo Wi-Fi: o endereço do computador na rede local
$lan = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.InterfaceAlias -match "Wi-Fi|Ethernet" -and $_.IPAddress -notmatch "^(127|169\.254|172\.)" } |
    Select-Object -First 1 -ExpandProperty IPAddress
if ($lan) { Write-Host "No celular (mesmo Wi-Fi): http://${lan}:4200" }
