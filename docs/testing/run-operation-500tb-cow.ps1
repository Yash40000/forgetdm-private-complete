param(
    [ValidateRange(16, 4096)]
    [int]$SampleMiB = 256,
    [string]$EvidenceRoot = "docs/testing/evidence/operation-500tb-cow"
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
        "-Dtest=io.forgetdm.virtualization.Operation500TbCowQualificationTest" `
        "-Dforgetdm.cow.sampleMiB=$SampleMiB" `
        "-Dforgetdm.cow.evidenceDir=$evidenceDir" `
        "-Dforgetdm.cow.commit=$commit" `
        test
    if ($LASTEXITCODE -ne 0) { throw "Operation 500 TB COW qualification failed with exit code $LASTEXITCODE" }

    foreach ($name in @("operation-500tb-cow-report.md", "operation-500tb-cow-report.json")) {
        $source = Join-Path $evidenceDir $name
        if (-not (Test-Path -LiteralPath $source)) { throw "Missing COW evidence: $source" }
        $latest = "latest-" + ($name -replace '^operation-500tb-cow-', '')
        Copy-Item -LiteralPath $source -Destination (Join-Path $repo (Join-Path $EvidenceRoot $latest)) -Force
    }

    $report = Get-Content -LiteralPath (Join-Path $evidenceDir "operation-500tb-cow-report.json") -Raw | ConvertFrom-Json
    Write-Host "Operation 500 TB COW verdict: $($report.verdict)"
    Write-Host "Evidence: $evidenceDir"
} finally {
    Pop-Location
}
