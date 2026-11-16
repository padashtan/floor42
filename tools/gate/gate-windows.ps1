# Windows half of the double gate (docs/releases.md): one heavy job at a time, on the checkout that holds this script.
# Logs in target/gate/. Last line: GATE GREEN|RED <host> <sha> <minutes>. Takes about 40 minutes; run nothing else heavy.
param([int]$Forks = 2, [string]$Heap = '2g')
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path "$PSScriptRoot\..\..").Path
Set-Location $Root
$L = "$Root\target\gate"; New-Item -ItemType Directory -Force $L | Out-Null
$Sha = (git rev-parse --short HEAD); $T0 = Get-Date
$Tune = "-Dfloor42.test.forks=$Forks", "-Dfloor42.test.heap=$Heap"
# Test failures are ignored at Maven level only so every step runs; the verdict below reads the summaries.
$Keep = "-Dmaven.test.failure.ignore=true"

function Step($name, [scriptblock]$cmd) {
  $s = Get-Date; & $cmd *> "$L\$name.log"
  "$name exit $LASTEXITCODE in $([int]((Get-Date) - $s).TotalSeconds) s"
}
function Failed($name) {
  Select-String '<<< (FAIL|ERR)' "$L\$name.log" | Where-Object { $_.Line -match ' -- in ' } |
    ForEach-Object { $_.Line -replace '.* -- in ', '' } | Sort-Object -Unique
}

"windows at $(git log --oneline -1)"
Step verify { ./mvnw -q -B verify $Keep @Tune }
Step slow { ./mvnw -q -B -pl floor42-server -am test -Pslow '-Dspotless.check.skip=true' $Keep @Tune }
Step package { ./mvnw -q -B package -DskipTests }
Push-Location floor42-web
Step e2e { npx playwright test }
Pop-Location

$bad = @('verify', 'slow') | ForEach-Object { Failed $_ } | Sort-Object -Unique
$red = (Select-String 'Tests run:.*(Failures|Errors): [1-9]' "$L\verify.log", "$L\slow.log" -Quiet) -or
  (Select-String '^ +[0-9]+ failed' "$L\e2e.log" -Quiet) -or -not (Select-String '[0-9]+ passed' "$L\e2e.log" -Quiet)
if ($bad) { "failing classes:"; $bad | ForEach-Object { "  $_" } }
Get-Content "$L\e2e.log" -Tail 5
$min = [int]((Get-Date) - $T0).TotalMinutes
"GATE $(if ($red) { 'RED' } else { 'GREEN' }) $env:COMPUTERNAME $Sha $min"
exit [int]$red
