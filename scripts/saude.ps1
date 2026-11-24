<#
.SYNOPSIS
  Saúde do código do Booksmith: arquitetura, código limpo, segurança e dependências, num comando só.

.DESCRIPTION
  Roda, nesta ordem:
    1. Backend  - mvnw verify: testes, ArchUnit (camadas), Spring Modulith (módulos), PMD (código limpo, bloqueia
                  prioridade 1-2), CPD (duplicação), SpotBugs + Find Security Bugs, JaCoCo (cobertura)
    2. Frontend - ng lint (ESLint: código limpo, segurança, arquitetura) e testes (Vitest) do app e do backoffice
    3. Origem das dependências - contra pacote falso (slopsquatting/typosquatting)
    4. OSV       - dependências com falha de segurança conhecida (riscos aceitos em config/osv-aceitos.txt)
    5. Semgrep   - segurança no código Java e TypeScript (regras públicas, --metrics=off)
    6. Gitleaks  - segredos no histórico do Git dos quatro repositórios e nas mudanças ainda não commitadas
  Cada passo diz quanto LEU, não só quanto achou: um "zero" que não leu nada não conta como aprovado.
  Relatório em booksmith-data/saude/ (com o tempo de cada passo). Sai com código 1 se algum passo falhar.

  Para ser rápido sem perder o que importa:
    - Origem e OSV rodam em paralelo com o backend (são leves: quase só rede) e, se as dependências não mudaram
      desde a última conferência aprovada no mesmo dia, não repetem;
    - Semgrep lê só os arquivos que mudaram desde a última análise aprovada; Gitleaks, só os commits novos e as
      mudanças ainda não commitadas;
    - uma vez por semana (ou com -Completo), tudo inteiro. Backend e testes do app rodam sempre inteiros.
  O que foi aprovado fica em booksmith-data/saude/estado.json.

.EXAMPLE
  .\scripts\saude.ps1
  .\scripts\saude.ps1 -Rapido     # pula os testes (só análise)
  .\scripts\saude.ps1 -Completo   # Semgrep, Gitleaks e dependências por inteiro
#>
param([switch]$Rapido, [switch]$Completo)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent                 # booksmith (API)
$project = Split-Path $root -Parent                       # booksmith-project
$web = Join-Path $project "booksmith-web"
$admin = Join-Path $project "booksmith-admin"
$tests = Join-Path $project "booksmith-tests"
$out = Join-Path $project "booksmith-data\saude"
New-Item -ItemType Directory -Force $out | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
# JDK 25: o portátil em tools\jdk-25 (oficial, conferido) quando existe; senão o instalado
$portable = Join-Path $project "tools\jdk-25"
$env:JAVA_HOME = if (Test-Path (Join-Path $portable "bin\java.exe")) { $portable } else { "C:\Program Files\Java\latest\jdk-25" }
$results = [ordered]@{}
$total = [System.Diagnostics.Stopwatch]::StartNew()

