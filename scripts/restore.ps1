<#
.SYNOPSIS
  Restaura o banco a partir de um backup. Antes, faz um backup do estado atual (nada se perde).
.EXAMPLE
  .\scripts\restore.ps1                                        # lista os backups disponíveis
  .\scripts\restore.ps1 -Arquivo booksmith-20261106-163641.dump
#>
param([string]$Arquivo)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$backupDir = if ($env:BOOKSMITH_BACKUP_DIR) { $env:BOOKSMITH_BACKUP_DIR } else { Join-Path $root "..\booksmith-data\backups" }
$pgDir = Join-Path $backupDir "postgres"

if (-not $Arquivo) {
    Write-Host "Backups disponíveis (mais recentes primeiro):"
    Get-ChildItem $pgDir -Filter "booksmith-*.dump" | Sort-Object LastWriteTime -Descending |
        Select-Object -First 20 Name, LastWriteTime, @{ n = "KB"; e = { [math]::Round($_.Length / 1KB) } } | Format-Table
    Write-Host "Use: .\scripts\restore.ps1 -Arquivo <nome>"
    exit 0
}

if (-not (Test-Path (Join-Path $pgDir $Arquivo))) { throw "Backup não encontrado: $Arquivo" }

$resposta = Read-Host "Isto substitui TODO o banco atual pelo backup $Arquivo. Digite RESTAURAR para continuar"
if ($resposta -ne "RESTAURAR") { Write-Host "Cancelado."; exit 1 }

& (Join-Path $PSScriptRoot "backup.ps1") -Nome "antes-de-restaurar"

docker exec booksmith-backup-db-1 pg_restore --clean --if-exists --no-owner -d booksmith "/backups/postgres/$Arquivo"
if ($LASTEXITCODE -ne 0) { throw "A restauração terminou com erros. O backup do estado anterior foi salvo (antes-de-restaurar)." }

Write-Host "Banco restaurado de $Arquivo. Reinicie a API para limpar caches."
