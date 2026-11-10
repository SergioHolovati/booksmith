<#
.SYNOPSIS
  Saúde do código do Booksmith: arquitetura, código limpo, segurança e dependências, num comando só.

.DESCRIPTION
  Roda, nesta ordem:
    1. Backend  - mvnw verify: testes, ArchUnit (camadas), Spring Modulith (módulos), PMD (código limpo, bloqueia
                  prioridade 1-2), CPD (duplicação), SpotBugs + Find Security Bugs, JaCoCo (cobertura)
    2. Frontend - ng lint (ESLint: código limpo, segurança, arquitetura) e testes (Vitest)
    3. Origem das dependências - contra pacote falso (slopsquatting/typosquatting)
    4. OSV       - dependências com falha de segurança conhecida (riscos aceitos em config/osv-aceitos.txt)
    5. Semgrep   - segurança no código Java e TypeScript (regras públicas, --metrics=off)
    6. Gitleaks  - segredos no código e em todo o histórico do Git dos três repositórios
  Cada passo diz quanto LEU, não só quanto achou: um "zero" que não leu nada não conta como aprovado.
  Relatório em booksmith-data/saude/. Sai com código 1 se algum passo falhar.

.EXAMPLE
  .\scripts\saude.ps1
  .\scripts\saude.ps1 -Rapido   # pula os testes (só análise)
#>
param([switch]$Rapido)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent                 # booksmith (API)
$project = Split-Path $root -Parent                       # booksmith-project
$web = Join-Path $project "booksmith-web"
$tests = Join-Path $project "booksmith-tests"
$out = Join-Path $project "booksmith-data\saude"
New-Item -ItemType Directory -Force $out | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
$results = [ordered]@{}

function Step($name, [scriptblock]$body) {
    Write-Host "`n== $name" -ForegroundColor Cyan
    try { $results[$name] = & $body } catch { $results[$name] = "FALHOU: $($_.Exception.Message)" }
    Write-Host "   $($results[$name])"
}

Step "Backend (testes, arquitetura, PMD, duplicação, SpotBugs)" {
    Push-Location $root
    # @(...) por fora: com um item só, o PowerShell viraria texto e o splatting passaria letra por letra
    $goals = @(if ($Rapido) { "verify", "-DskipTests" } else { "verify" })
    & .\mvnw.cmd -q @goals *> (Join-Path $out "backend.log")
    $ok = $LASTEXITCODE -eq 0
    Pop-Location
    $avisos = 0
    $pmd = Join-Path $root "target\pmd.xml"
    if (Test-Path $pmd) { $avisos = ([xml](Get-Content $pmd -Raw)).pmd.file.violation.Count }
    if ($ok) { "ok ($avisos avisos de complexidade para refatorar aos poucos: target/pmd.xml)" } else { "FALHOU (ver booksmith-data/saude/backend.log)" }
}

Step "Frontend (ESLint e testes)" {
    Push-Location $web
    npx ng lint *> (Join-Path $out "lint.log")
    $lint = $LASTEXITCODE -eq 0
    $test = $true
    if (-not $Rapido) { npx ng test --watch=false *> (Join-Path $out "frontend-testes.log"); $test = $LASTEXITCODE -eq 0 }
    Pop-Location
    $warn = (Select-String -Path (Join-Path $out "lint.log") -Pattern "warning" -SimpleMatch).Count
    if ($lint -and $test) { "ok ($warn avisos de complexidade)" } else { "FALHOU (lint: $lint, testes: $test; ver lint.log)" }
}

Step "Origem das dependências (pacote falso)" {
    python (Join-Path $root "scripts\dependencias-origem.py") (Join-Path $root "pom.xml") (Join-Path $web "package.json") `
        (Join-Path $web "package-lock.json") (Join-Path $tests "requirements.txt") *> (Join-Path $out "origem.log")
    $last = (Get-Content (Join-Path $out "origem.log") | Select-Object -Last 1)
    if ($LASTEXITCODE -eq 0) { "ok - $last" } else { "FALHOU - $last (ver origem.log)" }
}

Step "Dependências com falha conhecida (OSV)" {
    $deps = Join-Path $out "maven-deps.txt"
    Push-Location $root
    & .\mvnw.cmd -q dependency:list "-DoutputFile=$deps" -DincludeScope=test *> $null
    Pop-Location
    python (Join-Path $root "scripts\osv-check.py") $deps (Join-Path $web "package-lock.json") (Join-Path $tests "requirements.txt") `
        (Join-Path $root "config\osv-aceitos.txt") (Join-Path $out "osv.txt") *> $null
    $first = (Get-Content (Join-Path $out "osv.txt") | Select-Object -First 1)
    if ($LASTEXITCODE -eq 0) { "ok - $first" } else { "FALHOU - $first (ver osv.txt)" }
}

Step "Segurança no código (Semgrep)" {
    $report = Join-Path $out "semgrep.json"
    docker run --rm -v "${project}:/src" -w /src semgrep/semgrep:1.182.0 semgrep scan --metrics=off --quiet `
        --config p/java --config p/typescript --config p/owasp-top-ten `
        --exclude node_modules --exclude target --exclude dist --exclude .angular --exclude results --exclude .venv `
        --json --output /src/booksmith-data/saude/semgrep.json booksmith/src/main booksmith-web/src *> $null
    $data = Get-Content $report -Raw | ConvertFrom-Json
    $scanned = @($data.paths.scanned).Count
    $found = @($data.results).Count
    if ($scanned -lt 50) { "FALHOU - só $scanned arquivos lidos (a análise não rodou direito)" }
    elseif ($found -eq 0) { "ok - $scanned arquivos lidos, 0 achados" }
    else { "FALHOU - $found achados em $scanned arquivos (ver semgrep.json)" }
}

Step "Segredos no código e no histórico (Gitleaks)" {
    $lines = @(); $fail = $false
    foreach ($repo in "booksmith", "booksmith-web", "booksmith-tests") {
        $log = docker run --rm -v "$(Join-Path $project $repo):/repo" zricethezav/gitleaks:v8.30.1 git /repo --no-banner --redact 2>&1 | Out-String
        $commits = ([regex]::Match($log, '(\d+) commits scanned')).Groups[1].Value
        $clean = $log -match 'no leaks found'
        if (-not $commits -or [int]$commits -eq 0) { $fail = $true; $lines += "$repo NÃO LIDO" }
        elseif (-not $clean) { $fail = $true; $lines += "$repo COM SEGREDOS" ; $log | Out-File (Join-Path $out "gitleaks-$repo.log") }
        else { $lines += "$repo $commits commits" }
    }
    $summary = $lines -join ", "
    if ($fail) { "FALHOU - $summary" } else { "ok - 0 segredos ($summary)" }
}

$failed = @($results.GetEnumerator() | Where-Object { $_.Value -like "FALHOU*" }).Count
$report = Join-Path $out "relatorio-$stamp.md"
@("# Saúde do código - $(Get-Date -Format 'yyyy-MM-dd HH:mm')", "") + `
    ($results.GetEnumerator() | ForEach-Object { "- **$($_.Key)**: $($_.Value)" }) + `
    @("", "Resultado: $(if ($failed) { "$failed passo(s) com problema" } else { 'tudo ok' })") | Set-Content $report -Encoding utf8
Write-Host "`nRelatório: $report" -ForegroundColor $(if ($failed) { "Red" } else { "Green" })
exit $(if ($failed) { 1 } else { 0 })