# O que já foi aprovado (para repetir só o que mudou)
$stateFile = Join-Path $out "estado.json"
# ConvertFrom-Json devolve objeto; vira hashtable (funciona no Windows PowerShell e no PowerShell 7)
function ToTable($value) {
    if ($value -is [System.Management.Automation.PSCustomObject]) {
        $table = @{}
        foreach ($p in $value.PSObject.Properties) { $table[$p.Name] = ToTable $p.Value }
        return $table
    }
    return $value
}
$state = if (Test-Path $stateFile) { ToTable (Get-Content $stateFile -Raw | ConvertFrom-Json) } else { @{} }
foreach ($k in "gitleaks", "semgrep") { if (-not $state[$k]) { $state[$k] = @{} } }
$today = Get-Date -Format "yyyy-MM-dd"
# O PowerShell 7 já devolve a data do JSON como data; o Windows PowerShell, como texto ISO
$lastFull = [datetime]::MinValue
if ($state["full"] -is [datetime]) { $lastFull = $state["full"] }
elseif ($state["full"]) {
    $lastFull = [datetime]::Parse([string]$state["full"], [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind)
}
$full = $Completo -or ((Get-Date) - $lastFull).TotalDays -ge 7
if ($full) { Write-Host "Varredura completa (Semgrep, Gitleaks e dependências por inteiro)" -ForegroundColor Yellow }

function Elapsed($watch) {
    $t = $watch.Elapsed
    if ($t.TotalMinutes -ge 1) { "{0}min {1:00}s" -f [int][math]::Floor($t.TotalMinutes), $t.Seconds } else { "{0}s" -f [int]$t.TotalSeconds }
}

function Step($name, [scriptblock]$body) {
    Write-Host "`n== $name" -ForegroundColor Cyan
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try { $value = & $body } catch { $value = "FALHOU: $($_.Exception.Message)" }
    $results[$name] = "$value [$(Elapsed $watch)]"
    Write-Host "   $($results[$name])"
}

function Hash($paths) {
    $text = ($paths | Where-Object { Test-Path $_ } | ForEach-Object { (Get-FileHash $_ -Algorithm SHA256).Hash }) -join ""
    $sha = [System.Security.Cryptography.SHA256]::Create()
    return [System.BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))).Replace("-", "")
}

# Dependências: em paralelo com o backend, a não ser que já tenham sido conferidas hoje, iguais
$depFiles = @((Join-Path $root "pom.xml"), (Join-Path $web "package.json"), (Join-Path $web "package-lock.json"),
    (Join-Path $admin "package.json"), (Join-Path $admin "package-lock.json"),
    (Join-Path $tests "requirements.txt"), (Join-Path $root "config\osv-aceitos.txt"))
