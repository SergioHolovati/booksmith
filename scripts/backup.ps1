<#
.SYNOPSIS
  Backup imediato do banco e das imagens (além dos automáticos a cada 6 horas).
.EXAMPLE
  .\scripts\backup.ps1
  .\scripts\backup.ps1 -Nome "antes-de-testar-importacao"
#>
param([string]$Nome = "manual")

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$backupDir = if ($env:BOOKSMITH_BACKUP_DIR) { $env:BOOKSMITH_BACKUP_DIR } else { Join-Path $root "..\booksmith-data\backups" }
$backupDir = (New-Item -ItemType Directory -Force (Join-Path $backupDir "postgres")).Parent.FullName

$ts = Get-Date -Format "yyyyMMdd-HHmmss"
$file = "booksmith-$ts-$Nome.dump"

# pg_dump grava dentro do container, na pasta montada; nada passa pelo console (evita corromper o binário)
docker exec booksmith-backup-db-1 pg_dump -Fc -f "/backups/postgres/$file"
if ($LASTEXITCODE -ne 0) { throw "Falha no backup do banco. O Docker e o container backup-db estão rodando?" }

docker exec booksmith-backup-media-1 mc mirror --overwrite --quiet local/booksmith /backups/media | Out-Null

$size = [math]::Round((Get-Item (Join-Path $backupDir "postgres\$file")).Length / 1KB)
Write-Host "Backup criado: $backupDir\postgres\$file ($size KB) + imagens em $backupDir\media"
