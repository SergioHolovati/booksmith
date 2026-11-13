<#
.SYNOPSIS
  Deploy do Booksmith no servidor, em um comando, a partir do notebook.

.DESCRIPTION
  Só código commitado sobe (git archive HEAD dos dois repositórios); recusa árvore suja ou commit sem push.
  Manda os fontes e o deploy.sh por SSH (chave ~/.ssh/booksmith_vps) e roda o deploy.sh lá, que faz backup,
  build, subida e volta atrás se a API não subir. Nenhum segredo sai daqui: o .env fica só no servidor.

.PARAMETER Server
  root@<ip ou host>. Padrão: o conteúdo de booksmith-data\servidor.txt (fora do Git).

.EXAMPLE
  .\scripts\deploy.ps1
  .\scripts\deploy.ps1 -Server root@203.0.113.10
  .\scripts\deploy.ps1 -SemConferir   # pula a exigência de push (não use no dia a dia)
#>
param(
    [string]$Server,
    [switch]$SemConferir
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent          # booksmith
$project = Split-Path $root -Parent               # booksmith-project
$web = Join-Path $project "booksmith-web"
$key = Join-Path $HOME ".ssh\booksmith_vps"
$serverFile = Join-Path $project "booksmith-data\servidor.txt"

if (-not $Server) {
    if (Test-Path $serverFile) { $Server = (Get-Content $serverFile -Raw).Trim() }
    else { throw "Informe -Server root@<ip> ou grave o endereço em $serverFile" }
}
if (-not (Test-Path $key)) { throw "Chave SSH não encontrada: $key" }

function Conferir-Repo($path, $branch) {
    $dirty = git -C $path status --porcelain
    if ($dirty) { throw "$path tem mudanças não commitadas. Commite (ou guarde) antes do deploy." }
    if (-not $SemConferir) {
        git -C $path fetch -q origin
        $ahead = git -C $path rev-list --count "origin/$branch..HEAD"
        if ([int]$ahead -gt 0) { throw "$path tem $ahead commit(s) sem push para origin/$branch. Faça o push antes." }
    }
    git -C $path rev-parse --short HEAD
}

Write-Host "== Conferindo os repositórios" -ForegroundColor Cyan
$apiRev = Conferir-Repo $root "main"
$webRev = Conferir-Repo $web "master"
Write-Host "   API $apiRev, app $webRev"

$tmp = Join-Path $env:TEMP "booksmith-deploy"
New-Item -ItemType Directory -Force $tmp | Out-Null
Write-Host "== Empacotando o código commitado" -ForegroundColor Cyan
git -C $root archive --format=tar -o (Join-Path $tmp "booksmith.tar") HEAD
git -C $web archive --format=tar -o (Join-Path $tmp "booksmith-web.tar") HEAD
Copy-Item (Join-Path $root "deploy\deploy.sh") (Join-Path $tmp "deploy.sh")

$ssh = @("-i", $key, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=15")
Write-Host "== Enviando para $Server" -ForegroundColor Cyan
& ssh @ssh $Server "mkdir -p /opt/booksmith/releases/incoming"
& scp @ssh (Join-Path $tmp "booksmith.tar") (Join-Path $tmp "booksmith-web.tar") (Join-Path $tmp "deploy.sh") "${Server}:/opt/booksmith/releases/incoming/"
if ($LASTEXITCODE -ne 0) { throw "Falha ao enviar os arquivos" }

Write-Host "== Deploy no servidor (backup, build, subida, saúde)" -ForegroundColor Cyan
& ssh @ssh $Server "sed -i 's/\r$//' /opt/booksmith/releases/incoming/deploy.sh && bash /opt/booksmith/releases/incoming/deploy.sh"
if ($LASTEXITCODE -ne 0) { throw "O deploy falhou (veja a saída acima). Se houve volta atrás, o site continua na versão anterior." }

Write-Host "== Pronto: API $apiRev, app $webRev no ar em $Server" -ForegroundColor Green