$depsHash = Hash $depFiles
$depsCached = -not $full -and $state["deps"] -eq "$today|$depsHash"
$depsJob = $null
if (-not $depsCached) {
    $depsJob = Start-Job -ArgumentList $root, $web, $admin, $tests, $out, $env:JAVA_HOME -ScriptBlock {
        param($root, $web, $admin, $tests, $out, $javaHome)
        # O app e o backoffice: os dois projetos npm, separados por ";"
        $packages = (Join-Path $web "package.json") + ";" + (Join-Path $admin "package.json")
        $locks = (Join-Path $web "package-lock.json") + ";" + (Join-Path $admin "package-lock.json")
        $env:JAVA_HOME = $javaHome
        $env:PYTHONIOENCODING = "utf-8"   # sem isso, o log sai com os acentos trocados ("dependÛncias")
        [Console]::OutputEncoding = [Text.Encoding]::UTF8
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        python (Join-Path $root "scripts\dependencias-origem.py") (Join-Path $root "pom.xml") $packages `
            $locks (Join-Path $tests "requirements.txt") *> (Join-Path $out "origem.log")
        $originOk = $LASTEXITCODE -eq 0
        $originTime = [int]$watch.Elapsed.TotalSeconds
        $watch.Restart()
        $deps = Join-Path $out "maven-deps.txt"
        Push-Location $root
        & .\mvnw.cmd -q dependency:list "-DoutputFile=$deps" -DincludeScope=test *> $null
        Pop-Location
        python (Join-Path $root "scripts\osv-check.py") $deps $locks (Join-Path $tests "requirements.txt") `
            (Join-Path $root "config\osv-aceitos.txt") (Join-Path $out "osv.txt") *> $null
        @{ originOk = $originOk; originTime = $originTime; osvOk = ($LASTEXITCODE -eq 0); osvTime = [int]$watch.Elapsed.TotalSeconds }
    }
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

Step "Frontend (ESLint e testes do app e do backoffice)" {
    Push-Location $web
    npx ng lint *> (Join-Path $out "lint.log")
    $lint = $LASTEXITCODE -eq 0
    $test = $true
    if (-not $Rapido) { npx ng test --watch=false *> (Join-Path $out "frontend-testes.log"); $test = $LASTEXITCODE -eq 0 }
    Pop-Location
    Push-Location $admin
    npx ng lint *> (Join-Path $out "lint-admin.log")
    $lint = $lint -and $LASTEXITCODE -eq 0
    if (-not $Rapido) { npx ng test --watch=false *> (Join-Path $out "admin-testes.log"); $test = $test -and $LASTEXITCODE -eq 0 }
    Pop-Location
    $warn = (Select-String -Path (Join-Path $out "lint.log") -Pattern "warning" -SimpleMatch).Count
    if ($lint -and $test) { "ok ($warn avisos de complexidade)" } else { "FALHOU (lint: $lint, testes: $test; ver lint.log)" }
}

$depsResult = if ($depsJob) { Receive-Job $depsJob -Wait -AutoRemoveJob | Select-Object -Last 1 } else { $null }

Step "Origem das dependências (pacote falso)" {
    $last = (Get-Content (Join-Path $out "origem.log") | Select-Object -Last 1)
    if ($depsCached) { "ok - $last (dependências iguais às conferidas hoje)" }
    elseif ($depsResult.originOk) { "ok - $last (em paralelo, $($depsResult.originTime)s)" }
    else { "FALHOU - $last (ver origem.log)" }
}

Step "Dependências com falha conhecida (OSV)" {
    $first = (Get-Content (Join-Path $out "osv.txt") | Select-Object -First 1)
    if ($depsCached) { "ok - $first (dependências iguais às conferidas hoje)" }
    elseif ($depsResult.osvOk) {
        # Só fica no cache do dia com as duas conferências aprovadas (a origem também)
        if ($depsResult.originOk) { $state["deps"] = "$today|$depsHash" }
        "ok - $first (em paralelo, $($depsResult.osvTime)s)"
    }
    else { "FALHOU - $first (ver osv.txt)" }
}

# Arquivos de código que mudaram desde o commit dado (inclusive os ainda não commitados), relativos ao projeto
function Changed($repo, $since, $folder) {
    $dir = Join-Path $project $repo
    $files = @(git -C $dir diff --name-only $since 2>$null) + @(git -C $dir ls-files --others --exclude-standard 2>$null)
    $files | Where-Object { $_ -like "$folder/*" -and $_ -match '\.(java|ts|html)$' -and (Test-Path (Join-Path $dir $_)) } |
        Sort-Object -Unique | ForEach-Object { "$repo/$_" }
}

Step "Segurança no código (Semgrep)" {
    $report = Join-Path $out "semgrep.json"
    $heads = @{ "booksmith" = (git -C $root rev-parse HEAD); "booksmith-web" = (git -C $web rev-parse HEAD);
        "booksmith-admin" = (git -C $admin rev-parse HEAD) }
    $targets = @("booksmith/src/main", "booksmith-web/src", "booksmith-admin/src")
    $partial = $false
    if (-not $full -and $state["semgrep"]["booksmith"] -and $state["semgrep"]["booksmith-web"]) {
        $targets = @(Changed "booksmith" $state["semgrep"]["booksmith"] "src/main") + @(Changed "booksmith-web" $state["semgrep"]["booksmith-web"] "src")
        # Backoffice: o que mudou desde a última análise ou, na primeira vez, tudo
        $targets += if ($state["semgrep"]["booksmith-admin"]) { @(Changed "booksmith-admin" $state["semgrep"]["booksmith-admin"] "src") } else { @("booksmith-admin/src") }
        $partial = $true
    }
    if ($partial -and $targets.Count -eq 0) {
        "ok - nenhum arquivo de código mudou desde a última análise"
    } else {
        docker run --rm -v "${project}:/src" -w /src semgrep/semgrep:1.182.0 semgrep scan --metrics=off --quiet `
            --config p/java --config p/typescript --config p/owasp-top-ten `
            --exclude node_modules --exclude target --exclude dist --exclude .angular --exclude results --exclude .venv `
            --json --output /src/booksmith-data/saude/semgrep.json @targets *> $null
        $data = Get-Content $report -Raw | ConvertFrom-Json
        $scanned = @($data.paths.scanned).Count
        $found = @($data.results).Count
        $minimum = if ($partial) { [math]::Min(1, $targets.Count) } else { 50 }
        if ($scanned -lt $minimum) { "FALHOU - só $scanned arquivos lidos (a análise não rodou direito)" }
        elseif ($found -eq 0) {
            # Aprovado até o commit de agora; o que não está commitado entra de novo na próxima
            $state["semgrep"]["booksmith"] = $heads["booksmith"]; $state["semgrep"]["booksmith-web"] = $heads["booksmith-web"]
            $state["semgrep"]["booksmith-admin"] = $heads["booksmith-admin"]
            "ok - $scanned arquivos lidos$(if ($partial) { ' (os que mudaram)' }), 0 achados"
        }
        else { "FALHOU - $found achados em $scanned arquivos (ver semgrep.json)" }
    }
}

Step "Segredos no código e no histórico (Gitleaks)" {
    $lines = @(); $fail = $false
    foreach ($repo in "booksmith", "booksmith-web", "booksmith-admin", "booksmith-tests") {
        $dir = Join-Path $project $repo
        $head = git -C $dir rev-parse HEAD
        $since = $state["gitleaks"][$repo]
        $image = "zricethezav/gitleaks:v8.30.1"
        # Histórico: só os commits novos desde a última varredura aprovada (inteiro na completa)
        # @(...) por fora: com um item só, o PowerShell viraria texto e o splatting passaria letra por letra
        $range = @(if (-not $full -and $since) { "--log-opts=$since..HEAD" })
        if ($range -and $since -eq $head) {
            $commits = 0; $historyClean = $true; $log = ""
        } else {
            $log = docker run --rm -v "${dir}:/repo" $image git /repo --no-banner --redact @range 2>&1 | Out-String
            $commits = [int]([regex]::Match($log, '(\d+) commits scanned')).Groups[1].Value
            $historyClean = $log -match 'no leaks found'
        }
        # Mudanças ainda não commitadas (o que o portão protege antes do commit)
        $pending = docker run --rm -v "${dir}:/repo" $image git /repo --pre-commit --no-banner --redact 2>&1 | Out-String
        $pendingClean = $pending -match 'no leaks found'
        if (-not $range -and $commits -eq 0) { $fail = $true; $lines += "$repo NÃO LIDO" }
        elseif (-not $historyClean -or -not $pendingClean) {
            $fail = $true; $lines += "$repo COM SEGREDOS"; ($log + $pending) | Out-File (Join-Path $out "gitleaks-$repo.log")
        }
        else {
            $state["gitleaks"][$repo] = $head
            $lines += if ($range) { "$repo $commits commits novos" } else { "$repo $commits commits" }
        }
    }
    $summary = ($lines -join ", ") + " + mudanças não commitadas"
    if ($fail) { "FALHOU - $summary" } else { "ok - 0 segredos ($summary)" }
}

$failed = @($results.GetEnumerator() | Where-Object { $_.Value -like "FALHOU*" }).Count
if (-not $failed -and $full) { $state["full"] = (Get-Date).ToString("o") }
$state | ConvertTo-Json -Depth 4 | Set-Content $stateFile -Encoding utf8
Write-Host "`nTempo total: $(Elapsed $total)" -ForegroundColor Cyan
$report = Join-Path $out "relatorio-$stamp.md"
@("# Saúde do código - $(Get-Date -Format 'yyyy-MM-dd HH:mm')", "") + `
    ($results.GetEnumerator() | ForEach-Object { "- **$($_.Key)**: $($_.Value)" }) + `
    @("", "Resultado: $(if ($failed) { "$failed passo(s) com problema" } else { 'tudo ok' }) em $(Elapsed $total)$(if ($full) { ' (completa)' })") | Set-Content $report -Encoding utf8
Write-Host "`nRelatório: $report" -ForegroundColor $(if ($failed) { "Red" } else { "Green" })
exit $(if ($failed) { 1 } else { 0 })
