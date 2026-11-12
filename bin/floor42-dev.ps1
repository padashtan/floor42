<#
.SYNOPSIS
  Floor42's fast loop: Quarkus dev mode, with Vite's dev server behind Quinoa.

.DESCRIPTION
  floor42-dev                 Java changes reload on the next request, UI changes reload in the page (HMR)
  floor42-dev -Test           also run the tests continuously: only those a change affects run again
  floor42-dev -RealClaude     sessions run the real Claude Code (paid); by default the fake plays them

  It listens on http://127.0.0.1:7421 with its own data in ~/.floor42-dev, next to an installed daemon on 7420
  (~/.floor42 is never touched). The sign-in link is printed in this console. Java 25 is required (JAVA_HOME).
#>
param(
    [switch] $Test,
    [switch] $RealClaude,
    [Parameter(ValueFromRemainingArguments = $true)] [string[]] $Rest
)

$ErrorActionPreference = 'Stop'
$Base = 'http://127.0.0.1:7421'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')

# The format is the full gate's business (make verify), not the loop's.
$mvnArgs = @('-pl', 'floor42-server', '-am', 'quarkus:dev', '-Dspotless.check.skip=true')
if ($RealClaude) { $mvnArgs += '-Dfloor42.claude.command=claude', '-Dfloor42.sandbox.mandatory=true' }
if ($Test) { $mvnArgs += '-Dquarkus.test.continuous-testing=enabled' }
# No dynamic attach into the dev daemon either (SECURITY.md); FLOOR42_DEV_ATTACH=1 allows it, for jcmd or a profiler.
$jvmArgs = '-Djdk.httpclient.allowRestrictedHeaders=host' # the token broker pins the checked address and sets Host
if (-not $env:FLOOR42_DEV_ATTACH) { $jvmArgs = "-XX:+DisableAttachMechanism $jvmArgs" }
$mvnArgs += "-Djvm.args=$jvmArgs"
if ($Rest) { $mvnArgs += $Rest }

if (-not (Test-Path (Join-Path $Root 'floor42-web\node_modules'))) {
    Push-Location (Join-Path $Root 'floor42-web'); try { npm ci } finally { Pop-Location }
}

# Once the daemon answers, ask it for a sign-in link: dev mode prints it on its console (floor42.sign-in.delivery).
$signIn = Start-Job -ArgumentList $Base -ScriptBlock {
    param($Base)
    for ($i = 0; $i -lt 300; $i++) {
        try {
            Invoke-RestMethod "$Base/api/status" -TimeoutSec 2 | Out-Null
            try { Invoke-RestMethod -Method Post "$Base/auth/open" -TimeoutSec 5 | Out-Null } catch { }
            return
        } catch { Start-Sleep -Seconds 1 }
    }
}

Push-Location $Root
try {
    & (Join-Path $Root 'mvnw.cmd') @mvnArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
    Stop-Job $signIn -ErrorAction SilentlyContinue
    Remove-Job $signIn -Force -ErrorAction SilentlyContinue
}
