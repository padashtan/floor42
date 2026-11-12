<#
.SYNOPSIS
  Floor42 from the terminal.

.DESCRIPTION
  floor42                       start the daemon if needed and open the office in your browser
  floor42 doctor                what this machine lacks for Floor42 (works with the daemon stopped; installs nothing)
  floor42 "objective..."        also propose a real mission on the git repository of the current directory

  The daemon runs from a copy of the build in ~/.floor42/app, so rebuilding the repository never fights a running
  daemon. Java 25 is required: set FLOOR42_JAVA or JAVA_HOME if the java on your PATH is older.
#>
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Objective)

$ErrorActionPreference = 'Stop'
$Base = 'http://127.0.0.1:7420'
$DataDir = Join-Path $HOME '.floor42'
$AppDir = Join-Path $DataDir 'app'
$Build = Join-Path $PSScriptRoot '..\floor42-server\target\quarkus-app'
# A release archive holds the build at its root.
if (Test-Path (Join-Path $PSScriptRoot '..\quarkus-app')) { $Build = Join-Path $PSScriptRoot '..\quarkus-app' }

# Office's name for it (before 1.5.0) still works, with a notice.
if (-not $env:FLOOR42_JAVA -and $env:OFFICE_JAVA) {
    Write-Warning 'OFFICE_JAVA is now FLOOR42_JAVA: using it.'
    $env:FLOOR42_JAVA = $env:OFFICE_JAVA
}

function Find-Java {
    # Windows PowerShell 5.1 turns the first stderr line of `java -version` into a terminating error under Stop.
    $ErrorActionPreference = 'Continue'
    foreach ($candidate in @($env:FLOOR42_JAVA, $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' }), 'java')) {
        if (-not $candidate) { continue }
        try {
            $version = & $candidate -version 2>&1 | Select-Object -First 1
            if ("$version" -match '"(\d+)') { if ([int]$Matches[1] -ge 25) { return $candidate } }
        } catch { }
    }
    throw 'Java 25 or later is required: set FLOOR42_JAVA to its java.exe.'
}

function Test-Daemon {
    try { Invoke-RestMethod "$Base/api/status" -TimeoutSec 2 | Out-Null; return $true } catch { return $false }
}

# floor42 doctor: what this machine lacks for Floor42, measured now, with or without the daemon. Installs nothing;
# exit code 0 when every required item is present, 1 otherwise.
if ($Objective -and $Objective.Count -eq 1 -and $Objective[0] -eq 'doctor') {
    $lib = if (Test-Path (Join-Path $Build 'quarkus-run.jar')) { $Build } else { $AppDir }
    if (-not (Test-Path (Join-Path $lib 'quarkus-run.jar'))) { throw 'No build found: run ./mvnw package first.' }
    $lib = (Resolve-Path $lib).Path
    $java = Find-Java
    & $java '--enable-native-access=ALL-UNNAMED' '-Djava.util.logging.manager=org.jboss.logmanager.LogManager' -cp "$lib\app\*;$lib\lib\boot\*;$lib\lib\main\*" dev.floor42.server.runtime.Doctor
    exit $LASTEXITCODE
}

if (-not (Test-Daemon)) {
    if (-not (Test-Path (Join-Path $Build 'quarkus-run.jar'))) { throw "No build found: run ./mvnw package first." }
    # The newest file of the build, not quarkus-run.jar alone: that small launcher stays the same when only the
    # application changes (the web assets, a class), and the old copy would keep running.
    $buildTime = (Get-ChildItem -Recurse -File $Build | Measure-Object -Property LastWriteTime -Maximum).Maximum
    $appJar = Join-Path $AppDir 'quarkus-run.jar'
    $appTime = if (Test-Path $AppDir) { (Get-ChildItem -Recurse -File $AppDir | Measure-Object -Property LastWriteTime -Maximum).Maximum }
    if (-not (Test-Path $appJar) -or $appTime -lt $buildTime) {
        if (Test-Path $AppDir) { Remove-Item -Recurse -Force $AppDir }
        New-Item -ItemType Directory -Force $DataDir | Out-Null
        Copy-Item -Recurse $Build $AppDir
    }
    $java = Find-Java
    Write-Host 'Starting the Floor42 daemon...'
    # The previous run's console output (a crash) survives this start: one generation kept.
    foreach ($n in 'daemon', 'daemon.err') { $f = Join-Path $DataDir "$n.log"; if (Test-Path $f) { Move-Item -Force $f (Join-Path $DataDir "$n.prev.log") } }
    # No dynamic attach (jcmd, agents): it does not stop a same-user process from reading memory on Windows (SECURITY.md).
    Start-Process -FilePath $java -ArgumentList @('-XX:+DisableAttachMechanism', '--enable-native-access=ALL-UNNAMED', '-Djdk.httpclient.allowRestrictedHeaders=host', '-jar', $appJar) -WorkingDirectory $DataDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $DataDir 'daemon.log') -RedirectStandardError (Join-Path $DataDir 'daemon.err.log')
    $deadline = (Get-Date).AddSeconds(40)
    while (-not (Test-Daemon)) {
        if ((Get-Date) -gt $deadline) { throw "The daemon did not start: see $DataDir\daemon.log" }
        Start-Sleep -Milliseconds 500
    }
}

if ($Objective) {
    # The director's token is in memory only (ADR-0034): proposing from the terminal needs a script token from the desk
    # (System > Sessions and tokens, scope missions:write, ADR-0044) in FLOOR42_TOKEN, or the deprecated token file.
    $tokenFile = Join-Path $DataDir 'session.token'
    if ($env:FLOOR42_TOKEN) {
        $token = $env:FLOOR42_TOKEN
    } elseif (Test-Path $tokenFile) {
        Write-Warning 'Using the deprecated director token file: issue a script token from the desk instead (FLOOR42_TOKEN).'
        $token = (Get-Content $tokenFile -Raw).Trim()
    } else {
        throw 'Proposing a mission from the terminal needs a script token: issue one from the desk (System > Sessions and tokens, scope missions:write) and set FLOOR42_TOKEN, or propose it from the desk.'
    }
    $text = ($Objective -join ' ').Trim()
    $ErrorActionPreference = 'Continue' # same 5.1 behaviour for git's stderr outside a repository
    $repository = (git rev-parse --show-toplevel 2>$null)
    if (-not $repository) { throw 'Run floor42 "..." from inside a git repository: agents work on it.' }
    $title = if ($text.Length -gt 60) { $text.Substring(0, 57) + '...' } else { $text }
    $body = @{ title = $title; objective = $text; repository = (Resolve-Path $repository).Path } | ConvertTo-Json
    $mission = Invoke-RestMethod "$Base/api/missions" -Method Post -ContentType 'application/json' -Body $body `
        -Headers @{ Authorization = "Bearer $token" }
    Write-Host "Mission $($mission.id) proposed on $($mission.workspace)."
    Write-Host "Review its plan and permissions in your desk (press D), then approve it."
}

# The daemon opens a single-use sign-in link in the default browser; the link never comes back here.
try {
    Invoke-RestMethod "$Base/auth/open" -Method Post | Out-Null
} catch {
    Write-Host "No sign-in link opened ($($_.Exception.Message)): if the office is already open, reload it."
}
