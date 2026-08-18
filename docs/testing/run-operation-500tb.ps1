param(
    [ValidateRange(16, 4096)]
    [int]$SampleMiB = 256,
    [string]$EvidenceRoot = "docs/testing/evidence/operation-500tb"
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$maven = if ($env:MAVEN_HOME) {
    Join-Path $env:MAVEN_HOME "bin/mvn.cmd"
} elseif (Test-Path "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd") {
    "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd"
} else {
    (Get-Command mvn.cmd -ErrorAction Stop).Source
}
$git = if (Test-Path "C:\Program Files\Git\cmd\git.exe") {
    "C:\Program Files\Git\cmd\git.exe"
} else {
    (Get-Command git.exe -ErrorAction Stop).Source
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDir = Join-Path $repo (Join-Path $EvidenceRoot $stamp)
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$commit = (& $git -C $repo rev-parse --short=12 HEAD).Trim()

Push-Location $repo
try {
    & $maven `
        "-Dtest=io.forgetdm.scale.Operation500TbQualificationTest" `
        "-Dforgetdm.scale.sampleMiB=$SampleMiB" `
        "-Dforgetdm.scale.evidenceDir=$evidenceDir" `
        "-Dforgetdm.scale.commit=$commit" `
        test
    if ($LASTEXITCODE -ne 0) { throw "Operation 500 TB qualification failed with exit code $LASTEXITCODE" }

    Copy-Item -LiteralPath (Join-Path $evidenceDir "operation-500tb-report.md") `
        -Destination (Join-Path $repo (Join-Path $EvidenceRoot "latest-report.md")) -Force
    Copy-Item -LiteralPath (Join-Path $evidenceDir "operation-500tb-report.json") `
        -Destination (Join-Path $repo (Join-Path $EvidenceRoot "latest-report.json")) -Force
    Write-Host "Operation 500 TB evidence: $evidenceDir"
} finally {
    Pop-Location
}
